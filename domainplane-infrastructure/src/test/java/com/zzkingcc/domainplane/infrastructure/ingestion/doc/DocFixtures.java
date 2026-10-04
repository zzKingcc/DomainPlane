package com.zzkingcc.domainplane.infrastructure.ingestion.doc;

import java.io.IOException;
import java.io.InputStream;

/**
 * 旧版 {@code .doc} 测试样例的读取入口。
 *
 * <p>样例是<b>真的二进制文件</b>（不是现造的）：HWPF 的样式表、表格遍历、控制符这几处行为
 * 与实际二进制布局强相关，现造不出来。来源与实测结论见 {@code src/test/resources/ingestion/doc/README.md}。</p>
 */
public final class DocFixtures {

    private static final String DIR = "ingestion/doc/";

    private DocFixtures() {
    }

    public static byte[] load(String name) {
        try (InputStream in = DocFixtures.class.getClassLoader().getResourceAsStream(DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("测试样例缺失: " + DIR + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取测试样例失败: " + DIR + name, e);
        }
    }
}
