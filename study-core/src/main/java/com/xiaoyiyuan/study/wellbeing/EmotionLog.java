package com.xiaoyiyuan.study.wellbeing;

import com.xiaoyiyuan.study.markdown.Block;
import com.xiaoyiyuan.study.markdown.MarkdownDocument;
import com.xiaoyiyuan.study.markdown.MarkdownParser;
import com.xiaoyiyuan.study.markdown.RawBlock;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code 生活与情绪记录.md} 的文件模型（spec §6.1）。
 *
 * <h2>它只处理文本</h2>
 * 本类<b>不碰文件系统</b>：没有 {@code Path}、没有 {@code Files}、不读也不写。读盘写盘是界面与
 * {@code storage} 包的事（计划 3）。把 I/O 挡在外面不是洁癖——这是整个 App 里最私密的一个文件，
 * 「什么时候落盘、落到哪里」只应该有一处决定。
 *
 * <h2>P1：不认识的东西一律不乱动</h2>
 * 整篇经 {@link MarkdownParser} 切成块，每个条目就是这个块序列里的一个块（条目与块通常一一对应，
 * 因为同一段条目内部只有单换行、没有空行，而块边界只由零字符空行产生）。干净的块回写时逐字节
 * 返回原文，所以「打开、什么都不改、写回」的结果与输入<b>完全相同</b>——头部引用块、{@code ---}、
 * 空行的个数、末尾有没有收尾换行，一个字都不动。
 *
 * <h2>降级：宁可不解析，也不能改坏</h2>
 * 一个条目块只有在「整块都是可识别的字段行、未知 {@code - } 行、空行，且恰好一个日期标题」时才算
 * 结构化条目。出现别的形状（夹了散文、标题下面是段落、强度越界……）就<b>整块降级为原样块</b>：
 * 它不出现在 {@link #entries()} 里，回写时原样输出。
 *
 * <p>为什么是「整块降级」而不是「尽力解析、把怪行丢掉」：本类无法区分「用户写的散文」与
 * 「本类还不认识的未来字段」。丢掉它，就是在用户背后删他的字——而那些字没有第二个副本。
 * 降级的代价只是<b>读不到这一条</b>（界面会把它显示成不可编辑的原文，用户能看见、能自己改）；
 * 猜错的代价是<b>失去它</b>，且往往要等到用户发现少了一段才会知道。
 *
 * <h2>非法日期与重复日期：可读地拒绝</h2>
 * <ul>
 *   <li>{@code ## 2026-13-45（周X）} 这种解析不出日期的标题抛 {@link IllegalArgumentException}，
 *       消息里带标题原文。它与上面那条<b>并不</b>矛盾：降级针对的是「这块不是我们的结构」，
 *       而这里标题明明白白是本文件的标题形态、只是日期坏了——那多半是手改出来的笔误。
 *       静默略过会让用户觉得「我明明记了，怎么不见了」，报错才能让他改回来。</li>
 *   <li>同一天已经有条目时 {@link #addEntry(EmotionEntry)} 与 {@link #replaceEntry(int, EmotionEntry)}
 *       抛 {@link IllegalStateException}（消息含日期），<b>不覆盖、也不追加第二条</b>。理由与计划 1
 *       的周记录完全相同：覆盖会删掉用户的原文，静默追加第二份则会造出两个同一天的条目，
 *       之后谁也不知道该信哪一份——合并或删除的判断只能由用户来做。</li>
 * </ul>
 *
 * <h2>脏条目会重排（spec §4.3 已接受的代价）</h2>
 * 只有被 {@link #addEntry} / {@link #replaceEntry} 改过的条目才重新生成，生成的形态是固定字段顺序
 * （未知行排在已知字段之后）。因此「手动把字段行写乱了序」的条目，一旦被 App 编辑过，内部顺序会被
 * 规范化——这是为了让文件里只有一个「App 写出来的样子」。代价被限制在那一个条目<b>内部</b>：
 * 其余字节、以及所有没被编辑过的条目，一律原样回写。
 *
 * <p><b>本类必须 public</b>：Task 9 的 {@code ai} 包与计划 3 的界面在别的包里消费它。
 */
public final class EmotionLog {

    /**
     * 新建文件的头部，逐字来自 spec §6.1。
     *
     * <p>写成字面量再交给 {@link MarkdownParser} 切块，而不是手搭几个 {@link RawBlock}：
     * 这样「App 新建的头部」与「从文件里读到的头部」走同一条切块路径，{@link #empty()} 造出来的
     * 文件与用户后来手写了一份头部再打开的文件，块结构完全一致。
     */
    private static final String HEADER = """
            # 生活与情绪记录

            > 本文件由「学习助手」App 维护，只存放在本机。
            > 记录默认不会被发送给任何 AI；每次分析前 App 都会先展示将要发送的原文。
            > 最新记录在最上方。

            ---

            """;

    /** 条目五个字段的标签（spec §6.1）；顺序就是回写的顺序，只有这一处定义。 */
    private static final String FIELD_MOOD = "情绪";
    private static final String FIELD_SLEEP = "睡眠";
    private static final String FIELD_EXERCISE = "运动";
    private static final String FIELD_EVENT = "生活事件";
    private static final String FIELD_NOTE = "备注";

    /** 字段行的前缀：{@code "- 情绪："} 这种。冒号是全角。 */
    private static final String FIELD_PREFIX = "- ";
    private static final char FIELD_SEPARATOR = '：';

    /** 全角竖线 U+FF5C：情绪与强度之间的分隔符，与主计划文件一致。 */
    private static final char FULL_WIDTH_BAR = '｜';

    /** 强度部分里的字面量，避免在正则里再写一遍中文。 */
    private static final String STRENGTH_LABEL = "强度";

    /**
     * 强度部分的写法：{@code ｜ 强度：N}，竖线两侧可以是任意个半角或全角空格。
     *
     * <p>容忍两侧空白是为了别让用户手写时的一个空格把整条记录判成「读不出来」；<b>不</b>容忍的是
     * 「没有这条竖线」。后者会把强度连同后面的字一起当成情绪词——那才是真改坏了数据。
     */
    private static final Pattern INTENSITY_IN_LINE = Pattern.compile(
            "[\\s　]*" + FULL_WIDTH_BAR + "[\\s　]*" + STRENGTH_LABEL + FIELD_SEPARATOR + "[\\s　]*(\\d+)[\\s　]*");

    /**
     * 日期标题：{@code ## yyyy-MM-dd（周X）}，括号与冒号都是<b>全角</b>，星期必须是中文单字。
     *
     * <p>括号写成全角 {@code （）} 而不是半角：文件里的标题是 {@code ## 2026-10-10（周六）}，
     * 用半角括号的正则一条都匹配不上——而那不会报错，只会让每一条记录都「悄悄降级成读不出来」。
     * 这是本任务实测踩到过的坑（第一版就是半角）。
     */
    private static final Pattern HEADING = Pattern.compile(
            "##\\s+(\\d{4}-\\d{2}-\\d{2})（周[一二三四五六日]）");

    /** 块序列（可变：增删条目就是往这里插/删元素）；{@link #render()} 才把它冻结成文档。 */
    private final List<Block> blocks;

    /** 与 {@code blocks} 里那些结构化条目块的对应关系；顺序即文件顺序（最新在前）。 */
    private final List<EntrySlot> slots;

    /**
     * 一个结构化条目在块序列里占用的一段：{@code [blockFrom, blockToExclusive)}。
     *
     * <p>用可变字段而不是一路传下标：增删条目会让后面所有块的下标平移，而「条目 ↔ 块」的对应关系
     * 是唯一的真相，漂移了就会写错地方——写错地方等于毁用户的字。
     */
    private record Region(int blockFrom, int blockToExclusive) {

        private int size() {
            return blockToExclusive - blockFrom;
        }
    }

    /**
     * 一个结构化条目：内容 + 它在文件里的位置。
     *
     * <p>{@link #dirty} 为 false 时回写<b>原文</b>而不是重新生成——这就是 P1 在本类里的落点。
     * 留着原文还有一个用处：条目被改回原样时，字节仍然与用户手写的一模一样。
     */
    private static final class EntrySlot {

        /** 当前的记录内容。 */
        private EmotionEntry entry;

        /** 未改动时回写的那段原文（含归属于本块的空行）。 */
        private final String originalText;

        /**
         * 尾部空行：重新生成时只有这一部分会被沿用。
         *
         * <p>沿用它的意义是「原文件在末条之后有没有收尾空行、有几个」不会被 App 顺手改掉；
         * 头部空行则是格式的一部分，由生成器固定产出。
         */
        private final String originalSuffix;

        /** 是否已被本类改过；false 时回写 {@link #originalText}。 */
        private boolean dirty;

        /** 本条目在块序列里的位置。 */
        private Region region;

        private EntrySlot(EmotionEntry entry, String originalText, String originalSuffix, Region region) {
            this.entry = entry;
            this.originalText = originalText;
            this.originalSuffix = originalSuffix;
            this.region = region;
        }

        private void markDirty() {
            this.dirty = true;
        }

        /** 本条目当前该输出的字节。 */
        private String render() {
            return dirty ? entry.toBlockText(originalSuffix) : originalText;
        }
    }

    private EmotionLog(List<Block> blocks, List<EntrySlot> slots) {
        this.blocks = blocks;
        this.slots = slots;
    }

    // =====================================================================
    // 读 / 新建
    // =====================================================================

    /**
     * 解析一篇情绪记录；不修改任何东西时 {@link #render()} 逐字节等于 {@code markdown}。
     *
     * @param markdown 文件全文（编码与换行符的保真由 {@code TextFileCodec} 负责，本方法只管结构）
     * @throws IllegalArgumentException 某个日期标题的日期非法（消息里带标题原文）
     */
    public static EmotionLog parse(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        List<Block> blocks = new ArrayList<>(MarkdownParser.parse(markdown).blocks());
        List<EntrySlot> slots = new ArrayList<>();

        int i = 0;
        while (i < blocks.size()) {
            if (headingOf(blockLines(blocks.get(i))) == null) {
                i++; // 头部、分隔符、散文……都不认识，原样留着
                continue;
            }
            LocalDate date = dateOfHeading(blocks.get(i));
            int end = regionEnd(blocks, i);
            EmotionEntry entry = parseEntry(date, blocks, i, end);
            if (entry != null) {
                slots.add(new EntrySlot(entry, renderRange(blocks, i, end),
                        trailingBlankLines(blocks.get(end - 1)), new Region(i, end)));
            }
            // 解析不出来就降级：不进 slots，字节留在 blocks 里原样回写
            i = end;
        }
        return new EmotionLog(blocks, slots);
    }

    /** 新建一份文件：头部 + {@code ---} + 末尾空行，没有任何条目。 */
    public static EmotionLog empty() {
        return parse(HEADER);
    }

    /** 全部条目，<b>最新在前</b>（就是文件顺序；本类从不按日期重排）。 */
    public List<EmotionEntry> entries() {
        List<EmotionEntry> entries = new ArrayList<>(slots.size());
        for (EntrySlot slot : slots) {
            entries.add(slot.entry);
        }
        return List.copyOf(entries);
    }

    /** 这一天是否已有条目。 */
    public boolean hasEntryOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        for (EntrySlot slot : slots) {
            if (slot.entry.date().equals(date)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前文件内容。没有任何改动时<b>逐字节</b>等于解析时的输入（P1）。
     *
     * <p>每次都由块序列重新拼一份 {@link MarkdownDocument}：它构造时复制一份列表，因此拼接顺序
     * 就是当下真实的块顺序——增删条目之后不需要再维护第二份状态。
     */
    public String render() {
        return new MarkdownDocument(blocks).render();
    }

    // =====================================================================
    // 写
    // =====================================================================

    /**
     * 插入一条新记录到<b>第一个 {@code ## } 之前</b>（也就是最新记录在最上方）。
     *
     * <p>插入点固定是「第一个含 {@code ## } 标题的块之前」，不是「最后一条条目的原文之后」：
     * 前者只有一个可判的句法位置，所以无论文件里有多少降级块、散文或未识别的标题，新条目都落在
     * 头部与第一条记录之间，绝不会插进用户手写的段落里。整篇没有 {@code ## } 时落到文末。
     *
     * @throws IllegalStateException    这一天已经有条目（消息含日期）；不覆盖、不重复插入
     * @throws IllegalArgumentException 条目为 null（{@code Objects.requireNonNull}）或强度越界
     */
    public void addEntry(EmotionEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (hasEntryOn(entry.date())) {
            throw new IllegalStateException(duplicateMessage(entry.date()));
        }
        int at = firstEntryBlockIndex();
        if (at == blocks.size() && at > 0) {
            // 追加到文末：末块必须以**空行**结尾，否则新条目的标题会与末块的最后一行挤进同一个块。
            // 块边界只由零字符空行产生——只补一个换行符（`---\n## …`）仍然是一块，而那一块里
            // 标题不是行首那一行，新条目就会跟着整块一起被跳过：写出的文件自己读不回来
            // （实测就是这个结果：追加之后 0 条）。末块本来就以空行结尾时一个字节都不动。
            Block last = blocks.get(at - 1);
            String text = last.render();
            if (!text.endsWith("\n\n")) {
                blocks.set(at - 1, newTextBlock(text + (text.endsWith("\n") ? "\n" : "\n\n")));
            }
        }
        // slot 的插入次序必须用「插入之前」的下标来数：blocks.add 之后同一个下标已经指向下一个块了
        int slotIndex = slotIndexOfBlock(at);
        // 分隔空行是**新条目这一块自己的**尾部空行，不是「下一条目开头少一行」。
        // 块边界只由零字符空行产生，少了它，新条目的最后一行就会与下一条的 `## ` 标题
        // 落进同一个块里——那会让两条记录都变成「有两个标题的畸形块」而降级，等于写出一条读不回来的文件。
        blocks.add(at, newTextBlock(entry.toBlockText("\n")));
        slots.add(slotIndex, new EntrySlot(entry, "", "\n", new Region(at, at + 1)));
        // 新条目之后的条目整体后移一个块
        shiftRegions(slotIndex + 1, 1);
    }

    /**
     * 用 {@code entry} 替换第 {@code index} 条（下标按 {@link #entries()} 的顺序）。
     *
     * <p>只重写这一条<b>内部</b>的字节；它在文件里的位置、尾部的空行，以及其它所有块都不动。
     *
     * @throws IllegalStateException     换成的日期与<b>另一条</b>重复（消息含日期）
     * @throws IndexOutOfBoundsException 下标越界
     */
    public void replaceEntry(int index, EmotionEntry entry) {
        Objects.requireNonNull(entry, "entry");
        // 先全部校验、再落笔：被拒绝的写入必须一个字节都不改，也不能留下半改的状态
        EntrySlot slot = slotAt(index);
        for (int i = 0; i < slots.size(); i++) {
            if (i != index && slots.get(i).entry.date().equals(entry.date())) {
                throw new IllegalStateException(duplicateMessage(entry.date()));
            }
        }
        slot.entry = entry;
        slot.markDirty();
        Region region = slot.region;
        // 先清掉这一段**全部**块，再放回重新生成的那一块：一个条目只占一个块是常态，
        // 但「只换掉第一块」会在条目占了多块时留下旧文本残骸——那是最难发现的一类损坏。
        blocks.subList(region.blockFrom(), region.blockToExclusive()).clear();
        blocks.add(region.blockFrom(), newTextBlock(slot.render()));
        slot.region = new Region(region.blockFrom(), region.blockFrom() + 1);
        // 这一段从「多块」变成了「一块」，后面的条目必须跟着前移；漏掉这一步，
        // 它们的块下标就会指向邻居的字节，下一次编辑会改坏别的记录。
        if (region.size() != 1) {
            shiftRegions(index + 1, 1 - region.size());
        }
    }

    /**
     * 删除第 {@code index} 条（下标按 {@link #entries()} 的顺序）。
     *
     * <p>连同这一段自己的字节一起删掉，之后条目的块下标整体前移。
     *
     * <p><b>这里刻意不做「保留一个空块占位」的花样。</b>曾经担心「分隔空行会被一起删掉」，实测
     * （打印块序列）不成立：{@code MarkdownParser} 把空行归给<b>它前面</b>那一块，所以首条标题
     * 之前的那个空行落在 {@code ---} 块里（{@code ---\n\n}），并不属于被删的这一段。删掉首条之后，
     * 新的首条 `## ` 标题之前仍然是一个完整空行（{@code ---\n\n## …}），重新解析照样读得回两条。
     * 保留空块反而会多出一个零字节块、让「块序列 ↔ 槽位」的对应变得不直观。
     *
     * <p>与其留一个「防御性」的复杂写法，不如把这条性质用测试钉住——
     * 见 {@code removeFirstEntry_keepsTheSurvivorsSeparatorBlankLine}。
     *
     * @throws IndexOutOfBoundsException 下标越界
     */
    public void removeEntry(int index) {
        EntrySlot slot = slotAt(index);
        Region region = slot.region;
        blocks.subList(region.blockFrom(), region.blockToExclusive()).clear();
        slots.remove(index);
        // 被删条目之后的条目整体前移（含删除首条的情形：新的首条正好落在被删掉的位置上）
        shiftRegions(index, -region.size());
    }

    /** 取第 {@code index} 条，越界就抛。所有写入口共用它，越界判定只有一处。 */
    private EntrySlot slotAt(int index) {
        if (index < 0 || index >= slots.size()) {
            throw new IndexOutOfBoundsException("条目下标越界：" + index + "（共 " + slots.size() + " 条）");
        }
        return slots.get(index);
    }

    /** 重复日期的统一说法：界面直接把它显示给用户，所以要说清「为什么不覆盖」。 */
    private static String duplicateMessage(LocalDate date) {
        return "「" + date + "」已经有一条情绪记录了，不做重复插入、也不覆盖既有内容——"
                + "请改为编辑已有的那一条。";
    }

    // =====================================================================
    // 条目边界（只在这里判定；parse / addEntry 都调它）
    // =====================================================================

    /**
     * 从条目块 {@code start} 起，本条目延伸到第几个块（不含）。
     *
     * <p>规则只有一条：<b>下一个含 {@code ## } 标题行的块</b>是边界的起点；没有就到文末。
     * 条目内部的行之间没有空行，所以「一个条目 = 一个块」是常态；但本方法不假设这一点——
     * 万一中间出现空行，那些字节仍然归这一段，绝不会被丢在条目之外。
     */
    private static int regionEnd(List<Block> blocks, int start) {
        for (int i = start + 1; i < blocks.size(); i++) {
            if (headingOf(blockLines(blocks.get(i))) != null) {
                return i;
            }
        }
        return blocks.size();
    }

    /** 新条目要插到的块下标：第一个含 {@code ## } 标题行的块；没有就插到文末。 */
    private int firstEntryBlockIndex() {
        for (int i = 0; i < blocks.size(); i++) {
            if (headingOf(blockLines(blocks.get(i))) != null) {
                return i;
            }
        }
        return blocks.size();
    }

    /** 块 {@code blockIndex} 落在第几条条目之前（用于把新 slot 插进正确的次序位置）。 */
    private int slotIndexOfBlock(int blockIndex) {
        int count = 0;
        for (EntrySlot slot : slots) {
            if (slot.region.blockFrom() < blockIndex) {
                count++;
            }
        }
        return count;
    }

    /**
     * 从第 {@code firstSlot} 条起，把各条目的块下标整体平移 {@code delta} 个块。
     *
     * <p>只有被增删位置<b>之前</b>的条目会动，之前的条目一个下标都不变——于是「头部 / 分隔符 /
     * 降级块」的位置永远不需要重算，也就不可能被算错。下标是「这一段原文在文件里的坐标」，
     * 漂移了就会写错地方，而写错地方等于毁用户的字。
     */
    private void shiftRegions(int firstSlot, int delta) {
        for (int i = firstSlot; i < slots.size(); i++) {
            EntrySlot slot = slots.get(i);
            slot.region = new Region(slot.region.blockFrom() + delta, slot.region.blockToExclusive() + delta);
        }
    }

    // =====================================================================
    // 单条解析（字段识别只有这一处；识别不了就整块降级）
    // =====================================================================

    /**
     * 把一个条目块解析成记录；不是「整块都可识别」的形状就返回 {@code null}（降级为原样块）。
     *
     * <p>逐行判定：任何一行既不是空行、不是标题、也不是可识别字段行或未知 {@code - } 行，
     * 整块就作废。<b>不做部分解析</b>——部分解析的结果是「一个看起来正常、其实少了一段字的记录」，
     * 那比读不到更危险。
     */
    private static EmotionEntry parseEntry(LocalDate date, List<Block> blocks, int start, int end) {
        String mood = null;
        Integer intensity = null;
        String sleep = null;
        String exercise = null;
        String event = null;
        String note = null;
        List<String> unknownLines = new ArrayList<>();
        int headings = 0;

        for (int b = start; b < end; b++) {
            for (String line : blockLines(blocks.get(b))) {
                if (isBlankLine(line)) {
                    continue; // 只可能出现在末块尾部；块内的空白行不构成本模型的语义
                }
                // 只剥**行首**空白：行尾的空格可能是用户写在字段值末尾的字，一行 `- 睡眠：7 小时 `
                // 用 strip() 读回来会变成「7 小时」，用户下次编辑这一条时那个空格就永远消失了。
                String bodyLine = leadingTrimmed(line);
                if (bodyLine.startsWith(EmotionEntry.HEADING_PREFIX)) {
                    headings++;
                    continue;
                }
                if (!bodyLine.startsWith(FIELD_PREFIX)) {
                    return null; // 既不是字段行也不是列表项：整块降级
                }
                String body = bodyLine.substring(FIELD_PREFIX.length());
                if (isField(body, FIELD_MOOD)) {
                    // 值取自行首剥白之后的切片、且只剥行尾换行符：值内部与值末尾的空白都留着
                    String value = withoutLineEnding(body.substring(fieldPrefixLength(FIELD_MOOD)));
                    Matcher m = INTENSITY_IN_LINE.matcher(value);
                    if (m.find()) {
                        if (m.group(1).length() > 2) {
                            return null; // 位数过多：不让 parseInt 溢出成另一个数
                        }
                        int parsed = Integer.parseInt(m.group(1));
                        if (parsed < 1 || parsed > 5) {
                            return null; // 越界的分数不修不猜，整块降级（绝不「顺手改成 5」）
                        }
                        intensity = parsed;
                        value = value.substring(0, m.start());
                    }
                    // 情绪词两侧的空白剥掉：两侧空白是分隔符的一部分，不是情绪词的内容。
                    // 这是**字段值**里唯一会吃掉空白的地方（另一处是下面 headingOf 对标题整行的
                    // strip——那里剥掉的只是行首缩进与行尾空白，标题从不参与重新生成，所以安全）；
                    // 换来的是一切写法（半角/全角空格、有没有空格）都能读成同一个情绪词，
                    // 而不是被判成「读不出来」。
                    mood = value.strip();
                } else if (isField(body, FIELD_SLEEP)) {
                    sleep = withoutLineEnding(body.substring(fieldPrefixLength(FIELD_SLEEP)));
                } else if (isField(body, FIELD_EXERCISE)) {
                    exercise = withoutLineEnding(body.substring(fieldPrefixLength(FIELD_EXERCISE)));
                } else if (isField(body, FIELD_EVENT)) {
                    event = withoutLineEnding(body.substring(fieldPrefixLength(FIELD_EVENT)));
                } else if (isField(body, FIELD_NOTE)) {
                    note = withoutLineEnding(body.substring(fieldPrefixLength(FIELD_NOTE)));
                } else {
                    // 不认识的 `- ` 行：用户在同一个文件里记的别的东西，逐字留着（只剥行首与行尾换行）
                    unknownLines.add(withoutLineEnding(bodyLine));
                }
            }
        }
        if (headings != 1 || mood == null) {
            return null; // 标题必须恰好一个；「情绪」是唯一必填字段，缺了就不算本模型的一条
        }
        return new EmotionEntry(date, mood, intensity, sleep, exercise, event, note, unknownLines);
    }

    /** 该行的冒号之前是不是字段名 {@code label}。 */
    private static boolean isField(String body, String label) {
        return body.startsWith(label)
                && body.length() > label.length()
                && body.charAt(label.length()) == FIELD_SEPARATOR;
    }

    /** 字段值在 {@code body} 里的起点：字段名 + 冒号。 */
    private static int fieldPrefixLength(String label) {
        return label.length() + 1;
    }

    // =====================================================================
    // 标题行（形状识别与日期解析分开：形状不认识 → 不是条目；形状认识但日期坏 → 报错）
    //
    // 这里只说「以 ## 开头」，日期是否合法由 dateOfHeading 判——于是 `## 只有文字` 这类形状
    // 会走到 parse 阶段并报错（带原文），而不是被当成散文悄悄略过。
    // =====================================================================

    /**
     * 该块里的 {@code ## } 标题行（已 strip）；没有就返回 {@code null}。
     *
     * <p>这里对整行用 {@code strip()}，与 {@link #parseEntry} 里只剥行首的做法<b>不同</b>，
     * 而且是有意的：标题行从不参与重新生成（脏条目按 {@code EmotionEntry#headingLabel} 重写），
     * 所以行首缩进与行尾空白被剥掉不会丢任何字节；反过来，容忍行首缩进能让「缩进了一格的标题」
     * 照常被认出来，而不是整个条目降级。字段值那边就不行——那些字节会被重新生成。
     */
    private static String headingOf(List<String> lines) {
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith(EmotionEntry.HEADING_PREFIX)) {
                return stripped;
            }
        }
        return null;
    }

    /**
     * 块里标题行的日期。
     *
     * <p>实际有三条路，按顺序：
     * <ol>
     *   <li>整行匹配 {@link #HEADING}（{@code ## yyyy-MM-dd（周X）}）→ 取捕获组里的日期；</li>
     *   <li>不匹配 → 把 {@code ## } 之后的<b>整串</b>交给 {@code LocalDate.parse}。
     *       这一步<b>不只</b>接「日期本身坏掉」的形状：{@code ## 2026-10-10} 这种<b>省略了星期</b>
     *       的写法会在这里被<b>接受</b>（整串正好是合法 ISO 日期），而 {@code ## 2026-10-10(周六)}
     *       这种半角括号、或 {@code ## 随便写点什么} 会失败并抛错。</li>
     * </ol>
     *
     * <p><b>省略星期的写法是有意接受的</b>：星期只是给人看的显示细节，日期才是条目的身份；
     * 一个用户手写漏了 `（周六）` 的文件不因此变得不可读，条目照旧能被定位、编辑、删除。
     * 半角括号这类形状仍然报错（见下），因为那说明标题确实不是本文件的形态，猜它等于替用户改字。
     *
     * <p>失败时抛 {@link IllegalArgumentException}，消息里带标题原文，绝不静默略过。
     */
    private static LocalDate dateOfHeading(Block block) {
        String heading = headingOf(blockLines(block));
        String rest = heading.substring(EmotionEntry.HEADING_PREFIX.length());
        Matcher m = HEADING.matcher(heading);
        String datePart = m.matches() ? m.group(1) : rest;
        try {
            return LocalDate.parse(datePart);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("条目日期无法解析，标题原文是 `" + heading + "`"
                    + "（期望形如 `## 2026-10-10（周六）`）。"
                    + "不猜日期、也不跳过这一条：请改成合法日期后重新打开。", e);
        }
    }

    // =====================================================================
    // 块与行的工具（只用于判断与拼接；从不改写不属于自己的字节）
    // =====================================================================

    /** 一个新造的块：原文即内容，因此它在被改动之前也是「字节保真」的。 */
    private static Block newTextBlock(String text) {
        return new RawBlock(text);
    }

    /** 块内各行（保留行尾换行符），与分析层同一口径：不 trim、不规范化换行。 */
    private static List<String> blockLines(Block block) {
        return splitKeepingLineEndings(block.render());
    }

    /** 按 {@code '\n'} 切成「保留行尾换行符」的行。 */
    private static List<String> splitKeepingLineEndings(String text) {
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

    /** 去掉行尾的 {@code '\n'} / {@code '\r'}；只动行尾符，行内空白一格不动。 */
    private static String withoutLineEnding(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }

    /**
     * 只去掉行首的空白与行尾换行符，<b>保留行尾的空格</b>。
     *
     * <p>识别字段行需要忽略行首缩进（用户可能把列表缩进了一格），但行尾的空格可能是字段值的一部分。
     * 因此这里刻意<b>不</b>用 {@code strip()}：它一次就把两端都吃掉，而两端的意义完全不同。
     */
    private static String leadingTrimmed(String line) {
        int from = 0;
        while (from < line.length() && Character.isWhitespace(line.charAt(from))) {
            from++;
        }
        return withoutLineEnding(line.substring(from));
    }

    /** 纯空行：只含换行符（或末行的空串）。只含空格/制表符的行是内容，不是空行。 */
    private static boolean isBlankLine(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** 块序列 {@code [from, to)} 区间的原文拼接。 */
    private static String renderRange(List<Block> blocks, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(blocks.get(i).render());
        }
        return sb.toString();
    }

    /** 块末尾连续的纯空行（逐字）；没有就返回空串。 */
    private static String trailingBlankLines(Block block) {
        List<String> lines = blockLines(block);
        int from = lines.size();
        while (from > 0 && isBlankLine(lines.get(from - 1))) {
            from--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
