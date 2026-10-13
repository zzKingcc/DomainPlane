package com.zzkingcc.domainplane.infrastructure.elasticsearch;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-08：切片元数据的键名必须在 {@link ChunkMetadataKeys}、ES mapping、切片侧写入处三者间对齐。
 *
 * <p><b>为什么这条要专门守</b>：键名对不齐不会编译失败、不会启动失败、不报错，
 * 只在运行时静默退化成默认值。最典型的一处：融合阶段同分时的二级排序键是
 * {@code chunk_seq}，一旦读不到就恒等于 {@code Integer.MAX_VALUE}，
 * 于是「保证同一问题两次检索顺序一致」退化成按内容哈希排，内容一变顺序就跳，
 * 用户感知为答案在乱跳，而系统一切正常。</p>
 *
 * <p>ES mapping 是 JSON 字面量（{@code withJson} 的写法不支持插常量），
 * 所以 Java 侧的收口只能做到「常量唯一 + 有测试比对」，这一组测试就是那道比对。
 * 为了不为一个测试给 infrastructure 引入 Jackson，这里用正则读字段声明。</p>
 *
 * @author zzkingcc
 */
@DisplayName("D-08 切片元数据键名：常量 / mapping / 搬运三处必须一致")
class EsMetadataMappingTest {

    /**
     * metadata 段的字段声明，形如 {@code "chunk_seq":  { "type": "integer" }}。
     *
     * <p>要求 {@code "type"} <b>紧跟</b> {@code &#123;}：mapping 里所有字段都把 type 写在第一项，
     * 而 {@code "properties": &#123;} 后面跟的是子键名，用 {@code [^}]*} 跨配会把
     * 整段子字段的 type 记到 properties 名下（实测 doc_id 会被读成 null、
     * section_path 会被读成它的 keyword 子字段类型）。</p>
     */
    private static final Pattern FIELD_DECL = Pattern.compile(
            "\"(?<key>[A-Za-z0-9_]+)\"\\s*:\\s*\\{\\s*\"type\"\\s*:\\s*\"(?<type>[A-Za-z0-9_]+)\"");

    /** 参与切片-索引-检索全链路的业务字段（RAW_SCORE 是检索期产物，不进 mapping） */
    private static final List<String> CHAINED_KEYS = List.of(
            ChunkMetadataKeys.DOC_ID,
            ChunkMetadataKeys.FILE_NAME,
            ChunkMetadataKeys.DOMAIN,
            ChunkMetadataKeys.SECTION_PATH,
            ChunkMetadataKeys.SECTION_TITLE,
            ChunkMetadataKeys.CHUNK_SEQ,
            ChunkMetadataKeys.CHUNK_TOTAL,
            ChunkMetadataKeys.PAGE_FROM);

    /** 只截 metadata 段，避免顶层 vector / text 的声明混进来 */
    private static String metadataSection() {
        String mapping = EsIndexManager.buildIkMapping(1024);
        int start = mapping.indexOf("\"metadata\"");
        assertTrue(start > 0, "mapping 里必须有 metadata 段");
        return mapping.substring(start);
    }

    /** metadata 段里声明的「键 → 类型」 */
    private static java.util.Map<String, String> declaredTypes() {
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        Matcher m = FIELD_DECL.matcher(metadataSection());
        while (m.find()) {
            types.putIfAbsent(m.group("key"), m.group("type"));
        }
        return types;
    }

    private static String typeOf(String key) {
        return declaredTypes().get(key);
    }

    @Test
    @DisplayName("metadata 是 dynamic:false，且每个链路键都显式声明了类型")
    void mappingDeclaresEveryChainedKey() {
        assertTrue(metadataSection().contains("\"dynamic\": false"),
                "metadata 必须是 dynamic:false，否则新字段会被静默动态映射成 text 把检索带偏");

        java.util.Map<String, String> types = declaredTypes();
        List<String> missing = new ArrayList<>();
        for (String key : CHAINED_KEYS) {
            if (types.get(key) == null) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(),
                "这些键在 mapping 里没有显式声明，切片写进去的数据取不到：" + missing);
    }

    @Test
    @DisplayName("chunk_seq / chunk_total / page_from 必须是 integer（排序键取值的前提）")
    void numericKeysAreDeclaredInteger() {
        java.util.Map<String, String> types = declaredTypes();

        for (String key : List.of(ChunkMetadataKeys.CHUNK_SEQ,
                ChunkMetadataKeys.CHUNK_TOTAL, ChunkMetadataKeys.PAGE_FROM)) {
            assertEquals("integer", types.get(key),
                    key + " 必须是 integer；声明成 long 时下游按 Number 取值虽仍可行，"
                            + "但会让「ES 返回类型与切片写入类型一致」这条前提悄悄失效");
        }
    }

    @Test
    @DisplayName("doc_id / file_name 必须是 keyword（term 查询与等值过滤靠它）")
    void idAndFileNameAreKeyword() {
        java.util.Map<String, String> types = declaredTypes();

        assertEquals("keyword", types.get(ChunkMetadataKeys.DOC_ID),
                "doc_id 一旦被映射成 text，UUID 被分词后 term(metadata.doc_id) 基本查不中，"
                        + "表现为「删除文档没报错但删不掉」");
        assertEquals("keyword", types.get(ChunkMetadataKeys.FILE_NAME),
                "file_name 走 term/等值过滤，被映射成 text 会让文件名匹配失效");
    }

    @Test
    @DisplayName("section_path / section_title 是 text 且挂 ik 分词器")
    void sectionFieldsUseIkAnalyzer() {
        String section = metadataSection();

        for (String key : List.of(ChunkMetadataKeys.SECTION_PATH, ChunkMetadataKeys.SECTION_TITLE)) {
            assertEquals("text", typeOf(key), key + " 必须是 text（要参与分词检索）");
        }
        // section_title 是单行声明，section_path 是多行；两者都必须挂 ik
        assertTrue(section.contains("\"analyzer\": \"ik_max_word\""),
                "标题类字段建索引要用 ik_max_word");
        assertTrue(section.contains("\"search_analyzer\": \"ik_smart\""),
                "标题类字段查询要用 ik_smart");
    }

    @Test
    @DisplayName("RAW_SCORE 是检索期产物，不该出现在 mapping 里")
    void rawScoreIsNotPersisted() {
        assertFalse(declaredTypes().containsKey(ChunkMetadataKeys.RAW_SCORE),
                "原始检索分是每次查询算出来的，落进索引只会让下次写入把上一轮的分数带成脏数据");
    }

    @Test
    @DisplayName("键名常量与实际取值一致（防手滑写成别的字面量）")
    void constantValuesAreTheOnesInUse() {
        Set<String> expected = Set.of("doc_id", "file_name", "domain", "section_path",
                "section_title", "chunk_seq", "chunk_total", "page_from", "_retrieval_score");
        Set<String> actual = new LinkedHashSet<>();
        for (String key : CHAINED_KEYS) {
            actual.add(key);
        }
        actual.add(ChunkMetadataKeys.RAW_SCORE);

        assertEquals(expected, actual,
                "常量值被改动过 —— 改名等于改 ES mapping，必须同步 mapping 声明并重建存量索引");
    }
}
