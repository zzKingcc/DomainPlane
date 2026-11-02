package com.zzkingcc.domainplane.server.config;

import com.zzkingcc.domainplane.runtime.config.Timeouts;
import com.zzkingcc.domainplane.runtime.storage.BlobStore;
import com.zzkingcc.domainplane.runtime.vision.MediaAttachmentStore;
import com.zzkingcc.domainplane.server.storage.LocalBlobStore;
import com.zzkingcc.domainplane.server.vision.BlobMediaAttachmentStore;
import com.zzkingcc.domainplane.server.vision.VisionCallExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 多模态装配 —— 媒体暂存区与视觉调用的隔离层。
 *
 * <p>两块都是"部署决策"而不是内核逻辑，所以放在服务端装配：暂存区用哪种介质、
 * 看图允许多久、能同时看几张，都是跟着部署环境走的参数。</p>
 *
 * @author zzkingcc
 */
@Configuration
public class VisionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VisionConfiguration.class);

    /**
     * 视觉委派专用隔离池。
     *
     * <p>上限默认 60s 而不是本地工具的 30s：一次带图 completion 在图片大、模型慢时
     * 轻易超过 30s，而这个时限之后失败会被写成"工具超时"，看不出是图太大还是模型太慢。
     * 池默认 8 且零队列（满即拒）：看图是父轮阻塞等待的长调用，排队只会把"看图慢"
     * 扩散成"整个会话都在等"。</p>
     *
     * <p>{@code destroyMethod = "close"}：Spring 关闭时 {@code shutdownNow}，
     * 不留非守护线程挂着进程。</p>
     */
    @Bean(destroyMethod = "close")
    public VisionCallExecutor visionCallExecutor(
            @Value("${domainplane.vision.pool-size:8}") int poolSize,
            Timeouts timeouts) {
        VisionCallExecutor executor = new VisionCallExecutor(poolSize, timeouts.visionCallMs());
        log.info("[视觉委派] 专用隔离池 {} 线程（零队列，满即拒），单次看图上限 {}ms",
                Math.max(1, poolSize), executor.timeoutMs());
        return executor;
    }

    /**
     * 媒体暂存区（Redis 登记表 + blob 存储字节）—— 图 / 音 / 视都存。
     *
     * <p>与旧的纯 Redis 实现的分界：字节搬出 Redis（base64 大 value 是生产隐患），
     * 登记表（引用名 → 键 / 类型 / 有效期）留在 Redis——TTL 与活动续期继续吃原生
     * 能力，编排层 {@code renewActiveAttachments} 零改动。升级前的存量由新实现
     * 内置回退读（老格式字节内联在登记表里）。</p>
     *
     * <p>返回具体类型而不是 {@link MediaAttachmentStore}：孤儿回收调度
     * （{@code MediaBlobSweeper}）要按具体类型查登记表；编排层只认接口，
     * 子类型 bean 照常注入，互不影响。</p>
     */
    @Bean
    public BlobMediaAttachmentStore mediaAttachmentStore(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate stringRedisTemplate,
            BlobStore blobStore,
            @Value("${domainplane.vision.attachment-ttl-minutes:30}") long ttlMinutes) {
        Duration ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
        log.info("[附件暂存] 媒体暂存区 = Redis 登记表 + blob 存储，引用 TTL={} 分钟", ttlMinutes);
        return new BlobMediaAttachmentStore(stringRedisTemplate, ttl, blobStore);
    }
}
