package com.xiaoyiyuan.study.ui.service;

import com.xiaoyiyuan.study.plan.Course;
import com.xiaoyiyuan.study.plan.PlanDocument;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本周」快照的取值：底线按目标「进行中」判定归属，§五 生活准则走原文块路径。
 *
 * <p>关键性质：§四 的行**全部**返回，但只有挂在进行中目标下的那些 {@code goalActive=true}
 * （才进本周）；名字比对忽略两侧空白——手写表格常在单元格里多留空格
 * （{@code |  本学期学校课程  |}），而 {@code TableBlock.splitCells} 只去掉两侧各一个空格。
 */
class WeeklySnapshotTest {

    /**
     * §四 第一行的目标名两侧各多一个空格：{@code Goal.name()} 与 {@code Baseline.goal()}
     * 因此逐字不等，只有「忽略两侧空白」的比对才能把这一行判成进行中目标的条目。
     */
    static final String PLAN = """
            # 学习与生活计划

            ## 一、我的现状

            | 项目 | 内容 |
            |---|---|
            | 年级 / 学期 | 大二上 |

            ## 二、目标清单

            | 目标 | 状态 | 优先级 | 时间窗 | 说明 |
            |---|---|---|---|---|
            | 本学期学校课程 | 进行中 | 1 | 2026.9–2027.1 | 唯一必须完成的事 |
            | 考研（408） | 暂缓 | — | 大三再定 | 可能取消 |

            ## 三、课程（本学期）

            | 课程 | 本周任务 | 截止 | 状态 |
            |---|---|---|---|
            | 数据库 | 第 4 章作业 |  | 待回报 |
            | 计网 | 实验报告 |  | 待回报 |

            ## 四、每周底线

            | 目标 | 项 | 量 | 怎么安排 | 状态 |
            |---|---|---|---|---|
            |  本学期学校课程  | 作业按时交 | 每周随课 | 当周内完成 | 待回报 |
            | 考研（408） | 背单词 | 每天 50 个 | 睡前 | 待回报 |
            | 没出现过的目标 | 孤立行 | 无 | 无 | 待回报 |

            ## 五、生活准则

            - 睡眠优先：23:30 前上床
            - 每周运动 ≥2 次

            ## 六、阶段排期

            | 阶段 | 时间窗 | 主攻 | 验收标准 |
            |---|---|---|---|
            | A | 2026.9–2027.1 | 学校 6 门课 | 全部及格 |
            """;

    @Test
    void baselines_returnsEveryRow_taggedByGoalActive() {
        PlanDocument document = PlanDocument.parse(PLAN);

        List<WeeklySnapshot.BaselineView> rows = WeeklySnapshot.baselines(document);
        assertEquals(3, rows.size(), "§四 的行全部返回，归属由 goalActive 表达：" + rows);

        WeeklySnapshot.BaselineView active = rows.get(0);
        assertTrue(active.goalActive(), "挂在进行中目标下的行必须 goalActive=true：" + active);
        assertEquals("作业按时交", active.item());

        WeeklySnapshot.BaselineView deferred = rows.get(1);
        assertFalse(deferred.goalActive(), "暂缓目标的底线条目 goalActive=false（不进本周）：" + deferred);
        assertEquals("背单词", deferred.item());

        WeeklySnapshot.BaselineView orphan = rows.get(2);
        assertFalse(orphan.goalActive(), "§二 里没有对应目标的孤立行同样 goalActive=false：" + orphan);
        assertEquals("孤立行", orphan.item());
    }

    @Test
    void baselines_matchGoalNameIgnoringSurroundingWhitespace() {
        PlanDocument document = PlanDocument.parse(PLAN);

        // 前提校验：这一行的目标名与 §二 里的名字确实逐字不等（否则本用例什么都没测到）
        String goalName = document.goals().get(0).name();
        String rowGoal = document.baselines().get(0).goal();
        assertFalse(goalName.equals(rowGoal),
                "样例必须让两个名字逐字不等，否则「忽略空白」这条根本没被承重：'"
                        + goalName + "' vs '" + rowGoal + "'");

        assertTrue(WeeklySnapshot.baselines(document).get(0).goalActive(),
                "忽略两侧空白后必须判成同一个目标");
    }

    @Test
    void coursesThisWeek_returnsEveryCourseRow() {
        PlanDocument document = PlanDocument.parse(PLAN);
        List<Course> courses = WeeklySnapshot.coursesThisWeek(document);
        assertEquals(List.of("数据库", "计网"), courses.stream().map(Course::name).toList());
    }

    @Test
    void lifeRules_returnsSectionFiveBullets_withoutPrefixNorLineEnding() {
        PlanDocument document = PlanDocument.parse(PLAN);
        assertEquals(List.of("睡眠优先：23:30 前上床", "每周运动 ≥2 次"),
                WeeklySnapshot.lifeRules(document));
    }

    @Test
    void lifeRules_absentSection_isEmpty_notAnError() {
        String withoutSectionFive = PLAN.replace("""
                ## 五、生活准则

                - 睡眠优先：23:30 前上床
                - 每周运动 ≥2 次

                """, "");
        PlanDocument document = PlanDocument.parse(withoutSectionFive);
        assertEquals(List.of(), WeeklySnapshot.lifeRules(document),
                "没有 §五 时应返回空列表，而不是报错或猜");
    }
}
