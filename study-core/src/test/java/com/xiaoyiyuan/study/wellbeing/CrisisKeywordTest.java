package com.xiaoyiyuan.study.wellbeing;

import com.xiaoyiyuan.study.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 危机兜底：本地关键词匹配（spec §6.5）。
 *
 * <p><b>本类钉住两条「错误实现下会红」的核心性质：</b>
 * <ol>
 *   <li><b>判定完全来自本地关键词表。</b>把关键词表换成只含「香蕉」，真实的危机语句就
 *       不再命中——任何「把判定交给网络/AI」或「判定写死在代码常量里」的实现都过不了
 *       这一条。</li>
 *   <li><b>命中不阻断任何后续流程。</b>{@code detect} 命中之后照常返回信号，界面照常渲染
 *       AI 分析；判定层没有、也不许有任何停止/拒绝/锁定的方法，命中后也不抛异常。</li>
 * </ol>
 *
 * <p>另有四条独立的防回归网：不做否定排除是<b>有意为之</b>（防止后人当 bug「修好」）、
 * 面板数据中性（无禁用词、无诊断性标签）、匹配大小写不敏感、输入与文件格式的边界。
 */
class CrisisKeywordTest {

    // ================= 性质一：判定走本地关键词匹配 =================

    /** 明显信号必须命中；同时钉住命中的是哪一个词——「命中了但说不清因为哪个词」不算证据。 */
    @Test
    void builtinKeywords_triggerOnObviousSignals() {
        CrisisDetector d = CrisisDetector.builtIn();

        CrisisSignal a = d.detect("我不想活了");
        assertTrue(a.triggered(), "「我不想活了」必须命中；实际：" + a);
        assertTrue(a.matchedKeywords().contains("不想活"),
                "命中的必须包含「不想活」；实际命中词：" + a.matchedKeywords());

        CrisisSignal b = d.detect("我想自杀");
        assertTrue(b.triggered(), "「我想自杀」必须命中；实际：" + b);
        assertTrue(b.matchedKeywords().contains("自杀"),
                "命中的必须包含「自杀」；实际命中词：" + b.matchedKeywords());
    }

    /**
     * 别人的危机也是危机：第三人称转述同样命中。
     *
     * <p>命中词必须是<b>关键词表里的字面</b>，而不是输入里的一句话——面板的措辞由界面渲染，
     * 判定层不产出任何用户可见文案。
     */
    @Test
    void thirdPartyMention_alsoTriggers() {
        CrisisSignal s = CrisisDetector.builtIn().detect("朋友说他撑不下去了");
        assertTrue(s.triggered(), "第三人称转述必须命中；实际：" + s);
        assertTrue(s.matchedKeywords().contains("撑不下去"),
                "命中的必须是关键词表的字面「撑不下去」；实际命中词：" + s.matchedKeywords());
    }

    /**
     * 关键词表在磁盘上，换了文件就必须换判定——<b>这一条把「判定交给 AI」或「判定写死在代码里」
     * 钉死</b>。
     *
     * <p>文件只含「香蕉」「撑不住」，于是：「我想吃香蕉」命中、「撑不住」也命中，
     * 而真正的危机语句「我想自杀」<b>不</b>命中。
     */
    @Test
    void keywordFileFromDisk_isUsed_andOverridesBuiltin(@TempDir Path dir) throws IOException {
        Path f = writeKeywordFile(dir, "香蕉\n撑不住\n");
        CrisisDetector d = CrisisDetector.load(f);

        assertTrue(d.detect("我想吃香蕉").triggered(), "文件里的词必须生效");
        assertTrue(d.detect("我撑不住了").triggered(),
                "文件里的「撑不住」必须生效（内置表里是「撑不下去」，两者不同）");
        assertFalse(d.detect("我想自杀").triggered(),
                "文件里没有「自杀」，就不该命中——判定必须来自本地文件");
        assertEquals(Set.of("香蕉", "撑不住"), d.keywords(), "关键词集合就是文件内容");
    }

