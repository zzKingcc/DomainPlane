package com.zzkingcc.domainplane.server.settings;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzkingcc.domainplane.common.util.AtomicFiles;
import com.zzkingcc.domainplane.server.env.StorageLocations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基础设施存储配置的持久化存储
 * @author zzkingcc
 */
@Slf4j
@Component
public class InfraSettingsStore {

    /**
     * 读盘用的 mapper。
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} 必须关掉：这个文件解析失败会置 {@code loadFailed}
     * 并拒绝后续写入（防覆盖清空），也就是一个多出来的字段就能把整份存储配置变成只读。
     * 多一个不认识的键只该被忽略，不该让运维连改都改不了。</p>
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 旧版单槽配置迁移出来的连接 id（与 yaml 的默认后端 id 对齐） */
    private static final String LEGACY_ES_ID = "es-main";

    private static final String LEGACY_QDRANT_ID = "qdrant-main";

    private final Path settingsFile;

    /**
     * 最近一次读盘是否失败（解析出错或结果为 null）。
     *
     * 失败时 {@link #load()} 返回空设置，若此时照常 save 覆盖写，等于把坏文件"修"成空文件，
     * ES 地址、Redis 地址与密码全部永久消失。故置位后拒绝任何写入。
     */
    private volatile boolean loadFailed;

    public InfraSettingsStore(StorageLocations storage) {
        this.settingsFile = storage.settingsDir().resolve("infra-settings.json");
    }

