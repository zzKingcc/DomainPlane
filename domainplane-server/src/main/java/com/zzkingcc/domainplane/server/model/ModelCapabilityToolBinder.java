package com.zzkingcc.domainplane.server.model;

import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import com.zzkingcc.domainplane.server.capability.ImageGenTool;
import com.zzkingcc.domainplane.server.capability.SpeechTool;
import com.zzkingcc.domainplane.server.capability.TranscribeTool;
import com.zzkingcc.domainplane.server.capability.VideoGenTool;
import com.zzkingcc.domainplane.server.vision.VisionDelegateTool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力工具的<b>可用域</b>绑定器：把「域绑了哪族模型」翻译成「哪些域能看到对应的能力工具」。
 *
 * <p>与知识检索工具（{@code KnowledgeToolBinder}）同一个问题、同一套解法：工具的可见性由
 * {@code ToolDescriptor.domains()} 决定，而注解是编译期常量；能力工具的归属完全取决于
 * 用户此刻的模型配置——<b>域链上解析得出可用能力档案的域才有对应的工具</b>。
 * 写死成根域就是"每个域都能调一个根本不存在的生图模型"，模型只会白调一次再把
 * "未配置"转述给用户。</p>
 *
 * <p><b>覆盖的工具与判据</b>（判据与执行路径逐字对齐，两处口径分叉就会出现
 * "看得见工具却调用失败"或"明明能用却看不见工具"）：</p>
 * <ul>
 *   <li>{@code generateImage} / {@code synthesizeSpeech} / {@code transcribeAudio}：
 *       族解析（{@link ModelProfileRegistry#resolveCapability}）的别名里存在
 *       可用（{@code isUsable()}）且确实声明了该端点族的档案；</li>
 *   <li>{@code generateVideo} + {@code checkVideo}：同一 video 族判据，<b>同进同退</b>——
 *       单独放出查询工具会出现"能查任务却不能提交任务"的怪状态；</li>
 *   <li>{@code seeImage}：主模型绑定链上有可用档案（有"主模型"）∧ 其中存在声明了
 *       图片输入的档案——即 {@code DefaultModelResolver#visionModel} 两趟扫描的纯读版。</li>
 * </ul>
 *
 * <p><b>判定刻意不构建客户端</b>：全部判据只读 {@link ModelProfileSettings}
 * （别名 → 档案的存在性 / 可用性 / 端点族 / 输入模态），不碰 {@code ModelClientFactory}。
 * refresh 每次对全树重算，走工厂等于每改一次配置就构建一批模型客户端。</p>
 *
 * <p><b>刷新时点</b>（都只是"重算一遍再整体覆盖"，幂等，多跑几次无害）：</p>
 * <ol>
 *   <li>启动：{@code @PostConstruct}。此时工具已注册完毕（{@code ToolRegistry} bean
 *       创建时就完成了全部扫描注册，本类构造依赖它），不存在"注册表里还没有工具"的时序问题；</li>
 *   <li>模型配置变更：{@link ModelProfileRegistry#onChange} —— 五个写入口
 *       （save / delete / bindDomain / setEmbeddingAlias / unbindDomains）都收敛到
 *       落盘，一处监听全覆盖。</li>
 * </ol>
 *
 * <p>不监听 {@code DomainStore.onChange} 也不实现 {@code InfraSwappable}：
 * 能力绑定存于模型配置、不存域配置，也不依赖存储连接——域的增删改查本身
 * 不改变"哪个域绑了哪族模型"这个事实。删域时模型绑定的级联清理
 * （{@code unbindDomains → persist}）会走上面的监听，可见性随之回收。</p>
 *
 * <p>声明写在<b>绑定所在域</b>上，子域按 {@code visibleIn} 累加语义自动可见——
 * 与族解析的沿链回落（{@code resolveCapabilityAlong}）天然一致：
 * 父域绑了生图，子域解析得到，子域也该看见工具。</p>
 *
 * @author zzkingcc
 */
@Component
public class ModelCapabilityToolBinder {

    private static final Logger log = LoggerFactory.getLogger(ModelCapabilityToolBinder.class);

    /** 端点族 → 该族的工具（族内所有工具共用同一判据；video 是两个工具） */
    private static final Map<String, List<String>> FAMILY_TOOLS = buildFamilyTools();

    private static Map<String, List<String>> buildFamilyTools() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(ModelProfile.EP_IMAGES, List.of(ImageGenTool.TOOL_NAME));
        map.put(ModelProfile.EP_TTS, List.of(SpeechTool.TOOL_NAME));
        map.put(ModelProfile.EP_ASR, List.of(TranscribeTool.TOOL_NAME));
        map.put(ModelProfile.EP_VIDEO, List.of(VideoGenTool.SUBMIT_TOOL, VideoGenTool.CHECK_TOOL));
        return Collections.unmodifiableMap(map);
    }

    private final ToolRegistry registry;
    private final ModelProfileRegistry profiles;

    public ModelCapabilityToolBinder(ToolRegistry registry, ModelProfileRegistry profiles) {
        this.registry = registry;
        this.profiles = profiles;
    }

    @PostConstruct
    void start() {
        // 先挂监听再算第一次：算的过程中若有并发保存，监听已经在了，不会漏
        profiles.onChange(this::refresh);
        refresh();
    }

    /**
     * 重算并覆盖全部能力工具的可用域。
     *
     * <p>不抛异常：调用点在"保存模型配置"的收尾处，这里失败不该让用户的保存失败
     * （{@link ModelProfileRegistry} 侧也会兜一层）。</p>
     */
    public void refresh() {
        try {
            Map<String, ModelProfileSettings.DomainModels> bindings = profiles.domainBindings();
            FAMILY_TOOLS.forEach((family, tools) -> {
                List<String> visible = visibleDomainsForFamily(bindings.keySet(), family);
                tools.forEach(tool -> declare(tool, visible));
            });
            declare(VisionDelegateTool.TOOL_NAME, visibleDomainsForVision(bindings.keySet()));
        } catch (RuntimeException e) {
            log.warn("[能力工具] 更新能力工具可用域失败：{}", e.getMessage());
        }
    }

    /**
     * 单个工具的声明收口：注册表里没有该工具（自定义注册表裁掉了它）只降级为 debug。
     *
     * <p>先 {@code find} 再声明而不是直接调 {@code declareDomains}：后者对"工具不存在"
     * 会打一条 WARN，而裁掉能力工具的部署每保存一次配置就打 6 条——排障信息成了噪音，
     * 真出问题时反而没人看。</p>
     */
    private void declare(String toolName, List<String> visible) {
        if (registry.find(toolName).isEmpty()) {
            log.debug("[能力工具] 注册表里还没 {}，跳过本次绑定", toolName);
            return;
        }
        try {
            registry.declareDomains(toolName, visible);
        } catch (RuntimeException e) {
            log.warn("[能力工具] 更新 {} 的可用域失败：{}", toolName, e.getMessage());
        }
    }

    /**
     * 该域链上此族是否存在可用档案——{@code DefaultModelResolver#endpointModel} 判据的纯读版：
     * 档案存在 ∧ {@code isUsable()} ∧ 确实声明了该端点族。列表里第一个合格的会被执行路径取用，
     * 这里只问"有没有"，逐个试错的事留给执行。
     */
    private boolean familyUsable(String domain, String family) {
        ModelProfileSettings.CapabilityBinding binding = profiles.resolveCapability(domain, family);
        if (binding.empty()) {
            return false;
        }
        for (String alias : binding.aliases()) {
            ModelProfile profile = profiles.profile(alias).orElse(null);
            if (profile != null && profile.isUsable() && profile.endpoints().contains(family)) {
                return true;
            }
        }
        return false;
    }

    /** 绑定域里该族可用的（已排序，供日志与排障） */
    private List<String> visibleDomainsForFamily(Collection<String> bindingDomains, String family) {
        List<String> visible = new ArrayList<>();
        for (String domain : bindingDomains) {
            if (familyUsable(domain, family)) {
                visible.add(domain);
            }
        }
        Collections.sort(visible);
        return visible;
    }

    /**
     * 该域链上是否具备视觉委派条件——{@code DefaultModelResolver#visionModel} 的纯读版：
     * 主模型绑定链非空 ∧ 里面有可用对话档案（有"主模型"）∧ 里面有声明了图片输入的档案
     * （有"看图的"）。两个角色可以是一个档案，也可以是两个。
     */
    private boolean visionUsable(String domain) {
        ModelProfileSettings.DomainBinding binding = profiles.resolve(domain);
        if (binding.empty()) {
            return false;
        }
        boolean hasPrimary = false;
        boolean hasVision = false;
        for (String alias : binding.aliases()) {
            ModelProfile profile = profiles.profile(alias).orElse(null);
            if (profile == null || !profile.isUsable() || !profile.domainBindable()) {
                continue;
            }
            hasPrimary = true;
            if (profile.input().contains(ModelProfile.MOD_IMAGE)) {
                hasVision = true;
                break;
            }
        }
        return hasPrimary && hasVision;
    }

    private List<String> visibleDomainsForVision(Collection<String> bindingDomains) {
        List<String> visible = new ArrayList<>();
        for (String domain : bindingDomains) {
            if (visionUsable(domain)) {
                visible.add(domain);
            }
        }
        Collections.sort(visible);
        return visible;
    }
}
