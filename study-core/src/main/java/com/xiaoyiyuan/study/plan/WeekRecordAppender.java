package com.xiaoyiyuan.study.plan;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 周记录的追加与读取（spec §5.7：「追加为主，绝不覆盖历史周记录」）。
 *
 * <h2>为什么整类都是「纯字符串进出」</h2>
 * 用户的档案里有六周手写历史，里面混着 LaTeX、加粗、嵌套列表和 HTML 注释。<b>追加一周必须只
 * 增加字节，不能重排已有字节</b>。所以本类不经过 {@link PlanDocument}、不建块模型、不重新生成
 * 任何一个块，只在字符串的<b>一个下标</b>处插入：插入点之前的前缀与之后的原文都逐字节照抄。
 * 「重新生成整篇」的写法在结构上就能通过「把用户的排版顺手规范化一下」而毁掉历史，
 * 而插入式的写法<b>根本表达不出</b>这种破坏——想改别的字节得先写出别的代码。
 *
 * <h2>已存在的同一周：拒绝，不覆盖也不重复</h2>
 * {@link #appendWeek(String, int)} 发现该周已有标题就抛 {@link IllegalStateException}（消息里
 * 带周序号），把选择权交回界面：提示「这一周已有记录，是否编辑」。理由有两条，且都很实际：
 * <ul>
 *   <li><b>覆盖</b>会删掉用户手写的复盘（那些字没有第二个副本）；</li>
 *   <li><b>静默追加第二份</b>会造出两个「第 4 周」，之后 {@link #parseWeeks(String)} 读出来
 *       两份都不知道该信哪份——合并/覆盖的判断只能由用户来做，不能由工具替他想。</li>
 * </ul>
 *
 * <h2>边界</h2>
 * 行由 {@code '\n'} 切分，与分析层（{@code MarkdownParser}）同一套口径；引用式换行
 * （{@code '\r'}）当内容对待，不认识的东西一律不碰——读取保真优先于规范化。标题匹配与插入点
 * 判定都只用于<b>判断</b>，判出来的下标无论在哪，落盘的都是原文片段。
 *
 * <p>全部方法 {@code public static}：界面与后续任务在别的包里消费它。
 */
public final class WeekRecordAppender {

    /**
     * 周记录标题的行首形态：{@code ### 日期 – 日期（第 N 周…）}。
     *
     * <p>三个容忍都是有意的，全部来自用户档案的真实写法：
     * <ul>
     *   <li>{@code {4}} 与 {@code {2}}：日期写 2026-10-05 还是 20261005 都认；</li>
     *   <li>{@code [–—-]}：档案用短破折号 {@code –}（U+2013），但用户手打时可能是连字符；</li>
     *   <li>{@code [（(]} 与 {@code [）)]}：全角/半角括号都认。</li>
     * </ul>
     *
     * <p>但<b>不放松到「一行里出现『第 4 周』就算第 4 周」</b>：那样正文里「第 2 周第 1 晚（9/21）
     * 补上」这种句子会让 {@link #hasWeek(String, int)} 直接为真，于是 App 拒绝追加一个其实并不
     * 存在的周——最难查的一类错。所以必须同时满足：行首三级标题 + 两个完整日期 + 括号里的周序号，
     * 且日期必须解析得出来、有日期必须写完整。
     */
    private static final Pattern WEEK_HEADING = Pattern.compile(
            "^###\\s*(\\d{4}-\\d{2}-\\d{2})\\s*[–—-]\\s*(\\d{4}-\\d{2}-\\d{2})\\s*[（(]\\s*第\\s*(\\d+)\\s*周.*[）)]\\s*$");

    /**
     * 周记录章节标题的<b>正体字</b>（两处都用它：找插入点、判断插入点之前是不是真的在章节里）。
     *
     * <p>写成常量而不是把 {@code "周记录"} 内联进判断里，是为了让「章节标题换了名字」这件事
     * 只有一处要改。真换了名字，本类的每一次插入都会失败退出（见 {@link #requireWeeklySection}），
     * 而不是悄悄插到一篇没有周记录章节的文档里。
     */
    private static final String WEEKLY_SECTION_HEADING = "## 九、周记录（每周追加，不覆盖）";

    /** 新一周模板的四行；与档案 §六 各周的写法逐字一致（全局约束要求固定文案，UI 不得改写）。 */
    private static final List<String> TEMPLATE_BULLETS =
            List.of("- 本周计划：", "- 实际完成：", "- 卡住的点：", "- 下周行动：");

    private WeekRecordAppender() {
    }

    /**
     * 读出文档里所有周记录，按文件顺序。
     *
     * <p>每条记录的 {@code heading} 是标题整行原文（不含换行符），{@code bullets} 是紧随其后、
     * 到下一个三级标题 / 二级标题 / 文末之前的全部 {@code - } 行（含 {@code "- "} 前缀），
     * <b>同样不含行尾换行符</b>。缩进的行（如 {@code "  - **9/21 周一…"}）不算 {@code - } 行，
     * 因此第 2 周那种「实际完成」下面挂子条目、以及 {@code "  - 待修：…"} 这种形态不会被误当成
     * 并列项；它们仍然是文件内容，只是不属于 bullets 列表——本方法只读不写，误判也不会造成字节变化。
     *
     * <p><b>为什么必须去掉行尾换行符</b>：调用方几乎一定会用 {@code bullets().contains("…")}
     * 来判断「这一周有没有写某一项」，而 {@code List.contains} 走的是 {@code equals}——
     * 带着 {@code "\n"} 的字符串永远不等于用户看到的那一行，于是「明明写了却查不到」。
     * （这不是理论：本任务的第一版实现就留着换行符，测试里的
     * {@code bullets().contains("- 实际完成：进行中")} 因此失败。注意 {@code String.contains}
     * 是<b>子串</b>判断，它会照旧返回 true，所以这个坑不能靠「换个方法试试」发现。）
     * 这里只剥掉 {@code '\n'} / {@code '\r'} 这两种行尾符，行内空白一格不动。
     *
     * <p>日期字段取<b>标题里写的日期</b>而不是「按周序号反推」：两者不一致时（用户手改过）必须
     * 如实反映文件，而不是替用户「修正」。
     */
    public static List<WeekRecord> parseWeeks(String markdown) {
        List<String> lines = linesOf(Objects.requireNonNull(markdown, "markdown"));
        List<WeekRecord> weeks = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = headingMatcher(lines.get(i));
            if (m == null) {
                continue;
            }
            // 标题行本身不算 bullet；从紧跟的下一行开始收，遇到下一个标题就停
            List<String> bullets = new ArrayList<>();
            for (int j = i + 1; j < lines.size(); j++) {
                String line = lines.get(j);
                if (headingMatcher(line) != null || isSectionHeading(line)) {
                    break;
                }
                if (isBullet(line)) {
                    bullets.add(withoutLineEnding(line));
                }
            }
            weeks.add(new WeekRecord(
                    weekNumberOf(m),
                    LocalDate.parse(m.group(1)),
                    LocalDate.parse(m.group(2)),
                    withoutLineEnding(lines.get(i)),
                    bullets));
        }
        return List.copyOf(weeks);
    }

    /** 去掉 {@code line} 末尾的 {@code '\n'} / {@code '\r'}；只动行尾符，行内与其它空白原样保留。 */
    private static String withoutLineEnding(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }

    /**
     * 该周是否已有标题。<b>只看周序号</b>，不看日期：一周的身份是周序号，「标题里的日期写错了」
     * 应该由界面提示去编辑那一周，而不是让 App 在同一周里再插一份。
     */
    public static boolean hasWeek(String markdown, int weekNumber) {
        for (String line : linesOf(Objects.requireNonNull(markdown, "markdown"))) {
            Matcher m = headingMatcher(line);
            if (m != null && weekNumberOf(m) == weekNumber) {
                return true;
            }
        }
        return false;
    }

    /**
     * 追加第 {@code weekNumber} 周的空模板，返回<b>新</b>文本；入参字符串一个字符都不改。
     *
     * <p>插入点固定两条规则（先写清白，代码里也只有这一处实现）：
     * <ol>
     *   <li>周记录章节标题之后遇到的下一个二级标题（{@code ## }）之前——也就是 §十 之前；</li>
     *   <li>没有下一个二级标题时，插到<b>文档末尾</b>（整篇最后一个字符之后），并保证结果仍以换行结尾。</li>
     * </ol>
     *
     * <p><b>两条规则共同保证的性质</b>：新周块<b>末尾总是一个空行</b>。插在二级标题之前时它把新周
     * 与下一节分开；插到文档末尾时，文档本来那个收尾空行承担同样的作用。少了它，新周最后一行
     * （{@code "- 下周行动："}）会和后面的 {@code ## } 标题落进同一个块——块边界只由空行产生
     * （全局约束），所以这不是排版偏好，是块的正确性。
     *
     * <p>本章节标题之前必须能定位到<b>那一个</b>章节标题（{@link #requireWeeklySection}）：
     * 找不到就报错退出。这是为了让「插入点」永远落在周记录章节<b>里面</b>，而不是靠
     * {@code lastIndexOf} 命中某篇根本没有这一节的文档里的别处。
     *
     * @return 新文本（未改动历史字节；新周块与前后文之间各留一个空行，标题下空一行，四行模板紧随其后）
     * @throws IllegalStateException 该周已有记录（消息含周序号），或文档里没有周记录章节标题
     * @throws IllegalArgumentException 周序号不是正数
     */
    public static String appendWeek(String markdown, int weekNumber) {
        Objects.requireNonNull(markdown, "markdown");
        if (weekNumber <= 0) {
            throw new IllegalArgumentException("周序号必须是正数（第 1 周锚点是 "
                    + WeekCalendar.ANCHOR_MONDAY + "），收到：" + weekNumber + "。");
        }
        if (hasWeek(markdown, weekNumber)) {
            throw new IllegalStateException("第 " + weekNumber + " 周已经有记录了，"
                    + "不做重复追加、也不覆盖既有内容——请改为编辑已有的「第 " + weekNumber + " 周」记录。");
        }

        List<String> lines = linesOf(markdown);
        int anchorLine = requireWeeklySection(lines);
        int insertAt = insertionPointIndex(lines, anchorLine);
        String prefix = join(lines, 0, insertAt);
        String suffix = join(lines, insertAt, lines.size());

        // 插在某个二级标题之前时，前缀末尾已经是空行（含「最后一个换行符」），所以这里只需拼上
        // 新周块本身——块内部自带收尾空行（见 weekBlock），于是「下一个 `## ` 标题之前那一行必是
        // 空行」这条性质成立。注意：这个空行在插入块内部，动不到前缀，「插入点之前逐字节相同」
        // 不受影响。
        String insertion = insertAt < lines.size()
                ? weekBlock(weekNumber)
                : endOfFileBlock(prefix, weekNumber);
        return prefix + insertion + suffix;
    }

    // =====================================================================
    // 插入点（只在这里判定；appendWeek 只调用它一次）
    // =====================================================================

    /**
     * 定位「周记录」章节标题所在行；找不到就报错。
     *
     * <p>从文档末尾往前找，取<b>最后一个</b>完全等于「周记录」章节标题的行。若一份都没有，本方法
     * 直接失败——宁可报错也不把新周插到别的章节里（例如 {@code ## 十、待确认} 之后）。
     * 若用户复制粘贴出了两份周记录章节，取后面那份：插入点仍然落在<b>某一个</b>周记录章节内部，
     * 而不是靠 {@code lastIndexOf} 去猜一个「大概是这里」的位置。
     *
     * @throws IllegalStateException 文档里没有周记录章节标题
     */
    private static int requireWeeklySection(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).strip().equals(WEEKLY_SECTION_HEADING)) {
                return i;
            }
        }
        throw new IllegalStateException("文档里找不到周记录章节标题（`" + WEEKLY_SECTION_HEADING
                + "`）：周记录只能追加进这一节，找不到就不追加——凭空插到别处等于改写了不属于本模型的内容。");
    }

    /**
     * 插入点在 {@code lines} 里的下标（即「插入后新块从第几行开始」）。
     *
     * <p>规则只有两条，见 {@link #appendWeek(String, int)} 的说明。第二条的「文档末尾」是
     * <b>整篇最后一个字符之后</b>（返回 {@code lines.size()}），而不是「最后一个非空行之后」：
     * 后者会插到文档已有的收尾空行<b>之前</b>，把新周夹在正文与那些空行中间。按字面插到最末尾，
     * 文档本来那个收尾空行就自然成了新周与后文之间的分隔——不额外补空行，也不覆盖它。
     */
    private static int insertionPointIndex(List<String> lines, int anchorLine) {
        for (int i = anchorLine + 1; i < lines.size(); i++) {
            if (isSectionHeading(lines.get(i))) {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * 插到文末时用的文本：新周块 + 保证结果仍以换行结尾。
     *
     * <p>只可能补<b>一个</b>换行符，且只在文档最后一行缺行尾符时补（例如文末是
     * {@code "…- 下周行动："} 而没有收尾换行）。这里刻意<b>不</b>再补一个空行：文末本来就没有空行
     * 时，新周块自己的收尾空行已经把它与前文分开；再补一个就会凭空多出一行，让「插到末尾」的
     * 结果和「插在某个二级标题之前」的结果长得不一样。
     */
    private static String endOfFileBlock(String prefix, int weekNumber) {
        String newlineIfMissing = prefix.endsWith("\n") ? "" : "\n";
        return newlineIfMissing + weekBlock(weekNumber);
    }

    /**
     * 新一周的文本块：标题行 + 空行 + 四行模板 + <b>一个收尾空行</b>；日期一律来自
     * {@link WeekCalendar}。
     *
     * <p><b>为什么末尾必须是一个空行</b>：块边界只由零字符空行产生（全局约束），所以少了这个空行，
     * 新周的最后一行 {@code "- 下周行动："} 就会和紧跟的 {@code ## 十、待确认} 挤在同一个块里——
     * 下游按块读的人会把「周记录的最后一项」和「下一节的标题」看成一回事。文件自身的写法也是这样
     * （查档案：每周的项目行之后都留一个空行）。
     *
     * <p>这个空行<b>不会</b>影响「插入点之前逐字节相同」：它在插入块内部，前缀一个字符都没动。
     */
    private static String weekBlock(int weekNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append(headingOf(weekNumber)).append('\n').append('\n');
        for (String bullet : TEMPLATE_BULLETS) {
            sb.append(bullet).append('\n');
        }
        return sb.append('\n').toString();
    }

    /**
     * 新一周的标题行（不含换行符），例如
     * {@code ### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）}。
     *
     * <p>日期区间<b>必须</b>由 {@link WeekCalendar#weekRangeLabel(int)} 生成：项目里已经有过一次
     * 「界面与档案各写一种周标题」的教训，两处各拼一次迟早会对不上。
     */
    private static String headingOf(int weekNumber) {
        return "### " + WeekCalendar.weekRangeLabel(weekNumber)
                + "（第 " + weekNumber + " 周 ｜ 周一–周日）";
    }

    // =====================================================================
    // 行扫描与匹配（只用于判断；判断结果从不参与改写既有字节）
    // =====================================================================

    /**
     * 按 {@code '\n'} 切成「保留行尾换行符」的行；与分析层同口径（不 trim、不丢行尾、不规范化）。
     *
     * <p>于是「各行原样拼起来 == 输入」永远成立，插入时把前缀、插入块、后缀直接相接即可，
     * 中间不经过任何重建步骤。
     */
    private static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** 拼接 {@code [from, to)} 区间；只拼接，不加任何修饰。 */
    private static String join(List<String> lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * 二级标题行判据：{@code strip()} 后以 {@code "## "} 开头——与 {@link PlanDocument} 的章节
     * 边界口径一致（{@code "##"} 后面必须有空格，所以 {@code "###..."} 不会误命中）。
     */
    private static boolean isSectionHeading(String line) {
        return line.strip().startsWith("## ");
    }

    /** 列表项行判据：{@code "- "} 恰好从行首开始（缩进行是子条目，不算并列项）。 */
    private static boolean isBullet(String line) {
        return line.startsWith("- ");
    }

    /**
     * 该行若是周记录标题就返回带组的匹配器，否则返回 {@code null}。
     *
     * <p><b>日期非法时返回 {@code null}（当作「不是周标题」）而不是抛出</b>，例如
     * {@code ### 2026-13-45 – …（第 9 周）}。追加路径上没有理由被这一行拦住（它只是内容），
     * 而 {@code hasWeek} 也不会误判成「这一周已经有了」——那会无端拒绝一次合法追加。
     * 代价是这种坏行不会被 {@link #parseWeeks(String)} 读成记录：它只是<b>读不到</b>，
     * 不是「读出来一个日期错乱的对象」。本方法只做判断，绝不改动任何字节。
     */
    private static Matcher headingMatcher(String line) {
        Matcher m = WEEK_HEADING.matcher(line.strip());
        if (!m.matches()) {
            return null;
        }
        try {
            LocalDate.parse(m.group(1));
            LocalDate.parse(m.group(2));
        } catch (DateTimeParseException e) {
            return null;
        }
        return m;
    }

    /** 匹配器里的周序号；只有 {@link #WEEK_HEADING} 的匹配器会传进来。 */
    private static int weekNumberOf(Matcher headingMatcher) {
        return Integer.parseInt(headingMatcher.group(3));
    }
}
