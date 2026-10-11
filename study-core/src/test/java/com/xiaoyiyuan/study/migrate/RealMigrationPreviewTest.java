package com.xiaoyiyuan.study.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 把<b>真实档案</b>的迁移结果写到指定位置，供人工逐节通读（spec §4.6 第 3 步「展示完整 diff」）。
 *
 * <p>为什么必须有它、而且必须留在仓库里：脱敏样例再全，也不能替代「拿真实档案跑一遍、人眼看一遍」——
 * 真实档案里有 4 周手写周记录与 LaTeX，形状是样例写不出来的。但真实档案含个人信息，绝不能提交进仓库，
 * 所以本测试只在显式给出路径时才跑：
 *
 * <pre>
 * mvn -pl study-core test -Dtest=RealMigrationPreviewTest `
 *   "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md" `
 *   "-Dstudy.previewOut=C:\Users\&lt;你&gt;\AppData\Local\Temp\migrated-preview.md"
 * </pre>
 *
 * <p><b>它只读真实档案</b>：{@code plan()} 不写源文件、不建备份、不建归档，Preview 也写在别处。
 * 通读完成后删掉预览文件即可。
 */
@EnabledIfSystemProperty(named = "study.realFile", matches = ".+")
class RealMigrationPreviewTest {

    @Test
    void writePreviewForHumanReview(@TempDir Path dir) throws IOException {
        Path legacy = Path.of(System.getProperty("study.realFile"));
        MigrationPlan plan = new Migrator(dir).plan(legacy);   // 只读

        Path out = Path.of(System.getProperty("study.previewOut"));
        Files.writeString(out, plan.targetText(), StandardCharsets.UTF_8);

        assertTrue(Files.size(out) > 0, "预览文件不能是空的");
    }
}
