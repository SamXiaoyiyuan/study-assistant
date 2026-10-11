package com.xiaoyiyuan.study.migrate;

import com.xiaoyiyuan.study.plan.Course;
import com.xiaoyiyuan.study.plan.Goal;
import com.xiaoyiyuan.study.plan.GoalStatus;
import com.xiaoyiyuan.study.plan.PlanDocument;
import com.xiaoyiyuan.study.plan.WeekRecordAppender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守 P3：迁移不丢字。
 *
 * <p>P3（spec §4.6 / 全局约束）= 「原文件里所有原样块的文本行，迁移后必须逐行存在于新文件」。
 * 位置可以变、所属章节标题可以变，<b>内容不能少一行</b>。这一条是本任务存在的理由：旧档案是
 * 用户手写的、混着 LaTeX 与半结构化文本的唯一副本，迁移写错就等于毁掉它。
 */
class MigrationFidelityTest {

    private static String legacy() throws IOException {
        try (InputStream in = MigrationFidelityTest.class
                .getResourceAsStream("/fixtures/legacy-408-progress.md")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 抽出所有「原样块」文本行：非空、且不是表格行、且不是标题行。 */
    private static List<String> preservedLines(String md) {
        List<String> out = new ArrayList<>();
        for (String line : md.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("|")) continue;
            if (t.startsWith("#")) continue;
            if (t.equals("---")) continue;
            out.add(t);
        }
        return out;
    }

    @Test
    void everyPreservedLineSurvives(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = legacy();
        MigrationPlan plan = new Migrator(dir).plan(writeFixture(dir, source));

        List<String> missing = new ArrayList<>();
        String target = plan.targetText();
        for (String line : preservedLines(source)) {
            if (!target.contains(line)) {
                missing.add(line);
            }
        }
        assertTrue(missing.isEmpty(),
                "P3 失败：有 " + missing.size() + " 行原文没进新文件：\n" + String.join("\n", missing));
    }

    @Test
    void weekRecordsAreCarriedOverVerbatim(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = legacy();
        MigrationPlan plan = new Migrator(dir).plan(writeFixture(dir, source));

        // 周记录里的 LaTeX 一个字符都不能变
        assertTrue(plan.targetText().contains("$1^\\infty$"), "LaTeX 必须原样保留");
        assertTrue(plan.targetText().contains("\\sum_{k=1}^{n}\\frac{n}{n^2+k}"), "复杂公式必须原样保留");
    }

    // =====================================================================
    // 以下为守 P3 之外还必须成立的性质（控制器裁定 + 跨任务格式要求）
    // =====================================================================

    /**
     * 这次迁移<b>没有动用兜底</b>：fixture 的每一行都由映射本身放到了该去的位置。
     *
     * <p>为什么值得单独断言：迁移里有一条「一行都不许丢」的兜底（把没归类的原文并入 §八）。
     * 兜底能保证 P3，却也会把「映射写漏了」这件事故意掩盖成绿色。这条断言把两者分开：
     * 兜底一动，就说明映射漏了东西——P3 仍然绿，但这条会红。
     */
    @Test
    void mappingCarriesEveryLineWithoutTheBackstop(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        MigrationPlan plan = new Migrator(dir).plan(writeFixture(dir, legacy()));
        for (String note : plan.sectionNotes()) {
            assertFalse(note.contains("兜底"),
                    "迁移用了兜底（说明有行没被映射归位，P3 是靠兜底补上的）：" + note);
        }
    }

    /**
     * 每个 {@code ## } 标题的前一行必须是空行；§九 的最后一周条目与 §十 之间也要有空行。
     *
     * <p>理由（Task 7 转交的格式要求、控制器裁定）：块边界<b>只由零字符空行</b>产生，
     * 少一个空行就会让「上一节末尾」与「下一节标题」落进同一个 {@code RawBlock}，破坏块粒度；
     * 而 App 的 {@code appendWeek} 在「周记录恰为文件末节」时不会补前导空行。
     * 由迁移产出保证格式，比让 appendWeek 猜上下文可靠。
     */
    @Test
    void everySectionHeadingIsPrecededByABlankLine(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String target = new Migrator(dir).plan(writeFixture(dir, legacy())).targetText();
        assertBlankLineBeforeEverySectionHeading(target);
        assertTrue(target.contains("\n\n## 十、待确认"),
                "§九 最后一周条目之后、`## 十` 之前必须有一个空行（控制器明确点名的情形）");
    }

    /** 断言「每个 {@code ## } 标题前一行是空行」；供本类与追加周记录后的复检共用。 */
    private static void assertBlankLineBeforeEverySectionHeading(String md) {
        List<String> lines = List.of(md.split("\n", -1));
        int headings = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("## ")) continue;
            headings++;
            assertTrue(i > 0, "第 " + (i + 1) + " 行是 `## ` 标题，它前面必须有空行："
                    + lines.get(i));
            assertEquals("", lines.get(i - 1),
                    "第 " + (i + 1) + " 行是 `## ` 标题，但它前一行不是空行（原文："
                            + lines.get(i - 1) + "）——块边界只由零字符空行产生");
        }
        assertEquals(10, headings, "目标文件必须恰好有 10 个二级标题（§一 … §十）");
    }

    /**
     * 迁移产物必须能被 App 的计划模型直接打开：五个建模章节各一张结构表、表头与规格逐字一致。
     *
     * <p>这是「迁移不是写出一篇好看的 Markdown 就完事」的检验：迁移产物是 App 之后每天要读写的
     * 文件，打不开就等于迁移失败。
     */
    @Test
    void migratedTarget_isLoadableByPlanDocument(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String target = new Migrator(dir).plan(writeFixture(dir, legacy())).targetText();
        PlanDocument doc = PlanDocument.parse(target);

        assertEquals(3, doc.goals().size(), "§二 目标清单必须初始化三行");
        assertEquals(List.of("本学期学校课程", "生活与身心", "考研（408）"),
                doc.goals().stream().map(Goal::name).toList());
        assertEquals(GoalStatus.ACTIVE, doc.goals().get(0).status());
        assertEquals(GoalStatus.ACTIVE, doc.goals().get(1).status());
        assertEquals(GoalStatus.DEFERRED, doc.goals().get(2).status(), "考研必须是「暂缓」");
        assertEquals("1", doc.goals().get(0).priority());
        assertEquals("2", doc.goals().get(1).priority());

        assertEquals(List.of("数据库", "Java", "线性代数", "物理学", "电路分析基础", "马克思主义基本原理"),
                doc.courses().stream().map(Course::name).toList(),
                "§三 课程表必须按 §一「大二在学」初始化 6 门课");
        assertTrue(doc.courses().stream().allMatch(c -> c.status().equals("待回报")));

        assertEquals(4, doc.baselines().size(), "§四 每周底线必须承接源 §二 的 4 行");
        assertTrue(doc.baselines().stream().allMatch(b -> b.goal().equals("考研（408）")),
                "源 §二 的底线条目都属考研语境，目标列标注为「考研（408）」");
        assertTrue(doc.phases().isEmpty(), "§六 阶段排期 迁移后是空表（原阶段 A–E 已移入 §八）");

        assertTrue(doc.profile().size() >= 4, "§一 我的现状至少 4 项");
        assertTrue(doc.profile().stream().anyMatch(p -> p.key().equals("年级 / 学期")));
    }

    /**
     * 迁移产物必须还能被 {@link WeekRecordAppender} 追加新周——也就是 §九 的标题与 App 的锚点常量
     * 逐字一致；追加之后「每个 {@code ## } 标题前是空行」仍然成立。
     */
    @Test
    void migratedTarget_stillAcceptsAnAppendedWeek(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String target = new Migrator(dir).plan(writeFixture(dir, legacy())).targetText();

        List<String> headings = WeekRecordAppender.parseWeeks(target).stream()
                .map(w -> w.heading()).toList();
        assertEquals(4, headings.size(), "迁移后 §九 里必须仍是 4 周历史");
        assertEquals("### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）", headings.get(0));

        String appended = WeekRecordAppender.appendWeek(target, 5);
        assertTrue(appended.contains("（第 5 周 ｜ 周一–周日）"), "新周必须追加成功（§九 是唯一锚点）");
        assertTrue(appended.contains("\n\n## 十、待确认"), "追加后 §十 之前仍须有空行");
        assertBlankLineBeforeEverySectionHeading(appended);
    }

    /**
     * 「§一 有『大二在学』行就以其内容为准」：课程表不能被硬编码的 6 门课顶掉。
     */
    @Test
    void courseListFollowsProfileWhenPresent(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = """
                # 408 备考进度 · 测试

                ## 一、我的档案

                | 项目 | 内容 |
                |---|---|
                | 年级 | 大二上 |
                | 大二在学 | 离散数学、计算机组成原理 —— **这两门是重点** |

                """;
        String target = new Migrator(dir).plan(writeFixture(dir, source)).targetText();
        assertTrue(target.contains("| 离散数学 |  |  | 待回报 |"), "课程表必须用 §一 的内容：\n" + target);
        assertTrue(target.contains("| 计算机组成原理 |  |  | 待回报 |"),
                "带后缀的课程名必须被截干净（不能把 ` —— **这两门是重点**` 带进单元格）");
        assertFalse(target.contains("| 数据库 |"), "§一 给了课程清单时不得再写死默认 6 门课");
    }

    /**
     * 形状古怪的旧档案（标题带后缀、多出没映射的章节、注释不在文末）也一行都不许丢。
     *
     * <p>这是兜底机制存在的理由：旧档案用户改过，形状无法穷举。
     */
    @Test
    void oddlyShapedLegacy_stillLosesNoPreservedLine(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = legacy()
                .replace("## 三、阶段排期", "## 三、阶段排期（旧）")
                .replace("**修订周期**：", "**修订周期（补充）**：")
                + "\n## 八、临时笔记\n\n- 这一节没有对应的目标位置\n";
        MigrationPlan plan = new Migrator(dir).plan(writeFixture(dir, source));

        List<String> missing = new ArrayList<>();
        for (String line : preservedLines(source)) {
            if (!plan.targetText().contains(line)) {
                missing.add(line);
            }
        }
        assertTrue(missing.isEmpty(),
                "P3 失败（形状古怪的输入）：有 " + missing.size() + " 行原文没进新文件：\n"
                        + String.join("\n", missing));
        assertTrue(plan.sectionNotes().stream().anyMatch(n -> n.contains("临时笔记")),
                "没映射的章节必须在 sectionNotes 里记一条：" + plan.sectionNotes());
    }

    /** 源 §二 底线表里列数不符的行不能塞进新表（会让整张表降级），但也不许丢。 */
    @Test
    void malformedBaselineRow_isNotLost(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = """
                # 408 备考进度 · 测试

                ## 二、优先级与每周底线

                | 项 | 底线 | 怎么安排 | 状态 |
                |---|---|---|---|
                | 单词 | 7 天里 ≥5 天 | 晨起 | 待回报 |
                | 数学 | 每周 2~4 小时 | 哪天有空 | 降级 | 多出来的一格 |

                """;
        MigrationPlan plan = new Migrator(dir).plan(writeFixture(dir, source));

        assertTrue(plan.targetText().contains("| 考研（408） | 单词 | 7 天里 ≥5 天 | 晨起 | 待回报 |"),
                "合规的那一行必须进新表（加「目标」列）：\n" + plan.targetText());
        assertTrue(plan.targetText().contains("| 数学 | 每周 2~4 小时 | 哪天有空 | 降级 | 多出来的一格 |"),
                "列数不符的行不许丢，也不许塞进新表（会让整张表降级），必须原样保留：\n" + plan.targetText());
        assertTrue(plan.sectionNotes().stream().anyMatch(n -> n.contains("列数")),
                "列数不符必须在 sectionNotes 里记一条：" + plan.sectionNotes());
    }

    private static Path writeFixture(Path dir, String content) throws IOException {
        Path p = dir.resolve("408-进度.md");
        java.nio.file.Files.writeString(p, content);
        return p;
    }
}