    /** 读取设置；文件不存在或解析失败时返回空设置（不抛异常，让服务能起来） */
    public InfraSettings load() {
        if (!Files.exists(settingsFile)) {
            log.info("[存储配置] 未找到设置文件 {}，使用 yaml / 环境变量（或视为未配置）",
                    settingsFile.toAbsolutePath());
            loadFailed = false;
            return new InfraSettings();
        }
        try {
            String json = Files.readString(settingsFile, StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(json);
            InfraSettings settings = MAPPER.treeToValue(migrateLegacy(root), InfraSettings.class);
            if (settings == null) {
                loadFailed = true;
                log.error("[存储配置] {} 的解析结果为 null，按未配置继续（已禁止写入，避免覆盖清空）",
                        settingsFile.toAbsolutePath());
                return new InfraSettings();
            }
            loadFailed = false;
            log.info("[存储配置] 已加载设置文件 {}：连接 {} 个，知识体 {} 个，Redis={}",
                    settingsFile.toAbsolutePath(),
                    settings.connectionsOrEmpty().size(),
                    settings.bodiesOrEmpty().size(),
                    settings.getRedis() == null ? "未配置" : settings.getRedis().describe());
            return settings;
        } catch (IOException e) {
            // 读不出来不应阻断启动，否则用户连管控台都进不去，无法修复
            loadFailed = true;
            log.error("[存储配置] 读取设置文件失败，将以未配置状态启动（已禁止写入，可进管控台修正）: {}",
                    settingsFile.toAbsolutePath(), e);
            return new InfraSettings();
        }
    }

    /**
     * 旧格式 → 具名连接 + 知识体。三种旧格式都要认：
     *
     * <ol>
     *   <li>最早的单槽 {@code {"es":{…},"vectorStore":{…}}}：合成连接，再合成一个两路都指向
     *       ES 的知识体——改造前默认就是 ES 一条路包打天下（{@code vector-backend} 在 yaml
     *       里写的是 ES 后端）。</li>
     *   <li>中间版的 {@code {"sets":{id:{keyword,vector,text}}}}：<b>一个集正好对应一个知识体</b>
     *       （本来就是一个集的三个槽）。原文槽不迁——它已经不属于知识体了。</li>
     *   <li>一天的 {@code {"bodies":{id:{kind,connection}}}}（一个体一种用途）：拆回单槽体，
     *       {@code kind=text} 的体丢弃。</li>
     * </ol>
     *
     * <p>向量槽<b>刻意不迁到 Qdrant</b>：旧文件里那个填着地址的 {@code vectorStore}
     * 只说明"用户曾经填过"，不说明它在生效——把它填进向量槽等于升级时静默换后端，
     * 存量校验与检索会立刻全面落空。所以它作为一条<b>未绑定</b>的 Qdrant 连接留在表里，
     * 由用户在「知识体」里显式选。</p>
     */
    private static JsonNode migrateLegacy(JsonNode root) {
        if (!(root instanceof ObjectNode object)) {
            return root;
        }
        JsonNode existing = object.get("connections");
        boolean hasConnections = existing != null && existing.isObject() && !existing.isEmpty();
        if (hasConnections) {
            migrateSetsToBodies(object);
            migrateKindBodies(object);
            return object;
        }
        JsonNode legacyEs = object.get("es");
        JsonNode legacyVector = object.get("vectorStore");

        Map<String, JsonNode> connections = new LinkedHashMap<>();
        if (legacyEs != null && legacyEs.isObject() && hasText(legacyEs.path("host").asText(null))) {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("id", LEGACY_ES_ID);
            c.put("name", LEGACY_ES_ID);
            c.put("type", InfraSettings.TYPE_ELASTICSEARCH);
            c.set("es", legacyEs);
            connections.put(LEGACY_ES_ID, c);
        }
        if (legacyVector != null && legacyVector.isObject()
                && hasText(legacyVector.path("uri").asText(null))) {
            ObjectNode c = MAPPER.createObjectNode();
            c.put("id", LEGACY_QDRANT_ID);
            c.put("name", LEGACY_QDRANT_ID);
            c.put("type", InfraSettings.TYPE_QDRANT);
            c.set("vector", legacyVector);
            connections.put(LEGACY_QDRANT_ID, c);
        }
        if (connections.isEmpty()) {
            return root;
        }

        ObjectNode conns = MAPPER.createObjectNode();
        connections.forEach(conns::set);
        object.set("connections", conns);

        ObjectNode bodies = MAPPER.createObjectNode();
        if (connections.containsKey(LEGACY_ES_ID)) {
            bodies.set("default", bodyNode("default", "默认知识体", LEGACY_ES_ID, LEGACY_ES_ID));
        }
        object.set("bodies", bodies);
        object.remove("es");
        object.remove("vectorStore");
        object.remove("sets");
        object.remove("defaultSet");
        log.info("[存储配置] 已把旧的单槽配置迁移为具名连接与知识体：{}（默认知识体两路都指向 {}，"
                + "未绑定的 {} 请在「知识体」里显式选择）",
                connections.keySet(), LEGACY_ES_ID, LEGACY_QDRANT_ID);
        return object;
    }

    /**
     * 中间版存储集（三槽）→ <b>一个集 = 一个双路知识体</b>；幂等，已经是新格式时什么都不做。
     *
     * <p>原文槽（{@code text}）不迁：原文一律落平台内置 BlobStore，没有"配到哪台机器"这回事。</p>
     */
    private static void migrateSetsToBodies(ObjectNode object) {
        JsonNode sets = object.get("sets");
        JsonNode bodies = object.get("bodies");
        boolean hasBodies = bodies != null && bodies.isObject() && !bodies.isEmpty();
        if (sets == null || !sets.isObject() || sets.isEmpty() || hasBodies) {
            object.remove("sets");
            object.remove("defaultSet");
            return;
        }
        ObjectNode converted = MAPPER.createObjectNode();
        sets.fields().forEachRemaining(entry -> {
            String setId = entry.getKey();
            JsonNode set = entry.getValue();
            String label = set.path("name").asText(setId);
            String keyword = trimToNull(set.path("keyword").asText(null));
            String vector = trimToNull(set.path("vector").asText(null));
            if (keyword == null && vector == null) {
                return;
            }
            converted.set(setId, bodyNode(setId, label, keyword, vector));
        });
        object.set("bodies", converted);
        object.remove("sets");
        object.remove("defaultSet");
        log.info("[存储配置] 已把 {} 个存储集迁成 {} 个知识体（id 沿用餐 id；原文槽不再属于知识体）",
                sets.size(), converted.size());
    }

    /**
     * 一天版的"一个体一种用途"（{@code {id:{kind,connection}}}）→ 单槽知识体。
     *
     * <p>不把关键字体和向量体合并成一个双路体：它们 id 不同、可能各自被不同域绑着，
     * 合并等于替用户决定"这两个体是一套"。要保持原来的单路行为就是各留各的，
     * 用户在页面上把两路填进同一个体即可升级成混合召回。</p>
     */
    private static void migrateKindBodies(ObjectNode object) {
        JsonNode bodies = object.get("bodies");
        if (bodies == null || !bodies.isObject() || bodies.isEmpty()) {
            return;
        }
        boolean anyKind = false;
        var fields = bodies.fields();
        while (fields.hasNext()) {
            if (fields.next().getValue().has("kind")) {
                anyKind = true;
                break;
            }
        }
        if (!anyKind) {
            return; // 已经是双槽格式
        }
        ObjectNode converted = MAPPER.createObjectNode();
        int kept = 0;
        int dropped = 0;
        var it = bodies.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String id = entry.getKey();
            JsonNode node = entry.getValue();
            /* 不带 kind = 已经是双槽格式，原样搬过去。
               整体重写 converted，漏掉这一支就会把新格式的体一起删掉——
               同名体在页面上消失，绑它的域变成「知识体已失效」，且不报错。 */
            if (!node.has("kind")) {
                converted.set(id, node);
                kept++;
                continue;
            }
            String kind = node.path("kind").asText("").trim().toLowerCase();
            String name = node.path("name").asText(id);
            String connection = trimToNull(node.path("connection").asText(null));
            if (connection == null) {
                dropped++;
                continue;
            }
            if (InfraSettings.KIND_KEYWORD.equals(kind)) {
                converted.set(id, bodyNode(id, name, connection, null));
                kept++;
            } else if (InfraSettings.KIND_VECTOR.equals(kind)) {
                converted.set(id, bodyNode(id, name, null, connection));
                kept++;
            } else {
                dropped++; // text（及任何不认识的用途）：知识体不再有原文这一路
            }
        }
        object.set("bodies", converted);
        log.info("[存储配置] 已把单用途知识体改为双槽格式：保留 {} 个（含已是双槽的），丢弃 {} 个（原文体/未配连接）",
                kept, dropped);
        if (dropped > 0) {
            log.warn("[存储配置] 有 {} 个知识体在升级中被丢弃，绑着它们的域会显示「知识体已失效」，"
                    + "请到「域空间」重新选一个", dropped);
        }
    }

