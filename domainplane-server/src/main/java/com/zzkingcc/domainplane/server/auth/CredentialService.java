package com.zzkingcc.domainplane.server.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭证签发与校验（签名式，HMAC-SHA256）。
 *
 * @author zzkingcc
 */
@Slf4j
@Component
public class CredentialService {

    /** 对外携带凭证的请求头名（客户端接入用） */
    public static final String CREDENTIAL_HEADER = "X-DomainPlane-Credential";

    /** 管控台凭证 Cookie 名（HttpOnly，JS 读不到） */
    public static final String CREDENTIAL_COOKIE = "domainplane_admin";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 签发凭证。
     *
     * @param account 当前账号（非空）
     * @return {@code payload.signature}
     */
    public String issue(AccountSettings account) {
        String payload = ENCODER.encodeToString(encodePayload(account));
        return payload + "." + ENCODER.encodeToString(hmac(deriveKey(account), payload));
    }

    /**
     * 构造 payload（Base64URL 之前的明文 JSON）。
     */
    private static byte[] encodePayload(AccountSettings account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", account.getUsername());
        payload.put("iat", System.currentTimeMillis() / 1000);
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("凭证 payload 构造失败：" + e.getMessage(), e);
        }
    }

    /**
     * 校验凭证。
     *
     * 任何异常（格式错、Base64 解不开、账号为空）都返回 false，不抛：
     * 凭证来自不可信输入，让它可以被伪造出来的输入打穿成 500 是自找麻烦。
     *
     * @param account    当前账号；为 null 时直接 false
     * @param credential 待校验凭证
     */
    public boolean verify(AccountSettings account, String credential) {
        if (account == null || credential == null || credential.isBlank()) {
            return false;
        }
        int dot = credential.lastIndexOf('.');
        if (dot <= 0 || dot == credential.length() - 1) {
            return false;
        }
        String payload = credential.substring(0, dot);
        String signature = credential.substring(dot + 1);

        try {
            byte[] expected = hmac(deriveKey(account), payload);
            byte[] actual = DECODER.decode(signature);
            if (!MessageDigest.isEqual(expected, actual)) {
                return false;
            }
            // 签名过了才解析 payload：账号名对不上说明是别的环境签发的，同样拒绝
            JsonNode node = MAPPER.readTree(DECODER.decode(payload));
            return account.getUsername().equals(node.path("sub").asText(null));
        } catch (Exception e) {
            log.debug("[凭证] 校验失败（视为无效）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 派生实际签名密钥。
     */
    private byte[] deriveKey(AccountSettings account) {
        byte[] master = Base64.getDecoder().decode(account.getSigningKey());
        return hmac(master, account.getPasswordHash());
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 算法与密钥都来自本类，走到这里只可能是 JVM 环境异常
            throw new IllegalStateException("凭证签名失败：" + e.getMessage(), e);
        }
    }
}
