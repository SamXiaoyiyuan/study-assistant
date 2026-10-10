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

        TextFileCodec.write(f, tf.content());
        assertArrayEquals(original, Files.readAllBytes(f), "P1：BOM 也必须保真");
    }

    @Test
    void write_newFile_isUtf8NoBomWithLf(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("new.md");
        TextFileCodec.write(f, "# 新文件\n第二行\n");

        byte[] bytes = Files.readAllBytes(f);
        assertEquals('#', bytes[0], "新文件第一个字节必须是 #，不能是 BOM");
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
