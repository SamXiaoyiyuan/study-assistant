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
import java.net.URL;
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

    /**
     * 语义边界：{@link ExternalChangePolicy#FORCE_OVERWRITE} + <b>界面内容与基线相同</b> + 有外部改动。
     *
     * <p>此时 {@code currentText.equals(originalText)} 成立，但磁盘已被人改过。当前实现走的是
     * 「备份 + 覆盖」——把磁盘那份换成我们认可的那份，并留下对方那份的可回滚快照。
     *
     * <p><b>为什么这是对的</b>（不是缺陷）：这一格的冲突与「用户有没有在界面里编辑过」无关，
     * 而是「磁盘那份已经不是你打开时的那份」。用户在这里显式选了「覆盖」，意图指向的是磁盘上的
     * 内容——把它换成我认可的版本——而不是「我到底改没改过」。若这里改走 {@code UNCHANGED}
     * （什么都不做、什么都不提示），等于把别人写进去的内容<b>静默留在磁盘上</b>，同时不给用户任何
     * 反馈：那正是本任务（「外部改动绝不静默」）要杜绝的东西。而「备份 + 覆盖」至少把对方那份先存下来，
     * 用户随时能拿回去。与任务书第 1 条（外部改动必须先让用户表态）一致：表态过了就照办。
     */
    @Test
    void forceOverwrite_withContentEqualToBaseline_stillBacksUp_andOverwritesExternalChange()
            throws IOException {
        Path f = plan();
        Files.writeString(f, "初始\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        // 刻意不 update：currentText == originalText == "初始\n"
        Files.writeString(f, "外部那份\n", StandardCharsets.UTF_8);

        assertEquals(SaveResult.SAVED, s.save(ExternalChangePolicy.FORCE_OVERWRITE),
                "有外部改动 + 用户确认覆盖 → 必须真的写下去（不能退化成 UNCHANGED）");
        assertEquals("初始\n", Files.readString(f, StandardCharsets.UTF_8),
                "覆盖写下去的必须是我们认可的那份内容");
        List<String> backups = backupNames(f);
        assertEquals(1, backups.size(), "覆盖式写入必须把对方那份先备份下来（备份数 +1）");
        assertEquals("外部那份\n",
                Files.readString(dir.resolve(".backup").resolve(backups.get(0)), StandardCharsets.UTF_8),
                "备份下来的必须是外部改动后的那份（用户能拿回去的退路）");
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

    /**
     * {@link ExternalChangePolicy#RELOAD} <b>不是写路径</b>：它只把磁盘那一份接受为新基线，
     * 一个字节都不许写，也不许产生备份。
     *
     * <p>这一条与 {@link #reload_discardsMemoryEditAndRebaselines} 的区别：那条走的是用户直接调
     * {@code reload()}；这条走的是「带着未保存的编辑去 {@code save(RELOAD)}」——最容易和
     * {@code FORCE_OVERWRITE} 混起来的那条边界。RELOAD 的语义是「我认输，磁盘那份才算数」，
     * 所以这次调用必须一个字节都不写（内存里那份编辑由调用方按返回结果自行丢弃，见
     * {@link ExternalChangePolicy#RELOAD}），而新基线必须变成磁盘那份。
     */
    @Test
    void save_RELOAD_acceptsDiskAsNewBaseline_andWritesNotEvenAByte() throws IOException {
        Path f = plan();
        Files.writeString(f, "初始\n", StandardCharsets.UTF_8);

        DocumentSession s = DocumentSession.open(f, rotation());
        s.update("我的编辑\n");                                    // 内存里有未保存的编辑
        byte[] external = "外部那份\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f, external);                                 // 外部把磁盘改掉

        assertEquals(SaveResult.BLOCKED_EXTERNAL_CHANGE, s.save(ExternalChangePolicy.RELOAD),
                "RELOAD 不写盘：它只能返回「被拦下」");
        assertArrayEquals(external, Files.readAllBytes(f),
                "磁盘必须逐字等于外部那一份——RELOAD 一个字节都不许写");
        assertEquals(0, backupNames(f).size(),
                "RELOAD 连备份都不该产生（备份是「即将覆盖」的前奏，而这里什么都不覆盖）");
        assertEquals("外部那份\n", s.originalText(),
                "RELOAD 的语义是「接受磁盘那一份为新基线」：originalText() 必须变成外部那份");
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
     * 被守卫的两个包（内部名形式的包目录）。
     *
     * <p><b>刻意不写死类名</b>：Task 4–10 每个页面都会往这两个包里加类，写死的清单必定漏掉新类
     * ——那时守卫会「看起来还绿着」，实际罩不住任何新代码。改成「按包目录发现」后，只要类落在
     * {@code ui/view} / {@code ui/control} 下，就自动被扫到，无需每次加类都回来改清单。
     */
    private static final List<String> GUARDED_PACKAGES = List.of(
            "com/xiaoyiyuan/study/ui/view",
            "com/xiaoyiyuan/study/ui/control");

    /** 界面视图/控制层禁止引用的文件 API 的<b>内部名</b>（与 class 常量池里的写法一致）。 */
    private static final List<String> FORBIDDEN_INTERNAL_NAMES = List.of(
            "java/nio/file/Files",
            "com/xiaoyiyuan/study/storage/TextFileCodec",
            "com/xiaoyiyuan/study/storage/FileStore");

    @Test
    void uiViewLayer_hasNoDirectFileWriteApi() throws Exception {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String pkg : GUARDED_PACKAGES) {
            Path compiledDir = compiledPackageDir(pkg);
            List<String> classFiles = classFilesIn(compiledDir);

            // 反空转 A：目录里确实有类。目录路径写错 / 什么都没编译进来时，扫描会形同虚设。
            assertFalse(classFiles.isEmpty(),
                    pkg + " 的编译产物目录里一个 .class 都没有——扫描会形同虚设：" + compiledDir);

            // 反空转 B：编译出来的顶层类数 == 源码 .java 数。
            // 「某个类没被编译进来」（顶层类少一个）或「源码目录写错」（找不到 / 数目对不上）都会红，
            // 而不是静默 0 命中。只数顶层类：一个 .java 可以产出多个 .class（内部类 / 匿名类
            // Foo$1.class），把它们也算进来会让这条证据在合法代码上误红；但它们仍逐个参与常量池扫描。
            int topLevel = (int) classFiles.stream().filter(n -> !n.contains("$")).count();
            int sourceFiles = sourceJavaCount(pkg);
            assertEquals(sourceFiles, topLevel,
                    pkg + " 的编译产物顶层类数必须等于源码 .java 数"
                            + "（少了 = 有类没被编译进来；目录对不上 = 源码目录写错）。classFiles=" + classFiles);

            for (String classFile : classFiles) {
                scanned++;
                byte[] bytes = Files.readAllBytes(compiledDir.resolve(classFile));
                assertTrue(bytes.length > 0, "读不到 " + pkg + "/" + classFile + " 的字节码");
                for (String needle : FORBIDDEN_INTERNAL_NAMES) {
                    if (referencesConstant(bytes, needle)) {
                        offenders.add(pkg + "/" + classFile + " 引用 " + needle);
                    }
                }
            }
        }
        assertTrue(scanned > 0, "至少要扫到一个类，否则这条守卫是空转的");
        assertEquals(List.of(), offenders,
                "ui/view 与 ui/control 里禁止引用 Files / TextFileCodec / FileStore：唯一入口是 ui/service："
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

    /** 编译产物里某个包目录的位置。走 classpath 解析（{@code getResource}），与工作目录无关。 */
    private static Path compiledPackageDir(String pkg) throws Exception {
        URL url = DocumentSessionTest.class.getResource("/" + pkg);
        assertNotNull(url, "类路径里找不到包目录 /" + pkg);
        assertEquals("file", url.getProtocol(),
                "包目录必须落在文件系统上（测试不打包成 jar），实际协议 " + url.getProtocol() + "：" + url);
        return Path.of(url.toURI());
    }

    /** 某个包目录下的全部 {@code .class} 文件名（含内部类；按名排序，便于失败时阅读）。 */
    private static List<String> classFilesIn(Path compiledDir) throws IOException {
        try (Stream<Path> entries = Files.list(compiledDir)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .sorted()
                    .toList();
        }
    }

    /** 对应源码目录下的 {@code .java} 文件数（相对模块根定位，与 {@code ShellTest.uiSources} 同一约定）。 */
    private static int sourceJavaCount(String pkg) throws IOException {
        Path sourceDir = Path.of("src", "main", "java", pkg);
        assertTrue(Files.isDirectory(sourceDir),
                "找不到源码目录 " + sourceDir.toAbsolutePath() + "（路径写错？）");
        try (Stream<Path> entries = Files.list(sourceDir)) {
            return (int) entries.filter(p -> p.getFileName().toString().endsWith(".java")).count();
        }
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
