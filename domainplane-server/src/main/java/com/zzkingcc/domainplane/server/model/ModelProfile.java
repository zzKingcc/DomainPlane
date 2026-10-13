package com.zzkingcc.domainplane.server.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 模型档案 —— 一个 OpenAI 兼容端点的一份配置。
 *
 * 名字叫"档案"而不是"模型"：同一个模型可以被配成多份档案（不同 Key、不同温度、不同额度），
 * 域绑定的是档案而不是模型名。
 *
 * 两个正交维度（多模态模型可同时命中多个，不互斥）：
 *
 *   • {@link #endpoints()} —— 端点族："怎么调它"，如 {@code chat} / {@code embedding}；
 *   • {@link #input()} / {@link #output()} —— 模态："能吃什么 / 产出什么"，如 text / image / audio。
 *
 * 上面两者是**识别得来**的（适配文件声明或元数据读取）。另一个数 {@link #contextWindow()}
 * 不属于这两个维度、也不来自识别：它是"这个模型能吃多少"，只能由用户按服务商文档手填。
 * 它不参与绑域门禁、不进指纹，唯一消费点是装配预算的封顶。
 *
 *
 * @param alias        别名（唯一键，如 {@code default} / {@code smart}）
 * @param endpoints    端点族（可多选；空 = 未声明，不再默认按 chat 处理）。
 *                     由 {@link ModelMetadataReader} 读服务商清单填入，不花 token，
 *                     读不到就如实留空（管控台标「未声明类型」）
 * @param input        输入模态（可多选；同样来自元数据读取，读不到即未声明）
 * @param output       输出模态（可多选；同上）
 * @param baseUrl      服务商地址（OpenAI 兼容，通常带 {@code /v1}）
 * @param apiKey       API Key
 * @param modelName    模型名
 * @param temperature  温度（可空 = 用服务商默认）
 * @param maxTokens    单次最大输出 token（可空 = 不限制，由模型上下文决定）
 * @param contextWindow 模型的上下文窗口（token，含输入与输出；可空 = 未声明）。
 *                     <p>由用户在管控台卡片上手工填写，来源是服务商的模型文档。刻意不做自动识别：
 *                     适配文件的 schema 里本就没有这一项，服务商元数据清单（{@code GET /models}）
 *                     也不提供，探测更探不出来，唯一可行的来源就是用户。另外
 *                     {@code ModelCapabilityProbe} 的探针全是端点族与模态，加一个"上下文长度探针"
 *                     意味着发一条必然超限的请求去换一个 400，代价与收益不成比例。</p>
 *                     <p>刻意不做正确性校验：这个数只影响"这一轮给模型看多少"，
 *                     填错只波及填它的人，不移动任何权限边界（见 {@link #domainBindable()}）。
 *                     唯一的消费点是 {@code ContextBudget}，且只用于<b>收紧</b>装配预算，
 *                     "填小了"会被采纳，"填大了"不构成任何保证（真实 token 计数由服务商算，
 *                     用户的数字与平台的 {@code CjkWidth} 估算都对不上它）。</p>
 * @param thinking     是否开启思考模式（可空 = 不声明，由服务商默认决定）
 * @param returnThinking 是否把思考内容回传给平台（可空 = 不声明，等价于框架默认的"不返回"）
 * @param dimensions   向量维度（仅 embedding 用；可空 = 按模型默认）
 * @param fallbacks    降级链：本档案不可用时依次尝试的别名（M3 生效，先只存）
 * @param params       服务商适配的私有调优参数（如 {@code enable_thinking}）；
 *                     空 = 未适配或无调优值。只有匹配到适配文件（{@link ModelAdapterRegistry}）
 *                     的档案才允许非空；键与取值范围由适配 schema 把关
 * @author zzkingcc
 */
public record ModelProfile(String alias,
                           List<String> endpoints,
                           List<String> input,
                           List<String> output,
                           String baseUrl,
                           String apiKey,
                           String modelName,
                           Double temperature,
                           Integer maxTokens,
                           Integer contextWindow,
                           Boolean thinking,
                           Boolean returnThinking,
                           Integer dimensions,
                           List<String> fallbacks,
                           Map<String, Object> params) {

    /**
     * 兼容构造：不声明上下文窗口。
     *
     * <p>保这条重载是为了不让"加了一个字段"变成"所有人都得改构造调用"，
     * 既有调用方与测试零改动（同 {@code DualConstraintChatMemory} 保留旧重载的口径）。</p>
     *
     * <p><b>本类内部的复制方法一律不调它</b>，而是走全参构造器并把 {@code contextWindow}
     * 显式写进去：将来再加字段时，复制方法会直接编译不过，而不是悄悄把新字段丢掉
     * （丢字段的表现是"保存后那个值凭空消失"，且全程不报错）。</p>
     */
    public ModelProfile(String alias,
                        List<String> endpoints,
                        List<String> input,
                        List<String> output,
                        String baseUrl,
                        String apiKey,
                        String modelName,
                        Double temperature,
                        Integer maxTokens,
                        Boolean thinking,
                        Boolean returnThinking,
                        Integer dimensions,
                        List<String> fallbacks,
                        Map<String, Object> params) {
        this(alias, endpoints, input, output, baseUrl, apiKey, modelName, temperature, maxTokens,
                null, thinking, returnThinking, dimensions, fallbacks, params);
    }

    /** 端点族：对话（/chat/completions） */
    public static final String EP_CHAT = "chat";
    /** 端点族：向量（/embeddings） */
    public static final String EP_EMBEDDING = "embedding";
    /** 端点族：重排（/rerank） */
    public static final String EP_RERANK = "rerank";
    /** 端点族：生图（/images/generations） */
    public static final String EP_IMAGES = "images";
    /** 端点族：语音合成（/audio/speech） */
    public static final String EP_TTS = "tts";
    /** 端点族：语音识别（/audio/transcriptions） */
    public static final String EP_ASR = "asr";
    /** 端点族：生视频（无统一端点） */
    public static final String EP_VIDEO = "video";

    /**
     * 域绑定允许的族：主模型（{@code chat}）+ 四个能力族。
     *
     * <p>两个排除是刻意的，不是漏写：</p>
     * <ul>
     *   <li>{@code embedding} —— 全局唯一单选（见 {@code ModelProfileSettings.embeddingAlias}）。
     *       向量空间是全树共用的，按域切换会让同一索引混入不同模型的向量；</li>
     *   <li>{@code rerank} —— 不参与域绑定（产品口径），它只服务固定的检索链路，没有"这个域用哪个重排"这回事。</li>
     * </ul>
     */
    public static final List<String> BINDABLE_FAMILIES =
            List.of(EP_CHAT, EP_IMAGES, EP_TTS, EP_ASR, EP_VIDEO);

    /** 能力族（主模型之外的四个），顺序即管控台绑定弹窗的分段顺序 */
    public static final List<String> CAPABILITY_FAMILIES = List.of(EP_IMAGES, EP_TTS, EP_ASR, EP_VIDEO);

    /** 族 → 中文名（管控台展示 / 拒绝文案共用，避免同一份词表两处各写一遍） */
    public static String familyLabel(String family) {
        return switch (family == null ? "" : family) {
            case EP_CHAT -> "主模型（对话）";
            case EP_IMAGES -> "生图";
            case EP_TTS -> "语音合成";
            case EP_ASR -> "语音识别";
            case EP_VIDEO -> "生视频";
            case EP_RERANK -> "重排";
            case EP_EMBEDDING -> "向量";
            default -> family == null ? "未知" : family;
        };
    }

    /** 模态：文本 */
    public static final String MOD_TEXT = "text";
    /** 模态：图片 */
    public static final String MOD_IMAGE = "image";
    /** 模态：音频 */
    public static final String MOD_AUDIO = "audio";
    /** 模态：视频 */
    public static final String MOD_VIDEO = "video";
    /** 模态：文件 */
    public static final String MOD_FILE = "file";
    /** 模态：向量（仅输出侧） */
    public static final String MOD_EMBEDDING = "embedding";
    /**
     * 模态：相关性分数（仅输出侧）。
     *
     * 重排端点（{@code POST /v1/rerank}）的产出是与传入 documents 一一对应的一组相关性分数
     * （服务商字段名通常是 {@code relevance_score}），既不是文本也不是向量。
     *
     * 为什么加这一项：词表里原本没有它，重排模型的 {@code output} 只能留空，
     * 于是同一个模型在卡片上"端点族=重排、输入=文本、输出=（空）"，看起来像识别失败。
     * 留空和"读不到"混在一起，正是"未识别到 ≠ 确实没有"那个老毛病的又一次复发。
     */
    public static final String MOD_SCORE = "score";

    public ModelProfile {
        /* 端点族为空不再默认补 chat：类型只能来自元数据识别（保存时读服务商清单）或调用方显式声明。
           猜成 chat 会让向量 / 生图模型被当成对话模型，能绑到域、却当不了向量模型，
           而且全程没有任何报错。空就是"未声明"：{@code isChat()} / {@code isEmbedding()} 如实返回 false，
           需要"能不能当对话模型用"时看 {@link #mayBeChat()}。 */
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        input = input == null ? List.of() : List.copyOf(input);
        output = output == null ? List.of() : List.copyOf(output);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        params = normalizeParams(params);
    }

    /**
     * params 归一：剔除 value 为 null 的条目后拷贝为不可变 map。
     *
     * 剔除 null 是硬需求，{@code Map.copyOf} 遇 null 直接 NPE，而调用方传
     * {@code {"top_p": null}} 是合法输入，语义就是"不发送该参数"。
     */
    private static Map<String, Object> normalizeParams(Map<String, Object> in) {
        if (in == null || in.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new TreeMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    /**
     * 复制一份，把读到的元数据并进来，端点族与输入 / 输出模态的唯一来源
     * （{@link ModelMetadataReader} 读服务商清单，零 token）。
     *
     * 两处刻意的不对称：
     *
     *   • 端点族：读到才覆盖。读不到（服务商清单不给类型）保留原值，
     *     调用方显式声明过的端点不该被一次"读不到"抹成空。
     *   • 模态：只有{@code architecture} 那种**完整枚举**才覆盖；{@code sub_type} 给的是端点规范
     *     基线值（{@code /chat/completions} → text → text），只能并进已有声明。
     *     基线值覆盖的后果：用户为 PaddleOCR-VL 之类手填的 {@code image} 会被一次读取悄悄抹掉，
     *     而卡片上只看到 text，"能吃什么"这条最关键的信息就这么没了，且看不出发生过什么。
     *
     * 其余 11 个字段原样带走：识别只补「怎么调它 / 能吃什么」，不该顺手动到用户填的值。
     */
    public ModelProfile withDetected(List<String> detectedEndpoints,
                                     List<String> detectedInput, List<String> detectedOutput,
                                     boolean modalitiesAuthoritative) {
        List<String> ep = (detectedEndpoints == null || detectedEndpoints.isEmpty())
                ? endpoints : List.copyOf(detectedEndpoints);
        return new ModelProfile(alias, ep,
                mergeModalities(input, detectedInput, modalitiesAuthoritative),
                mergeModalities(output, detectedOutput, modalitiesAuthoritative),
                baseUrl, apiKey, modelName,
                temperature, maxTokens, contextWindow, thinking, returnThinking, dimensions, fallbacks, params);
    }

    /** 模态合并：权威（完整枚举）覆盖，基线值并集。读不到一律保留原值。 */
    private static List<String> mergeModalities(List<String> declared, List<String> detected,
                                                boolean authoritative) {
        if (detected == null || detected.isEmpty()) {
            return declared;
        }
        if (authoritative) {
            return List.copyOf(detected);
        }
        List<String> merged = new ArrayList<>(declared);
        for (String m : detected) {
            if (!merged.contains(m)) {
                merged.add(m);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 复制一份、把「探测确认到的」并进来，探测只增不减。
     *
     * 与 {@link #withDetected} 的关键差别在语义：探测一次失败只说明「这次没确认到」，
     * 不等于「不支持」（网络抖动、样本格式被服务商挑剔都会失败）。若按
     * {@code withDetected(..., true)} 整体替换，一次抖动就会把用户 / 元数据已有的正确声明抹掉。
     * 这里对端点族与输入 / 输出模态都取并集：新确认的补进来，旧声明一律保留。
     */
    public ModelProfile withConfirmed(List<String> confirmedEndpoints,
                                      List<String> confirmedInput, List<String> confirmedOutput) {
        return new ModelProfile(alias,
                union(endpoints, confirmedEndpoints),
                union(input, confirmedInput),
                union(output, confirmedOutput),
                baseUrl, apiKey, modelName,
                temperature, maxTokens, contextWindow, thinking, returnThinking, dimensions, fallbacks, params);
    }

    /** 有序并集：{@code base} 原样在前，{@code extra} 里没有的新值追加在后 */
    private static List<String> union(List<String> base, List<String> extra) {
        List<String> out = new ArrayList<>(base);
        if (extra != null) {
            for (String value : extra) {
                if (value != null && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 复制一份、只换调优字段（温度 / 单次最大输出 / 私有参数）。
     *
     * 卡片右侧的绿色调优框只动这三样，连接信息（服务商地址 / Key / 模型名）与端点族、模态
     * 一律原样带走，连接信息不可改是产品口径，用"复制时不提供入口"来保证，
     * 而不是指望调用方自觉不传。三个参数都是全量替换（传 {@code null} / 空 map 即清除）。
     */
    public ModelProfile withTuning(Double newTemperature, Integer newMaxTokens,
                                   Map<String, Object> newParams) {
        return withTuning(newTemperature, newMaxTokens, dimensions, newParams);
    }

    /**
     * 复制一份、替换调优字段并**带上向量维度**（向量卡片绿框的那条保存路径）。
     *
     * dimensions 走专用字段而不是 params：它要参与 ES 索引契约、换模型时触发重灌确认，
     * 与"服务商私有参数"是两码事。四个参数都是全量替换（{@code null} / 空 map 即清除）。
     */
    public ModelProfile withTuning(Double newTemperature, Integer newMaxTokens,
                                   Integer newDimensions, Map<String, Object> newParams) {
        return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey, modelName,
                newTemperature, newMaxTokens, contextWindow, thinking, returnThinking, newDimensions, fallbacks, newParams);
    }

    /**
     * 复制一份、只换向量维度。
     *
     * 用于新建向量档案时的「原生维度回填」：用户没填维度时，用探测到的模型出厂维度落上，
     * 卡片就不再是"按模型默认"而是显示一个具体数字（见 AdminModelProfileController#save）。
     */
    public ModelProfile withDimensions(Integer newDimensions) {
        return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey, modelName,
                temperature, maxTokens, contextWindow, thinking, returnThinking, newDimensions, fallbacks, params);
    }

    /**
     * 复制一份、只换「是否回写思考内容」。
     *
     * 卡片头部那颗开关的保存口：一个字段、一个语义，不值得为它弹编辑窗、也不与调优参数
     * 共用保存动作。其余字段（连接信息 / 调优值 / 端点族 / 降级链）原样带走，
     * 与 {@link #withTuning} 同一个口径：复制时不提供改动入口，而不是指望调用方自觉不传。
     *
     * 传入非 null 的显式布尔：开关只有两态，{@code null}（不声明）与 {@code false} 对调用者
     * 后果相同，拨过一趟就该落定，不留"未声明"这个第三态。
     */
    public ModelProfile withReturnThinking(Boolean newReturnThinking) {
        return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey, modelName,
                temperature, maxTokens, contextWindow, thinking, newReturnThinking, dimensions, fallbacks, params);
    }

    /**
     * 复制一份、只换上下文窗口（卡片上那个独立输入框的保存口）。
     *
     * <p>与 {@link #withReturnThinking} 同一个理由走独立动作，但**不能**与它共用接口：
     * 上下文长度不属于"发给服务商的参数"（OpenAI 兼容端点没有这个入参，写进 {@code params}
     * 就是往请求体里塞一个服务商不认识的键），也不属于适配 schema 管的范围，
     * 而调优那条路（{@code PUT /model-profiles/tuning}）是按"是否命中适配"放行的，
     * 最常见的标准 OpenAI / DeepSeek 档案恰恰不命中，从那里根本改不到。</p>
     *
     * <p>传 {@code null} = 清除（回到"未声明"，装配预算维持配置值）。</p>
     */
    public ModelProfile withContextWindow(Integer newContextWindow) {
        return new ModelProfile(alias, endpoints, input, output, baseUrl, apiKey, modelName,
                temperature, maxTokens, newContextWindow, thinking, returnThinking, dimensions, fallbacks, params);
    }

    /**
     * 能不能走对话端点 —— 模型档案解析链（域 → 档案）只认这个
     *
     * 域绑定只认对话档案：向量档案即使被绑进 {@code domainBindings} 也不会被解析链取到，
     * 向量模型走 {@code ModelProfileSettings.embeddingAlias} 那一条全局单选。
     */
    public boolean isChat() {
        return endpoints.contains(EP_CHAT);
    }

    /** 能不能走向量端点 —— 只有这类档案能被选为全局向量模型 */
    public boolean isEmbedding() {
        return endpoints.contains(EP_EMBEDDING);
    }

    /**
     * 能不能**当作**对话模型来用 —— 与 {@link #isChat()} 的差别全在"未声明"这一格。
     *
     * {@code isChat()} 回答的是"**已确认**是对话模型"；元数据读不出类型时（标准 OpenAI 规范的服务商，
     * OpenAI / DeepSeek 官方就是这么一种形态），端点族为空，那是"不知道"，不是"确定不是"。
     * 若一律按 {@code isChat()} 判，"不知道"的档案就绑不了域、解析不出模型、卡片上的调优框也整块消失：
     * 等于建了一份永远用不了的档案，而这类恰恰是最常见的一类。
     *
     * 所以"不知道"按"可以当对话模型用"处理。真不是对话模型时（把向量 / 生图模型绑到域上），
     * 上游调用会明确报错，那比"从头到尾都用不了、且看不出为什么"更接近真相，也更容易修。
     *
     * 注意：这只放宽"能不能当对话模型用"，**不放宽**"是不是向量模型"（{@link #isEmbedding()}），
     * 向量模型是全局单选，选错会让整库向量维度对不上、检索静默失效，那个方向必须继续从严。
     */
    public boolean mayBeChat() {
        return isChat() || endpoints.isEmpty();
    }

    /**
     * 是否已识别 —— 端点族非空即「知道它是什么类型」。
     *
     * 空 = 未识别（提供商清单读不出类型，或压根没读到）：既绑不了域、也当不了向量模型。
     * 与 {@link #mayBeChat()} 分开答：后者问「能不能当对话模型用」，本方法问「知不知道它是什么」。
     */
    public boolean identified() {
        return !endpoints.isEmpty();
    }

    /**
     * 能不能绑到域上 —— 类型已知 且 主模态已知 且 能当对话模型用。绑域门禁的统一判据。
     *
     * 只查 {@link #identified()} 不够：API 可显式声明 {@code endpoints=[chat]} 却留空
     * {@code input}/{@code output}，那份档案「类型名义上已识别，输入输出模态仍未知」，
     * 域上跑对话 / 多模态时下游行为完全不可预测，正是要拦的形态。
     *
     * 探测路径天然满足：chat 探针必落 text→text 基线，故探过的对话模型一定通过。
     */
    public boolean domainBindable() {
        return identified() && !input.isEmpty() && !output.isEmpty() && mayBeChat();
    }

    /** 必填项是否齐备 */
    public boolean isUsable() {
        return hasText(baseUrl) && hasText(apiKey) && hasText(modelName);
    }

    /**
     * 实际生效的单次最大输出 token：未声明时为 {@code null}，即不限制。
     *
     * "不限制"是刻意保留的默认而不是遗漏：由模型与服务商上下文决定，
     * 只有显式填了值才会把 {@code max_tokens} 发出去。
     */
    public Integer effectiveMaxTokens() {
        return maxTokens;
    }

    /**
     * 实际生效的上下文窗口：未声明（或填了非正数）时为 {@code null}，即"平台不知道"。
     *
     * <p>{@code null} 与 {@code domainBindable()} 的关系是刻意的：上下文长度<b>不参与</b>
     * 绑域门禁。门禁的口径是"类型已知 + 主模态已知 + 可对话"，那是"能不能用"；
     * 上下文长度只回答"这一轮给模型看多少"，是使用中的取舍。把它加进门禁，等于
     * 让一个手填错的数字把档案变成绑不上域，反而比不填更糟。</p>
     *
     * <p>非正数按未声明处理：那是填错而不是"上下文为零"，
     * 让它流进装配预算会把预算算成负数，降级链会一路退到"只剩系统提示 + 本轮提问"。</p>
     */
    public Integer effectiveContextWindow() {
        return contextWindow == null || contextWindow <= 0 ? null : contextWindow;
    }

    /**
     * 档案指纹 —— 客户端缓存的键。
     *
     * 含 Key 的哈希：Key 一轮换，指纹就变，于是自然拿到新实例，
     * 既不需要手工清缓存，也不会出现"改了 Key 却还在用旧连接"。
     *
     * 思考开关必须在指纹里：它决定建客户端时发不发 {@code enable_thinking}。
     * 漏进去的话，管控台把"思考"从开改成关以后，缓存里那份旧实例照旧在用，
     * 表现为"保存成功、开关也变了，思考却还在烧"，且没有任何提示。
     *
     * {@link #params()} 同理：改一个调优值指纹不变，缓存里的旧客户端会继续用旧参数，
     * 同样表现为"保存成功、行为没变"。
     *
     * <p>{@link #contextWindow()} <b>刻意不在指纹里</b>：它不在建客户端时被消费
     * （消费点是装配预算，见 {@code ContextBudget}），纳进来只会让用户改一个数字就打醒一份
     * 新客户端 —— 白丢一份连接与它上面的连接池，而行为不会有任何变化。</p>
     */
    public String fingerprint() {
        String raw = String.join("|",
                nz(baseUrl), nz(modelName),
                String.valueOf(temperature), String.valueOf(maxTokens),
                String.valueOf(thinking), String.valueOf(returnThinking),
                stableParams(),
                sha256(nz(apiKey)));
        return sha256(raw).substring(0, 16);
    }

    /**
     * params 的稳定序列化：排序后走 Jackson，键序与数字形态都确定。
     *
     * 空 map 返回空串，未适配档案的指纹与改造前逐字节一致，存量缓存不被打醒。
     * 不用手写拼接：字符串 {@code "5"} 与整数 {@code 5} 在手拼下会撞成同一个指纹，
     * 而它们在线路上的 JSON 形态不同（{@code "5"} vs {@code 5}）。
     */
    private String stableParams() {
        if (params.isEmpty()) {
            return "";
        }
        try {
            return PARAMS_MAPPER.writeValueAsString(new TreeMap<>(params));
        } catch (Exception e) {
            // 极不可能发生（params 值只能是 JSON 标量）；退化为 toString，宁可缓存不命中也不能抛
            return params.toString();
        }
    }

    private static final ObjectMapper PARAMS_MAPPER = new ObjectMapper();

    /** 脱敏后的 Key（供管控台展示） */
    public String maskedApiKey() {
        if (!hasText(apiKey)) {
            return "";
        }
        String key = apiKey.trim();
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 极不可能发生（JDK 必带 SHA-256）；退化为原串，宁可缓存不命中也不能抛
            return raw;
        }
    }
}
