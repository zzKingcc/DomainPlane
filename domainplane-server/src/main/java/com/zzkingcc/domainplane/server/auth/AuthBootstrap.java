package com.zzkingcc.domainplane.server.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 账号启动期初始化。
 * @author zzkingcc
 */
@Slf4j
@Configuration
public class AuthBootstrap {

    @Bean
    public SmartInitializingSingleton domainplaneAccountBootstrap(AccountStore accountStore) {
        return () -> {
            try {
                AccountStore.StoreState state = accountStore.ensureInitialized();
                if (state == AccountStore.StoreState.ABSENT) {
                    log.info("[账号] 当前无账号，闸门放开：可访问 /console/login.html 完成首次初始化");
                } else if (state == AccountStore.StoreState.CORRUPTED) {
                    log.warn("[账号] 账号文件已损坏，进入降级状态：受保护接口一律拒绝、初始化入口关闭。"
                            + "修复或删除该文件后重启即可恢复");
                }
            } catch (Exception e) {
                // 账号目录不可写等情况：不阻断启动，退化为"从未初始化"（放行 + 开放初始化入口），
                // 用户仍能进管控台排查；此时不会有任何账号数据被覆盖
                log.error("[账号] 启动期初始化失败，本次按【无账号】处理（不阻断启动）: {}", e.getMessage(), e);
            }
        };
    }
}
