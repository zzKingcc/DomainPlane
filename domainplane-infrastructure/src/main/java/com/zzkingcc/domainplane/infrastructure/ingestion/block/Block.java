package com.zzkingcc.domainplane.infrastructure.ingestion.block;

import java.util.Objects;

/**
 * 块 —— 格式适配层与通用切片层之间的中间表示。
 *
 * <p><b>格式适配层只做一件事：{@code 文件 → List<Block>}</b>，
 * 标题层级由格式自己给出（md 的 {@code ###}、docx 的 {@code Heading 3}），而不是像 txt 那样靠正则猜。</p>
 *
 * @param kind   种类（决定可切 / 不可切）
 * @param level  仅 {@link BlockKind#TITLE} 用，取值范围 1~6；其余恒为 0
 * @param text   正文。{@code TABLE} = markdown 表格文本；{@code CODE} = 含围栏的整段代码；
 *               {@code IMAGE} = 占位描述（如 {@code [图片: 流程图]}）
 * @param pageNo 页码，只有 pdf 用；其余恒为 0
 * @author zzkingcc
 */
public record Block(BlockKind kind, int level, String text, int pageNo) {

    public Block {
        Objects.requireNonNull(kind, "kind");
        text = text == null ? "" : text;
    }

    public static Block title(int level, String text) {
        return new Block(BlockKind.TITLE, level, text, 0);
    }

    public static Block title(int level, String text, int pageNo) {
        return new Block(BlockKind.TITLE, level, text, pageNo);
    }

    public static Block paragraph(String text) {
        return new Block(BlockKind.PARAGRAPH, 0, text, 0);
    }

    public static Block paragraph(String text, int pageNo) {
        return new Block(BlockKind.PARAGRAPH, 0, text, pageNo);
    }

    public static Block listItem(String text) {
        return new Block(BlockKind.LIST_ITEM, 0, text, 0);
    }

    public static Block quote(String text) {
        return new Block(BlockKind.QUOTE, 0, text, 0);
    }

    public static Block code(String text) {
        return new Block(BlockKind.CODE, 0, text, 0);
    }

    public static Block table(String text) {
        return new Block(BlockKind.TABLE, 0, text, 0);
    }

    public static Block image(String alt) {
        return new Block(BlockKind.IMAGE, 0, imagePlaceholder(alt), 0);
    }

    public static Block image(String alt, int pageNo) {
        return new Block(BlockKind.IMAGE, 0, imagePlaceholder(alt), pageNo);
    }

    /** 是否按语意可再切（段落 / 列表项 / 引用 / 图片占位） */
    public boolean splittable() {
        return kind == BlockKind.PARAGRAPH
                || kind == BlockKind.LIST_ITEM
                || kind == BlockKind.QUOTE
                || kind == BlockKind.IMAGE;
    }

    /** 是否必须整块保留（代码块 / 表格） */
    public boolean atomic() {
        return kind == BlockKind.CODE || kind == BlockKind.TABLE;
    }

    public boolean isTitle() {
        return kind == BlockKind.TITLE;
    }

    /** 图片占位文本；alt 为空时退化成 {@code [图片]} */
    public static String imagePlaceholder(String alt) {
        String clean = alt == null ? "" : alt.strip();
        return clean.isEmpty() ? "[图片]" : "[图片: " + clean + "]";
    }
}
