package com.zzkingcc.domainplane.toolprovider;

/**
 * 工具贡献者（Spring Bean）—— 客户应用实现它来声明自己提供的工具
 * @author zzkingcc
 */
@FunctionalInterface
public interface ToolInstanceContributor {

    /** 声明本应用提供的工具（启动装配期调用一次） */
    void contribute(ToolRegistrar registrar);
}
