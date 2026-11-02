package com.zzkingcc.domainplane.server.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 域配置版本信号（多实例传播，F03 域存储增强方案 §4）。
 *
 * 定位是"信号通道"不是"数据通道"：本地文件是域配置的唯一权威存储，Redis 里只放
 * 一个版本号——写节点变更后 version+1 并在此登记，其他节点比对发现本地落后时
 * 知道该去同步（拉取动作属 F13 多实例范围，届时经实例登记表找写节点 HTTP 拉取）。
 *
 * 为什么值得单独一个组件而不是塞进 DomainStore：存储的正确性不能依赖 Redis 可用。
 * 本组件所有方法都不抛异常——广播失败只意味着其他节点滞后，本节点早已生效。
 *
 * @author zzkingcc
 */
@Component
public class DomainVersionSignal {

    private static final Logger log = LoggerFactory.getLogger(DomainVersionSignal.class);

    /** 版本号键与频道同名：键供定期比对，频道供即时通知（消费方后上，先通车） */
    public static final String VERSION_KEY = "domainplane:domain:version";

    private final StringRedisTemplate redis;

    private final InfraSettingsHolder infraSettingsHolder;

    public DomainVersionSignal(
            @Qualifier("domainplaneStringRedisTemplate") StringRedisTemplate redis,
            InfraSettingsHolder infraSettingsHolder) {
        this.redis = redis;
        this.infraSettingsHolder = infraSettingsHolder;
    }

    /**
     * 登记新版本号（写节点在每次域配置落盘后调用）。
     *
     * 同时写键与发频道：键是"当前权威版本"的持久事实（定期比对的基准），
     * 频道是变更通知（低延迟消费方的入口，尚未有消费方也先发着）。
     */
    public void publish(long version) {
        if (!infraSettingsHolder.isRedisConfigured()) {
            // 单机未配 Redis：没有广播对象，也没有多实例问题
            return;
        }
        try {
            redis.opsForValue().set(VERSION_KEY, String.valueOf(version));
            redis.convertAndSend(VERSION_KEY, String.valueOf(version));
            log.debug("[域版本信号] 已广播域配置版本 v{}", version);
        } catch (Exception e) {
            log.warn("[域版本信号] 广播 v{} 失败（Redis 不可用？），本节点已生效，其他节点将滞后：{}",
                    version, e.getMessage());
        }
    }

    /**
     * 远端登记的权威版本号。
     *
     * @return 版本号；{@code -1} = Redis 未配置 / 键不存在 / 读取失败（调用方按"无从判断"处理）
     */
    public long remoteVersion() {
        if (!infraSettingsHolder.isRedisConfigured()) {
            return -1;
        }
        try {
            String value = redis.opsForValue().get(VERSION_KEY);
            return value == null ? -1 : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("[域版本信号] 读取远端版本失败（Redis 不可用？）：{}", e.getMessage());
            return -1;
        }
    }
}
