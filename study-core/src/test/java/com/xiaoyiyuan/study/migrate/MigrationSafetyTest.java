package com.xiaoyiyuan.study.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
     *
     * <p>这条拒绝与「归档冲突」都是 {@link IOException}，所以必须断言消息，否则两条路径互换也看不出来。
     */
    @Test
    void apply_refusesWhenSourceChangedAfterPlan(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Migrator m = new Migrator(dir);
        MigrationPlan plan = m.plan(legacy);
        Files.writeString(legacy, LEGACY + "\n- plan 之后用户又写了一行\n", StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, () -> m.apply(plan, dir.resolve("学习与生活计划.md")));

        assertTrue(e.getMessage().contains("源文件"), "拒绝理由必须是「源文件内容与方案不符」：" + e.getMessage());
        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")), "过期方案不得写出目标文件");
        assertFalse(Files.exists(dir.resolve(".backup")), "过期方案不得产生备份");
        assertFalse(Files.exists(dir.resolve(".archive")), "过期方案不得产生归档");
    }

    /**
     * {@code MigrationPlan} 自带源文件路径，所以 {@code apply} 的闸门是<b>内容证据</b>而不是
     * 「方案是不是本实例产的」：brief 公开的用法正是 {@code new Migrator(dir).apply(plan, target)}
     * （Task 10 的集成点），换一个实例不该改变任何结果。
     */
    @Test
    void apply_acceptsAPlanProducedByAnotherMigrator(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        MigrationPlan plan = new Migrator(dir).plan(legacy);

        MigrationResult r = new Migrator(dir).apply(plan, target);

        assertTrue(Files.exists(r.target()), "新实例 apply 旧方案（同一份源文件）必须照常迁移");
        assertTrue(Files.exists(r.backup()));
        assertTrue(Files.exists(r.archive()));
        assertArrayEquals(Files.readAllBytes(legacy), Files.readAllBytes(r.archive()),
                "归档仍必须逐字节等于源文件");
    }

    /**
     * 外来方案 + 目标已存在：该抛的是 {@link TargetExistsException}，不是参数错误。
     *
     * <p>目标检查必须排在任何别的检查之前，否则「绝不静默覆盖」这条规格会被一个无关的检查遮住
     * （早期实现的身份闸门就抢在它前面抛 {@code IllegalArgumentException}）。
     */
    @Test
    void apply_withForeignPlanStillRefusesAnExistingTarget(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Files.writeString(target, "# 已经有一份了\n");
        MigrationPlan foreign = new Migrator(dir).plan(legacy);

        assertThrows(TargetExistsException.class, () -> new Migrator(dir).apply(foreign, target));
        assertEquals("# 已经有一份了\n", Files.readString(target));
        assertFalse(Files.exists(dir.resolve(".backup")), "拒绝时不得已经建了备份目录");
    }

    /**
     * 凭空造的方案（源路径指着真实文件，但 {@code sourceText} 与盘上内容不符）照样拒绝。
     *
     * <p>这是内容闸门接手身份闸门之后必须守住的东西：判据变成「现在是同一份内容」，那么
     * 「内容对不上」就必须响亮拒绝，而不是照着伪造的目标文本写盘。
     */
    @Test
    void apply_refusesAPlanWhoseSourceTextDoesNotMatchTheFile(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        MigrationPlan forged = new MigrationPlan(legacy, "# 伪造的源内容\n", "# 伪造的目标\n", List.of());

        IOException e = assertThrows(IOException.class,
                () -> new Migrator(dir).apply(forged, dir.resolve("学习与生活计划.md")));

        assertTrue(e.getMessage().contains("源文件"), "拒绝理由必须是「源文件内容与方案不符」：" + e.getMessage());
        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")), "内容不符的方案不得写出目标文件");
        assertFalse(Files.exists(dir.resolve(".backup")));
        assertFalse(Files.exists(dir.resolve(".archive")));
    }

    /**
     * {@code .archive} 是不可变归档：内容不同就拒绝覆盖（绝不静默改写历史快照）。
     *
     * <p>这条拒绝也必须<b>没有副作用</b>：预检查排在备份之前，所以连备份都不许新增。
     * 只断言「抛了 IOException」不够——{@code TargetExistsException} 与源内容不符也都是
     * {@code IOException}，而且把预检查挪到备份之后，异常照抛、备份照多，断言不红。
     */
    @Test
    void apply_refusesArchiveConflictBeforeCreatingAnyBackup(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        byte[] before = Files.readAllBytes(legacy);
        // 预置一份与源文件不同的归档：模拟「历史上归档过另一份内容」
        Path archiveDir = Files.createDirectory(dir.resolve(".archive"));
        Files.writeString(archiveDir.resolve("408-进度.md"), "# 另一份历史归档\n", StandardCharsets.UTF_8);
        Migrator m = new Migrator(dir);

        IOException e = assertThrows(IOException.class,
                () -> m.apply(m.plan(legacy), dir.resolve("学习与生活计划.md")));

        assertTrue(e.getMessage().contains("不可变归档"),
                "拒绝理由必须是归档冲突（不是别的 IOException），实际消息：" + e.getMessage());
        assertFalse(Files.exists(dir.resolve(".backup")),
                "归档冲突被拒绝时不得产生备份——备份会轮转、会删掉最旧的一份，"
                        + "一次「什么都没做」的拒绝不该有这个后果");
        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")), "归档冲突时不得写出目标文件");
        assertArrayEquals(before, Files.readAllBytes(legacy), "源文件必须一个字节都没动");
        assertEquals("# 另一份历史归档\n", Files.readString(archiveDir.resolve("408-进度.md")),
                "不可变归档绝不能被改写");
    }

    /** 归档冲突与「源文件被改过」是两条不同的拒绝路径，消息必须能区分。 */
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
        // 第一次迁移已经留下一份备份：这里要证明的是「它一个字节都没被动过」。
        Path existingBackup;
        try (var entries = Files.list(dir.resolve(".backup"))) {
            existingBackup = entries.findFirst().orElseThrow();
        }
        byte[] backupBefore = Files.readAllBytes(existingBackup);
        IOException e = assertThrows(IOException.class, () -> again.apply(plan, target));

        assertTrue(e.getMessage().contains("不可变归档"),
                "这条路径必须是归档冲突，实际消息：" + e.getMessage());
        // 这条路径走到的是 apply 的**只读预检查**（排在备份之前）：此刻源文件已经变了，而归档还是
        // 原来那份，预检查立刻就能看见冲突。所以这里的消息**应当**说「也没有产生备份」——它在这个
        // 调用点是真话（见下面 apply_writePhaseRecheck_... 那条：写入期复查是另一个调用点，
        // 那里备份已经落地，措辞必须不同）。
        assertTrue(e.getMessage().contains("也没有产生备份"),
                "预检查排在备份之前，消息必须声明没有产生备份，实际消息：" + e.getMessage());
        assertFalse(e.getMessage().contains("备份已建立"),
                "这条路径上备份确实没有建立（预检查早于备份），实际消息：" + e.getMessage());
        // 文件数不够：备份名精度是秒，同一秒内重复备份会 REPLACE_EXISTING 撞名、数量不变。
        // 所以「没新增备份」要用「原来那份备份的字节一个都没变」来证明。
        assertEquals(1, backupCount(dir), "归档冲突的拒绝不得新增备份");
        assertArrayEquals(backupBefore, Files.readAllBytes(existingBackup),
                "归档冲突的拒绝不得改动已有备份（同一秒内撞名会覆盖它）");
        assertFalse(Files.exists(target), "归档冲突时不得写出目标文件");
        assertTrue(Files.readString(dir.resolve(".archive").resolve("408-进度.md")).contains("测试"),
                "原归档必须原样还在");
    }

    /**
     * <b>写入期复查</b>那条路的拒绝消息：归档在「只读预检查通过」与「写入前最后一瞬」之间被别的
     * 程序改掉（TOCTOU），此时备份<b>已经</b>落地，消息必须如实给出它的路径。
     *
     * <p>为什么单开一条测试：{@link #apply_refusesToOverwriteADifferentArchive} 走的是只读预检查，
     * 断言的是「也没有产生备份」；同一个 {@code requireArchiveCoherent} 的第二个调用点（
     * {@code archiveSource} 内）在磁盘状态上正相反。早先两个调用点共用一句写死的话，于是在这条路上
     * 消息会假称没有备份——用户读完那句话本该去 {@code .backup\} 找内容，却会以为内容无从找回。
     *
     * <p><b>怎么确定性地制造这个窗口（2026-10-14 修复轮 2 重写）</b>：不再靠「另起线程轮询
     * {@code .backup\} 出现、再用超大文件把 {@code Files.copy} 拉长」去抢时机——那种写法在
     * {@code Files.copy} 够快时干扰线程根本来不及替换，实测全量跑 5/18 次红
     * （{@code Expected java.io.IOException to be thrown, but nothing was thrown}），
     * 而隔离跑 0/8 红，也就是说它守的性质<b>没有</b>被稳定守住。
     *
     * <p>现在把「谁在什么时候动归档」变成一行同步代码：用
     * {@link Migrator#apply(MigrationPlan, Path, Migrator.WritePhaseHook)} 的写入期观测点，
     * 在<b>备份已经落地、归档复查还没读盘</b>的那一刻，由<b>调用线程自己</b>把归档换成别的内容
     * （与真实世界里另一个程序的动作逐字同形）。于是：
     * <ul>
     *   <li>只读预检查<b>必然</b>看到那份与源文件一致的归档 → 必然通过；</li>
     *   <li>写入期复查<b>必然</b>看到被换过的归档 → 必然抛异常；</li>
     * </ul>
     * 全程没有 sleep、没有第二个线程、没有「通常更快」的假设。大文件也不再需要：本测试与体积无关。
     */
    @Test
    void apply_writePhaseRecheck_reportsTheBackupItAlreadyMade(@TempDir Path dir) throws Exception {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Migrator m = new Migrator(dir);
        MigrationPlan plan = m.plan(legacy);

        // 预置一份与源文件一致的归档：只读预检查因此通过（差异由钩子在备份之后制造）。
        Path archiveDir = Files.createDirectories(dir.resolve(".archive"));
        Path archive = archiveDir.resolve("408-进度.md");
        byte[] plannedBytes = plan.sourceText().getBytes(StandardCharsets.UTF_8);
        Files.write(archive, plannedBytes);

        // 钩子精确跑在「备份已建好」与「归档复查」之间。这里断言的是钩子自己的前置条件，
        // 它们同时也是「窗口真的被打开了」的证据：没有备份 → 这条测试就没有意义。
        AtomicInteger hookCalls = new AtomicInteger();
        AtomicReference<Path> backupAtHook = new AtomicReference<>();
        Migrator.WritePhaseHook intruder = () -> {
            hookCalls.incrementAndGet();
            try (var backups = Files.list(dir.resolve(".backup"))) {
                List<Path> made = backups.toList();
                assertEquals(1, made.size(),
                        "钩子被调用时备份必须已经恰好落地一份（这正是「写入期复查」这个调用点的定义）："
                                + made);
                assertEquals(plannedBytes.length, Files.size(made.get(0)),
                        "钩子看到的备份必须已经是源文件的完整副本：" + made.get(0));
                backupAtHook.set(made.get(0));
            }
            // 与真实世界里另一个程序的动作同形：replace 让归档**永远存在且内容不同**
            // （不存在「先删掉、预检查看见不存在而放行」的窗口），atomic 让替换不是半个状态。
            Path replacement = Files.createTempFile(archiveDir, "intruder", ".md");
            Files.writeString(replacement, "被别的程序改掉了\n", StandardCharsets.UTF_8);
            Files.move(replacement, archive, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        };

        IOException e = assertThrows(IOException.class, () -> m.apply(plan, target, intruder),
                "归档在预检查之后、写入之前被改掉时，apply 必须拒绝（写入期复查）");

        assertEquals(1, hookCalls.get(), "写入期观测点必须恰好被调用一次，实际 " + hookCalls.get() + " 次");
        assertTrue(e.getMessage().contains("不可变归档"),
                "这条路径必须是归档冲突，实际消息：" + e.getMessage());
        // 判据刻意用「目标文件未写入；备份已建立：」这个只有复查分支才会产出的整串：
        // 只断言含「备份已建立」的话，预检查分支哪天被误改成也带上这四个字就会蒙混过关。
        assertTrue(e.getMessage().contains("目标文件未写入；备份已建立："),
                "复查分支的措辞必须声明备份已建立，实际消息：" + e.getMessage());
        assertFalse(e.getMessage().contains("也没有产生备份"),
                "备份确实产生了，消息不得声称没有备份，实际消息：" + e.getMessage());
        try (var backups = Files.list(dir.resolve(".backup"))) {
            List<Path> made = backups.toList();
            assertEquals(1, made.size(), "写入期复查之前必须恰好产生一份备份");
            assertTrue(e.getMessage().contains(made.get(0).getFileName().toString()),
                    "消息必须给出那份备份的真实文件名，实际消息：" + e.getMessage());
        }
        assertNotNull(backupAtHook.get(), "钩子里必须已经观测到备份");
        assertFalse(Files.exists(target), "归档冲突时不得写出目标文件");
        assertEquals("被别的程序改掉了\n", Files.readString(archive),
                "归档已被别的程序改掉；migrator 绝不许把它改回去或覆盖它");
        assertArrayEquals(plannedBytes, Files.readAllBytes(legacy), "源文件必须一个字节都没动");
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

    /**
     * {@code .backup\} 里的文件数；目录不存在算 0。
     *
     * <p>用来断言「拒绝路径不新增备份」：只断言目录不存在是不够的——先在别处成功迁移过一次，
     * 目录就已经存在了。但它<b>不是充分</b>判据：备份名精度只到秒，同一秒内重复备份会
     * {@code REPLACE_EXISTING} 撞名，数量不变而内容被换掉。所以「已有备份没被动过」还要比对字节。
     */
    private static long backupCount(Path dir) throws IOException {
        Path backupDir = dir.resolve(".backup");
        if (!Files.isDirectory(backupDir)) {
            return 0;
        }
        try (var entries = Files.list(backupDir)) {
            return entries.count();
        }
    }
}
