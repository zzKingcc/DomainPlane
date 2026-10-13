package com.zzkingcc.domainplane.infrastructure.ingestion.block;

/**
 * 块的种类 —— 格式适配层产出的统一中间表示。
 *
 * txt 的管线是「猜结构」（靠正则认标题），md / docx / pdf 的管线是「读结构」
 * （格式里本来就有标题层级、表格、代码块）。中间加一层 {@code Block}，把各种格式翻译成同一套
 * 枚举 + 层级 + 文本，后面的打包、重叠、去重、导出就全格式共用了。
 *
 * 决定分片行为的是两个划分：
 *
 *   • 可切（{@link #PARAGRAPH} / {@link #LIST_ITEM} / {@link #QUOTE} / {@link #IMAGE}）：
 *       按语意攒行，交给 {@code Chunker} 按句子打包，与 txt 完全同一套规则。
 *   • 不可切（{@link #CODE} / {@link #TABLE}）：整块独占一片。代码块切成两半、
 *       表格去掉一半列，这条知识就废了。
 *
 *
 * @author zzkingcc
 */
public enum BlockKind {

    /**
     * 标题：层级来自格式自身（md 的 {@code #} 级数、docx 的 {@code HeadingN}），不是猜出来的。
     */
    TITLE,

    /** 普通段落 */
    PARAGRAPH,

    /**
     * 列表项：保留 {@code -} / {@code *} / {@code 1.} 这些标记，
     * 「会员权益包括：」后面跟一串列表项，标记本身就是检索线索。
     */
    LIST_ITEM,

    /**
     * 引用块：保留 {@code >} 标记（「注意」「警告」这类内容的载体）。
     */
    QUOTE,

    /** 代码块：整块不可切，超长才按行切 */
    CODE,

    /** 表格：整块不可切，超长按数据行切且每片重复表头 */
    TABLE,

    /** 图片：只有占位文本（{@code [图片: alt]}），不进 OCR */
    IMAGE
}
