package com.zzkingcc.domainplane.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户输入安全检测与过滤工具，覆盖指令覆盖、角色混淆、分隔符注入、
 * 提示词窃取、编码绕过等注入手法。
 *
 * @author zzkingcc
 */
public final class InputSanitizer {

    private static final Logger log = LoggerFactory.getLogger(InputSanitizer.class);

    /** 用户输入最大长度（字符）。超长显式拒绝，不截断 —— 见 {@link #validate(String)}。 */
    private static final int MAX_INPUT_LENGTH = 2000;

    //1、指令覆盖检测
    private static final Pattern[] INSTRUCTION_OVERRIDE = {
            Pattern.compile("(?s)忽略.{0,10}(上[面文]|之前|此前|以上).{0,10}(指令|规则|提示|要求|约束|限制|对话|内容|说明)"),
            Pattern.compile("(?s)(忘记|抛弃|无视|清除|删除|重置).{0,10}(之前|上[面文]|此前|以上).{0,10}(指令|规则|记忆|对话|上下文|历史)"),
            Pattern.compile("从现在开始.{0,20}(你[是叫]|你是|现在你)"),
            Pattern.compile("(?s)ignore.{0,10}(all|previous|above|prior).{0,10}(instruction|rule|prompt|command|directive|constraint|context|content)"),
            Pattern.compile("(?s)(forget|discard|disregard|override|overwrite|reset|clear).{0,10}(previous|above|prior|all).{0,10}(instruction|rule|prompt|memory|context)"),
            Pattern.compile("(?s)you are now.{0,30}(not|no longer|instead)"),
            Pattern.compile("new (system |)instruction"),
            Pattern.compile("你不再.{0,10}(是|需要|应该)"),
            Pattern.compile("(?s)override.{0,10}(system|instruction|prompt|rule)"),
    };

    //2、角色混淆检测
    private static final Pattern[] ROLE_CONFUSION = {
            // (?m) 不能省：多行输入里「第二行才是 system:」是真实注入手法，
            // 没有 (?m) 时 ^ 只匹配整个字符串开头，行首注入完全不被检测到。
            Pattern.compile("(?im)^\\s*(system|assistant|user|function|tool)\\s*[:：]"),
            Pattern.compile("(?i)\\[system\\]|\\[assistant\\]|\\[user\\]"),
            Pattern.compile("SystemMessage\\s*[:：]"),
            Pattern.compile("(?i)role\\s*[:：]\\s*(system|assistant)"),
            Pattern.compile("你是.{0,5}(系统|AI|模型|GPT|LLM|大模型|人工智能)"),
            Pattern.compile("(?s)you are.{0,5}(system|AI|model|GPT|LLM)"),
    };

    //3、分隔符注入检测
    private static final Pattern[] DELIMITER_INJECTION = {
            Pattern.compile("(---|===|___|\\*\\*\\*|###)\\s*(system|instruction|命令|指令|规则|提示)"),
            Pattern.compile("(system|instruction|命令|指令|规则|提示)\\s*(---|===|___|\\*\\*\\*|###)"),
            Pattern.compile("(?m)^\\s*#{1,3}\\s*(system|指令|规则|提示|命令)"),
    };

    //4、系统提示词窃取检测
    // 两条规则分别覆盖「动词在前」与「动词在后」两种语序，缺一即漏。
    private static final Pattern[] PROMPT_EXTRACTION = {
            Pattern.compile("(?s)(输出|打印|显示|重复|复述|告诉我|说出|透露|泄露|展示).{0,15}(系统提示词|system.{0,5}prompt|系统指令|你的指令|你的规则|你的设定|你的角色|你的人设)"),
            Pattern.compile("(?s)(repeat|print|output|show|display|tell|reveal|leak|dump|send|give).{0,15}(system.{0,5}prompt|instruction|your.{0,5}rule|your.{0,5}setting|your.{0,5}role)"),
            Pattern.compile("(?s)(你的.{0,5}prompt|你的.{0,5}提示词).{0,5}(是|什么)"),
            Pattern.compile("(?s)what.{0,5}(is|are).{0,5}your.{0,5}(instruction|prompt|rule|system)"),
            Pattern.compile("(?s)(把你|把你自己的|给我|发给我).{0,10}(提示词|指令|规则|prompt).{0,8}(发|给|告诉|说出|输出|打印|透露|泄露|展示)"),
            Pattern.compile("(?s)(send|give|tell|show|reveal).{0,8}(me|us).{0,12}(your.{0,5}(prompt|instruction|rule|system|setting))"),
            Pattern.compile("(?s)(翻译|转换|编码).{0,10}(提示词|prompt|指令)"),
    };