    /** 文件不存在 → 回退内置表，且不抛异常（全新安装、用户误删都要能启动）。 */
    @Test
    void missingKeywordFile_fallsBackToBuiltin_andDoesNotThrow(@TempDir Path dir) {
        CrisisDetector d = assertDoesNotThrow(
                () -> CrisisDetector.load(dir.resolve("根本没有这个文件.txt")),
                "关键词表缺失不得让加载失败");
        assertTrue(d.detect("我不想活了").triggered(), "回退后内置词必须仍生效");
        assertTrue(d.detect("我想自杀").triggered(), "回退后内置词必须仍生效");
        assertFalse(d.detect("今天做了两小时数学，还行").triggered(), "回退不该把所有文本都判为命中");
    }

    /** 路径指向目录（不是文件）也要能回退，而不是抛 IOException。 */
    @Test
    void keywordPathIsDirectory_fallsBackToBuiltin(@TempDir Path dir) throws IOException {
        Path asDir = Files.createDirectory(dir.resolve("这是个目录"));
        CrisisDetector d = assertDoesNotThrow(() -> CrisisDetector.load(asDir),
                "路径不是普通文件时必须回退内置表");
        assertTrue(d.detect("我想自杀").triggered(), "回退后内置词必须仍生效");
    }

    // ================= 性质二：命中不阻断 =================

    /**
     * <b>命中之后一切照常</b>：同一个检测器继续可用，命中不抛异常、不返回空，
     * 也不改变后续调用的行为。
     *
     * <p>spec §6.5「关键决定：不阻断 AI」落到判定层就是这一条。若有人给 {@code detect}
     * 加上「命中即抛异常 / 拒绝继续」，或者让命中后的调用行为变化，本方法当场变红。
     */
    @Test
    void triggered_doesNotBlockOrThrow_flowContinues() {
        CrisisDetector d = CrisisDetector.builtIn();

        CrisisSignal crisis = assertDoesNotThrow(() -> d.detect("我想自杀"),
                "命中危机词不得抛异常——抛异常就等于阻断了后续流程");

        assertNotNull(crisis, "命中时必须返回一个信号，而不是空");
        assertFalse(crisis.matchedKeywords().isEmpty(), "命中却不给命中词，界面无从解释中性面板的由来");

        // 命中之后，普通输入与再次命中都必须照常工作：判定层不持有「已锁定」这类状态。
        assertNotNull(d.detect("今天做了两小时数学，还行"), "命中后仍须能继续判定");
        assertTrue(d.detect("我又想起自杀这件事").triggered(), "命中后再次命中仍须照常返回信号");
        assertFalse(d.detect("今天做了两小时数学，还行").triggered(), "命中后普通文本仍不该命中");
        assertFalse(crisis.matchedKeywords().isEmpty(), "信号不得被后续调用改成空");
    }

    /**
     * 判定层<b>不给</b>任何「阻止继续」的 API：{@code CrisisSignal} 的字段就两个，
     * 没有任何一扇门可以让界面据此锁死流程。
     *
     * <p>这是对「不得提供阻止继续的 API」的静态钉法：日后有人加 {@code boolean blocked()}、
     * {@code void lock()} 之类，本方法立刻红。
     */
    @Test
    void signalExposesNoRefusalSurface() {
        List<String> fields = java.util.Arrays.stream(CrisisSignal.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).sorted().toList();
        assertEquals(List.of("matchedKeywords", "triggered"), fields,
                "信号只能有「是否命中」与「命中哪些词」两个字段；实际：" + fields);

        for (String name : allPublicMethodNames(CrisisDetector.class)) {
            assertFalse(isRefusal(name), "判定层不得提供任何阻止继续的 API，却发现方法：" + name);
        }
        for (String name : allPublicMethodNames(CrisisSignal.class)) {
            assertFalse(isRefusal(name), "信号不得提供任何阻止继续的 API，却发现方法：" + name);
        }
    }

    /** 拒绝动词清单本身先自检，免得 isRefusal 被改成恒假后上面的扫描变成空转。 */
    @Test
    void refusalHelper_matchesOnlyRefusalVerbs() {
        assertTrue(isRefusal("block"), "block 必须被认成拒绝动作");
        assertTrue(isRefusal("abortAiAnalysis"), "包含 abort 的方法名必须被认成拒绝动作");
        assertFalse(isRefusal("builtIn"), "builtIn 不是拒绝动作");
        assertFalse(isRefusal("matchedKeywords"), "matchedKeywords 不是拒绝动作");
        assertFalse(isRefusal("detect"), "detect 本身不是拒绝动作");
    }

