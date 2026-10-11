package com.xiaoyiyuan.study.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守写入安全：{@code plan()} 只读、{@code apply()} 的固定顺序、原文件一个字节都不许动。
 *
 * <p>迁移是<b>一次性、不可逆感</b>的操作（spec §4.6），所以这里的每一条断言都对应一种
 * 「用户数据回不来」的失败：源文件被改写、已有目标被覆盖、没备份就先写、源文件在人工确认期间
 * 被别的程序改过而我们照着旧内容迁移。
 */
class MigrationSafetyTest {

    private static final String LEGACY = """
            # 408 考研备考进度 · 测试

            > 建档：2026-09-12

            ---

            ## 一、我的档案

            | 项目 | 内容 |
            |---|---|
            | 年级 | 大二上 |

            ---

            ## 二、优先级与每周底线

            | 项 | 底线 | 怎么安排 | 状态 |
            |---|---|---|---|
            | 单词 | 7 天里 ≥5 天 | 晨起或睡前 | 待回报 |

            ---

            ## 六、周记录（每周追加，不覆盖）

            ### 2026-09-14 – 2026-09-20（第 1 周）

            - 实际完成：单词 6 天

            <!-- 填写方式：周日晚说一句 -->
            """;

    private static Path legacyFile(Path dir) throws IOException {
        Path p = dir.resolve("408-进度.md");
        Files.writeString(p, LEGACY, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void plan_doesNotTouchAnyFile(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        byte[] before = Files.readAllBytes(legacy);

        new Migrator(dir).plan(legacy); // 只读

        assertArrayEquals(before, Files.readAllBytes(legacy), "plan() 不得改动源文件");
        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")), "plan() 不得写出目标文件");
        assertFalse(Files.exists(dir.resolve(".backup")), "plan() 不得产生备份");
    }

    /** 反复调用 {@code plan()} 也必须始终只读（它是只读的，所以「调两次」不该有任何副作用）。 */
    @Test
    void plan_isRepeatableAndStillReadOnly(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        byte[] before = Files.readAllBytes(legacy);
        Migrator m = new Migrator(dir);

        MigrationPlan first = m.plan(legacy);
        MigrationPlan second = m.plan(legacy);

        assertEquals(first.sourceText(), second.sourceText());
        assertEquals(first.targetText(), second.targetText());
        assertArrayEquals(before, Files.readAllBytes(legacy));
        assertFalse(Files.exists(dir.resolve(".backup")));
        assertFalse(Files.exists(dir.resolve(".archive")));
        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")));
    }

    @Test
    void apply_createsBackupAndArchive_leavesSourceUntouched(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        byte[] before = Files.readAllBytes(legacy);
        Migrator m = new Migrator(dir);

        MigrationResult r = m.apply(m.plan(legacy), dir.resolve("学习与生活计划.md"));

        assertArrayEquals(before, Files.readAllBytes(legacy), "原文件必须一个字节都不变");
        assertTrue(Files.exists(r.target()), "目标文件必须已创建");
        assertTrue(Files.exists(r.backup()), "必须已备份");
        assertTrue(Files.exists(r.archive()), "必须已归档");
        assertArrayEquals(before, Files.readAllBytes(r.archive()), "归档必须与原文逐字节相同");
        assertTrue(r.archive().toString().contains(".archive"), "归档目录必须是 .archive");
    }

    /** 备份必须落在 {@code .backup\} 里，且命名符合全局约束（{@code <原文件名>.<时间戳>.bak}）。 */
    @Test
    void apply_namesTheBackupAfterTheSource(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Migrator m = new Migrator(dir);

        MigrationResult r = m.apply(m.plan(legacy), dir.resolve("学习与生活计划.md"));

        assertEquals(dir.resolve(".backup"), r.backup().getParent(), "备份必须落在 .backup\\");
        String name = r.backup().getFileName().toString();
        assertTrue(name.startsWith("408-进度.md."), "备份名必须以原文件名开头：" + name);
        assertTrue(name.endsWith(".bak"), "备份名必须以 .bak 结尾：" + name);
    }

    @Test
    void apply_refusesWhenTargetExistsAndIsNotEmpty(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Files.writeString(target, "# 已经有一份了\n");
        Migrator m = new Migrator(dir);

        assertThrows(TargetExistsException.class, () -> m.apply(m.plan(legacy), target));
        assertEquals("# 已经有一份了\n", Files.readString(target), "已存在的目标文件绝不能被覆盖");
    }

    /**
     * 拒绝必须发生在<b>任何写入之前</b>：连备份与归档都不许产生。
     *
     * <p>只断言「抛了异常」是不够的——把检查挪到备份之后，异常照样抛，但 {@code .backup\} 已经
     * 多出一个文件。所以这里断言目录不存在（Task 8 的教训：安全关键顺序必须由「两种重排下都会红」
     * 的断言钉住）。
     */
    @Test
    void apply_refusalHappensBeforeAnyWrite(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Files.writeString(target, "# 已经有一份了\n");
        Migrator m = new Migrator(dir);

        assertThrows(TargetExistsException.class, () -> m.apply(m.plan(legacy), target));

        assertFalse(Files.exists(dir.resolve(".backup")), "拒绝覆盖时不得已经建了备份目录");
        assertFalse(Files.exists(dir.resolve(".archive")), "拒绝覆盖时不得已经建了归档目录");
        assertArrayEquals(LEGACY.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(legacy),
                "拒绝覆盖时源文件必须一个字节都没动");
    }

    @Test
    void apply_allowsMissingOrEmptyTarget(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Files.writeString(target, "");
        Migrator m = new Migrator(dir);

        MigrationResult r = m.apply(m.plan(legacy), target);
        assertTrue(Files.readString(r.target()).contains("学习与生活计划"));
    }

    /** 目标路径存在但不是普通文件（例如同名目录）：同样拒绝，且不做任何写入。 */
    @Test
    void apply_refusesWhenTargetIsADirectory(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = Files.createDirectory(dir.resolve("学习与生活计划.md"));
        Migrator m = new Migrator(dir);

        assertThrows(TargetExistsException.class, () -> m.apply(m.plan(legacy), target));
        assertFalse(Files.exists(dir.resolve(".backup")), "拒绝时不得已经建了备份目录");
        assertTrue(Files.isDirectory(target), "已存在的目录绝不能被当作目标文件覆盖");
    }

    /**
     * 源文件在「出方案」与「确认写入」之间被改过（本项目里三方共同编辑同一个文件是常态）：
     * 必须响亮拒绝，而不是照着旧内容迁移、顺手把新内容归档掉。
     */
    @Test
    void apply_refusesWhenSourceChangedAfterPlan(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Migrator m = new Migrator(dir);
        MigrationPlan plan = m.plan(legacy);
        Files.writeString(legacy, LEGACY + "\n- plan 之后用户又写了一行\n", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> m.apply(plan, dir.resolve("学习与生活计划.md")));

        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")), "过期方案不得写出目标文件");
        assertFalse(Files.exists(dir.resolve(".backup")), "过期方案不得产生备份");
        assertFalse(Files.exists(dir.resolve(".archive")), "过期方案不得产生归档");
    }

    /**
     * {@code MigrationPlan} 是纯数据、没有源文件路径，而 {@code apply} 需要源路径去备份与归档。
     * 因此 apply 只接受<b>本实例 plan() 刚产出</b>的方案；拿别的实例（或凭空造的）方案来调，
     * 必须拒绝而不是去猜一个「大概是这个」的源文件。
     */
    @Test
    void apply_refusesAPlanFromAnotherMigrator(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        MigrationPlan foreign = new Migrator(dir).plan(legacy);

        assertThrows(IllegalArgumentException.class,
                () -> new Migrator(dir).apply(foreign, dir.resolve("学习与生活计划.md")));
        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")));
        assertFalse(Files.exists(dir.resolve(".backup")));
    }

    /** {@code .archive} 是不可变归档：内容不同就拒绝覆盖（绝不静默改写历史快照）。 */
    @Test
    void apply_refusesToOverwriteADifferentArchive(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Migrator m = new Migrator(dir);
        m.apply(m.plan(legacy), target);
        Files.delete(target);                                   // 模拟用户删掉目标后再迁移一次
        Files.writeString(legacy, LEGACY + "\n- 后来又写了一行\n", StandardCharsets.UTF_8);

        Migrator again = new Migrator(dir);
        MigrationPlan plan = again.plan(legacy);
        assertThrows(IOException.class, () -> again.apply(plan, target));

        assertFalse(Files.exists(target), "归档冲突时不得写出目标文件");
        assertTrue(Files.readString(dir.resolve(".archive").resolve("408-进度.md")).contains("测试"),
                "原归档必须原样还在");
    }

    /** 归档内容一致时（源文件没变，用户只是删了目标）允许重跑：归档保持原样，不重复写。 */
    @Test
    void apply_isRepeatableWhenNothingChanged(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Migrator m = new Migrator(dir);
        MigrationResult first = m.apply(m.plan(legacy), target);
        byte[] archiveBefore = Files.readAllBytes(first.archive());
        Files.delete(target);

        Migrator again = new Migrator(dir);
        MigrationResult second = again.apply(again.plan(legacy), target);

        assertTrue(Files.exists(second.target()));
        assertArrayEquals(archiveBefore, Files.readAllBytes(second.archive()), "归档不得被改写");
        assertArrayEquals(Files.readAllBytes(legacy), Files.readAllBytes(second.archive()),
                "归档始终等于原文");
    }
}
