package com.zzkingcc.domainplane.server.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-02：工具回流地址的准入规则。
 *
 * <p>这个地址由实例心跳上报，服务端随后主动 POST 过去——上报方决定请求打到哪。
 * 策略的取舍写在 {@link ToolEndpointPolicy} 的类注释里：<b>不</b>禁内网与回环
 * （工具实例本来就在内网、常常同机），只拦"没有任何业务理由"的那几类。</p>
 *
 * @author zzkingcc
 */
@DisplayName("ToolEndpointPolicy：回流地址准入规则")
class ToolEndpointPolicyTest {

    private final ToolEndpointPolicy policy = new ToolEndpointPolicy();

    @Test
    @DisplayName("合法的实例地址放行：内网、回环、https 都允许")
    void allowedEndpointsPass() {
        assertNull(policy.rejectReason("http://10.0.0.5:8081/domainplane/invoke"), "内网地址是正常用法");
        assertNull(policy.rejectReason("http://127.0.0.1:8081/domainplane/invoke"), "同机部署必须允许");
        assertNull(policy.rejectReason("https://tool.internal/invoke"), "https + 内网域名应放行");
        assertNull(policy.rejectReason("http://localhost:8081/invoke"), "localhost 是合法的同机地址");
    }

    @Test
    @DisplayName("非 http/https scheme 一律拒绝")
    void nonHttpSchemeRejected() {
        assertTrue(policy.rejectReason("file:///etc/passwd").contains("http/https"));
        assertTrue(policy.rejectReason("ftp://10.0.0.5/x").contains("http/https"));
        assertTrue(policy.rejectReason("10.0.0.5:8081/invoke") != null, "缺 scheme 也不能猜");
    }

    @Test
    @DisplayName("带 user:pass@ 的地址拒绝：它掩盖真实目标主机")
    void userInfoRejected() {
        assertTrue(policy.rejectReason("http://attacker:pw@10.0.0.5:8081/invoke").contains("user:pass"));
    }

    @Test
    @DisplayName("通配地址拒绝：0.0.0.0 在多数系统上等价于连本机")
    void wildcardAddressRejected() {
        assertTrue(policy.rejectReason("http://0.0.0.0:8081/invoke").contains("通配地址"));
        assertTrue(policy.rejectReason("http://[::]:8081/invoke").contains("通配地址"));
    }

    @Test
    @DisplayName("链路本地与云元数据拒绝：一个 POST 就能读走实例凭证")
    void metadataRejected() {
        assertTrue(policy.rejectReason("http://169.254.169.254/latest/meta-data/").contains("链路本地"));
        assertTrue(policy.rejectReason("http://[fe80::1]/invoke").contains("链路本地"));
        assertTrue(policy.rejectReason("http://metadata.google.internal/x").contains("云元数据"));
        assertTrue(policy.rejectReason("http://100.100.100.200/latest/meta-data/").contains("云元数据"));
    }

    @Test
    @DisplayName("空地址拒绝，且注册入口抛的是 IllegalArgumentException（天然 400）")
    void blankRejected() {
        assertNotNull(policy.rejectReason(null));
        assertNotNull(policy.rejectReason("   "));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed(""));
    }

    @Test
    @DisplayName("配了白名单就只认白名单：精确匹配与 *.suffix 两种写法")
    void allowlistEnforced() {
        ToolEndpointPolicy strict = new ToolEndpointPolicy("10.0.0.5, *.internal");

        assertNull(strict.rejectReason("http://10.0.0.5:8081/invoke"));
        assertNull(strict.rejectReason("http://tool.internal/invoke"), "*.internal 应覆盖子域");
        assertTrue(strict.rejectReason("http://10.0.0.6:8081/invoke").contains("白名单"));
        assertTrue(strict.rejectReason("http://evil.example.com/invoke").contains("白名单"));
        assertTrue(strict.hasAllowlist());
    }

    @Test
    @DisplayName("白名单不能顺带放宽既有拦截：元数据地址即使在白名单里也拒")
    void allowlistDoesNotWeakenMetadataRule() {
        ToolEndpointPolicy odd = new ToolEndpointPolicy("169.254.169.254");
        assertTrue(odd.rejectReason("http://169.254.169.254/latest/meta-data/").contains("链路本地"));
    }
}
