package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计划文件结构映射（Task 6）。
 *
 * <p>三层断言，对应本任务的三处风险：
 * <ol>
 *   <li><b>定位</b>：五个建模章节（§一/二/三/四/六）必须按「中文序号 + 标题名」双重匹配找到。
 *       缺标题、标题重复、标题下没有可识别的表、表头与规格不符，都抛可读的
 *       {@link SectionMissingException}——定位不到就不改，更不写。</li>
 *   <li><b>P1/P2</b>：不动就不许改一个字节；只改一张表时，它之外的字节（含未建模章节里的散文、
 *       LaTeX、HTML 注释，以及 §七 里那张与 §四 形状完全相同的表）必须留在原位。
 *       本类用「表前 + 表后」两段字节直接卡住这一点，而不是只断言某几行还在。</li>
 *   <li><b>写入口闸门</b>：每个写入口的每个字段都拒绝裸 {@code |} 与换行，且拒绝后文档零改动。</li>
 * </ol>
 */
class PlanDocumentTest {

    // ---- brief 指定的受控样例 ----

    private static final String MD = """
            # 学习与生活计划 · 测试

            > 当前学期：大二上

            ---

            ## 一、我的现状

            | 项目 | 内容 |
            |---|---|
            | 年级 / 学期 | 大二上 |
            | 本学期课程 | 数据库、Java |

            ---

            ## 二、目标清单

            | 目标 | 状态 | 优先级 | 时间窗 | 说明 |
            |---|---|---|---|---|
            | 本学期学校课程 | 进行中 | 1 | 2026.9–2027.1 | 唯一必须完成的事 |
            | 考研（408） | 暂缓 | — | 大三再定 | 可能取消 |

            ---

            ## 三、课程（本学期）

            | 课程 | 本周任务 | 截止 | 状态 |
            |---|---|---|---|
            | 数据库 | 第 4 章作业 |  | 待回报 |

            ---

            ## 四、每周底线

            | 目标 | 项 | 量 | 怎么安排 | 状态 |
            |---|---|---|---|---|
            | 学校课程 | 作业按时交 | 每周随课 | 当周内完成 | 待回报 |

            ---

            ## 六、阶段排期

            | 阶段 | 时间窗 | 主攻 | 验收标准 |
            |---|---|---|---|
            | A | 2026.9–2027.1 | 学校 6 门课 | 全部及格 |
            | C | 2027.7–2027.8 | 数学基础一轮 | 基础题 ≥70% |
            """;

    // ---- 更贴近真实档案的样例：含未建模章节、LaTeX、HTML 注释、围栏代码 ----
    //
    // 拆成常量再拼接，是为了让「§二 的表」「§四 的表」「§六 的表」各自成为一段可以逐字节
    // 定位的文本：P2 断言要拿它当切点，才卡得住「表之外的字节一个都没动」。

    private static final String RICH_HEAD = """
            # 学习与生活计划 · 测试

            > 当前学期：大二上
            > 周界：周一 – 周日

            ---

            ## 一、我的现状

            | 项目 | 内容 |
            |---|---|
            | 年级 / 学期 | 大二上 |
            | 本学期课程 | 数据库、Java |

            ---

            ## 二、目标清单

            """;

    private static final String RICH_GOALS_TABLE = """
            | 目标 | 状态 | 优先级 | 时间窗 | 说明 |
            |---|---|---|---|---|
            | 本学期学校课程 | 进行中 | 1 | 2026.9–2027.1 | 唯一必须完成的事 |
            | 考研（408） | 暂缓 | — | 大三再定 | 可能取消 |
            """;

    private static final String RICH_MID = """

            ---

            ## 三、课程（本学期）

            | 课程 | 本周任务 | 截止 | 状态 |
            |---|---|---|---|
            | 数据库 | 第 4 章作业 |  | 待回报 |

            ---

            ## 四、每周底线

            """;

    private static final String RICH_BASELINES_TABLE = """
            | 目标 | 项 | 量 | 怎么安排 | 状态 |
            |---|---|---|---|---|
            | 学校课程 | 作业按时交 | 每周随课 | 当周内完成 | 待回报 |
            """;