    // ================= 有意为之：不做否定语境排除 =================

    /**
     * <b>「我不想自杀」同样命中，这是有意为之，不是 bug。</b>
     *
     * <p>spec §6.5：不做否定语境排除。误报的代价只是一个中性面板，漏报的代价可能是生命；
     * 而且「我不想自杀」这句话本身往往也是求助信号。
     *
     * <p>断言刻意写得「精确到字面」：命中词里必须原样含有「自杀」。任何「否定排除 / 白名单 /
     * 上下文判断」的改动都会让本方法变红。<b>若看到本方法失败，请改回实现，而不是改这条测试。</b>
     */
    @Test
    void negationIsNotExcluded_deliberatelyTriggers() {
        CrisisSignal s = CrisisDetector.builtIn().detect("我不想自杀，只是太累了");
        assertTrue(s.triggered(), "按 spec §6.5 不做否定排除：宁误报不漏报");
        assertFalse(s.matchedKeywords().isEmpty(), "命中必须给出命中词");
        assertTrue(s.matchedKeywords().contains("自杀"),
                "「我不想自杀」里的「自杀」必须照样命中（有意为之）；实际命中词：" + s.matchedKeywords());
    }

    /** 同义变体一律照此处理：否定句不排除。 */
    @Test
    void negationVariants_alsoTrigger() {
        CrisisDetector d = CrisisDetector.builtIn();
        assertTrue(d.detect("我从来没有想过自杀").triggered(), "「从来没有想过自杀」也必须命中");
        assertTrue(d.detect("我不会自杀的，别担心").triggered(), "「我不会自杀」也必须命中");
        assertTrue(d.detect("我才不想死").triggered(), "「我才不想死」也必须命中");
    }

    // ================= 中性：无禁用词、无诊断性标签 =================

    /**
     * 危机路径上的数据必须中性：判定层产出的命中词、以及面板默认渠道，都<b>不得</b>出现
     * 界面禁用词或诊断性标签。
     *
     * <p>禁用词：未完成 / 逾期 / 落后 / 断签 / 失败 / 警告 / 已放弃 / 未坚持（global-constraints）。
     * 诊断性标签：抑郁 / 焦虑症 / 中度 / 得分（spec §6.5 措辞约束）。
     *
     * <p>把「文本块 + 禁用词表」做成一张逐块的扫描：日后谁往面板或命中词里塞一句
     * 「警告：你的状态……」或一个「中度」标签，报错信息会直接点出是哪一块。
     */
    @Test
    void crisisOutputAndPanelDefaults_areNeutral(@TempDir Path dir) throws IOException {
        // 覆盖三条产出路径：内置表判定、磁盘表判定、以及「否定句也命中」这条最容易漂移的路径。
        for (String input : List.of("我想自杀", "我不想活了", "我不想自杀，只是太累了")) {
            CrisisSignal s = CrisisDetector.builtIn().detect(input);
            assertTrue(s.triggered(), "前置条件：这段文本必须命中：" + input);
            assertNeutral("内置表命中词（输入：" + input + "）", joined(s.matchedKeywords()));
        }

        CrisisDetector disk = CrisisDetector.load(writeKeywordFile(dir, "香蕉\n"));
        assertNeutral("磁盘表命中词", joined(disk.detect("我想吃香蕉").matchedKeywords()));

        // 面板数据：默认五条渠道就是面板上逐行渲染的东西（spec 附录 A）。
        List<CrisisResource> panel = new ArrayList<>(
                AppConfig.load(dir.resolve("不存在.properties")).crisisResources());
        assertEquals(5, panel.size(), "面板默认五条渠道，逐条都要查");
        for (CrisisResource r : panel) {
            assertNeutral("面板渠道 " + r.number(), r.label() + " " + r.number());
        }
        assertNeutral("面板全部渠道合并",
                joined(panel.stream().map(r -> r.label() + " " + r.number()).toList()));
    }

    // ================= 边界：大小写、空输入、注释与空行 =================

    /** ASCII 关键词大小写不敏感。 */
    @Test
    void matchingIsCaseInsensitive_forAsciiKeywords(@TempDir Path dir) throws IOException {
        CrisisDetector d = CrisisDetector.load(writeKeywordFile(dir, "help\n"));
        assertTrue(d.detect("HELP me").triggered(), "大写输入必须命中");
        assertTrue(d.detect("help me").triggered(), "小写输入必须命中");
        assertFalse(d.detect("今天做了两小时数学，还行").triggered());
    }

