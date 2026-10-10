package com.xiaoyiyuan.study.plan;

import com.xiaoyiyuan.study.markdown.Block;
import com.xiaoyiyuan.study.markdown.MarkdownDocument;
import com.xiaoyiyuan.study.markdown.MarkdownParser;
import com.xiaoyiyuan.study.markdown.RawBlock;
import com.xiaoyiyuan.study.markdown.TableBlock;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把一篇 Markdown 计划文件映射成「可以逐字段编辑的结构」（spec §4.4 / §5.2–§5.5）。
 *
 * <p>它只做两件事：<b>定位</b>与<b>写入</b>。定位失败就不写（见下），写入永远只落在自己认领的
 * 那几张表上。所有未被建模的内容——标题、引用块、散文、列表、§五/§七/§八/§九/§十、LaTeX、
 * HTML 注释、围栏代码——都留在 {@link MarkdownDocument} 的原样块里，{@link #render()} 只是
 * 把它拼回来，本类<b>从不改写不属于自己的块</b>。这是「用户的散文、公式与注释不会被 App 弄坏」
 * 的唯一保证。
 *
 * <h2>章节定位</h2>
 * 按块顺序扫描，凡二级标题（{@code ## …}）都是章节边界；标题与
 * {@link #SECTION_HEADINGS} 里的完整标题<b>逐字相等</b>才算命中——也就是<b>中文序号 + 标题名
 * 双重匹配</b>（{@code ## 二、目标清单}）。只认序号或只认名字都会在用户改标题时误绑到别的节，
 * 所以两者都在同一个字符串里，改一个就定位失败。三级及更深的标题（{@code ### 2026-09-14 …}）
 * 不是章节边界。
 *
 * <p>未建模的二级标题同样会把「当前章节」清空：否则 §七 里那张与 §四 形状相同的底线表会被
 * 当成 §四 的表，用户一改 §四 就动到了 §七 的字节。
 *
 * <h2>每个建模章节恰好一次</h2>
 * 0 次或 ≥2 次都抛 {@link SectionMissingException}；标题在而下面没有可用结构表、或表头与规格
 * 不符，同样抛。理由只有一条：<b>定位不到就不知道要改哪里，宁可报错也不能猜。</b>
 *
 * <h2>写入口闸门</h2>
 * {@code TableBlock.setCell} 接受任意字符串，含裸 {@code |} 或换行的值一旦置脏重新生成，就会
 * 多切出一列或把一行断成两行——这是本模型<b>唯一</b>能把合法输入变成畸形 Markdown 的路径。
 * 因此本类的每个写入口都先过 {@link #requireCellText}：<b>拒绝</b>而不是转义（转义再解析会得到
 * 带反斜杠的另一串文本，等于偷偷改了用户写的字；而且 {@code TableBlock.splitCells} 不回解转义，
 * 转义后的往返并不是恒等）。被拒绝的写入不会碰到表格，文档一个字节都不变。
 *
 * <p>单源约定（spec §5.3）：§一 的「本学期课程」行本该是课程清单的唯一来源，§三 的表由它生成；
 * 本任务只实现「§三 的表存在就解析它」，生成逻辑留到界面层（那需要用户交互才能决定何时同步）。
 *
 * <h2>两个刻意选择</h2>
 * <ul>
 *   <li><b>结构问题在 parse 时报，记录问题在读到那一节时报</b>：{@code parse} 只负责「定位」
 *       （章节缺失 / 重复 / 没有可用表 / 表头不符），{@code goals()} 这类访问器才把行变成记录，
 *       因此某一格状态写错只会在读到 §二 时抛 {@code IllegalArgumentException}（带原文），
 *       而不是让整篇文件打不开。无论在哪一处失败，都不会写入任何内容。</li>
 *   <li><b>规格固定的字段（表头、状态）比对时忽略两侧空白，自由文本一格都不动</b>：
 *       {@code TableBlock} 重新生成时按列宽补空格，而 {@code splitCells} 只去前后各一个空格
 *       （Task 4 钉死的规则），于是本 App 自己写出的文件里，单元格会带对齐空格。
 *       若按原文严格比对表头与状态，App 会读不了自己刚保存的文件——那是自伤。
 *       自由文本仍然逐字返回（空白就是数据），已知代价见
 *       {@code PlanDocumentTest#alignmentSpacesMayBeAbsorbedIntoFreeTextValues_butFileBytesNeverChange}。</li>
 * </ul>
 *
 * <p><b>本类必须 public</b>：{@code study-desktop} 的界面与后续任务在别的包里消费它。
 */
public final class PlanDocument {

    // ---- 规格钉死的结构：五个建模章节的完整标题与各节表头 ----

    /**
     * 章节标题的完整原文（中文序号 + 标题名）。
     *
     * <p>把两者写进同一个字符串，是为了让「双重匹配」在实现上只有一种写法：比对整行。
     * 分成「先找序号再找名字」两步，迟早会有人只写其中一步。
     */
    private static final Map<PlanSection, String> SECTION_HEADINGS = Map.of(
            PlanSection.PROFILE, "## 一、我的现状",
            PlanSection.GOALS, "## 二、目标清单",
            PlanSection.COURSES, "## 三、课程（本学期）",
            PlanSection.BASELINES, "## 四、每周底线",
            PlanSection.PHASES, "## 六、阶段排期");

    /**
     * 各节结构表的表头（spec §4.4）。
     *
     * <p>校验表头是<b>硬要求</b>：行的列是按下标读的，表头对不上时按位置读就会把「优先级」当成
     * 「状态」；写回去的时候则会写错列。表头不符就整节失败，绝不按位置硬猜。
     */
    private static final Map<PlanSection, List<String>> SECTION_HEADERS = Map.of(
            PlanSection.PROFILE, List.of("项目", "内容"),
            PlanSection.GOALS, List.of("目标", "状态", "优先级", "时间窗", "说明"),
            PlanSection.COURSES, List.of("课程", "本周任务", "截止", "状态"),
            PlanSection.BASELINES, List.of("目标", "项", "量", "怎么安排", "状态"),
            PlanSection.PHASES, List.of("阶段", "时间窗", "主攻", "验收标准"));

    // 列下标：与上面的表头一一对应。表头校验保证「确实就是这几列、这个顺序」，
    // 因此这里可以用常量而不是每次去 indexOf。
    private static final int PROFILE_KEY = 0;
    private static final int PROFILE_VALUE = 1;

    private static final int GOAL_NAME = 0;
    private static final int GOAL_STATUS = 1;
    private static final int GOAL_PRIORITY = 2;
    private static final int GOAL_WINDOW = 3;
    private static final int GOAL_NOTE = 4;

    private static final int COURSE_NAME = 0;
    private static final int COURSE_TASK = 1;
    private static final int COURSE_DUE = 2;
    private static final int COURSE_STATUS = 3;

    private static final int BASELINE_GOAL = 0;
    private static final int BASELINE_ITEM = 1;
    private static final int BASELINE_AMOUNT = 2;
    private static final int BASELINE_PLAN = 3;
    private static final int BASELINE_STATUS = 4;

    private static final int PHASE_NAME = 0;
    private static final int PHASE_WINDOW = 1;
    private static final int PHASE_FOCUS = 2;
    private static final int PHASE_ACCEPTANCE = 3;

    /** 底层文档；本类只通过各块自己的编辑方法动它，从不整篇重写。 */
    private final MarkdownDocument document;

    /** 每个建模章节对应的表格块下标；parse 成功即保证五个章节各有一个。 */
    private final Map<PlanSection, Integer> tableIndexOfSection;

    private PlanDocument(MarkdownDocument document, Map<PlanSection, Integer> tableIndexOfSection) {
        this.document = document;
        this.tableIndexOfSection = tableIndexOfSection;
    }

    /**
     * 解析一篇计划文件。
     *
     * @param markdown 文件全文（读取保真由 {@code TextFileCodec} 负责，本方法不碰编码与换行符）
     * @throws SectionMissingException 任一建模章节定位失败（缺失 / 重复 / 没有可用表 / 表头不符）
     * @throws IllegalArgumentException 目标状态不是四态之一（消息里带原文，不静默降级）
     */
    public static PlanDocument parse(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        List<Block> blocks = new ArrayList<>(MarkdownParser.parse(markdown).blocks());

        // ---- 第一遍：按二级标题把块序列切成章节 ----
        PlanSection[] sectionOfBlock = new PlanSection[blocks.size()];
        Map<PlanSection, Integer> headingBlockIndex = new EnumMap<>(PlanSection.class);
        Map<PlanSection, Integer> occurrences = new EnumMap<>(PlanSection.class);
        PlanSection current = null;
        for (int i = 0; i < blocks.size(); i++) {
            for (String line : linesOf(blocks.get(i))) {
                String stripped = line.strip();
                if (!stripped.startsWith("## ")) {
                    continue; // 三级及更深标题不是章节边界
                }
                current = sectionWithHeading(stripped);
                if (current != null) {
                    occurrences.merge(current, 1, Integer::sum);
                    headingBlockIndex.putIfAbsent(current, i);
                }
            }
            sectionOfBlock[i] = current;
        }

        // ---- 第二遍：每个建模章节必须恰好一次，且必须有一张能认领的表 ----
        Map<PlanSection, Integer> tableIndex = new EnumMap<>(PlanSection.class);
        for (PlanSection section : PlanSection.values()) {
            int count = occurrences.getOrDefault(section, 0);
            if (count == 0) {
                throw new SectionMissingException(section, "文档里找不到这一节的标题。"
                        + "标题要同时匹配中文序号与标题名（本节的完整标题是 `" + SECTION_HEADINGS.get(section)
                        + "`），只改序号或只改名字都会定位失败——这是刻意的：宁可不改，也不能改错节。");
            }
            if (count > 1) {
                throw new SectionMissingException(section, "这一节的标题出现了 " + count
                        + " 次。同一个章节有两份就无法确定该改哪一份，猜错就会写错表，因此不做任何写入。");
            }
            tableIndex.put(section, locateTable(blocks, sectionOfBlock, headingBlockIndex.get(section), section));
        }
        return new PlanDocument(new MarkdownDocument(blocks), tableIndex);
    }

    /**
     * 文档当前内容。未改动时<b>逐字节</b>等于解析时的输入（P1）；只改过一张表时，只有那张表的
     * 字节变化（P2），其余部分（含未建模章节）由各自的块原样给出。
     */
    public String render() {
        return document.render();
    }

    // =====================================================================
    // 读
    // =====================================================================

    /** §一 我的现状的全部行，按文件顺序。 */
    public List<ProfileItem> profile() {
        List<ProfileItem> items = new ArrayList<>();
        for (List<String> row : table(PlanSection.PROFILE).rows()) {
            items.add(new ProfileItem(row.get(PROFILE_KEY), row.get(PROFILE_VALUE)));
        }
        return List.copyOf(items);
    }

    /**
     * §二 目标清单的全部行，按文件顺序（行序是语义，不重排）。
     *
     * <p>状态取的是去掉两侧空白后的文本，理由与
     * {@link #headerMatches(PlanSection, List)} 相同：状态必须恰好是四态之一，两侧的对齐空格是
     * {@code TableBlock} 重新生成时补上的排版，不是用户写的字。其余字段一格都不动（空白就是数据）。
     *
     * @throws IllegalArgumentException 某一行的状态不是四态之一（消息里带原文，绝不静默降级）
     */
    public List<Goal> goals() {
        List<Goal> goals = new ArrayList<>();
        for (List<String> row : table(PlanSection.GOALS).rows()) {
            goals.add(new Goal(row.get(GOAL_NAME),
                    GoalStatus.fromLabel(row.get(GOAL_STATUS).strip()),
                    row.get(GOAL_PRIORITY),
                    row.get(GOAL_WINDOW),
                    row.get(GOAL_NOTE)));
        }
        return List.copyOf(goals);
    }

    /** §三 课程（本学期）的全部行，按文件顺序。 */
    public List<Course> courses() {
        List<Course> courses = new ArrayList<>();
        for (List<String> row : table(PlanSection.COURSES).rows()) {
            courses.add(new Course(row.get(COURSE_NAME),
                    row.get(COURSE_TASK),
                    row.get(COURSE_DUE),
                    row.get(COURSE_STATUS)));
        }
        return List.copyOf(courses);
    }

    /** §四 每周底线的全部行，按文件顺序。 */
    public List<Baseline> baselines() {
        List<Baseline> baselines = new ArrayList<>();
        for (List<String> row : table(PlanSection.BASELINES).rows()) {
            baselines.add(new Baseline(row.get(BASELINE_GOAL),
                    row.get(BASELINE_ITEM),
                    row.get(BASELINE_AMOUNT),
                    row.get(BASELINE_PLAN),
                    row.get(BASELINE_STATUS)));
        }
        return List.copyOf(baselines);
    }

    /** §六 阶段排期的全部行，<b>按文件顺序</b>（阶段 A → A+ → B → C…，行序就是语义）。 */
    public List<Phase> phases() {
        List<Phase> phases = new ArrayList<>();
        for (List<String> row : table(PlanSection.PHASES).rows()) {
            phases.add(new Phase(row.get(PHASE_NAME),
                    row.get(PHASE_WINDOW),
                    row.get(PHASE_FOCUS),
                    row.get(PHASE_ACCEPTANCE)));
        }
        return List.copyOf(phases);
    }

    // =====================================================================
    // 写（每个入口都先过统一闸门）
    // =====================================================================

    /**
     * 改 §一 某一项的「内容」。
     *
     * <p>「项目」列是这一行的身份，改不了：本次接口里没有增删现状项的口子，所以换字段要等界面层
     * 提供「增行 / 删行」。
     *
     * @throws IllegalArgumentException 值含裸 {@code |} 或换行（拒绝，不改文件）
     * @throws IndexOutOfBoundsException 行下标越界
     */
    public void setProfileItem(int index, String value) {
        requireCellText("我的现状 · 内容", value);
        table(PlanSection.PROFILE).setCell(index, PROFILE_VALUE, value);
    }

    /** 改 §二 的一行。 */
    public void setGoal(int index, Goal goal) {
        writeRow(table(PlanSection.GOALS), index, goalCells(goal));
    }

    /** 在 §二 末尾追加一行。 */
    public void addGoal(Goal goal) {
        table(PlanSection.GOALS).addRow(goalCells(goal));
    }

    /** 删除 §二 的一行。 */
    public void removeGoal(int index) {
        table(PlanSection.GOALS).removeRow(index);
    }

    /** 调整 §二 的行序（{@code to} 是「移完之后所在的位置」）。 */
    public void moveGoal(int from, int to) {
        table(PlanSection.GOALS).moveRow(from, to);
    }

    /** 改 §三 的一行。 */
    public void setCourse(int index, Course course) {
        writeRow(table(PlanSection.COURSES), index, courseCells(course));
    }

    /** 在 §三 末尾追加一行。 */
    public void addCourse(Course course) {
        table(PlanSection.COURSES).addRow(courseCells(course));
    }

    /** 删除 §三 的一行。 */
    public void removeCourse(int index) {
        table(PlanSection.COURSES).removeRow(index);
    }

    /** 改 §四 的一行。 */
    public void setBaseline(int index, Baseline baseline) {
        writeRow(table(PlanSection.BASELINES), index, baselineCells(baseline));
    }

    /** 在 §四 末尾追加一行。 */
    public void addBaseline(Baseline baseline) {
        table(PlanSection.BASELINES).addRow(baselineCells(baseline));
    }

    /** 删除 §四 的一行。 */
    public void removeBaseline(int index) {
        table(PlanSection.BASELINES).removeRow(index);
    }

    /** 改 §六 的一行。 */
    public void setPhase(int index, Phase phase) {
        writeRow(table(PlanSection.PHASES), index, phaseCells(phase));
    }

    /** 在 §六 末尾追加一行。 */
    public void addPhase(Phase phase) {
        table(PlanSection.PHASES).addRow(phaseCells(phase));
    }

    /** 删除 §六 的一行。 */
    public void removePhase(int index) {
        table(PlanSection.PHASES).removeRow(index);
    }

    /** 调整 §六 的行序（spec §4.4：阶段排期是有序表，界面支持上移/下移）。 */
    public void movePhase(int from, int to) {
        table(PlanSection.PHASES).moveRow(from, to);
    }

    // =====================================================================
    // 章节定位（全部集中在这里；各访问器只经 table(PlanSection) 取表）
    // =====================================================================

    /** 取某个建模章节的表；parse 成功即保证存在，取不到说明本类被改坏了。 */
    private TableBlock table(PlanSection section) {
        Integer index = tableIndexOfSection.get(section);
        if (index == null) {
            throw new IllegalStateException("内部错误：章节「" + section.label() + "」没有定位到表格"
                    + "（parse 成功时五个建模章节各应有一张表）");
        }
        return (TableBlock) document.blocks().get(index);
    }

    /** 标题行命中的章节；「中文序号 + 标题名」的双重匹配就发生在这一次整行比对里。 */
    private static PlanSection sectionWithHeading(String strippedLine) {
        for (PlanSection section : PlanSection.values()) {
            if (strippedLine.equals(SECTION_HEADINGS.get(section))) {
                return section;
            }
        }
        return null;
    }

    /**
     * 在章节范围内找出这一节的结构表，返回它在块序列里的下标。
     *
     * <p>只在这个章节的范围内找：遇到下一个二级标题（建模或未建模）就停。
     */
    private static int locateTable(List<Block> blocks, PlanSection[] sectionOfBlock,
                                   int headingIndex, PlanSection section) {
        // ① 第一张被 MarkdownParser 认出来的表（连续 ≥3 行、各行列数一致）
        for (int i = headingIndex; i < blocks.size() && sectionOfBlock[i] == section; i++) {
            if (blocks.get(i) instanceof TableBlock table) {
                requireHeader(section, table);
                return i;
            }
        }
        // ② 只有「表头 + 分隔行」的空表：MarkdownParser 的门槛是连续 ≥3 行，认不出它，它会作为
        //    原样块留下。这种表仍然属于本节，就地升级成 TableBlock（原文逐字节照抄，未改动时
        //    render() 仍返回原文，因此 P1 不受影响）；否则「删掉最后一行 → 保存 → 重新打开」
        //    会直接失败——App 写出的文件自己读不回来。
        for (int i = headingIndex; i < blocks.size() && sectionOfBlock[i] == section; i++) {
            if (blocks.get(i) instanceof RawBlock raw) {
                TableBlock empty = asEmptyTable(section, raw);
                if (empty != null) {
                    blocks.set(i, empty);
                    return i;
                }
            }
        }
        throw new SectionMissingException(section,
                "这一节的标题下找不到可识别的结构表（表至少要 3 行、每行列数一致；只有表头与分隔行的空表也认）。"
                        + "定位不到表就不改这一节——凭空造一张表等于改写了不属于本模型的块。");
    }

    /** 表头必须与规格一致：列对不上就无法判断哪一列是什么。 */
    private static void requireHeader(PlanSection section, TableBlock table) {
        if (!headerMatches(section, table.header())) {
            throw new SectionMissingException(section, "这一节的结构表表头与规格不符：期望 "
                    + SECTION_HEADERS.get(section) + "，实际 " + table.header()
                    + "。表头对不上，按位置读就会读错列、写就会写错列。");
        }
    }

    /**
     * 表头比对：忽略单元格两侧的空白。
     *
     * <p><b>为什么这里可以忽略空白，而自由文本一格都不许动</b>：表头是<b>规格</b>（spec §4.4 钉死的列名），
     * 不是用户内容。而 {@code TableBlock} 重新生成时按列宽补空格、{@code splitCells} 只去前后各
     * 一个空格，于是本 App 自己写出的文件里，表头单元格会带上对齐空格。按原文严格比对，就会出现
     * 「App 打不开自己刚保存的文件」这种自伤。忽略两侧空白之后，表头仍然必须是规格里那几列。
     */
    private static boolean headerMatches(PlanSection section, List<String> cells) {
        List<String> expected = SECTION_HEADERS.get(section);
        if (cells.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!cells.get(i).strip().equals(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把「表头 + 分隔行（+ 纯空行）」的原样块看成这一节的空表；不是这个形状就返回 null。
     *
     * <p>刻意只认<b>整块</b>都是这个形状的情况：如果表头行前面还有标题行或散文，就说明这段文本
     * 不属于本模型（升级成 TableBlock 后一旦置脏重新生成，那些行会被丢掉——那就是毁用户的字），
     * 因此一律不认。
     */
    private static TableBlock asEmptyTable(PlanSection section, RawBlock raw) {
        List<String> lines = linesOf(raw);
        if (lines.size() < 2) {
            return null;
        }
        if (!isTableLine(lines.get(0)) || !isTableLine(lines.get(1))) {
            return null;
        }
        List<String> header = TableBlock.splitCells(lines.get(0));
        if (!headerMatches(section, header)) {
            return null;
        }
        if (!isSeparatorCells(TableBlock.splitCells(lines.get(1)), header.size())) {
            return null;
        }
        for (int i = 2; i < lines.size(); i++) {
            if (!isBlankLine(lines.get(i))) {
                return null; // 表后面还有别的内容：这不是「只有表头的空表」
            }
        }
        // originalText 原样照抄整块文本（含尾部空行），suffix 是表行之后的空行——
        // 于是未改动时 render() 逐字节返回原文，P1 在升级路径上同样成立。
        return new TableBlock(raw.render(), join(lines, 2), header, List.of());
    }

    // =====================================================================
    // 写入口闸门
    // =====================================================================

    /**
     * 写入口的统一闸门：拒绝含<b>裸 {@code |}</b> 或<b>换行</b>的值。
     *
     * <p>为什么是拒绝而不是转义：转义后写进表格的是另一串文本（多一个反斜杠），
     * 而 {@code TableBlock.splitCells} 把 {@code \|} 原样当成内容、<b>不回解转义</b>，
     * 于是「写进去再读出来」得到的不是用户输入的那串字——静默改用户的字，正是本项目最不能做的事。
     * 拒绝则把选择权交回用户，报错里带上原文与字段名，改一个字就能继续。
     *
     * <p>{@code \|} 本身放行：它是 Markdown 表格里的转义竖线，与 {@code splitCells} 的
     * 读取规则逐字一致（因此往返恒等）。这里必须与那边<b>同一套扫描规则</b>，否则会出现
     * 「闸门放行的值被解析成两列」这种最难查的错。
     */
    private static String requireCellText(String field, String value) {
        if (value == null) {
            throw new IllegalArgumentException("字段「" + field + "」不能为 null（表格单元格必须有文本）");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && value.charAt(i + 1) == '|') {
                i++; // `\|` 是内容，不是列分隔符
                continue;
            }
            if (c == '|') {
                throw new IllegalArgumentException("字段「" + field + "」不能包含裸竖线 `|`"
                        + "（重新生成时会多切出一列，写坏表格）：" + escape(value));
            }
            if (c == '\n' || c == '\r') {
                throw new IllegalArgumentException("字段「" + field + "」不能包含换行符"
                        + "（会把一行表格断成两行，写坏表格）：" + escape(value));
            }
        }
        return value;
    }

    /** 报错信息里把换行写成转义形式，其余原样——用户要能在报错里认出自己输入的那串字。 */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    /** 状态取标签；null 交给闸门统一报错（null 也不许写进表里）。 */
    private static String labelOf(GoalStatus status) {
        return status == null ? null : status.label();
    }

    private static List<String> goalCells(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        return List.of(
                requireCellText("目标清单 · 目标", goal.name()),
                requireCellText("目标清单 · 状态", labelOf(goal.status())),
                requireCellText("目标清单 · 优先级", goal.priority()),
                requireCellText("目标清单 · 时间窗", goal.window()),
                requireCellText("目标清单 · 说明", goal.note()));
    }

    private static List<String> courseCells(Course course) {
        Objects.requireNonNull(course, "course");
        return List.of(
                requireCellText("课程（本学期） · 课程", course.name()),
                requireCellText("课程（本学期） · 本周任务", course.weeklyTask()),
                requireCellText("课程（本学期） · 截止", course.due()),
                requireCellText("课程（本学期） · 状态", course.status()));
    }

    private static List<String> baselineCells(Baseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        return List.of(
                requireCellText("每周底线 · 目标", baseline.goal()),
                requireCellText("每周底线 · 项", baseline.item()),
                requireCellText("每周底线 · 量", baseline.amount()),
                requireCellText("每周底线 · 怎么安排", baseline.plan()),
                requireCellText("每周底线 · 状态", baseline.status()));
    }

    private static List<String> phaseCells(Phase phase) {
        Objects.requireNonNull(phase, "phase");
        return List.of(
                requireCellText("阶段排期 · 阶段", phase.phase()),
                requireCellText("阶段排期 · 时间窗", phase.window()),
                requireCellText("阶段排期 · 主攻", phase.focus()),
                requireCellText("阶段排期 · 验收标准", phase.acceptance()));
    }

    /**
     * 逐列写入一行。
     *
     * <p>所有单元格文本都已经过了 {@link #requireCellText}，因此这里只会遇到「行下标越界」——
     * 而它在第一次 {@code setCell} 时就会抛出，绝不会留下写了一半的行。
     */
    private static void writeRow(TableBlock table, int index, List<String> cells) {
        for (int c = 0; c < cells.size(); c++) {
            table.setCell(index, c, cells.get(c));
        }
    }

    // =====================================================================
    // 行扫描（只用于判断，从不据它改写不属于自己的字节）
    // =====================================================================

    /**
     * 按 {@code '\n'} 切成「保留行尾符」的行。
     *
     * <p>规则与 {@link MarkdownParser} 内部一致：不 trim、不丢行尾符、不规范化换行。
     * 本类只用它做判断（哪些行是标题、哪一块是不是空表），判断的结果从不参与者回写。
     */
    private static List<String> linesOf(Block block) {
        String text = block.render();
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

    /** 拼接 {@code lines} 的 {@code [from, end)} 区间；只拼接，不加任何修饰。 */
    private static String join(List<String> lines, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * 纯空行：只含换行符（或文件末行的空串）。
     *
     * <p>只含空格或制表符的行是<b>内容</b>，不是空行（全局约束已钉死）——误判成空行会让
     * 空表升级时把用户的缩进吃掉。
     */
    private static boolean isBlankLine(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    /** 与 {@link MarkdownParser} 的表格行判据一致：{@code strip()} 后以 {@code |} 开头并以 {@code |} 结尾。 */
    private static boolean isTableLine(String line) {
        String body = line.strip();
        return body.length() >= 2 && body.startsWith("|") && body.endsWith("|");
    }

    /** 分隔行：列数一致，且每格都是 {@code ---} / {@code :--} 这类形状。 */
    private static boolean isSeparatorCells(List<String> cells, int columns) {
        if (cells.size() != columns) {
            return false;
        }
        for (String cell : cells) {
            if (!isSeparatorCell(cell)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSeparatorCell(String cell) {
        String body = cell.strip();
        int from = body.startsWith(":") ? 1 : 0;
        int to = body.endsWith(":") ? body.length() - 1 : body.length();
        if (to <= from) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (body.charAt(i) != '-') {
                return false;
            }
        }
        return true;
    }
}
