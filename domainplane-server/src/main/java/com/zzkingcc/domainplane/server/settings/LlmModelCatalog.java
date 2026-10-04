package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型目录查询 —— 走 <b>OpenAI 规范</b>的 {@code GET {baseUrl}/models}
 * @author zzkingcc
 */
@Component
public class LlmModelCatalog {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * 拉取模型 id 列表。
     */
    public List<String> listModelIds(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 baseUrl 或 API Key");
        }

        String url = stripTrailingSlash(baseUrl.trim()) + "/models";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("拉取模型列表被中断", e);
        } catch (Exception e) {
            // 含 URI.create 的非法地址、连接超时、DNS 失败等
            throw new IllegalStateException("请求 " + url + " 失败: " + e.getMessage(), e);
        }

        if (response.statusCode() / 100 != 2) {
            // 带上响应体：调用方要从中解析 error.code 翻译成用户可读的原因
            //（实测 401/403 的 body 就是 {"error":{"code":"invalid_api_key"}} 这类结构化报文）
            throw new IllegalStateException("请求 " + url + " 返回 HTTP " + response.statusCode()
                    + "：" + limit(response.body()));
        }

        List<String> ids = parseIds(response.body());
        if (ids.isEmpty()) {
            throw new IllegalStateException("响应中没有可用的模型条目: " + abbreviate(response.body()));
        }
        return ids;
    }

    private List<String> parseIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode data = mapper.readTree(body).path("data");
            if (data.isArray()) {
                for (JsonNode node : data) {
                    String id = node.path("id").asText(null);
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析模型列表响应失败: " + e.getMessage(), e);
        }
        return ids;
    }

    /** 容忍用户在地址末尾多打一个斜杠（OpenAI 规范的端点都是 {@code /models}，不能出现双斜杠） */
    private static String stripTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "(空)";
        }
        String flat = body.replaceAll("\\s+", " ");
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    /**
     * 供错误信息携带响应体用：留足长度让 JSON 保持完整可解析
     */
    private static String limit(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 1000 ? flat : flat.substring(0, 1000) + "…";
    }
}
