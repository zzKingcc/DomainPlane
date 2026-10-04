package com.zzkingcc.domainplane.infrastructure.ingestion.txt;

import com.zzkingcc.domainplane.api.code.ErrorCode;
import com.zzkingcc.domainplane.common.exception.KnowledgeBaseException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 统一字符集 + 文本规范化（txt 入库第 1 步）。
 *
 * <p><b>全链路只有 UTF-8 一种字符集。</b>不管上传的文件原本是什么编码，都在 {@link #decode(byte[])}
 * 里一次性转成 UTF-8 语义的字符串；后面的清洗、切片、写索引、导出都只处理 UTF-8，
 * 全链路不含任何编码分支。</p>
 *
 * <p>探测顺序固定为：BOM →（无 BOM 时）严格 UTF-8 → GB18030。两条都判不出就<b>拒绝该文件</b> ——
 * 猜错编码会静默灌进乱码知识，比直接报错更糟。</p>
 *
 * @author zzkingcc
 */
public final class TxtNormalizer {

    public static final String UTF8 = "UTF-8";
    public static final String UTF8_BOM = "UTF-8(BOM)";
    public static final String UTF16_LE = "UTF-16LE";
    public static final String UTF16_BE = "UTF-16BE";
    public static final String GB18030 = "GB18030";

    private static final Charset GB18030_CHARSET = Charset.forName(GB18030);

    /** 行尾空白（含全角空格），行首缩进要保留 */
    private static final Pattern TRAILING_BLANK = Pattern.compile("[ \\t\\u3000]+(?=\\n|\\z)");

    /** 控制字符；\\t(09) \\n(0A) \\f(0C) 刻意排除 —— \\f 要留给清洗阶段找跨页重复行 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000E-\\u001F\\u007F]");

    private TxtNormalizer() {
    }

    /** 解码结果：文本 + 实际识别的编码名（原样回传给上传返回值） */
    public record Decoded(String text, String encoding) {
    }

    /**
     * 把任意编码的 txt 字节统一成 UTF-8 语义的字符串。
     *
     * @throws KnowledgeBaseException 两种候选编码都解不开时抛出（不猜、不静默）
     */
    public static Decoded decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new Decoded("", UTF8);
        }

        // 1. BOM 优先（最可靠，不需要猜）
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            String text = strictDecode(raw, 3, raw.length - 3, StandardCharsets.UTF_8);
            if (text != null) {
                return new Decoded(text, UTF8_BOM);
            }
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            String text = strictDecode(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
            if (text != null) {
                return new Decoded(text, UTF16_LE);
            }
        }
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            String text = strictDecode(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
            if (text != null) {
                return new Decoded(text, UTF16_BE);
            }
        }

        // 2. 无 BOM：先按严格 UTF-8
        String utf8 = strictDecode(raw, 0, raw.length, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new Decoded(utf8, UTF8);
        }

        // 3. 回落 GB18030（GBK/GB2312 的超集，覆盖中文存量 txt 里的大量老文件）
        String gb = strictDecode(raw, 0, raw.length, GB18030_CHARSET);
        if (gb != null) {
            return new Decoded(gb, GB18030);
        }

        throw new KnowledgeBaseException(ErrorCode.KNOWLEDGE_UPLOAD_REJECTED,
                "无法判定文件编码：既不是 UTF-8（含 BOM），也不是 GB18030。"
                        + "为避免灌入乱码知识，本次上传被拒绝；请先另存为 UTF-8 再上传。");
    }

    /**
     * 文本规范化：换行统一 {@code \n}、去行尾空白（保留行首缩进）、删控制字符（保留 {@code \f}）。
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = text;
        if (s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = TRAILING_BLANK.matcher(s).replaceAll("");
        s = CONTROL_CHARS.matcher(s).replaceAll("");
        return s;
    }

    /**
     * 严格解码：遇到非法字节就返回 {@code null}，绝不替换成 U+FFFD。
     */
    private static String strictDecode(byte[] raw, int offset, int length, Charset charset) {
        if (length < 0) {
            return null;
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(raw, offset, length)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
