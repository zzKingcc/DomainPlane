package com.zzkingcc.domainplane.infrastructure.ingestion;

import dev.langchain4j.data.document.Metadata;

import java.util.Objects;

/**
 * 入库来源：一份待导入文件的「原始字节 + 文件名 + metadata（+ 文本类的解码结果）」。
 *
 * 本包的词根约定（三个词根各管一段，别混用）：
 *
 *   • {@code Ingest*} —— 导入过程：本类（来源）、{@code IngestReport}（结果）、
 *       {@code IngestLimits}（限额）。
 *   • {@code Document*} —— 文档实体：{@code DocumentProcessStrategy}（处理策略）、
 *       {@code DocumentItem}（已入库条目）。
 *   • {@code Doc*} —— 具体文件格式：{@code DocReader} / {@code DocxReader}，
 *       与 {@code ExcelReader} / {@code PdfReader} 同列。
 *
 * 驱动导入的组件叫 {@code DocumentIngestor}（"导入文档的组件"），与本类
 * （"待导入的来源"）是同一组词但相反顺序，因此本类不叫 {@code IngestDocument} ——
 * 那会让两种顺序同时存在，读代码时分不清谁是谁。
 *
 * 为什么不用 langchain4j 的 {@code Document}：docx / pdf 是二进制，而 {@code Document}
 * 只有 {@code text()}。把二进制塞进"统一字符集"那一步会有两种结果 ——
 *
 *   • 二进制恰好能通过严格 UTF-8 → 乱码入库。这是最糟的一种：静默成功，
 *       检索出来全是垃圾，日志里一条警告都没有。
 *   • 过不了 UTF-8 也过不了 GB18030 → 报"编码判不出"。用户按提示另存为 UTF-8 永远解决不了。
 *
 * 所以入口必须按类型分叉：文本类（txt / md）在入口一次性解码成 UTF-8 文本，
 * 二进制类（docx / pdf）绝不解码，原字节交给对应的解析器。
 *
 * @author zzkingcc
 */
public final class IngestSource {

    /** 二进制格式在上传返回值里的"编码"占位 —— 它压根没有文本编码这回事 */
    public static final String BINARY_ENCODING = "(binary)";

    private final byte[] content;
    private final String fileName;
    private final Metadata metadata;
    /** 文本类：已解码的 UTF-8 文本；二进制类：null */
    private final String text;
    private final String encoding;

    private IngestSource(byte[] content, String fileName, Metadata metadata, String text, String encoding) {
        this.content = Objects.requireNonNull(content, "content");
        this.fileName = fileName;
        this.metadata = metadata;
        this.text = text;
        this.encoding = encoding;
    }

    /**
     * 文本类：入口已把字节解码成 UTF-8 语义的文本。
     *
     * @param text     解码后的文本（全链路只处理 UTF-8）
     * @param encoding 实际识别到的源文件编码（只用于上传返回值的诊断）
     */
    public static IngestSource ofText(String text, byte[] content, String fileName,
                                        Metadata metadata, String encoding) {
        return new IngestSource(content, fileName, metadata, text == null ? "" : text, encoding);
    }

    /**
     * 二进制类：不解码，字节原样留给格式的解析器。
     */
    public static IngestSource ofBinary(byte[] content, String fileName, Metadata metadata) {
        return new IngestSource(content, fileName, metadata, null, BINARY_ENCODING);
    }

    /** 原始字节（文本类也留着，排查时能对得上） */
    public byte[] content() {
        return content;
    }

    /**
     * 已解码文本；二进制类返回 {@code null}。
     *
     * 返回 {@code null} 而不是空串，是为了让"这个格式不该有文本"变成显式状态 ——
     * 二进制策略要是误取了它，立刻 NPE 而不是静默导入一堆空切片。
     */
    public String text() {
        return text;
    }

    public String fileName() {
        return fileName == null ? "(unknown)" : fileName;
    }

    public Metadata metadata() {
        return metadata;
    }

    /** 上传返回值里的编码诊断值 */
    public String encoding() {
        return encoding == null ? "" : encoding;
    }

    /** 是否二进制载体（决定策略取 {@link #content()} 还是 {@link #text()}） */
    public boolean binary() {
        return text == null;
    }
}
