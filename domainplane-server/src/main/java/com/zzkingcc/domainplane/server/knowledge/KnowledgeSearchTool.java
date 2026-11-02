package com.zzkingcc.domainplane.server.knowledge;

import com.zzkingcc.domainplane.api.annotation.Tool;
import com.zzkingcc.domainplane.api.annotation.ToolParam;
import com.zzkingcc.domainplane.api.tool.ToolDescriptor;
import com.zzkingcc.domainplane.domain.capability.knowledge.KnowledgeSearchService;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具：把域层的检索能力暴露成模型可调用的工具。
 *
 * <p>为什么工具类在 server 而能力在 domain：{@link KnowledgeSearchService} 是纯能力组件
 * （只回答"检索到什么"），"以什么名字、什么口径暴露给模型"是服务端的装配决策，
 * 与 {@code ToolConfiguration} 扫描服务端 Bean 注册工具的机制配套，域层不背工具注解。</p>
 *
 * <p><b>域策略：不挂根域，由"域绑了知识体"决定谁能看到它。</b>
 * 初始声明是哨兵 {@link ToolDescriptor#UNBOUND_DOMAIN}（对任何域都不可见），
 * 之后由 {@link KnowledgeToolBinder} 在启动、域配置变更、存储配置保存三个时点重算：
 * 绑了可用知识体的域才拿到这个工具，解绑或体失效就收回。原因很直接——一个没有知识库的域，
 * 模型却能调用"检索内部知识库"，它要么白调一次、要么被返回的"未检索到"误导着编造答案。
 * 声明在哪个域，它的子域按累加语义自动可见（这是对的：子域查得到父域的数据）。</p>
 *
 * <p>检索范围不由工具签名决定，而由 tools 节点执行时绑定的 {@code RetrievalScope}
 * （= 本轮域）决定：按域链（自身 + 全部祖先）查索引，每层各用自己绑的知识体。
 * 工具名不随知识体变化——体从单路改成双路，下一次调用自然走混合，不需要换一个工具。</p>
 *
 * <p>READ + 免审批：检索不改任何状态，直连 tools 节点执行，不触发 review 中断；
 * 检索不到 / 检索故障的两种口径由 {@link KnowledgeSearchService} 定义，模型按提示词准则
 * 如实转告用户，不编造结果。</p>
 *
 * @author zzkingcc
 */
@Component
public class KnowledgeSearchTool {

    /** 工具名（模型看到的函数名）。{@link KnowledgeToolBinder} 按它改可用域 */
    public static final String TOOL_NAME = "searchKnowledge";

    private final KnowledgeSearchService searchService;

    public KnowledgeSearchTool(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    @Tool(value = TOOL_NAME,
            desc = "检索内部知识库，返回与问题最相关的文档片段（含来源文件名与章节标题）。"
                    + "凡涉及具体事实、政策、流程、价格、时限、承诺的问题，作答前必须先调用本工具取证；"
                    + "确认真相后结合片段回答并标注来源，检索不到就如实告知用户知识库中没有相关信息，"
                    + "不得凭记忆编造。",
            effect = Tool.Effect.READ,
            domains = ToolDescriptor.UNBOUND_DOMAIN)
    public String search(@ToolParam("检索关键词：优先直接使用用户问题的原文；"
            + "问题冗长时可提炼为简短关键词，不要自造用户没提过的概念") String keyword) {
        return searchService.searchKnowledgeBase(keyword);
    }
}
