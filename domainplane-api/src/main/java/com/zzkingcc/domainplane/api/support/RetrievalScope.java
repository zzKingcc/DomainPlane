package com.zzkingcc.domainplane.api.support;

import com.zzkingcc.domainplane.api.agent.Domains;

/**
 * 检索时使用的域（线程绑定）
 *
 * @author zzkingcc
 */
public final class RetrievalScope {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RetrievalScope() {}

    /**
     * 绑定本轮检索所用的域，空白按根域处理，检索会取「该域 + 全部祖先」上的内容。
     */
    public static void bind(String domain) {
        HOLDER.set(Domains.normalize(domain));
    }

    /** 当前域；未绑定时返回 {@code null}（表示不做域过滤） */
    public static String current() {
        return HOLDER.get();
    }

    /** 解除绑定（与 {@link #bind} 成对调用） */
    public static void clear() {
        HOLDER.remove();
    }
}
