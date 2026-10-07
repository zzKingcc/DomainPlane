package com.zzkingcc.domainplane.example.chatwindow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 01：对话框页面。
 *
 * 整个应用只有两样东西：一个 {@link ChatController}（收一句话、把事件流推回去）、
 * 一张聊天页面。凭证与 WebClient 都由对话 SDK 的自动装配提供，这里一行都不用写 ——
 * 引 jar 即生效，也正因为如此，本应用必须在服务端起来之后才能启动
 * （SDK 里有启动期连通性校验，不可关闭）。
 *
 * @author zzkingcc
 */
@SpringBootApplication
public class ChatWindowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatWindowApplication.class, args);
    }
}
