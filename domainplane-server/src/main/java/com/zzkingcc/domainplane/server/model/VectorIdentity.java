package com.zzkingcc.domainplane.server.model;

import java.util.Locale;

/**
 * 向量身份：判断"两个档案是不是同一个向量模型"的唯一判据。
 *
 * <p><b>为什么不能拿别名当判据。</b>别名是配置句柄，随时可改（删了重建、换个名字、
 * 加个后缀）；而索引里那批向量只关心"是谁算的"。把句柄当身份，两个方向都会错：</p>
 * <ul>
 *   <li>同一个模型换个别名 → 判成"换了模型" → 全库白重灌一次（花时间、花 embed 费用，
 *       还会把索引短暂推入"删了还没灌回"的半成品状态）；</li>
 *   <li>同一个别名改了模型名 / 维度 → 判成"没换" → 索引静默错配，检索结果变成噪声
 *       却一条错误都不报，比维度不符难发现得多。</li>
 * </ul>
 *
 * <p><b>身份 = 服务商地址 + 模型名。</b>两者任一变化，向量空间就不再可比：</p>
 * <ul>
 *   <li>{@code baseUrl}：同名模型在不同服务商上是两个东西（两家都叫 bge-m3 也可能不一致），
 *       所以地址必须进身份；</li>
 *   <li>{@code modelName}：换模型就是换向量空间，维度相同也不行 ——
 *       这正是"维度一致所以不用重灌"这个直觉最危险的地方。</li>
 * </ul>
 *
 * <p><b>刻意不含</b> {@code apiKey} / 协议 / 温度 / 自定义参数 / 维度：它们不改变向量。
 * 换一把 Key、改个超时，不该让人把整个知识库重灌一遍；维度已经不作为可配项存在
 * （一律按模型原生维度实测），自然也不需要进身份。</p>
 *
 * @param baseUrl    服务商地址（已按 {@link #normalizeBaseUrl} 归一，仅为判等）
 * @param modelName  模型名
 * @author zzkingcc
 */
public record VectorIdentity(String baseUrl, String modelName) {

    /** 档案的向量身份；{@code profile} 为 {@code null}（未配置）时返回 {@code null} */
    public static VectorIdentity of(ModelProfile profile) {
        if (profile == null) {
            return null;
        }
        return new VectorIdentity(normalizeBaseUrl(profile.baseUrl()),
                trimToNull(profile.modelName()));
    }

    /**
     * 两个档案是否指向同一个向量空间 —— 「要不要重灌索引」只该问这一句。
     *
     * <p>任一方未配置（{@code null}）都算<b>不同</b>："从无到有"和"从有到无"都是真实变更，
     * 都要走确认；不能因为 {@code null} 看起来"两边都没有"就把一次变更放过去。</p>
     *
     * @return 两者身份完全一致时为 {@code true}
     */
    public static boolean same(ModelProfile left, ModelProfile right) {
        VectorIdentity a = of(left);
        VectorIdentity b = of(right);
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(b);
    }

    /**
     * baseUrl 的判等归一：去首尾空白 → 去尾部斜杠 → 统一小写。
     *
     * <p>只服务于判等，不参与发请求，所以这里对大小写从严归一没有副作用：
     * 两边走同一套规则，不会因为"复制粘贴时多了一个斜杠"就判成换了模型、
     * 然后把整个知识库重灌一遍。</p>
     */
    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** 空白串与 {@code null} 是同一件事（都没声明），否则 " " 会被当成一个真实模型名 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
