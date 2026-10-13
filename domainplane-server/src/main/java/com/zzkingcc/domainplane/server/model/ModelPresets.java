package com.zzkingcc.domainplane.server.model;

import java.util.List;

/**
 * 内置服务商预设 —— 只承担「选中后把默认地址填进表单」这一件事。
 *
 * 连接基底统一为 OpenAI 兼容：预设之间只差默认地址，不携带协议，也不登记任何元数据
 * （端点族 / 支持的参数 / 字段映射）—— 那些属于"参数识别"，已整体移除，
 * 重适配时会重新设计。预设现在只回答两个问题：这家叫什么、默认地址是什么。
 *
 * @author zzkingcc
 */
public final class ModelPresets {

    private ModelPresets() {
    }

    /**
     * 一条预设。
     *
     * @param label      下拉里显示的名字
     * @param baseUrl    默认服务商地址（该厂商的 OpenAI 兼容入口）
     * @param needApiKey 是否需要密钥；本地端点为 false，界面据此不必强制提醒
     * @param note       补充说明（可空）
     */
    public record Preset(String label, String baseUrl, boolean needApiKey, String note) {
    }

    /**
     * 全部预设。
     *
     * 只保留 OpenAI 与 DeepSeek 两家 —— 预设的价值是「选中即得官方地址」，而这两家也正是
     * 有内置适配的。其余厂商不设预设：地址栏本就是自由输入，用户手填即可，不必留一堆占位项。
     */
    private static final List<Preset> ALL = List.of(
            new Preset("DeepSeek 官方",
                    "https://api.deepseek.com/v1", true, null),
            new Preset("OpenAI 官方",
                    "https://api.openai.com/v1", true, null));

    /** 全部预设（不可变） */
    public static List<Preset> all() {
        return ALL;
    }
}
