package com.zzkingcc.domainplane.server.settings;

/**
 * 可热替换的连接实现
 * @author zzkingcc
 */
public interface InfraSwappable {

    /**
     * 按 {@link InfraSettingsHolder#current()} 重建内部实现并原子替换。
     */
    void swap();
}
