package com.xiaoyiyuan.study.wellbeing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 危机兜底：<b>本地</b>关键词匹配（spec §6.5）。
 *
 * <p>本类只回答一个问题——「这段话里出现了关键词表里的词吗」——并给出命中的是哪些词。
 * 它<b>不</b>生成任何用户可见文案：面板由界面渲染，且必须中性（无红色、无告警措辞、
 * 不追问、不评判）。它<b>也</b>不提供任何「阻止继续」的 API：命中不等于锁定界面，
 * 更不等于中断流程（见 {@code CrisisSignal} 的类注释）。
 *
 * <h2>为什么不由 AI 判断</h2>
 * <ul>
 *   <li>本地匹配是<b>可预期、可审计、可离线</b>的：关键词表就摆在磁盘上，用户能读到；
 *       AI 判断则取决于模型、服务状态与提示词，一次超时就等于这一次没有兜底。</li>
 *   <li>AI 会「综合上下文后认为没事」。而这里恰恰要的是不看上下文。</li>
 *   <li>AI 不可用时仍然要能兜底——危机发生在断网的时候并不特殊。</li>
 * </ul>
 *
 * <h2>为什么不排除否定语境（重要，别修）</h2>
 * <p>「我不想自杀」「我没有想过自杀」同样命中，这是<b>有意为之</b>，不是漏写的判断。
 * 理由（spec §6.5）：误报的代价只是一个<b>中性面板</b>——多一屏可以打电话的号码；
 * 漏报的代价可能是生命。而且「我不想自杀」这句话本身往往也是求助信号，
 * 恰恰是最不该被程序当作「没事」打发过去的那一种。所以这里按照「宁误报不漏报」取向，
 * 刻意<b>不</b>做否定排除，也<b>不</b>判断上下文。
 *
 * <h2>关键词表</h2>
 * <p>存放于 {@code study-core/src/main/resources/crisis-keywords.txt}，可在<b>不重新编译</b>
 * 的情况下更新（用户把它放在自己的数据目录里，{@link #load(Path)} 就会优先用它）。文件格式：
 * {@code #} 开头为注释、空行忽略、其余每行一个词，自动去空白、去 BOM、吃 CRLF。
 * 匹配时大小写不敏感（中英文都适用）。
 *
 * <h2>归一口径：先去掉空白与不可见字符，再做包含匹配</h2>
 * <p>两侧（输入与关键词）都先按同一个 {@code normalize} 口径去掉<b>空白与不可见字符</b>
 * （空格、制表符、换行、零宽空格 U+200B、零宽连接符 U+200D、LRM U+200E……），然后才做
 * {@code String.contains}。所以「自 杀」「自\u200B杀」「kill&#92;nmyself」这类被折行、空格或
 * 零宽字符切开的写法，与连写的写法命中结果完全一致。
 *
 * <p>为什么非要归一化：这些写法是<b>同一条求助信息</b>，只是字形被切开。漏掉它们的代价与
 * 漏掉整句话一样，都是静默漏报；而多做一次归一化的代价仍是「可能多一个中性面板」——
 * 与上一节「宁误报不漏报」同一取向。（输入里的零散字符仍可能凑成一个词，这一条是已知且
 * 可接受的代价：漏报的代价不对称地更高。）
 *
 * <h2>词表被清空时不静默归零</h2>
 * <p>外部文件读不到、读不动，<b>或解析后一个词都没有</b>（空文件、只剩注释）时，一律回退到
 * 内置资源表。理由与 {@code 120}/{@code 110} 的删除保护同源：<b>不能让安全兜底静默变成零</b>
 * ——App 照常启动、界面照常渲染，却再也不会提示任何求助渠道，而且这一切没有任何提示音。
 * 用户要收窄范围，删掉具体几行即可；真要「一个词都不要」，那也不是把兜底关掉的理由。
 *
 * <p><b>本类只读文件，不写任何文件</b>：它没有写入口，误用也写不坏用户的词表。
 */
public final class CrisisDetector {

    /** 内置词表在 classpath 上的位置；改它要同步改 spec §6.5 与 global-constraints。 */
    public static final String BUILT_IN_RESOURCE = "/crisis-keywords.txt";

    /**
     * 连内置资源都读不到时的<b>最后一道</b>兜底，刻意只留最短的一组。
     *
     * <p>它不是「内置表」——内置表是资源文件。它的作用是不让「jar 打包漏了资源」变成
     * 「危机兜底静默消失」：一个安全兜底不该有「什么都没兜住」这种结果。
     * 因此这里宁可写死几个词，也不要一个空的检测器。
     */
    private static final List<String> LAST_RESORT = List.of("自杀", "不想活", "撑不下去",
            "活不下去", "suicide");

    private final List<String> keywords;
    private final Set<String> keywordSet;
    /** 与 {@link #keywords} 一一对应的「匹配形态」：即同一个 {@link #normalize} 口径下的形式。 */
    private final List<String> keywordForms;

    private CrisisDetector(List<String> keywords) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(keywords));
        this.keywords = List.copyOf(ordered);
        // Set.copyOf 不保证迭代顺序，所以在有序列表之上再包一层：迭代 keywords() 时
        // 用户看到的顺序与词表文件一致（LinkedHashSet 的迭代顺序是插入顺序）。
        this.keywordSet = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
        List<String> forms = new ArrayList<>(ordered.size());
        for (String k : ordered) {
            // 词的「匹配形态」= 去掉空白与不可见字符后的形态（英文多词短语因此变成连写）。
            // 展示用的字面仍保留原文，界面才能如实说「按哪些词兜底」。
            forms.add(normalize(k));
        }
        this.keywordForms = List.copyOf(forms);
    }

    /**
     * 从外部文件加载关键词表；<b>文件不存在（或不是普通文件），或解析后一个词都没有，
     * 则回退到内置资源</b>。
     *
     * <p>回退是「没填就用默认」的语义：用户没建词表、把词表删了、或路径指向一个目录时，
     * 都应当照常兜底，而不是让 App 起不来——用户恰恰要靠 App 才能把词表改回去。
     *
     * <p><b>「文件在、内容为空」同样回退</b>（修复轮 1，评审 F2 裁定）：一份解析后为零个词的
     * 词表会让危机兜底<b>静默归零</b>——用户打开设置把文件清空（或只剩注释）之后，界面看上去
     * 一切正常，实际再也没有任何一句话能触发求助资源面板。这与 spec §12 给 {@code 120}/
     * {@code 110} 写删除保护的理由是同一个：安全兜底的条数不能无声地变成 0。
     * 因此这里与「读不到 / 读不动」走同一条回退路径；解析后非空的外来词表仍<b>完全以磁盘为准</b>
     * （见 {@code keywordFileFromDisk_isUsed_andOverridesBuiltin}）。
     *
     * <p><b>不抛异常</b>：读盘不成功（权限、非法 UTF-8……）同样回退内置资源。
     * 一个安全兜底不该因为读不到配置文件就消失。
     *
     * @param keywordFile 外部关键词表路径；{@code null} 视为「没有外部表」，直接用内置资源
     * @return 已装好词表的检测器，永不返回 {@code null}，也永不为「装 0 个词」的检测器
     */
    public static CrisisDetector load(Path keywordFile) {
        if (keywordFile == null || !Files.isRegularFile(keywordFile)) {
            return builtIn();
        }
        try {
            // 显式 UTF-8：用户可能用任意编辑器保存，词表里是中文，平台默认编码不是它。
            List<String> table = fromLines(Files.readAllLines(keywordFile, StandardCharsets.UTF_8));
            if (table.isEmpty()) {
                // 空表 / 只剩注释 / 每一行都被归一化清空：兜底不能静默归零（见本方法 javadoc）。
                return builtIn();
            }
            return new CrisisDetector(table);
        } catch (IOException | RuntimeException e) {
            return builtIn();
        }
    }

    /** 内置资源表；资源缺失时退回 {@link #LAST_RESORT}，永不返回 {@code null}。 */
    public static CrisisDetector builtIn() {
        try (InputStream in = CrisisDetector.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in == null) {
                return new CrisisDetector(LAST_RESORT);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> table = parseLines(text.lines().toList());
            return new CrisisDetector(table.isEmpty() ? LAST_RESORT : table);
        } catch (IOException | RuntimeException e) {
            return new CrisisDetector(LAST_RESORT);
        }
    }

    /**
     * 判断一段文本是否命中危机关键词（spec §6.5）。
     *
     * <p>匹配前两侧都按 {@link #normalize(String)} 归一（去掉空白与不可见字符、折小写），
     * 所以被空格、换行或零宽字符切开的写法与连写等价；<b>大小写不敏感</b>。
     * <b>不做否定排除、不做上下文判断</b>——原因见类注释，那不是遗漏。
     *
     * <p><b>命中不阻断</b>：本方法永远正常返回一个信号，不抛业务异常、不返回 {@code null}、
     * 不留下任何「已锁定」的状态。界面拿到信号后照常渲染 AI 分析，是否展示资源面板
     * 是界面的事，不是判定层的事。
     *
     * @param text 待判定文本；{@code null} 是调用方的编程错误，明确拒绝而不是静默当成空串
     * @return 判定结果，永不为 {@code null}；无命中时为「未命中 + 空命中词」
     */
    public CrisisSignal detect(String text) {
        Objects.requireNonNull(text, "text");
        // 输入先归一到「只剩可见字符」的形式；关键词在装进来时已经用同一口径归一过。
        String flat = normalize(text);
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < keywords.size(); i++) {
            String form = keywordForms.get(i);
            // form 为空串时 contains 恒真，会把普通文本误判成命中：跳过（空词解析时已丢弃）。
            if (!form.isEmpty() && flat.contains(form)) {
                // 给出的是词表里的字面（不是归一后的连写形态）：界面展示与用户排查都要用它。
                hits.add(keywords.get(i));
            }
        }
        return new CrisisSignal(!hits.isEmpty(), hits);
    }

    /**
     * 当前生效的关键词表，按文件里的顺序（{@link LinkedHashSet} 保序），不可变。
     *
     * <p>公开它有两个用处：界面可以展示「现在按哪些词兜底」，用户因此能理解误报从哪来；
     * 测试也能断言词表<b>真的</b>被装进来了，而不是一个「看起来能跑」的空检测器。
     *
     * @return 不可变的关键词集合，元素是词表里的字面（已折小写、已去掉首尾空白），
     *         顺序与词表文件一致；匹配时用的是它们各自的归一形态
     */
    public Set<String> keywords() {
        return keywordSet;
    }

    // ================= 解析 =================

    /**
     * 解析一整份词表文本（按行）：跳注释与空行、去 BOM、去首尾空白、折小写、去重保序。
     *
     * <p>BOM 交由 {@link #fromLines(List)} 逐行剥离（带 BOM 的行不一定是第一行，逐行剥更稳）。
     */
    private static List<String> parseLines(List<String> lines) {
        return fromLines(lines);
    }

    private static List<String> fromLines(List<String> lines) {
        Set<String> unique = new LinkedHashSet<>();
        for (String line : lines) {
            // 先剥 BOM 再去首尾空白：带 BOM 的首行若不剥，会变成「\uFEFF自杀」这样的词，
            // 从此永远匹配不上任何输入，而且是静默的——词表看着没问题，兜底却少一条。
            String literal = stripBom(line == null ? "" : line).strip();
            if (literal.isEmpty() || literal.startsWith("#")) {
                continue;
            }
            // 词本身夹着零宽字符或控制字符（例如「kill\u200Bmyself」）：不猜用户想要什么，
            // 跳过这一行——被静默改写的词比少一个词更难发现。词中间的普通空白是合法的
            // （正常多词短语 "kill myself" 就靠它），匹配时按 normalize 得到的连写形态比对。
            if (containsFormatOrControl(literal)) {
                continue;
            }
            unique.add(literal.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(unique);
    }

    /** 去 BOM。 */
    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    /** 这一行里有没有格式/控制字符（零宽字符、制表符等）。 */
    private static boolean containsFormatOrControl(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isFormatOrControl(cp)) {
                return true;
            }
        }
        return false;
    }

    /** 完全不可见的码点：空白 + 格式字符 + 控制字符。 */
    private static boolean isInvisible(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || isFormatOrControl(cp);
    }

    /**
     * 归一口径：去掉<b>全部不可见字符</b>（空白、零宽字符等格式字符、控制字符），再按
     * {@link Locale#ROOT} 折小写。<b>输入侧与关键词侧必须用同一个口径</b>，否则「自 杀」能命中
     * 而关键词「kill myself」永远也不命中带空格的写法。词表里的字面不被改写，只有匹配形态
     * 走这里（见构造器与 {@link #fromLines(List)}）。
     *
     * <p>按<b>码点</b>遍历：孤立代理项不该被当字符处理，成对代理项要整体保留（emoji 不能被打碎）。
     */
    private static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!isInvisible(cp)) {
                out.appendCodePoint(cp);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 格式字符或控制字符——比「不可见」窄一档：<b>不含普通空白</b>。
     *
     * <p>格式字符（{@link Character#FORMAT}）含零宽空格 U+200B、零宽连接符 U+200D、双向标记
     * LRM U+200E / RLM U+200F、软连字符 U+00AD、词连接符 U+2060、BOM U+FEFF；控制字符
     * （{@link Character#CONTROL}）含制表符 U+0009、换行、{@code \r}。
     */
    private static boolean isFormatOrControl(int cp) {
        int type = Character.getType(cp);
        return type == Character.FORMAT || type == Character.CONTROL;
    }
}