    private static final String RICH_BEFORE_PHASES = """

            ---

            ## 五、生活准则

            - 睡眠优先：23:30 前上床
            - 每周运动 ≥2 次

            ---

            ## 六、阶段排期

            """;

    private static final String RICH_PHASES_TABLE = """
            | 阶段 | 时间窗 | 主攻 | 验收标准 |
            |---|---|---|---|
            | A | 2026.9–2027.1 | 学校 6 门课 | 全部及格 |
            """;

    private static final String RICH_TAIL = """

            ---

            ## 七、本周

            | 目标 | 项 | 量 | 怎么安排 | 状态 |
            |---|---|---|---|---|
            | 学校课程 | 七节的底线表不能被当成四节 | 每周随课 | 当周内完成 | 待回报 |

            ---

            ## 八、考研（暂缓）

            求极限时把 $1^\\infty$ 型单独拎出来，下周复述 $\\sum_{k=1}^{n}\\frac{n}{n^2+k} \\to 1$。

            ```text
            阶段 A  2026.9 – 2027.1   学校 6 门课
            ```

            ---

            ## 九、周记录

            ### 2026-09-14 – 2026-09-20

            - 本周计划：单词 ≥5 天
            - 卡住的点：$\\lim_{x \\to 0}$ 还没通

            ---

            ## 十、待确认

            > 目标院校：待定

            <!-- 填写方式：周日晚说一句「这周完成了…」，由助手在此处追加 -->
            """;

    private static final String RICH = RICH_HEAD + RICH_GOALS_TABLE + RICH_MID
            + RICH_BASELINES_TABLE + RICH_BEFORE_PHASES + RICH_PHASES_TABLE + RICH_TAIL;

    /** 空表：只有表头与分隔行（用户在文本编辑器里删光了阶段行，或本 App 删掉最后一行后保存）。 */
    private static final String RICH_EMPTY_PHASES_TABLE = """
            | 阶段 | 时间窗 | 主攻 | 验收标准 |
            |---|---|---|---|
            """;

    private static final String RICH_EMPTY_PHASES = RICH.replace(RICH_PHASES_TABLE, RICH_EMPTY_PHASES_TABLE);

    // =====================================================================
    // brief 指定的用例（逐字保留）
    // =====================================================================

    @Test
    void parsesProfileGoalsCoursesPhases() {
        PlanDocument doc = PlanDocument.parse(MD);
        assertEquals(2, doc.profile().size());
        assertEquals("大二上", doc.profile().get(0).value());

        assertEquals(2, doc.goals().size());
        assertEquals(GoalStatus.ACTIVE, doc.goals().get(0).status());
        assertEquals(GoalStatus.DEFERRED, doc.goals().get(1).status());
        assertEquals("考研（408）", doc.goals().get(1).name());

        assertEquals(1, doc.courses().size());
        assertEquals("数据库", doc.courses().get(0).name());

        assertEquals(1, doc.baselines().size());
        assertEquals("作业按时交", doc.baselines().get(0).item());
        assertEquals("学校课程", doc.baselines().get(0).goal());

        assertEquals(2, doc.phases().size());
        assertEquals("A", doc.phases().get(0).phase());
    }

    @Test
    void changingOneCell_changesOnlyThatTable() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.setGoal(0, new Goal("本学期学校课程", GoalStatus.ACTIVE, "1", "2026.9–2027.1", "唯一必须完成的事"));

