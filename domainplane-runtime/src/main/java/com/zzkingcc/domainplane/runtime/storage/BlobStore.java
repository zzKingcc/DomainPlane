package com.zzkingcc.domainplane.runtime.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 二进制大对象存储 —— 内核定义契约，介质是部署决策。
 *
 * <p>为什么接口在内核、实现在服务端：与 {@code MediaAttachmentStore}、{@code ModelResolver}
 * 同一条分工线。内核（编排层）只关心"按一个键存 / 取 / 删字节"；用本地磁盘还是
 * OSS/COS 跟着部署环境走，放服务端装配。本波唯一消费者是服务端的媒体暂存实现，
 * 但契约放内核——"存字节"要有一个不随介质漂移的定义位，后续文档原文、导出物
 * 都往这个口子上接，不必再把接口往下沉。</p>
 *
 * <p><b>键是不透明字符串</b>：接口只约定"同一个键指向同一个对象"，合法字符集与
 * 目录布局由实现定义（本地实现限定为相对路径、禁目录穿越）。调用方各自管键命名，
 * 如媒体暂存区用 {@code attachments/} 前缀 + 内容哈希引用名。</p>
 *
 * <p><b>流式读写</b>：{@link #put} 读完并关闭传入流；{@link #open} 返回的流由调用方
 * 关闭。暂存区现在的体量（上传上限 5MB）内存也扛得住，但接口按流设计——视频生成
 * 产物与后续文档原文没有小体积保证。</p>
 *
 * @author zzkingcc
 */
public interface BlobStore {

    /**
     * 写入一个对象；同键重复写是覆盖语义（幂等）。
     *
     * @param key  对象键
     * @param data 字节流；方法内部读完并关闭，失败时不留半截对象
     */
    void put(String key, InputStream data);

    /**
     * 写入一个对象，并告知长度。
     *
     * <p>远程实现（S3 兼容）需要长度才能走流式上传；不知道长度就只能整体读进内存。
     * 知道大小的调用方都应该走这个方法——原文档案 / 原始文件都是字节数组，长度本来就有。
     * 本地实现忽略该参数（默认委托给 {@link #put(String, InputStream)}）。</p>
     *
     * @param size 字节数；未知传负数
     */
    default void put(String key, InputStream data, long size) {
        put(key, data);
    }

    /**
     * 按键取回字节流。
     *
     * <p>取不到返回空——调用方要区分"从来没有 / 已被回收"与"存储故障"：
     * 前者按业务语义处置（如附件过期），后者应当抛出。</p>
     *
     * @return 字节流（调用方负责关闭）；不存在时为空
     */
    Optional<InputStream> open(String key);

    /** 删除一个对象；幂等，删不存在的键不报错 */
    void delete(String key);

    /** 对象是否存在 */
    boolean exists(String key);

    /**
     * 列举指定前缀下的全部键（调用方不依赖顺序）。
     *
     * <p>给"对账"用：孤儿回收要拿"存储里有的键"与"登记表里活着的键"比对。
     * 本地实现是目录遍历，远程实现是前缀 LIST（分页由实现吞掉）。
     * 空前缀 = 列举全部。</p>
     */
    List<String> listKeys(String prefix);

    /**
     * 对象的最后修改时间；取不到返回空。
     *
     * <p>主要给回收对账当竞态护栏：写入方"先落字节、后登记"，回收扫描撞进这个
     * 窗口会把还没登记的字节当孤儿删掉，回收方据此跳过年龄小于护栏线的对象。
     * 本地实现取文件 mtime，远程实现取对象元数据。</p>
     */
    Optional<Instant> lastModified(String key);
}
