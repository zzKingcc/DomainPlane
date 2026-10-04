package com.zzkingcc.domainplane.toolprovider;

/**
 * 工具声明的登记口（由 SDK 传给 {@link ToolInstanceContributor}）
 *
 * @author zzkingcc
 */
@FunctionalInterface
public interface ToolRegistrar {

    /**
     * 登记一个工具（同一个工具名重复登记时以后一次为准，并打 WARN）
     *
     * @return 本登记口（可链式继续登记）
     */
    ToolRegistrar register(ToolSpec spec, ToolHandler handler);
}
