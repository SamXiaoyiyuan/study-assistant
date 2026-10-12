package com.xiaoyiyuan.study.wellbeing;

import com.sun.net.httpserver.HttpServer;
import com.xiaoyiyuan.study.ai.AiClient;
import com.xiaoyiyuan.study.ai.AiConfig;
import com.xiaoyiyuan.study.ai.ConfirmingAiSession;
import com.xiaoyiyuan.study.ai.PendingRequest;
import com.xiaoyiyuan.study.ai.PromptRole;
import com.xiaoyiyuan.study.prompt.PromptLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 情绪 AI 分析（spec §6.4 / §6.5，计划 2 Task 9）。
 *
 * <p><b>本类钉住七条「错误实现下会红」的性质：</b>
 * <ol>
 *   <li><b>时间窗选取正确</b>：含两端、跨月跨年、窗口内 0 条 / 1 条 / 多条；窗口内的条目
 *       <b>一条不少</b>，窗口外的<b>一条不进</b>（用具体条数与日期断言）。</li>
 *   <li><b>提示词真的进了 {@code messages[0]}</b>。这条是本任务唯一的守卫：Task 7 的契约只保证
 *       「预览 == 实发」，它管不了「提示词有没有被放进去」——不显式断言，一个只发记录的实现
 *       可以全绿通过。</li>
 *   <li><b>预览 == 待发送内容</b>：{@code PendingRequest.promptText()} 必须等于
 *       {@code PendingRequest.joinContents(messages)}，也要等于确认门自己组装、真正发上线的那一份。</li>
 *   <li><b>不落盘（覆盖面写在这里，口径与断言名一致）</b>：{@code build} / {@code Result.parse} /
 *       {@code renderEntries} 跑完之后，以下四处不得出现<b>新条目</b>、也不得有<b>已存在条目被改动</b>
 *       （快照口径 = 相对路径 → 大小 + mtime，不再只是「相对路径集合」）：
 *       ① 进程工作目录（<b>递归</b>，含子目录）② {@code java.io.tmpdir}（顶层）
 *       ③ 用户主目录（顶层；递归会慢且易 flaky）④ 模块 {@code target}（顶层）。
 *       另加一条<b>顺序无关</b>的子 JVM 断言：在全新的空工作目录里跑同一段分析，那里必须仍然是空的。
 *       分析类型的公开面里没有任何 {@code Path}，{@code wellbeing} 包内也没有任何写文件 API 引用
 *       （含 {@code Files.write}）。</li>
 *   <li><b>禁止诊断性标签与 8 个禁用词</b>：产出的一切用户可见文本都过一遍词表断言。</li>
 *   <li><b>危机命中不阻断</b>：记录里含危机关键词时照常产出分析请求（不抛异常、不锁定、不替换），
 *       并保留一份含 120/110、不含禁用词的中性资源提示。</li>
 *   <li><b>解析层不洗白模型输出</b>：模型若违规写出诊断性词汇，{@code parse} 如实呈现，
 *       由界面标注——静默改写会让用户看不到模型真的说了什么。</li>
 * </ol>
 */
class EmotionAnalysisTest {

    /**
     * 内置《生活与心理顾问》提示词的<b>首句</b>（逐字）。
     *
     * <p>它是本任务「提示词真的被放进请求」那条断言的锚点。Task 7 的契约只保证「预览 == 实发」，
     * 管不了「提示词有没有被放进去」——没有这条断言，一个只把记录拼进 user 消息、
     * 丢掉 system 提示词的实现可以全绿。
     */
    private static final String PROMPT_FIRST_SENTENCE = "# 生活与心理顾问（内置默认提示词）";

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    /**
     * 脱敏样例（结构性 fixture，<b>不是</b>真实档案）：跨度 2025-12-31 → 2026-10-20，
     * 覆盖跨年、跨月、窗口两端、窗口外。
     *
     * <p>三条备注是「窗口外一条不进」的探针：窗口取 2026-10-04 ~ 2026-10-10，于是
     * {@code 十月二十日窗口外备注} / {@code 十月三日窗口外备注} / {@code 去年跨年窗口外备注}
     * 一个都不许出现在待发送文本里。
     */
    private static final String LOG_TEXT = """
            # 生活与情绪记录

            > 本文件由「学习助手」App 维护，只存放在本机。

            ---

            ## 2026-10-20（周二）

            - 情绪：兴奋 ｜ 强度：5
            - 备注：十月二十日窗口外备注

            ## 2026-10-10（周六）

            - 情绪：平静 ｜ 强度：3
            - 睡眠：7 小时
            - 备注：窗口最后一天备注

            ## 2026-10-04（周日）

            - 情绪：疲惫 ｜ 强度：4
            - 生活事件：连着两天赶作业
            - 备注：窗口第一天备注

            ## 2026-10-03（周六）

            - 情绪：放松
            - 备注：十月三日窗口外备注

            ## 2025-12-31（周三）

            - 情绪：低落 ｜ 强度：2
            - 备注：去年跨年窗口外备注
            """;

    /** 含危机关键词的脱敏样例（fixture，不是真实档案）。 */
    private static final String CRISIS_LOG_TEXT = """
            # 生活与情绪记录

            ---

            ## 2026-10-08（周四）

            - 情绪：低落 ｜ 强度：5
            - 备注：最近总觉得撑不下去了

            ## 2026-10-06（周二）

            - 情绪：疲惫 ｜ 强度：4
            - 备注：睡不好，作业也拖着
            """;

