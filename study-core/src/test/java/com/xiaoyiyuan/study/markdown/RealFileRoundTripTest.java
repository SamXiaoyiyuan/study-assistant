package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对<b>真实档案</b>验证 P1。
 *
 * <p>为什么必须可选：真实档案含个人信息，绝不能提交进仓库；但「无损往返」又必须被真实数据
 * 验证过，只测脱敏样例不算数。所以本测试只在显式给出路径时才跑：
 *
 * <pre>
 * &amp; 'D:\Program Files\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' `
 *   -pl study-core test -Dtest=RealFileRoundTripTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"
 * </pre>
 *
 * <p>本测试<b>只读</b>该文件：不写入、不建备份、不在其目录里创建任何东西。
 */
@EnabledIfSystemProperty(named = "study.realFile", matches = ".+")
class RealFileRoundTripTest {

    @Test
    void realProgressFile_isByteIdentical() throws IOException {
        Path p = Path.of(System.getProperty("study.realFile"));
        byte[] original = Files.readAllBytes(p);

        String text = new String(original, StandardCharsets.UTF_8);
        MarkdownDocument doc = MarkdownParser.parse(text);
        byte[] rendered = doc.render().getBytes(StandardCharsets.UTF_8);

        // 断言消息与输出里的关键事实刻意用 ASCII 写（bytesIn / bytesOut / firstDiffByte）：
        // 本机的 Maven/JVM 输出编码不是 UTF-8，中文在控制台会变成乱码，
        // 「13377 字节进、13377 字节出」如果写成中文就有可能在取证时读不出来。
        int firstDiff = firstDifference(original, rendered);
        assertArrayEquals(original, rendered,
                "P1 FAILED for real archive: bytesIn=" + original.length
                        + " bytesOut=" + rendered.length + " firstDiffByte=" + firstDiff);
        System.out.println("[P1] realFile round-trip OK: bytesIn=" + original.length
                + " bytesOut=" + rendered.length + " identical=true");
    }

    /** 返回首个不一致的字节偏移；完全一致时返回 -1。 */
    private static int firstDifference(byte[] a, byte[] b) {
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return a.length == b.length ? -1 : limit;
    }
}
