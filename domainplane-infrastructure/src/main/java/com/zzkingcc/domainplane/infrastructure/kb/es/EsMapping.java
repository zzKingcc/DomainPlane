package com.zzkingcc.domainplane.infrastructure.kb.es;

/**
 * ES 索引 mapping（IK 分词器版本）。
 *
 * 从 {@code EsIndexManager.buildIkMapping} 原文平移，**一字不改**：改造的验收口径是
 * "改造前后检索行为零变化"，mapping 是行为的一部分——analyzer 换一个、字段权重动一点，
 * 命中集合就会漂，而基线比对失败时没人会先怀疑 mapping。
 *
 * metadata 用 {@code dynamic:false} + 全部字段显式声明：
 *
 *   • 防字段污染（切片器将来加字段不会悄悄变成 text 把检索带偏）；
 *   • {@code doc_id} 必须显式声明为可精确查询的类型：它一旦被动态映射成 text，
 *       删除/统计用的 {@code term(metadata.doc_id)} 就会失效：UUID 被分词后基本查不中。
 *
 * text 挂两个子字段：{@code keyword} 精确等值（留档）；
 * {@code standard} 标准分词器，救 ik 会把英文词/数字/订单号切碎的场景
 * （{@code ik_smart} 对 "SKU-10086"、"v2.1" 这类标识符切出来的词往往对不上）。
 *
 * 字段名必须与 {@code ChunkMetadataKeys} 一致。这里是 JSON 字面量，
 * 用不了常量，只能靠约定：所以 {@code EsMetadataMappingTest} 会把两边逐字比对，
 * 改名只改一处时那条测试会红。
 *
 * @author zzkingcc
 */
public final class EsMapping {

    private EsMapping() {
    }

    /**
     * 构造 IK 分词器版本的索引 mapping JSON。
     *
     * @param dims 向量维度
     */
    public static String build(int dims) {
        return """
                {
                  "mappings": {
                    "properties": {
                      "vector": {
                        "type": "dense_vector",
                        "dims": %d,
                        "index": true,
                        "similarity": "cosine"
                      },
                      "text": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart",
                        "fields": {
                          "keyword": {
                            "type": "keyword",
                            "ignore_above": 256
                          },
                          "standard": {
                            "type": "text",
                            "analyzer": "standard"
                          }
                        }
                      },
                      "metadata": {
                        "type": "object",
                        "dynamic": false,
                        "properties": {
                          "doc_id":          { "type": "keyword" },
                          "file_name":       { "type": "keyword" },
                          "file_name_lower": { "type": "keyword" },
                          "upload_time":     { "type": "keyword" },
                          "domain":          { "type": "keyword" },
                          "content_hash":    { "type": "keyword" },
                          "chunk_seq":       { "type": "integer" },
                          "chunk_total":     { "type": "integer" },
                          "page_from":       { "type": "integer" },
                          "section_title":   { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                          "section_path": {
                            "type": "text",
                            "analyzer": "ik_max_word",
                            "search_analyzer": "ik_smart",
                            "fields": {
                              "keyword": { "type": "keyword", "ignore_above": 512 }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(dims);
    }
}
