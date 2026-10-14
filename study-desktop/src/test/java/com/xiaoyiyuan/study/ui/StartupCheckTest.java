package com.xiaoyiyuan.study.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.xiaoyiyuan.study.config.AppPaths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StartupCheck} 的护栏（Task 2）。
 *
 * <p>启动检查是「App 起不起来」这条路径上的东西，所以它的性质有两面：</p>
 * <ol>
 *   <li><b>只读</b>：检查本身绝不创建目录、绝不写文件（测试用 {@code @TempDir}，
 *       并在调用前后逐字节比对目录里的文件清单）；</li>
 *   <li><b>不阻断、不恐吓</b>：任何失败只产出一条中性 notice，绝不含 8 个界面禁用词，
 *       也绝不含诊断性标签。</li>
 * </ol>
 *
 * <p>全部用 {@code @TempDir}，绝不碰真实数据目录。</p>
 */
class StartupCheckTest {

    /** 界面禁用词（与全局约束、spec §8.2 逐字一致）。 */
    static final List<String> FORBIDDEN_WORDS =
            List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持");

    /** 诊断性标签（全局约束「AI 与安全」一节禁止）。 */
    static final List<String> DIAGNOSTIC_LABELS =
            List.of("抑郁", "焦虑症", "中度", "心理健康得分", "得分");

    /** 只读性断言排除的临时文件：Java 自己可能产生的（本检查绝不该产生）。 */
    static List<String> snapshot(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of("<目录不存在>");
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void bothFilesMissing_notReady_andOffersNothingToMigrate(@TempDir Path studyDir) {
        StartupCheck.Result result = StartupCheck.inspect(studyDir);

        assertFalse(result.ready(),
                "计划文件不存在时必须 ready=false（界面据此显示首启提示而不是进入总览），实际 notices=" + result.notices());
        assertFalse(StartupCheck.shouldOfferMigration(studyDir),
                "旧档案也不存在时没有可迁移的东西，不该提供迁移入口");
        assertEquals(AppPaths.planFile(studyDir), result.planFile(), "planFile 必须是 <studyDir>\\学习与生活计划.md");
        assertEquals(AppPaths.legacyFile(studyDir), result.legacyFile(), "legacyFile 必须是 <studyDir>\\408-进度.md");
        assertEquals(studyDir, result.studyDir(), "studyDir 必须原样回传，界面状态栏要用它");
        assertEquals(List.of(StartupCheck.NOTICE_NO_PLAN), result.notices(),
                "两个都不存在时恰好一条中性提示：" + StartupCheck.NOTICE_NO_PLAN);
        assertEquals("还没有计划文件。可以在设置里选一个目录，或让助手帮你建一份。",
                StartupCheck.NOTICE_NO_PLAN, "提示文案逐字取自本任务规则一节");
    }

    @Test
    void onlyLegacyPresent_offersMigration(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.legacyFile(studyDir), "# 旧档案\n", StandardCharsets.UTF_8);

        StartupCheck.Result result = StartupCheck.inspect(studyDir);

        assertTrue(StartupCheck.shouldOfferMigration(studyDir),
                "计划文件不存在而 408-进度.md 存在时必须提供迁移（spec §4.6 的触发条件）");
        assertFalse(result.ready(), "计划文件仍不存在，ready 必须为 false");
        assertEquals(List.of(StartupCheck.NOTICE_LEGACY_PRESENT), result.notices(),
                "旧档案存在时恰好一条中性提示：" + StartupCheck.NOTICE_LEGACY_PRESENT);
        assertEquals("旧档案还在原来的位置。可以把它迁移成新的计划文件。",
                StartupCheck.NOTICE_LEGACY_PRESENT, "提示文案逐字取自本任务规则一节");
    }

    @Test
    void onlyPlanPresent_readyWithoutMigration(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.planFile(studyDir), "# 学习与生活计划\n", StandardCharsets.UTF_8);

        StartupCheck.Result result = StartupCheck.inspect(studyDir);

