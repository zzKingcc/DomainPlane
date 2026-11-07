package com.zzkingcc.domainplane.server.settings;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.BaseException;
import com.zzkingcc.domainplane.infrastructure.redis.conversation.RedisSessionStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单会话清除的编排：六路不能漏、顺序不能乱、在飞会话必须拦、失败要给出可重试的文案。
 *
 * <p>本项目测试不引 Mockito，所以走 {@link SessionPurgeService.Targets} 注入动作桩，
 * 不拖 Redis 具体类进来。</p>
 */
@DisplayName("单会话清除：六路级联 / 在飞拦截 / 失败可重试")
class SessionPurgeServiceTest {

    private static final String SESSION = "default.sales|s-1";

    private static final class Rig {
        final List<String> calls = new ArrayList<>();
        Set<String> running = Set.of();
        String failAt;
        BaseException failWith;

        private void maybeFail(String name) {
            if (!name.equals(failAt)) {
                return;
            }
            if (failWith != null) {
                throw failWith;
            }
            throw new IllegalStateException("redis down at " + name);
        }

        private Consumer<String> step(String name) {
            return k -> {
                calls.add(name + ":" + k);
                maybeFail(name);
            };
        }

        private ToIntFunction<String> counting(String name, int n) {
            return k -> {
                calls.add(name + ":" + k);
                maybeFail(name);
                return n;
            };
        }

        SessionPurgeService.Targets targets() {
            return new SessionPurgeService.Targets(
                    () -> running,
                    k -> new RedisSessionStorage.Snapshot(k, "default.sales", "s-1", 42, 18, 3, 5),
                    step("memory"),
                    counting("checkpoint", 1),
                    step("archive"),
                    counting("index", 7),
                    counting("summary", 1),
                    counting("assembly", 1));
        }
    }

    @Test
    @DisplayName("六路按顺序全清，返回删除前的记忆条数与归档轮数")
    void clearsEveryStoreInOrder() {
        Rig rig = new Rig();
        SessionPurgeService.ClearResult r = new SessionPurgeService(rig.targets()).clear(SESSION);

        assertEquals(List.of("memory:" + SESSION, "checkpoint:" + SESSION,
                "archive:" + SESSION, "index:" + SESSION,
                "summary:" + SESSION, "assembly:" + SESSION), rig.calls);
        assertEquals(SESSION, r.sessionKey());
        assertEquals(42L, r.memoryMessages());
        assertEquals(18L, r.archiveTurns());
        assertEquals(1, r.checkpointKeys());
        assertEquals(7, r.indexKeys());
        assertEquals(1, r.summaryKeys());
        assertEquals(1, r.assemblyKeys());
    }

    @Test
    @DisplayName("空会话键直接拒绝，任何清理动作都不触发")
    void rejectsBlankKey() {
        for (String key : new String[]{null, "", "   "}) {
            Rig rig = new Rig();
            BaseException e = assertThrows(BaseException.class,
                    () -> new SessionPurgeService(rig.targets()).clear(key));
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(rig.calls.isEmpty());
        }
    }

    @Test
    @DisplayName("在飞会话拒绝清除：那一轮结束时会把记忆写回刚清空的键")
    void rejectsRunningSession() {
        Rig rig = new Rig();
        rig.running = Set.of(SESSION);
        BaseException e = assertThrows(BaseException.class,
                () -> new SessionPurgeService(rig.targets()).clear(SESSION));
        assertTrue(e.getMessage().contains("正在运行"));
        assertTrue(rig.calls.isEmpty());
    }

    @Test
    @DisplayName("中途失败不假装回滚，文案告诉运维再跑一次")
    void midwayFailureIsRetryable() {
        Rig rig = new Rig();
        rig.failAt = "index";
        BaseException e = assertThrows(BaseException.class,
                () -> new SessionPurgeService(rig.targets()).clear(SESSION));
        assertEquals(ErrorCode.CHAT_MEMORY_DELETE_ERROR, e.getErrorCode());
        assertTrue(e.getMessage().contains("重新执行一次清除"));
        // 前半段已经删掉，后半段没走到；这正是"幂等 + 重跑"要覆盖的状态
        assertEquals(List.of("memory:" + SESSION, "checkpoint:" + SESSION,
                "archive:" + SESSION, "index:" + SESSION), rig.calls);
    }

    @Test
    @DisplayName("清理步骤抛出的业务异常原样透传，不被包装成中途失败")
    void businessExceptionPassesThrough() {
        Rig rig = new Rig();
        rig.failAt = "archive";
        BaseException original = new BaseException(ErrorCode.CHAT_MEMORY_DELETE_ERROR, "归档清除失败");
        rig.failWith = original;
        assertSame(original, assertThrows(BaseException.class,
                () -> new SessionPurgeService(rig.targets()).clear(SESSION)));
    }

    /**
     * 上面几例都是纯内存 new，覆盖不到 Spring 装配；之前就是这个盲区让应用起不来：
     * 生产构造器 + 包可见测试构造器共两个，不标 @Autowired 时 Spring 回落无参构造，
     * 启动直接报 "No default constructor found"。
     */
    @Test
    @DisplayName("Spring 能解析出生产构造器：多构造器必须显式标 @Autowired")
    void springResolvesTheAutowiredConstructor() {
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
        processor.setBeanFactory(new DefaultListableBeanFactory());
        Constructor<?>[] candidates = processor.determineCandidateConstructors(
                SessionPurgeService.class, "sessionPurgeService");
        assertTrue(candidates != null && candidates.length == 1,
                "多构造器下必须靠 @Autowired 指定，否则 Spring 回落到无参构造");
        assertEquals(8, candidates[0].getParameterCount());
    }
}
