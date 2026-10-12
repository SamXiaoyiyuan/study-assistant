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
 * <p>另有五条独立的防回归网：不做否定排除是<b>有意为之</b>（防止后人当 bug「修好」）、
 * 面板数据中性（无禁用词、无诊断性标签）、匹配大小写不敏感、输入与文件格式的边界、
 * 以及判定在「去掉空白与不可见字符」后的归一口径上做包含匹配（修复轮 1）。
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
     * <b>归一口径：判定在「去掉空白与不可见字符」之后做包含匹配</b>（修复轮 1）。
     *
     * <p>「自 杀」「自&#92;n杀」这种写法是同一条求助信息，只是被折行、空格或零宽字符切开了；
     * 不归一化就等于静默漏报。所以两侧都先去不可见字符再匹配。
     *
     * <p>注意：拆字用的字符一律由 {@link #cp(int)} 在运行期构造，<b>不</b>写成
     * {@code \\uXXXX} 字面量——Java 在词法分析<b>之前</b>就处理转义，写 {@code "\\u0020"}
     * 会直接在源码里变成一个空格，把字符串字面量劈成两半，编译不过。
     */
    @Test
    void blankAndInvisibleCharactersInsideAKeyword_stillTrigger() {
        CrisisDetector d = CrisisDetector.builtIn();

        String spaced = "自" + cp(0x20) + "杀";
        String tabbed = "自" + cp(0x09) + "杀";
        String newlined = "自" + cp(0x0A) + "杀";
        String zwsp = "自" + cp(0x200B) + "杀";
        String zwj = "自" + cp(0x200D) + "杀";
        String lrm = "自" + cp(0x200E) + "杀";
        String killAcrossLines = "kill" + cp(0x0A) + "myself";

        assertTrue(d.detect(spaced).triggered(), "空格拆字必须命中（输入：自+空格+杀）");
        assertTrue(d.detect(tabbed).triggered(), "制表符拆字必须命中（输入：自+制表符+杀）");
        assertTrue(d.detect(newlined).triggered(), "换行拆字必须命中（输入：自+换行+杀）");
        assertTrue(d.detect(zwsp).triggered(), "零宽空格拆字必须命中（U+200B）");
        assertTrue(d.detect(zwj).triggered(), "零宽连接符拆字必须命中（U+200D）");
        assertTrue(d.detect(lrm).triggered(), "LRM 拆字必须命中（U+200E）");
        assertTrue(d.detect(killAcrossLines).triggered(), "英文跨行拆词必须命中（kill+换行+myself）");

        assertTrue(d.detect(spaced).matchedKeywords().contains("自杀"),
                "命中词必须是词表里的字面「自杀」；实际：" + d.detect(spaced).matchedKeywords());
        assertTrue(d.detect(killAcrossLines).matchedKeywords().contains("kill myself"),
                "英文跨行拆词的命中词必须是词表里的字面「kill myself」；实际："
                        + d.detect(killAcrossLines).matchedKeywords());

        // 归一化不能被当成「什么都命中」：删掉空白之后仍是普通文本的输入必须继续不命中。
        assertFalse(d.detect("自 己 杀 时 间").triggered(),
                "归一化只是去掉空白：拆开的字若不构成词表里的词，仍不得命中");
    }

    /**
     * 归一化对<b>每一类</b>不可见/空白字符都成立，且覆盖词表里的每一个词——不是只对上面那几条
     * 特例有效。
     *
     * <p>对词表里的每个词：原样必须命中；用空白、制表符、换行、零宽空格、零宽连接符、LRM
     * 拆开也必须命中；英文词再加一条「全大写字形」。这一条把「归一化只做了一半」（例如只处理
     * 空格、或只归一化输入而漏掉关键词一侧）钉住。
     */
    @Test
    void normalization_coversEveryKeywordAndEveryInvisibleCharacterClass() {
        CrisisDetector d = CrisisDetector.builtIn();

        // 前置守卫：词表必须真被装进来，否则下面的循环会空转。
        assertFalse(d.keywords().isEmpty(), "内置词表不得为空");
        assertTrue(d.keywords().contains("自杀"), "前置条件：词表必须收录「自杀」");
        assertTrue(d.keywords().contains("kill myself"), "前置条件：词表必须收录英文多词短语");
        assertTrue(d.keywords().contains("一了百了"), "前置条件：词表必须收录长词");

        List<String> gaps = List.of(cp(0x20), cp(0x09), cp(0x0A), cp(0x200B), cp(0x200D), cp(0x200E));
        for (String k : d.keywords()) {
            assertTrue(d.detect(k).triggered(), "词表里的词原样必须命中：「" + k + "」");

            for (String gap : gaps) {
                String broken = String.join(gap, k.split(""));
                assertTrue(d.detect(broken).triggered(),
                        "用 U+" + Integer.toHexString(gap.charAt(0)).toUpperCase(Locale.ROOT)
                                + " 拆开的词必须命中：「" + k + "」的拆写（长度 " + broken.length() + "）");
            }
            assertTrue(d.detect(k.toUpperCase(Locale.ROOT)).triggered(),
                    "归一口径下大小写不敏感必须对每个词成立：「" + k + "」");
        }
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

    /**
     * <b>解析后词表为空时必须回退内置表</b>（修复轮 1，评审 F2）。
     *
     * <p>「清空词表」与「删掉 `120`/`110`」是同一类改动：它们都会让一条安全兜底<b>静默
     * 归零</b>——App 照常启动、界面照常渲染，只是再也不会提示任何求助渠道。词表的用途是
     * 兜底，不是表达力；用户真的要收窄范围，删除具体几行即可，不必清空整个文件。
     * 因此这里与 {@code load} 的其它失败路径一致，一律回退内置表（绝不返回「装 0 个词的
     * 检测器」）。
     */
    @Test
    void emptyKeywordFile_fallsBackToBuiltin_neverSilentlyZero(@TempDir Path dir) throws IOException {
        for (String content : List.of("", "\n", "   \n\t\n", "# 只有注释\n\n# 再来一行注释\n",
                "# 注释里写着自杀，但它被注释掉了\n")) {
            CrisisDetector d = CrisisDetector.load(writeKeywordFile(dir, content));
            assertFalse(d.keywords().isEmpty(),
                    "词表被清空/只剩注释时不得静默归零；文件内容：" + escape(content));
            assertTrue(d.detect("我想自杀").triggered(),
                    "回退后危机语句必须仍然命中；文件内容：" + escape(content));
            assertTrue(d.detect("自 杀").triggered(),
                    "回退后归一化口径也必须生效；文件内容：" + escape(content));
            assertFalse(d.detect("今天做了两小时数学，还行").triggered(),
                    "回退不等于「什么都命中」；文件内容：" + escape(content));
        }
    }

    /**
     * 与上一条配对：<b>磁盘表非空时仍以磁盘表为准</b>（回退只在解析后为空时发生，不是
     * 「总是覆盖用户的自定义」）。空表回退的改动一旦越界成「磁盘表被内置表顶替」，
     * 这一条与 {@code keywordFileFromDisk_isUsed_andOverridesBuiltin} 会一起变红。
     */
    @Test
    void nonEmptyKeywordFile_stillOverridesBuiltin(@TempDir Path dir) throws IOException {
        CrisisDetector d = CrisisDetector.load(writeKeywordFile(dir, "香蕉\n撑不住\n"));
        assertEquals(Set.of("香蕉", "撑不住"), d.keywords(),
                "磁盘表非空时必须以磁盘表为准，不得被内置表顶替");
        assertFalse(d.detect("我想自杀").triggered(), "磁盘表里没有的词不得因为内置表而命中");
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
     * <p>这是对「不得提供阻止继续的 API」的静态钉法（修复轮 1 加固，评审 F5）：
     * <ul>
     *   <li>object 面上的 public 成员<b>恰好等于白名单</b>（方法名 + public 字段名一起比），
     *       所以 {@code blocked()}、{@code lock()}、{@code refusalReason()} 这类不在动词
     *       黑名单里的新 API 也会红——黑名单要穷举，白名单不必。</li>
     *   <li>扫的是 {@code getMethods()}/{@code getFields()}（<b>含继承</b>），所以日后
     *       {@code implements SomeBlockingInterface}（接口里带着 {@code halt()}）也躲不过；
     *       原先的 {@code getDeclaredMethods()} 只看本类，继承来的拒绝 API 是隐形的。</li>
     * </ul>
     * 白名单里出现 {@code Object} 的方法（{@code getClass} 等）是 record 的固有面，不是新门。
     */
    @Test
    void signalExposesNoRefusalSurface() {
        List<String> fields = java.util.Arrays.stream(CrisisSignal.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).sorted().toList();
        assertEquals(List.of("matchedKeywords", "triggered"), fields,
                "信号只能有「是否命中」与「命中哪些词」两个字段；实际：" + fields);

        assertEquals(EXPECTED_DETECTOR_SURFACE, publicSurface(CrisisDetector.class),
                "CrisisDetector 的 public 面只允许白名单里的成员——多出任何成员（包括继承来的、"
                        + "名字不含拒绝动词的）都要先在这里登记并说明它不是「阻止继续」的入口");
        assertEquals(EXPECTED_SIGNAL_SURFACE, publicSurface(CrisisSignal.class),
                "CrisisSignal 的 public 面只允许白名单里的成员——多出任何成员都要先在这里登记");
        // 自检：把「blocked / survived」加到信号的白名单上，等值断言就恰好成立。
        assertEquals(EXPECTED_STUB_SIGNAL_SURFACE, publicSurface(StubSignal.class),
                "白名单等值断言必须能抓出「不在拒绝动词黑名单里的新方法」（blocked / survived）");

        // 白名单式扫描不是空转：继承来的、或不在动词黑名单里的新成员都必须落网。
        assertTrue(hasMethod(StubDetector.class, "halt"),
                "反射（getMethods）必须看得见继承来的 halt()——它由父类提供，StubDetector 自己"
                        + "一个方法都没声明");
        assertFalse(hasDeclaredMethod(StubDetector.class, "halt"),
                "桩自检：halt() 确实不在 StubDetector 自己声明的成员里——所以旧实现用的"
                        + "getDeclaredMethods() 看不见它（这正是评审 F5 的缺口）");
        assertTrue(publicSurface(StubDetector.class).contains("halt"),
                "publicSurface（用 getMethods()）必须把继承来的 halt() 收进来");
        assertFalse(singleNameMembers(StubSignal.class).contains("matchedKeywords"),
                "桩自检：StubSignal 只是一个普通类，不是 record，没有访问器");
        assertEquals(List.of("blocked", "none", "survived"), declaredMethodNames(StubSignal.class),
                "不在拒绝动词黑名单里的新方法（blocked / survived）也必须被 public 面等值断言抓住");
    }

    /**
     * public 面扫描器<b>不是空转</b>：它必须真的把方法名与 public 字段名都收进来，且看得见
     * 继承来的成员。这一条把「扫描器被改成恒返回空列表」这类变异钉住——否则上面的白名单
     * 等值断言会退化成「空等于空」而永不变红。
     */
    @Test
    void publicSurfaceHelper_seesMethodsAndPublicFields() {
        List<String> surface = publicSurface(CrisisDetector.class);
        assertTrue(surface.contains("detect"), "扫描器必须收进方法名；实际：" + surface);
        assertTrue(surface.contains("keywords"), "扫描器必须收进方法名；实际：" + surface);
        assertTrue(surface.contains("BUILT_IN_RESOURCE"),
                "扫描器必须收进 public 字段名，而不只是方法名；实际：" + surface);
        assertTrue(surface.contains("equals"), "扫描器必须看清 object 面上的固有不变量（继承可见）；实际：" + surface);
        assertTrue(surface.contains("halt") || !surface.isEmpty(), "扫描器不得返回空列表");
        assertTrue(singleNameMembers(StubDetector.class).contains("halt"),
                "扫描器必须看得见继承来的方法（getDeclaredMethods() 看不到）");
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
        assertEquals(List.of("自杀"), s.matchedKeywords(),
                "否定语境下命中词必须一条不少、也不多：任何「部分放行/少报一个词」的改动都会红；"
                        + "实际命中词：" + s.matchedKeywords());
    }

    /**
     * 同义变体一律照此处理：否定句不排除。
     *
     * <p>每条都<b>同时</b>钉住「触发了」<b>与</b>「命中词里就是那个词」（修复轮 1，评审 F1）：
     * 只断言 {@code triggered()} 会漏掉「触发为真但命中词少一个」这类改动——面板的解释力
     * 恰恰来自命中词，少一个词就等于用户看不到为什么弹出来。
     */
    @Test
    void negationVariants_alsoTrigger() {
        CrisisDetector d = CrisisDetector.builtIn();

        CrisisSignal a = d.detect("我从来没有想过自杀");
        assertTrue(a.triggered(), "「从来没有想过自杀」也必须命中");
        assertTrue(a.matchedKeywords().contains("自杀"),
                "「我从来没有想过自杀」必须给出命中词「自杀」；实际：" + a.matchedKeywords());

        CrisisSignal b = d.detect("我不会自杀的，别担心");
        assertTrue(b.triggered(), "「我不会自杀」也必须命中");
        assertTrue(b.matchedKeywords().contains("自杀"),
                "「我不会自杀的」必须给出命中词「自杀」；实际：" + b.matchedKeywords());

        CrisisSignal c = d.detect("我才不想死");
        assertTrue(c.triggered(), "「我才不想死」也必须命中");
        assertTrue(c.matchedKeywords().contains("想死"),
                "「我才不想死」必须给出命中词「想死」；实际：" + c.matchedKeywords());
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

    /**
     * <b>命中词必须来自关键词表，且其归一口径必须是输入归一形式的子串。</b>
     *
     * <p>比较口径在修复轮 1 改过（原因见 {@code task-3-fix1-report.md}）：判定本身现在也在
     * 归一形式上进行，所以「命中词的字面必须是<b>原始输入</b>的连续子串」不再成立——
     * 输入「自 杀」命中词是「自杀」，它不是原始输入的连续子串，但它是<b>输入产生的</b>。
     *
     * <p>新口径由两条断言组成，仍可证伪：
     * <ol>
     *   <li><b>归属</b>：每个命中词都必须是 {@code keywords()} 里的元素。若判定层用输入里的
     *       整句、或自己造一个词来当命中词，这一条红。</li>
     *   <li><b>来源</b>：命中词的归一形式必须是输入归一形式的子串。若命中词是无中生有
     *       （例如恒返回词表本身，或返回一个输入里根本没有的词），这一条红。</li>
     * </ol>
     * 另加「命中词不得为空」的守卫：命中却被清空命中词，界面就无从解释面板为何弹出。
     */
    @Test
    void matchedKeywords_alwaysComeFromTheTable_andWithinTheNormalizedInput() {
        CrisisDetector d = CrisisDetector.builtIn();
        for (String input : List.of("我想自杀", "我不想活了", "朋友说他撑不下去了",
                "我不想自杀，只是太累了", "自 杀", "自" + cp(0x200B) + "杀", "KILL MYSELF",
                "kill" + cp(0x0A) + "myself",
                "今天做了两小时数学，还行")) {
            CrisisSignal s = d.detect(input);
            assertEquals(s.triggered(), !s.matchedKeywords().isEmpty(),
                    "命中与命中词必须同步：命中却没有命中词，或没命中却有命中词。输入：" + input);
            for (String k : s.matchedKeywords()) {
                assertTrue(d.keywords().contains(k),
                        "命中词「" + k + "」必须取自关键词表，而不是判定层生成的文案；输入：" + input);
                assertTrue(collapseInvisible(input).contains(collapseInvisible(k)),
                        "命中词「" + k + "」的归一形态必须是输入归一形态的子串，否则它就不是输入产生的；"
                                + "（「kill myself」的归一形态是连写的「killmyself」，两者都用同一口径比）"
                                + "输入：" + input);
            }
        }

        // 反例：不能「命中就随便给个词表里的词」。上面「自 杀」一条已覆盖——词表里没有「自 杀」，
        // 命中词只能是「自杀」，而它必须在归一后的输入里找得到。
        assertTrue(d.detect("自 杀").matchedKeywords().contains("自杀"),
                "归一形状的输入必须给出词表里的「自杀」；实际：" + d.detect("自 杀").matchedKeywords());
        // 字面保留：英文多词短语命中时给出的是词表里的字面，不是去空格后的连写。
        assertTrue(d.detect("kill" + cp(0x0A) + "myself").matchedKeywords().contains("kill myself"),
                "命中词必须是词表字面「kill myself」（带空格），而不是连写形态；实际："
                        + d.detect("kill" + cp(0x0A) + "myself").matchedKeywords());
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

    /**
     * {@code CrisisDetector} 允许出现的 public 成员（修复轮 1，评审 F5）。
     *
     * <p>它是<b>白名单</b>：一旦多出任何 public 成员（方法或字段，含继承来的），扫描就红，
     * 逼着作者在这里显式登记「它不是阻止继续的入口」——黑名单要穷举，白名单不必。
     * {@code notify}/{@code notifyAll}/{@code wait} 是任何对象都有的固有面。
     *
     * <p>实测本 JDK 上 {@code clone}/{@code finalize} 不出现在反射结果里，故不登记；
     * 若换个 JDK 它们冒出来，扫描会红并要求登记，这是正确的失败方向（宁可要求显式登记）。
     */
    private static final List<String> EXPECTED_DETECTOR_SURFACE = List.of(
            "BUILT_IN_RESOURCE", "builtIn", "detect", "equals", "getClass", "hashCode",
            "keywords", "load", "notify", "notifyAll", "toString", "wait");

    /** {@code CrisisSignal} 允许出现的 public 成员：两个访问器 + {@code none()} 便捷判定。 */
    private static final List<String> EXPECTED_SIGNAL_SURFACE = List.of(
            "equals", "getClass", "hashCode", "matchedKeywords", "none", "notify", "notifyAll",
            "toString", "triggered", "wait");

    /** 自检桩的期望面：信号白名单 + 两个「新增 API」——用来证明扫描不是空转。 */
    private static final List<String> EXPECTED_STUB_SIGNAL_SURFACE = List.of(
            "blocked", "equals", "getClass", "hashCode", "none", "notify", "notifyAll", "survived",
            "toString", "wait");

    private static void assertNeutral(String what, String text) {
        String t = text == null ? "" : text;
        for (String bad : FORBIDDEN) {
            assertFalse(t.contains(bad),
                    "危机路径的数据必须中性，但「" + what + "」里出现了禁用/诊断性用词「" + bad
                            + "」；实际内容：" + t);
        }
    }

    /** 用码点构造一个字符——避免 {@code \\uXXXX} 在词法分析前就被展开的陷阱。 */
    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** 去掉全部不可见字符（零宽、控制、空白）并折小写——与判定层的归一口径一致。 */
    private static String collapseInvisible(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.CONTROL) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> sortedNames(List<String> names) {
        return names.stream().sorted().distinct().toList();
    }

    /** public 成员名（含继承）的去重集合，字段名与方法名合在一起——白名单等值断言的比较对象。 */
    private static List<String> publicSurface(Class<?> type) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (java.lang.reflect.Field f : type.getFields()) {
            if (java.lang.reflect.Modifier.isPublic(f.getModifiers())) {
                names.add(f.getName());
            }
        }
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && !m.isBridge() && !m.isSynthetic()) {
                names.add(m.getName());
            }
        }
        return List.copyOf(names);
    }

    /** public 方法名（含继承）的去重集合。 */
    private static List<String> singleNameMembers(Class<?> type) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && !m.isBridge() && !m.isSynthetic()) {
                names.add(m.getName());
            }
        }
        return List.copyOf(names);
    }

    /** 本类自己声明的 public 方法名（不含继承）。 */
    private static List<String> declaredMethodNames(Class<?> type) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && !m.isBridge() && !m.isSynthetic()) {
                names.add(m.getName());
            }
        }
        return List.copyOf(names);
    }

    /** 反射（含继承）能看到这个方法吗。 */
    private static boolean hasMethod(Class<?> type, String name) {
        for (java.lang.reflect.Method m : type.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 这个方法是不是本类自己声明的（不含继承）。 */
    private static boolean hasDeclaredMethod(Class<?> type, String name) {
        return declaredMethodNames(type).contains(name);
    }

    /** 反射可见性的自检桩：{@code halt()} 由父类提供，子类自己一个方法都没声明。 */
    private interface StubInterface {
        void halt();
    }

    private static class StubBase implements StubInterface {
        @Override
        public void halt() {
            // 桩：父类实现，子类的 getDeclaredMethods() 看不见它。
        }
    }

    private static final class StubDetector extends StubBase {
    }

    /** 自检桩：{@code blocked} 命中动词黑名单，{@code survived} 不在黑名单里。 */
    private static final class StubSignal {
        public boolean blocked() {
            return true;
        }

        public boolean survived() {
            return false;
        }

        public boolean none() {
            return false;
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n') {
                out.append("\\n");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
