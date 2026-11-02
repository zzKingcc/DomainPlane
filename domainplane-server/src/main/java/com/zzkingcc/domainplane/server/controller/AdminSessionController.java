package com.zzkingcc.domainplane.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisSessionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话侧的运维接口（F04-a 装配清单 / F04-c 体量监控与导出副本）。
 *
 * <p><b>这一组接口全是只读</b>：没有清会话、没有删记忆、没有瘦身。
 * 存储无限增长是 accepted cost（记忆/归档只增不减是用户硬约束），
 * 运维侧需要的是"看得见 + 搬得走"，不是"删得掉"。</p>
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin/sessions")
public class AdminSessionController {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionController.class);

    private final RedisSessionStorage sessionStorage;
    private final AssemblyReportStore assemblyReportStore;
    private final ObjectMapper objectMapper;

    public AdminSessionController(RedisSessionStorage sessionStorage,
                                  AssemblyReportStore assemblyReportStore,
                                  ObjectMapper objectMapper) {
        this.sessionStorage = sessionStorage;
        this.assemblyReportStore = assemblyReportStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 体量最大的若干会话（F04-c：看得见）。
     *
     * @param limit 返回条数（默认 20）
     */
    @GetMapping("/storage")
    public Map<String, Object> storage(@RequestParam(required = false) Integer limit) {
        int n = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        List<RedisSessionStorage.Snapshot> sessions = sessionStorage.topBySize(n);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("sessions", sessions);
        return body;
    }

    /**
     * 某会话的装配清单（F04-a："它为什么忘了"的排障入口）。
     *
     * @param domain    域路径；留空时按 sessionId 反查
     * @param sessionId 会话标识
     * @param limit     条数（默认 10，上限 {@link AssemblyReportStore#KEEP_PER_SESSION}）
     */
    @GetMapping("/assembly")
    public Map<String, Object> assembly(@RequestParam(required = false) String domain,
                                        @RequestParam(required = false) String sessionId,
                                        @RequestParam(required = false) Integer limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        Optional<String> key = resolveSessionKey(domain, sessionId);
        if (key.isEmpty()) {
            body.put("code", 1);
            body.put("error", "找不到该会话（sessionId=" + sessionId + "）");
            return body;
        }
        int n = limit == null || limit <= 0 ? 10 : Math.min(limit, AssemblyReportStore.KEEP_PER_SESSION);
        List<ContextAssemblyReport> reports = assemblyReportStore.recent(key.get(), n);
        body.put("code", 0);
        body.put("sessionKey", key.get());
        body.put("reports", reports);
        return body;
    }

    /**
     * 导出一个会话的副本（F04-c：搬得走）。
     *
     * <p>导出的是<b>归档全轮 + 装配清单</b>，不含记忆消息列表：
     * 归档本来就是全流程档案（用户输入 / 回答 / 工具流 / 用量），
     * 记忆是它的一个派生的近况视图，副本里带档案就够复盘，也避免把媒体内容块再抄一份。</p>
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String domain,
                                         @RequestParam(required = false) String sessionId) {
        Optional<String> key = resolveSessionKey(domain, sessionId);
        if (key.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<RedisSessionStorage.Export> exported = sessionStorage.export(key.get());
        if (exported.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(exported.get());
            String filename = "session-" + sanitize(key.get()) + ".json";
            log.info("[管控] 导出会话副本：sessionKey={}，{} 轮，{} 字节",
                    key.get(), exported.get().turns().size(), bytes.length);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bytes);
        } catch (Exception e) {
            log.error("[管控] 导出会话副本失败（sessionKey={}）：{}", key.get(), e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 会话键解析：给了域就拼（O(1)），没给域就反查（SCAN）。
     *
     * <p>两个参数都空直接返回空，不做"默认域"猜测——猜出来的会话键拿去导副本，
     * 导到的就是别人的对话。</p>
     */
    private Optional<String> resolveSessionKey(String domain, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String sid = sessionId.trim();
        if (domain != null && !domain.isBlank()) {
            return Optional.of(SessionKeys.of(domain.trim(), sid));
        }
        return sessionStorage.findSessionKey(sid);
    }

    /** 文件名里只留安全字符：sessionKey 含域分隔符与可能的中文，直接进 filename 会出问题 */
    private static String sanitize(String sessionKey) {
        StringBuilder sb = new StringBuilder();
        for (char c : sessionKey.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        byte[] ascii = sb.toString().getBytes(StandardCharsets.UTF_8);
        return new String(ascii, StandardCharsets.UTF_8);
    }
}
