package com.zzkingcc.domainplane.runtime.tool;

/**
 * 按键取锁：固定条带数的锁数组。
 * @author zzkingcc
 */
final class StripedLocks {

    private static final int STRIPES = 64;

    private static final Object[] LOCKS = new Object[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new Object();
        }
    }

    private StripedLocks() {
    }

    /** 取该键对应的锁对象（同一键恒定返回同一把） */
    static Object of(String key) {
        int hash = key == null ? 0 : key.hashCode();
        return LOCKS[(hash & 0x7fffffff) % STRIPES];
    }
}
