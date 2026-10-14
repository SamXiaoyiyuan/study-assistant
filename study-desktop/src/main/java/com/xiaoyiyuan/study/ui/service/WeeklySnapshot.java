package com.xiaoyiyuan.study.ui.service;

import com.xiaoyiyuan.study.plan.Baseline;
import com.xiaoyiyuan.study.plan.Course;
import com.xiaoyiyuan.study.plan.Goal;
import com.xiaoyiyuan.study.plan.GoalStatus;
import com.xiaoyiyuan.study.plan.PlanDocument;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 「本周该做什么」的一次取值（spec §5.4 / §5.5）：只读、不写、不碰文件系统。
 *
 * <p>把「哪些底线进本周」这条领域规则从界面里抽出来的理由：它同时牵涉 §二（目标状态）与 §四
 * （底线条目）两个章节，散在页面里就会变成「这个页面这样判、那个页面那样判」。规则只有一处：
 * 一条底线进本周，当且仅当它挂在一个<b>进行中</b>的目标下。
 */
public final class WeeklySnapshot {

    private WeeklySnapshot() {
    }

    /**
     * 一条底线在「本周」视图里的样子。
     *
     * @param goal       它挂在哪个目标下（与 §二 的名字对应）
     * @param item       项
     * @param amount     量
     * @param plan       怎么安排
     * @param status     状态（自由文本，无数据时是「待回报」/「未回报」）
     * @param goalActive 该目标是否在进行中——<b>只有 true 的条目进本周</b>
     */
    public record BaselineView(String goal, String item, String amount, String plan, String status,
                               boolean goalActive) {
    }

    /**
     * §四 每周底线的<b>全部</b>行，每行按「目标是否进行中」打上 {@code goalActive}。
     *
     * <p>为什么返回全部而不是只返回进行中的：暂缓 / 已取消目标的底线条目不是「不存在」，
     * 而是「这一周先不做」。界面要能把它们按折叠/淡出的方式显示出来（暂缓不是删除也不是放弃），
     * 所以这里如实返回、由 {@code goalActive} 标注归属，由界面决定怎么呈现。
     *
     * <p><b>名字比对忽略两侧空白</b>：手写表格常在单元格里多留空格
     * （{@code |  本学期学校课程  |}），而 {@code TableBlock.splitCells} 只去掉两侧各一个空格，
     * 于是 {@code Goal.name()} 与 {@code Baseline.goal()} 会逐字不等。忽略两侧空白后，
     * 这种「看起来一样、字节差一点」的行才不会被错判成孤立行。
     */
    public static List<BaselineView> baselines(PlanDocument document) {
        Objects.requireNonNull(document, "document");
        Set<String> activeGoalNames = new HashSet<>();
        for (Goal goal : document.goals()) {
            if (goal.status() == GoalStatus.ACTIVE) {
                activeGoalNames.add(goal.name().strip());
            }
        }
        List<BaselineView> views = new ArrayList<>();
        for (Baseline baseline : document.baselines()) {
            boolean active = activeGoalNames.contains(baseline.goal().strip());
            views.add(new BaselineView(baseline.goal(), baseline.item(), baseline.amount(),
                    baseline.plan(), baseline.status(), active));
        }
        return List.copyOf(views);
    }

    /** §三 课程（本学期）的全部行，按文件顺序（本周视图里课程不按目标过滤）。 */
    public static List<Course> coursesThisWeek(PlanDocument document) {
        Objects.requireNonNull(document, "document");
        return document.courses();
    }

    /** §五 生活准则的逐行文本（去掉 {@code - } 前缀与行尾换行）；与 {@link PlanService} 同一通道。 */
    public static List<String> lifeRules(PlanDocument document) {
        Objects.requireNonNull(document, "document");
        return PlanService.lifeRulesOf(document.render());
    }
}
