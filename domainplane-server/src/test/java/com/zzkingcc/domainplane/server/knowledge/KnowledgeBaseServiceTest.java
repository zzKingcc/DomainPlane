package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.support.KbIndexes;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库的域声明与索引派生：
 * 域必须是从根域出发的完整路径，留空 = 根域；一域一索引，索引名由域路径确定性派生。
 */
class KnowledgeBaseServiceTest {

    @Test
    void 留空或全空白都归到根域() {
        assertEquals(Domains.DEFAULT, KnowledgeBaseService.normalizeDomain(null));
        assertEquals(Domains.DEFAULT, KnowledgeBaseService.normalizeDomain(""));
        assertEquals(Domains.DEFAULT, KnowledgeBaseService.normalizeDomain("   "));
    }

    @Test
    void 去空白后原样保留完整路径() {
        assertEquals("default.sales", KnowledgeBaseService.normalizeDomain(" default.sales "));
    }

    @Test
    void 非法路径直接抛异常而不是静默丢弃() {
        // 静默会把文档写到一个树里不存在的域上，结果是永远检索不到且不报错
        assertThrows(KnowledgeBaseException.class,
                () -> KnowledgeBaseService.normalizeDomain("sales"), "不从根出发");
        assertThrows(KnowledgeBaseException.class,
                () -> KnowledgeBaseService.normalizeDomain("*"), "通配不再是合法域");
        assertThrows(KnowledgeBaseException.class,
                () -> KnowledgeBaseService.normalizeDomain("default.sa les"), "段含空白");
    }

    @Test
    void 一域一索引_名字可复现且互不相同() {
        assertEquals(KnowledgeBaseService.indexOf("default"), KnowledgeBaseService.indexOf("default"));
        assertEquals(KnowledgeBaseService.indexOf("default.sales"),
                KnowledgeBaseService.indexOf(" default.sales "), "同一路径（含空白）派生同一个索引名");
        assertNotEquals(KnowledgeBaseService.indexOf("default.sales"),
                KnowledgeBaseService.indexOf("default.hr"));
        // 索引名里不带点号：域路径的点会被替换掉（点号在 ES 通配/隐藏索引场景下有歧义）
        assertTrue(KnowledgeBaseService.indexOf("default.sales").startsWith(KbIndexes.PREFIX));
        assertFalse(KnowledgeBaseService.indexOf("default.sales")
                .substring(KbIndexes.PREFIX.length()).contains("."));
    }

    @Test
    void 大小写不同的域派生出不同索引() {
        // 域路径大小写敏感，转小写后前缀会撞名，靠哈希后缀区分
        assertNotEquals(KnowledgeBaseService.indexOf("default.a"),
                KnowledgeBaseService.indexOf("default.A"));
    }

    @Test
    void 没有domain字段时按根域读() {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("file_name", "退款政策.md");
        assertEquals(Domains.DEFAULT, KnowledgeBaseService.domainOf(md),
                "未声明域的文档按根域算");
    }

    @Test
    void 能读出归属域() {
        assertEquals("default.sales",
                KnowledgeBaseService.domainOf(Map.of("domain", "default.sales")));
        assertEquals(Domains.DEFAULT,
                KnowledgeBaseService.domainOf(Map.of("domain", "  ")), "空值等同未声明");
    }
}
