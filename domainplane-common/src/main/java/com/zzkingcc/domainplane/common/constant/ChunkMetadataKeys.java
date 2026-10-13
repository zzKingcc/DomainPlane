package com.zzkingcc.domainplane.common.constant;

/**
 * 切片元数据（{@code TextSegment#metadata()}）的键名常量。
 *
 * 三模块（infrastructure 切片、domain 融合、server 导出）共用同一批键，
 * 只有 common 是三者共同依赖下层；若各处各写常量，键对不齐不会编译/启动失败，
 * 只会在运行时静默退化成默认值。
 *
 * 注意：改这里等于改 ES mapping，{@code metadata} 为 {@code dynamic:false} +
 * 全字段显式声明，新增字段必须重建存量索引。
 *
 * @author zzkingcc
 */
public final class ChunkMetadataKeys {

    /** 文档 ID（同一文档的多个切片共用） */
    public static final String DOC_ID = "doc_id";

    /** 原始文件名 */
    public static final String FILE_NAME = "file_name";

    /** 归属域 */
    public static final String DOMAIN = "domain";

    /** 标题层级路径，如 {@code 会员中心 > 退款} */
    public static final String SECTION_PATH = "section_path";

    /** 当前小节标题（单级） */
    public static final String SECTION_TITLE = "section_title";

    /** 切片序号，文档内从 1 开始；融合阶段的稳定排序二级键 */
    public static final String CHUNK_SEQ = "chunk_seq";

    /** 文档切片总数 */
    public static final String CHUNK_TOTAL = "chunk_total";

    /** 起始页码（仅 PDF），0 / 缺省表示无页码概念 */
    public static final String PAGE_FROM = "page_from";

    /**
     * 检索原始分写入 {@code TextSegment} metadata 用的键。
     *
     * 下划线前缀是为了与业务字段区分：它不是切片属性，不参与 ES mapping，
     * 只在检索结果对象上流转。
     */
    public static final String RAW_SCORE = "_retrieval_score";

    private ChunkMetadataKeys() {
    }
}
