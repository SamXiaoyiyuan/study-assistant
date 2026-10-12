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
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 *   <li><b>不落盘</b>：调用前后 {@code @TempDir} 与进程工作目录的<b>递归</b>快照完全一致，
 *       且分析类型的公开面里没有任何 {@code Path}。</li>
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
    void lastDays_nonPositive_throwsInsteadOfSilentlyReturningEmptyWindow() {
        assertThrows(IllegalArgumentException.class, () -> AnalysisWindow.lastDays(TODAY, 0),
                "0 天不是一个时间窗；静默返回空窗会让用户以为「这几天没记录」");
        assertThrows(IllegalArgumentException.class, () -> AnalysisWindow.lastDays(TODAY, -7));
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
    // 5. 不落盘：一个字节都不许写
    // =====================================================================

    @Test
    void build_writesNothing_anywhere(@TempDir Path tempDir) throws Exception {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Set<String> cwdBefore = snapshot(workingDirectory);
        Set<String> tempBefore = snapshot(tempDir);

        PendingRequest pending =
                EmotionAnalysis.build(PromptRole.COUNSELOR, OCT_4_TO_10, sampleLog());
        EmotionAnalysis.Result.parse("观察到的模式：甲\n可能的影响因素：乙\n小建议：丙");
        EmotionAnalysis.renderEntries(OCT_4_TO_10.select(sampleLog()));

        // 前置：这次 build 确实产出了东西，后面的快照断言才有意义（否则「什么都没做」也会绿）。
        // 锚点用「本次真正加载到的那份提示词」，不是写死的字面量——外部提示词是可替换的。
        assertTrue(pending.promptText().contains(
                        PromptLibrary.builtIn().load(PromptRole.COUNSELOR)),
                "前置不成立：build 没有产出预期的待发送文本");

        assertEquals(tempBefore, snapshot(tempDir),
                "build / parse / renderEntries 不得在给定目录里创建或修改任何文件");
        Set<String> cwdAfter = snapshot(workingDirectory);
        assertEquals(cwdBefore, cwdAfter,
                "build / parse / renderEntries 不得在工作目录（含子目录）里创建或修改任何文件；差异："
                        + snapshotDifference(cwdBefore, cwdAfter));
    }

    /**
     * 「一个字节都不许写」的<b>顺序无关</b>版本：在<b>一个全新的空目录</b>里跑同一段分析，
     * 之后那个目录必须仍然是空的。
     *
     * <p>为什么需要它：{@link #build_writesNothing_anywhere} 比较的是<b>调用前后</b>的差集，
     * 而 JUnit 可能把本类里别的用例排到它前面；那些用例若写了文件，文件在「调用之前」就已在盘上，
     * 差集就是空的——实测：一个真的往工作目录写文件的变异体在那一条下全绿（同时探针文件确实出现）。
     *
     * <p>做法是起一个<b>子 JVM</b>，把它的工作目录设成一个新建的空目录，让它在里面跑
     * {@code EmotionAnalysis.build}；相对路径的文件写入会落在那个空目录里，于是「有没有写过」
     * 只有一个答案，与 JUnit 的执行顺序无关。用子进程而不是删掉工作目录：删模块目录会让同一次
     * 运行里其它测试（例如靠相对路径读 {@code src/test/resources} 的那些）连带失败。
     */
    @Test
    void build_writesNothing_inAFreshEmptyWorkingDirectory(@TempDir Path tempDir) throws Exception {
        Path emptyCwd = Files.createDirectory(tempDir.resolve("空工作目录"));
        Path runnerDir = Files.createDirectory(tempDir.resolve("runner"));

        String runnerSource = """
                import com.xiaoyiyuan.study.ai.PromptRole;
                import com.xiaoyiyuan.study.wellbeing.AnalysisWindow;
                import com.xiaoyiyuan.study.wellbeing.EmotionAnalysis;
                import com.xiaoyiyuan.study.wellbeing.EmotionLog;
                import java.time.LocalDate;

                public class T9WriteProbe {
                    public static void main(String[] args) {
                        EmotionLog log = EmotionLog.parse(
                                "## 2026-10-05（周一）\\n\\n- 情绪：平静 ｜ 强度：3\\n- 备注：探针\\n");
                        EmotionAnalysis.build(PromptRole.COUNSELOR,
                                new AnalysisWindow(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10)),
                                log);
                        EmotionAnalysis.Result.parse("观察到的模式：甲");
                        EmotionAnalysis.renderEntries(log.entries());
                        System.out.println("T9-OK");
                    }
                }
                """;
        Path runnerFile = runnerDir.resolve("T9WriteProbe.java");
        Files.writeString(runnerFile, runnerSource, StandardCharsets.UTF_8);

        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", classpath, runnerFile.toString());
        builder.directory(emptyCwd.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES);

        assertTrue(finished, "子 JVM 没有在 2 分钟内结束；输出：\n" + output);
        assertEquals(0, process.exitValue(),
                "子 JVM 里跑分析这一段落本身就失败了（这条测试要证明的是「跑完了但没写文件」）；输出：\n"
                        + output);
        assertTrue(output.contains("T9-OK"), "子 JVM 没有跑到最后一步；输出：\n" + output);

        assertEquals(Set.of(), snapshot(emptyCwd),
                "在空的子 JVM 工作目录里跑完分析之后，那里必须还是一个空目录"
                        + "（spec §6.4：分析结果不写回情绪记录文件）。多出来的："
                        + snapshotDifference(Set.of(), snapshot(emptyCwd)));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Test
    void analysisTypes_haveNoPathInTheirPublicSurface() {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        for (Class<?> type : List.of(AnalysisWindow.class, EmotionAnalysis.class,
                EmotionAnalysis.Result.class)) {
            for (Method method : type.getDeclaredMethods()) {
                scanned++;
                collectIfPath(offenders, type, "方法 " + method.getName() + " 的返回值",
                        method.getGenericReturnType());
                for (Type parameter : method.getGenericParameterTypes()) {
                    collectIfPath(offenders, type, "方法 " + method.getName() + " 的参数", parameter);
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

        assertTrue(offenders.isEmpty(),
                "情绪分析的公开面里不得出现 java.nio.file.Path（spec §6.4：分析结果不写回情绪记录文件）；"
                        + "命中：" + offenders);
        // 扫描面本身要非空：上面若因开关写错而一个成员都没扫到，这条也得红
        assertTrue(scanned > 0, "反射扫描没有覆盖到任何成员");
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

    /** 递归快照（相对路径集合）；单层快照会漏掉写进子目录的文件。 */
    private static Set<String> snapshot(Path directory) throws IOException {
        try (Stream<Path> entries = Files.walk(directory)) {
            return entries.filter(entry -> !entry.equals(directory))
                    .map(entry -> directory.relativize(entry).toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String snapshotDifference(Set<String> before, Set<String> after) {
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        return "新增：" + added + "；消失：" + removed;
    }

    private static void collectIfPath(List<String> offenders, Class<?> owner, String where, Type type) {
        String name = type.getTypeName();
        if (name.contains("java.nio.file") || name.contains("java.io.File")) {
            offenders.add(owner.getSimpleName() + " 的 " + where + " → " + name);
        }
    }
}
