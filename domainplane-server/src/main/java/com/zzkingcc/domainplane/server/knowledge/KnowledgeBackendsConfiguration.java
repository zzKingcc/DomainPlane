package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.common.exception.NotConfiguredException;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.infrastructure.kb.es.EsKnowledgeBackend;
import com.zzkingcc.domainplane.infrastructure.kb.qdrant.QdrantHttpClient;
import com.zzkingcc.domainplane.infrastructure.kb.qdrant.QdrantKnowledgeBackend;
import com.zzkingcc.domainplane.infrastructure.kb.qdrant.VectorTarget;
import com.zzkingcc.domainplane.server.config.EsClients;
import com.zzkingcc.domainplane.server.config.KnowledgeProperties;
import com.zzkingcc.domainplane.server.config.RetrievalProperties;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import com.zzkingcc.domainplane.server.settings.InfraSwappable;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库后端装配：管控台的<b>具名连接</b> → 后端注册表 + 检索计划 + 校验器。
 *
 * <p><b>后端 id 就是连接 id</b>。这条约定省掉了一整层映射：管控台里那行
 * {@code es-main}，在知识体里被引用时是 {@code es-main}，在注册表里也是
 * {@code es-main}，排障时看到一个 id 就能在页面上找到对应那一行。</p>
 *
 * <p>连接参数（{@code domainplane.knowledge.backends.<id>.vector.min-score} 等）仍然
 * 从 yaml 读，按 id 对齐；yaml 里没声明的连接走废弃兼容键的默认值——管控台新建的连接
 * 本来就不可能出现在 yaml 里，这不算配错，所以不打 WARN。</p>
 *
 * <p>装配是<b>可重建</b>的：注册表用 {@link LiveKnowledgeBackendRegistry}，
 * {@link InfraSettingsHolder#apply} 保存后把这里注册的 {@link Rebuild} 当作
 * {@link InfraSwappable} 一起 swap 一次，注册表与客户端池随之换新。这样"改知识体→保存"
 * 才真的即时生效，而不是等到重启。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeBackendsConfiguration {

    /**
     * 后端注册表：按管控台的连接清单逐个建适配器，并挂上"保存即重建"。
     *
     * <p>清单为空时不炸，返回空注册表——服务照常启动（知识库不是启动依赖），
     * 首次检索 / 上传时给出带指引的 60007。</p>
     */
    @Bean
    public LiveKnowledgeBackendRegistry domainplaneKnowledgeBackendRegistry(
            KnowledgeProperties knowledgeProperties,
            RetrievalProperties retrievalProperties,
            InfraSettingsHolder infraSettingsHolder,
            EsClients esClients) {
        LiveKnowledgeBackendRegistry registry = new LiveKnowledgeBackendRegistry();
        Rebuild rebuild = new Rebuild(knowledgeProperties, retrievalProperties,
                infraSettingsHolder, esClients, registry);
        rebuild.swap();
        infraSettingsHolder.register(rebuild);
        return registry;
    }

    /**
     * 检索计划：策略 + 域 → 知识体的解析器。
     *
     * <p>计划里不再存"全局关键字后端 / 向量后端"两个 id：一个域只挂一个知识体，
     * 用途与连接都随体而定，所以每次 {@code channels(domain)} 都按域现解析
     * （见 {@link KnowledgeBodyResolver}）。这样同一条域链上父域用关键字体、
     * 子域用向量体也能各查各的。</p>
     */
    @Bean
    public RetrievalPlan domainplaneRetrievalPlan(
            @Qualifier("domainplaneKnowledgeBackendRegistry") LiveKnowledgeBackendRegistry registry,
            KnowledgeProperties knowledgeProperties,
            KnowledgeBodyResolver bodyResolver) {
        RetrievalPlan plan = new RetrievalPlan(
                knowledgeProperties.getStrategy(), registry, bodyResolver);
        log.info("[知识库装配] 检索计划：strategy={}，fusion={}；角色由「域 → 知识体」绑定现解析"
                        + "（平台默认：keyword={}，vector={}）",
                plan.strategy(), knowledgeProperties.getFusion(),
                plan.keywordBackendId(), plan.vectorBackendId());
        return plan;
    }

    /**
     * 装配校验器。单独挂 bean（而不是内联进启动检查）是为了让"管控台保存存储配置时"
     * 也能注它直接调 {@link KnowledgeBackendValidator#validate()}——保存即校验，
     * 不把错误留到重启。
     *
     * <p><b>启动期不再校验</b>（原来这里还有一个 SmartInitializingSingleton 阻断校验）：
     * 知识库没配 / 配错不再是"服务起不来"的理由——管控台、对话、模型管理都不依赖它。
     * 空注册表照常启动，第一次真的用到知识库时（检索 / 上传）给出带指引的 60007；
     * 能力矩阵是否匹配延迟到"保存存储配置"时反馈（AdminController#saveInfra）。</p>
     */
    @Bean
    public KnowledgeBackendValidator domainplaneKnowledgeBackendValidator(
            RetrievalPlan plan,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        return new KnowledgeBackendValidator(plan, embeddingModel);
    }

    // ==================== 内部：按连接建后端 ====================

    /**
     * 重建器：把"当前生效配置里的连接"翻译成后端集合。
     *
     * <p>注册为 {@link InfraSwappable}，与 ES / Redis 代理在同一轮 swap 里跑。
     * 顺序上它排在 ES 代理之后（{@code EsClientConfiguration} 先于本类装配），
     * 因此重建时平台 ES 客户端已经换好，不会拿到旧 transport。</p>
     */
    static final class Rebuild implements InfraSwappable {

        private final KnowledgeProperties knowledgeProperties;
        private final RetrievalProperties retrievalProperties;
        private final InfraSettingsHolder holder;
        private final EsClients esClients;
        private final LiveKnowledgeBackendRegistry registry;

        Rebuild(KnowledgeProperties knowledgeProperties,
                RetrievalProperties retrievalProperties,
                InfraSettingsHolder holder,
                EsClients esClients,
                LiveKnowledgeBackendRegistry registry) {
            this.knowledgeProperties = knowledgeProperties;
            this.retrievalProperties = retrievalProperties;
            this.holder = holder;
            this.esClients = esClients;
            this.registry = registry;
        }

        @Override
        public void swap() {
            InfraSettings settings = holder.current();
            Map<String, KnowledgeProperties.Backend> declared = knowledgeProperties.getBackends();
            List<KnowledgeBackend> backends = new ArrayList<>();
            List<String> esIds = new ArrayList<>();
            for (InfraSettings.Connection conn : settings.connectionsOrEmpty().values()) {
                if (conn == null || conn.getId() == null) {
                    continue;
                }
                if (!InfraSettings.SUPPORTED_TYPES.contains(conn.effectiveType())) {
                    log.warn("[知识库装配] 连接 {} 的类型 {} 不受支持，跳过；当前支持 {}",
                            conn.getId(), conn.effectiveType(),
                            String.join(" / ", InfraSettings.SUPPORTED_TYPES));
                    continue;
                }
                KnowledgeBackend built = build(conn, settings, declared);
                if (built != null) {
                    backends.add(built);
                    if (conn.isElasticsearch()) {
                        esIds.add(conn.getId());
                    }
                }
            }
            // 连接被删了，它那份客户端池也要收掉（不删会让一次误操作永久占着一条连接池）
            esClients.retainOnly(esIds);
            registry.replace(backends);
        }

        private KnowledgeBackend build(InfraSettings.Connection conn,
                                      InfraSettings settings,
                                      Map<String, KnowledgeProperties.Backend> declared) {
            KnowledgeProperties.Backend decl = declared == null ? null : declared.get(conn.getId());
            if (conn.isElasticsearch()) {
                if (decl != null && Boolean.FALSE.equals(decl.getVectorEnabled())) {
                    // ES 的 keyword-only 需要改索引 mapping（去 dense_vector），当前不做；
                    // 显式 vector:false 直接点名，避免"配了像没配"的静默行为
                    log.error("[知识库装配] 连接 {} 配了 vector:false，但 type=elasticsearch 不支持"
                            + "（纯 keyword 请用 Qdrant 连接），该连接未注册", conn.getId());
                    return null;
                }
                try {
                    return new EsKnowledgeBackend(conn.getId(), conn.location(),
                            esClients.forConnection(conn.getId()),
                            minScoreOf(decl),
                            shouldMatchOf(decl));
                } catch (NotConfiguredException e) {
                    // 非平台默认的那台 ES 还没填地址：这是"表单填了一半"，不是配置错误
                    log.warn("[知识库装配] ES 连接 {} 尚未填写地址，暂不注册", conn.getId());
                    return null;
                } catch (Exception e) {
                    log.error("[知识库装配] ES 连接 {} 的客户端创建失败，该连接未注册: {}",
                            conn.getId(), e.getMessage());
                    return null;
                }
            }
            // Qdrant：地址 / token / 度量 / 前缀每次操作现取，支持"管控台填完即生效"
            boolean vectorEnabled = decl == null || decl.getVectorEnabled() == null
                    || decl.getVectorEnabled();
            if (!vectorEnabled) {
                log.info("[知识库装配] 连接 {} 声明为纯 keyword（vector:false）", conn.getId());
            }
            QdrantHttpClient client = new QdrantHttpClient(
                    () -> targetOf(conn.getId()), Duration.ofSeconds(10));
            if (conn.getVector() == null || !conn.getVector().isUsable()) {
                log.warn("[知识库装配] 连接 {} 的 type=qdrant，但地址尚未配置；服务照常启动，"
                        + "首次用到该连接时会提示去管控台「存储配置」页填写。", conn.getId());
            }
            return new QdrantKnowledgeBackend(conn.getId(), conn.location(), client,
                    minScoreOf(decl), vectorEnabled);
        }

        /** 现取当前生效的 Qdrant 目标；连接被删或清空时返回未配置占位 */
        private VectorTarget targetOf(String connectionId) {
            InfraSettings.Connection conn = holder.current().connection(connectionId);
            if (conn == null || !conn.isQdrant() || conn.getVector() == null
                    || !conn.getVector().isUsable()) {
                return VectorTarget.UNCONFIGURED;
            }
            InfraSettings.VectorStore vs = conn.getVector();
            return new VectorTarget(vs.getUri(), vs.getToken(),
                    vs.effectiveMetric(), vs.effectiveCollectionPrefix());
        }

        /**
         * 向量阈值：yaml 里按连接 id 声明了就用自己的；没声明就走废弃兼容键的默认值。
         *
         * <p>不打 WARN：管控台新建的连接本来就不会出现在 yaml 的 {@code backends} 里，
         * 那条 WARN 会变成"每建一个连接就警告一次"的噪音。</p>
         */
        private double minScoreOf(KnowledgeProperties.Backend decl) {
            Double minScore = decl == null || decl.getVector() == null
                    ? null : decl.getVector().getMinScore();
            return minScore != null ? minScore : retrievalProperties.getVectorMinScore();
        }

        private String shouldMatchOf(KnowledgeProperties.Backend decl) {
            String shouldMatch = decl == null || decl.getKeyword() == null
                    ? null : decl.getKeyword().getMinimumShouldMatch();
            return shouldMatch != null && !shouldMatch.isBlank()
                    ? shouldMatch : retrievalProperties.getMinimumShouldMatch();
        }
    }

}
