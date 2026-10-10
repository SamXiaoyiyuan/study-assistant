package com.xiaoyiyuan.study.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupRotationTest {

    @Test
    void backupNameFollowsSpec(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("学习与生活计划.md");
        Files.writeString(src, "内容");
        Path backupDir = dir.resolve(".backup");

        Path made = new BackupRotation(backupDir, 10).backup(src);

        assertTrue(made.getFileName().toString().startsWith("学习与生活计划.md."), "前缀必须是原文件名");
        assertTrue(made.getFileName().toString().endsWith(".bak"), "后缀必须是 .bak");
        assertTrue(made.getFileName().toString().matches(
                "学习与生活计划\\.md\\.\\d{8}-\\d{6}\\.bak"), "实际：" + made.getFileName());
        assertEquals("内容", Files.readString(made), "备份内容必须与源一致");
    }

    @Test
    void keepsOnlyTenNewest(@TempDir Path dir) throws IOException, InterruptedException {
        Path src = dir.resolve("f.md");
        Files.writeString(src, "0");
        BackupRotation rot = new BackupRotation(dir.resolve(".backup"), 10);

        for (int i = 1; i <= 13; i++) {
            Files.writeString(src, String.valueOf(i));
            rot.backup(src);
            Thread.sleep(1100); // 文件名时间戳精度是秒，必须跨秒才能区分
        }

        List<Path> kept = rot.list(src);
        assertEquals(10, kept.size(), "必须只保留最近 10 份");
        assertEquals("13", Files.readString(kept.get(0)), "最新的必须排第一");
    }

    @Test
    void backupOfMissingFile_throws(@TempDir Path dir) {
        assertThrows(IOException.class,
                () -> new BackupRotation(dir.resolve(".backup"), 10).backup(dir.resolve("不存在.md")));
    }
}
