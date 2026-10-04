package com.zzkingcc.domainplane.toolprovider.spring;

import com.zzkingcc.domainplane.toolprovider.ToolInstanceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 心跳开关：装配完成后启动，容器关闭时停止
 *
 * @author zzkingcc
 */
public class ToolInstanceBootstrap implements SmartInitializingSingleton, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ToolInstanceBootstrap.class);

    private final ToolInstanceClient client;

    public ToolInstanceBootstrap(ToolInstanceClient client) {
        this.client = client;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (client.toolNames().isEmpty()) {
            // 只提醒不阻断：一个"暂时还没写工具"的实例是合法的（它在等服务端那边先上线）
            log.warn("[工具实例] 已启用工具实例但没有任何工具被声明："
                    + "请实现 ToolInstanceContributor 并交给 Spring 扫描");
        }
        client.start();
    }

    @Override
    public void destroy() {
        client.stop();
    }
}