    /** {@code #} 开头是注释、空行忽略；注释文字本身不是关键词。BOM 与 CRLF 也要吃掉。 */
    @Test
    void commentsAndBlankLines_areIgnored(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("带注释的关键词.txt");
        Files.writeString(f, "\uFEFF# 这一行是注释\r\n"
                        + "\r\n"
                        + "   \r\n"
                        + "# 注释里写着自杀两个字，它不该成为关键词\r\n"
                        + "香蕉\r\n"
                        + "\r\n",
                StandardCharsets.UTF_8);
        CrisisDetector d = CrisisDetector.load(f);

        assertEquals(Set.of("香蕉"), d.keywords(), "只有「香蕉」是关键词；实际：" + d.keywords());
        assertTrue(d.detect("我想吃香蕉").triggered());
        assertFalse(d.detect("注释里写着自杀两个字，它不该成为关键词").triggered(),
                "注释行的文字不得成为关键词");
        assertFalse(d.detect("我想自杀").triggered(), "被注释掉的词不再生效");
    }

    /** 空输入、纯空白、null 的边界：前两者安全返回「未命中」，null 明确拒绝而不是静默当成空串。 */
    @Test
    void detect_neverReturnsNull_andHandlesBlankInput() {
        CrisisDetector d = CrisisDetector.builtIn();

        CrisisSignal empty = d.detect("");
        assertNotNull(empty, "空输入也必须返回信号而不是 null");
        assertFalse(empty.triggered());
        assertTrue(empty.matchedKeywords().isEmpty(),
                "空输入不该有命中词；实际：" + empty.matchedKeywords());

        assertFalse(d.detect("   ").triggered(), "纯空白按无内容处理");
        assertFalse(d.detect("\n\t \r\n").triggered(), "各类空白一律按无内容处理");

        NullPointerException npe = assertThrows(NullPointerException.class, () -> d.detect(null),
                "null 是调用方的编程错误，明确拒绝比静默当成空串好");
        assertNotNull(npe.getMessage(), "拒绝时要有可定位的消息");
    }

