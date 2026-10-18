package com.zzkingcc.domainplane.runtime.vision;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 透明底图片合成器 —— 图片发往视觉模型前，把带 alpha 通道的 PNG 先合成到白底。
 *
 * <p><b>为什么需要它（2026-10-16 实测定性）</b>：一张手写签名 PNG（RGBA，90% 像素
 * {@code alpha=0}，且所有像素 RGB 均为黑 —— 笔画内容 100% 承载在 alpha 通道），
 * 经本平台原样推给硅基流动后，模型回"一片完全黑色的画面，没有任何内容"；
 * 同一张图合成到白底再发，同模型即能辨认。根因是服务商图像预处理丢 alpha
 * （Pillow {@code convert('RGB')} 式只留 RGB 三通道），对本平台侧完全不可见、
 * 也无法要求服务商改。官网聊天页能在客户端 canvas 上先合成再上传，所以正常。
 * 在推送给上游之前自己做这一步，是唯一不依赖服务商行为的修法。</p>
 *
 * <p><b>为什么对"正确保留 alpha 的厂商"也无害</b>：合成只在图片<b>确实带 alpha
 * 通道</b>时触发，无 alpha 的 JPEG / 普通 PNG 一个字节都不动；而透明区域本就
 * 没有内容，显式定义为白色不改变图像语义（对话场景是"让模型理解图里有什么"，
 * 不是保留透明背景素材）。任何厂商收到白底图都与收到官网上传的图同构。</p>
 *
 * <p><b>范围刻意收得很窄</b>：</p>
 * <ul>
 *   <li>只认 PNG（按文件头魔数判，不认 mime 字符串 —— 前端 mime 取自
 *       {@code file.type}，个别浏览器为空，按字节判才可靠）；</li>
 *   <li>只对 color type 4/6（灰度/真彩 + alpha）或含 {@code tRNS} 块（调色板
 *       透明度）的图动手；</li>
 *   <li><b>APNG 不动</b>（见 {@code acTL} 块）—— 动图重新编码只会留第一帧，
 *       那是在丢内容，与合成 alpha 的初衷相反；</li>
 *   <li>GIF / WebP / 其他格式一律原样透传：ImageIO 解不了 WebP；GIF 若透明，
 *       重编码同样会丢帧，要做须另案。</li>
 * </ul>
 *
 * <p><b>全程 fail-open</b>：base64 解不动、块结构损坏、ImageIO 读不出或写不出，
 * 一律原样返回入参，绝不让"一个图片处理动作"把一轮对话搞失败。最坏结果退回到
 * 修复前的行为（服务商看到什么是什么），不会更差。</p>
 *
 * @author zzkingcc
 */
public final class ImageAlphaFlattener {

    private static final byte[] PNG_SIG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** PNG 块固定开销：4 长度 + 4 类型 + 4 CRC */
    private static final int PNG_CHUNK_OVERHEAD = 12;

    private ImageAlphaFlattener() {
    }

    /**
     * 合成结果。{@code base64} 是（可能被替换的）图片数据，{@code mime} 与之配套，
     * 重新编码后输出恒为 {@code image/png}，调用方必须用这里的 mime，不能沿用入参的。
     */
    public record Flattened(String base64, String mime) {

        public static Flattened of(String base64, String mime) {
            return new Flattened(base64, mime);
        }
    }

    /**
     * 带 alpha 的 PNG → 白底合成后的 PNG；其余情况原样返回。
     *
     * @param base64 图片数据（不含 {@code data:} 前缀）
     * @param mime   调用方声明的 mime（仅在原样返回时被沿用）
     */
    public static Flattened flatten(String base64, String mime) {
        if (base64 == null || base64.isEmpty()) {
            return Flattened.of(base64, mime);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return Flattened.of(base64, mime); // 不是合法 base64：不猜，原样
        }
        if (!isPng(bytes)) {
            return Flattened.of(base64, mime);
        }
        AlphaScan scan = scanAlpha(bytes);
        if (!scan.hasAlpha() || scan.animated()) {
            return Flattened.of(base64, mime);
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return Flattened.of(base64, mime);
        }
        // 双保险：块结构说有 alpha，解码器却不这么认为时，不动它
        if (image == null || !image.getColorModel().hasAlpha()) {
            return Flattened.of(base64, mime);
        }
        BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            g.drawImage(image, 0, 0, null); // SRC_OVER：alpha 在白底上正常合成
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(flat, "png", out)) {
                return Flattened.of(base64, mime); // 没有 PNG writer（理论上不可能）
            }
        } catch (IOException e) {
            return Flattened.of(base64, mime);
        }
        return Flattened.of(Base64.getEncoder().encodeToString(out.toByteArray()), "image/png");
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIG.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIG.length; i++) {
            if (bytes[i] != PNG_SIG[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 块结构预检：是否需要合成 + 是不是动图。只走块头不解码像素，成本可忽略。
     *
     * <p>不依赖 {@code ImageIO} 先解码再判 alpha，是为了让"无 alpha 的图"连解码都不做，
     * 每张图都完整解码一遍是纯浪费，而绝大多数对话图片没有 alpha。</p>
     */
    private static AlphaScan scanAlpha(byte[] png) {
        boolean alpha = false;
        boolean animated = false;
        int pos = PNG_SIG.length;
        while (pos + 8 <= png.length) {
            int len = be32(png, pos);
            String type = ascii(png, pos + 4, 4);
            if ("IHDR".equals(type)) {
                // data = 宽(4) 高(4) 位深(1) 颜色类型(1) …
                if (pos + 8 + 10 > png.length) {
                    break;
                }
                int colorType = png[pos + 8 + 9] & 0xFF;
                if (colorType == 4 || colorType == 6) {
                    alpha = true;
                }
            } else if ("tRNS".equals(type)) {
                alpha = true; // 调色板透明度
            } else if ("acTL".equals(type)) {
                animated = true; // APNG 动图：不碰
            } else if ("IEND".equals(type)) {
                break;
            }
            // 用 long 算下一块的起点：{@code pos + 12 + len} 在 int 上会溢出成负数，
            // 越界判断随之失效，pos 变成负值后下一次 be32 直接数组越界，
            // 一张构造过的 PNG 就能让整轮对话以 ORCHESTRATION_FAILED 结束。
            long next = (long) pos + PNG_CHUNK_OVERHEAD + len;
            if (len < 0 || next > png.length) {
                break; // 长度越界 = 结构损坏：按"无 alpha"处理，交给 ImageIO 那边裁决
            }
            pos = (int) next;
        }
        return new AlphaScan(alpha, animated);
    }

    private record AlphaScan(boolean hasAlpha, boolean animated) {
    }

    private static int be32(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    private static String ascii(byte[] b, int pos, int len) {
        return new String(b, pos, len, StandardCharsets.US_ASCII);
    }
}
