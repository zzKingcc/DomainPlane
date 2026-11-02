package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackend;
import com.zzkingcc.domainplane.domain.rag.store.KnowledgeBackendRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 可整体替换的后端注册表。
 *
 * <p>与 {@link MapKnowledgeBackendRegistry} 的差别只有一条：<b>允许整份替换</b>。
 * 管控台改知识体（关键字体换一台 ES、向量体从 ES 换到 Qdrant）必须立即生效，
 * 而"立即生效"意味着后端集合会变——不是连接参数变，是集合本身变。</p>
 *
 * <p>为什么敢开放替换而原来的实现刻意不可变：那个注释担心的是"给一个 put 入口
 * 会诱导出半新半旧的装配"。这里没有 put，只有 {@link #replace(List)}——一次换全套，
 * 换的过程中读侧看到的是旧集合或新集合，不会看到混合体（引用整体替换）。半新半旧
 * 是"逐条改"才会有的问题，不是"整体换"的问题。</p>
 *
 * <p>换完备会通知监听者（检索器缓存要失效：缓存里的检索器抓着旧后端实例）。</p>
 *
 * @author zzkingcc
 */
@Slf4j
public class LiveKnowledgeBackendRegistry implements KnowledgeBackendRegistry {

    private volatile Map<String, KnowledgeBackend> backends = Map.of();

    private final List<Runnable> onReplace = new CopyOnWriteArrayList<>();

    /** 注册替换回调（检索器缓存的失效入口挂在这里） */
    public void onReplace(Runnable listener) {
        if (listener != null) {
            onReplace.add(listener);
        }
    }

    /**
     * 整份替换后端集合。
     *
     * @throws IllegalArgumentException id 重复时抛（装配出来的重复 id 是代码 bug，
     *                                  不该在检索期才表现为"结果飘")
     */
    public void replace(List<KnowledgeBackend> fresh) {
        Map<String, KnowledgeBackend> map = new LinkedHashMap<>();
        if (fresh != null) {
            for (KnowledgeBackend backend : fresh) {
                if (backend == null) {
                    continue;
                }
                KnowledgeBackend prev = map.putIfAbsent(backend.id(), backend);
                if (prev != null) {
                    throw new IllegalArgumentException("知识库后端 id 重复注册: " + backend.id());
                }
            }
        }
        this.backends = Map.copyOf(map);
        log.info("[知识库装配] 后端集合已替换：{} 个（{}）", map.size(), map.keySet());
        for (Runnable listener : onReplace) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("[知识库装配] 替换回调执行失败（可忽略，缓存会自然过期）: {}", e.getMessage());
            }
        }
    }

    @Override
    public KnowledgeBackend require(String backendId) {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("未绑定知识库后端（知识体没绑连接），可用: "
                    + backends.keySet());
        }
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
