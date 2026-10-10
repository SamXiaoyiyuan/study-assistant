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

    // ---- 以下两个是补充用例（brief 的 7 个用例逐字保留），补的是「重排式实现能蒙过去」的地方 ----

    @Test
    void appendWeek_newWeekReadsBackThroughTheSameParser() {
        // appendWeek 写出去的文本必须能被 parseWeeks 原样读回来：新周有完整四行、既有周一条不少。
        // 若哪天有人把模板或标题格式改了，这里会先亮——而不是等到界面显示时才看出来。
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

        // 既有的两周逐字未变（标题与项目行都从新文本里读出来，最接近「界面看到什么」）
        assertEquals("### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）", weeks.get(0).heading());
        assertEquals(List.of("- 本周计划：单词 20 分钟", "- 实际完成：单词 6 天"), weeks.get(0).bullets());
        assertEquals(List.of("- 实际完成：进行中"), weeks.get(1).bullets());
    }

    @Test
    void appendWeek_historyAndTrailingSectionsAreByteIdentical_aroundTheNewWeek() {
        // 这条是冲着「重建整篇再写回」那种实现去的：把新周与 §十 之间的一切（周记录章节结尾的
        // `---` 与空行、§十 整节、文末 HTML 注释）要求逐字节等于原文里对应的那一段。
        // 重建式实现会把空行/分隔线按自己的排版重写一遍，于是这里会失败。
        String out = WeekRecordAppender.appendWeek(MD, 4);

        // 期望文本刻意写死（不从 MD 里推导）：一旦有人把 MD 改了，这条不会跟着一起变，
        // 于是「新周该长什么样、历史该长什么样」始终有一个独立的标准答案。
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
                "新文本必须逐字等于「原文里 §九 结尾的 `---` 之前的全部历史 + 新周块 + 原文那一行 `---`"
                        + " + 原文 §十 及之后」；任何一处被重排（空行、分隔线、历史周内容）都会让这条失败");
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
