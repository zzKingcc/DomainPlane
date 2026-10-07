package com.zzkingcc.domainplane.runtime.tool;

import java.util.Set;

/**
 * 在线表条目 —— 服务端对一个工具实例的全部认知
 *
 * @author zzkingcc
 * @param instanceId     实例标识（由实例自报，重连需沿用同一个才能覆盖自己的旧副本）
 * @param endpoint       调用回流地址 —— 服务端按此地址 POST 让实例执行工具（Push 模式）
 * @param lastSeen       最近一次受理心跳的时间戳；超时判定只看它
 * @param toolNames      该实例本次声明提供的工具名（与 {@code ToolRegistry} 反向索引对应）
 * @param manifestDigest manifest 摘要 —— 快路径判等用：相同则"只刷 lastSeen、不进注册表"
 * @param state          存活状态
 */
public record InstanceSession(String instanceId,
                              String endpoint,
                              long lastSeen,
                              Set<String> toolNames,
                              String manifestDigest,
                              InstanceState state) {

    public InstanceSession {
        toolNames = toolNames == null ? Set.of() : Set.copyOf(toolNames);
    }

    /** 状态迁移（其余字段原样保留） */
    public InstanceSession withState(InstanceState newState) {
        return new InstanceSession(instanceId, endpoint, lastSeen, toolNames, manifestDigest, newState);
    }

    /** 安静时长（毫秒）——截至 {@code now} 有多久没收到心跳 */
    public long silentMillis(long now) {
        return Math.max(0L, now - lastSeen);
    }
}