        assertTrue(result.ready(), "计划文件存在即 ready=true（正常进入总览），实际 notices=" + result.notices());
        assertFalse(StartupCheck.shouldOfferMigration(studyDir), "计划文件已在，就不该再提迁移");
        assertTrue(result.notices().isEmpty(), "一切正常时提示条为空，实际 " + result.notices());
    }

    @Test
    void bothFilesPresent_readyAndNoMigration(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.planFile(studyDir), "# 学习与生活计划\n", StandardCharsets.UTF_8);
        Files.writeString(AppPaths.legacyFile(studyDir), "# 旧档案\n", StandardCharsets.UTF_8);

        StartupCheck.Result result = StartupCheck.inspect(studyDir);

        assertTrue(result.ready(), "两份都在也是「正常」：迁移已经做过了");
        assertFalse(StartupCheck.shouldOfferMigration(studyDir),
                "计划文件已存在时不提供迁移（否则每次启动都劝用户再迁移一次）");
        assertTrue(result.notices().isEmpty(), "提示条为空，实际 " + result.notices());
    }

    /**
     * 计划文件存在但是个目录（读不动的典型形态之一）时：不许抛异常，只给中性 notice。
     */
    @Test
    void planPathIsADirectory_isReportedAsNotice_notException(@TempDir Path studyDir) throws IOException {
        Files.createDirectory(AppPaths.planFile(studyDir));

        StartupCheck.Result result = StartupCheck.inspect(studyDir);

        assertFalse(result.ready(), "计划文件位置上是目录、不是文件 → 不 ready");
        assertEquals(1, result.notices().size(), "恰好一条中性提示，实际 " + result.notices());
        assertTrue(result.notices().get(0).startsWith(StartupCheck.NOTICE_UNREADABLE_PREFIX),
                "读不动时的提示必须带固定前缀 " + StartupCheck.NOTICE_UNREADABLE_PREFIX
                        + "，实际：" + result.notices().get(0));
        assertTrue(result.notices().get(0).contains(StartupCheck.NOTICE_UNREADABLE_SUFFIX),
                "提示里要给一条「接着能做什么」的中性说明，实际：" + result.notices().get(0));
    }

    /**
     * 指向一个<b>可控的</b>不可写点：注入「不可写」判定（minor 清算批 Minor-1）。
     *
     * <p>旧用例拿 {@code C:\Windows\…} 当不可写点，在本机被 {@code assumeFalse} 永久跳过，
     * 变异证明这半没被守住（task-2-review.md 的 M4b：把判据改成 {@code if (false)} 仍然全绿）。
     * 现在判定由测试注入，这条分支恒定承重、也不再依赖任何本机权限。</p>
     *
     * <p>注意「目录还没建」本身<b>不算</b>不可写——那是全新安装的常态（见
     * {@link #notYetExistingDirUnderWritableAncestor_isWritable}）。</p>
     */
    @Test
    void unwritableDir_isReportedAsNotice_notException(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.legacyFile(studyDir), "# 旧档案\n", StandardCharsets.UTF_8);
        List<Path> probed = new ArrayList<>();

        StartupCheck.Result result = StartupCheck.inspect(studyDir, dir -> {
            probed.add(dir);
            return false; // 注入：这个目录不可写
        });

        assertEquals(List.of(studyDir), probed, "可写判定必须作用在传入的 studyDir 上，而不是别的路径");
        assertFalse(result.ready(), "目录不可写时 ready=false");
        assertEquals(List.of(StartupCheck.NOTICE_NOT_WRITABLE), result.notices(),
                "不可写时要恰好一条中性提示（不可写优先于「有旧档案」那支，不得报迁移）："
                        + StartupCheck.NOTICE_NOT_WRITABLE + "，实际 " + result.notices());
        assertEquals(studyDir, result.studyDir(), "studyDir 原样回传（界面要显示它自己）");
    }

    /** 注入「可写」时走正常分支：不得因为注入了判定就改变其它分支的行为（Minor-1 的对照组）。 */
    @Test
    void writableProbeSaysTrue_fallsThroughToTheNoPlanBranch(@TempDir Path studyDir) {
        StartupCheck.Result result = StartupCheck.inspect(studyDir, dir -> true);

        assertFalse(result.ready(), "可写但没有计划文件 → 仍不 ready（这是首启常态）");
        assertEquals(List.of(StartupCheck.NOTICE_NO_PLAN), result.notices(),
                "判定为可写时走正常分支（无计划文件 → NO_PLAN），不得出现 NOT_WRITABLE，实际 " + result.notices());
    }

    @Test
    void notYetExistingDirUnderWritableAncestor_isWritable(@TempDir Path tempDir) {
        Path notYetExisting = tempDir.resolve("新学习目录").resolve("更深一层");

        assertFalse(Files.exists(notYetExisting), "前提：这个目录还不存在");
        assertTrue(StartupCheck.isWritable(notYetExisting),
                "目录还没建不等于不可写——要看最近的已存在祖先（" + tempDir
                        + "）是否可写，否则全新安装会被报成「不可写」而劝退用户");
        assertTrue(StartupCheck.isWritable(tempDir), "已存在的可写目录必须判为可写");
    }

    @Test
    void isWritable_doesNotCreateAnything(@TempDir Path tempDir) throws IOException {
        Path notYetExisting = tempDir.resolve("只判断_不要建");
        List<String> before = snapshot(tempDir);

        StartupCheck.isWritable(notYetExisting);

        assertEquals(before, snapshot(tempDir),
                "isWritable 是只读判断：只提示不建目录（建目录是存储层在真正保存时的事）");
    }

    @Test
    void inspect_isReadOnly_neverCreatesNorWritesAnything(@TempDir Path studyDir) throws IOException {
        List<String> before = snapshot(studyDir);

        StartupCheck.inspect(studyDir);
        StartupCheck.shouldOfferMigration(studyDir);
        StartupCheck.isWritable(studyDir);

        assertEquals(before, snapshot(studyDir),
                "启动检查必须只读：不得创建目录、不得落任何文件（本任务明确要求）");
    }

    @Test
    void notices_neverContainForbiddenWords(@TempDir Path studyDir) throws IOException {
        List<List<String>> cases = new ArrayList<>();
        cases.add(StartupCheck.inspect(studyDir).notices());
        Files.writeString(AppPaths.legacyFile(studyDir), "# 旧档案\n", StandardCharsets.UTF_8);
        cases.add(StartupCheck.inspect(studyDir).notices());
        Files.writeString(AppPaths.planFile(studyDir), "# 计划\n", StandardCharsets.UTF_8);
        cases.add(StartupCheck.inspect(studyDir).notices());
        Files.delete(AppPaths.planFile(studyDir));
        Files.createDirectory(AppPaths.planFile(studyDir));
        cases.add(StartupCheck.inspect(studyDir).notices());
        cases.add(StartupCheck.inspect(studyDir, dir -> false).notices());

        int checked = 0;
        for (List<String> notices : cases) {
            for (String notice : notices) {
                assertNotNull(notice, "notice 不得为 null");
                for (String word : FORBIDDEN_WORDS) {
                    assertFalse(notice.contains(word),
                            "启动提示里出现界面禁用词「" + word + "」：" + notice);
                }
                for (String label : DIAGNOSTIC_LABELS) {
                    assertFalse(notice.contains(label),
                            "启动提示里出现诊断性标签「" + label + "」：" + notice);
                }
                checked++;
            }
        }
        assertTrue(checked >= 3, "至少要覆盖 3 类提示（无计划 / 有旧档案 / 读不动），实际只检查了 " + checked + " 条");
    }

    @Test
    void noticeConstants_themselvesAreClean() {
        List<String> all = List.of(StartupCheck.NOTICE_NO_PLAN, StartupCheck.NOTICE_LEGACY_PRESENT,
                StartupCheck.NOTICE_NOT_WRITABLE, StartupCheck.NOTICE_UNREADABLE_PREFIX,
                StartupCheck.NOTICE_UNREADABLE_SUFFIX);
        for (String text : all) {
            assertFalse(text.isBlank(), "提示常量不得为空");
            for (String word : FORBIDDEN_WORDS) {
                assertFalse(text.contains(word), "提示常量里出现禁用词「" + word + "」：" + text);
            }
            for (String label : DIAGNOSTIC_LABELS) {
                assertFalse(text.contains(label), "提示常量里出现诊断性标签「" + label + "」：" + text);
            }
        }
    }

    @Test
    void inspect_rejectsNullStudyDir() {
        assertThrows(NullPointerException.class, () -> StartupCheck.inspect(null),
                "null 学习目录必须当场拒绝，而不是变成「相对路径」悄悄读到进程的工作目录");
        assertThrows(NullPointerException.class, () -> StartupCheck.shouldOfferMigration(null));
        assertThrows(NullPointerException.class, () -> StartupCheck.isWritable(null));
    }

    @Test
    void shouldOfferMigration_matchesInspectOnEveryCombination(@TempDir Path studyDir) throws IOException {
        assertFalse(StartupCheck.shouldOfferMigration(studyDir), "两份都没有时没有可迁移的东西");

        Files.writeString(AppPaths.planFile(studyDir), "# 计划\n", StandardCharsets.UTF_8);
        assertFalse(StartupCheck.shouldOfferMigration(studyDir), "只有计划文件时不提供迁移");
        Files.delete(AppPaths.planFile(studyDir));

        Files.writeString(AppPaths.legacyFile(studyDir), "# 旧档案\n", StandardCharsets.UTF_8);
        assertTrue(StartupCheck.shouldOfferMigration(studyDir), "只有旧档案时提供迁移");

        Files.writeString(AppPaths.planFile(studyDir), "# 计划\n", StandardCharsets.UTF_8);
        assertFalse(StartupCheck.shouldOfferMigration(studyDir), "两份都在时不提供迁移");
    }

    /**
     * Minor-3（2026-10-14 minor 清算批）：{@code ready} 与 {@code notices().isEmpty()} 必须等价。
     *
     * <p>{@code Result.ready()} 在生产代码里目前零调用方（{@code AppShell} 用
     * {@code notices().isEmpty()} 决定要不要显示提示条），评审把它点名成「护栏承重、但字段本身
     * 是死数据」。本批选择<b>补这条显式断言</b>而不是改 {@code AppShell}：在 {@code inspect} 的
     * 每条出口上，「{@code ready=false}」与「至少一条提示」本就同进同退；让 {@code AppShell}
     * 改读 {@code ready} 只会把这层等价从一个字段挪到另一个字段，行为上无法被任何测试区分，
     * 反而在等价被破坏时会静默丢掉提示。把不变式钉在这里，等价一旦被破坏（例如某支返回
     * {@code ready=true} 却带提示）立刻红。</p>
     */
    @Test
    void ready_and_noticesAreEmpty_areEquivalent_onEveryOutcome(@TempDir Path studyDir) throws IOException {
        List<StartupCheck.Result> outcomes = new ArrayList<>();
        outcomes.add(StartupCheck.inspect(studyDir));                                  // 两份都没有
        Files.writeString(AppPaths.legacyFile(studyDir), "# 旧档案\n", StandardCharsets.UTF_8);
        outcomes.add(StartupCheck.inspect(studyDir));                                  // 只有旧档案
        Files.writeString(AppPaths.planFile(studyDir), "# 计划\n", StandardCharsets.UTF_8);
        outcomes.add(StartupCheck.inspect(studyDir));                                  // 两份都在（正常）
        Files.delete(AppPaths.planFile(studyDir));
        Files.createDirectory(AppPaths.planFile(studyDir));
        outcomes.add(StartupCheck.inspect(studyDir));                                  // 计划位置是目录（读不动）
        outcomes.add(StartupCheck.inspect(studyDir, dir -> false));                    // 不可写
        outcomes.add(StartupCheck.inspect(studyDir.resolve("更深一层"), dir -> false)); // 还不存在 + 不可写

        assertEquals(6, outcomes.size(), "必须覆盖 inspect 的全部出口");
        for (StartupCheck.Result r : outcomes) {
            assertEquals(r.notices().isEmpty(), r.ready(),
                    "ready 必须与 notices().isEmpty() 等价：ready=" + r.ready() + " notices=" + r.notices());
        }
        // 两种取值都必须出现过，否则这条等价是空转
        assertTrue(outcomes.stream().anyMatch(StartupCheck.Result::ready),
                "至少要有一条 ready=true 的出口");
        assertTrue(outcomes.stream().anyMatch(r -> !r.ready()),
                "至少要有一条 ready=false 的出口");
    }
}
