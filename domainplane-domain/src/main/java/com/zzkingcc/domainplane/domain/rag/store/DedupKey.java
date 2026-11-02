package com.zzkingcc.domainplane.domain.rag.store;

/**
 * 去重身份 = 正文哈希 + 文件名。
 *
 * 为什么不是纯正文哈希：一次上传可能包含多个文件，两个文件里出现同一句话
 * （合同的标准条款、多份文件共用的表头）是常态。纯哈希会把后一个文件的这条切片
 * 当成"已存在"而丢弃——报告仍按文档数返回、日志仍写"写入完成"，用户完全看不出
 * 有内容没入库。加上文件名后：同一文档内的重复段落仍会去重（本来要做的），
 * "改了文件名重传同一份文档"不再是同一身份（可接受，文档管理侧本来就有
 * 同名校验与 replace 覆盖）。
 *
 * @param contentHash 正文 SHA-256（十六进制小写）
 * @param fileName    文件名；null 与空串归为同一身份（历史口径）
 *
 * @author zzkingcc
 */
public record DedupKey(String contentHash, String fileName) {

    public DedupKey {
        contentHash = contentHash == null ? "" : contentHash;
        fileName = fileName == null ? "" : fileName;
    }

    /**
     * 序列化形如旧实现的 {@code 哈希\n文件名}（分隔符 {@code \n}）。
     *
     * 保留这个形状是为了让改造前后的去重行为逐字节等价：文件名在入库时已被规范化，
     * 不含控制字符，不会与内容混淆。
     */
    public String key() {
        return contentHash + "\n" + fileName;
    }
}