        String out = doc.render();
        assertTrue(out.contains("## 一、我的现状"), "其它章节必须仍在");
        assertTrue(out.contains("| 数据库 | 第 4 章作业 |  | 待回报 |"), "课程表必须逐字未变");
    }

    @Test
    void missingSection_throwsReadableException() {
        String broken = MD.replace("## 二、目标清单", "## 二、目标");
        SectionMissingException ex = assertThrows(SectionMissingException.class,
                () -> PlanDocument.parse(broken));
        assertEquals(PlanSection.GOALS, ex.section());
        assertTrue(ex.getMessage().contains("目标清单"), "异常信息必须能直接读懂");
    }

    @Test
    void duplicateSection_throwsReadableException() {
        String broken = MD + "\n\n## 二、目标清单\n\n| 目标 | 状态 | 优先级 | 时间窗 | 说明 |\n|---|---|---|---|---|\n";
        assertThrows(SectionMissingException.class, () -> PlanDocument.parse(broken));
    }

    @Test
    void phasesOrder_isPreservedAndMovable() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.movePhase(0, 1);
        assertEquals("C", doc.phases().get(0).phase());
        assertEquals("A", doc.phases().get(1).phase());
    }

    @Test
    void goalAddRemove_works() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.addGoal(new Goal("四级", GoalStatus.ACTIVE, "3", "2027.6", ""));
        assertEquals(3, doc.goals().size());
        doc.removeGoal(0);
        assertEquals(2, doc.goals().size());
    }

    // =====================================================================
    // 1. 章节定位：序号与标题名双重匹配、恰好一次、表必须真的在
    // =====================================================================

    /** 五个建模章节的完整标题——测试自己再写一份，不复用生产常量，免得两边一起写错。 */
    private static String headingOf(PlanSection section) {
        return switch (section) {
            case PROFILE -> "## 一、我的现状";
            case GOALS -> "## 二、目标清单";
            case COURSES -> "## 三、课程（本学期）";
            case BASELINES -> "## 四、每周底线";
            case PHASES -> "## 六、阶段排期";
        };
    }

    private static void assertLocateFailure(String markdown, PlanSection section) {
        SectionMissingException ex = assertThrows(SectionMissingException.class,
                () -> PlanDocument.parse(markdown),
                "章节「" + section.label() + "」定位失败时必须抛异常，而不是挑一张表凑合");
        assertEquals(section, ex.section());
        assertTrue(ex.getMessage().contains(section.label()),
                "异常信息必须能直接读懂是哪一节，实际：" + ex.getMessage());
    }

    /** 每个建模章节都必须在场：§五/§七/§八/§九/§十 不在场完全没关系。 */
    @Test
    void allFiveModelledSectionsArePresent_inTheRichSample() {
        PlanDocument doc = PlanDocument.parse(RICH);
        assertEquals(2, doc.profile().size());
        assertEquals(2, doc.goals().size());
        assertEquals(1, doc.courses().size());
        assertEquals(1, doc.baselines().size());
        assertEquals(1, doc.phases().size());
    }

    /** 只改序号（名字没变）→ 定位失败。只认名字的话这里就会误判。 */
    @ParameterizedTest
    @EnumSource(PlanSection.class)
    void onlyTheNumeralChanged_isALocateFailure(PlanSection section) {
        String heading = headingOf(section);
        String changed = "## 五、" + section.label();
        assertNotEquals(heading, changed);
        assertLocateFailure(RICH.replace(heading, changed), section);
    }

    /** 只改名字（序号没变）→ 定位失败。只认序号的话这里就会误判。 */
    @ParameterizedTest
    @EnumSource(PlanSection.class)
    void onlyTheNameChanged_isALocateFailure(PlanSection section) {
        String heading = headingOf(section);
        char numeral = heading.charAt(3); // "## X、…" 里的中文序号
        assertLocateFailure(RICH.replace(heading, "## " + numeral + "、改名占位"), section);
    }

    /** 同一个标题出现两次 → 定位失败。不知道该改哪一份，猜就是写错表。 */
    @ParameterizedTest
    @EnumSource(PlanSection.class)
    void duplicateHeading_isALocateFailure(PlanSection section) {
        assertLocateFailure(RICH + "\n\n" + headingOf(section) + "\n", section);
    }

    /** 标题在、表被删了 → 定位失败（不许凭空造一张表，那要改写不属于本模型的块）。 */
    @Test
    void headingWithoutAnyTable_isALocateFailure() {
        String md = MD.replace("| 目标 | 状态 | 优先级 | 时间窗 | 说明 |\n"
                + "|---|---|---|---|---|\n"
                + "| 本学期学校课程 | 进行中 | 1 | 2026.9–2027.1 | 唯一必须完成的事 |\n"
                + "| 考研（408） | 暂缓 | — | 大三再定 | 可能取消 |\n", "（表被用户删了）\n");
        assertTrue(md.contains("## 二、目标清单"));
        assertFalse(md.contains("| 本学期学校课程 |"));
        assertLocateFailure(md, PlanSection.GOALS);
    }

    /** 表在，但表头不是规格里的那一套 → 定位失败（列对不上就无法判断哪一列是什么，写了就是错列）。 */
    @Test
    void tableWithUnexpectedHeader_isALocateFailure() {
        String md = MD.replace("| 目标 | 状态 | 优先级 | 时间窗 | 说明 |",
                "| 目标 | 状态 | 优先级 | 时间窗 | 备注 |");
        assertLocateFailure(md, PlanSection.GOALS);
    }

    /**
     * 未建模章节里的表，绝不能被绑到某个建模章节上。
     *
     * <p>§四 每周底线 的表被删掉，只留散文；紧接着的未建模章节 §五 里放一张**同样五列、同样表头**
     * 的表（刻意紧跟在 §四 之后，中间不隔别的表，免得别的表先撞上表头校验把本用例变成空测试）。
     * 若章节定位没有在未建模的二级标题（`## 五、生活准则`）处清零，{@code baselines()} 就会读到
     * §五 的那张表——「改 §四」就会写进 §五 的字节。这里必须定位失败。
     */
    @Test
    void identicallyShapedTableInAnUnmodelledSection_isNeverBoundToAModelledSection() {
        String md = RICH.replace(RICH_BASELINES_TABLE, "（本周底线还没填）\n")
                .replace("## 五、生活准则\n\n", "## 五、生活准则\n\n" + RICH_BASELINES_TABLE + "\n");
        assertFalse(md.substring(0, md.indexOf("## 五、生活准则")).contains(RICH_BASELINES_TABLE),
                "§四 的表必须真的被删掉了");
        assertTrue(md.contains("## 五、生活准则\n\n" + RICH_BASELINES_TABLE), "§五 的那张同形状表必须就位");
        assertLocateFailure(md, PlanSection.BASELINES);
    }

    /** 未建模章节里的表，在改别的表时逐字节不许动。 */
    @Test
    void editingBaselines_leavesTheTableInSectionSevenByteIdentical() {
        PlanDocument doc = PlanDocument.parse(RICH);
        assertEquals(1, doc.baselines().size());
        assertEquals("作业按时交", doc.baselines().get(0).item(), "读到的必须是 §四 的表，不是 §七 的表");

        doc.setBaseline(0, new Baseline("学校课程", "作业按时交", "每周随课", "当周内完成", "已完成"));
        String out = doc.render();

        assertTrue(out.contains("| 学校课程 | 七节的底线表不能被当成四节 | 每周随课 | 当周内完成 | 待回报 |"),
                "§七 的表属于未建模章节，逐字节都不许动");
        assertTrue(out.contains("已完成"), "§四 的改动要真的落到输出里，否则本用例是空的");
    }

    /** 三级标题（§九 的周标题）不是章节边界：它不能把当前章节清空。 */
    @Test
    void thirdLevelHeadingsAreNotSectionBoundaries() {
        PlanDocument doc = PlanDocument.parse(RICH);
        assertEquals(1, doc.phases().size(), "§六 的表必须仍被认出来，尽管 §七/§九 里有别的标题");
        assertEquals("A", doc.phases().get(0).phase());
    }

    // =====================================================================
    // 2. P1 / P2：没动就不许变，只改一张表
    // =====================================================================

    /** P1：不修改就写回，字节完全相同（含未建模章节、LaTeX、HTML 注释、围栏代码）。 */
    @Test
    void parseWithoutEdits_rendersByteIdentical() {
        assertEquals(RICH, PlanDocument.parse(RICH).render());
        assertEquals(MD, PlanDocument.parse(MD).render());
    }

    /**
     * P2 的强形式：把 §二 的表当成切点，断言它<b>之前</b>与<b>之后</b>的字节一字不差。
     *
     * <p>只要有人让 PlanDocument 顺手重排、重写或规范化了任何不属于这张表的块（包括未建模章节），
     * 这条就会红——它比「某几行还在」强得多。
     */
    @Test
    void editingOneGoal_changesOnlyThatTable_bytesOutsideItAreIdentical() {
        int start = RICH.indexOf(RICH_GOALS_TABLE);
        assertTrue(start > 0, "样例里必须含有 §二 的表");
        String before = RICH.substring(0, start);
        String after = RICH.substring(start + RICH_GOALS_TABLE.length());

        PlanDocument doc = PlanDocument.parse(RICH);
        doc.setGoal(0, new Goal("本学期学校课程", GoalStatus.DONE, "1", "2026.9–2027.1", "唯一必须完成的事"));
        String out = doc.render();

        assertNotEquals(RICH, out, "改了一格，输出必须真的变了（否则下面的断言是空的）");
        assertTrue(out.startsWith(before), "§二 表之前的每一个字节都不许动");
        assertTrue(out.endsWith(after), "§二 表之后的每一个字节都不许动（含未建模章节与 §七 的表）");
        assertTrue(out.contains("## 七、本周"), "未建模章节必须原样留在文档里");
        assertTrue(out.contains("$1^\\infty$"), "LaTeX 必须逐字保留");
        assertTrue(out.contains("<!-- 填写方式"), "HTML 注释必须逐字保留");
    }

    /**
     * 写完再解析一次：模型读得回来（可见内容不变），字节也一模一样——写出的文件自己读得回来。
     *
     * <p>值的两侧可能多出对齐空格（已知局限，见
     * {@link #alignmentSpacesMayBeAbsorbedIntoFreeTextValues_butFileBytesNeverChange}），
     * 因此这里比对「可见内容」与字节，而不是逐字段原文相等。
     */
    @Test
    void editedDocument_canBeReParsed_toTheSameVisibleModelAndBytes() {
        PlanDocument doc = PlanDocument.parse(RICH);
        doc.setGoal(0, new Goal("本学期学校课程", GoalStatus.DONE, "1", "2026.9–2027.1", "唯一必须完成的事"));
        doc.setCourse(0, new Course("数据库", "第 5 章作业", "2026-10-20", "进行中"));
        doc.addPhase(new Phase("D", "2027.9–2028.6", "专业课二轮", "真题 ≥60%"));
        doc.removeBaseline(0); // §四 删到空表：写入结果必须仍然能被自己解析
        doc.setProfileItem(1, "数据库、Java、线性代数");

        String out = doc.render();
        PlanDocument reloaded = PlanDocument.parse(out);

        assertEquals(2, reloaded.goals().size());
        assertEquals(GoalStatus.DONE, reloaded.goals().get(0).status(), "状态列必须读得出来");
        assertEquals(GoalStatus.DEFERRED, reloaded.goals().get(1).status());
        assertEquals("本学期学校课程", reloaded.goals().get(0).name().strip());
        assertEquals("数据库", reloaded.courses().get(0).name().strip());
        assertEquals("第 5 章作业", reloaded.courses().get(0).weeklyTask().strip());
        assertEquals("2026-10-20", reloaded.courses().get(0).due().strip());
        assertTrue(doc.baselines().isEmpty(), "§四 的最后一行被删掉了");
        assertTrue(reloaded.baselines().isEmpty(), "只剩表头与分隔行的 §四 必须仍被认领");
        assertEquals(2, reloaded.phases().size());
        assertEquals("D", reloaded.phases().get(1).phase().strip(), "新阶段必须写进去，行序保留");
        assertEquals("本学期课程", reloaded.profile().get(1).key().strip());
        assertEquals("数据库、Java、线性代数", reloaded.profile().get(1).value().strip());
        assertEquals(out, reloaded.render(), "再解析一次必须逐字节相同（写入是幂等的）");
    }

    /**
     * 已知局限（Task 4 转交的对齐空格问题）：脏表重新生成时按列宽补空格，而
     * {@code TableBlock.splitCells} 只去前后各一个空格（Task 4 钉死的规则：多出来的空格是内容），
     * 于是「保存 → 重新打开」后，自由文本单元格的值会带上对齐空格。
     *
     * <p>本用例把这件事的边界钉死，免得它变成不可预期的漂移：
     * <ul>
     *   <li>规格固定的字段（表头、状态）不受影响——它们比对时忽略两侧空白，因此文件永远读得回来；</li>
     *   <li>自由文本只可能多出空格，可见内容一字不改；</li>
     *   <li>文件<b>字节</b>不会因此改变：对齐空格吸收进值之后，重新生成写出的仍是同一串字节
     *       （「值里的空格 + 补位空格」总数守恒）。</li>
     * </ul>
     */
    @Test
    void alignmentSpacesMayBeAbsorbedIntoFreeTextValues_butFileBytesNeverChange() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.setGoal(0, new Goal("本学期学校课程", GoalStatus.DONE, "1", "2026.9–2027.1", "唯一必须完成的事"));
        String saved = doc.render();

        PlanDocument reloaded = PlanDocument.parse(saved);
        assertEquals(2, reloaded.goals().size(), "状态列带对齐空格时也必须读得出来");
        assertEquals(GoalStatus.DONE, reloaded.goals().get(0).status());
        assertEquals(GoalStatus.DEFERRED, reloaded.goals().get(1).status());
        for (int i = 0; i < doc.goals().size(); i++) {
            assertEquals(doc.goals().get(i).name().strip(), reloaded.goals().get(i).name().strip(),
                    "自由文本的可见内容一字不许改，差异只允许是对齐空格");
        }
        assertEquals(saved, reloaded.render(), "重新打开再保存，字节一个都不动");

        // 再改一次同一张表：字节仍然完全一样（对齐空格已经吸收进值里，不会再变）
        reloaded.setGoal(1, new Goal(reloaded.goals().get(1).name(), GoalStatus.DEFERRED, "—", "大三再定", "可能取消"));
        assertEquals(saved, reloaded.render(), "对齐空格不许让文件越写越长或改样");
    }

    /** 行序按文件顺序给出（spec §4.4「表格保留行序」）。 */
    @Test
    void goals_areReturnedInFileRowOrder_andMovable() {
        PlanDocument doc = PlanDocument.parse(MD);
        List<String> names = doc.goals().stream().map(Goal::name).toList();
        assertEquals(List.of("本学期学校课程", "考研（408）"), names);

        doc.moveGoal(0, 1);
        assertEquals("考研（408）", doc.goals().get(0).name());
        assertEquals("本学期学校课程", doc.goals().get(1).name());
    }

    /** §一 的「项目」是这一行的身份，只改「内容」列。 */
    @Test
    void setProfileItem_onlyChangesTheValueColumn() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.setProfileItem(1, "数据库、Java、线性代数");

        assertEquals("本学期课程", doc.profile().get(1).key(), "键必须原样保留");
        assertEquals("数据库、Java、线性代数", doc.profile().get(1).value());
        assertEquals("年级 / 学期", doc.profile().get(0).key(), "其它行不许动");

        PlanDocument reloaded = PlanDocument.parse(doc.render());
        assertEquals("本学期课程", reloaded.profile().get(1).key().strip());
        assertEquals("数据库、Java、线性代数", reloaded.profile().get(1).value().strip());
    }

    // =====================================================================
    // 3. 空表：只有表头与分隔行
    // =====================================================================

    /**
     * 只剩「表头 + 分隔行」的空表必须仍被本节认领，且未改动时逐字节往返。
     *
     * <p>MarkdownParser 的表格识别门槛是连续 ≥3 行，这种表会被降级成原样块；如果 PlanDocument
     * 就此认不出它，「删掉最后一行 → 保存 → 重新打开」会直接失败——App 写出的文件自己读不回来。
     */
    @Test
    void emptySectionTable_isRecognised_regrows_andStaysByteIdentical() {
        PlanDocument doc = PlanDocument.parse(RICH_EMPTY_PHASES);
        assertTrue(doc.phases().isEmpty());
        assertEquals(RICH_EMPTY_PHASES, doc.render(), "空表在没被改动前必须逐字节往返（P1）");

        doc.addPhase(new Phase("A", "2026.9–2027.1", "学校 6 门课", "全部及格"));
        String out = doc.render();
        assertTrue(out.contains("| 2026.9–2027.1 | 学校 6 门课 | 全部及格 |"), "新加的阶段必须写进 §六");

        PlanDocument reloaded = PlanDocument.parse(out);
        assertEquals(1, reloaded.phases().size());
        assertEquals("A", reloaded.phases().get(0).phase().strip());
        assertEquals(out, reloaded.render());
    }

    /** 没有分隔行的「表」不算空表：那只是一行含竖线的散文，不许被认领。 */
    @Test
    void loneHeaderLineWithoutSeparator_isNotAnEmptyTable() {
        String md = MD.replace("| 阶段 | 时间窗 | 主攻 | 验收标准 |\n|---|---|---|---|\n", "| 阶段 | 时间窗 | 主攻 | 验收标准 |\n");
        assertLocateFailure(md, PlanSection.PHASES);
    }

    // =====================================================================
    // 4. 状态取值：不静默降级
    // =====================================================================

    /** 状态写错一个字必须是可定位的失败，不能悄悄变成另一个状态。 */
    @Test
    void unknownGoalStatus_failsOnRead_withTheOriginalText_neverSilentlyDegrades() {
        String md = MD.replace("| 考研（408） | 暂缓 |", "| 考研（408） | 暂停中 |");
        assertTrue(md.contains("暂停中"), "样例必须真的被改过，否则本用例是空的");

        // parse 只管结构定位（Review Focus 4 的那一类问题）；记录级问题在读到那一节时报出来。
        // 这样一格写错不会让整篇文件打不开，而「读到就必须报错」保证了绝不静默降级。
        PlanDocument doc = PlanDocument.parse(md);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, doc::goals);
        assertTrue(ex.getMessage().contains("暂停中"), "异常必须带上原文，方便定位：" + ex.getMessage());
    }

    // =====================================================================
    // 5. 写入口闸门：裸 `|` 与换行，每个入口、每个字段都拒
    // =====================================================================

    private static Goal goalWithBadField(int field, String bad) {
        return new Goal(field == 0 ? bad : "目标甲", GoalStatus.ACTIVE,
                field == 2 ? bad : "1",
                field == 3 ? bad : "长期",
                field == 4 ? bad : "说明");
    }

    private static Course courseWithBadField(int field, String bad) {
        return new Course(field == 0 ? bad : "课程甲",
                field == 1 ? bad : "本周任务",
                field == 2 ? bad : "截止",
                field == 3 ? bad : "待回报");
    }

    private static Baseline baselineWithBadField(int field, String bad) {
        return new Baseline(field == 0 ? bad : "目标甲",
                field == 1 ? bad : "项甲",
                field == 2 ? bad : "每周 2 次",
                field == 3 ? bad : "周末",
                field == 4 ? bad : "待回报");
    }

    private static Phase phaseWithBadField(int field, String bad) {
        return new Phase(field == 0 ? bad : "阶段甲",
                field == 1 ? bad : "时间窗",
                field == 2 ? bad : "主攻",
                field == 3 ? bad : "验收标准");
    }

    /** 被拒绝的写入必须一个字节都不动（表不能被置脏）。 */
    private static void assertRejected(PlanDocument doc, Runnable write) {
        String before = doc.render();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, write::run);
        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
        assertEquals(before, doc.render(), "被拒绝的写入不得改动任何一个字节");
    }

    /**
     * 每个写入口、每个字段位置都拒绝裸 {@code |} 与各种换行。
     *
     * <p>这是本模型唯一一条能把合法输入变成畸形 Markdown 的路径（{@code TableBlock.setCell}
     * 接受任意字符串），因此闸门必须对所有入口一致生效，而不是只守住其中一个。
     */
    @ParameterizedTest
    @ValueSource(strings = {"坏|值", "换\r\n行", "换\n行", "换\r行"})
    void writeGate_rejectsUnsafeTextAtEveryEntryPointAndField(String bad) {
        PlanDocument doc = PlanDocument.parse(RICH);

        assertRejected(doc, () -> doc.setProfileItem(0, bad));

        for (int f = 0; f < 5; f++) {
            if (f == 1) {
                continue; // 状态是枚举，不可能带竖线或换行
            }
            Goal goal = goalWithBadField(f, bad);
            assertRejected(doc, () -> doc.setGoal(0, goal));
            assertRejected(doc, () -> doc.addGoal(goal));
        }
        for (int f = 0; f < 4; f++) {
            Course course = courseWithBadField(f, bad);
            assertRejected(doc, () -> doc.setCourse(0, course));
            assertRejected(doc, () -> doc.addCourse(course));
        }
        for (int f = 0; f < 5; f++) {
            Baseline baseline = baselineWithBadField(f, bad);
            assertRejected(doc, () -> doc.setBaseline(0, baseline));
            assertRejected(doc, () -> doc.addBaseline(baseline));
        }
        for (int f = 0; f < 4; f++) {
            Phase phase = phaseWithBadField(f, bad);
            assertRejected(doc, () -> doc.setPhase(0, phase));
            assertRejected(doc, () -> doc.addPhase(phase));
        }
    }

    /** 报错要说清是哪个字段，并带上用户输入的那串字。 */
    @Test
    void writeGate_messageNamesTheFieldAndCarriesTheOffendingText() {
        PlanDocument doc = PlanDocument.parse(MD);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> doc.setGoal(0,
                new Goal("本学期学校课程", GoalStatus.ACTIVE, "1", "2026.9–2027.1", "改|坏了")));
        assertTrue(ex.getMessage().contains("改|坏了"), "异常必须带上用户输入的那串字：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("目标清单") && ex.getMessage().contains("说明"),
                "异常必须指出是哪个字段，用户才知道去哪里改：" + ex.getMessage());
    }

    /** 换行的报错自己也必须是一行——否则错误提示本身就是坏的。 */
    @Test
    void writeGate_newlineIsRejected_andTheMessageStaysOnOneLine() {
        PlanDocument doc = PlanDocument.parse(MD);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> doc.setCourse(0,
                new Course("数据库", "第 4 章\r\n第 5 章", "", "待回报")));
        assertTrue(ex.getMessage().contains("换行"), "异常必须说明原因：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("课程（本学期）") && ex.getMessage().contains("本周任务"),
                "异常必须指出是哪个字段：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("\n") || ex.getMessage().contains("\r"),
                "报错信息里不能有真换行：" + ex.getMessage());
    }

    /**
     * 转义竖线 {@code \|} 是<b>内容</b>（与 {@code TableBlock.splitCells} 同一条规则），因此放行，
     * 而且必须与「重新解析」自洽：写出的文件读回来还是同一串字，字节也不再变化。
     */
    @Test
    void writeGate_acceptsEscapedPipe_andRoundTripsStably() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.setCourse(0, new Course("数据库", "第 4 章 \\| 第 5 章", "", "待回报"));

        String out = doc.render();
        PlanDocument reloaded = PlanDocument.parse(out);
        assertEquals("第 4 章 \\| 第 5 章", reloaded.courses().get(0).weeklyTask(),
                "转义竖线是内容，重新解析必须得到同一串文本");
        assertEquals(out, reloaded.render(), "写出的文件必须能被自己重新解析，且字节不变");
    }

    /** 下标越界必须是当场报错，而不是悄悄写坏行或吞掉整篇。 */
    @Test
    void outOfRangeIndices_throw_andLeaveTheDocumentUnchanged() {
        PlanDocument doc = PlanDocument.parse(MD);
        String before = doc.render();

        assertThrows(IndexOutOfBoundsException.class, () -> doc.setGoal(9, new Goal("甲", GoalStatus.ACTIVE, "1", "长期", "")));
        assertThrows(IndexOutOfBoundsException.class, () -> doc.removeGoal(9));
        assertThrows(IndexOutOfBoundsException.class, () -> doc.moveGoal(0, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> doc.setCourse(9, new Course("甲", "", "", "")));
        assertThrows(IndexOutOfBoundsException.class, () -> doc.setProfileItem(9, "甲"));
        assertThrows(IndexOutOfBoundsException.class, () -> doc.movePhase(9, 0));

        assertEquals(before, doc.render(), "越界的写入不得留下任何改动");
    }
}
