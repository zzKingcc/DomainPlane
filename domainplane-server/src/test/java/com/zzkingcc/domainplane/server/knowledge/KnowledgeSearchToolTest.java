package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.tool.ParamSchemaResolver;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.domain.capability.knowledge.KnowledgeSearchService;
import com.zzkingcc.domainplane.runtime.tool.AnnotatedToolScanner;
import com.zzkingcc.domainplane.runtime.tool.ToolRegistry;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索工具的注册形态与委托行为：
 * 扫出来的描述符决定模型"看得到、调得对"，委托决定关键词不被二次加工。
 */
class KnowledgeSearchToolTest {

    /** 记录收到的关键词，按脚本返回内容 —— 不 mock，lambda 桩足够 */
    private static final class RecordingRetriever implements ContentRetriever {

        private final List<String> queries = new ArrayList<>();
        private final List<Content> contents;

        private RecordingRetriever(List<Content> contents) {
            this.contents = contents;
        }

        @Override
        public List<Content> retrieve(Query query) {
            queries.add(query.text());
            return contents;
        }
    }

    private static Content chunk(String text, String fileName, String section) {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put(ChunkMetadataKeys.FILE_NAME, fileName);
        md.put(ChunkMetadataKeys.SECTION_TITLE, section);
        return Content.from(TextSegment.from(text, Metadata.from(md)));
    }

    private static KnowledgeSearchTool toolOf(RecordingRetriever retriever) {
        return new KnowledgeSearchTool(new KnowledgeSearchService(retriever));
    }

    private static ToolDescriptor descriptorOf(KnowledgeSearchTool tool) {
        List<ToolRegistry.Registered> registered = AnnotatedToolScanner.scan(tool);
        assertEquals(1, registered.size(), "应只注册一个工具");
        return registered.get(0).descriptor();
    }

    @Test
    void 扫描注册_名称说明域副作用审批都是检索工具应有的形态() {
        ToolDescriptor d = descriptorOf(toolOf(new RecordingRetriever(List.of())));

        assertEquals("searchKnowledge", d.name());
        assertFalse(d.description().isBlank(), "desc 是模型判断何时调用的唯一依据，不能空");
        // 不声明域 = 挂根域、全树可见；检索范围靠执行时绑定的 RetrievalScope（本轮域）收窄
        assertTrue(d.visibleIn("default"), "根域必须可见");
        assertTrue(d.visibleIn("default.sales"), "子域按累加语义可见（父域知识子域天然可用）");
        assertEquals(ToolDescriptor.Approval.none().mode(), d.approval().mode(), "只读检索免审批");
        assertFalse(d.requiresApproval());
        assertEquals("READ", d.sideEffect().name());
    }

    @Test
    void 参数带说明且必填_不触发缺说明告警() {
        ToolDescriptor d = descriptorOf(toolOf(new RecordingRetriever(List.of())));

        assertEquals(1, d.params().size());
        ToolDescriptor.Param keyword = d.params().get(0);
        assertEquals("keyword", keyword.name());
        assertEquals("string", keyword.type());
        assertTrue(keyword.required());
        // 缺说明只会 WARN 不阻断，但模型只能靠参数名猜 —— 这里必须真有说明
        assertEquals(0, ParamSchemaResolver.countMissingDescription(d.params()));
    }

    @Test
    void 关键词原样透传_结果带切片正文与来源() {
        RecordingRetriever retriever = new RecordingRetriever(
                List.of(chunk("退款时效为 7 个工作日", "退款政策.md", "时效说明")));
        KnowledgeSearchTool tool = toolOf(retriever);

        String result = tool.search("会员退款时效是多久");

        assertEquals(List.of("会员退款时效是多久"), retriever.queries,
                "keyword 必须原样进检索服务，不能在工具层改写");
        assertTrue(result.contains("退款时效为 7 个工作日"), "切片正文要进结果");
        assertTrue(result.contains("退款政策.md"), "来源文件名要进结果（提示词要求标注来源）");
        assertTrue(result.contains("时效说明"), "章节标题要进结果");
    }

    @Test
    void 未命中时原样返回提示_由模型如实转告用户() {
        KnowledgeSearchTool tool = toolOf(new RecordingRetriever(List.of()));

        assertEquals("未检索到相关内容", tool.search("一个不存在的问题"));
    }
}
