package com.zzkingcc.domainplane.toolprovider;

/**
 * 工具的实际执行体
 *
 * @author zzkingcc
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * @param argumentsJson 工具参数的 JSON 文本（服务端原样转发，可能为空对象 {@code {}}）
     * @return 结果文本／JSON 文本；会被原样回喂模型
     * @throws Exception 执行失败（异常信息会作为失败原因回喂模型，不回抛给用户）
     */
    String handle(String argumentsJson) throws Exception;
}
