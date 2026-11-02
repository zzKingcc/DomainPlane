package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackendRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link KnowledgeBackendRegistry} 的内存实现：装配期一次性给全集，之后只读。
 *
 * 不可变是有意的：后端集合由 {@code domainplane.knowledge.backends} 配置决定，
 * 运行期增删后端等于换 bean 图（连接参数可热替换，后端类型不可，见设计第 7 节），
 * 给一个 put 入口只会诱导出半新半旧的装配。
 *
 * 重复 id 在构造时即炸：两个后端同名会让 {@link #require} 随机命中一个，
 * 而这种漂移在检索期才暴露，排障时看到的全是"结果不对"而不是"装配错了"。
 *
 * @author zzkingcc
 */
public class MapKnowledgeBackendRegistry implements KnowledgeBackendRegistry {

    private final Map<String, KnowledgeBackend> backends;

    public MapKnowledgeBackendRegistry(List<KnowledgeBackend> backends) {
        Map<String, KnowledgeBackend> map = new LinkedHashMap<>();
        for (KnowledgeBackend backend : backends) {
            KnowledgeBackend prev = map.putIfAbsent(backend.id(), backend);
            if (prev != null) {
                throw new IllegalArgumentException("知识库后端 id 重复注册: " + backend.id());
            }
        }
        this.backends = Map.copyOf(map);
    }

    @Override
    public KnowledgeBackend require(String backendId) {
        KnowledgeBackend backend = backends.get(backendId);
        if (backend == null) {
            throw new IllegalArgumentException("知识库后端未注册: " + backendId
                    + "，可用: " + backends.keySet());
        }
        return backend;
    }

    @Override
    public List<KnowledgeBackend> all() {
        return List.copyOf(backends.values());
    }

    @Override
    public boolean isEmpty() {
        return backends.isEmpty();
    }
}
