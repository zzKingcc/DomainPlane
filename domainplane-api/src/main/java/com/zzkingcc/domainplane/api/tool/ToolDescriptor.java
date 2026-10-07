package com.zzkingcc.domainplane.api.tool;

import com.zzkingcc.domainplane.api.agent.Domains;
import com.zzkingcc.domainplane.api.annotation.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具描述符 —— 平台侧对一个工具的全部认知
 *
 * @author zzkingcc
 * @param name        工具名（全局唯一）
 * @param description 给 LLM 的用途说明
 * @param category    分组
 * @param version     版本
 * @param sideEffect  副作用等级
 * @param idempotent  是否幂等（决定能否自动重试）
 * @param toModel     结果是否回填 LLM
 * @param params      参数列表（结构 + 语义，用于校验与生成 schema）
 * @param domains     本工具的可用域（授权边界）：每项都是从根域出发的完整路径；
 *                    留空 = 挂在根域上（按累加语义对全树可见）
 * @param approval    二次确认策略
 * @param source      来源标识，如 {@code com.foo.Bean#method}，用于排障与审计
 */
public record ToolDescriptor(
        String name,
        String description,
        String category,
        String version,
        Tool.Effect sideEffect,
        boolean idempotent,
        boolean toModel,
        List<Param> params,
        List<String> domains,
        Approval approval,
        String source) {

    /**
     * 域被撤销后写入的哨兵：{@code <revoked>} 里的尖括号不在 {@link Domains} 的
     * 域段字符集 {@code [A-Za-z0-9_-]} 内，因此它既过不了 {@link Domains#validatePath}，
     * 也永远不会出现在任何域的祖先链上 —— 写进去等于这个工具对任何域都不可见。
     *
     * 不能用空串或纯空白：{@link #declaredDomains()} 会把空白项过滤掉，
     * 过滤完又是空列表，又会回落根域 —— 那正是本哨兵要防的那个 bug。
     * {@code <revoked>} 既非空白（{@code isBlank} 为 false）也不会被 {@code trim} 改动，可安全作哨兵。
     *
     * 为什么需要它：删域时 {@code ToolRegistry.forgetDomains} 会把被删的域从工具声明里
     * 剥掉。若工具恰好只声明了被删的那一个域，剥完就是空列表 —— 而空列表按累加语义
     * 表示"挂在根域上"，根域在每个域的祖先链里，于是这个工具会对所有域可见。
     * 删域本意是撤销授权，实际效果却是把授权放大到全域。
     *
     * 用哨兵而不是就地移除条目：移除会让该工具在管控台上彻底消失，排障时看不出
     * 这里原本有个工具。保留条目 + 标记为不可见，既维持可观测性，又能在作者改完代码
     * 重新注册（本地工具重启 / 远程工具下一次心跳带新声明）时自动恢复
     * —— {@code refreshReplica} 会用新声明覆盖这个哨兵。
     */
    public static final String REVOKED_DOMAIN = "<revoked>";

    /**
     * 本工具是否对指定域可见 —— 授权判定，不是过滤偏好。
     *
     * 判定按累加语义：声明命中该域，或命中它的任一祖先，即视为可见。
     * 于是"挂父域、子域默认可用"成立 —— 公共能力挂到根域一次即可，无需逐域声明。
     *
     * 声明留空 = 挂在根域上。根域在任何域的祖先链里，因此留空即全树可见；
     * 想收紧就显式写出完整路径。
     */
    public boolean visibleIn(String domainId) {
        List<String> chain = Domains.chainOf(Domains.normalize(domainId));
        if (chain.isEmpty()) {
            return false;
        }
        for (String declared : declaredDomains()) {
            if (chain.contains(Domains.normalize(declared))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 声明的可用域；留空视为只挂根域 {@link Domains#DEFAULT}。
     *
     * 含 {@link #REVOKED_DOMAIN} 时直接返回它自己，不回落根域：
     * 回落意味着全树可见，那会让「删域撤销授权」变成「删域放开授权」。
     * 部分撤销（还剩别的域）时把哨兵滤掉、只留真实域。
     */
    public List<String> declaredDomains() {
        if (domains == null || domains.isEmpty()) {
            return List.of(Domains.DEFAULT);
        }
        List<String> out = new ArrayList<>(domains.size());
        for (String declared : domains) {
            if (declared == null || declared.isBlank()) {
                continue;
            }
            String normalized = Domains.normalize(declared);
            if (REVOKED_DOMAIN.equals(normalized)) {
                return List.of(REVOKED_DOMAIN);
            }
            out.add(normalized);
        }
        return out.isEmpty() ? List.of(Domains.DEFAULT) : List.copyOf(out);
    }

    /**
     * 参数描述
     *
     * @param name        参数名
     * @param type        类型名：string / integer / number / boolean / enum / array / object
     * @param description 语义说明
     * @param required    是否必填
     * @param allowValues 枚举白名单（{@code type=enum} 时有效）
     * @param example     示例值
     * @param sensitive   是否敏感（日志 / 事件 / 审批 payload 中脱敏）
     * @param properties  {@code type=object} 时的子字段；简单类型为空列表。
     *                    这就是 DTO / record 参数被展开后的嵌套结构 —— 没有它，
     *                    模型只会看到一个"字符串"，无法构造出对象。
     * @param items       {@code type=array} 时的元素结构；非数组为空列表。
     *                    元素如果是 DTO，这里递归展开成对象，模型才知道数组里该填什么 ——
     *                    和 {@code properties} 是同一类"不能退化成字符串"的规则，只不过套在数组里。
     */
    public record Param(
            String name,
            String type,
            String description,
            boolean required,
            List<String> allowValues,
            String example,
            boolean sensitive,
            List<Param> properties,
            List<Param> items) {

        /** 简单类型：没有子字段，也没有数组元素结构。保留七参形态，既有调用点无需改动 */
        public Param(String name, String type, String description, boolean required,
                     List<String> allowValues, String example, boolean sensitive) {
            this(name, type, description, required, allowValues, example, sensitive, List.of(), List.of());
        }

        /** object 类型：有子字段，但没有数组元素结构（数组元素用 {@code items} 单独表达）。 */
        public Param(String name, String type, String description, boolean required,
                     List<String> allowValues, String example, boolean sensitive,
                     List<Param> properties) {
            this(name, type, description, required, allowValues, example, sensitive, properties, List.of());
        }
    }

    /**
     * 二次确认策略（与注解 {@code @Tool.Approval} 对应）
     *
     * @param mode           NONE / ALWAYS
     * @param condition      条件表达式（CONDITIONAL 模式）
     * @param reason         展示给审批人的原因
     * @param approverRoles  有批准权的角色标识，由宿主判定——平台不预定义角色，也不做校验
     * @param timeoutSeconds 审批超时（秒）
     */
    public record Approval(
            String mode,
            String condition,
            String reason,
            List<String> approverRoles,
            int timeoutSeconds) {

        /** 不需要确认 */
        public static Approval none() {
            return new Approval("NONE", "", "", List.of(), 0);
        }

        /** 是否需要人工确认 */
        public boolean required() {
            return mode != null && !"NONE".equalsIgnoreCase(mode);
        }
    }

    /** 是否需要人工确认 */
    public boolean requiresApproval() {
        return approval != null && approval.required();
    }
}