    private static ObjectNode bodyNode(String id, String name, String keyword, String vector) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", id);
        body.put("name", name);
        // 两路都可空：空槽显式写 null，让"这个体只有一路"在文件里看得见
        body.put("keyword", keyword);
        body.put("vector", vector);
        return body;
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    /**
     * 保存设置（覆盖写）。
     *
     * @throws IllegalStateException 写盘失败。必须让调用方感知：
     *         静默失败会让用户以为配置已生效，实际重启后丢失
     */
    public void save(InfraSettings settings) {
        if (loadFailed) {
            throw new IllegalStateException("存储配置文件无法解析，拒绝覆盖写入以免把既有配置清空："
                    + settingsFile.toAbsolutePath() + "；请修复或删除该文件后重启服务");
        }
        try {
            AtomicFiles.write(settingsFile, MAPPER.writeValueAsBytes(settings));
            log.info("[存储配置] 已保存到 {}：连接 {} 个，知识体 {} 个，Redis={}",
                    settingsFile.toAbsolutePath(),
                    settings.connectionsOrEmpty().size(),
                    settings.bodiesOrEmpty().size(),
                    settings.getRedis() == null ? "未配置" : settings.getRedis().describe());
        } catch (IOException e) {
            throw new IllegalStateException("存储配置保存失败：" + settingsFile.toAbsolutePath(), e);
        }
    }

    /** 设置文件路径（供管控台展示，便于运维确认落盘位置） */
    public String filePath() {
        return settingsFile.toAbsolutePath().toString();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
