package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.server.env.StorageLocations;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * blob 存储装配 —— 本波只有本地实现（默认、零外部依赖）。
 *
 * <p>S3 兼容 provider（OSS / COS / MinIO 一家实现）与"存储配置"管控页
 * 随 F13 多实例波次落地，见 {@code docs/L2运行时内核改造升级计划.md} 横切附注。
 * 到那时这里多一个 {@code @ConditionalOnProperty} 二选一即可，
 * 编排层与媒体暂存实现都不用动。</p>
 *
 * @author zzkingcc
 */
@Slf4j
@Configuration
public class StorageConfiguration {

    @Bean
    public LocalBlobStore localBlobStore(StorageLocations storageLocations) {
        Path root = storageLocations.blobDir();
        log.info("[blob 存储] 本地实现（默认），根目录 {}", root.toAbsolutePath());
        return new LocalBlobStore(root);
    }
}
