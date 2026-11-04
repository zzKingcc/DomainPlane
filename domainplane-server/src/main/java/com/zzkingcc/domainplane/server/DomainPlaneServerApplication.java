package com.zzkingcc.domainplane.server;

import ch.qos.logback.classic.Logger;
import com.zzkingcc.domainplane.runtime.tool.ToolRouter;
import com.zzkingcc.domainplane.server.env.RuntimeEnvironment;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * DomainPlane 服务端启动类
 * @author zzkingcc
 */
@Slf4j
@SpringBootApplication
public class DomainPlaneServerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DomainPlaneServerApplication.class);
        // 用 defaultProperties 而不是 System.setProperty：系统属性优先级高于 application.yaml，
        // 部署方在 yaml 里显式写的路径会被平台默认值静默盖掉（而且没有任何提示）。
        // defaultProperties 的优先级最低：命令行参数、环境变量、yaml 都能正常覆盖它，
        // 这才是"兜底默认"该待的位置。
        java.util.Map<String, Object> defaults = new java.util.LinkedHashMap<>();
        defaults.put("domainplane.settings.path", RuntimeEnvironment.defaultSettingsPath());
        defaults.put("domainplane.logging.path", RuntimeEnvironment.defaultLogPath());
        application.setDefaultProperties(defaults);
        application.run(args);
    }

    private final ToolRouter toolRouter;
    private final com.zzkingcc.domainplane.server.settings.InfraSettingsHolder infraHolder;
    private final com.zzkingcc.domainplane.server.auth.AccountStore accountStore;
    private final StorageLocations storageLocations;

    public DomainPlaneServerApplication(ToolRouter toolRouter,
                                     com.zzkingcc.domainplane.server.settings.InfraSettingsHolder infraHolder,
                                     com.zzkingcc.domainplane.server.auth.AccountStore accountStore,
                                     StorageLocations storageLocations) {
        this.toolRouter = toolRouter;
        this.infraHolder = infraHolder;
        this.accountStore = accountStore;
        this.storageLocations = storageLocations;
    }

    /**
     * 启动完成摘要。
     *
     * <p>只陈述"这次启动实际生效的是什么"，不给操作指引（指引在管控台对应页面上）。
     * 每行都必须能回答一个排障问题：服务在哪、数据在哪、谁在管、检索走哪、连不上时会怎样。
     * 过时的入口（旧版 {@code /admin.html} 单页）与硬编码端口都不留在这里——
     * 端口一律取本次实际的 {@code server.port}。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var env = event.getApplicationContext().getEnvironment();
        String port = env.getProperty("server.port", "9527");
        var infra = infraHolder.current();

        log.info("=".repeat(72));
        log.info("DomainPlane 启动完成 | 端口 {} | {} [{}]",
                port, RuntimeEnvironment.describe(), RuntimeEnvironment.containerRuntime());
        log.info("  管控台  : http://localhost:{}/console/login.html", port);
        log.info("  接口    : http://localhost:{}/api/agent/**", port);
        log.info("  账号    : {}", accountStatus());
        log.info("  工具    : {} 个（{} 个需授权），域 {} 个",
                toolRouter.toolDescriptors().size(),
                toolRouter.toolsRequiringApproval().size(),
                toolRouter.knownDomains().size());
        log.info("  知识库  : {}", knowledgeStatus(infra));
        log.info("  Redis   : {}{}", infra.getRedis().describe(),
                infraHolder.isRedisConfigured() ? "" : "  ← 未配置");
        log.info("  目录    : {} 存储({}) | {} blob({}) | {} 日志({})",
                storageLocations.settingsDir().toAbsolutePath(), storageLocations.settingsSource(),
                storageLocations.blobDir().toAbsolutePath(), storageLocations.blobSource(),
                storageLocations.logDir().toAbsolutePath(), storageLocations.logSource());
        logFileStatus();
        log.info("=".repeat(72));
    }

    /**
     * 两路各绑了哪台实例。
     *
     * <p>取的是"平台默认那对"（第一个在这一路配了连接的知识体），不是逐域明细——
     * 启动日志只回答"默认会往哪写"，逐域的落点在管控台知识库页看。</p>
     */
    private String knowledgeStatus(com.zzkingcc.domainplane.server.settings.InfraSettings infra) {
        String keyword = describeSlot(infra, com.zzkingcc.domainplane.server.settings.InfraSettings.KIND_KEYWORD);
        String vector = describeSlot(infra, com.zzkingcc.domainplane.server.settings.InfraSettings.KIND_VECTOR);
        if (keyword == null && vector == null) {
            return "未配置（管控台「存储配置」建连接与知识体后生效）";
        }
        return (keyword == null ? "关键字 未绑" : "关键字 " + keyword)
                + " | "
                + (vector == null ? "向量 未绑" : "向量 " + vector);
    }

    private String describeSlot(com.zzkingcc.domainplane.server.settings.InfraSettings infra, String kind) {
        String id = infra.platformConnectionId(kind);
        if (id == null) {
            return null;
        }
        var conn = infra.connection(id);
        if (conn == null) {
            return id + "（已删除）";
        }
        return id + " " + conn.describe();
    }

    /** 文件日志是否开启；开了就只说落在哪，没开才说怎么开 */
    private void logFileStatus() {
        if (fileLoggingEnabled()) {
            log.info("  日志    : 控制台 + 文件（错误单独见 -error.log）");
            return;
        }
        log.info("  日志    : 仅控制台（开启文件日志：--logging.config=classpath:logback-file.xml）");
    }

    /** root logger 上是否挂着文件 appender —— 对应 {@code logback-file.xml} 的 FILE / ERROR_FILE */
    private static boolean fileLoggingEnabled() {
        if (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) instanceof Logger root) {
            return root.getAppender("FILE") != null || root.getAppender("ERROR_FILE") != null;
        }
        return false;
    }

    /**
     * 账号状态描述（只输出状态与账号名，不回显哈希与凭证）
     */
    private String accountStatus() {
        if (accountStore.state() == com.zzkingcc.domainplane.server.auth.AccountStore.StoreState.CORRUPTED) {
            return "账号文件损坏（读不出来：受保护接口一律拒绝、初始化入口关闭。"
                    + "请修复或删除 " + accountStore.filePath() + " 后重启）";
        }
        var account = accountStore.current();
        if (account == null) {
            return "未初始化（无账号文件：当前不鉴权，请到管控台完成初始化）";
        }
        return "已启用（账号 " + account.getUsername() + "，文件 " + accountStore.filePath() + "）";
    }
}
