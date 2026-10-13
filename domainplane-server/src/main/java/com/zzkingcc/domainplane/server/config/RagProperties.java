package com.zzkingcc.domainplane.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * 知识库上传配置（平台自有，统一前缀 {@code domainplane.rag}）。
 *
 * 知识库索引是一域一索引，索引名由域路径确定性派生（见 {@code KbIndexes}），
 * 因此这里没有「索引名」配置：索引在首次上传时按需创建。
 *
 * @author zzkingcc
 */
@Data
@ConfigurationProperties(prefix = "domainplane.rag")
public class RagProperties {

    /**
     * 单个上传文件的大小上限（默认 5MB）。
     *
     * 必须与 {@code spring.servlet.multipart.max-file-size} 保持一致：
     * 容器会在请求进入 Controller 之前按 multipart 上限做拦截，两者不一致时
     * 这个校验形同虚设：1MB~5MB 的文件会被容器挡在门外，用户拿到的是
     * 一个与"文件太大"毫无关系的 500，而不是这里精心写的 60006 提示。
     *
     * yaml 里刻意没有显式配置本项：与 multipart 上限共用一个真相来源，
     * 避免只改一处的漂移。
     */
    private DataSize maxFileSize = DataSize.ofMegabytes(5);

    /**
     * 允许上传的扩展名白名单（小写）。
     *
     * 格式决定走哪条格式适配层：{@code txt} 靠正则猜结构，
     * {@code md} / {@code markdown} 由 Markdown 解析器、{@code docx} 与 {@code doc} 由 POI、
     * {@code pdf} 由 PDFBox、{@code xls} / {@code xlsx} 由 POI 读结构。
     * 加一种新格式 = 加一套适配器，不是给这个列表加一项就够了。
     *
     * {@code xls} 与 {@code xlsx} 由同一条策略覆盖：{@code WorkbookFactory} 按文件头判类型，
     * 两个扩展名共用一个适配器。
     */
    private List<String> allowedExtensions =
            List.of("txt", "md", "markdown", "docx", "doc", "pdf", "xls", "xlsx");

    /**
     * 单文件（单次上传）允许的最大切片数。
     *
     * 几百页的文档一次就能产出几千片，向量化是分批请求模型的：
     * 一次上传就能把导入队列堵死。超限时直接拒绝并提示拆分，不是"切一半留下"。
     */
    private int maxChunksPerDocument = 2000;

    /**
     * 切片参数（只这三项可调，其余阈值都是代码常量）。
     */
    private Chunking chunking = new Chunking();

    /**
     * 排队等待导入串行锁的最长时间（秒，默认 60）
     */
    private long ingestLockWaitSeconds = 60;

    /** 知识库导入线程池线程数 */
    private int ingestPoolSize = 2;

    /**
     * 导入任务队列容量。
     */
    private int ingestQueueCapacity = 16;

    /**
     * 切片参数。
     *
     * {@code maxChars} 是长度上限而不是固定长度：切点永远落在标题或句子上：
     * 撞到标题立刻断、整段不足上限就是实际字数。想强行凑满只能跨标题拼内容，反而让模型看不出层级。
     */
    @Data
    public static class Chunking {

        /** 单切片长度上限（字）。400 个中文字约合 400 token，对上限 512 token 的向量模型也安全 */
        private int maxChars = 400;

        /** 相邻切片重叠几句 */
        private int overlapSentences = 1;

        /** 尾片短于该值就并进前一片 */
        private int minChars = 60;
    }
}