    private static final AnalysisWindow OCT_4_TO_10 =
            new AnalysisWindow(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10));

    /** 8 个界面禁用词（global-constraints「领域规则」）。 */
    private static final List<String> DISABLED_WORDS = List.of(
            "未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持");

    /** 禁止的诊断性标签（spec §6.5）。 */
    private static final List<String> DIAGNOSTIC_LABELS = List.of(
            "抑郁", "焦虑症", "中度", "心理健康得分", "评估结果");

    private static EmotionLog sampleLog() {
        return EmotionLog.parse(LOG_TEXT);
    }

    private static AnalysisWindow window(LocalDate from, LocalDate to) {
        return new AnalysisWindow(from, to);
    }

    private static EmotionEntry entry(LocalDate date, String mood, String note) {
        return new EmotionEntry(date, mood, null, null, null, null, note, List.of());
    }

    // =====================================================================
    // 1. 时间窗：构造与 lastDays
    // =====================================================================

    @Test
    void lastDays7_isInclusiveOfBothEnds() {
        assertEquals(OCT_4_TO_10, AnalysisWindow.lastDays(TODAY, 7),
                "近 7 天必须是 [today-6, today]（含两端共 7 天）");
        assertEquals(LocalDate.of(2026, 10, 4), AnalysisWindow.lastDays(TODAY, 7).from());
        assertEquals(LocalDate.of(2026, 10, 10), AnalysisWindow.lastDays(TODAY, 7).to());
    }

    @Test
    void lastDays_crossesMonthAndYearBoundaries() {
        assertEquals(window(LocalDate.of(2025, 12, 26), LocalDate.of(2026, 1, 1)),
                AnalysisWindow.lastDays(LocalDate.of(2026, 1, 1), 7),
                "跨年的近 7 天必须落在上一年；from 不得被截到 1 月 1 日");
        assertEquals(window(LocalDate.of(2026, 2, 23), LocalDate.of(2026, 3, 1)),
                AnalysisWindow.lastDays(LocalDate.of(2026, 3, 1), 7),
                "跨月的近 7 天必须落在上一个月");
    }

    @Test
    void lastDays30_isTheThirtyDayWindowEndingToday() {
        assertEquals(window(LocalDate.of(2026, 9, 11), TODAY), AnalysisWindow.lastDays(TODAY, 30));
    }

    @Test
    void lastDays1_isJustToday() {
        assertEquals(window(TODAY, TODAY), AnalysisWindow.lastDays(TODAY, 1));
    }

    @Test
    void lastDays_nonPositive_rejectsForTheDayCountReason_andSaysHowManyDaysItGot() {
        // 评审 V4：老版本只钉「抛 IllegalArgumentException」，于是把 days<1 放宽成 days<0 仍然全绿——
        // 那时抛的是「起点晚于终点」（窗口回绕成 [明天, 今天]），理由与 javadoc 承诺的完全不同。
        // 这里同时钉住「类型」与「理由」。
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> AnalysisWindow.lastDays(TODAY, 0),
                "0 天不是一个时间窗；静默返回空窗会让用户以为「这几天没记录」");
        assertTrue(zero.getMessage().contains("天数"),
                "拒绝理由必须是「天数」不够，而不是「起点晚于终点」（后者会把用户引到错误的方向）；"
                        + "实际：" + zero.getMessage());
        assertTrue(zero.getMessage().contains("0"),
                "消息里要带出实际收到的天数 0；实际：" + zero.getMessage());
        assertFalse(zero.getMessage().contains("晚于"),
                "0 天的窗口内部会回绕成 [明天, 今天]，但绝不能拿「起始日不能晚于结束日」当理由；实际："
                        + zero.getMessage());

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> AnalysisWindow.lastDays(TODAY, -7));
        assertTrue(negative.getMessage().contains("天数"),
                "-7 天的拒绝理由同样必须是「天数」；实际：" + negative.getMessage());
        assertTrue(negative.getMessage().contains("-7"),
                "消息里要带出实际收到的天数 -7；实际：" + negative.getMessage());
        assertFalse(negative.getMessage().contains("晚于"),
                "-7 天同样不许拿「起始日不能晚于结束日」当理由；实际：" + negative.getMessage());
    }

    @Test
    void window_withFromAfterTo_throwsInsteadOfBeingAnEmptyWindow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> window(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 4)));
        assertTrue(e.getMessage().contains("2026-10-10") && e.getMessage().contains("2026-10-04"),
                "拒绝消息要带出两个端点，界面才能指出用户填错了哪一头；实际：" + e.getMessage());
    }

    @Test
    void window_ofASingleDay_holdsExactlyThatDay() {
        assertEquals(1, window(TODAY, TODAY).select(sampleLog()).size());
    }

    // =====================================================================
    // 2. 时间窗选取：一条不少、一条不进
    // =====================================================================

    @Test
    void select_filtersByDateRange_andKeepsNewestFirst() {
        List<EmotionEntry> selected = OCT_4_TO_10.select(sampleLog());

        assertEquals(List.of(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 4)),
                selected.stream().map(EmotionEntry::date).toList(),
                "窗口 2026-10-04 ~ 2026-10-10 内正好 2 条，且保持文件顺序（最新在前）");
        assertEquals(List.of("平静", "疲惫"), selected.stream().map(EmotionEntry::mood).toList());
    }

    @Test
    void select_includesBothBoundaryDays() {
        assertEquals(List.of(LocalDate.of(2026, 10, 10)),
                window(TODAY, TODAY).select(sampleLog()).stream().map(EmotionEntry::date).toList(),
                "上端点当天必须进入窗口（窗口以今天结尾时今天必须算在里面）");
        assertEquals(List.of(LocalDate.of(2026, 10, 4)),
                window(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4))
                        .select(sampleLog()).stream().map(EmotionEntry::date).toList(),
                "下端点当天必须进入窗口");
    }

    @Test
    void select_excludesTheDaysJustOutsideEitherEnd() {
        AnalysisWindow inner = window(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9));
        assertEquals(List.of(), inner.select(sampleLog()),
                "10-05 ~ 10-09 这个窗口里一条记录都没有（10-04 与 10-10 都恰好在窗口外）");
    }

    @Test
    void select_windowWithNoEntries_isEmpty_notAnError() {
        assertEquals(List.of(),
                window(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 7)).select(sampleLog()));
    }

    @Test
    void select_windowWithExactlyOneEntry_returnsThatOne() {
        assertEquals(1, window(TODAY, TODAY).select(sampleLog()).size());
    }

    @Test
    void select_wholeFileSpan_returnsEveryEntry() {
        assertEquals(List.of(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 10),
                        LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 3), LocalDate.of(2025, 12, 31)),
                window(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31)).select(sampleLog())
                        .stream().map(EmotionEntry::date).toList(),
                "横跨全文件的窗口必须把 5 条全部选中（跨年那条也算）");
    }

    @Test
    void select_crossYearWindow_picksTheDecemberEntry() {
        assertEquals(List.of(LocalDate.of(2025, 12, 31)),
                window(LocalDate.of(2025, 12, 26), LocalDate.of(2026, 1, 1)).select(sampleLog())
                        .stream().map(EmotionEntry::date).toList(),
                "跨年窗口必须选出 2025-12-31 那一条");
    }

    @Test
    void select_isAnImmutableSnapshot_unaffectedByLaterChangesToTheLog() {
        EmotionLog log = sampleLog();
        List<EmotionEntry> selected = OCT_4_TO_10.select(log);
        log.addEntry(entry(LocalDate.of(2026, 10, 6), "焦虑", "后加的"));

        assertEquals(2, selected.size(), "选取结果是快照：之后往 log 里加条目不该改变它");
        assertThrows(UnsupportedOperationException.class,
                () -> selected.add(entry(LocalDate.of(2026, 10, 7), "平静", null)));
    }

    // =====================================================================
    // 3. 渲染：原文形态，不是摘要
    // =====================================================================

    @Test
    void renderEntries_usesTheOriginalText_notASummary() {
        String rendered = EmotionAnalysis.renderEntries(OCT_4_TO_10.select(sampleLog()));

        assertTrue(rendered.contains("窗口最后一天备注"),
                "渲染必须逐字含 10-10 的备注；实际：\n" + rendered);
        assertTrue(rendered.contains("窗口第一天备注"),
                "渲染必须逐字含 10-04 的备注；实际：\n" + rendered);
        assertTrue(rendered.contains(EmotionEntry.headingLabel(LocalDate.of(2026, 10, 10))),
                "渲染必须含 10-10 的日期标题（原文形态）；实际：\n" + rendered);
        assertTrue(rendered.contains("- 情绪：平静 ｜ 强度：3"),
                "渲染必须逐字含情绪与强度那一行（含全角竖线 U+FF5C）；实际：\n" + rendered);
        assertFalse(rendered.contains("…"), "渲染不得截断成摘要");
    }

    @Test
    void renderEntries_includesUnknownLinesVerbatim() {
        EmotionEntry withUnknown = new EmotionEntry(LocalDate.of(2026, 10, 6), "平静", 3,
                "7 小时", "散步 30 分钟", "交了作业", "备注原文",
                List.of("- 天气：阴", "- 咖啡：两杯"));
        String rendered = EmotionAnalysis.renderEntries(List.of(withUnknown));

        assertTrue(rendered.contains("- 天气：阴"),
                "未知行属于用户写下的数据，必须逐字进入待发送文本；实际：\n" + rendered);
        assertTrue(rendered.contains("- 咖啡：两杯"), "实际：\n" + rendered);
        assertTrue(rendered.contains("备注原文"), "实际：\n" + rendered);
    }

    @Test
    void renderEntries_keepsNewestFirstOrder() {
        String rendered = EmotionAnalysis.renderEntries(OCT_4_TO_10.select(sampleLog()));

        int tenth = rendered.indexOf("2026-10-10（周六）");
        int fourth = rendered.indexOf("2026-10-04（周日）");
        assertTrue(tenth >= 0 && fourth >= 0, "两天都要在渲染里；实际：\n" + rendered);
        assertTrue(tenth < fourth, "渲染必须保持「最新在前」；实际顺序：\n" + rendered);
    }

    @Test
    void renderEntries_emptySelection_isEmptyString_notPlaceholder() {
        assertEquals("", EmotionAnalysis.renderEntries(List.of()));
    }

    // =====================================================================
    // 4. build：提示词进 messages[0]、窗口内容一条不少一条不进、预览 == 实发
    // =====================================================================

    @Test
    void build_putsTheRolePromptInMessagesZero_andPromptTextContainsItsFirstSentence() {
        PendingRequest pending =
                EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog());
        String prompt = PromptLibrary.builtIn().load(PromptRole.COUNSELOR);

        assertEquals("system", pending.messages().get(0).role(),
                "messages[0] 必须是 system（提示词那一格）；实际：" + pending.messages().get(0).role());
        assertEquals(prompt, pending.messages().get(0).content(),
                "messages[0] 的正文必须逐字等于 PromptLibrary.load(COUNSELOR) 的返回值");

        // ★ 本任务唯一的守卫：Task 7 的契约只管「预览 == 实发」，管不了「提示词有没有被放进去」。
        assertTrue(prompt.startsWith(PROMPT_FIRST_SENTENCE),
                "内置提示词的首句变了，本断言的常量必须跟着改；实际首句：" + firstLine(prompt));
        assertTrue(pending.promptText().contains(PROMPT_FIRST_SENTENCE),
                "promptText 必须含提示词的首句「" + PROMPT_FIRST_SENTENCE + "」——"
                        + "没有它，模型收到的只是一堆记录，三段式与措辞约束全部丢失；"
                        + "实际 promptText 开头：\n" + excerpt(pending.promptText()));
    }

    @Test
    void build_usesWhicheverPromptTheRoleSelects() {
        String planner = PromptLibrary.builtIn().load(PromptRole.PLANNER);
        String counselor = PromptLibrary.builtIn().load(PromptRole.COUNSELOR);
        assertNotEquals(planner, counselor, "两个角色的内置提示词本来就不同（fixture 前提）");

        PendingRequest asPlanner = EmotionAnalysis.build(PromptRole.PLANNER, OCT_4_TO_10, sampleLog());
        PendingRequest asCounselor = EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog());

        assertEquals(planner, asPlanner.messages().get(0).content(),
                "角色参数必须决定装进去的是哪一份提示词（硬编码 COUNSELOR 的实现在这里红）");
        assertEquals(counselor, asCounselor.messages().get(0).content());
        assertEquals(PromptRole.COUNSELOR.displayName(), asCounselor.role(),
                "PendingRequest.role 要是角色的展示名，供确认界面标注");
        assertEquals(PromptRole.PLANNER.displayName(), asPlanner.role());
    }

    /**
     * 评审 F3（变异 V9 全绿的那一半）：{@code build} 的运行时自检必须真的检查
     * 「待发送文本里含提示词锚点」，而不只是「参数被用到了」。
     *
     * <p>给一份 {@code load(role)} 只返回空白的提示词库，锚点就算成空串。只要自检还在，
     * {@code build} 必须当场抛 {@link IllegalStateException}，而不是安静地发出去一份没有约束的请求。
     *
     * <p>变异体：把自检条件从 {@code anchor.isEmpty() || …} 换成 {@code prompt.isEmpty() || …}
     * （即永不看锚点）→ 本条会以「没有抛异常」红。
     */
    @Test
    void build_selfCheck_throwsWhenTheAssembledTextCarriesNoPromptAnchor() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog(),
                        new BlankPromptLibrary()),
                "自检必须拦住「待发送文本里没有提示词锚点」——没有它，一个只拼记录、"
                        + "丢掉提示词的实现会把「预览 == 实发」一致地做对，而模型失去全部行为约束");
        assertTrue(e.getMessage().contains("提示词"),
                "异常消息要说清是提示词没进待发送文本；实际：" + e.getMessage());
        assertTrue(e.getMessage().contains("锚点"),
                "异常消息要带上「锚点」这个判定依据，便于定位；实际：" + e.getMessage());
    }

    @Test
    void build_producesPendingRequestWhosePromptTextContainsEverySelectedEntry() {
        String promptText =
                EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog()).promptText();

        for (String must : List.of("2026-10-10（周六）", "窗口最后一天备注",
                "2026-10-04（周日）", "窗口第一天备注", "连着两天赶作业")) {
            assertTrue(promptText.contains(must),
                    "promptText 必须逐字含选中记录的「" + must + "」；实际：\n" + promptText);
        }
        assertTrue(promptText.contains("将发送 2 条记录"),
                "用户要先看到「将发送 N 条记录」（spec §6.4 第 2 步）；实际：\n" + promptText);
        assertTrue(promptText.contains("2026-10-04") && promptText.contains("2026-10-10"),
                "时间窗的两个端点要出现在待发送文本里（用户核对「发的是哪一段」）；实际：\n" + promptText);
    }

    @Test
    void build_promptTextExcludesOutOfRangeEntries() {
        String promptText =
                EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog()).promptText();

        for (String forbidden : List.of("十月二十日窗口外备注", "十月三日窗口外备注", "去年跨年窗口外备注")) {
            assertFalse(promptText.contains(forbidden),
                    "窗口外的记录「" + forbidden + "」一个字节都不许进入待发送文本"
                            + "（隐私：只发用户选定的那一窗）；实际：\n" + promptText);
        }
        assertFalse(promptText.contains("2026-10-20"), "窗口外的日期也不许出现");
        assertFalse(promptText.contains("2026-10-03"), "窗口外的日期也不许出现");
        assertFalse(promptText.contains("2025-12-31"), "窗口外的日期也不许出现");
    }

    @Test
    void build_withEmptyWindow_stillProducesSendableRequest_withoutInventingEntries() {
        PendingRequest pending = EmotionAnalysis.build(PromptRole.COUNSELOR,
                window(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 7)), sampleLog());
        String promptText = pending.promptText();

        assertTrue(promptText.contains("将发送 0 条记录"),
                "空窗口也要如实说「0 条」，不许悄悄省略；实际：\n" + promptText);
        assertFalse(promptText.contains("2026-10-10"), "空窗口里不许出现任何窗口外的条目");
        assertTrue(promptText.contains(PROMPT_FIRST_SENTENCE), "空窗口同样要带提示词");
    }

    @Test
    void build_promptTextIsExactlyWhatWillBeSent() {
        PendingRequest pending =
                EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog());

        // 复用 Task 7 的出口，但这里自己断言一次：预览必须逐字等于「消息正文用 \n 连接」
        assertEquals(PendingRequest.joinContents(pending.messages()), pending.promptText(),
                "promptText 必须等于 joinContents(messages)——预览与实发各算一次就是两处真相");
        assertEquals(2, pending.messages().size(),
                "这次请求是「提示词 + 用户消息」两条；实际：" + pending.messages());
        assertEquals("user", pending.messages().get(1).role(),
                "第二条必须是 user（记录原文装在这里）；实际：" + pending.messages().get(1).role());
    }

    @Test
    void build_sentMessagesReachTheWireIdentically() throws Exception {
        PendingRequest pending =
                EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog());

        // 空确认门什么也证明不了，所以这里放一个真的本地桩（不访问真实网络）。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AiClient client = new AiClient(
                    new AiConfig(base, "test-key", "test-model", Duration.ofSeconds(5)),
                    HttpClient.newHttpClient());
            ConfirmingAiSession session = new ConfirmingAiSession(client, gate -> true);

            // 发送路径自己组装的那一份，必须与 build 出来的这一份逐字相同
            assertEquals(pending.promptText(),
                    session.preview(PromptRole.COUNSELOR, pending.messages()).promptText(),
                    "build 的预览必须与确认门自己算出来的那一份逐字相同");

            session.complete(PromptRole.COUNSELOR, pending.messages());

            assertEquals(1, hits.get(), "确认之后必须真的发出一次");
            assertEquals(1, bodies.size());
            assertTrue(bodies.get(0).contains("窗口最后一天备注"),
                    "发到线上的 JSON 必须带上选中记录的原文；实际：" + bodies.get(0));
            assertFalse(bodies.get(0).contains("十月三日窗口外备注"),
                    "窗口外的记录不许在线路上出现；实际：" + bodies.get(0));
            assertEquals(pending.promptText(), session.lastSentPreview(),
                    "确认门记录的「真正发出过的文本」必须等于 build 的 promptText");
        } finally {
            server.stop(0);
        }
    }

    // =====================================================================
    // 5. 不落盘：四处位置都不许新增，也不许改动已有文件
    // =====================================================================

    /**
     * 本轮加强后的「不落盘」守卫（评审 F1）。
     *
     * <p>老版本只比<b>相对路径集合</b>，于是两类写入完全看不见：写到 {@code java.io.tmpdir} /
     * {@code target} / 用户主目录的<b>新文件</b>（位置根本不在快照范围内），以及<b>追加/改写已有文件</b>
     * （路径集合不变）。现在压到子 JVM 里跑，快照口径是「路径 → 大小 + mtime」。
     */
    @Test
    void build_parse_renderEntries_writeNothing_anywhere(@TempDir Path tempDir) throws Exception {
        Path freshCwd = Files.createDirectory(tempDir.resolve("空工作目录"));
        Path runnerDir = Files.createDirectory(tempDir.resolve("runner"));
        Path runnerFile = writeProbeRunner(runnerDir, WatchProbe.MAIN);

        String output = runProbeRunner(runnerFile, freshCwd);

        assertTrue(output.contains("T9-PROBE-OK"),
                "子 JVM 没有跑到最后一步（这条测试要证明的是「跑完了但没写文件」）；输出：\n" + output);
        assertEquals("无差异", decodeDelta(between(output, "T9-PROBE-DELTA-BEGIN", "T9-PROBE-DELTA-END")),
                "build / Result.parse / renderEntries 不得在【工作目录（递归）/ java.io.tmpdir（顶层）/ "
                        + "用户主目录（顶层）/ 模块 target（顶层）】四处新建或改动任何文件"
                        + "（spec §6.4：分析结果不写回情绪记录文件）");
    }

    /**
     * 顺序无关版：在<b>一个全新的空目录</b>里跑同一段分析，之后那个目录里的条目必须<b>一个都没有</b>。
     *
     * <p>为什么需要它：{@code build_parse_renderEntries_writeNothing_anywhere} 比的是<b>子 JVM 内</b>
     * 调用前后的差集，而 JUnit 可能把本类里别的用例排到它前面；它们若写过文件，文件在「调用之前」
     * 就已在那个位置，差集就是空的（第一版实测过：真写文件的变异体全绿，而探针确实落盘）。
     * 把工作目录换成一个全新空目录之后，「里面有没有东西」只有一个答案，与执行顺序无关。
     * 用子进程而不是删掉工作目录：删模块目录会让同一次运行里其它测试（例如靠相对路径读
     * {@code src/test/resources} 的那些）连带失败。
     */
    @Test
    void build_writesNothing_inAFreshEmptyWorkingDirectory(@TempDir Path tempDir) throws Exception {
        Path freshCwd = Files.createDirectory(tempDir.resolve("空工作目录"));
        Path runnerDir = Files.createDirectory(tempDir.resolve("runner"));
        Path runnerFile = writeProbeRunner(runnerDir, FreshCwdProbe.MAIN);

        String output = runProbeRunner(runnerFile, freshCwd);

        assertTrue(output.contains("T9-OK"), "子 JVM 没有跑到最后一步；输出：\n" + output);
        String fresh = between(output, "T9-FRESH-BEGIN", "T9-FRESH-END");
        // 源码启动模式实测不落 .class（JDK 25 的内存编译），所以这里要求「一个条目都不许有」。
        assertEquals("空", fresh,
                "在空的子 JVM 工作目录里跑完分析之后，那里必须还是一个空目录"
                        + "（spec §6.4：分析结果不写回情绪记录文件）；实测目录内容：\n"
                        + Files.list(freshCwd).map(Path::toString).collect(Collectors.joining("\n")));
    }

    /**
     * 守卫机制的自证（一）：快照口径含「大小 + mtime」，所以<b>追加到已存在文件</b>会被看见。
     *
     * <p>旧快照只比相对路径集合，这条「追加」在它眼里是完全隐形的。这里对一条真实文件做
     * {@link StandardOpenOption#APPEND} 写入，断言新快照里两个量都变了——守卫不是纸面覆盖。
     */
    @Test
    void writeGuard_snapshotRemembersSizeAndTime_soAppendingToAnExistingFileIsVisible(
            @TempDir Path tempDir) throws Exception {
        Path existing = tempDir.resolve("已存在.txt");
        Files.writeString(existing, "base\n", StandardCharsets.UTF_8);

        String before = FileSnapshot.capture(tempDir);
        assertTrue(before.contains("size=") && before.contains("mtime="),
                "前置不成立：快照口径里必须同时有大小与 mtime；实际：\n" + before);

        Files.writeString(existing, "追加的一行\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        String after = FileSnapshot.capture(tempDir);

        assertNotEquals(before, after,
                "追加到已存在文件必须改变快照（旧口径只比相对路径集合，这里是隐形的）");
        assertTrue(before.contains("size=5"),
                "5 = \"base\\n\" 的字节数（UTF-8）；实际：\n" + before);
        assertNotEquals(extractField(before, "已存在.txt", "size="),
                extractField(after, "已存在.txt", "size="),
                "追加之后大小必须变大；before=" + before + "\nafter=" + after);
        assertTrue(Long.parseLong(extractField(after, "已存在.txt", "size="))
                        > Long.parseLong(extractField(before, "已存在.txt", "size=")),
                "追加之后的新大小必须严格大于旧大小；before=" + before + "\nafter=" + after);
    }

    /** 守卫机制的自证（二）：探针子 JVM 本身跑得起来（子 JVM 起不来时那条断言会以别的方式红）。 */
    @Test
    void writeGuard_probeRunnerActuallyRunsTheAnalysis(@TempDir Path tempDir) throws Exception {
        Path freshCwd = Files.createDirectory(tempDir.resolve("空工作目录"));
        Path runnerDir = Files.createDirectory(tempDir.resolve("runner"));
        Path runnerFile = writeProbeRunner(runnerDir, FreshCwdProbe.MAIN);

        String output = runProbeRunner(runnerFile, freshCwd);

        assertTrue(output.contains("T9-OK"),
                "探针子 JVM 必须真的跑完 EmotionAnalysis.build / parse / renderEntries；输出：\n" + output);
        assertEquals("", between(output, "T9-STDERR-BEGIN", "T9-STDERR-END"),
                "探针子 JVM 不许有任何异常输出（有异常时上面那条会以别的方式红）；输出：\n" + output);
    }

    /**
     * 分析类型的公开面里不得出现 {@code java.nio.file.Path} / {@code java.io.File}
     * （spec §6.4：分析结果不写回情绪记录文件）。
     *
     * <p>评审 F5：老版本只扫 {@code getDeclaredMethods()}（漏掉继承来的公开方法）、只扫三个类型
     * （漏 {@link EmotionAnalysis.Outcome} 与私有的 {@code Heading}）、只匹配 {@code java.nio.file}
     * 与 {@code java.io.File}。现在换成 {@code getMethods()}、扫全部相关类型，并额外匹配
     * {@code java.io.OutputStream} / {@code Writer} / {@code Reader} / {@code InputStream}
     * ——「拿得到一个写出口」同样是形态上的缺口。
     */
    @Test
    void analysisTypes_haveNoPathOrFileHandleInTheirPublicSurface() {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        int typeCount = 0;

        for (Class<?> type : List.of(AnalysisWindow.class, EmotionAnalysis.class,
                EmotionAnalysis.Result.class, EmotionAnalysis.Outcome.class, analysisHeadingType())) {
            typeCount++;
            // getMethods()：public 面（含继承来的）；getDeclaredMethods()：包内/私有面，一并扫
            for (Method method : type.getMethods()) {
                scanned++;
                collectIfPath(offenders, type, "public 方法 " + method.getName() + " 的返回值",
                        method.getGenericReturnType());
                for (Type parameter : method.getGenericParameterTypes()) {
                    collectIfPath(offenders, type, "public 方法 " + method.getName() + " 的参数", parameter);
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                scanned++;
                for (Type parameter : constructor.getGenericParameterTypes()) {
                    collectIfPath(offenders, type, "构造器参数", parameter);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                scanned++;
                collectIfPath(offenders, type, "字段 " + field.getName(), field.getGenericType());
            }
            RecordComponent[] components = type.getRecordComponents();
            for (RecordComponent component : components == null ? new RecordComponent[0] : components) {
                scanned++;
                collectIfPath(offenders, type, "record 组件 " + component.getName(),
                        component.getGenericType());
            }
        }

        assertEquals(5, typeCount, "扫描面必须是这 5 个类型（漏掉一个就少扫一片公开面）");
        assertTrue(offenders.isEmpty(),
                "情绪分析的公开面里不得出现 java.nio.file.Path / java.io.File / 任何文件句柄"
                        + "（spec §6.4：分析结果不写回情绪记录文件）；命中：" + offenders);
        // 扫描面本身要非空：上面若因开关写错而一个成员都没扫到，这条也得红
        assertTrue(scanned >= 50,
                "反射扫描覆盖面异常：只扫到 " + scanned + " 个成员（getMethods() 之后应当有几十个）");
    }

    /**
     * brief Step 1 明写、但此前<b>不存在</b>的一条断言（评审 F5）：
     * 「{@code wellbeing} 包内无 {@code Files.write} 引用」。
     *
     * <p>反射只看签名，管不了方法体里写文件；这条直接扫源码文本，把「包内出现任何写文件 API」
     * 变成一次红。当前实现干净（无命中），断言的价值在于这段扫描逻辑会一直跑。
     */
    @Test
    void wellbeingPackage_hasNoFileWriteApiReference() throws Exception {
        Path sourceRoot = Path.of("src", "main", "java", "com", "xiaoyiyuan", "study", "wellbeing");
        assertTrue(Files.isDirectory(sourceRoot),
                "工作目录应当是 study-core 模块根；实际 " + sourceRoot.toAbsolutePath());

        List<Path> sources;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(sources.size() >= 6,
                "扫描面异常：wellbeing 包下至少应有 6 个生产类；实际：" + sources);

        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                if (line.matches(".*(Files\\.write|Files\\.newOutputStream|Files\\.createFile|"
                        + "FileOutputStream|FileWriter|new\\s+BufferedWriter|RandomAccessFile).*")) {
                    hits.add(source.getFileName() + " → " + line.strip());
                }
            }
        }

        assertTrue(hits.isEmpty(),
                "wellbeing 包内不得出现任何写文件 API（brief Step 1：包内无 Files.write 引用；"
                        + "spec §6.4：分析结果不写回情绪记录文件）；命中：\n" + String.join("\n", hits));
    }

    // =====================================================================
    // 6. 措辞：禁止诊断性标签与 8 个禁用词
    // =====================================================================

    @Test
    void buildingTexts_containNoDiagnosticLabelsAndNoDisabledWords() {
        PendingRequest pending =
                EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog());

        // 扫描范围：本任务**自己生成**的每一段文字。
        // messages[0] 是提示词（用户可在 <studyDir>\提示词\*.md 里改的内容），它的文案合规
        // 由计划 3 的 DisabledWordTest 直接扫 study-core/src/main/resources 覆盖；
        // 本任务不得改提示词一个字（任务约束），所以这里不把提示词算进「本任务产出的文案」。
        assertWordingIsClean("user 消息（本任务拼装的正文）", pending.messages().get(1).content());
        assertWordingIsClean("promptText 里本任务拼装的那一段",
                pending.promptText().substring(pending.messages().get(0).content().length()));
        assertWordingIsClean("渲染片段", EmotionAnalysis.renderEntries(OCT_4_TO_10.select(sampleLog())));
        assertWordingIsClean("角色名", pending.role());
        assertWordingIsClean("危机资源提示", EmotionAnalysis.crisisNotice());
    }

    @Test
    void plainAnalysis_reportsNoCrisis() {
        EmotionAnalysis.Result result = EmotionAnalysis.Result.parse("观察到的模式：甲\n小建议：丙");
        assertFalse(result.crisisTriggered(),
                "模型输出里没有危机词时 crisisTriggered 必须是 false（否则这个标记会变成噪声）");
        assertEquals("", result.crisisNotice(),
                "没有命中时不许出现任何危机提示（提示本身就是给用户的信号）；实际："
                        + result.crisisNotice());
    }

    // =====================================================================
    // 7. 危机命中：不阻断，且提示中性
    // =====================================================================

    @Test
    void crisisKeywordInWindow_doesNotBlockAnalysis_andKeepsANeutralResourceNotice() {
        EmotionLog log = EmotionLog.parse(CRISIS_LOG_TEXT);
        AnalysisWindow window = window(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10));

        // 前提：本地判定确实命中（否则这条测试什么也没证明）
        String windowText = EmotionAnalysis.renderEntries(window.select(log));
        CrisisSignal signal = CrisisDetector.builtIn().detect(windowText);
        assertTrue(signal.triggered(),
                "fixture 前提不成立：窗口内文本必须命中危机关键词；实际：" + signal + "\n" + windowText);

        // 主导航：命中之后照常产出分析请求——不抛异常、不替换成「请拨打热线」、不丢记录
        PendingRequest pending = EmotionAnalysis.build(PromptRole.COUNSELOR, window, log);
        assertEquals(2, pending.messages().size(),
                "命中危机词之后请求形状不变（仍是 提示词 + 用户消息）；实际：" + pending.messages());
        assertTrue(pending.promptText().contains(PROMPT_FIRST_SENTENCE), "提示词照常在");
        assertTrue(pending.promptText().contains("将发送 2 条记录"),
                "两条记录照常进入请求（不因为命中而少发）；实际：\n" + pending.promptText());
        assertTrue(pending.promptText().contains("最近总觉得撑不下去了"),
                "命中危机的原文照常作为分析材料送出去（不替换成热线话术）；实际：\n"
                        + pending.promptText());
        assertTrue(pending.promptText().contains("睡不好，作业也拖着"),
                "同窗口的其它记录也不许被丢掉；实际：\n" + pending.promptText());

        // 中性的危机资源提示：保留 120 与 110，且不含禁用词
        String notice = EmotionAnalysis.crisisNotice();
        assertTrue(notice.contains("120"), "危机提示必须保留 120；实际：" + notice);
        assertTrue(notice.contains("110"), "危机提示必须保留 110；实际：" + notice);
        assertTrue(notice.contains("专业"),
                "必须说明「不替代专业帮助」（spec §6.5）；实际：" + notice);
        assertWordingIsClean("危机资源提示", notice);
    }

    @Test
    void buildWithCrisis_flagsTheHit_andLeavesTheRequestByteIdentical() {
        EmotionLog log = EmotionLog.parse(CRISIS_LOG_TEXT);
        AnalysisWindow window = window(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10));

        EmotionAnalysis.Outcome outcome = EmotionAnalysis.buildWithCrisis(
                PromptRole.COUNSELOR, window, log, CrisisDetector.builtIn());

        assertTrue(outcome.crisisTriggered(), "这一窗记录里有危机关键词，必须标记为命中");
        assertEquals(EmotionAnalysis.crisisNotice(), outcome.crisisNotice(),
                "命中时要给出中性资源提示");
        assertEquals(EmotionAnalysis.build(PromptRole.COUNSELOR, window, log).promptText(),
                outcome.request().promptText(),
                "命中之后待发送文本必须**逐字相同**——不丢记录、不换成热线话术、不缩短预览");
        assertTrue(outcome.request().promptText().contains("最近总觉得撑不下去了"),
                "命中危机的原文照常留在请求里；实际：\n" + outcome.request().promptText());
        assertFalse(outcome.request().promptText().contains("拨打热线"),
                "请求里不许出现「请拨打热线」这类替换分析的话术");
    }

    @Test
    void buildWithCrisis_withoutAHit_isSilent() {
        EmotionAnalysis.Outcome outcome = EmotionAnalysis.buildWithCrisis(
                PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog(), CrisisDetector.builtIn());
        assertFalse(outcome.crisisTriggered(), "这几条记录里没有危机词");
        assertEquals("", outcome.crisisNotice(), "没命中就不许显示任何危机提示");
    }

    /**
     * 评审 F4：危机判定必须打在「本次真正会发送的那份原文」上。
     *
     * <p>老实现为了判定又 {@code window.select(log)} 了一遍（同一段记录被扫两次），两次选取条件
     * 一旦分岔，「面板判定的文本」与「实际发送的文本」就会悄悄不一样。这里把「判定用的文本」
     * 逐字钉到 {@code promptText} 里 {@code # 我的记录（原文）} 与 {@code # 我要的分析} 之间的那一段。
     *
     * <p><b>诚实说明</b>：调用次数本身在公开面上<b>不可观测</b>——{@code CrisisDetector} 是
     * {@code final}、构造器私有的，没法用替身记账；而「选取两次」与「选取一次」在值上完全等价
     * （{@code select} 是纯函数）。所以这条用例守的是「判定 = 发送」这条性质，
     * 而不是「只扫描一次」这个实现细节：双选版本的变异体在它下面照样全绿（见报告的变异转录）。
     */
    @Test
    void buildWithCrisis_judgesTheExactTextItSends() {
        EmotionLog crisisLog = EmotionLog.parse(CRISIS_LOG_TEXT);
        AnalysisWindow window = window(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10));

        EmotionAnalysis.Outcome outcome = EmotionAnalysis.buildWithCrisis(
                PromptRole.COUNSELOR, window, crisisLog, CrisisDetector.builtIn());

        assertTrue(outcome.crisisTriggered(), "这一窗的记录里确实有关键词表命中的表达");
        String promptText = outcome.request().promptText();
        int from = promptText.indexOf("# 我的记录（原文）\n\n");
        int to = promptText.indexOf("\n\n# 我要的分析");
        assertTrue(from >= 0 && to > from, "待发送文本里应当有「我的记录（原文）」那一段；实际：\n" + promptText);
        String sent = promptText.substring(from + "# 我的记录（原文）\n\n".length(), to);

        assertEquals(EmotionAnalysis.renderEntries(window.select(crisisLog)), sent,
                "待发送文本里那一段必须逐字等于 renderEntries(窗口选中的条目)");
        assertTrue(sent.contains("最近总觉得撑不下去了"),
                "判定材料里应当有那条备注；实际：\n" + sent);
        assertFalse(sent.contains("十月三日窗口外备注"), "窗口外的条目一个也不许进判定材料");

        EmotionAnalysis.Outcome withoutDetector =
                EmotionAnalysis.buildWithCrisis(PromptRole.COUNSELOR, window, crisisLog, null);
        assertFalse(withoutDetector.crisisTriggered(),
                "detector 为 null 等价于「不判定」，此时恒为 false（不猜、不阻断）");
        assertEquals(outcome.request().promptText(), withoutDetector.request().promptText(),
                "带不带检测器都不许改变待发送文本");
    }

    @Test
    void result_thatMentionsCrisisInTheRecording_carriesTheNoticeWithoutLosingTheAnalysis() {
        String aiText = """
                观察到的模式：这两天都写了睡得晚，10-08 那天写「撑不下去了」。
                可能的影响因素：作业堆在晚上，睡得越来越晚。
                小建议：把明天第一件事写在纸上；
                """;
        // 「本地关键词表命中了」这个事实由 build 的调用方判定之后传进来（spec §6.5：不由 AI 判断）
        EmotionAnalysis.Result result = EmotionAnalysis.Result.parse(aiText, true);

        assertTrue(result.crisisTriggered(),
                "命中时要给出信号，界面才有资源面板可显示；实际：" + result);
        assertTrue(result.crisisNotice().contains("120") && result.crisisNotice().contains("110"),
                "危机提示必须保留 120 与 110；实际：" + result.crisisNotice());
        assertWordingIsClean("危机提示", result.crisisNotice());
        assertEquals("这两天都写了睡得晚，10-08 那天写「撑不下去了」。", result.observations(),
                "三段分析照收，不因为命中而丢内容；实际：" + result.observations());
        assertTrue(result.factors().contains("作业堆在晚上"), "实际：" + result.factors());
        assertTrue(result.suggestions().contains("把明天第一件事写在纸上"), "实际：" + result.suggestions());
    }

    @Test
    void renderEntries_doesNotConsultCrisisDetector_soNoSilentRedaction() {
        // 「命中即改写」的实现会在这里红：原文里的危机句必须逐字留着送到分析去
        String rendered = EmotionAnalysis.renderEntries(EmotionLog.parse(CRISIS_LOG_TEXT).entries());
        assertTrue(rendered.contains("最近总觉得撑不下去了"),
                "渲染层不许因为命中危机词而改写用户原文；实际：\n" + rendered);
    }

    // =====================================================================
    // 8. parse：三段式、容错、不洗白
    // =====================================================================

    @Test
    void parse_extractsThreeSections() {
        EmotionAnalysis.Result result =
                EmotionAnalysis.Result.parse("观察到的模式：甲\n可能的影响因素：乙\n小建议：丙");

        assertEquals("甲", result.observations());
        assertEquals("乙", result.factors());
        assertEquals("丙", result.suggestions());
    }

    @Test
    void parse_ignoresContentBeforeTheFirstSection() {
        EmotionAnalysis.Result result = EmotionAnalysis.Result.parse(
                "好的，我看了这几天记录。\n\n观察到的模式：甲\n可能的影响因素：乙\n小建议：丙");
        assertEquals("甲", result.observations(), "开场白不该混进第一段");
        assertEquals("乙", result.factors());
        assertEquals("丙", result.suggestions());
    }

    @Test
    void parse_missingSection_yieldsEmptyString_notThrow() {
        EmotionAnalysis.Result onlyFirst = EmotionAnalysis.Result.parse("观察到的模式：只有第一段");
        assertEquals("只有第一段", onlyFirst.observations());
        assertEquals("", onlyFirst.factors(), "缺的那一段必须是空串，不是 null、不抛异常");
        assertEquals("", onlyFirst.suggestions());

        EmotionAnalysis.Result onlyLast = EmotionAnalysis.Result.parse("小建议：只给建议");
        assertEquals("", onlyLast.observations());
        assertEquals("", onlyLast.factors());
        assertEquals("只给建议", onlyLast.suggestions());
    }

    @Test
    void parse_emptySectionBody_isEmptyString() {
        EmotionAnalysis.Result result =
                EmotionAnalysis.Result.parse("观察到的模式：甲\n可能的影响因素：\n小建议：丙");
        assertEquals("甲", result.observations());
        assertEquals("", result.factors(), "标题在、正文空 → 空串（不是「：」后面那点空白）");
        assertEquals("丙", result.suggestions());
    }

    @Test
    void parse_headingMustMatchExactly() {
        // 标题必须逐字匹配：少了「到」不算那一段（近似匹配会把正文里的词误当标题）。
        // 探针行放在第一个标题**之前**：那是解析器明确忽略的位置，于是这条断言只检验
        // 「近似标题不被认成标题」，不会顺带断言「最后一个标题之后的内容要不要」。
        EmotionAnalysis.Result result = EmotionAnalysis.Result.parse(
                "观察模式的补充说明：这不是标题\n观察到的模式：甲");
        assertEquals("甲", result.observations(), "实际：" + result.observations());
        assertEquals("", result.factors());
        assertEquals("", result.suggestions());
    }

    @Test
    void parse_sectionsKeepTheirInternalLineBreaks() {
        EmotionAnalysis.Result result = EmotionAnalysis.Result.parse("""
                观察到的模式：十天里有三天写了睡得晚。
                这三天分别是 10-08、10-09、10-10。
                可能的影响因素：
                (1) 作业堆在晚上
                (2) 睡前刷手机
                小建议：
                1. 把明天第一件事写在纸上
                2. 睡前把手机放到桌子上
                """);

        assertTrue(result.observations().contains("10-08、10-09、10-10"), "实际：" + result.observations());
        assertTrue(result.observations().contains("\n"), "段内的换行要留着（模型的分点排版）");
        assertTrue(result.factors().contains("(1) 作业堆在晚上"), "实际：" + result.factors());
        assertTrue(result.factors().contains("(2) 睡前刷手机"), "实际：" + result.factors());
        assertTrue(result.suggestions().contains("1. 把明天第一件事写在纸上"), "实际：" + result.suggestions());
        assertTrue(result.suggestions().contains("2. 睡前把手机放到桌子上"), "实际：" + result.suggestions());
        assertFalse(result.observations().contains("可能的影响因素"), "上一段不得吞掉下一段的标题");
    }

    @Test
    void parse_emptyOrBlankInput_returnsAllEmpty() {
        for (String input : new String[]{null, "", "   ", "\n\n"}) {
            EmotionAnalysis.Result result = EmotionAnalysis.Result.parse(input);
            assertEquals("", result.observations(), "输入=" + input);
            assertEquals("", result.factors(), "输入=" + input);
            assertEquals("", result.suggestions(), "输入=" + input);
            assertFalse(result.crisisTriggered(), "输入=" + input);
        }
    }

    @Test
    void parse_recognizesHeadingsWhoseDecorationsAreStripped_butNotLookalikes() {
        // 与 headingAt 的注释配套：各种装饰形态都要照旧认出来，而「多一个字的近似标题」不算标题。
        EmotionAnalysis.Result result = EmotionAnalysis.Result.parse("""
                ## 观察到的模式
                甲
                **可能的影响因素**:乙
                （2）小建议:丙
                """);
        assertEquals("甲", result.observations(), "实际：" + result);
        assertEquals("乙", result.factors(), "实际：" + result);
        assertEquals("丙", result.suggestions(), "实际：" + result);

        // 反向：多一个字就不是标题（逐字匹配），免得近似匹配把正文整块挪进错误的段
        EmotionAnalysis.Result lookalike = EmotionAnalysis.Result.parse("观察模式的补充说明：这不是标题");
        assertTrue(lookalike.hasNoSections(),
                "「观察模式的补充说明」不是标题，三段都该是空串；实际：" + lookalike);
    }

    @Test
    void parse_matchesHeadingsWithAsciiColon() {
        EmotionAnalysis.Result result =
                EmotionAnalysis.Result.parse("观察到的模式:甲\n可能的影响因素:乙\n小建议:丙");
        assertEquals("甲", result.observations(), "模型可能给半角冒号，不能因此整段丢失");
        assertEquals("乙", result.factors());
        assertEquals("丙", result.suggestions());
    }

    @Test
    void parse_matchesHeadingsWrittenAsMarkdownHeadingsOrWithNumbering() {
        EmotionAnalysis.Result result = EmotionAnalysis.Result.parse("""
                ## 观察到的模式
                甲
                **可能的影响因素**：乙
                3. 小建议：丙
                """);
        assertEquals("甲", result.observations(), "实际：" + result.observations());
        assertEquals("乙", result.factors(), "实际：" + result.factors());
        assertEquals("丙", result.suggestions(), "实际：" + result.suggestions());
    }

    @Test
    void parse_doesNotRewriteDiagnosticVocabularyFromTheModel() {
        // 措辞约束属于提示词职责；解析层如实呈现，界面负责标注「模型违规」。
        // 一个「洗白」（把「抑郁」换成别的词）的实现会在这里红。
        EmotionAnalysis.Result result =
                EmotionAnalysis.Result.parse("观察到的模式：这一段提到了抑郁两个字\n小建议：丙");
        assertEquals("这一段提到了抑郁两个字", result.observations(),
                "parse 必须如实呈现模型输出：静默改写会让用户看不到模型真的说了什么");
    }

    @Test
    void parse_neverThrows_onGarbageInput() {
        for (String garbage : new String[]{"！！！", "观察到的模式", "小建议：", "- 甲\n- 乙",
                "可能的影响因素：乙\n观察到的模式：甲"}) {
            assertDoesNotThrow(() -> EmotionAnalysis.Result.parse(garbage), "输入=" + garbage);
        }
    }

    /**
     * 评审要求补的 3 参便捷构造器（brief 原本写的就是 3 组件；三段仍然是纯模型输出，
     * 危机信息是并列元数据）。计划 3 里「先渲染三段、危机面板另走一路」的代码不必都写 5 个参数。
     */
    @Test
    void result_threeArgumentConvenienceConstructor_equalsTheFiveArgumentOneWithoutCrisis() {
        EmotionAnalysis.Result convenient =
                new EmotionAnalysis.Result("观察到甲", "因素乙", "建议丙");
        EmotionAnalysis.Result canonical =
                new EmotionAnalysis.Result("观察到甲", "因素乙", "建议丙", false, "");

        assertEquals(canonical, convenient,
                "3 参构造器必须等价于 crisisTriggered=false、crisisNotice=\"\" 的 5 参版本");
        assertFalse(convenient.crisisTriggered(), "3 参构造器的 crisisTriggered 恒为 false");
        assertEquals("", convenient.crisisNotice(), "3 参构造器的 crisisNotice 恒为空串");
        assertEquals("观察到甲", convenient.observations());
        assertEquals("因素乙", convenient.factors());
        assertEquals("建议丙", convenient.suggestions());
        assertFalse(convenient.hasNoSections(), "三段都非空时 hasNoSections() 必须是 false");
        assertTrue(new EmotionAnalysis.Result("", "", "").hasNoSections(),
                "三段都空时 hasNoSections() 必须是 true");

        // 5 组件这个形状本身也要钉住：计划 3 若按 3 参数解构，这里会先红
        assertEquals(5, EmotionAnalysis.Result.class.getRecordComponents().length,
                "Result 仍然是 5 个组件（三段 + crisisTriggered + crisisNotice）");
        EmotionAnalysis.Result parsed = EmotionAnalysis.Result.parse("观察到的模式：甲");
        assertEquals(new EmotionAnalysis.Result("甲", "", ""), parsed,
                "parse 出来的结果与 3 参构造器造出来的是同一件事（未命中危机）");
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private static void assertWordingIsClean(String what, String text) {
        for (String word : DISABLED_WORDS) {
            assertFalse(text.contains(word), what + " 含界面禁用词「" + word + "」；实际：\n" + text);
        }
        for (String label : DIAGNOSTIC_LABELS) {
            assertFalse(text.contains(label), what + " 含诊断性标签「" + label + "」（spec §6.5）；实际：\n" + text);
        }
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String excerpt(String text) {
        return text.length() <= 400 ? text : text.substring(0, 400) + "…（截断显示）";
    }

    /** 反射拿私有内部类型（{@code Heading} 等），不为了测试把它放宽成 public。 */
    private static Class<?> nestedType(String simpleName) {
        for (Class<?> nested : EmotionAnalysis.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) {
                return nested;
            }
        }
        throw new AssertionError("EmotionAnalysis 里没有内部类型 " + simpleName
                + "；实际有：" + java.util.Arrays.toString(EmotionAnalysis.class.getDeclaredClasses()));
    }

    private static Class<?> analysisHeadingType() {
        return nestedType("Heading");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    // ---------------------------------------------------------------------
    // 探针子 JVM：把「有没有写盘」压到一个全新空工作目录里，与执行顺序无关
    // ---------------------------------------------------------------------

    /** 把探针写成 {@code .java} 文件（放在 {@code @TempDir} 里，不在模块 {@code target} 里留东西）。 */
    private static Path writeProbeRunner(Path directory, String mainBody) throws IOException {
        Path runner = directory.resolve("T9WriteProbe.java");
        Files.writeString(runner, """
                import com.xiaoyiyuan.study.ai.PromptRole;
                import com.xiaoyiyuan.study.wellbeing.AnalysisWindow;
                import com.xiaoyiyuan.study.wellbeing.EmotionAnalysis;
                import com.xiaoyiyuan.study.wellbeing.EmotionLog;
                import java.time.LocalDate;

                public class T9WriteProbe {

                """ + mainBody + "}\n", StandardCharsets.UTF_8);
        return runner;
    }

    /**
     * 在子 JVM 里跑探针：工作目录设成 {@code freshCwd}，把父进程的三处目录位置用
     * {@code -D} 系统属性传进去（路径含空格/中文时命令行参数容易出错，系统属性最稳）。
     */
    private static String runProbeRunner(Path runnerFile, Path freshCwd) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        // 探针的输出里有中文（「空」「无差异」），子 JVM 的默认编码在这台机器上是 GBK，
        // 父进程按 UTF-8 解码就会得到乱码——所以显式把文件编码与 stdout 编码都钉成 UTF-8。
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-D" + ProbeRunner.HOME_PROPERTY + "=" + System.getProperty("user.home"));
        command.add("-D" + ProbeRunner.PARENT_CWD_PROPERTY + "="
                + Path.of(System.getProperty("user.dir")).toAbsolutePath());
        command.add("-D" + ProbeRunner.TARGET_PROPERTY + "="
                + Path.of("target").toAbsolutePath());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(runnerFile.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(freshCwd.toFile());
        builder.redirectErrorStream(false);

        Process process = builder.start();
        String stdout;
        String stderr;
        try (var errorStream = process.getErrorStream(); var outStream = process.getInputStream()) {
            stderr = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
            stdout = new String(outStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(3, TimeUnit.MINUTES);

        assertTrue(finished, "探针子 JVM 没有在 3 分钟内结束；输出：\n" + stdout + "\n" + stderr);
        assertEquals(0, process.exitValue(),
                "探针子 JVM 里跑分析这一段落本身就失败了（要证明的是「跑完了但没写文件」）；输出：\n"
                        + stdout + "\n" + stderr);
        return stdout + "\n" + ProbeRunner.STDERR_BEGIN + "\n" + stderr + "\n" + ProbeRunner.STDERR_END;
    }

    private static String between(String text, String begin, String end) {
        int start = text.indexOf(begin);
        int stop = text.indexOf(end);
        assertTrue(start >= 0 && stop > start, "探针输出里没有 " + begin + " … " + end + " 这一段；实际：\n" + text);
        return text.substring(start + begin.length(), stop).strip();
    }

    /** 探针把差异做了 Base64（中文路径直接打印会撞上子进程的默认编码）。 */
    private static String decodeDelta(String base64) {
        return new String(java.util.Base64.getDecoder().decode(base64),
                StandardCharsets.UTF_8);
    }

    /** 从一份快照里取出某个文件的那一项（断言失败时能直接看到是哪一项变了）。 */
    private static String entryOf(String snapshot, String fileName) {
        for (String line : snapshot.split("\n", -1)) {
            if (line.startsWith(fileName + " | ")) {
                return line;
            }
        }
        return "<快照里没有 " + fileName + ">";
    }

    private static String extractField(String snapshot, String fileName, String field) {
        String entry = entryOf(snapshot, fileName);
        int at = entry.indexOf(field);
        if (at < 0) {
            return "<" + fileName + " 的 " + field + " 缺失>";
        }
        int end = entry.indexOf(' ', at + field.length());
        return end < 0 ? entry.substring(at + field.length()) : entry.substring(at + field.length(), end);
    }

    private static void collectIfPath(List<String> offenders, Class<?> owner, String where, Type type) {
        String name = type.getTypeName();
        if (name.contains("java.nio.file") || name.contains("java.io.File")
                || name.contains("java.io.OutputStream") || name.contains("java.io.Writer")
                || name.contains("java.io.InputStream") || name.contains("java.io.Reader")
                || name.contains("java.io.RandomAccessFile")) {
            offenders.add(owner.getSimpleName() + " 的 " + where + " → " + name);
        }
    }

    /**
     * 「不落盘」快照：目录 → 【相对路径 → 大小 + mtime】。
     *
     * <p>比旧的「相对路径集合」多两件事：<b>大小与修改时间</b>。于是「追加/改写已有文件」
     * （路径集合不变）也会让快照变样。{@link #captureTopLevel} 只列直接子项，给那些
     * 「递归会很慢、也容易 flaky」的目录用（用户主目录、{@code java.io.tmpdir}、模块 {@code target}）。
     */
    private static final class FileSnapshot {

        private static final int ATTEMPTS = 5;
        private static final long RETRY_SLEEP_MILLIS = 400;

        private FileSnapshot() {
        }

        private record Item(String path, long size, FileTime modified) {

            String render() {
                return path + " | size=" + size + " mtime=" + modified.toMillis();
            }
        }

        /** 递归快照（含子目录）。 */
        static String capture(Path directory) throws IOException {
            return String.join("\n", withRetry(() -> {
                try (Stream<Path> entries = Files.walk(directory)) {
                    return entries.filter(entry -> !entry.equals(directory))
                            .map(entry -> item(directory, entry))
                            .sorted()
                            .toList();
                }
            }));
        }

        /** 只列直接子项的浅快照（递归会慢、也容易 flaky 的目录用它）。 */
        static String captureTopLevel(Path directory) throws IOException {
            return String.join("\n", withRetry(() -> {
                try (Stream<Path> entries = Files.list(directory)) {
                    return entries.map(entry -> item(directory, entry))
                            .sorted()
                            .toList();
                }
            }));
        }

        private static String item(Path base, Path entry) {
            try {
                return new Item(base.relativize(entry).toString(),
                        Files.size(entry), Files.getLastModifiedTime(entry)).render();
            } catch (IOException e) {
                // 快照途中被别的进程挪走/删掉：如实记成一个条目，让「差异」把它显示出来
                return new Item(base.relativize(entry).toString(), -1, FileTime.fromMillis(-1)).render();
            }
        }

        /**
         * 重试取快照，最多 5 次。系统或别的进程（Ant、编译器、编辑器……）在一次运行里
         * 顺手动一下 {@code user.home}/{@code tmpdir} 会让「前后两个瞬间」天然不等——
         * 那属于噪声，不该算成「这份实现写了盘」。
         */
        private static List<String> withRetry(SnapshotAttempt attempt) throws IOException {
            IOException last = null;
            for (int i = 0; i < ATTEMPTS; i++) {
                try {
                    return attempt.snapshot();
                } catch (IOException e) {
                    last = e;
                    try {
                        Thread.sleep(RETRY_SLEEP_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
            throw last;
        }

        /** 一个目录的快照结果。 */
        @FunctionalInterface
        private interface SnapshotAttempt {
            List<String> snapshot() throws IOException;
        }

        /** 两份快照的可读差异：无差异时返回「无差异」。 */
        static String diff(String before, String after) {
            if (before.equals(after)) {
                return "无差异";
            }
            Set<String> beforeLines = new TreeSet<>(List.of(before.split("\n", -1)));
            Set<String> afterLines = new TreeSet<>(List.of(after.split("\n", -1)));
            Set<String> added = new TreeSet<>(afterLines);
            added.removeAll(beforeLines);
            Set<String> removed = new TreeSet<>(beforeLines);
            removed.removeAll(afterLines);
            return "新增/改动：" + added + "\n消失/改动前：" + removed;
        }
    }

    /** 探针程序的正文（被 {@link #writeProbeRunner} 拼进一个 {@code public class T9WriteProbe}）。 */
    private static final class ProbeRunner {

        static final String HOME_PROPERTY = "t9.probe.user.home";        static final String PARENT_CWD_PROPERTY = "t9.probe.parent.cwd";
        static final String TARGET_PROPERTY = "t9.probe.target";
        static final String SKIP_HOME_PROPERTY = "t9.probe.skip.home";
        static final String DELTA_BEGIN = "T9-PROBE-DELTA-BEGIN";
        static final String DELTA_END = "T9-PROBE-DELTA-END";
        static final String STDERR_BEGIN = "T9-STDERR-BEGIN";
        static final String STDERR_END = "T9-STDERR-END";

        private ProbeRunner() {
        }

        /**
         * 探针正文：在子 JVM 里<b>先</b>对四处位置取快照，再跑分析，再取一次快照，打印差异。
         *
         * <p>四次快照全在子 JVM 里、紧贴着分析调用，所以「前后」之间的缝隙里没有别的测试代码；
         * 模块 {@code target} 与用户主目录是<b>顶层</b>浅快照（递归会慢、也容易 flaky），
         * 工作目录是<b>递归</b>快照。
         */
        static final String MAIN = """
                    private static final String SEP = "\\u0000";

                    public static void main(String[] args) throws Exception {
                        java.util.List<String> places = new java.util.ArrayList<>();
                        places.add("工作目录(" + System.getProperty("user.dir") + ",递归)");
                        if (System.getProperty("%1$s") == null) {
                            places.add("java.io.tmpdir(" + System.getProperty("java.io.tmpdir") + ",顶层)");
                        }
                        if (System.getProperty("%2$s") == null) {
                            places.add("用户主目录(" + System.getProperty("user.home") + ",顶层)");
                        }
                        if (System.getProperty("%3$s") == null) {
                            places.add("模块 target(顶层)");
                        }

                        java.util.List<String> before = snapshotAll(places);
                        runAnalysis();
                        java.util.List<String> after = snapshotAll(places);

                        System.out.println("T9-PROBE-OK");
                        System.out.println("%4$s");
                        System.out.println(diff(before, after));
                        System.out.println("%5$s");
                    }

                    private static void runAnalysis() {
                        EmotionLog log = EmotionLog.parse(
                                "## 2026-10-05（周一）\\n\\n- 情绪：平静 ｜ 强度：3\\n- 备注：探针\\n");
                        EmotionAnalysis.build(PromptRole.COUNSELOR,
                                new AnalysisWindow(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10)),
                                log);
                        EmotionAnalysis.Result.parse("观察到的模式：甲\\n可能的影响因素：乙\\n小建议：丙");
                        EmotionAnalysis.renderEntries(log.entries());
                    }

                    private static java.util.List<String> snapshotAll(java.util.List<String> places)
                            throws Exception {
                        java.util.List<String> out = new java.util.ArrayList<>();
                        for (String place : places) {
                            if (place.startsWith("工作目录")) {
                                out.add(place + SEP + walk(java.nio.file.Path.of(System.getProperty("user.dir"))));
                            } else if (place.startsWith("java.io.tmpdir")) {
                                out.add(place + SEP + topLevel(java.nio.file.Path.of(
                                        System.getProperty("java.io.tmpdir"))));
                            } else if (place.startsWith("用户主目录")) {
                                out.add(place + SEP + topLevel(java.nio.file.Path.of(
                                        System.getProperty("%1$s"))));
                            } else {
                                out.add(place + SEP + topLevel(java.nio.file.Path.of(
                                        System.getProperty("%3$s"))));
                            }
                        }
                        return out;
                    }

                    private static String walk(java.nio.file.Path dir) throws Exception {
                        try (java.util.stream.Stream<java.nio.file.Path> entries =
                                     java.nio.file.Files.walk(dir)) {
                            return entries.filter(p -> !p.equals(dir))
                                    .map(p -> item(dir, p))
                                    .sorted()
                                    .collect(java.util.stream.Collectors.joining("\\n"));
                        }
                    }

                    private static String topLevel(java.nio.file.Path dir) throws Exception {
                        if (java.nio.file.Files.isDirectory(dir)) {
                            try (java.util.stream.Stream<java.nio.file.Path> entries =
                                         java.nio.file.Files.list(dir)) {
                                return entries.map(p -> item(dir, p))
                                        .sorted()
                                        .collect(java.util.stream.Collectors.joining("\\n"));
                            }
                        }
                        return pathOf(dir);
                    }

                    private static String item(java.nio.file.Path base, java.nio.file.Path entry) {
                        try {
                            return base.relativize(entry) + " | size=" + java.nio.file.Files.size(entry)
                                    + " mtime=" + java.nio.file.Files.getLastModifiedTime(entry).toMillis();
                        } catch (java.io.IOException e) {
                            return base.relativize(entry) + " | 读不到";
                        }
                    }

                    private static String pathOf(java.nio.file.Path path) {
                        return path + " | size=" + (java.nio.file.Files.exists(path) ? "有" : "无");
                    }

                    private static String diff(java.util.List<String> before, java.util.List<String> after) {
                        StringBuilder out = new StringBuilder();
                        for (int i = 0; i < before.size() && i < after.size(); i++) {
                            String head = before.get(i).substring(0, before.get(i).indexOf(SEP));
                            String was = before.get(i).substring(before.get(i).indexOf(SEP) + 1);
                            String now = after.get(i).substring(after.get(i).indexOf(SEP) + 1);
                            if (!was.equals(now)) {
                                out.append("【").append(head).append("】新增/改动了：\\n")
                                        .append(added(was, now)).append('\\n');
                            }
                        }
                        String text = out.length() == 0 ? "无差异" : out.toString().strip();
                        // Base64：路径与文案里有中文，直接打印会撞上子进程的默认编码
                        return java.util.Base64.getEncoder().encodeToString(
                                text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    private static String added(String was, String now) {
                        java.util.Set<String> beforeLines = new java.util.TreeSet<>(
                                java.util.List.of(was.split("\\n", -1)));
                        java.util.Set<String> afterLines = new java.util.TreeSet<>(
                                java.util.List.of(now.split("\\n", -1)));
                        java.util.Set<String> added = new java.util.TreeSet<>(afterLines);
                        added.removeAll(beforeLines);
                        java.util.Set<String> removed = new java.util.TreeSet<>(beforeLines);
                        removed.removeAll(afterLines);
                        return "  新增/改动：" + added + "\\n  消失/改动前：" + removed;
                    }
                """.formatted(HOME_PROPERTY, SKIP_HOME_PROPERTY, TARGET_PROPERTY,
                DELTA_BEGIN, DELTA_END);
    }

    /**
     * 四处位置的探针（评审 F1）：子 JVM 里对「工作目录（递归）/ java.io.tmpdir（顶层）/
     * 用户主目录（顶层）/ 模块 target（顶层）」各取一次「路径 → 大小 + mtime」快照，
     * 跑完分析再取一次，打印差异。
     */
    private static final class WatchProbe {

        static final String MAIN = ProbeRunner.MAIN;

        private WatchProbe() {
        }
    }

    /** 只要「那个空工作目录里有什么」的探针（顺序无关那条断言用它）。 */
    private static final class FreshCwdProbe {

        static final String MAIN = """
                    public static void main(String[] args) throws Exception {
                        runAnalysis();
                        System.out.println("T9-OK");
                        System.out.println("T9-FRESH-BEGIN");
                        java.util.List<String> entries;
                        try (java.util.stream.Stream<java.nio.file.Path> stream =
                                     java.nio.file.Files.list(java.nio.file.Path.of(
                                             System.getProperty("user.dir")))) {
                            entries = stream.map(java.nio.file.Path::toString).sorted().toList();
                        }
                        System.out.println(entries.isEmpty() ? "空" : String.join("\\n", entries));
                        System.out.println("T9-FRESH-END");
                    }

                    private static void runAnalysis() {
                        EmotionLog log = EmotionLog.parse(
                                "## 2026-10-05（周一）\\n\\n- 情绪：平静 ｜ 强度：3\\n- 备注：探针\\n");
                        EmotionAnalysis.build(PromptRole.COUNSELOR,
                                new AnalysisWindow(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10)),
                                log);
                        EmotionAnalysis.Result.parse("观察到的模式：甲");
                        EmotionAnalysis.renderEntries(log.entries());
                    }
                """;

        private FreshCwdProbe() {
        }
    }

    /** {@link PromptLibrary} 的替身：返回一份只有空白的提示词，用于自检那半的用例。 */
    private static final class BlankPromptLibrary extends PromptLibrary {

        BlankPromptLibrary() {
            super(null);
        }

        @Override
        public String load(PromptRole role) {
            return "   \n\n  \n";
        }
    }
}
