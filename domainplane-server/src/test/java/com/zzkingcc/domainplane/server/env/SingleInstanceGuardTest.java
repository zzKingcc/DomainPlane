package com.zzkingcc.domainplane.server.env;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单实例守卫：抢不到租约就拒绝启动。
 *
 * <p>理由见 {@link SingleInstanceGuard} —— 多实例共用 Redis 的失败方式不是报错，
 * 而是会话占用、stop、流与工具注册表<b>悄悄错乱</b>。既然当前只支持单体，
 * 就让它在启动期变成一个响亮的失败。</p>
 */
class SingleInstanceGuardTest {

    /** 手写桩：不需要 Redis，也不需要 Mockito（本模块没有这两个测试依赖） */
    private static final class FakeLeaseStore implements SingleInstanceGuard.LeaseStore {

        private final boolean acquirable;
        private final RuntimeException failWith;
        private final AtomicBoolean released = new AtomicBoolean(false);
        String releasedHolder;

        FakeLeaseStore(boolean acquirable) {
            this.acquirable = acquirable;
            this.failWith = null;
        }

        FakeLeaseStore(RuntimeException failWith) {
            this.acquirable = false;
            this.failWith = failWith;
        }

        @Override
        public boolean tryAcquire(String holderId, Duration ttl) {
            if (failWith != null) {
                throw failWith;
            }
            return acquirable;
        }

        @Override
        public String currentHolder() {
            return "someone-else";
        }

        @Override
        public void releaseIfMine(String holderId) {
            released.set(true);
            releasedHolder = holderId;
        }
    }

    private static final Duration TTL = Duration.ofSeconds(90);

    @Test
    void 租约已被占用时拒绝启动() {
        FakeLeaseStore leases = new FakeLeaseStore(false);
        SingleInstanceGuard guard = new SingleInstanceGuard(leases, TTL, true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, guard::acquire);
        assertTrue(ex.getMessage().contains("另一个 DomainPlane 实例"),
                "报错要说清是检测到多实例，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("domainplane.single-instance-guard=false"),
                "报错要给可操作的出路，实际: " + ex.getMessage());
    }

    @Test
    void 抢到租约时正常启动() {
        FakeLeaseStore leases = new FakeLeaseStore(true);

        assertDoesNotThrow(() -> new SingleInstanceGuard(leases, TTL, true).acquire());
    }

    @Test
    void 抢到租约后关闭会释放它() {
        FakeLeaseStore leases = new FakeLeaseStore(true);
        SingleInstanceGuard guard = new SingleInstanceGuard(leases, TTL, true);
        guard.acquire();

        guard.close();

        assertTrue(leases.released.get(), "优雅停机必须释放租约，否则重启要白等一个 TTL");
        assertFalse(leases.releasedHolder.isEmpty(), "释放必须是带自身标识的比较删除");
    }

    @Test
    void 守卫关闭时不做任何检查() {
        // 即便明确返回"抢不到"，关闭后也必须放行
        FakeLeaseStore leases = new FakeLeaseStore(false);

        assertDoesNotThrow(() -> new SingleInstanceGuard(leases, TTL, false).acquire());
    }

    @Test
    void Redis不可用时不拦启动() {
        FakeLeaseStore leases = new FakeLeaseStore(new RuntimeException("connection refused"));

        // Redis 连不上是"连接问题"，由别处报错，不该由本守卫拦停
        assertDoesNotThrow(() -> new SingleInstanceGuard(leases, TTL, true).acquire());
    }

    @Test
    void 未持有租约时关闭是空操作() {
        FakeLeaseStore leases = new FakeLeaseStore(false);
        SingleInstanceGuard guard = new SingleInstanceGuard(leases, TTL, true);

        assertDoesNotThrow(guard::close);
        assertFalse(leases.released.get(), "没拿到租约就不该去删别人的");
    }
}