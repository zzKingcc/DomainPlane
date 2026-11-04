package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.server.settings.DomainStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSwappable;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库统计的缓存层：文档 / 切片 / 索引计数只有管控台展示一个消费者，
 * 不值得让每个页面请求都去扫一遍全部后端索引。
 *
 * <p><b>为什么单独一层</b>：{@link KnowledgeBaseService#list()} 的读放大是
 * O(全部切片)（每个索引一次 matchAll），ES 在远端时一个页面请求能烧满一次
 * connectTimeout。管控台数字的时效要求是"分钟级可接受"，于是这里做两件事：</p>
 *
 * <ul>
 *   <li>TTL 缓存：60s 内的重复请求直接复用上一次结果，不碰存储；</li>
 *   <li>失败熔断：一次失败后的冷却窗口（5min，刻意比 TTL 长）内不再发起任何远程调用
 *       （复用旧快照）。</li>
 * </ul>
 *
 * <p><b>熔断只对"会抛"的后端有效</b>：ES 的 {@code listCollections()} 是 fail-open 的
 * （catch 之后返回空列表），存储黑洞在统计层表现为"索引为空"而不是异常，进不了熔断。
 * 对这种后端能兜住的只有 TTL——即"每分钟最多烧一次 connectTimeout"。要让它也进熔断，
 * 得让存储层把失败抛出来（展示层再决定 fail-open），那是另一件事，别在这里拿"索引为空"
 * 猜故障：新建的空知识库本来就一个索引都没有。</p>
 *
 * <p><b>归属口径</b>：按<b>知识体</b>聚合（文档实际存在体的连接那台机器上），
 * 域只保留 byDomain 视图给删域预演用——它问的是"删这棵子树连带多少文档"，
 * 视角天然是域。文档 → 域 → 绑定的体，用的是切片 metadata 里记录的归属域
 * 与<b>当前</b>绑定表：换过知识体的域，旧文档会归到旧体名下，与按域归类的
 * 旧口径偏差完全一致，不算回归。</p>
 *
 * <p><b>失效信号</b>（照 {@link KnowledgeToolBinder} 同一套三时点）：
 * 域配置变更（{@link DomainStore#onChange}）、存储配置保存
 * （{@link InfraSwappable#swap}）时清缓存，下一次请求现算。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Service
public class KnowledgeStatsService implements InfraSwappable {

    /** 缓存有效期：管控台数字分钟级时效足够 */
    static final long CACHE_TTL_MS = 60_000L;

    /**
     * 失败熔断冷却期：一次（可观测的）失败后这段时间内不再发起远程调用。
     *
     * <p><b>必须大于 {@link #CACHE_TTL_MS}</b>，否则永远轮不到它生效：TTL 内本来就不重拉，
     * 冷却窗口比 TTL 短就只是摆设。</p>
     */
    static final long FAILURE_COOLDOWN_MS = 300_000L;

    private final KnowledgeBaseService knowledgeBaseService;
    private final DomainStore domainStore;
    private final InfraSettingsHolder infraHolder;

    private volatile Snapshot snapshot;

    /** 失败冷却截止时间（毫秒时间戳；0 = 无冷却）。比"上次失败时刻"好读：判断只剩一次比较 */
    private volatile long retryNotBefore;

    public KnowledgeStatsService(KnowledgeBaseService knowledgeBaseService,
                                 DomainStore domainStore,
                                 InfraSettingsHolder infraHolder) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.domainStore = domainStore;
        this.infraHolder = infraHolder;
    }

    @PostConstruct
    void start() {
        domainStore.onChange(this::invalidate);
        infraHolder.register(this);
    }

    /** 存储配置保存后由 {@link InfraSettingsHolder#apply} 回调：连接 / 知识体变了，数字作废 */
    @Override
    public void swap() {
        invalidate();
    }

    /** 域配置变更（绑体 / 解绑 / 删域 / 回滚）后归属关系变了，数字作废 */
    public void invalidate() {
        snapshot = null;
        /* 冷却也一起清：配置刚变过（尤其刚在管控台换了地址 / 知识体），旧的"连不上"
           不该继续压着不试——否则用户把 ES 修好、保存完，还得再等一个冷却期才看到数字。 */
        retryNotBefore = 0;
    }

    /**
     * 一次统计快照。
     *
     * @param byBody  知识体 id → [文档数, 切片数]
     * @param indices 当前全部已存在的索引名（"体有没有索引"与"无主索引"都靠它判）
     * @param stale   true = 本次数字来自缓存/旧快照（存储不可达或冷却期内），仅供展示提示
     */
    public record Stats(Map<String, long[]> byBody, Set<String> indices, boolean stale) {
    }

    /**
     * 取统计（带缓存与熔断）。绝不抛异常：存储故障对展示路径是"数字旧了"，
     * 不是"页面打不开"。
     */
    public Stats stats() {
        return aggregate(load());
    }

    /** 拉一次原始数据；缓存有效或冷却期内直接复用旧快照 */
    private Snapshot load() {
        long now = now();
        Snapshot current = snapshot;
        if (current != null && now - current.at < CACHE_TTL_MS) {
            return current;
        }
        if (now < retryNotBefore) {
            // 熔断冷却期：不发起远程调用。有旧快照用旧的（标 stale），没有就给空
            return current != null ? new Snapshot(current.items, current.owners, now, true)
                    : Snapshot.empty(now);
        }
        try {
            /* 枚举只做一次，且要"索引名 + 归属后端"一起拿：知识库页要显示"这份数据在哪几台"，
               再单独枚举一遍拿名字，等于存储黑洞时白烧第二个 connectTimeout。
               索引为空还去调 list()，同理——空索引正是不可达的典型形态。 */
            Map<String, List<String>> owners = knowledgeBaseService.indexOwners();
            List<KnowledgeBaseService.DocumentItem> items =
                    owners.isEmpty() ? List.of() : knowledgeBaseService.list(owners.keySet());
            Snapshot fresh = new Snapshot(items, owners, now, false);
            snapshot = fresh;
            retryNotBefore = 0;
            return fresh;
        } catch (Exception e) {
            retryNotBefore = now + FAILURE_COOLDOWN_MS;
            log.warn("[知识库统计] 拉取失败（{}s 内不再重试，展示旧数据）: {}",
                    FAILURE_COOLDOWN_MS / 1000, e.getMessage());
            return current != null ? new Snapshot(current.items, current.owners, now, true)
                    : Snapshot.empty(now);
        }
    }

    /** 时间源：测试用假时钟推进 TTL / 冷却期，不必 sleep */
    protected long now() {
        return System.currentTimeMillis();
    }

    private Stats aggregate(Snapshot s) {
        Map<String, long[]> byBody = new LinkedHashMap<>();
        for (KnowledgeBaseService.DocumentItem item : s.items) {
            String raw = item.body();
            /* metadata.domain 在 v1 里存的是域路径，v2 起存知识体 id。迁移后的旧文档仍带着
               域路径：按"先当体 id、再当域路径反查体"两段解析，旧数据的数字不会凭空消失。 */
            String bodyId = null;
            if (raw != null && !raw.isBlank()) {
                if (infraHolder.current().body(raw) != null) {
                    bodyId = raw;
                } else {
                    String mapped = domainStore.bodyOf(raw);
                    bodyId = mapped == null || mapped.isBlank() ? null : mapped;
                }
            }
            if (bodyId == null) {
                continue;
            }
            accumulate(byBody, bodyId, item.chunks());
        }
        return new Stats(byBody, Set.copyOf(s.indices()), s.stale());
    }

    /**
     * 知识库页的整页快照：同一次 {@link #load()} 里出全部数字与归属明细。
     *
     * <p><b>为什么不拆成三个接口</b>：知识库页要同时显示"全平台汇总 + 每个体的两槽落点
     * + 每个索引在哪几台 + 无主索引"，其中"索引 → 后端"和"文档数"都只能靠远程枚举算出来。
     * 三个接口各扫一遍，就是把刚拆掉的域空间页性能问题原样搬到这页；共用一份快照则
     * 60s 内只烧一次。页面渲染顺序按"先出骨架、再补数"。</p>
     *
     * @param documents 全平台文档数（按 docId 聚合，与文档表行数同源）
     * @param chunks    全平台切片数（跨后端取最大值，不重复计扇出）
     * @param owners    索引名 → 存在该索引的后端 id（"落点"列与无主索引判定都用它）
     */
    public record Overview(Map<String, List<String>> owners, Map<String, long[]> byBody,
                           Set<String> indices, long documents, long chunks, boolean stale) {
    }

    /** 整页快照（绝不抛异常，语义同 {@link #stats()}） */
    public Overview overview() {
        Snapshot s = load();
        Stats aggregated = aggregate(s);
        long chunks = 0;
        for (KnowledgeBaseService.DocumentItem item : s.items) {
            chunks += item.chunks();
        }
        return new Overview(s.owners, aggregated.byBody(), aggregated.indices(),
                s.items.size(), chunks, s.stale());
    }

    private static void accumulate(Map<String, long[]> into, String key, long chunks) {
        long[] acc = into.computeIfAbsent(key, k -> new long[2]);
        acc[0] += 1;
        acc[1] += chunks;
    }

    /** 知识体展示名（id 兜底），管控台直接用 */
    public String bodyName(String bodyId) {
        InfraSettings.KnowledgeBody body = infraHolder.current().body(bodyId);
        return body == null ? bodyId : body.effectiveName();
    }

    /**
     * 每个域"一次检索能查到多少"：<b>沿域链（根 → 自身）把各域绑的知识体去重后累加</b>。
     *
     * <p>这是与 {@link RetrievalPlan#channels(String)} 完全同口径的计数：那里对链上每个域
     * 各开一条通道查它绑的体，并<b>按索引去重</b>（父子绑同一个体时只查一次，深的那条优先）。
     * 所以这里也必须按体去重——否则"父子绑同一个体"会被算成两份，页面上出现一个
     * 永远不会发生的翻倍数字。</p>
     *
     * <ul>
     *   <li>子域没绑体 → 它自己没有知识库，但它仍能查到<b>祖先域</b>的文档（祖先自己绑了体）；</li>
     *   <li>子域绑的文档，父域<b>查不到</b>——通道只沿链向上，不向下。</li>
     * </ul>
     *
     * <p>只返回有数的域（0 的不进 map，前端按 0 展示）：空数据不值得占 payload。</p>
     *
     * @param domains 需要算的域（管控台域树里的那批）
     * @return 域 → [可检索文档数, 可检索切片数]
     */
    public Map<String, long[]> retrievableBy(Collection<String> domains) {
        Stats stats = stats();
        Map<String, String> bindings = domainStore.bodies();
        Map<String, long[]> out = new LinkedHashMap<>();
        if (domains == null) {
            return out;
        }
        for (String domain : domains) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            long[] total = new long[2];
            Set<String> seen = new LinkedHashSet<>();
            for (String step : Domains.chainOf(Domains.normalize(domain))) {
                String body = bindings.get(step);
                if (body == null || body.isBlank() || !seen.add(body)) {
                    continue;   // 没绑体 = 不开通道；同一个体在链上出现两次只算一次
                }
                long[] acc = stats.byBody().get(body);
                if (acc == null) {
                    continue;
                }
                total[0] += acc[0];
                total[1] += acc[1];
            }
            if (total[0] > 0 || total[1] > 0) {
                out.put(domain, total);
            }
        }
        return out;
    }

    private record Snapshot(List<KnowledgeBaseService.DocumentItem> items,
                            Map<String, List<String>> owners, long at, boolean stale) {

        static Snapshot empty(long at) {
            return new Snapshot(List.of(), Map.of(), at, true);
        }

        /** 索引名集合：归属表的 key 视图，两处必须是同一份枚举结果 */
        Set<String> indices() {
            return owners.keySet();
        }
    }
}
