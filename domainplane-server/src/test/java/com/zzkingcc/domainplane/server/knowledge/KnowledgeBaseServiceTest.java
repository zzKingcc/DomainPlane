package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.rag.store.KbChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库的纯函数口径：索引命名（知识体身份）+ 切片预览整理。
 *
 * <p>v2 起索引名不再由域派生：域是消费者，知识体才是数据身份。所以这里钉的是
 * "唯一名 → 索引名"的确定性、唯一性与形态约束；"域路径合法性"那组断言随
 * {@code normalizeDomain} 一起退场（域路径校验仍在 {@code Domains.validatePath}，
 * 由域管理链路覆盖）。</p>
 *
 * @author zzkingcc
 */
class KnowledgeBaseServiceTest {

    // ==================== 索引命名：知识体身份 ====================

    @Test
    void 同一体唯一名与id派生同一个索引名() {
        assertEquals(KbIndexes.nameOfBody("sales", "kb-sales"),
                KbIndexes.nameOfBody(" sales ", "kb-sales"), "首尾空白不影响派生");
        assertEquals(KbIndexes.nameOfBody("sales", "kb-sales"),
                KbIndexes.nameOfBody("SALES", "kb-sales"), "唯一名统一小写");
    }

    @Test
    void 不同唯一名或不同id派生出不同索引名() {
        assertNotEquals(KbIndexes.nameOfBody("sales", "kb-sales"),
                KbIndexes.nameOfBody("hr", "kb-hr"), "不同体不能共用一个集合");
        assertNotEquals(KbIndexes.nameOfBody("sales", "kb-a"),
                KbIndexes.nameOfBody("sales", "kb-b"),
                "唯一名相同也要靠 id 哈希区分（迁移前的同名体）");
    }

    @Test
    void 索引名只带前缀与安全字符_不含点号与中文() {
        String index = KbIndexes.nameOfBody("销售 知识库", "kb-1");
        assertTrue(index.startsWith(KbIndexes.PREFIX));
        String tail = index.substring(KbIndexes.PREFIX.length());
        assertFalse(tail.contains("."), "点号在 ES 通配 / 隐藏索引场景下有歧义：" + index);
        assertTrue(tail.matches("[a-z0-9-]+_[0-9a-f]{8}"),
                "形态固定为 <slug>_<8位哈希>，便于台账与排障：" + index);
    }

    @Test
    void 唯一名有明确形态约束() {
        assertNull(KbIndexes.validateSlug("sales"));
        assertNull(KbIndexes.validateSlug("kb-2024-q1"));
        assertTrue(KbIndexes.validateSlug("a").contains("长度"), "太短要拒");
        assertTrue(KbIndexes.validateSlug("-sales").contains("首尾"), "不能以连字符开头");
        assertTrue(KbIndexes.validateSlug("sales-").contains("首尾"), "不能以连字符结尾");
        assertTrue(KbIndexes.validateSlug("Sales").contains("小写"), "只允许小写");
        assertTrue(KbIndexes.validateSlug("sales kb").contains("小写"), "空格不合法");
    }

    @Test
    void 旧命名只服务迁移_与新命名不同() {
        String legacy = KbIndexes.legacyNameOf("default.sales");
        assertTrue(legacy.startsWith(KbIndexes.PREFIX));
        assertNotEquals(legacy, KbIndexes.nameOfBody("default-sales", "default-sales"),
                "旧命名不能与新命名撞车，否则迁移会把两份数据当成同一个集合");
    }

    // ==================== 看文档：切片预览的整理口径 ====================

    private static KbChunk chunk(int seq, String text) {
        return new KbChunk("id-" + seq, text, Map.of(ChunkMetadataKeys.CHUNK_SEQ, String.valueOf(seq)));
    }

    @Test
    void 预览按切片序号排序_返回顺序不可信时按序号显示() {
        var preview = KnowledgeBaseService.previewOf("doc-1", "idx",
                List.of(chunk(3, "三"), chunk(1, "一"), chunk(2, "二")), true, 100);
        assertEquals(List.of(1, 2, 3),
                preview.chunks().stream().map(KnowledgeBaseService.PreviewChunk::seq).toList(),
                "索引/档案返回顺序不可信，页面必须按 chunk_seq 显示");
        assertEquals("一", preview.chunks().get(0).text());
        assertEquals(3, preview.total(), "total 是全部切片数，不是返回的条数");
    }

    @Test
    void 预览截断到上限_但total仍报全部() {
        var preview = KnowledgeBaseService.previewOf("doc-1", "idx",
                List.of(chunk(1, "a"), chunk(2, "b"), chunk(3, "c")), false, 2);
        assertEquals(2, preview.chunks().size(), "最多回 cap 片，避免把上千片塞进弹层");
        assertEquals(3, preview.total());
        assertFalse(preview.fromArchive(), "缺档时前端要能显示「从索引现读」");
    }

    @Test
    void 缺序号与空正文按0和空串处理_不抛异常() {
        KbChunk noSeq = new KbChunk("id-x", null, Map.of());
        var preview = KnowledgeBaseService.previewOf("doc-1", "idx", List.of(noSeq, chunk(1, "一")), true, 10);
        assertEquals(2, preview.chunks().size());
        assertEquals(0, preview.chunks().get(0).seq(), "缺序号按 0，稳定排在最前面");
        assertEquals("", preview.chunks().get(0).text());
        assertEquals(0, preview.chunks().get(0).chars());
    }
}
