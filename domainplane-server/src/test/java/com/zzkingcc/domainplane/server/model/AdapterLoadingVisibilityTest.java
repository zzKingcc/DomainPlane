package com.zzkingcc.domainplane.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 适配机制整套下架后的回归保险丝。
 *
 * <p>以前这里钉的是"适配文件还在不在"；方向现在反过来：钉住它<b>确实没了</b>。
 * 少了这条，哪天有人把旧注册表或旧 controller 又拷回来，现象只是"卡片上莫名多出绿框"，
 * 没人会往"适配被复活了"这个方向想。</p>
 */
@DisplayName("适配机制已整套下架")
class AdapterLoadingVisibilityTest {

    @Test
    @DisplayName("ModelAdapterRegistry 不在 classpath 上")
    void registryClassIsGone() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.zzkingcc.domainplane.server.model.ModelAdapterRegistry"));
    }

    @Test
    @DisplayName("排障用的 /admin/model-adapters 控制器不在 classpath 上")
    void adapterControllerIsGone() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.zzkingcc.domainplane.server.controller.AdminModelAdapterController"));
    }
}
