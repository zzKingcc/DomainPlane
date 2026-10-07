package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

/**
 * 一次导入的处理报告。
 *
 * 多这样一层是为了让切片质量能被上传方看见：识别到几个标题、删掉几行噪声。
 * 服务端是无人值守的批量上传，只能靠这些计数发现"这批文件切坏了"。
 *
 * @param documents    成功处理的文档数
 * @param sections     识别到的标题总数
 * @param droppedLines 清洗阶段删掉的行总数
 * @author zzkingcc
 */
public record IngestReport(int documents, int sections, int droppedLines) {

    public static final IngestReport EMPTY = new IngestReport(0, 0, 0);

    public IngestReport plus(IngestReport other) {
        if (other == null) {
            return this;
        }
        return new IngestReport(documents + other.documents(),
                sections + other.sections(),
                droppedLines + other.droppedLines());
    }
}
