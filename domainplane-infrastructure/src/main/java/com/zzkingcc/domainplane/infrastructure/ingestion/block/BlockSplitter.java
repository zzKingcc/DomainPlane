package com.zzkingcc.domainplane.infrastructure.ingestion.block;

import com.zzkingcc.domainplane.common.constant.ChunkMetadataKeys;
import com.zzkingcc.domainplane.infrastructure.ingestion.IngestSource;
import com.zzkingcc.domainplane.infrastructure.ingestion.processor.SplitResult;
import com.zzkingcc.domainplane.infrastructure.ingestion.txt.Chunker;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用切片层：把 {@link Block} 流打包成 {@link TextSegment}。
 *
 * <p><b>md / docx / pdf 共用这一份，且与 txt 共用同一个 {@link Chunker}</b> ——
 * 格式适配层负责把每种格式翻译成 {@code Block}，这里只管三件事：
 * 标题路径栈、可切 / 不可切的分派、句子级打包。</p>
 *
 * <p>三条定死的规则（别改回去）：</p>
 * <ol>
 *   <li><b>撞标题立刻断。</b>短的开篇段落不会被并进下一节 ——
 *       它是上一节的归属，合并会让模型看不出层级。</li>
 *   <li><b>代码块与表格整块独占。</b>一条 shell 命令被切成两半、一张表被拦腰截断，
 *       这条知识就废了；宁可让单片超过 {@code maxChars} 也不切。</li>
 *   <li><b>标题层级直接用格式给的。</b>{@code level} 由 {@code Block} 带进来，
 *       这里不猜、不归一化 —— md 的 {@code ###} 与 docx 的 {@code Heading 3} 都是 level 3。</li>
 * </ol>
 *
 * @author zzkingcc
 */
@Slf4j
public final class BlockSplitter {

    private final Chunker chunker;
    private final int maxChars;

    public BlockSplitter(int maxChars, int overlapSentences, int minChars) {
        this.maxChars = Math.max(1, maxChars);
        this.chunker = new Chunker(maxChars, overlapSentences, minChars);
    }

    /** 只出片段，不要诊断计数时用 */
    public List<TextSegment> split(IngestSource source, List<Block> blocks) {
        return splitWithStats(source, blocks, 0).segments();
    }

    /**
     * 打包一个文档的 Block 流。
     *
     * @param source       入库载具（只用它的 metadata 与文件名；二进制格式正文为空是正常的）
     * @param blocks       格式适配层产出的块序列
     * @param droppedLines 适配层丢掉的标记行数（原样带回 {@link SplitResult}，用于上传返回值诊断）
     */
    public SplitResult splitWithStats(IngestSource source, List<Block> blocks, int droppedLines) {
        if (blocks == null || blocks.isEmpty()) {
            return SplitResult.empty();
        }
        Packer packer = new Packer(stripExtension(source.fileName()));
        int titles = 0;

        for (Block block : blocks) {
            if (block.isTitle()) {
                titles++;
                // 撞标题立刻断：先把已攒的正文按旧路径出片，再更新路径栈
                packer.flushPending();
                packer.pushTitle(block.level(), block.text());
                continue;
            }
            if (block.atomic()) {
                packer.flushPending();
                if (block.kind() == BlockKind.TABLE) {
                    packer.emitTablePieces(block.text(), block.pageNo());
                } else {
                    packer.emitCodePieces(block.text(), block.pageNo());
                }
                continue;
            }
            if (block.splittable()) {
                String text = block.text().strip();
                if (!text.isEmpty()) {
                    packer.pending.add(new Pending(text, block.pageNo()));
                }
            }
        }
        packer.flushPending();

        List<TextSegment> segments = packer.build(source);
        log.info("[结构化切片] 文件[{}]：识别标题 {} 个，产出切片 {} 片，适配层丢标记 {} 行",
                source.fileName(), titles, segments.size(), droppedLines);
        return new SplitResult(segments, titles, droppedLines);
    }

    // ==================== 内部：打包状态机 ====================

    /** 待打包的一行正文 + 它的页码（只有 pdf 会带上页码，其余格式恒为 0） */
    private record Pending(String text, int pageNo) {
    }

    /** 一片待落地的切片（可变的，代码块那片还允许后续正文并入） */
    private static final class Piece {
        private final StringBuilder text;
        private final String path;
        private final String title;
        private final int pageNo;
        /** 是否允许后续正文并入（只有代码块的最后一片为 true） */
        private boolean acceptsText;

        private Piece(String text, String path, String title, int pageNo, boolean acceptsText) {
            this.text = new StringBuilder(text);
            this.path = path;
            this.title = title;
            this.pageNo = pageNo;
            this.acceptsText = acceptsText;
        }
    }

    private final class Packer {

        private final String fallback;
        /** 标题路径栈：下标 1~6 对应标题层级 1~6 */
        private final String[] stack = new String[7];
        /** 攒着的可切正文行（带页码：pdf 的段落要能落进 metadata.page_from） */
        private final List<Pending> pending = new ArrayList<>();
        private final List<Piece> pieces = new ArrayList<>();
        /** 当前路径 / 当前路径末节 */
        private Piece open;
        private int pageNo = 0;

        private Packer(String fallback) {
            this.fallback = fallback == null || fallback.isBlank() ? "(文档开头)" : fallback;
        }

        private void pushTitle(int level, String text) {
            int lv = Math.min(6, Math.max(1, level));
            stack[lv] = text;
            // 层级回退：新的一级标题要把所有更深层的路径段清掉
            for (int i = lv + 1; i <= 6; i++) {
                stack[i] = null;
            }
        }

        private String path() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 6; i++) {
                if (stack[i] != null) {
                    if (!sb.isEmpty()) {
                        sb.append(" > ");
                    }
                    sb.append(stack[i]);
                }
            }
            // 标题之前的开篇正文不能丢：整篇还没有标题时用文件名当路径
            return sb.isEmpty() ? fallback : sb.toString();
        }

        private String title() {
            String last = null;
            for (int i = 1; i <= 6; i++) {
                if (stack[i] != null) {
                    last = stack[i];
                }
            }
            return last == null ? fallback : last;
        }

        /** 攒的可切正文交给 Chunker（句子打包 + 重叠 + 尾片合并全部复用 txt 那套） */
        private void flushPending() {
            if (pending.isEmpty()) {
                return;
            }
            // 整批取首行的页码：一批就是一个 section 内的连续正文，起点即归属
            int page = pending.get(0).pageNo();
            List<String> lines = new ArrayList<>(pending.size());
            for (Pending item : pending) {
                lines.add(item.text());
            }
            pending.clear();
            List<String> chunks = chunker.chunk(lines);
            boolean first = true;
            for (String chunk : chunks) {
                // 只有紧跟着代码块的第一片才允许并入代码块那片
                addPiece(chunk, first, page, false);
                first = false;
            }
        }

        private void emitCodePieces(String raw, int blockPage) {
            List<String> parts = codeParts(raw);
            for (int i = 0; i < parts.size(); i++) {
                addPiece(parts.get(i), false, blockPage, i == parts.size() - 1);
            }
        }

        private void emitTablePieces(String raw, int blockPage) {
            for (String part : tableParts(raw)) {
                addPiece(part, false, blockPage, false);
            }
        }

        /**
         * @param tryMerge    是否尝试并入上一条代码块的最后一片
         * @param acceptsText 本片是否允许后续正文并入（代码块的最后一片）
         */
        private void addPiece(String text, boolean tryMerge, int blockPage, boolean acceptsText) {
            if (blockPage > 0 && pageNo == 0) {
                pageNo = blockPage;
            }
            if (tryMerge && open != null && open.acceptsText) {
                String merged = open.text + "\n" + text;
                if (count(merged) <= maxChars) {
                    open.text.setLength(0);
                    open.text.append(merged);
                    // 只并一次，避免后续 chunk 无上限地堆到同一片
                    open.acceptsText = false;
                    return;
                }
            }
            if (open != null) {
                open.acceptsText = false;
            }
            open = new Piece(text, path(), title(), pageNo, acceptsText);
            pieces.add(open);
            pageNo = 0;
        }

        private List<TextSegment> build(IngestSource source) {
            int total = pieces.size();
            List<TextSegment> segments = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                Piece piece = pieces.get(i);
                String path = piece.path;
                String body = piece.text.toString();
                // 与 txt 同一条约定：标题路径前置进正文，向量与 BM25 吃同一份文本
                String full = path.isEmpty() ? body : path + "\n\n" + body;

                Metadata metadata = source.metadata().copy();
                metadata.put(ChunkMetadataKeys.SECTION_PATH, path);
                metadata.put(ChunkMetadataKeys.SECTION_TITLE, piece.title);
                metadata.put(ChunkMetadataKeys.CHUNK_SEQ, i + 1);
                metadata.put(ChunkMetadataKeys.CHUNK_TOTAL, total);
                if (piece.pageNo > 0) {
                    metadata.put(ChunkMetadataKeys.PAGE_FROM, piece.pageNo);
                }
                segments.add(TextSegment.from(full, metadata));
            }
            return segments;
        }
    }

    // ==================== 不可切块的自有切法 ====================

    /**
     * 代码块超长时<b>按行切</b>：不按句切、不加重叠，每片重新包上围栏。
     *
     * <p>保留围栏（含语言标记）是为了让模型看得出这是一段代码而不是正文。</p>
     */
    private List<String> codeParts(String raw) {
        if (count(raw) <= maxChars) {
            return List.of(raw);
        }
        String[] lines = raw.split("\n", -1);
        boolean backtick = lines[0].startsWith("```");
        boolean fenced = backtick || lines[0].startsWith("~~~");
        if (!fenced) {
            return List.of(raw);
        }
        String fence = backtick ? "```" : "~~~";
        int from = 1;
        int end = lines.length;
        if (end > from && lines[end - 1].strip().equals(fence)) {
            end--;
        }
        if (end <= from) {
            return List.of(raw);
        }
        // 每片正文的预算要扣掉围栏本身的开销（开+闭+两个换行），否则切完仍然超上限
        int budget = maxChars - count(lines[0]) - count(fence) - 2;
        if (budget <= 8) {
            return List.of(raw);
        }

        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int used = 0;
        for (int i = from; i < end; i++) {
            String line = lines[i];
            int need = count(line);
            if (!current.isEmpty() && used + 1 + need > budget) {
                groups.add(current.toString());
                current.setLength(0);
                used = 0;
            }
            if (!current.isEmpty()) {
                current.append('\n');
                used++;
            }
            current.append(line);
            used += need;
        }
        if (!current.isEmpty()) {
            groups.add(current.toString());
        }

        List<String> parts = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            String open = i == 0 ? lines[0] : fence;
            parts.add(open + "\n" + groups.get(i) + "\n" + fence);
        }
        return parts;
    }

    /**
     * 表格超长时<b>按数据行切，每片重复表头行</b>。
     *
     * <p>不重复表头的话，第 2 片起就只剩孤零零的数据行，谁也不知道哪一列是哪一列。</p>
     */
    private List<String> tableParts(String raw) {
        if (count(raw) <= maxChars) {
            return List.of(raw);
        }
        String[] lines = raw.split("\n", -1);
        if (lines.length < 3) {
            return List.of(raw);
        }
        String header = lines[0] + "\n" + lines[1];
        int headLen = count(header);

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int used = headLen;
        for (int i = 2; i < lines.length; i++) {
            String row = lines[i];
            if (used + 1 + count(row) > maxChars && current.length() > header.length()) {
                parts.add(current.toString());
                current = new StringBuilder(header);
                used = headLen;
            }
            current.append('\n').append(row);
            used += 1 + count(row);
        }
        if (current.length() > header.length()) {
            parts.add(current.toString());
        }
        return parts.isEmpty() ? List.of(raw) : parts;
    }

    // ==================== 工具 ====================

    private static int count(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
