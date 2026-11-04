package com.zzkingcc.domainplane.server.storage;

import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.server.settings.InfraSettings;
import com.zzkingcc.domainplane.server.settings.InfraSettingsHolder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原文落点 → blob 存储实例。
 *
 * <p>一个知识体的原文落在哪里（{@code local} 或某条 s3 连接），由它自己的
 * {@code raw} 字段决定；这个类负责把那一个字符串翻译成能读写的 {@link BlobStore}。
 * 上层（{@code ChunkArchive}、上传链路）只认"按落点取 store"，不认识 S3 或磁盘。</p>
 *
 * <p><b>为什么按连接懒建并缓存</b>：S3Client 带连接池与签名器，每次读写都新建会
 * 把单次上传拖成秒级。按连接 id 缓存，配置变了（地址 / 桶 / 前缀 / 凭证任一改变，
 * 由 {@link S3BlobStore#signature()} 判定）就关掉旧的换新的——与 ES 连接热切换同一条纪律。</p>
 *
 * <p><b>不缓存失败</b>：落点不可用时直接抛，不建空壳实例。调用方看到的是
 * "这个落点用不了 + 该去哪配"，而不是一个永远返回空的对象。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class BlobStoreRegistry {

    private final LocalBlobStore local;
    private final InfraSettingsHolder infra;
    /** 连接 id → S3 实例（只装真正被用到的连接，不预建全部） */
    private final Map<String, S3BlobStore> remote = new ConcurrentHashMap<>();

    public BlobStoreRegistry(LocalBlobStore local, InfraSettingsHolder infra) {
        this.local = local;
        this.infra = infra;
    }

    /**
     * 取某个原文落点对应的存储。
     *
     * @param location {@code local} 或一条 s3 连接的 id；空值按 {@code local} 处理
     * @throws IllegalStateException 落点不存在 / 不是对象存储 / 配置不完整
     */
    public BlobStore of(String location) {
        String loc = location == null || location.isBlank()
                ? InfraSettings.RAW_LOCAL : location.trim();
        if (InfraSettings.RAW_LOCAL.equals(loc)) {
            return local;
        }
        InfraSettings.Connection conn = infra.current().connection(loc);
        if (conn == null) {
            throw new IllegalStateException("原文落点「" + loc + "」指向的连接已不存在："
                    + "请到「存储配置」页确认，或把知识体的原文落点改回本地原文存储");
        }
        if (!conn.isS3()) {
            throw new IllegalStateException("原文落点「" + loc + "」不是对象存储连接（当前类型 "
                    + conn.effectiveType() + "）：原文只能落本地盘或 s3 连接");
        }
        if (!conn.isUsable() || conn.getS3() == null) {
            throw new IllegalStateException("对象存储连接「" + loc + "」还没配完整（endpoint / 桶 / AK / SK）");
        }
        InfraSettings.ObjectStorage config = conn.getS3();
        String signature = config.location();
        String key = loc;
        return remote.compute(key, (id, existing) -> {
            if (existing != null && signature != null && signature.equals(existing.signature())) {
                return existing;
            }
            if (existing != null) {
                log.info("[blob 存储] 连接 {} 的配置已变化（{} → {}），重建客户端", id, existing.signature(), signature);
                existing.close();
            }
            return new S3BlobStore(config);
        });
    }

    /** 该落点当前是否有可用实现（不抛、不建实例；页面探测用） */
    public boolean usable(String location) {
        try {
            of(location);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 全部可用落点 → store：本地 + 每条配好的 s3 连接。
     *
     * <p>对账要跨落点看——体换过原文落点之后，旧桶里还有一批活档案。
     * 只看当前落点会把它们判成"不存在"，然后从索引补写出一份重复副本。</p>
     */
    public Map<String, BlobStore> all() {
        Map<String, BlobStore> out = new LinkedHashMap<>();
        out.put(InfraSettings.RAW_LOCAL, local);
        for (InfraSettings.Connection c : infra.current().connectionsOrEmpty().values()) {
            if (c == null || !c.isS3() || c.getId() == null || c.getId().isBlank()) {
                continue;
            }
            try {
                out.put(c.getId(), of(c.getId()));
            } catch (RuntimeException e) {
                log.warn("[blob 存储] 跳过不可用的原文落点 {}: {}", c.getId(), e.getMessage());
            }
        }
        return out;
    }

    /** 连通过一次就够：给管控台「测试连接」用 */
    public boolean ping(String location) {
        try {
            BlobStore store = of(location);
            return store instanceof S3BlobStore s3 ? s3.ping() : true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 连接配置整份保存后调用：把所有客户端丢掉，下次按新配置重建 */
    public void invalidate() {
        remote.values().forEach(S3BlobStore::close);
        remote.clear();
    }

    @PreDestroy
    public void shutdown() {
        invalidate();
    }
}
