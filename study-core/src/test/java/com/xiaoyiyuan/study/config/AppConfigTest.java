package com.xiaoyiyuan.study.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void defaults_whenFileMissing(@TempDir Path dir) {
        AppConfig c = AppConfig.load(dir.resolve("不存在.properties"));
        assertEquals(Path.of("%USERPROFILE%\\study"), c.studyDir());
        assertEquals(DeferredVisibility.COLLAPSED, c.deferredVisibility(), "默认折叠");
        assertEquals(List.of("平静", "有劲", "疲惫", "烦躁", "低落", "焦虑", "开心", "麻木"),
                c.moodOptions());
    }

    @Test
    void roundTripsThroughFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("E:\\别的目录"));
        c.setDeferredVisibility(DeferredVisibility.HIDDEN);
        c.setMoodOptions(List.of("还行", "很累"));
        c.save();

        AppConfig again = AppConfig.load(f);
        assertEquals(Path.of("E:\\别的目录"), again.studyDir());
        assertEquals(DeferredVisibility.HIDDEN, again.deferredVisibility());
        assertEquals(List.of("还行", "很累"), again.moodOptions());
    }

    @Test
    void fileIsUtf8_noBom_lfOnly(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setMoodOptions(List.of("疲惫"));
        c.save();

        byte[] bytes = Files.readAllBytes(f);
        assertNotEquals((byte) 0xEF, bytes[0], "不得写 BOM");
        assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("\r\n"),
                "不得写 CRLF");
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("疲惫"),
                "中文必须可读，不能被转义成 \\uXXXX");
    }

    @Test
    void unknownVisibilityValue_fallsBackToDefault(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        Files.writeString(f, "deferredVisibility=乱写的\n");
        assertEquals(DeferredVisibility.COLLAPSED, AppConfig.load(f).deferredVisibility());
    }

    @Test
    void appPaths_areUnderStudyDir(@TempDir Path dir) {
        assertEquals(dir.resolve("学习与生活计划.md"), AppPaths.planFile(dir));
        assertEquals(dir.resolve("408-进度.md"), AppPaths.legacyFile(dir));
        assertEquals(dir.resolve(".backup"), AppPaths.backupDir(dir));
        assertEquals(dir.resolve(".archive"), AppPaths.archiveDir(dir));
    }

    // 以下三条超出 brief 的逐字测试，是「配置坏了也得能启动」与「文件格式可读」这两条要求的防回归网。
    @Test
    void valueWithWindowsPathBackslash_survivesRoundTrip(@TempDir Path dir) throws IOException {
        // 值里的反斜杠必须按字面往返。用 java.util.Properties 读写时（本机 JDK 25 实测）：
        //   E:\new\my-dir  → "E:" + 换行 + "ew" + 制表符 + "abs" 之类，
        //   %USERPROFILE%\study → "D:示例 目录study"（反斜杠被吞掉）。
        // 后者仍然是个「合法路径」，Path.of 不报错，App 会安静地去一个不存在的目录找计划文件。
        // 所以这里刻意用带空格、带反斜杠的真实形状，而不是一个只有一段的目录名。
        Path f = dir.resolve("backslash.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("%USERPROFILE%\\study"));
        c.save();

        assertEquals(Path.of("%USERPROFILE%\\study"), AppConfig.load(f).studyDir());

        AppConfig c2 = AppConfig.load(f);
        c2.setStudyDir(Path.of("E:\\new\\tabs"));
        c2.save();
        assertEquals(Path.of("E:\\new\\tabs"), AppConfig.load(f).studyDir());
    }

    @Test
    void load_toleratesBomCommentsAndBlankLines(@TempDir Path dir) throws IOException {
        // BOM + 注释 + 空行 + 值两侧多余空白 + 没有结尾换行：手改过的配置文件长这样。
        // 带 BOM 时若剥不掉，第一个键会变成「BOM + studyDir」，整份配置静默退回默认值；
        // 值尾部多一个空格若不去掉，Path.of 会抛 InvalidPathException，学习目录同样静默退回默认值。
        Path f = dir.resolve("hand-edited.properties");
        Files.writeString(f, "\uFEFF# 手改过的配置\n\n studyDir = E:\\我的目录 \n\n",
                java.nio.charset.StandardCharsets.UTF_8);

        AppConfig c = AppConfig.load(f);
        assertEquals(Path.of("E:\\我的目录"), c.studyDir());
        assertEquals(DeferredVisibility.COLLAPSED, c.deferredVisibility());
        assertEquals(List.of("平静", "有劲", "疲惫", "烦躁", "低落", "焦虑", "开心", "麻木"),
                c.moodOptions());
    }

    @Test
    void writtenFileIsPlainReadableText(@TempDir Path dir) throws IOException {
        // 文件内容逐字可预测：没有注释行、没有时间戳、没有中文被写成 Unicode 转义。
        Path f = dir.resolve("plain.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("E:\\别的目录"));
        c.save();

        assertEquals("studyDir=E:\\别的目录\n"
                        + "deferredVisibility=COLLAPSED\n"
                        + "moodOptions=平静,有劲,疲惫,烦躁,低落,焦虑,开心,麻木\n",
                Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
    }
}
