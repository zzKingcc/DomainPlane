package com.zzkingcc.domainplane.runtime.vision;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ImageAlphaFlattener} 单测。输入 PNG 全部现场手写装配（CRC32 + Deflater），
 * 不依赖任何图片素材文件：与 {@code ModelCapabilityProbe} 的 PNG 样本同一立场：
 * 抄来的 base64 串合不合法没人验得出来，现场编码才能让"输入合法"可自证。
 */
class ImageAlphaFlattenerTest {

    private static final byte[] SIG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /* ==================== 现场装配 PNG ==================== */

    private static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeIntBE(out, data.length);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeIntBE(out, (int) crc.getValue());
        return out.toByteArray();
    }

    private static void writeIntBE(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static byte[] deflate(byte[] raw) {
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) {
            int n = def.deflate(buf);
            out.write(buf, 0, n);
        }
        def.end();
        return out.toByteArray();
    }

    /** 通用装配：colorType + 可选的 IDAT 前块（PLTE/tRNS/acTL…）+ 原始扫描行（调用方负责补 filter 字节） */
    private static byte[] buildPng(int w, int h, int colorType, byte[] beforeIdat, byte[] raw) {
        ByteArrayOutputStream ih = new ByteArrayOutputStream();
        writeIntBE(ih, w);
        writeIntBE(ih, h);
        byte[] ihdr = Arrays.copyOf(ih.toByteArray(), 13); // 后 5 字节留零再逐项赋值
        ihdr[8] = 8;               // bit depth
        ihdr[9] = (byte) colorType;
        ihdr[10] = 0;              // compression
        ihdr[11] = 0;              // filter method
        ihdr[12] = 0;              // interlace
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIG);
        out.writeBytes(chunk("IHDR", ihdr));
        if (beforeIdat != null) {
            out.writeBytes(beforeIdat);
        }
        out.writeBytes(chunk("IDAT", deflate(raw)));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    /** 左半透明、右半不透黑：复刻用户那张签名图的形态：内容全在 alpha 里，RGB 平面全黑 */
    private static byte[] signatureLikePng(int w, int h) {
        int ch = 4;
        byte[] raw = new byte[h * (1 + w * ch)];
        for (int y = 0; y < h; y++) {
            int row = y * (1 + w * ch);
            raw[row] = 0; // filter: None
            for (int x = 0; x < w; x++) {
                int p = row + 1 + x * ch;
                boolean opaque = x >= w / 2;
                raw[p] = 0;
                raw[p + 1] = 0;
                raw[p + 2] = 0;                          // RGB 恒黑
                raw[p + 3] = (byte) (opaque ? 0xFF : 0);  // 笔画只在 alpha
            }
        }
        return buildPng(w, h, 6, null, raw);
    }

    private static byte[] plainRgbPng(int w, int h, int rgb) {
        int ch = 3;
        byte[] raw = new byte[h * (1 + w * ch)];
        for (int y = 0; y < h; y++) {
            int row = y * (1 + w * ch);
            raw[row] = 0;
            for (int x = 0; x < w; x++) {
                int p = row + 1 + x * ch;
                raw[p] = (byte) ((rgb >> 16) & 0xFF);
                raw[p + 1] = (byte) ((rgb >> 8) & 0xFF);
                raw[p + 2] = (byte) (rgb & 0xFF);
            }
        }
        return buildPng(w, h, 2, null, raw);
    }

    /** 调色板 PNG：idx0 = 不透黑，idx1 = 透明白（tRNS 第二项 0x00） */
    private static byte[] palettePngWithTrns(int w, int h) {
        byte[] plte = new byte[6]; // 两项：黑、白
        plte[3] = (byte) 0xFF;
        plte[4] = (byte) 0xFF;
        plte[5] = (byte) 0xFF;
        byte[] trns = {(byte) 0xFF, 0x00}; // 第一项不透、第二项全透
        byte[] raw = new byte[h * (1 + w)];
        for (int y = 0; y < h; y++) {
            int row = y * (1 + w);
            raw[row] = 0;
            for (int x = 0; x < w; x++) {
                raw[row + 1 + x] = (byte) (x >= w / 2 ? 1 : 0); // 左黑右透明
            }
        }
        ByteArrayOutputStream pre = new ByteArrayOutputStream();
        pre.writeBytes(chunk("PLTE", plte));
        pre.writeBytes(chunk("tRNS", trns));
        return buildPng(w, h, 3, pre.toByteArray(), raw);
    }

    /** 在 IHDR 之后插入 acTL（num_frames=1, num_plays=0）：声明自己是 APNG 动图 */
    private static byte[] withActl(byte[] png) {
        byte[] actl = new byte[8];
        actl[4] = 1; // num_plays
        int ihdrEnd = SIG.length + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Arrays.copyOfRange(png, 0, ihdrEnd));
        out.writeBytes(chunk("acTL", actl));
        out.writeBytes(Arrays.copyOfRange(png, ihdrEnd, png.length));
        return out.toByteArray();
    }

    private static BufferedImage decode(String base64) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    /* ==================== 用例 ==================== */

    @Test
    void 透明底PNG合成为白底黑字() throws Exception {
        byte[] in = signatureLikePng(8, 8);
        String b64 = Base64.getEncoder().encodeToString(in);

        ImageAlphaFlattener.Flattened f = ImageAlphaFlattener.flatten(b64, "image/png");

        assertEquals("image/png", f.mime());
        assertNotEquals(b64, f.base64());
        BufferedImage img = decode(f.base64());
        assertNotNull(img);
        assertFalse(img.getColorModel().hasAlpha(), "合成后不应再有 alpha 通道");
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int expected = x >= 4 ? 0x000000 : 0xFFFFFF; // 右半保留黑笔画，左半透明处变白
                assertEquals(expected, img.getRGB(x, y) & 0xFFFFFF,
                        "像素(" + x + "," + y + ") 应为" + (x >= 4 ? "黑" : "白"));
            }
        }
    }

    @Test
    void 无alpha的RGB_PNG一个字节都不动() throws Exception {
        byte[] in = plainRgbPng(8, 8, 0x808080);
        String b64 = Base64.getEncoder().encodeToString(in);

        ImageAlphaFlattener.Flattened f = ImageAlphaFlattener.flatten(b64, "image/png");

        assertEquals(b64, f.base64());
        assertEquals("image/png", f.mime());
    }

    @Test
    void 调色板加tRNS的PNG也合成() throws Exception {
        byte[] in = palettePngWithTrns(8, 8);
        String b64 = Base64.getEncoder().encodeToString(in);

        ImageAlphaFlattener.Flattened f = ImageAlphaFlattener.flatten(b64, "image/png");

        assertEquals("image/png", f.mime());
        assertNotEquals(b64, f.base64());
        BufferedImage img = decode(f.base64());
        assertNotNull(img);
        assertFalse(img.getColorModel().hasAlpha());
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int expected = x >= 4 ? 0xFFFFFF : 0x000000;
                assertEquals(expected, img.getRGB(x, y) & 0xFFFFFF);
            }
        }
    }

    @Test
    void APNG动图不碰() {
        byte[] in = withActl(signatureLikePng(8, 8));
        String b64 = Base64.getEncoder().encodeToString(in);

        ImageAlphaFlattener.Flattened f = ImageAlphaFlattener.flatten(b64, "image/png");

        assertEquals(b64, f.base64(), "动图重新编码只会留第一帧，必须原样放行");
        assertEquals("image/png", f.mime());
    }

    @Test
    void 非PNG原样返回() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 74, 70, 73, 70, 0, 1, 1, 1, 0, 96, 0, 96, 0, 0};
        String b64 = Base64.getEncoder().encodeToString(jpeg);

        ImageAlphaFlattener.Flattened f = ImageAlphaFlattener.flatten(b64, "image/jpeg");

        assertEquals(b64, f.base64());
        assertEquals("image/jpeg", f.mime());
    }

    @Test
    void 坏base64与空值不抛() {
        ImageAlphaFlattener.Flattened bad = ImageAlphaFlattener.flatten("!!!not-base64!!!", "image/png");
        assertEquals("!!!not-base64!!!", bad.base64());
        assertEquals("image/png", bad.mime());

        ImageAlphaFlattener.Flattened empty = ImageAlphaFlattener.flatten("", "image/png");
        assertEquals("", empty.base64());

        ImageAlphaFlattener.Flattened nul = ImageAlphaFlattener.flatten(null, "image/png");
        assertNull(nul.base64());
        assertEquals("image/png", nul.mime());
    }

    @Test
    void 块结构损坏的PNG不抛() {
        byte[] in = signatureLikePng(8, 8);
        in[in.length - 20] ^= 0xFF; // 破坏一个字节，让块长度越界
        String b64 = Base64.getEncoder().encodeToString(in);

        ImageAlphaFlattener.Flattened f = ImageAlphaFlattener.flatten(b64, "image/png");

        // 最低要求是不抛；原样放行或 ImageIO 裁决后的任一结果都可接受
        assertNotNull(f);
    }
}
