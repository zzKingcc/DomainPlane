package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.domain.capability.knowledge.KnowledgeSearchService;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具：把域层的检索能力暴露成模型可调用的工具。
 *
 * <p>为什么工具类在 server 而能力在 domain：{@link KnowledgeSearchService} 是纯能力组件
 * （只回答"检索到什么"），"以什么名字、什么口径暴露给模型"是服务端的装配决策 ——
 * 与 {@code ToolConfiguration} 扫描服务端 Bean 注册工具的机制配套，域层不背工具注解。</p>
 *
 * <p>域策略：不声明 domains（挂根域、按累加语义全树可见）。检索范围不由工具可见性决定，
 * 而由 tools 节点执行时绑定的 {@code RetrievalScope}（= 本轮域）决定：按域链
 * （自身 + 全部祖先）查索引，挂在父域的知识子域天然可用 —— 与
 * {@code DomainChannelProvider} 的通道解析同构。要收紧授权时改成显式完整路径域即可。</p>
 *
 * <p>READ + 免审批：检索不改任何状态，直连 tools 节点执行，不触发 review 中断；
 * 检索不到 / 检索故障的两种口径由 {@link KnowledgeSearchService} 定义，模型按提示词准则
 * 如实转告用户，不编造结果。</p>
 *
 * @author zzkingcc
 */
@Component
public class KnowledgeSearchTool {

    private final KnowledgeSearchService searchService;

    public KnowledgeSearchTool(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Tool(value = "searchKnowledge",
            desc = "检索内部知识库，返回与问题最相关的文档片段（含来源文件名与章节标题）。"
                    + "凡涉及具体事实、政策、流程、价格、时限、承诺的问题，作答前必须先调用本工具取证；"
                    + "确认真相后结合片段回答并标注来源，检索不到就如实告知用户知识库中没有相关信息，"
                    + "不得凭记忆编造。",
            effect = Tool.Effect.READ)
    public String search(@ToolParam("检索关键词：优先直接使用用户问题的原文；"
            + "问题冗长时可提炼为简短关键词，不要自造用户没提过的概念") String keyword) {
        return searchService.searchKnowledgeBase(keyword);
    }
}
