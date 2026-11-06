package com.zzkingcc.domainplane.server.capability;

import com.zzkingcc.domainplane.runtime.tool.ImageGenBudget;
import com.zzkingcc.domainplane.runtime.tool.ToolInvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生图闸门：额度用尽时，必须在解析档案 / 发远程请求之前就回一句「从已有编号里选一张」。
 *
 * <p>这里刻意不 mock 四个依赖（项目没有引入 mock 框架），而是把它们全传 null：
 * 只要闸门拦住了，就不会碰到这些依赖，调用自然返回；一旦闸门漏了，
 * 立刻 NullPointerException。用「会不会抛」当探针，比造一套假档案更直接。</p>
 *
 * @author zzkingcc
 */
@DisplayName("生图闸门：额度用尽时不发远程请求，并逼模型收敛到交付")
class ImageGenToolQuotaTest {

    @AfterEach
    void cleanup() {
        ToolInvocationContext.clear();
    }

    private static ImageGenTool tool() {
        return new ImageGenTool(null, null, null, null);
    }

    private static ImageGenBudget exhausted() {
        ImageGenBudget budget = new ImageGenBudget();
        for (int i = 0; i < ImageGenBudget.MAX_CALLS; i++) {
            budget.tryAcquire();
        }
        return budget;
    }

    @Test
    @DisplayName("额度用尽：只回文本，不碰档案解析与服务商")
    void exhaustedQuotaStopsBeforeAnyRemoteCall() {
        ImageGenBudget budget = exhausted();
        ToolInvocationContext.bind("t-1", "s-A", null, budget);

        String reply = tool().generate("画一只戴墨镜的柴犬", null);

        assertTrue(reply.contains("已达上限"), reply);
        assertTrue(reply.contains("不要再调用"), reply);
        assertTrue(reply.contains("编号"), reply);
        assertEquals(0, budget.remaining(), "被拦的这一次不该再扣额度");
    }

    @Test
    @DisplayName("额度未用尽：闸门放行并扣掉一次")
    void availableQuotaIsConsumed() {
        ImageGenBudget budget = new ImageGenBudget();
        ToolInvocationContext.bind("t-1", "s-A", null, budget);

        // 依赖为空：能走到 run() 才会抛，这里正是用它证明「这一轮被放行了」
        assertThrows(RuntimeException.class, () -> tool().generate("画一只猫", null));
        assertEquals(ImageGenBudget.MAX_CALLS - 1, budget.remaining());
    }

    @Test
    @DisplayName("没有绑定预算（单测直调 / 旧调用路径）：不限制，保持原行为")
    void unboundBudgetDoesNotLimit() {
        ToolInvocationContext.bind("t-1", "s-A");
        assertThrows(RuntimeException.class, () -> tool().generate("画一只猫", null));
    }

    @Test
    @DisplayName("空 prompt 优先于闸门：先要描述，不消耗额度")
    void blankPromptDoesNotConsumeQuota() {
        ImageGenBudget budget = new ImageGenBudget();
        ToolInvocationContext.bind("t-1", "s-A", null, budget);

        String reply = tool().generate("   ", null);

        assertTrue(reply.contains("没有说明要画什么"));
        assertEquals(ImageGenBudget.MAX_CALLS, budget.remaining());
    }
}
