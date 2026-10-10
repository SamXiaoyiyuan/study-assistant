package com.xiaoyiyuan.study.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本文件的编码保真读写。
 *
 * <p>本项目的最高优先级是字节保真：用户的档案由本应用与 AI 助手共同编辑，
 * 读写不逐字节一致就会损坏用户数据。因此读取时<b>不做任何规范化</b>
 * （CRLF 原样留在字符串里，BOM 作为 \uFEFF 留在串首），写入时<b>直接写字符串本身</b>。
 */
final class TextFileCodec {

    /** UTF-8 BOM 的三个字节。 */
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private TextFileCodec() {
    }

    /**
     * 读取文件，返回内容及其字节层特征（BOM / CRLF）。
     *
     * <p>这里刻意用 {@code Files.readAllBytes} + {@code new String(bytes, UTF_8)}，
     * <b>不用</b> {@code Files.readString}：后者会把 BOM 吞掉，我们就再也无法识别它，
     * 也就无法在写回时保真。
     */
    static TextFile read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new TextFile(new String(bytes, StandardCharsets.UTF_8), hasBom(bytes), hasCrlf(bytes));
    }

    /**
     * 把内容按 UTF-8 写回文件。
     *
     * <p>直接写字符串自身的字节，<b>不做任何替换</b>：不补/不去 BOM、不转换换行符、
     * 不 trim。CRLF 与 BOM 的保真正是靠「什么都不做」实现的。
     */
    static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 规范化为 LF 且去掉开头的 BOM。
     *
     * <p><b>只允许由用户显式触发</b>（界面上点「规范化为 LF（无 BOM）」），
     * 绝不能在 read / write 路径上自动调用——自动调用就是静默转换。
     */
    static String normalizeToLf(String content) {
        String withoutBom = content.startsWith("\uFEFF") ? content.substring(1) : content;
        return withoutBom.replace("\r\n", "\n");
    }

    private static boolean hasBom(byte[] bytes) {
        if (bytes.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (bytes[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否有 CRLF。直接查字节而不查解码后的字符串：UTF-8 不会把 \r\n 编码到别的字节序列里，
     * 字节层判定与内容层判定等价，且不受字符串解码影响。
     */
    private static boolean hasCrlf(byte[] bytes) {
        for (int i = 1; i < bytes.length; i++) {
            if (bytes[i - 1] == '\r' && bytes[i] == '\n') {
                return true;
            }
        }
        return false;
    }
}
