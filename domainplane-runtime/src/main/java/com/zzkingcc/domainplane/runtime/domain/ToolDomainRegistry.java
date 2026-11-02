package com.zzkingcc.domainplane.runtime.domain;

import com.zzkingcc.domainplane.api.tool.ToolDescriptor;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具侧声明过的域 —— 从 {@code ToolRegistry} 里拆出来的那一半"域记忆"。
 *
 * <p>它原来住在 {@code ToolRegistry} 里，和"工具副本"混在同一个类、同一把锁下。
 * 拆出来的理由不是行数，是<b>改动放大器</b>：域治理每动一次（F03 的删域三阶段、
 * 撤销后重建、工具断开后的域保留），都要在一个同时管着"工具副本增删改"的类里下刀，
 * 而那两件事的并发模型完全不同 —— 副本按实例分段锁，域是全进程一把锁。</p>
 *
 * <p>拆走的是"记忆"，不是"事实"：</p>
 * <ul>
 *   <li>这里记的是<b>声明史</b> —— 哪个域被声明过、哪个域被显式撤销过。
 *       它不关心此刻还有没有工具在声明它。</li>
 *   <li>{@code ToolRegistry} 仍握着<b>事实</b> —— 活着的工具条目此刻声明了哪些域。
 *       "某个域是否存在" = 两者取并集（见 {@code ToolRegistry#knownDomains}）。</li>
 * </ul>
 *
 * <p>并集这个形状是刻意的：工具断开（实例熔断 / 判死）不该让域跟着消失，
 * 否则一次网络抖动就把入口判成"域不存在"（10004）。</p>
 *
 * <p><b>撤销必须是"记住"而不是"删一次"</b>：删域只是把域从现有声明里剥离，
 * 存活实例的下一次心跳会带着原始 manifest 回来，被剥离的域会被重新声明、工具重建为该域
 * 的可调用叶子 —— 删域等于没删，授权边界一并恢复。所以撤销态在进程内保持，
 * 直到管控台重新创建同名域（{@link #allow}）。</p>
 *
 * <p>放在 {@code runtime.domain} 包（紧挨 {@link DomainRegistry}）而不是 {@code runtime.tool}：
 * 这里的语义是"域"而不是"工具"，与域树同源；工具注册表只是它的持有者之一。</p>
 */
public final class ToolDomainRegistry {

    private final DomainRegistry domainTree;

    /**
     * 本进程内出现过的域：某个工具一旦声明过它，就记在这里不再移除。
     *
     * <p>它记录的是"声明史"而不是"此刻是否有人声明"，所以只有 {@link #forget} 会摘除它。</p>
     */
    private final Set<String> declared = ConcurrentHashMap.newKeySet();

    /** 被显式撤销过的域：从此不再接受工具声明，直到 {@link #allow} 解除 */
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    /**
     * 域变更互斥锁。
     *
     * <p>{@link #forget} 与 {@link #remember} 必须串行，否则"撤销正在剥离"与
     * "心跳正在登记"交错，撤销结果取决于两条线程的时序。
     * 调用方若要把"域变更"与"另一件基于域的事"（如剥离存活工具的声明）合成一个原子动作，
     * 用 {@link #underLock} 把后者包进来 —— 锁本身不外泄。</p>
     */
    private final Object lock = new Object();

    public ToolDomainRegistry(DomainRegistry domainTree) {
        this.domainTree = domainTree == null ? new DomainRegistry() : domainTree;
    }

    /**
     * 记下一次域声明：域一经被声明就在进程内保留，提供它的工具断开后它依然可用；
     * 同时把它沿链派生进域树（完整路径，缺失的祖先一并补齐，不留悬空节点）。
     *
     * <p>被撤销过的域在这里被过滤掉 —— 撤销对新声明立即生效，不需要等下一次删域。</p>
     */
    public void remember(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        synchronized (lock) {
            List<String> allowed = domains.stream()
                    .filter(domain -> domain != null && !ToolDescriptor.isSentinel(domain)
                            && !revoked.contains(domain))
                    .toList();
            if (allowed.isEmpty()) {
                return;
            }
            addDomains(declared, allowed);
            domainTree.ensureChains(allowed);
        }
    }

    /** 声明史的快照（不含活工具此刻的声明；并集在持有方那里做） */
    public Set<String> declared() {
        return Set.copyOf(declared);
    }

    /** 该域是否已被显式撤销（撤销中的域不接受任何新声明） */
    public boolean isRevoked(String domain) {
        return domain != null && revoked.contains(domain);
    }

    /**
     * 撤下一批域的声明资格。
     *
     * <p>先记撤销再清声明：顺序反过来的话，"剥离完成"到"记下撤销"之间的一次心跳
     * 就能把域加回来，删域当场失效。</p>
     */
    public void forget(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        synchronized (lock) {
            revoked.addAll(domains.stream()
                    .filter(domain -> domain != null && !domain.isBlank())
                    .toList());
            declared.removeAll(domains);
        }
    }

    /**
     * 解除撤销：管控台重新创建同名域时调用，允许工具重新声明这些域。
     *
     * <p>只清撤销标记，不主动补声明：工具声明来自工具侧，重启或下一次心跳会自然带回；
     * 这里替它们"补"一遍等于凭空造出一次没人真正声明过的授权。</p>
     */
    public void allow(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        synchronized (lock) {
            revoked.removeAll(domains);
        }
    }

    /**
     * 在域锁内执行一段动作：给"域变更 + 另一件必须与之原子的事"用。
     *
     * <p>典型调用方是删域：撤销标记与"把域从存活工具的声明里剥离"必须是一个原子动作，
     * 否则中间插进一次心跳就把域加回来了。锁对象不外泄，只借出临界区。</p>
     */
    public void underLock(Runnable action) {
        synchronized (lock) {
            action.run();
        }
    }

    /**
     * 把域列表并入目标集合：过滤 null / 空白与哨兵。
     *
     * <p>哨兵（{@link ToolDescriptor#REVOKED_DOMAIN} / {@link ToolDescriptor#UNBOUND_DOMAIN}）
     * 不是域：把它并入"域全集"会让管控台的「域空间」多出一个假域，也会让域树去建一条
     * 永远合法的链（{@code <unbound>} 过不了 {@code Domains.validatePath}，只会刷一条 WARN）。</p>
     */
    public static void addDomains(Set<String> target, List<String> domains) {
        if (domains == null) {
            return;
        }
        for (String p : domains) {
            if (p != null && !p.isBlank() && !ToolDescriptor.isSentinel(p)) {
                target.add(p);
            }
        }
    }
}
