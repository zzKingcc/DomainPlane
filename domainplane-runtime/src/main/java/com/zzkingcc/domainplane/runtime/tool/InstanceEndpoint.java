package com.zzkingcc.domainplane.runtime.tool;

/**
 * 工具副本地址 —— "哪个实例的哪个调用地址"
 *
 * @author zzkingcc
 * @param instanceId 实例标识；本地工具恒为 {@link #LOCAL_INSTANCE_ID}
 * @param endpoint   工具调用回流地址（Push 模式：服务端 POST 到此地址让实例执行工具）
 */
public record InstanceEndpoint(String instanceId, String endpoint) {

    /** 本地 Bean 工具的实例标识 */
    public static final String LOCAL_INSTANCE_ID = "local";

    /** 本地副本的地址占位：本地工具不经 HTTP 调用，执行器直接调 Bean 方法 */
    public static final String LOCAL_ENDPOINT = "local://in-process";

    private static final InstanceEndpoint LOCAL = new InstanceEndpoint(LOCAL_INSTANCE_ID, LOCAL_ENDPOINT);

    /**
     * 本地副本（单例）。
     */
    public static InstanceEndpoint local() {
        return LOCAL;
    }

    /** 是否为本地副本（本地副本无 HTTP 地址，远程执行器不应尝试路由到它） */
    public boolean isLocal() {
        return LOCAL_INSTANCE_ID.equals(instanceId);
    }
}
