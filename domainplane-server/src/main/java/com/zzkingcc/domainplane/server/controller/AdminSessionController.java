package com.zzkingcc.domainplane.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzkingcc.domainplane.api.conversation.AssemblyReportStore;
import com.zzkingcc.domainplane.api.conversation.ContextAssemblyReport;
import com.zzkingcc.domainplane.api.support.SessionKeys;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisSessionStorage;
import com.zzkingcc.domainplane.server.settings.SessionPurgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话侧的运维接口（F04-a 装配清单 / F04-c 体量监控与导出副本 / F04-d 分页查档 / 清空）。
 *
 * <p>查档、体量、导出都是只读；唯一的写操作是 {@link #clear} —— 按 sessionId 把一个会话
 * 在各存储里的痕迹清干净，前端必须二次确认。存储仍<b>不做自动淘汰</b>：无限增长是
 * accepted cost，删不删由人指名决定，不按体量排行、不批量、不定时。</p>
 *
 * @author zzkingcc
 */
@RestController
@RequestMapping("/admin/sessions")
public class AdminSessionController {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionController.class);

    private final RedisSessionStorage sessionStorage;
    private final AssemblyReportStore assemblyReportStore;
    private final SessionPurgeService sessionPurgeService;
    private final ObjectMapper objectMapper;

    public AdminSessionController(RedisSessionStorage sessionStorage,
                                  AssemblyReportStore assemblyReportStore,
                                  SessionPurgeService sessionPurgeService,
                                  ObjectMapper objectMapper) {
        this.sessionStorage = sessionStorage;
        this.assemblyReportStore = assemblyReportStore;
        this.sessionPurgeService = sessionPurgeService;
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
     * 按轮号区间取一页会话档案（F04-d：长会话分页拉取，只读）。
     *
     * <p>给 sessionId 就能查，域选填：填了直接拼键，不填走现有 SCAN 反查。
     * 不给 {@code from} 时落到最后一页（最新一轮），翻页由前端按 {@code total} 计算下一页起始轮号。</p>
     *
     * @param domain    域路径；留空时按 sessionId 反查
     * @param sessionId 会话标识
     * @param from      起始轮号，1 基；留空 = 最后一页
     * @param size      每页条数，默认 20，上限 50
     */
    @GetMapping("/turns")
    public Map<String, Object> turns(@RequestParam(required = false) String domain,
                                     @RequestParam(required = false) String sessionId,
                                     @RequestParam(required = false) Long from,
                                     @RequestParam(required = false) Integer size) {
        Map<String, Object> body = new LinkedHashMap<>();
        Optional<String> key = resolveSessionKey(domain, sessionId);
        if (key.isEmpty()) {
            body.put("code", 1);
            body.put("error", "找不到该会话（sessionId=" + sessionId + "）");
            return body;
        }
        RedisSessionStorage.Page p = sessionStorage.page(key.get(), from, size);
        /* 事实行的两个计数沿用既有快照（都是 O(1) 的 LLEN），不为此另建键或索引 */
        RedisSessionStorage.Snapshot snap = sessionStorage.snapshotOf(key.get());
        body.put("code", 0);
        body.put("sessionKey", p.sessionKey());
        body.put("total", p.total());
        body.put("from", p.from());
        body.put("size", p.size());
        body.put("memoryMessages", snap.memoryMessages());
        body.put("assemblyReportTotal", snap.assemblyReports());
        body.put("turns", p.turns());
        body.put("assemblyReports", p.assemblyReports());
        return body;
    }

    /** 清空请求体：域选填，与查档走同一个键解析，避免"删到别人的会话" */
    public record ClearRequest(String domain, String sessionId) {
    }

    /**
     * 清空指定会话的全部记忆痕迹（人工、单会话、前端二次确认后调用）。
     *
     * <p>解析不到会话键直接拒绝，不猜默认域。级联的六路清理与失败语义见
     * {@link SessionPurgeService#clear(String)}：中途失败不回滚，重跑一次即可。</p>
     */
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestBody(required = false) ClearRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        String sid = request == null ? null : request.sessionId();
        String domain = request == null ? null : request.domain();
        Optional<String> key = resolveSessionKey(domain, sid);
        if (key.isEmpty()) {
            body.put("code", 1);
            body.put("error", "找不到该会话（sessionId=" + sid + "）");
            return body;
        }
        try {
            SessionPurgeService.ClearResult r = sessionPurgeService.clear(key.get());
            body.put("code", 0);
            body.put("sessionKey", r.sessionKey());
            body.put("memoryMessages", r.memoryMessages());
            body.put("archiveTurns", r.archiveTurns());
            body.put("message", "已清空 " + r.sessionKey() + "：记忆 " + r.memoryMessages()
                    + " 条、归档 " + r.archiveTurns() + " 轮、断点 " + r.checkpointKeys()
                    + " 键、索引 " + r.indexKeys() + " 键、摘要 " + r.summaryKeys()
                    + " 键、装配清单 " + r.assemblyKeys() + " 键");
            return body;
        } catch (Exception e) {
            log.warn("[管控] 清空会话失败（sessionKey={}）：{}", key.get(), e.getMessage());
            body.put("code", 1);
            body.put("error", e.getMessage() == null ? "清空失败" : e.getMessage());
            return body;
        }
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
     * 导到的就是别人的对话；拿去清空更是删错会话。</p>
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
