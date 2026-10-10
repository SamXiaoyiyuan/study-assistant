package com.xiaoyiyuan.study.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EncodingTest {

    @Test
    void read_preservesCrlfFile_byteIdenticalRoundTrip(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("crlf.md");
        byte[] original = "# 标题\r\n\r\n正文\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f, original);

        TextFile tf = TextFileCodec.read(f);
        assertTrue(tf.hasCrlf(), "应识别出 CRLF");
        assertFalse(tf.hasBom(), "不应误报 BOM");
        assertEquals("# 标题\r\n\r\n正文\r\n", tf.content(), "读取必须原样保留 CRLF，不转换");

        TextFileCodec.write(f, tf.content());
        assertArrayEquals(original, Files.readAllBytes(f), "P1：不修改就写回，字节必须完全相同");
    }

    @Test
    void read_preservesBomFile_byteIdenticalRoundTrip(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("bom.md");
        byte[] original = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '#', ' ', 'a', '\n'};
        Files.write(f, original);

        TextFile tf = TextFileCodec.read(f);
        assertTrue(tf.hasBom(), "应识别出 UTF-8 BOM");
        // 严格解码下 U+FEFF 仍是合法 UTF-8，必须留在串首（否则 BOM 保真就此断掉）
        assertEquals("\uFEFF# a\n", tf.content(), "严格解码不得吞掉 BOM 对应的 U+FEFF");

        TextFileCodec.write(f, tf.content());
        assertArrayEquals(original, Files.readAllBytes(f), "P1：BOM 也必须保真");
    }

    @Test
    void invalidUtf8_isRejected_notSilentlyReplaced(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("gbk.md");
        // GBK 的「中文」是 D6 D0 CE C4，不是合法 UTF-8 序列
        byte[] original = new byte[] {(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4, '\n'};
        Files.write(f, original);

        NotUtf8Exception ex = assertThrows(NotUtf8Exception.class, () -> TextFileCodec.read(f));
        assertTrue(ex.getMessage().contains("gbk.md"), "异常必须带文件名，便于用户定位");
        // 偏移必须是真的：GBK 字节就在文件开头，所以是 0。
        // 曾经的实现取 `in.position() - result.length()`，在 REPORT 模式下 position 本就停在序列起点，
        // 相减会得到 -1，这条断言就是那个 bug 的回归守卫。
        assertTrue(ex.getMessage().contains("偏移 0"),
                "首个非法字节偏移必须是 0（非法字节就在文件开头），实际消息：" + ex.getMessage());
        assertArrayEquals(original, Files.readAllBytes(f), "拒绝时必须一个字节都不改");
    }

    @Test
    void write_newFile_isUtf8NoBomWithLf(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("new.md");
        TextFileCodec.write(f, "# 新文件\n第二行\n");

        byte[] bytes = Files.readAllBytes(f);
        assertArrayEquals("# 新文件\n第二行\n".getBytes(StandardCharsets.UTF_8), bytes,
                "新文件必须逐字节等于上述 UTF-8 内容：无 BOM、LF 结尾，且不追加结尾换行");
        assertFalse(TextFileCodec.read(f).hasCrlf(), "新文件必须是 LF");
    }

    @Test
    void normalizeToLf_stripsBomAndCrlf(@TempDir Path dir) throws IOException {
        String normalized = TextFileCodec.normalizeToLf("\uFEFF# 标题\r\n正文\r\n");
        assertEquals("# 标题\n正文\n", normalized);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "study.realFile", matches = ".+")
    void realFile_hasNoBomAndNoCrlf() throws IOException {
        TextFile tf = TextFileCodec.read(Path.of(System.getProperty("study.realFile")));
        assertFalse(tf.hasBom(), "真实档案应为无 BOM");
        assertFalse(tf.hasCrlf(), "真实档案应为纯 LF");
        assertEquals(13377, tf.content().getBytes(StandardCharsets.UTF_8).length,
                "应为 13377 字节（spec §4.1 实测值）");
    }
}
