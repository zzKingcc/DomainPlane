package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.runtime.domain.DomainRegistry;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 域装配：把域注册表装进内核，并在启动期恢复管控台声明的域与它们的可调用性。
 *
 * @author zzkingcc
 */
@Configuration
public class DomainConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DomainConfiguration.class);

    /**
     * 域注册表：根域 default 由构造期预置（恒为可调用单元），管控台声明的域从落盘恢复。
     *
     * <p>恢复时<b>只按条目显式给定的可调用性</b>，沿链补齐出来的祖先一律是装配节点。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public DomainRegistry domainRegistry(DomainStore domainStore) {
        DomainRegistry registry = new DomainRegistry();
        DomainStore.StoredDomains stored = domainStore.load();
        registry.loadManual(stored.callables());
        if (stored.status() == DomainStore.StoredDomains.Status.CORRUPT) {
            log.error("[域注册表] 配置文件损坏且无法解析（{}），已用空声明初始化注册表："
                            + "人工创建的域本轮不可用，但服务照常启动，且不会把这份空配置写回磁盘",
                    domainStore.filePath());
        }
        log.info("[域注册表] 初始化完成：已登记 {} 个域 {}，其中可调用 {} 个 {}；"
                        + "另有工具声明派生的域由注册表侧维护",
                registry.ids().size(), registry.ids(),
                registry.callableIds().size(), registry.callableIds());
        return registry;
    }

    /**
     * 一次性归一：把"当前已经有子域"的域的落盘标记改成装配节点。
     *
     * <p><b>叶子规则本身不靠这里保证</b> —— 它由 {@link DomainRegistry#isCallable} 在读取时派生
     * （显式标记 ∧ 无子域），无论标记从哪来都越不过去。本迁移只是把<b>历史落盘值</b>
     * 一次性对齐成生效值，免得文件里留着一堆与实际角色不符的 {@code true}。</p>
     *
     * <p>只跑一次（落盘的 {@code callableMigrated} 为真即跳过）。</p>
     */
    @Bean
    public SmartInitializingSingleton domainCallableMigration(DomainRegistry domainRegistry,
                                                             DomainStore domainStore) {
        return () -> {
            // 自检类的 bean 一律不该把启动带崩：这里是唯一没有 try/catch 的一个，
            // 而配置目录在容器里常常是只读挂载 —— 迁移失败应当"下次启动再试"，而不是进程起不来。
            try {
                runMigration(domainRegistry, domainStore);
            } catch (Exception e) {
                log.error("[域] 可调用性迁移失败（不影响启动，下次启动会重试）：{}", e.getMessage(), e);
            }
        };
    }

    private void runMigration(DomainRegistry domainRegistry, DomainStore domainStore) {
        DomainStore.StoredDomains stored = domainStore.load();
        if (stored.status() == DomainStore.StoredDomains.Status.CORRUPT) {
            // 损坏时**绝不迁移、绝不写盘**：此刻注册表里只有根域与工具派生域，
            // 人工建的域一个都没有。此时写回磁盘就是把它们全部抹掉，且迁移标记一旦置 true
            // 就再也不会重来 —— 一次磁盘故障升级成永久的数据丢失。
            // 让服务带着"只有派生域"的状态启动，至少原始文件还在，运维修好后重启即可恢复。
            log.error("[域] 配置文件已损坏，跳过可调用性迁移并拒绝写盘。"
                    + "请修复或恢复 {}；在此之前人工建的域不会生效（服务仍可正常启动）", domainStore.filePath());
            return;
        }
        if (stored.callableMigrated()) {
            return;
        }
        List<String> changed = new ArrayList<>();
        for (DomainRegistry.Domain domain : domainRegistry.all()) {
            // 不再对根域豁免：根域也服从叶子规则 —— 它有了子域就同样只是装配节点，
            // "整棵树只有根域"时它才是叶子、才可调用。
            if (!domain.callable()) {
                continue;
            }
            if (domainRegistry.hasChildren(domain.id())) {
                domainRegistry.setCallable(domain.id(), false);
                changed.add(domain.id());
            }
        }
        domainStore.save(domainRegistry.manualConfig(), true);
        if (changed.isEmpty()) {
            log.info("[域] 落盘可调用性归一完成：没有需要改为装配节点的域（可调用集合 {} 个）",
                    domainRegistry.callableIds().size());
        } else {
            log.warn("[域] 落盘可调用性归一：已把 {} 个「有子域」的域落盘为装配节点：{}。"
                            + "按「只有叶子域可以作为可调用单元」的规则，有子域的域不再能作为入口；"
                            + "若需要该层能力的入口，请在管控台「域空间」另建一个没有子域的域",
                    changed.size(), changed);
        }
    }
}
