package com.zzkingcc.domainplane.api.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 域标识的约定与工具方法。
 *
 * <p>域是一棵树，标识是<b>完整路径</b>（点分，从根域 {@link #DEFAULT} 出发），例如
 * {@code default.sales.order}。主键与展示同形，因此不存在"同名不同父"的歧义：
 * {@code default.a.x} 与 {@code default.b.x} 是两个不同的域。</p>
 *
 * <p>路径运算是<b>纯字符串运算</b>，不依赖任何注册表状态，因此放在本类供所有模块共用 ——
 * 包括拿不到运行时容器的基础设施层。</p>
 *
 * @author zzkingcc
 */
public final class Domains {

    /**
     * 根域（基层域）：整棵域树的起点，启动即存在、不可删除。
     *
     * <p>工具声明留空、调用未指定域都归一化为它 —— 即"挂在根上"，按累加语义对全树可见。</p>
     */
    public static final String DEFAULT = "default";

    /** 路径分隔符 */
    public static final char SEPARATOR = '.';

    /** 单段长度上限 */
    public static final int MAX_SEGMENT_LENGTH = 64;

    /** 路径总长度上限 */
    public static final int MAX_PATH_LENGTH = 256;

    /** 单段合法字符集：字母、数字、下划线、连字符 */
    private static final String SEGMENT_PATTERN = "[A-Za-z0-9_-]+";

    private Domains() {
    }

    /**
     * 归一化域标识：{@code null} / 空白 → {@link #DEFAULT}；其余去除首尾空白。
     */
    public static String normalize(String domainId) {
        return domainId == null || domainId.isBlank() ? DEFAULT : domainId.trim();
    }

    /**
     * 是否根域（{@code null} / 空白视为根域）。
     */
    public static boolean isDefault(String domainId) {
        return DEFAULT.equals(normalize(domainId));
    }

    /**
     * 父域标识；根域返回 {@code null}。
     */
    public static String parentOf(String domainId) {
        if (domainId == null) {
            return null;
        }
        String id = domainId.trim();
        int at = id.lastIndexOf(SEPARATOR);
        return at < 0 ? null : id.substring(0, at);
    }

    /**
     * 从根到自身的完整链（<b>含自身</b>），按由根向下的顺序。
     *
     * <p>{@code default.a.b} → {@code [default, default.a, default.a.b]}。</p>
     */
    public static List<String> chainOf(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return List.of();
        }
        String[] segments = domainId.trim().split("\\.");
        List<String> out = new ArrayList<>(segments.length);
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (path.length() > 0) {
                path.append(SEPARATOR);
            }
            path.append(segment);
            out.add(path.toString());
        }
        return List.copyOf(out);
    }

    /**
     * 祖先链（<b>不含自身</b>），按由根向下的顺序。
     *
     * <p>{@code default.a.b} → {@code [default, default.a]}；根域 → 空列表。</p>
     */
    public static List<String> ancestorsOf(String domainId) {
        List<String> chain = chainOf(domainId);
        return chain.isEmpty() ? List.of() : List.copyOf(chain.subList(0, chain.size() - 1));
    }

    /**
     * 校验完整域路径。规则（顺序即报错优先级）：
     * <ol>
     *   <li>非空、总长 ≤ {@link #MAX_PATH_LENGTH}；</li>
     *   <li><b>必须从根域 {@link #DEFAULT} 出发</b> —— 不允许悬空节点；</li>
     *   <li>单段非空、长度 ≤ {@link #MAX_SEGMENT_LENGTH}、字符集 {@code [A-Za-z0-9_-]}；</li>
     *   <li>自身链上不得重复段 —— 同名不同父允许，同一条链上重复不允许。</li>
     * </ol>
     *
     * @return {@code null} 表示合法；否则返回不合法原因
     */
    public static String validatePath(String domainId) {
        if (domainId == null || domainId.isBlank()) {
            return "域标识不能为空";
        }
        String id = domainId.trim();
        if (id.length() > MAX_PATH_LENGTH) {
            return "域路径过长（上限 " + MAX_PATH_LENGTH + " 字符）";
        }
        String[] segments = id.split("\\.", -1);
        if (!DEFAULT.equals(segments[0])) {
            return "域路径必须从根域 " + DEFAULT + " 出发：" + id;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return "域路径不能含空段（首尾或连续的分隔符）：" + id;
            }
            if (segment.length() > MAX_SEGMENT_LENGTH) {
                return "域路径单段过长（上限 " + MAX_SEGMENT_LENGTH + " 字符）：" + segment;
            }
            if (!segment.matches(SEGMENT_PATTERN)) {
                return "域路径单段只允许字母、数字、下划线、连字符：" + segment;
            }
            if (!seen.add(segment)) {
                return "域路径自身链上出现重复段：" + segment + "（完整路径 " + id + "）";
            }
        }
        return null;
    }
}
