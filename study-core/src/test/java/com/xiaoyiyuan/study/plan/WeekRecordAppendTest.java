package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeekRecordAppendTest {

    private static final String MD = """
            # 学习与生活计划 · 测试

            ## 九、周记录（每周追加，不覆盖）

            ### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）

            - 本周计划：单词 20 分钟
            - 实际完成：单词 6 天

            ### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）

            - 实际完成：进行中

            ---

            ## 十、待确认

            1. 目标院校

            <!-- 周界固定为周一–周日 -->
            """;

    @Test
    void parseWeeks_readsExistingWeeks() {
        List<WeekRecord> weeks = WeekRecordAppender.parseWeeks(MD);
        assertEquals(2, weeks.size());
        assertEquals(1, weeks.get(0).weekNumber());
        assertEquals(java.time.LocalDate.of(2026, 9, 14), weeks.get(0).start());
        assertEquals(java.time.LocalDate.of(2026, 9, 20), weeks.get(0).end());
        assertTrue(weeks.get(1).bullets().contains("- 实际完成：进行中"));
    }

    @Test
    void appendWeek_addsHeadingAndTemplateBullets() {
        String out = WeekRecordAppender.appendWeek(MD, 4);

        assertTrue(out.contains("### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）"),
                "标题必须与档案格式逐字一致");
        assertTrue(out.contains("- 本周计划："));
        assertTrue(out.contains("- 实际完成："));
        assertTrue(out.contains("- 卡住的点："));
        assertTrue(out.contains("- 下周行动："));
        assertTrue(WeekRecordAppender.hasWeek(out, 4));
    }

    @Test
    void appendWeek_neverTouchesExistingWeeks_orLaterSections() {
        String out = WeekRecordAppender.appendWeek(MD, 4);

        assertTrue(out.contains("- 实际完成：单词 6 天"), "第 1 周必须逐字保留");
        assertTrue(out.contains("### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）"), "第 2 周必须在");
        assertEquals(MD.indexOf("## 十、待确认") < out.indexOf("## 十、待确认"), true);
    }

    @Test
    void appendWeek_insertsBeforeNextSection_notAtEndOfFile() {
        String out = WeekRecordAppender.appendWeek(MD, 4);

        int newWeek = out.indexOf("第 4 周");
        int section10 = out.indexOf("## 十、待确认");
        int htmlComment = out.indexOf("<!-- 周界固定为周一–周日 -->");

        assertTrue(newWeek > 0, "新周必须被插入");
        assertTrue(newWeek < section10, "新周必须插在「## 十」之前");
        assertTrue(section10 < htmlComment, "文末 HTML 注释必须仍在最后");
    }

    @Test
    void appendWeek_leavesEverythingBeforeItByteIdentical() {
        String out = WeekRecordAppender.appendWeek(MD, 4);
        String prefixOfOriginal = MD.substring(0, MD.indexOf("## 十、待确认"));
        assertEquals(prefixOfOriginal, out.substring(0, out.indexOf("### 2026-10-05")),
                "插入点之前的全部内容必须字节相同");
    }

    @Test
    void appendWeek_twiceForSameWeek_isRejected() {
        String once = WeekRecordAppender.appendWeek(MD, 4);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> WeekRecordAppender.appendWeek(once, 4));
        assertTrue(ex.getMessage().contains("4"), "异常必须说明是第几周，便于界面改为「编辑既有」");
    }

    @Test
    void appendWeek_whenNoNextSection_appendsAtEnd() {
        String only = "# 计划\n\n## 九、周记录（每周追加，不覆盖）\n\n";
        String out = WeekRecordAppender.appendWeek(only, 3);
        assertTrue(out.contains("第 3 周"));
        assertTrue(out.endsWith("\n"), "结尾应有换行");
    }

    // ---- 以下 5 个是补充用例（brief 的 7 个用例逐字保留），补的是「重排式实现 / 锚点放宽能蒙过去」的地方 ----

    @Test
    void appendWeek_newWeekReadsBackThroughTheSameParser() {
        // appendWeek 写出去的文本必须能被 parseWeeks 原样读回来：新周有完整四行、既有周一条不少。
        String out = WeekRecordAppender.appendWeek(MD, 4);
        List<WeekRecord> weeks = WeekRecordAppender.parseWeeks(out);

        assertEquals(3, weeks.size(), "第 4 周必须能作为一条独立记录被读出来");
        WeekRecord week4 = weeks.get(2);
        assertEquals(4, week4.weekNumber());
        assertEquals(java.time.LocalDate.of(2026, 10, 5), week4.start());
        assertEquals(java.time.LocalDate.of(2026, 10, 11), week4.end());
        assertEquals("### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）", week4.heading());
        assertEquals(List.of("- 本周计划：", "- 实际完成：", "- 卡住的点：", "- 下周行动："),
                week4.bullets(), "四行模板必须逐字、按序落在这一周下面");

        assertEquals("### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）", weeks.get(0).heading());
        assertEquals(List.of("- 本周计划：单词 20 分钟", "- 实际完成：单词 6 天"), weeks.get(0).bullets());
        assertEquals(List.of("- 实际完成：进行中"), weeks.get(1).bullets());
    }

    @Test
    void appendWeek_historyAndTrailingSectionsAreByteIdentical_aroundTheNewWeek() {
        // 期望文本刻意写死（不从 MD 推导）：MD 改了也不会跟着一起变，标准答案是独立的。
        String out = WeekRecordAppender.appendWeek(MD, 4);

        String expected = """
                # 学习与生活计划 · 测试

                ## 九、周记录（每周追加，不覆盖）

                ### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）

                - 本周计划：单词 20 分钟
                - 实际完成：单词 6 天

                ### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）

                - 实际完成：进行中

                ---

                ### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）

                - 本周计划：
                - 实际完成：
                - 卡住的点：
                - 下周行动：

                ## 十、待确认

                1. 目标院校

                <!-- 周界固定为周一–周日 -->
                """;

        assertEquals(expected, out,
                "新文本必须逐字等于「原文里 §九 结尾的 `---` 之前的全部历史 + 新周块（末尾带一个空行）"
                        + " + 原文那一行 `---` + 原文 §十 及之后」；任何一处被重排都会让这条失败");
    }

    @Test
    void appendWeek_newWeekIsSeparatedFromFollowingSectionByABlankLine() {
        // 块边界只由零字符空行产生（全局约束），所以「新周最后一行」与「下一个 `## ` 标题」之间
        // 必须有一个空行；否则两者落进同一个块，按块读取的人会把它们看成一回事。
        // 两种插入点都要成立：有下一个 `## `（插到它之前）与没有（插到文档末尾）。
        String withNextSection = WeekRecordAppender.appendWeek(MD, 4);
        assertTrue(withNextSection.contains("- 下周行动：\n\n## 十、待确认"),
                "新周与 §十 之间必须有一个空行");
        assertTrue(hasBlankLineBeforeEverySectionHeading(withNextSection),
                "结果里每个 `## ` 标题之前都必须是一个空行");

        String atEnd = WeekRecordAppender.appendWeek("# 计划\n\n## 九、周记录（每周追加，不覆盖）\n\n", 3);
        assertTrue(atEnd.endsWith("- 下周行动：\n\n"),
                "插到文档末尾时，新周块自身也以空行收尾");
        assertTrue(atEnd.endsWith("\n"), "且结果仍以换行结尾");
    }

    @Test
    void appendWeek_archiveShapedHistoryWithLatexIsUntouched() {
        // 用户档案里的周记录长这样：LaTeX 行内公式、嵌套子条目、加粗、比较符、空行。任何「重建
        // 再写回」的实现都可能在这些地方动手（公式里的 `_`/`^` 被转义、嵌套列表被拍平、空行被
        // 规范化），而这条测试不关心实现怎么写——它只要求插入点之前的每一个字节都原样躺在结果里。
        String archive = """
                # 学习与生活计划 · 测试

                ## 九、周记录（每周追加，不覆盖）

                ### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）

                - 实际完成（进行中 · 已完成 D1、D2）：
                  - **9/21 周一 D1 ✅**：$\\sum_{k=1}^{n}\\frac{n}{n^2+k} \\to 1$
                  - **9/22 周二 D2 ✅**：$1-\\cos x\\sim\\frac{x^2}{2}$，$a_n<2$
                - 卡住的点：单调有界准则自评「搞不明白」——判定为**当成夹逼使用**
                - 下周行动：待填（周日晚一句话复盘即可）

                ---

                ## 十、待确认

                <!-- 周界固定为周一–周日 -->
                """;

        String out = WeekRecordAppender.appendWeek(archive, 3);

        // 这份样例（和真实档案一样）在最后一周与 §十 之间才有 `---`，所以插入点紧贴 §十：
        // 新周落在 `---` 之前，`---` 与 §十 之间的相对关系一成不变。
        String prefix = archive.substring(0, archive.indexOf("---\n\n## 十、待确认"));
        assertTrue(out.startsWith(prefix),
                "插入点之前的历史（含 LaTeX、嵌套子条目）必须逐字节不变；"
                        + "prefix.len=" + prefix.length() + " out.len=" + out.length()
                        + " firstDiff=" + firstDiff(out, prefix));

        assertTrue(out.contains("$\\sum_{k=1}^{n}\\frac{n}{n^2+k} \\to 1$"), "公式必须一字不动");
        assertTrue(out.contains("  - **9/21 周一 D1 ✅**"), "缩进的子条目必须一字不动");
        assertTrue(out.contains("$1-\\cos x\\sim\\frac{x^2}{2}$，$a_n<2$"), "第二个公式也必须一字不动");
        assertTrue(out.contains("### 2026-09-28 – 2026-10-04（第 3 周 ｜ 周一–周日）"));
    }

    @Test
    void appendWeek_weeklySectionIsAnchoredBySectionNineOnly() {
        // 锚点是钉死的：只有 `## 九、周记录（每周追加，不覆盖）` 才是周记录章节。周记录在 §六 的
        // 文档（迁移前真实档案的形状）必须被拒绝——不许「顺手也认一下 §六」：那样插入点就不再是
        // 从一个已知章节里挑出来的，而且 Task 6 的 PlanDocument 也是「序号 + 标题名逐字匹配」，
        // 只在这里放宽会让两处对「哪一节是周记录」有两种说法。
        String sectionSix = """
                # 学习与生活计划 · 测试

                ## 六、周记录（每周追加，不覆盖）

                ### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）

                - 实际完成：进行中

                ---

                ## 七、待确认

                <!-- 周界固定为周一–周日 -->
                """;
        String before = sectionSix;

        assertThrows(IllegalStateException.class, () -> WeekRecordAppender.appendWeek(sectionSix, 3),
                "§六 不是周记录章节，必须拒绝而不是插进去");
        assertEquals(before, sectionSix, "被拒绝的调用不能改动入参");
        assertFalse(WeekRecordAppender.hasWeek(sectionSix, 3), "拒绝之后文档里仍然没有第 3 周");
    }

    @Test
    void hasWeek_onlyMatchesHeadings_mentionsOfAWeekNumberDoNotCount() {
        // 正文里「第 4 周」这种提法不能被当成标题，否则 hasWeek 会为真、appendWeek 会拒绝一次
        // 其实合法的追加（最难查的一类错）。档案里真实存在这种句子：
        // 「卡在极限的概念、性质及存在准则……第 2 周第 1 晚（9/21）补上」。
        String mentionsOnly = """
                # 学习与生活计划 · 测试

                ## 九、周记录（每周追加，不覆盖）

                ### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）

                - 卡住的点：存在准则例题还没动手做过，第 4 周第 1 晚（10/5）补上
                - 下周行动：第 4 周再说

                ---

                ## 十、待确认
                """;

        assertFalse(WeekRecordAppender.hasWeek(mentionsOnly, 4),
                "只是在正文里提到「第 4 周」不算已有记录");

        String out = WeekRecordAppender.appendWeek(mentionsOnly, 4);
        assertTrue(out.contains("### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）"),
                "正文提到过也必须照样能追加");
        assertEquals(List.of("- 卡住的点：存在准则例题还没动手做过，第 4 周第 1 晚（10/5）补上",
                        "- 下周行动：第 4 周再说"),
                WeekRecordAppender.parseWeeks(out).get(0).bullets(),
                "提到周号的正文行必须原样保留，且不被当成新的周记录");
    }

    /** 每个二级标题（{@code ## }）之前那一行必须是空行——块边界只由空行产生。 */
    private static boolean hasBlankLineBeforeEverySectionHeading(String markdown) {
        List<String> lines = linesOf(markdown);
        int checked = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("## ")) {
                checked++;
                assertTrue(lines.get(i - 1).isEmpty(),
                        "第 " + i + " 行是二级标题，但它前面一行不是空行：["
                                + lines.get(i - 1).strip() + "]");
            }
        }
        return checked > 0;
    }

    /** 按 {@code '\n'} 切行并去掉行尾符；只用于测试里的结构断言。 */
    private static List<String> linesOf(String text) {
        return List.of(text.split("\n", -1));
    }

    private static int firstDiff(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        for (int i = 0; i < limit; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return i;
            }
        }
        return a.length() == b.length() ? -1 : limit;
    }
}