    /** 命中词只能是输入里的子串：判定层不做归一化重写，更不产出任何自己的文案。 */
    @Test
    void matchedKeywords_areAlwaysSubstringsOfInput() {
        CrisisDetector d = CrisisDetector.builtIn();
        for (String input : List.of("我想自杀", "我不想活了", "朋友说他撑不下去了",
                "我不想自杀，只是太累了", "今天做了两小时数学，还行")) {
            CrisisSignal s = d.detect(input);
            for (String k : s.matchedKeywords()) {
                assertTrue(input.toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT)),
                        "命中词「" + k + "」必须来自输入原文，而不是判定层生成的文案；输入：" + input);
            }
        }
    }

    /** {@code builtIn()} 必须真的把内置资源表装进来。 */
    @Test
    void builtIn_exposesTheShippedKeywordTable() {
        Set<String> kws = CrisisDetector.builtIn().keywords();
        assertTrue(kws.contains("自杀"), "内置表必须收录「自杀」；实际：" + kws);
        assertTrue(kws.contains("不想活"), "内置表必须收录「不想活」；实际：" + kws);
        assertTrue(kws.contains("撑不下去"), "内置表必须收录「撑不下去」；实际：" + kws);
        assertTrue(kws.contains("suicide"), "内置表必须收录英文变体；实际：" + kws);
        assertThrows(UnsupportedOperationException.class, () -> kws.add("新词"),
                "关键词集合必须不可变");
    }

    /**
     * 兜底链条的每一环都必须真的接上：<b>词表文件里写的词，{@code keywords()} 必须一条不少。</b>
     *
     * <p>断言是「两边内容相等」，而不是「包含某几个词」：期望值从<b>随包发布的资源文件</b>里
     * 现读现解析（同一个 {@code #} 注释 / 空行 / 去空白规则），所以日后谁往文件里加一个词、
     * 或把加载路径换成一份写死的短名单，这里当场变红，并直接点出差在哪一条。
     */
    @Test
    void builtinKeywords_matchTheShippedResourceFile() throws IOException {
        Path table = shippedKeywordTablePath();
        List<String> fromFile = parseKeywordLines(Files.readAllLines(table, StandardCharsets.UTF_8));
        assertTrue(fromFile.size() >= 30,
                "随包词表不该被削到只剩几条；实际 " + fromFile.size() + " 条，文件：" + table);
        assertTrue(fromFile.contains("自杀"), "词表文件本身必须收录「自杀」；实际：" + fromFile);
        assertTrue(fromFile.contains("一了百了"),
                "词表文件本身必须收录「一了百了」（本条专抓「内置资源没被读、只用了写死的短名单」）；实际：" + fromFile);

        assertEquals(fromFile, List.copyOf(CrisisDetector.builtIn().keywords()),
                "builtIn() 装进来的词必须与随包文件逐条一致（顺序也要一致）；文件：" + table);
    }

    /**
     * 内置表必须来自<b>资源文件</b>，而不是代码里那份写死的短名单。
     *
     * <p>「一了百了」只在资源文件里、不在 {@code LAST_RESORT} 里：只有真的读了资源，
     * 它才会命中。这一条把「资源加载被短名单顶替」这种静默失效钉死——它不会让 App 崩，
     * 只会让兜底少掉三十多个词，是最难被肉眼发现的一类退化。
     */
    @Test
    void builtinUsesResourceTable_notHardcodedLastResort() {
        CrisisDetector d = CrisisDetector.builtIn();
        assertTrue(d.keywords().contains("一了百了"),
                "「一了百了」只存在于随包资源文件里；它不在词表里就说明内置资源没被读进来。实际：" + d.keywords());

        CrisisSignal s = d.detect("这日子真是一了百了算了");
        assertTrue(s.triggered(), "资源文件里的词必须真的能触发兜底；实际：" + s);
        assertTrue(s.matchedKeywords().contains("一了百了"),
                "命中的必须是资源表里的字面「一了百了」；实际：" + s.matchedKeywords());
    }

    // ================= 工具 =================

    private static final List<String> FORBIDDEN = List.of(
            "未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持",
            "抑郁", "焦虑症", "中度", "得分");

    private static void assertNeutral(String what, String text) {
        String t = text == null ? "" : text;
        for (String bad : FORBIDDEN) {
            assertFalse(t.contains(bad),
                    "危机路径的数据必须中性，但「" + what + "」里出现了禁用/诊断性用词「" + bad
                            + "」；实际内容：" + t);
        }
    }

    private static final List<String> REFUSAL_MARKERS = List.of("block", "abort", "stop", "lock",
            "deny", "forbid", "prevent", "reject", "cancel", "interrupt", "refuse", "halt");

    private static boolean isRefusal(String methodName) {
        String n = methodName.toLowerCase(Locale.ROOT);
        return REFUSAL_MARKERS.stream().anyMatch(n::contains);
    }

    private static List<String> allPublicMethodNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                names.add(m.getName());
            }
        }
        return names;
    }

    private static String joined(List<String> parts) {
        return String.join("|", parts);
    }

    private static Path writeKeywordFile(Path dir, String content) throws IOException {
        return writeKeywordFile(dir, "keywords-" + content.hashCode() + ".txt", content);
    }

    private static Path writeKeywordFile(Path dir, String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    /**
     * 定位<b>随包发布</b>的词表源文件。
     *
     * <p>路径要兼容两种跑法：IDEA/surefire 以模块目录为工作目录（最常见），以及在仓库根跑。
     * 都找不到时直接失败，而不是悄悄跳过——「资源文件读不到」本身就是必须暴露的问题。
     */
    private static Path shippedKeywordTablePath() {
        List<Path> candidates = List.of(
                Path.of("src", "main", "resources", "crisis-keywords.txt"),
                Path.of("study-core", "src", "main", "resources", "crisis-keywords.txt"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("找不到随包词表文件，尝试过：" + candidates
                + "；当前工作目录：" + Path.of("").toAbsolutePath());
    }

    /** 与 {@code CrisisDetector} 的解析规则一致：去 BOM、跳注释与空行、去空白、折小写。 */
    private static List<String> parseKeywordLines(List<String> lines) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            String word = line.strip().toLowerCase(Locale.ROOT);
            if (word.isEmpty() || word.startsWith("#")) {
                continue;
            }
            words.add(word);
        }
        return words;
    }
}
