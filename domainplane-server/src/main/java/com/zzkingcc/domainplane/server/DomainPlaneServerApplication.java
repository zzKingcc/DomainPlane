package com.zzkingcc.domainplane.server;

import ch.qos.logback.classic.Logger;
import com.zzkingcc.domainplane.api.support.KbIndexes;
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
        SpringApplication.run(DomainPlaneServerApplication.class, withPlatformDefaults(args));
    }

    /**
     * 在 Spring 装配前用 {@link RuntimeEnvironment} 动态探测运行系统，给两个存储目录注入平台默认兜底。
     */
    private static String[] withPlatformDefaults(String[] args) {
        applyPlatformDefault(args, "domainplane.settings.path", "DOMAINPLANE_SETTINGS_PATH", RuntimeEnvironment.defaultSettingsPath());
        applyPlatformDefault(args, "domainplane.logging.path", "DOMAINPLANE_LOG_PATH", RuntimeEnvironment.defaultLogPath());
        return args;
    }

    private static void applyPlatformDefault(String[] args, String key, String envName, String platformDefault) {
        if (System.getProperty(key) != null) {
            return;
        }
        if (System.getenv(envName) != null) {
            return;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--" + key + "=")) {
                return;
            }
        }
        System.setProperty(key, platformDefault);
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
     * 启动完成摘要
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        var env = event.getApplicationContext().getEnvironment();
        String port = env.getProperty("server.port", "9527");
        String logPath = storageLocations.logDir().toString();
        var infra = infraHolder.current();

        log.info("=" .repeat(72));
        log.info("DomainPlane 服务端启动成功");
        log.info("  监听端口    : {}", port);
        log.info("  Agent 接口  : http://localhost:{}/api/agent/**", port);
        log.info("  运行环境    : {}  [容器: {}]", RuntimeEnvironment.describe(), RuntimeEnvironment.containerRuntime());
        log.info("  存储目录    : {}（{}）",
                storageLocations.settingsDir().toAbsolutePath(), storageLocations.settingsSource());
        log.info("  日志目录    : {}（{}）",
                storageLocations.logDir().toAbsolutePath(), storageLocations.logSource());
        log.info("  账号鉴权    : {}", accountStatus());
        log.info("  管控台      : http://localhost:{}/console/login.html（未登录）/ admin.html", port);
        log.info("  已注册工具  : {} 个（其中 {} 个需人工授权）",
                toolRouter.toolDescriptors().size(),
                toolRouter.toolsRequiringApproval().size());
        log.info("  工具域      : {}", toolRouter.knownDomains());
        log.info("  Elasticsearch: {}   知识库索引 {}{}", infra.getEs().describe(), KbIndexes.WILDCARD,
                infraHolder.isEsConfigured() ? "（一域一索引，按需创建）" : "   ← 未配置，请到管控台「存储配置」页填写");
        log.info("  Redis        : {}{}", infra.getRedis().describe(),
                infraHolder.isRedisConfigured() ? "" : "   ← 未配置，请到管控台「存储配置」页填写");
        logFileStatus(logPath);
        log.info("=" .repeat(72));
    }

    /**
     * 打印文件日志的状态与开启方式。
     */
    private void logFileStatus(String logPath) {
        if (fileLoggingEnabled()) {
            log.info("  日志文件    : {}/domainplane-server.log（错误日志单独见 -error.log）", logPath);
            return;
        }
        log.info("  日志文件    : 未开启，仅输出控制台");
        log.info("             开启方式: 启动参数加 --logging.config=classpath:logback-file.xml");
        log.info("                       并用 DOMAINPLANE_LOG_PATH 指定目录（建议绝对路径，如 /var/log/domainplane）");
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
