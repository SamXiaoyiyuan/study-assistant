package com.xiaoyiyuan.study.wellbeing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 情绪记录的文件模型（spec §6.1）。
 *
 * <p>样例是<b>脱敏</b>的：全局约束要求绝不把真实情绪记录提交进仓库。
 *
 * <p>下面这几条断言是刻意写成「两种错误实现都会红」的形状：
 * <ul>
 *   <li>{@link #roundTrip_untouchedFileIsByteIdentical()} 钉住 P1：不修改就必须逐字节相同。
 *       任何「顺手收拾一下」的实现（trim 行、补末尾换行、重排字段）都会当场失败。</li>
 *   <li>{@link #unknownLines_arePreservedVerbatim()} 钉住「不认识的行是数据，不是噪音」。
 *       把未知 {@code - } 行丢掉或改写的实现会失败。</li>
 *   <li>{@link #unrecognisedContent_degradesTheWholeEntryToRaw()} 钉住「宁可不解析，不能改坏」。
 *       试图「修好」畸形条目（补字段、丢怪行）的实现会失败。</li>
 * </ul>
 */
class EmotionFileTest {

    /**
     * 与 spec §6.1 / Task 1 brief 逐字一致的一份样例，含<b>两个</b>条目。
     *
     * <p>第二个条目（2026-10-09）是刻意加的：没有它，任何「把整篇当一块」的错误实现也能通过
     * 往返断言；有了它，「未知行不丢」与「条目边界」才真正被压住。
     */
    private static final String SAMPLE = """
            # 生活与情绪记录

            > 本文件由「学习助手」App 维护，只存放在本机。
            > 记录默认不会被发送给任何 AI；每次分析前 App 都会先展示将要发送的原文。
            > 最新记录在最上方。

            ---

            ## 2026-10-10（周六）

            - 情绪：疲惫 ｜ 强度：3
            - 睡眠：7 小时
            - 运动：无
            - 生活事件：下午电路分析实验拖到六点
            - 备注：实验报告还没写，有点烦

            ## 2026-10-09（周五）

            - 情绪：平静 ｜ 强度：2
            """;

    // =====================================================================
    // 往返与头部（P1）
    // =====================================================================

    @Test
    void roundTrip_untouchedFileIsByteIdentical() {
        assertEquals(SAMPLE, EmotionLog.parse(SAMPLE).render());
    }

    @Test
    void newLog_hasHeaderAndNoEntries() {
        assertTrue(EmotionLog.empty().render().startsWith("# 生活与情绪记录"));
        assertEquals(0, EmotionLog.empty().entries().size());
    }

    @Test
    void headerBlockquote_isKeptVerbatim() {
        String header = "> 本文件由「学习助手」App 维护，只存放在本机。\n"
                + "> 记录默认不会被发送给任何 AI；每次分析前 App 都会先展示将要发送的原文。\n"
                + "> 最新记录在最上方。\n";
        assertTrue(EmotionLog.empty().render().contains(header),
                "头部引用块必须逐字在内（连行尾换行符一起）");
        // 只改一个条目之后，头部一个字节都不许动
        EmotionLog log = EmotionLog.parse(SAMPLE);
        log.replaceEntry(0, new EmotionEntry(LocalDate.of(2026, 10, 10), "开心", 5, null, null, null, null, List.of()));
        assertTrue(log.render().contains(header), "改了条目之后头部仍须逐字节相同");
    }

    // =====================================================================
    // 解析
    // =====================================================================

    @Test
    void parse_readsEveryFieldInFileOrder() {
        List<EmotionEntry> entries = EmotionLog.parse(SAMPLE).entries();
        assertEquals(2, entries.size());

        EmotionEntry first = entries.get(0);
        assertEquals(LocalDate.of(2026, 10, 10), first.date());
        assertEquals("疲惫", first.mood());
        assertEquals(3, first.intensity());
        assertEquals("7 小时", first.sleep());
        assertEquals("无", first.exercise());
        assertEquals("下午电路分析实验拖到六点", first.event());
        assertEquals("实验报告还没写，有点烦", first.note());
        assertEquals(List.of(), first.unknownLines());

        EmotionEntry second = entries.get(1);
        assertEquals(LocalDate.of(2026, 10, 9), second.date());
        assertEquals("平静", second.mood());
        assertEquals(2, second.intensity());
        assertEquals(null, second.sleep(), "没有那一行就是 null，不能是空串");
        assertEquals(null, second.exercise());
        assertEquals(null, second.event());
        assertEquals(null, second.note());
    }

    @Test
    void invalidDateHeading_throwsWithOriginalText() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EmotionLog.parse("## 2026-13-45（周X）\n\n- 情绪：平静 ｜ 强度：2\n"));
        assertTrue(ex.getMessage().contains("2026-13-45"));
    }

    // =====================================================================
    // 未知内容
    // =====================================================================

    @Test
    void unknownLines_arePreservedVerbatim() {
        String text = SAMPLE.replace("- 运动：无\n", "- 天气：晴\n- 运动：无\n");
        EmotionLog log = EmotionLog.parse(text);

        // 换行符不带进列表：调用方几乎一定会用 contains/equals 找「我写的那一行」，
        // 带着 "\n" 的字符串永远不等于用户看到的那一行（计划 1 的 WeekRecord 踩过同一个坑）。
        // 回写时那个换行符由模型自己补回来，因此下面「逐字往返」仍然成立。
        assertEquals(List.of("- 天气：晴"), log.entries().get(0).unknownLines());
        assertTrue(log.render().contains("- 天气：晴"));
        assertEquals(text, log.render(), "已知字段之外的行不许被挪动或吞掉");
    }

    @Test
    void unknownFieldLines_doNotBreakTheFollowingEntry() {
        String text = SAMPLE.replace("- 运动：无\n", "- 天气：晴\n- 咖啡：两杯\n- 运动：无\n");
        List<EmotionEntry> entries = EmotionLog.parse(text).entries();

        assertEquals(2, entries.size(), "未知行不能把后面的条目吃掉");
        assertEquals(List.of("- 天气：晴", "- 咖啡：两杯"), entries.get(0).unknownLines());

        EmotionEntry later = entries.get(1);
        assertEquals(LocalDate.of(2026, 10, 9), later.date());
        assertEquals("平静", later.mood());
        assertEquals(2, later.intensity());
        assertEquals(text, EmotionLog.parse(text).render());
    }

    @Test
    void unknownLines_areWrittenAfterTheKnownFields_inOriginalOrder() {
        // 未知行在原文里排在第 3、4 行（已知字段之间）。条目一旦被编辑过，就会按固定字段顺序
        // 重新生成（spec §4.3 已接受的「脏块会重排」代价），于是未知行落到已知字段之后——
        // 但**它们之间的相对顺序不许变**，而且一个都不许少。
        String text = SAMPLE.replace("- 运动：无\n", "- 乙：2\n- 甲：1\n- 运动：无\n");
        EmotionLog log = EmotionLog.parse(text);
        log.replaceEntry(0, log.entries().get(0)); // 只置脏，不改任何值

        String rendered = log.render();
        assertEquals(2, log.entries().size());
        assertEquals(List.of("- 乙：2", "- 甲：1"), log.entries().get(0).unknownLines());
        assertTrue(rendered.indexOf("- 备注：实验报告还没写，有点烦") < rendered.indexOf("- 乙：2"));
        assertTrue(rendered.indexOf("- 乙：2") < rendered.indexOf("- 甲：1"), "相对顺序必须保持");
        // 已知字段一个不少；没被碰过的那个条目逐字未动
        assertTrue(rendered.contains("- 情绪：疲惫 ｜ 强度：3\n- 睡眠：7 小时\n"));
        assertTrue(rendered.endsWith("## 2026-10-09（周五）\n\n- 情绪：平静 ｜ 强度：2\n"));
        assertEquals(List.of("- 乙：2", "- 甲：1"),
                EmotionLog.parse(rendered).entries().get(0).unknownLines(), "重新读回来还是那两行");
    }

    @Test
    void unrecognisedContent_degradesTheWholeEntryToRaw() {
        // 条目里夹了一段散文：整块降级为原样块——不部分解析、不修补、不丢行
        String text = SAMPLE.replace("- 运动：无\n", "- 运动：无\n\n今天状态不太好\n");
        EmotionLog log = EmotionLog.parse(text);

        assertEquals(text, log.render(), "降级块必须逐字节原样回写");
        assertEquals(1, log.entries().size(), "降级的那一条不出现在 entries() 里");
        assertEquals(LocalDate.of(2026, 10, 9), log.entries().get(0).date(), "降级不能波及后面的条目");
        assertFalse(log.hasEntryOn(LocalDate.of(2026, 10, 10)), "降级的条目解析不出来，就不算存在");
    }

    @Test
    void entrySpanningTwoBlocks_isReplacedWithoutLeavingRemnants() {
        // 同一日期下夹了一个空行：条目跨两个块（用户的备注另起一段，完全是合法文件）。
        // 替换时必须整段清掉，不能只换第一块——只换第一块会留下旧文本残骸。
        String text = """
                # 生活与情绪记录

                ---

                ## 2026-10-10（周六）

                - 情绪：疲惫 ｜ 强度：3
                - 生活事件：下午电路分析实验拖到六点

                - 备注：实验报告还没写，有点烦

                ## 2026-10-09（周五）

                - 情绪：平静 ｜ 强度：2
                """;
        EmotionLog log = EmotionLog.parse(text);
        assertEquals(2, log.entries().size(), "跨两块的条目仍是一条，且不能把后面的条目吃掉");
        assertEquals("实验报告还没写，有点烦", log.entries().get(0).note());

        log.replaceEntry(0, new EmotionEntry(LocalDate.of(2026, 10, 10), "开心", 4,
                null, null, null, null, List.of()));

        String rendered = log.render();
        assertEquals(1, countOccurrences(rendered, "## 2026-10-10"), "旧块残骸不许留下");
        assertEquals(1, countOccurrences(rendered, "- 情绪：开心 ｜ 强度：4"));
        assertFalse(rendered.contains("有点烦"), "被替换掉的那一段原文必须整段消失");
        assertEquals(1, countOccurrences(rendered, "## 2026-10-09"));
        assertEquals(2, EmotionLog.parse(rendered).entries().size(), "写出的文件必须能读回来");

        // 这一段从两块缩成一块之后，后面那一条的块下标必须跟着前移。漏掉这一步，
        // 下一次编辑就会写到邻居的字节上（这里用「再改一次第二条」把它逼出来）。
        log.replaceEntry(1, new EmotionEntry(LocalDate.of(2026, 10, 9), "低落", 2, null, null, null, null, List.of()));
        String second = log.render();
        assertEquals(1, countOccurrences(second, "## 2026-10-09"));
        assertTrue(second.contains("- 情绪：低落 ｜ 强度：2"));
        assertFalse(second.contains("- 情绪：平静"), "第二条要被改掉，说明下标确实指向它自己");
        assertTrue(second.contains("- 情绪：开心 ｜ 强度：4"), "第一条不许被误改");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    // =====================================================================
    // 写入
    // =====================================================================

    @Test
    void addEntry_insertsBeforeFirstEntry_newestFirst() {
        EmotionLog log = EmotionLog.parse(SAMPLE);
        log.addEntry(new EmotionEntry(LocalDate.of(2026, 10, 11), "平静", 2, null, null, null, null, List.of()));

        assertEquals(LocalDate.of(2026, 10, 11), log.entries().get(0).date());
        assertEquals(LocalDate.of(2026, 10, 10), log.entries().get(1).date());
        assertEquals(LocalDate.of(2026, 10, 9), log.entries().get(2).date());

        String rendered = log.render();
        assertTrue(rendered.indexOf("2026-10-11") < rendered.indexOf("2026-10-10"));
        assertTrue(rendered.startsWith("# 生活与情绪记录"), "插入点在头部之后，头部不许被挤走");
        assertTrue(rendered.contains("- 情绪：平静 ｜ 强度：2\n"), "新条目按固定字段顺序生成");

        // 脏条目重新生成之后必须仍能读回来（自洽性）
        EmotionLog reparsed = EmotionLog.parse(rendered);
        assertEquals(3, reparsed.entries().size());
        assertEquals(LocalDate.of(2026, 10, 11), reparsed.entries().get(0).date());
        assertEquals("平静", reparsed.entries().get(0).mood());
        assertEquals(2, reparsed.entries().get(0).intensity());
    }

    @Test
    void addEntry_sameDateTwice_isRejected() {
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, () -> {
            EmotionLog l = EmotionLog.parse(SAMPLE);            // SAMPLE 已含 2026-10-10
            l.addEntry(new EmotionEntry(LocalDate.of(2026, 10, 10), "开心", 5, null, null, null, null, List.of()));
        });
        assertTrue(ex2.getMessage().contains("2026-10-10"));
    }

    @Test
    void addEntry_duplicateDateLeavesTheLogUntouched() {
        EmotionLog log = EmotionLog.parse(SAMPLE);
        assertThrows(IllegalStateException.class, () -> log.addEntry(
                new EmotionEntry(LocalDate.of(2026, 10, 10), "开心", 5, null, null, null, null, List.of())));
        assertEquals(2, log.entries().size(), "被拒绝的写入不许留下半条记录");
        assertEquals(SAMPLE, log.render(), "被拒绝的写入必须一个字节都不改");
    }

    @Test
    void replaceEntry_rewritesOnlyThatEntry() {
        EmotionLog log = EmotionLog.parse(SAMPLE);
        log.replaceEntry(0, new EmotionEntry(LocalDate.of(2026, 10, 10), "开心", 5,
                "8 小时", "跑步 3 公里", "实验做完了", "轻松一点", List.of("- 天气：晴")));

        String rendered = log.render();
        assertTrue(rendered.startsWith("# 生活与情绪记录"), "头部不动");
        assertTrue(rendered.contains("- 情绪：开心 ｜ 强度：5\n"));
        assertTrue(rendered.contains("- 天气：晴\n"), "未知行排在已知字段之后");
        assertTrue(rendered.indexOf("- 备注：轻松一点") < rendered.indexOf("- 天气：晴"));
        // 另一个条目（2026-10-09）逐字不变
        assertTrue(rendered.endsWith("## 2026-10-09（周五）\n\n- 情绪：平静 ｜ 强度：2\n"));

        EmotionLog reparsed = EmotionLog.parse(rendered);
        assertEquals(2, reparsed.entries().size());
        assertEquals("开心", reparsed.entries().get(0).mood());
        assertEquals(5, reparsed.entries().get(0).intensity());
        assertEquals("8 小时", reparsed.entries().get(0).sleep());
        assertEquals("跑步 3 公里", reparsed.entries().get(0).exercise());
        assertEquals("实验做完了", reparsed.entries().get(0).event());
        assertEquals("轻松一点", reparsed.entries().get(0).note());
        assertEquals(List.of("- 天气：晴"), reparsed.entries().get(0).unknownLines());
    }

    @Test
    void removeEntry_dropsExactlyThatEntry() {
        EmotionLog log = EmotionLog.parse(SAMPLE);
        log.removeEntry(0);

        assertEquals(1, log.entries().size());
        assertEquals(LocalDate.of(2026, 10, 9), log.entries().get(0).date());
        String rendered = log.render();
        assertFalse(rendered.contains("2026-10-10"), "被删的条目一个字都不许留");
        assertTrue(rendered.startsWith("# 生活与情绪记录"));
        // 删的是首条：它前面没有「与上一条之间的分隔空行」要留，所以留下的条目逐字不变，
        // 包括它自己的收尾换行；那个被清空的块贡献零字节（不额外添一个空行）。
        assertTrue(rendered.endsWith("## 2026-10-09（周五）\n\n- 情绪：平静 ｜ 强度：2\n"),
                "留下的条目逐字不变，且文件仍以一个换行结尾");
        assertEveryHeadingFollowsABlankLine(rendered);
    }

    /** 三条目样例：用来压「删中间一条」——这是条目之间那块分隔空行最容易丢的形状。 */
    private static final String THREE_ENTRIES = """
            # 生活与情绪记录

            ---

            ## 2026-10-10（周六）

            - 情绪：疲惫 ｜ 强度：3

            ## 2026-10-09（周五）

            - 情绪：平静 ｜ 强度：2

            ## 2026-10-08（周四）

            - 情绪：低落 ｜ 强度：1
            """;

    @Test
    void removeMiddleEntry_keepsSurvivorsByteIdenticalAndSeparated() {
        EmotionLog log = EmotionLog.parse(THREE_ENTRIES);
        assertEquals(3, log.entries().size());
        log.removeEntry(1); // 删 2026-10-09

        assertEquals(2, log.entries().size());
        assertEquals(LocalDate.of(2026, 10, 10), log.entries().get(0).date());
        assertEquals(LocalDate.of(2026, 10, 8), log.entries().get(1).date());

        String rendered = log.render();
        // (a) 幸存的条目逐字节不变
        assertTrue(rendered.contains("## 2026-10-10（周六）\n\n- 情绪：疲惫 ｜ 强度：3\n"),
                "第一条内容逐字不变");
        assertTrue(rendered.contains("## 2026-10-08（周四）\n\n- 情绪：低落 ｜ 强度：1\n"),
                "第三条内容逐字不变");
        // (b) 重新解析得到的就是留下的这两条
        EmotionLog reparsed = EmotionLog.parse(rendered);
        assertEquals(2, reparsed.entries().size(), "删完必须还能读回两条");
        assertEquals(List.of(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 8)),
                reparsed.entries().stream().map(EmotionEntry::date).toList());
        // (c) 每个 `## ` 标题之前都有空行
        assertEveryHeadingFollowsABlankLine(rendered);
        assertEquals(rendered, reparsed.render(), "再往返一次必须逐字节相同");
    }

    @Test
    void removeFirstEntry_keepsTheSurvivorsSeparatorBlankLine() {
        // 这条断言才是「只清文字、留下那个空块」这个写法的**判别性**证据。
        // 分隔空行属于「被删的首条」那一块；把这一段连块一起删掉，新的首条 `## ` 标题就会
        // 直接贴在 `---` 下面（`---\n## …`），块边界消失，重新解析时整条降级 → 0 条。
        EmotionLog log = EmotionLog.parse(THREE_ENTRIES);
        log.removeEntry(0); // 删 2026-10-10

        assertEquals(2, log.entries().size());
        assertEquals(LocalDate.of(2026, 10, 9), log.entries().get(0).date());
        assertEquals(LocalDate.of(2026, 10, 8), log.entries().get(1).date());

        String rendered = log.render();
        // (a) 幸存条目逐字节不变
        assertTrue(rendered.contains("## 2026-10-09（周五）\n\n- 情绪：平静 ｜ 强度：2\n"),
                "新的首条内容逐字不变");
        assertTrue(rendered.contains("## 2026-10-08（周四）\n\n- 情绪：低落 ｜ 强度：1\n"),
                "第二条内容逐字不变");
        // (c) 每个标题之前都有空行——尤其是新的首条，它前面必须仍是 `---` 之后那个空行
        assertTrue(rendered.contains("---\n\n## 2026-10-09（周五）"),
                "`---` 与新的首条标题之间必须保留空行");
        assertEveryHeadingFollowsABlankLine(rendered);
        // (b) 重新解析得到的就是留下的这两条
        EmotionLog reparsed = EmotionLog.parse(rendered);
        assertEquals(2, reparsed.entries().size(), "删完必须还能读回两条");
        assertEquals(List.of(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 8)),
                reparsed.entries().stream().map(EmotionEntry::date).toList());
        assertEquals(rendered, reparsed.render(), "再往返一次必须逐字节相同");
    }

    /**
     * 文件末块不以空行结尾、且还没有任何条目：`addEntry` 会走「追加到文末」那条路。
     *
     * <p>两种子形状都要压——它们分别落在 {@code addEntry} 里那一个三元表达式的两个分支上：
     * <ul>
     *   <li>{@code HEADER_NO_LINE_END}：末块连换行都没有（{@code …---}），要补 {@code "\n\n"}；</li>
     *   <li>{@code HEADER_ONE_LINE_END}：末块已以单个换行结尾（{@code …---\n}），只需再补一个
     *       {@code "\n"}。</li>
     * </ul>
     * 只压其中一个，另一个分支就是一个绿着出去、其实没人守的洞（评审正是用这一条找到漏网的）。
     */
    private static final String HEADER_NO_LINE_END = """
            # 生活与情绪记录

            ---\
            """;

    private static final String HEADER_ONE_LINE_END = "# 生活与情绪记录\n\n---\n";

    @Test
    void addEntry_whenLastBlockHasNoTrailingBlankLine_stillRoundTrips() {
        // 只要文件里还有条目，addEntry 就插在第一个标题之前，走不到「追加到文末」那条路。
        // 真正会追加到文末的是**没有任何条目**、且末块不是以空行结尾的文件——例如用户手工
        // 删掉了所有记录。那时必须让末块以空行结尾，否则新条目的标题会与末块的最后一行挤进
        // 同一个块（块边界只由零字符空行产生），新条目跟着整块一起被跳过：自己写出的文件自己读不回来。
        for (String fixture : List.of(HEADER_NO_LINE_END, HEADER_ONE_LINE_END)) {
            // fixture 的形状必须自己先断言，而不是假设：形状变了这两条就没有意义了
            assertFalse(fixture.endsWith("\n\n"),
                    "样例必须以「不以空行结尾」收尾，否则压不到追加到文末那条路："
                            + fixture.replace("\n", "\\n"));
            assertTrue(fixture.startsWith("# 生活与情绪记录")
                            && (fixture.endsWith("---") || fixture.endsWith("---\n")),
                    "样例末块必须是 `---`：[" + fixture.replace("\n", "\\n") + "]");

            EmotionLog log = EmotionLog.parse(fixture);
            assertEquals(0, log.entries().size(), "样例里本来不该有条目");
            assertEquals(fixture, log.render(), "先确认这份输入本身是保真的");

            log.addEntry(new EmotionEntry(LocalDate.of(2026, 10, 11), "平静", 2, null, null, null, null, List.of()));

            String rendered = log.render();
            // 关键：断言的是「新写出的文本能被本类自己读回来」，不只是 render() 里含某个子串——
            // 一个 append 把标题写进上一个块的实现同样能「含有」那串字符，却读不回任何条目。
            EmotionLog fresh = EmotionLog.parse(rendered);
            assertEquals(1, fresh.entries().size(),
                    "追加之后的文本必须能被重新解析出 1 条：[" + rendered.replace("\n", "\\n") + "]");
            assertEquals(List.of(LocalDate.of(2026, 10, 11)),
                    fresh.entries().stream().map(EmotionEntry::date).toList());
            assertEquals("平静", fresh.entries().get(0).mood());
            assertEquals(2, fresh.entries().get(0).intensity());
            assertEquals(rendered, fresh.render(), "第二次往返逐字节相同");
            assertEveryHeadingFollowsABlankLine(rendered);
        }
    }

    /** 逐个 `## ` 标题检查：它前面必须是一个完整的空行（块边界只由空行产生）。 */
    private static void assertEveryHeadingFollowsABlankLine(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("## ")) {
                continue;
            }
            assertTrue(i > 0, "标题 `" + lines[i] + "` 不能是整个文件的第一行");
            assertEquals("", lines[i - 1], "标题 `" + lines[i] + "` 之前必须是一个空行");
        }
    }

    @Test
    void emptyFields_areOmitted() {
        // 目录里已经有一条字段齐全的记录，所以只能断言「新加的那一条里面没有这些行」。
        // 断言整篇不含 "- 睡眠：" 会误伤 SAMPLE 自己那条，那是测试写错，不是实现出错。
        EmotionLog log = EmotionLog.parse(SAMPLE);
        log.addEntry(new EmotionEntry(LocalDate.of(2026, 10, 11), "平静", 2, null, null, null, null, List.of()));

        String rendered = log.render();
        assertTrue(rendered.contains("- 情绪：平静 ｜ 强度：2\n"));
        assertEquals(1, countOccurrences(rendered, "- 睡眠："));
        assertEquals(1, countOccurrences(rendered, "- 运动："));
        assertEquals(1, countOccurrences(rendered, "- 生活事件："));
        assertEquals(1, countOccurrences(rendered, "- 备注："));
        // 块末尾那个空行是新条目这一类块自己的分隔空行（块边界只由空行产生），
        // 所以它出现在这里，而不是「下一条目少了一行」。
        assertEquals("""
                ## 2026-10-11（周日）

                - 情绪：平静 ｜ 强度：2

                """, rendered.substring(rendered.indexOf("## 2026-10-11"),
                rendered.indexOf("## 2026-10-10")), "只有填了的字段才输出那一行");

        // 读回来也必须是「没填」而不是「填了空串」——两者在文件里长得不一样
        EmotionEntry added = EmotionLog.parse(rendered).entries().get(0);
        assertEquals(null, added.sleep());
        assertEquals(null, added.exercise());
        assertEquals(null, added.event());
        assertEquals(null, added.note());
    }

    @Test
    void setStrengthOutOfRange_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmotionEntry(LocalDate.of(2026, 10, 10), "平静", 9, null, null, null, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EmotionEntry(LocalDate.of(2026, 10, 10), "平静", 0, null, null, null, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EmotionEntry(LocalDate.of(2026, 10, 10), "平静", -1, null, null, null, null, List.of()));
        // null 是允许的（用户没打分）
        assertEquals(null, new EmotionEntry(LocalDate.of(2026, 10, 10), "平静", null,
                null, null, null, null, List.of()).intensity());
    }

    @Test
    void fullWidthSeparator_isUsed() {
        assertTrue(EmotionLog.parse(SAMPLE).render().contains("｜"));   // U+FF5C

        EmotionLog log = EmotionLog.empty();
        log.addEntry(new EmotionEntry(LocalDate.of(2026, 10, 11), "疲惫", 3, null, null, null, null, List.of()));
        String rendered = log.render();
        assertTrue(rendered.contains("- 情绪：疲惫 ｜ 强度：3\n"), "分隔符必须是全角 U+FF5C，两侧各一个空格");
        assertFalse(rendered.contains("- 情绪：疲惫 | 强度：3\n"), "半角竖线不是本文件的写法");
    }

    @Test
    void hasEntryOn_isTrueForSameDate() {
        EmotionLog log = EmotionLog.parse(SAMPLE);
        assertTrue(log.hasEntryOn(LocalDate.of(2026, 10, 10)));
        assertTrue(log.hasEntryOn(LocalDate.of(2026, 10, 9)));
        assertFalse(log.hasEntryOn(LocalDate.of(2026, 10, 11)));
        assertFalse(EmotionLog.empty().hasEntryOn(LocalDate.of(2026, 10, 10)));
    }

    @Test
    void emptyLog_canTakeItsFirstEntry() {
        EmotionLog log = EmotionLog.empty();
        log.addEntry(new EmotionEntry(LocalDate.of(2026, 10, 11), "平静", 2, null, null, null, null, List.of()));

        String rendered = log.render();
        assertEquals(1, log.entries().size());
        assertTrue(rendered.startsWith("# 生活与情绪记录"));
        assertTrue(rendered.contains("## 2026-10-11（周日）"), "日期标题带中文星期");
        assertEquals(rendered, EmotionLog.parse(rendered).render(), "App 写出的文件必须能读回来");
        assertEquals(1, EmotionLog.parse(rendered).entries().size());
    }

    @Test
    void entryHeadingLabel_followsTheFileConvention() {
        assertEquals("2026-10-10（周六）", EmotionEntry.headingLabel(LocalDate.of(2026, 10, 10)));
        assertEquals("2026-10-11（周日）", EmotionEntry.headingLabel(LocalDate.of(2026, 10, 11)));
        assertEquals("2026-10-09（周五）", EmotionEntry.headingLabel(LocalDate.of(2026, 10, 9)));
    }

    @Test
    void moodWithoutStrength_isReadable() {
        String text = """
                ## 2026-10-08（周四）

                - 情绪：低落
                - 备注：没什么力气
                """;
        EmotionLog log = EmotionLog.parse(text);
        assertEquals(1, log.entries().size());
        assertEquals("低落", log.entries().get(0).mood());
        assertEquals(null, log.entries().get(0).intensity(), "没打分就是 null");
        assertEquals(text, log.render(), "不带强度的一行也要逐字节往返");
    }

    @Test
    void weekdayLessHeading_isAcceptedOnPurpose() {
        // 有意接受：星期只是显示细节，日期才是一条记录的身份。用户手写漏了 `（周四）`
        // 不该让这条记录变得「读不出来」，它照旧能被定位、编辑、删除。
        // 注意这与「非法日期」不同：`## 2026-13-45（周X）` 仍然抛错（见 invalidDateHeading…）。
        String text = """
                ## 2026-10-08

                - 情绪：低落 ｜ 强度：1
                """;
        EmotionLog log = EmotionLog.parse(text);
        assertEquals(1, log.entries().size());
        assertEquals(LocalDate.of(2026, 10, 8), log.entries().get(0).date());
        assertEquals("低落", log.entries().get(0).mood());
        assertEquals(text, log.render(), "原样往返，不改用户的标题写法");
    }

    @Test
    void strengthOutOfRangeInFile_isNotSilentlyAccepted() {
        // 文件里的 9 分：既不接受成 9，也不「顺手改成 5」——整块降级为原样块，字节不动
        String text = """
                ## 2026-10-08（周四）

                - 情绪：低落 ｜ 强度：9
                """;
        EmotionLog log = EmotionLog.parse(text);
        assertEquals(0, log.entries().size());
        assertEquals(text, log.render());
    }

    // =====================================================================
    // 三条「错误实现会红」的负面断言
    // =====================================================================

    @Test
    void fieldText_keepsInternalAndEdgeSpaces_neverTrimmed() {
        // 「修剪每一行」的实现会在这里红。空白是数据：用户写的首尾空格也是他写的字。
        // 情绪词两侧的空白是例外（它是分隔符的一部分），所以这里压的是其余四个字段。
        EmotionLog log = EmotionLog.parse(SAMPLE);
        log.addEntry(new EmotionEntry(LocalDate.of(2026, 10, 11), "平静", 2,
                "  7 小时 ", " ", "   ", "  前后都有空格  ", List.of()));

        String rendered = log.render();
        assertTrue(rendered.contains("- 睡眠：  7 小时 \n"), "行内与首尾空格一律不动");
        assertTrue(rendered.contains("- 运动： \n"), "只有一个空格也是一个字符，不是「没填」");
        assertTrue(rendered.contains("- 生活事件：   \n"));
        assertTrue(rendered.contains("- 备注：  前后都有空格  \n"));

        EmotionEntry reread = EmotionLog.parse(rendered).entries().get(0);
        assertEquals("  7 小时 ", reread.sleep());
        assertEquals(" ", reread.exercise());
        assertEquals("   ", reread.event());
        assertEquals("  前后都有空格  ", reread.note());
    }

    @Test
    void crlfFile_roundTripsByteIdentically() {
        // 全局约束：不对换行符做任何转换。CRLF 文件必须原样进来、原样出去。
        String crlf = SAMPLE.replace("\n", "\r\n");
        assertEquals(crlf, EmotionLog.parse(crlf).render());
    }

    @Test
    void model_neverTouchesTheFileSystem() {
        // 静态保证：本任务的产物只处理文本。任何 Path / 文件读写入口一旦出现，这里就红。
        for (Class<?> type : List.of(EmotionLog.class, EmotionEntry.class)) {
            for (Method m : type.getDeclaredMethods()) {
                assertFalse(Path.class.isAssignableFrom(m.getReturnType()),
                        type.getSimpleName() + "." + m.getName() + " 返回了 Path：本模型不许持有写入口");
                for (Class<?> p : m.getParameterTypes()) {
                    assertFalse(Path.class.isAssignableFrom(p),
                            type.getSimpleName() + "." + m.getName() + " 接收 Path：本模型不许持有写入口");
                }
            }
        }
        for (RecordComponent c : EmotionEntry.class.getRecordComponents()) {
            assertFalse(Path.class.isAssignableFrom(c.getType()),
                    "EmotionEntry." + c.getName() + " 是 Path：记录不该知道文件在哪");
        }
    }
}
