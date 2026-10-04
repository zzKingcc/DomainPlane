package com.zzkingcc.domainplane.infrastructure.ingestion.processor;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 切片结果：分片出来的片段 + 本次分片的诊断计数。
 *
 * <p>诊断计数（识别到的标题数、删掉的行数）会一路回传到上传接口的返回值，
 * 让批量上传时不用翻日志也能看出切片是否正常。</p>
 *
 * @param segments    切片片段
 * @param sections    识别到的标题数
 * @param droppedLines 清洗阶段删掉的行数
 * @author zzkingcc
 */
public record SplitResult(List<TextSegment> segments, int sections, int droppedLines) {

    public static SplitResult of(List<TextSegment> segments) {
        return new SplitResult(segments, 0, 0);
    }

    public static SplitResult empty() {
        return new SplitResult(List.of(), 0, 0);
    }
}
