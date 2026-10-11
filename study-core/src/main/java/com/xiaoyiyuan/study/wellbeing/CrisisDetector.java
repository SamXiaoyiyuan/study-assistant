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
 * 它<b>不</b>生成任何用户可见文案：面板由界面渲染，且必须中性（无红色、无「警告」、
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
 * <p><b>本类只读文件，不写任何文件</b>：它没有写入口，误用也写不坏用户的词表。
 */
public final class CrisisDetector {

    /** 内置词表在 classpath 上的位置；改它要同步改 spec §6.5 与 global-constraints。 */
    public static final String BUILT_IN_RESOURCE = "/crisis-keywords.txt";

    /**
     * 连内置资源都读不到时的<b>最后一道</b>兜底，刻意只留最短的一组。
     *
     * <p>它不是「内置表」——内置表是资源文件。它的作用是不让「jar 打包漏了资源」变成
     * 「危机兜底静默消失」：一个安全兜底不该有「什么都没兜住」这种失败模式。
     * 因此这里宁可写死几个词，也不要一个空的检测器。
     */
    private static final List<String> LAST_RESORT = List.of("自杀", "不想活", "撑不下去",
            "活不下去", "suicide");

    private final List<String> keywords;
    private final Set<String> keywordSet;

    private CrisisDetector(List<String> keywords) {
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(keywords));
        this.keywords = List.copyOf(ordered);
        // Set.copyOf 不保证迭代顺序，所以在有序列表之上再包一层：迭代 keywords() 时
        // 用户看到的顺序与词表文件一致（LinkedHashSet 的迭代顺序是插入顺序）。
        this.keywordSet = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
    }

    /**
     * 从外部文件加载关键词表；<b>文件不存在（或不是普通文件）则回退到内置资源</b>。
     *
     * <p>回退是「没填就用默认」的语义：用户没建词表、把词表删了、或路径指向一个目录时，
     * 都应当照常兜底，而不是让 App 起不来——用户恰恰要靠 App 才能把词表改回去。
     *
     * <p>但「文件在、内容为空」<b>不回退</b>：空表是用户明确的「我只要我自己这几条」，
     * 静默塞回内置表会更让人意外。这一条与配置层「文件在、没有 crisis.* 键 → 回退默认」
     * 并不冲突：那里缺的是<b>键</b>，这里空的是<b>内容</b>。
     *
     * <p><b>不抛异常</b>：读盘失败（权限、非法 UTF-8……）同样回退内置资源。
     * 一个安全兜底不该因为读不到配置文件就消失。
     *
     * @param keywordFile 外部关键词表路径；{@code null} 视为「没有外部表」，直接用内置资源
     * @return 已装好词表的检测器，永不返回 {@code null}
     */
    public static CrisisDetector load(Path keywordFile) {
        if (keywordFile == null || !Files.isRegularFile(keywordFile)) {
            return builtIn();
        }
        try {
            // 显式 UTF-8：用户可能用任意编辑器保存，词表里是中文，平台默认编码不是它。
            return new CrisisDetector(fromLines(Files.readAllLines(keywordFile, StandardCharsets.UTF_8)));
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
     * <p><b>大小写不敏感</b>（两侧都按 {@link Locale#ROOT} 折小写）。
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
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String k : keywords) {
            if (lower.contains(k)) {
                hits.add(k);
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
     * @return 不可变的关键词集合，元素已折小写、已去空白
     */
    public Set<String> keywords() {
        return keywordSet;
    }

    // ================= 解析 =================

    /** 解析一整份词表文本（按行）：去 BOM、跳过注释与空行、去空白、折小写、去重保序。 */
    private static List<String> parseLines(List<String> lines) {
        List<String> raw = new ArrayList<>(lines);
        if (!raw.isEmpty()) {
            // 首个字符可能是 BOM：本机编辑器（记事本等）保存 UTF-8 时常带上它。
            // 不剥掉的话，第一行会变成「\uFEFF自杀」，这个词从此永远匹配不上任何输入，
            // 而且是静默的——词表看着没问题，兜底却少一条。
            raw.set(0, stripBom(raw.get(0)));
        }
        return fromLines(raw);
    }

    private static List<String> fromLines(List<String> lines) {
        Set<String> unique = new LinkedHashSet<>();
        for (String line : lines) {
            String word = normalize(stripBom(line));
            if (word.isEmpty() || word.startsWith("#")) {
                continue;
            }
            unique.add(word);
        }
        return List.copyOf(unique);
    }

    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    /** 去首尾空白（含 CRLF 带来的 {@code \r}）并折小写。 */
    private static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