    //5、编码绕过检测
    private static final Pattern[] ENCODING_BYPASS = {
            Pattern.compile("(?s)(base64|unicode|hex|url.{0,5}encode|utf.{0,5}encode).{0,20}(decode|解码|解析)"),
            Pattern.compile("(?s)用.{0,5}(base64|unicode|编码).{0,10}(输出|翻译|回复|回答)"),
    };

    private InputSanitizer() {}

    /**
     * 检测用户输入是否包含注入攻击特征
     * @param input 用户输入
     * @return 命中检测规则时必须 reject
     */
    public static boolean isMalicious(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String lower = input.toLowerCase().trim();

        for (Pattern p : INSTRUCTION_OVERRIDE) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到指令覆盖攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : ROLE_CONFUSION) {
            if (p.matcher(input).find()) {
                log.warn("[输入安全] 检测到角色混淆攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : DELIMITER_INJECTION) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到分隔符注入攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : PROMPT_EXTRACTION) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到提示词窃取攻击: {}", truncate(input, 100));
                return true;
            }
        }
        for (Pattern p : ENCODING_BYPASS) {
            if (p.matcher(lower).find()) {
                log.warn("[输入安全] 检测到编码绕过攻击: {}", truncate(input, 100));
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗用户输入：统一换行符 + 去隐形字符，不截断、不压平换行。
     *
     * 超长由 {@link #validate(String)} 显式拒绝而非静默截断（截断会基于残缺输入回答，却零信号）。
     * 保留换行：段落边界是语义的一部分（代码、清单等）。
     * 真正防行首角色注入的是给 {@code ROLE_CONFUSION} 规则加 {@code (?m)}，已一并修正。
     *
     * @param input 原始用户输入
     * @return 清洗后的文本（结构不变）
     */
    public static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input.trim()
                // 统一换行符（\r\n 与 \r 都归一到 \n），不做 \n → 空格
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                // 去除零宽字符（Unicode 隐形注入）
                .replaceAll("[\\u200B-\\u200F\\u2028-\\u202F\\uFEFF\\u00AD]", "");
    }

    /**
     * 检测 + 清洗一步完成。
     *
     * @param input 原始用户输入
     * @return 清洗后的安全文本（保留换行与段落结构）
     * @throws com.zzkingcc.domainplane.common.exception.BaseException 检测到注入攻击，
     *         携带 {@link com.zzkingcc.domainplane.api.code.ErrorCode#INPUT_REJECTED}（40003）
     * @throws com.zzkingcc.domainplane.common.exception.BaseException 输入超过
     *         {@value #MAX_INPUT_LENGTH} 字符，携带
     *         {@link com.zzkingcc.domainplane.api.code.ErrorCode#INPUT_TOO_LONG}（40005）——
     *         拒绝而非截断，且抛出点在任何副作用之前
     */
    public static String validate(String input) {
        if (isMalicious(input)) {
            throw new com.zzkingcc.domainplane.common.exception.BaseException(
                    com.zzkingcc.domainplane.api.code.ErrorCode.INPUT_REJECTED);
        }
        String cleaned = sanitize(input);
        if (cleaned.length() > MAX_INPUT_LENGTH) {
            // 只记长度不记内容：用户输入属隐私，全文入日志既无必要也会显著膨胀日志体积
            log.warn("[输入安全] 输入超长被拒绝: {} 字符 > 上限 {} 字符", cleaned.length(), MAX_INPUT_LENGTH);
            throw new com.zzkingcc.domainplane.common.exception.BaseException(
                    com.zzkingcc.domainplane.api.code.ErrorCode.INPUT_TOO_LONG,
                    "输入超长（" + cleaned.length() + " 字符），上限 " + MAX_INPUT_LENGTH + " 字符，请缩短后重发");
        }
        return cleaned;
    }

    /**
     * 检测命中规则列表，返回所有命中的规则描述
     */
    public static List<String> detectDetails(String input) {
        List<String> hits = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return hits;
        }
        String lower = input.toLowerCase().trim();

        checkPatterns(lower, INSTRUCTION_OVERRIDE, "指令覆盖", hits);
        checkPatterns(input, ROLE_CONFUSION, "角色混淆", hits);
        checkPatterns(lower, DELIMITER_INJECTION, "分隔符注入", hits);
        checkPatterns(lower, PROMPT_EXTRACTION, "提示词窃取", hits);
        checkPatterns(lower, ENCODING_BYPASS, "编码绕过", hits);

        return hits;
    }

    private static void checkPatterns(String input, Pattern[] patterns, String category, List<String> hits) {
        for (Pattern p : patterns) {
            if (p.matcher(input).find()) {
                hits.add(category + ": " + p.pattern());
            }
        }
    }

    private static String truncate(String input, int maxLen) {
        return input.length() <= maxLen ? input : input.substring(0, maxLen) + "...";
    }
}
