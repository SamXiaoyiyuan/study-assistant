package com.xiaoyiyuan.study.ui.service;

import com.xiaoyiyuan.study.markdown.MarkdownParser;
import com.xiaoyiyuan.study.plan.PlanDocument;
import com.xiaoyiyuan.study.storage.BackupRotation;
import com.xiaoyiyuan.study.storage.NotUtf8Exception;
import com.xiaoyiyuan.study.wellbeing.EmotionEntry;
import com.xiaoyiyuan.study.wellbeing.EmotionLog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 界面侧数据访问层（Task 3）的核心断言。
 *
 * <p>本文件把三件事钉在测试里：① {@code read()} 先于 {@code save()}，外部改动一行都不许静默覆盖；
 * ② 每份文件全局只有一个 {@code DocumentSession}（第二个保存者必须被拦下）；
 * ③ {@code ui/view} 与 {@code ui/control} 里没有任何类直接引用文件写入 API
 * （Step 7 的形态守卫，用字节码常量池扫描实现）。
 */
class DocumentSessionTest {

    /** 每个文件保留 10 份（spec §4.5）。 */
    private static final int BACKUP_KEEP = 10;

    /** 备份命名规格：{@code <原文件名>.<yyyyMMdd-HHmmss>.bak}。 */
    private static final Pattern BACKUP_NAME = Pattern.compile("^p\\.md\\.\\d{8}-\\d{6}\\.bak$");

    @TempDir
    Path dir;

    private BackupRotation rotation() {
        return new BackupRotation(dir.resolve(".backup"), BACKUP_KEEP);
    }

    private Path plan() {
        return dir.resolve("p.md");
    }

    // ================================================================================
    // Step 1：read() 先于 save()，外部改动不静默覆盖
    // ================================================================================

    @Test
    void save_beforeAnyRead_isBlocked_notSilentOverwrite() throws IOException {
        Path f = plan();
        Files.writeString(f, "已有内容\n", StandardCharsets.UTF_8);
        DocumentSession s = DocumentSession.open(f, rotation());
        Files.writeString(f, "别人改过\n", StandardCharsets.UTF_8); // 模拟 AI 助手 / 编辑器改动

        assertEquals(SaveResult.BLOCKED_EXTERNAL_CHANGE, s.save(ExternalChangePolicy.ASK_USER));
        assertEquals("别人改过\n", Files.readString(f, StandardCharsets.UTF_8),
                "检测到外部改动时一个字节都不许写");
    }

