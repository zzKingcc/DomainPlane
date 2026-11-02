package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSwappable;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 知识检索工具的<b>可用域</b>绑定器：把「域绑了哪个知识体」翻译成「哪些域能看到 searchKnowledge」。
 *
 * <p>为什么这件事不能由注解表达：工具的可见性由 {@code ToolDescriptor.domains()} 决定，而注解是
 * 编译期常量。知识检索工具的归属完全取决于用户此刻的配置——<b>绑了可用知识体的域才有它</b>。
 * 写死成根域就是"每个域都能调一个可能根本不存在的知识库"，写死成某个域就是"换个域就得改代码"。</p>
 *
 * <p><b>三个刷新时点</b>（都只是"重算一遍再整体覆盖"，没有增量动作，多跑几次也无害）：</p>
 * <ol>
 *   <li>启动：{@code @PostConstruct} 里算第一次；</li>
 *   <li>容器就绪：{@link ApplicationReadyEvent} 再算一次 —— {@code @PostConstruct} 那一轮
 *       跑得太早：{@link DomainStore} 的读盘发生在 {@code DomainConfiguration} 建
 *       {@code DomainRegistry} 的时候，与本类的初始化<b>没有依赖边</b>，谁先谁后由 bean
 *       定义顺序决定。抢在前面就会读到一份空的绑定表，于是首次声明落成"谁都不可见"，
 *       而此后只要没人改配置就再没人来纠正它——工具静默地一直不出现；</li>
 *   <li>域配置变更：{@link DomainStore#onChange} —— 绑定、解绑、删域、回滚都走落盘，一处就够；</li>
 *   <li>存储配置保存：实现 {@link InfraSwappable} —— 知识体被删、被改、连接被删都可能让一批域
 *       从"有"变"没有"。</li>
 * </ol>
 *
 * <p><b>判定口径</b>：域 d 有工具 ⟺ d 绑了知识体 ∧ 该体存在 ∧ 至少一路的连接可用
 * （{@link InfraSettings#usableBody}）。一路就够——向量库还在、关键字体那台停了，
 * 这个域仍该能检索，走剩下的那一路。</p>
 *
 * <p>声明写在<b>域自己</b>上，靠 {@code ToolDescriptor.visibleIn} 的累加语义让子域自动可见：
 * 子域确实查得到父域的数据（检索按域链展开），所以它也该有这个工具。</p>
 *
 * @author zzkingcc
 */
@Component
public class KnowledgeToolBinder implements InfraSwappable {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeToolBinder.class);

    private final ToolRegistry registry;
    private final DomainStore domainStore;
    private final InfraSettingsHolder infra;

    public KnowledgeToolBinder(ToolRegistry registry, DomainStore domainStore, InfraSettingsHolder infra) {
        this.registry = registry;
        this.domainStore = domainStore;
        this.infra = infra;
    }

    @PostConstruct
    void start() {
        // 先挂监听再算第一次：算的过程中若有并发保存，监听已经在了，不会漏
        domainStore.onChange(this::refresh);
        infra.register(this);
        refresh();
    }

    /** 存储配置保存后由 {@link InfraSettingsHolder#apply} 回调 */
    @Override
    public void swap() {
        refresh();
    }

    /**
     * 重算并覆盖工具可用域。
     *
     * <p>不抛异常：调用点在"保存域配置/保存存储配置"的收尾处，这里失败不该让用户的保存失败。
     * {@link DomainStore} 与 {@link InfraSettingsHolder} 各自也会兜一层。</p>
     */
    public void refresh() {
        List<String> visible = visibleDomains();
        try {
            boolean found = registry.declareDomains(KnowledgeSearchTool.TOOL_NAME, visible);
            if (!found) {
                log.debug("[知识工具] 注册表里还没 {}，跳过本次绑定（启动期正常现象）",
                        KnowledgeSearchTool.TOOL_NAME);
            }
        } catch (RuntimeException e) {
            log.warn("[知识工具] 更新 {} 的可用域失败：{}", KnowledgeSearchTool.TOOL_NAME, e.getMessage());
        }
    }

    /**
     * 此刻该有检索工具的域（已排序，供日志与排障）。
     *
     * <p>绑了体但体不可用的域<b>不在这里</b>，另由 {@link #danglingDomains()} 报出来——
     * "配了但坏了"和"没配"对用户是两件事：前者要去修，后者本来就该没有知识库。</p>
     */
    public List<String> visibleDomains() {
        InfraSettings settings = infra.current();
        List<String> bound = new ArrayList<>();
        for (Map.Entry<String, String> entry : domainStore.bodies().entrySet()) {
            if (settings.usableBody(entry.getValue()) != null) {
                bound.add(entry.getKey());
            }
        }
        Collections.sort(bound);
        return bound;
    }

    /** 绑了知识体、但那个体此刻不可用的域（管控台靠 bodyMissing 提示，这里只用于日志） */
    public List<String> danglingDomains() {
        InfraSettings settings = infra.current();
        List<String> dangling = new ArrayList<>();
        for (Map.Entry<String, String> entry : domainStore.bodies().entrySet()) {
            if (settings.usableBody(entry.getValue()) == null) {
                dangling.add(entry.getKey());
            }
        }
        Collections.sort(dangling);
        return dangling;
    }
}
