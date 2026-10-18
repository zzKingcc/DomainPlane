package com.zzkingcc.domainplane.runtime.vision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * #16：{@code pos + PNG_CHUNK_OVERHEAD + len} 在 int 上溢出成负数。
 *
 * <p>PNG 块长度字段是 32 位无符号，可以声明一个接近 {@code Integer.MAX_VALUE} 的长度。
 * 溢出后 {@code next} 变负，"{@code next > png.length}" 这道越界判断随之失效，
 * {@code pos} 被赋成负数，下一次 {@code be32(png, pos)} 直接数组越界：
 * 而调用方只兜 {@code IllegalArgumentException} 与 {@code IOException}，
 * 于是一张构造过的 PNG 就能让整轮对话以 {@code ORCHESTRATION_FAILED} 结束。</p>
 *
 * <p>本测试用一张"块长度声明得很大、实际却没有那么多字节"的 PNG 钉住这条：
 * 必须按"结构损坏、无 alpha"安静处理，原样返回。</p>
 *
 * @author zzkingcc
 */
@DisplayName("ImageAlphaFlattener：块长度溢出不能让对话失败")
class ImageAlphaFlattenerOverflowTest {

    private static final byte[] PNG_SIG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** 拼一个块：4 字节长度 + 4 字节类型 + 数据 + 4 字节 CRC（CRC 随便填，本路径不解码） */
    private static void chunk(ByteArrayOutputStream out, String type, byte[] data, int declaredLen) {
        put32(out, declaredLen);
        out.write(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 4);
        if (data != null) {
            out.write(data, 0, data.length);
        }
        put32(out, 0xDEADBEEF); // CRC 不校验
    }

    private static void put32(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /**
     * IHDR：宽 2、高 2、位深 8、颜色类型 6（真彩 + alpha），后面跟一个长度声明得
     * 接近 Integer.MAX_VALUE 的块：{@code pos + 12 + len} 在 int 上必溢出。
     */
    private static String overflowingPngBase64() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PNG_SIG, 0, PNG_SIG.length);
        byte[] ihdr = new byte[13];
        put32Into(ihdr, 0, 2);   // 宽
        put32Into(ihdr, 4, 2);   // 高
        ihdr[8] = 8;             // 位深
        ihdr[9] = 6;             // 颜色类型 6 = 带 alpha
        chunk(out, "IHDR", ihdr, ihdr.length);

        // 长度声明 0x7FFFFFF0：pos + 12 + len 溢出成负数
        chunk(out, "IDAT", new byte[0], 0x7FFFFFF0);
        chunk(out, "IEND", new byte[0], 0);

        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static void put32Into(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    @Test
    @DisplayName("块长度溢出：安静按「无 alpha」处理，原样返回，不抛数组越界")
    void overflowingChunkLengthIsHandledQuietly() {
        String base64 = overflowingPngBase64();

        ImageAlphaFlattener.Flattened result = ImageAlphaFlattener.flatten(base64, "image/png");

        assertEquals(base64, result.base64(), "结构损坏的 PNG 必须原样返回（fail-open 口径）");
        assertEquals("image/png", result.mime(), "原样返回时沿用调用方声明的 mime");
    }

    @Test
    @DisplayName("正常 RGBA PNG 仍会被合成：守卫没有把合成功能一起废掉")
    void realRgbaPngIsStillFlattened() throws Exception {
        BufferedImage rgba = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        rgba.setRGB(0, 0, new Color(0, 0, 0, 0).getRGB());      // 全透明像素
        rgba.setRGB(1, 0, new Color(0, 0, 0, 255).getRGB());    // 不透明黑
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(rgba, "png", png);
        String base64 = Base64.getEncoder().encodeToString(png.toByteArray());

        ImageAlphaFlattener.Flattened result = ImageAlphaFlattener.flatten(base64, "image/png");

        assertNotEquals(base64, result.base64(), "带 alpha 的 PNG 必须被合成，否则透明区域会被服务商丢成黑块");
        assertEquals("image/png", result.mime(), "重新编码后恒为 image/png");
    }
}