    @Test
    void save_afterRead_writesBackExactly() throws IOException {
        Path f = plan();
        Files.writeString(f, "原有\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        s.update("新内容\n");

        assertEquals(SaveResult.SAVED, s.save(ExternalChangePolicy.ASK_USER));
        assertEquals("新内容\n", Files.readString(f, StandardCharsets.UTF_8), "磁盘必须逐字等于我们写下的内容");
    }

    @Test
    void save_unchangedContent_isUNCHANGED_andDoesNotBackup() throws IOException {
        Path f = plan();
        Files.writeString(f, "已有内容\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        assertEquals(SaveResult.UNCHANGED, s.save(ExternalChangePolicy.ASK_USER));
        assertFalse(Files.exists(dir.resolve(".backup")),
                "一次「什么都没改的保存」不该在 .backup 里堆快照（那会把真正需要的那十份挤掉）");
    }

    @Test
    void everySave_leavesABackupWithSpecName() throws Exception {
        Path f = plan();
        Files.writeString(f, "初始\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        s.update("第一版\n");
        assertEquals(SaveResult.SAVED, s.save(ExternalChangePolicy.ASK_USER));

        // 备份名精度是「秒」，两次保存必须落在不同的秒里，否则 BackupRotation 会按规格用
        // REPLACE_EXISTING 覆盖同名备份（计划 1 的已接受取舍），这里就只剩一份。
        awaitNextSecond();

        s.update("第二版\n");
        assertEquals(SaveResult.SAVED, s.save(ExternalChangePolicy.ASK_USER));

        List<String> backups = backupNames(f);
        assertEquals(2, backups.size(), "两次真实保存必须留下 2 份备份，实际 " + backups);
        for (String name : backups) {
            assertTrue(BACKUP_NAME.matcher(name).matches(), "备份名必须匹配 " + BACKUP_NAME + "，实际 " + name);
        }
    }

    @Test
    void forceOverwrite_isTheOnlyWayToWriteOverAnExternalChange() throws IOException {
        Path f = plan();
        Files.writeString(f, "初始\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        s.update("我的内容\n");
        Files.writeString(f, "别人改过\n", StandardCharsets.UTF_8);

        assertEquals(SaveResult.BLOCKED_EXTERNAL_CHANGE, s.save(ExternalChangePolicy.ASK_USER),
                "ASK_USER 不是写路径：它只能返回「被拦下」");
        assertEquals("别人改过\n", Files.readString(f, StandardCharsets.UTF_8), "被拦下的这次必须一个字节都没写");
        assertEquals(0, backupNames(f).size(), "被拦下的这次连备份都不该产生");

        assertEquals(SaveResult.SAVED, s.save(ExternalChangePolicy.FORCE_OVERWRITE));
        assertEquals("我的内容\n", Files.readString(f, StandardCharsets.UTF_8));
        assertEquals(1, backupNames(f).size(), "覆盖式写入必须把对方那份先备份下来（备份数 +1）");
    }

    @Test
    void reload_discardsMemoryEditAndRebaselines() throws IOException {
        Path f = plan();
        Files.writeString(f, "初始\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        s.update("我的编辑\n");
        Files.writeString(f, "外部那份\n", StandardCharsets.UTF_8);

        assertEquals("外部那份\n", s.reload(), "reload() 必须丢弃内存编辑、返回外部那份文本");
        assertEquals("外部那份\n", s.currentText(), "reload() 之后内存文本就是外部那份");
        assertEquals(SaveResult.UNCHANGED, s.save(ExternalChangePolicy.ASK_USER),
                "重新载入后基线已对齐，再保存就是「什么都没改」");
        assertEquals("外部那份\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void invalidUtf8_isReportedAndNothingIsWritten() throws IOException {
        Path f = plan();
        byte[] gbk = "你好，世界\n".getBytes(Charset.forName("GBK"));
        Files.write(f, gbk);

        assertThrows(NotUtf8Exception.class, () -> DocumentSession.open(f, rotation()));
        assertArrayEquals(gbk, Files.readAllBytes(f), "非法 UTF-8 一律不写任何内容");
        assertFalse(Files.exists(dir.resolve(".backup")), "读取失败不该产生任何备份");
    }

    @Test
    void twoSessionsOnSameFile_secondSaveIsBlocked() throws IOException {
        Path f = plan();
        Files.writeString(f, "初始\n", StandardCharsets.UTF_8);

        DocumentSession a = DocumentSession.open(f, rotation());
        DocumentSession b = DocumentSession.open(f, rotation()); // b 的基线是「初始」

        a.update("A\n");
        assertEquals(SaveResult.SAVED, a.save(ExternalChangePolicy.ASK_USER));

        b.update("B\n");
        assertEquals(SaveResult.BLOCKED_EXTERNAL_CHANGE, b.save(ExternalChangePolicy.ASK_USER),
                "b 的基线过期了：它必须被拦下，而不是静默覆盖 a 刚写下的内容");
        assertEquals("A\n", Files.readString(f, StandardCharsets.UTF_8));
    }

    @Test
    void baselineHash_matchesDiskBytes_andTracksReads() throws Exception {
        Path f = plan();
        byte[] bytes = "基线内容\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f, bytes);

        DocumentSession s = DocumentSession.open(f, rotation());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                s.baselineHash(), "open 之后基线哈希必须等于磁盘字节的 SHA-256");
    }

    @Test
    void lastSaved_isNullUntilARealSave_thenOnlyAdvancesOnSuccess() throws IOException {
        Path f = plan();
        Files.writeString(f, "初始\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        assertNull(s.lastSaved(), "从未保存过时 lastSaved() 必须是 null");

        s.update("v1\n");
        assertEquals(SaveResult.SAVED, s.save(ExternalChangePolicy.ASK_USER));
        LocalDateTime afterFirstSave = s.lastSaved();
        assertNotNull(afterFirstSave, "成功保存后 lastSaved() 必须有值");

        // 一次被拦下的保存不得推进「最后保存时间」
        Files.writeString(f, "外面改的\n", StandardCharsets.UTF_8);
        s.update("v2\n");
        assertEquals(SaveResult.BLOCKED_EXTERNAL_CHANGE, s.save(ExternalChangePolicy.ASK_USER));
        assertEquals(afterFirstSave, s.lastSaved(), "没保存成功的这次不得更新最后保存时间");
    }

    // ================================================================================
    // §五 生活准则：走原文块路径（PlanDocument 没有建模这一节）
    // ================================================================================

    @Test
    void planService_editLifeRule_keepsEveryOtherByteIdentical() throws IOException {
        Path f = plan();
        Files.writeString(f, SAMPLE_PLAN, StandardCharsets.UTF_8);

        PlanService service = new PlanService(DocumentSession.open(f, rotation()), dir);

        service.setLifeRule(0, "新的第一行");

        String expected = SAMPLE_PLAN.replace("- 睡眠优先：23:30 前上床\n", "- 新的第一行\n");
        assertEquals(expected, service.document().render(),
                "只允许目标那一行变化，其余每一字节都必须原样");
    }

    @Test
    void lifeRules_roundTrip_isByteIdentical() throws IOException {
        Path f = plan();
        Files.writeString(f, SAMPLE_PLAN, StandardCharsets.UTF_8);

        PlanService service = new PlanService(DocumentSession.open(f, rotation()), dir);
        List<String> rules = service.lifeRules();

        // 读出每一行、原样写回去：render() 必须逐字节等于原文
        for (int i = 0; i < rules.size(); i++) {
            service.setLifeRule(i, rules.get(i));
        }
        assertEquals(SAMPLE_PLAN, service.document().render(),
                "读出来再原样写回去，必须逐字节等于原文");
    }

    @Test
    void lifeRules_addRemoveMove_areOrderStable() throws IOException {
        Path f = plan();
        Files.writeString(f, SAMPLE_PLAN, StandardCharsets.UTF_8);

        PlanService service = new PlanService(DocumentSession.open(f, rotation()), dir);
        assertEquals(List.of("睡眠优先：23:30 前上床", "每周运动 ≥2 次"), service.lifeRules(),
                "lifeRules() 返回去掉 `- ` 前缀与行尾换行的正文行");

        service.addLifeRule("第三条");
        assertEquals(List.of("睡眠优先：23:30 前上床", "每周运动 ≥2 次", "第三条"), service.lifeRules());
        assertTrue(service.document().render().contains("- 第三条\n"), "写回时必须补 `- ` 前缀");

        service.removeLifeRule(1);
        assertEquals(List.of("睡眠优先：23:30 前上床", "第三条"), service.lifeRules());

        service.moveLifeRule(0, 1);
        assertEquals(List.of("第三条", "睡眠优先：23:30 前上床"), service.lifeRules());

        // 移动之后，其余字节（标题、其它章节）仍原样
        String rendered = service.document().render();
        assertTrue(rendered.contains("## 一、我的现状"));
        assertTrue(rendered.contains("## 六、阶段排期"));
    }

    @Test
    void planService_mutate_goesThroughSession_andSaveBlocksOnExternalChange() throws IOException {
        Path f = plan();
        Files.writeString(f, SAMPLE_PLAN, StandardCharsets.UTF_8);

        PlanService service = new PlanService(DocumentSession.open(f, rotation()), dir);
        Files.writeString(f, "别人把计划改了\n", StandardCharsets.UTF_8);

        service.mutate(document -> document.setProfileItem(0, "大三上"));
        assertEquals(SaveResult.BLOCKED_EXTERNAL_CHANGE, service.save(ExternalChangePolicy.ASK_USER));
        assertEquals("别人把计划改了\n", Files.readString(f, StandardCharsets.UTF_8),
                "外部改动下 PlanService.save 同样一个字节都不许写");
    }

    // ================================================================================
    // 情绪记录：EmotionLog 没有 dirty()，脏判定只能由 DocumentSession 做
    // ================================================================================

    @Test
    void emotionService_addEntry_writesThroughAndKeepsHeaderByteIdentical() throws IOException {
        Path f = plan();
        String header = EmotionLog.empty().render();
        Files.writeString(f, header, StandardCharsets.UTF_8);

        EmotionService service = new EmotionService(DocumentSession.open(f, rotation()));
        LocalDate date = LocalDate.of(2026, 10, 10);
        service.mutate(log -> log.addEntry(
                new EmotionEntry(date, "平静", 3, "7 小时", null, null, null, List.of())));

        assertEquals(SaveResult.SAVED, service.save(ExternalChangePolicy.ASK_USER));
        String onDisk = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(onDisk.startsWith(header), "头部必须逐字节原样，实际 " + onDisk);
        assertTrue(onDisk.contains("## " + EmotionEntry.headingLabel(date)), "新条目必须写进去：" + onDisk);
    }

    @Test
    void emotionService_savingWithNoEdit_isUNCHANGED() throws IOException {
        Path f = plan();
        Files.writeString(f, EmotionLog.empty().render(), StandardCharsets.UTF_8);

        EmotionService service = new EmotionService(DocumentSession.open(f, rotation()));
        assertEquals(SaveResult.UNCHANGED, service.save(ExternalChangePolicy.ASK_USER));
        assertFalse(Files.exists(dir.resolve(".backup")));
    }

    // ================================================================================
    // Step 7：ui/view 与 ui/control 下不得引用文件写入 API（字节码常量池扫描）
    // ================================================================================

    /**
     * {@code ui.view} 与 {@code ui.control} 下的类，<b>类列表显式写死</b>。
     *
     * <p>写死的理由与 Task 1 那条占位断言同源：空列表会让下面的循环形同虚设，
     * 所以列表非空本身也要被断言。
     */
    private static final List<String> VIEW_AND_CONTROL_CLASSES = List.of(
            "com.xiaoyiyuan.study.ui.view.Page",
            "com.xiaoyiyuan.study.ui.view.PlaceholderPage",
            "com.xiaoyiyuan.study.ui.view.StartupNoticeBar",
            "com.xiaoyiyuan.study.ui.control.StatusModel");

    /** 界面视图层禁止引用的文件 API 的<b>内部名</b>（与 class 常量池里的写法一致）。 */
    private static final List<String> FORBIDDEN_INTERNAL_NAMES = List.of(
            "java/nio/file/Files",
            "com/xiaoyiyuan/study/storage/TextFileCodec",
            "com/xiaoyiyuan/study/storage/FileStore");

    @Test
    void uiViewLayer_hasNoDirectFileWriteApi() throws IOException {
        assertFalse(VIEW_AND_CONTROL_CLASSES.isEmpty(),
                "类列表不能为空——空列表会让下面的循环形同虚设（Task 1 那条占位断言的同类错误）");

        List<String> offenders = new ArrayList<>();
        for (String className : VIEW_AND_CONTROL_CLASSES) {
            byte[] bytes = classBytes(className);
            assertTrue(bytes.length > 0, "读不到 " + className + " 的字节码");
            for (String needle : FORBIDDEN_INTERNAL_NAMES) {
                if (referencesConstant(bytes, needle)) {
                    offenders.add(className + " 引用 " + needle);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "ui.view / ui.control 里禁止引用 Files / TextFileCodec / FileStore：唯一入口是 ui/service："
                        + offenders);

        // 正向自证：扫描器必须在一个【确实引用 FileStore】的类上命中。
        // 少了这一条，上面那段可能只是「扫描器永远返回 false」的假绿。
        assertTrue(referencesConstant(classBytes("com.xiaoyiyuan.study.ui.service.DocumentSession"),
                        "com/xiaoyiyuan/study/storage/FileStore"),
                "正向自证：扫描器必须能在 DocumentSession 上命中 FileStore");
        assertFalse(referencesConstant(classBytes("com.xiaoyiyuan.study.ui.control.StatusModel"),
                        "com/xiaoyiyuan/study/storage/FileStore"),
                "负向对照：StatusModel 不该引用 FileStore");
    }

    /** 类路径上的 {@code .class} 字节（不依赖工作目录，走 classpath 资源）。 */
    private static byte[] classBytes(String className) throws IOException {
        String resource = "/" + className.replace('.', '/') + ".class";
        try (InputStream in = DocumentSessionTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类路径里找不到 " + resource);
            return in.readAllBytes();
        }
    }

    /**
     * 字节码里是否出现某个内部名常量。
     *
     * <p>要求命中位置的下一个字节<b>不是</b>名字字符：否则 {@code java/nio/file/Files}
     * 会把 {@code java/nio/file/FileSystems} 也当成命中（前者是后者的前缀）。
     * 常量池里一个 UTF8 项之后紧跟的是下一个项的类型字节（控制字符）或类文件结构字段，
     * 都不会是名字字符，所以这个边界检查既挡住误报、也不会漏掉真命中。
     */
    private static boolean referencesConstant(byte[] classFile, String internalName) {
        byte[] needle = internalName.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length <= classFile.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classFile[i + j] != needle[j]) {
                    continue outer;
                }
            }
            int after = i + needle.length;
            if (after >= classFile.length || !isNameByte(classFile[after])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNameByte(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                || b == '_' || b == '$' || b == '/';
    }

    // ================================================================================
    // 测试基建
    // ================================================================================

    /** 等待时钟跨过下一秒（备份名精度是秒，见 {@link #everySave_leavesABackupWithSpecName}）。 */
    private static void awaitNextSecond() throws InterruptedException {
        int second = LocalDateTime.now().getSecond();
        for (int i = 0; i < 40 && LocalDateTime.now().getSecond() == second; i++) {
            Thread.sleep(50);
        }
    }

    private List<String> backupNames(Path source) throws IOException {
        Path backupDir = dir.resolve(".backup");
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        String prefix = source.getFileName() + ".";
        try (Stream<Path> entries = Files.list(backupDir)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 一份结构完整、可被 {@link PlanDocument} 解析的样例计划（含 §五 生活准则与
     * §七 那张与 §四 同形状的表——用来验证§五 的原文块路径不会误伤别的章节）。
     */
    static final String SAMPLE_PLAN = """
            # 学习与生活计划

            > 当前学期：大二上

            ---

            ## 一、我的现状

            | 项目 | 内容 |
            |---|---|
            | 年级 / 学期 | 大二上 |

            ---

            ## 二、目标清单

            | 目标 | 状态 | 优先级 | 时间窗 | 说明 |
            |---|---|---|---|---|
            | 本学期学校课程 | 进行中 | 1 | 2026.9–2027.1 | 唯一必须完成的事 |
            | 考研（408） | 暂缓 | — | 大三再定 | 可能取消 |

            ---

            ## 三、课程（本学期）

            | 课程 | 本周任务 | 截止 | 状态 |
            |---|---|---|---|
            | 数据库 | 第 4 章作业 |  | 待回报 |

            ---

            ## 四、每周底线

            | 目标 | 项 | 量 | 怎么安排 | 状态 |
            |---|---|---|---|---|
            | 本学期学校课程 | 作业按时交 | 每周随课 | 当周内完成 | 待回报 |
            | 考研（408） | 背单词 | 每天 50 个 | 睡前 | 待回报 |

            ---

            ## 五、生活准则

            - 睡眠优先：23:30 前上床
            - 每周运动 ≥2 次

            ---

            ## 六、阶段排期

            | 阶段 | 时间窗 | 主攻 | 验收标准 |
            |---|---|---|---|
            | A | 2026.9–2027.1 | 学校 6 门课 | 全部及格 |

            ---

            ## 七、本周

            | 目标 | 项 | 量 | 怎么安排 | 状态 |
            |---|---|---|---|---|
            | 本学期学校课程 | 七节的底线表不能被当成四节 | 每周随课 | 当周内完成 | 待回报 |
            """;

    /** 计划文件里那些不是 §五 的行，逐行取出（供「其余字节不变」类断言复用）。 */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    static {
        // 让 SAMPLE_PLAN 的自洽性在测试类装载时就被一次轻量检查覆盖：
        // 它必须能被 MarkdownParser 逐字节往返（§五 的原文块路径依赖这条性质）。
        String rendered = MarkdownParser.parse(SAMPLE_PLAN).render();
        if (!rendered.equals(SAMPLE_PLAN)) {
            throw new AssertionError("SAMPLE_PLAN 不是 MarkdownParser 的往返恒等样例");
        }
    }
}
