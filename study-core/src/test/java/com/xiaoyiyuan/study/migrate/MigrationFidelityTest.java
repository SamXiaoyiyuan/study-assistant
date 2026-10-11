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
    // P3 的真实判据：逐行精确相等 + 冻结的「消失清单」
    // =====================================================================

    /**
     * 迁移后从源文件里消失的行——<b>冻结清单</b>，按源文件顺序排列，重复的行重复列出
     * （所以 {@code ---} 出现 8 次）。
     *
     * <p><b>为什么需要它</b>：这一轮之前，P3 只做「源行是不是目标里的子串」和「某行在不在」
     * 两件事，<b>没有任何断言在算「少了什么」</b>——`absent ⊆ {---}` 从来没有被断言过，一整张表
     * 静默消失测试也照样全绿。这份清单把「允许消失的东西」一条不差地枚举出来：多一条（迁移丢
     * 内容）、少一条（映射改了去向却没人更新这里），等式两边都会红。
     *
     * <p><b>判据是 {@code strip()} 后的逐行精确相等，不是子串</b>。这正是本类原来那套判据的漏洞：
     * 子串口径下这 24 条里有 12 条会被自己的「新版本」满足——4 行底线是加了「目标」列的新行的子串，
     * 8 条 {@code ---} 被每个表格的 {@code |---|} 分隔行满足——于是这一桶测不出任何东西，
     * 而「12 条消失」恰好就是子串口径量到的数字。改用精确相等后，<b>每一条消失的行都是明确的、
     * 要交代去处的决定</b>。
     *
     * <p>清单里每一类都有对应的「内容落点」断言（见 {@link #everyFrozenAbsentLineIsAccountedForByLandingContent}）：
     * 这里的「消失」是<b>整行文本形态</b>的消失（改名、加了列、换了包装），不是内容丢失。
     */
    private static final List<String> FROZEN_ABSENT_LINES = List.of(
            // 旧文件标题被新标题取代
            "# 408 备考进度 · 示例同学",
            // 分隔线：目标以空行分节（每个 `## ` 标题前一个空行），8 条一律不搬
            "---",
            "## 一、我的档案",
            // §一 档案行：项目名换成目标列名，值逐字保留
            "| 年级 | 大二上 |",
            "| 大二在学 | 数据库、Java、线性代数、物理学、电路分析基础、马克思主义基本原理 —— **这 6 门是本学期第一优先级** |",
            "| 待确认 | 目标院校 / 学硕还是专硕（见第七节） |",
            "---",
            "## 二、优先级与每周底线",
            // 底线表：表头改名（底线 → 量）并加「目标」列；4 行数据加列后整行文本不再逐字相等
            "| 项 | 底线 | 怎么安排 | 状态 |",
            "| **单词** | 7 天里 **≥5 天**，每次 20 分钟 | 用「示例词表」；晨起或睡前，**时段自选** | 唯一保留的每日习惯 |",
            "| **数学** | **每周 2~4 小时**，≥2 次、每次 ≥20 分钟 | **哪天有空哪天做**，周末优先 | 从\"每天 40 分钟\"降级 |",
            "| **408** | **0** | 暂时完全停 | 本学期不排 |",
            "| **四级听力** | **0** | 四级 2027 年 6 月才考，不提前占用 | 2027 年 2 月再启动 |",
            "---",
            "## 三、阶段排期",
            "---",
            "## 四、现在明确不碰",
            "---",
            "## 五、本周（2026-10-05 – 10-11 ｜ 第 4 周）",
            "---",
            "## 六、周记录（每周追加，不覆盖）",
            "---",
            "## 七、待确认（不急，到点再说）",
            "---");

    /**
     * 源文件里所有消失的行，必须<b>恰好</b>是 {@link #FROZEN_ABSENT_LINES}。
     *
     * <p>这是本任务唯一一条会随「整块内容消失」变红的断言——P3 原来的子串判据不会。
     */
    @Test
    void absentLinesAreExactlyTheFrozenList(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = legacy();
        String target = new Migrator(dir).plan(writeFixture(dir, source)).targetText();

        List<String> absent = absentLines(source, target);

        assertEquals(FROZEN_ABSENT_LINES, absent, () -> "源文件里消失的行与冻结清单不一致。\n"
                + "实际消失 " + absent.size() + " 行：\n  " + String.join("\n  ", absent) + "\n"
                + "冻结清单 " + FROZEN_ABSENT_LINES.size() + " 行：\n  "
                + String.join("\n  ", FROZEN_ABSENT_LINES) + "\n"
                + "实际比清单多 = 迁移丢了内容（严重）；实际比清单少 = 某行的去向改了，"
                + "要一并更新冻结清单并说明理由。");
    }

    /**
     * {@code ---} 这一桶必须是<b>真的在查</b>：用逐行精确相等，而不是 {@code target.contains(...)}。
     *
     * <p>为什么值得单独一条：目标里每个表格都有 {@code |---|} 分隔行，任何子串形式的检查都会被它们
     * 满足——于是「目标里没有 {@code ---}」这条断言永远是绿的，哪怕 {@code ---} 一条都没删。
     * 这条测试同时钉住「源里确实有 8 条」与「目标里确实一条都没有」，中间那两种判据的差别写在断言里。
     */
    @Test
    void removedDividersAreGoneNotHiddenBehindTableSeparators(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = legacy();
        String target = new Migrator(dir).plan(writeFixture(dir, source)).targetText();

        List<String> sourceDividers = dividers(source);
        assertEquals(8, sourceDividers.size(),
                "脱敏样例里应当有 8 条 `---` 分隔线；数字写错的话这条断言本身就空了");

        // 现场证据：目标里到处是 `|---|`，子串判据 `target.contains("---")` 一定为真。
        assertTrue(target.contains("|---|"),
                "目标里的表格分隔行 `|---|` 正是子串判据的陷阱：它会满足 target.contains(\"---\")");

        List<String> targetDividers = dividers(target);
        assertTrue(targetDividers.isEmpty(),
                "目标里不得再有 `---` 分隔线，实际有 " + targetDividers.size() + " 条："
                        + String.join(" / ", targetDividers));
        // 删掉的 8 条必须条条记在冻结清单里（不然「删除」就是没人交代的删除）
        assertEquals(sourceDividers.size(), FROZEN_ABSENT_LINES.stream().filter("---"::equals).count(),
                "源里每一条 `---` 都要在冻结清单里有一条对应记录");
    }

    /**
     * 冻结清单里那些「整行形态消失」的内容，<b>逐条都要能指出它落在哪</b>。
     *
     * <p>这条测试是 {@link #absentLinesAreExactlyTheFrozenList} 的另一半：清单断言抓「新出现的
     * 消失」，这条抓「清单里那些已经消失的行，内容其实是不是真的还在」。少了它，一整张表
     * （例如 §三 的 阶段 A–E，正是清单里 {@code ## 三、阶段排期} 那一行的内容）可以整体不见了而
     * 清单本身毫无变化——上一轮的真实漏洞就在这里：表格来源的内容一条断言都没有。
     */
    @Test
    void everyFrozenAbsentLineIsAccountedForByLandingContent(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String target = new Migrator(dir).plan(writeFixture(dir, legacy())).targetText();

        // 旧标题 → 新标题
        assertTrue(target.startsWith("# 学习与生活计划\n"),
                "旧文件标题必须被新标题取代，目标第一行实际是：" + target.lines().findFirst().orElse(""));

        // 「| 年级 | 大二上 |」→ §一 改列名；「| 大二在学 | … |」→ §一「本学期课程」（值一字未改）
        assertLinesInSection(target, "## 一、我的现状",
                "| 年级 / 学期 | 大二上 |",
                "| 本学期课程 | 数据库、Java、线性代数、物理学、电路分析基础、马克思主义基本原理 —— **这 6 门是本学期第一优先级** |");

        // 「## 二、优先级与每周底线」的正文 → §四（顺序固定 / 砍单顺序 / 单词用法 / 不做的事）
        assertLinesInSection(target, "## 四、每周底线",
                "**顺序固定，不并行竞争：**",
                "1. **学校课程** —— 上课、作业、实验、期末。这是本学期唯一\"必须完成\"的事",
                "**时间不够时的砍单顺序（从上往下砍，砍到底也不影响课程）：**",
                "**单词用法**：新词上限 25；**复习优先**——待复习没清完，不加新词。队列断了 1~2 天不用补。",
                "**不做的事**：不给每天排时段表、不指定\"今晚必须做哪一步\"、不设每日打卡考核。**你哪天做、什么时候做，自己定。**");

        // 底线表：表头改名加列，4 行数据的每个单元格逐字都在
        assertLinesInSection(target, "## 四、每周底线",
                "| 目标 | 项 | 量 | 怎么安排 | 状态 |",
                "| 考研（408） | **单词** | 7 天里 **≥5 天**，每次 20 分钟 | 用「示例词表」；晨起或睡前，**时段自选** | 唯一保留的每日习惯 |",
                "| 考研（408） | **数学** | **每周 2~4 小时**，≥2 次、每次 ≥20 分钟 | **哪天有空哪天做**，周末优先 | 从\"每天 40 分钟\"降级 |",
                "| 考研（408） | **408** | **0** | 暂时完全停 | 本学期不排 |",
                "| 考研（408） | **四级听力** | **0** | 四级 2027 年 6 月才考，不提前占用 | 2027 年 2 月再启动 |");

        // 「## 三、阶段排期」整表（阶段 A–E 共 6 行）→ §八；§一 的考研字段也进 §八
        assertLinesInSection(target, "## 八、考研（暂缓）",
                "| 目标 | 未定校，倾向**专硕** |",
                "| 弱项自评 | C 弱 ｜ 数据结构 弱 ｜ 计网 一般 ｜ 高数·线代 弱 ｜ 英语 一般 |",
                "| **A** | 2026.9–2027.1<br>大二上 | **学校 6 门课** + 单词不断线 + 数学少量 | 学校课程全部及格以上；单词累计 ≥1000 |",
                "| **A+** | **2027.1–2027.2<br>寒假【已确认可投入】** | **第一个上量窗口**（短，1 个月） | 高数上册**完整过一遍**，基础题正确率 ≥70% |",
                "| **B** | 2027.2–2027.6<br>大二下 | 学校课程 + **四级过线** + 线代跟课 | **四级 ≥425**；数学维持低频 |",
                "| **C** | **2027.7–2027.8<br>暑假【主承重期】** | **数学基础一轮 + 数据结构第一轮** | 数学基础题正确率 ≥70%；能独立实现二叉树三种遍历 |",
                "| **D** | 2027.9–2028.8<br>大三全年 | 408 四科一轮 + 数学强化 | 四科各过一遍；分章真题正确率 ≥50% |",
                "| **E** | 2028.9–2028.12<br>大四上 | 二轮 + 整卷模考 + 政治 | 近 10 年整卷 180 分钟内完成，稳定 90+/150 |");

        // 「## 四、现在明确不碰」的考研部分 → §八（课程处理原则那段留在 §四）
        assertLinesInSection(target, "## 八、考研（暂缓）",
                "**\"不碰\"只针对考研，不针对课程。学校 6 门课照上、作业照交、期末照考。**",
                "- **408 全部**：数据结构、计组、OS、计网 —— 本学期不排任何时间",
                "- 数学：概率论、无穷级数、三重积分、曲线曲面积分",
                "- 四级：作文模板、听力 —— **2027 年 2 月**再启动",
                "- 考研政治：复习与刷题 —— 2028 年 9 月再说");
        assertLinesInSection(target, "## 四、每周底线",
                "| 马克思主义基本原理 | 跟课 + 期末卷面 ≥60 |");

        // 「## 五、本周」的 ① 与检查机制 → §七；③ 生活保障 → §五；② 考研轻量 → §八
        assertLinesInSection(target, "## 七、本周",
                "**① 学校课程（第一优先级）**",
                "- 本周课程任务：**待填**（各科作业 / 实验报告的截止时间）",
                "**周检查（只写 3 个数字，2 分钟）**：单词做了几天 ｜ 数学做了几次 ｜ 有没有被课程挤掉",
                "**修订周期**：大计划（阶段表）一月一看；周计划只在课程变化时改。");
        assertLinesInSection(target, "## 五、生活准则",
                "**③ 生活保障（硬约束，不用于补学习）**",
                "- 待你给出 1~3 条（例：运动 / 睡眠时间 / 周末半天）");
        assertLinesInSection(target, "## 八、考研（暂缓）",
                "**② 考研轻量（有量即可）**",
                "| 单词 | ≥5 天，每次 20 分钟 | 待回报 |",
                "| 数学 | ≥2 次，每次 ≥20 分钟 | 待回报 |");

        // 「## 六、周记录（每周追加，不覆盖）」→ §九（标题换成 App 的锚点标题，4 周原样）
        assertLinesInSection(target, "## 九、周记录（每周追加，不覆盖）",
                "### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）",
                "### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）",
                "### 2026-09-28 – 2026-10-04（第 3 周 ｜ 周一–周日）",
                "### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）");

        // 「| 待确认 | 目标院校 / 学硕还是专硕（见第七节） |」与「## 七、待确认（不急，到点再说）」
        // 的内容 → §十
        assertLinesInSection(target, "## 十、待确认",
                "> ✅ **初试年份已确认：2028 年 12 月**——**不再核对、不再询问**。窗口约 27 个月，当前低强度与之匹配。",
                "> ✅ **假期投入已确认：2027 寒假（约 1 个月）+ 2027 暑假（约 2 个月）均可投入**。",
                "1. **目标院校 / 学硕还是专硕**：决定数一还是数二。**2027 年 9 月前定下即可。**",
                "2. **两个假期的每日可投入时长**：待 2027 年 6 月前确认（用于把暑假 8 周排到具体章节）。");
        assertSectionContains(target, "## 十、待确认", "目标院校 / 学硕还是专硕（见第七节）");

        // 文末 3 行 HTML 注释仍在最后
        List<String> lines = strippedNonEmpty(target);
        assertEquals(3, lines.stream().filter(l -> l.startsWith("<!--")).count(),
                "文末 3 行 HTML 注释必须原样还在");
        assertTrue(lines.get(lines.size() - 1).startsWith("<!-- 编制原则（2026-09-22 起）"),
                "注释的最后一行必须是原来那一行，实际：" + lines.get(lines.size() - 1));
    }

    /** 源文件里所有 {@code ---} 分隔线（strip 后精确相等）。 */
    private static List<String> dividers(String md) {
        return strippedNonEmpty(md).stream().filter("---"::equals).toList();
    }

    /**
     * 源文件里消失的行：<b>所有</b>非空行都在被检查范围内（表格行、标题行、分隔线一个不落），
     * 判据是 {@code strip()} 后的逐行精确相等，按源文件顺序返回、保留重复。
     */
    private static List<String> absentLines(String source, String target) {
        java.util.Set<String> targetLines = new java.util.LinkedHashSet<>(strippedNonEmpty(target));
        List<String> absent = new ArrayList<>();
        for (String line : strippedNonEmpty(source)) {
            if (!targetLines.contains(line)) {
                absent.add(line);
            }
        }
        return absent;
    }

    /** 非空行（{@code strip()} 之后）。 */
    private static List<String> strippedNonEmpty(String md) {
        List<String> out = new ArrayList<>();
        for (String line : md.split("\n", -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                out.add(stripped);
            }
        }
        return out;
    }

    /** 取 {@code ## } 标题之后、下一个 {@code ## } 标题之前的正文（不含两边标题行）。 */
    private static List<String> sectionBody(String md, String heading) {
        List<String> lines = List.of(md.split("\n", -1));
        int start = lines.indexOf(heading);
        assertTrue(start >= 0, "目标文件里找不到章节标题：" + heading);
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                end = i;
                break;
            }
        }
        return lines.subList(start + 1, end);
    }

    /** 断言这些行<b>逐字</b>出现在指定章节的正文里（精确相等，不是子串）。 */
    private static void assertLinesInSection(String md, String heading, String... expected) {
        java.util.Set<String> body = new java.util.LinkedHashSet<>(strippedNonEmpty(
                String.join("\n", sectionBody(md, heading))));
        for (String line : expected) {
            assertTrue(body.contains(line),
                    "「" + heading + "」里少了这一行（它来自源文件的表格/列表，内容不许丢）：\n  " + line);
        }
    }

    /** 断言指定章节的正文<b>含</b>这段内容（用于「行形态变了、内容还在」的情形）。 */
    private static void assertSectionContains(String md, String heading, String snippet) {
        String body = String.join("\n", sectionBody(md, heading));
        assertTrue(body.contains(snippet),
                "「" + heading + "」里少了这段内容：" + snippet);
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
