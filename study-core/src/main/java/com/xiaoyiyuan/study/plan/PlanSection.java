package com.xiaoyiyuan.study.plan;

/**
 * 计划文件里被建模的五个章节（spec §4.4）。
 *
 * <p><b>只列被建模的节</b>：§五 生活准则、§七 本周、§八 考研（暂缓）、§九 周记录、§十 待确认
 * <b>不在</b>这里，也<b>不要求</b>它们存在——它们的内容是含 LaTeX 与 HTML 注释的散文，由
 * {@code MarkdownDocument} 的原样块逐字节保存，{@link PlanDocument} 永远不碰。
 * 把它们写进枚举会诱使后来者给它们也建模型，而那正是「App 改写用户散文」的起点。
 *
 * <p>{@link #label()} 是<b>人的说法</b>（与档案里的标题文字一致），供异常消息直接引用；
 * 枚举名是给代码看的。用户看到「章节『目标清单』定位失败」才知道去改哪一节，
 * 看到 {@code GOALS} 只会一头雾水。
 */
public enum PlanSection {

    /** §一 我的现状（表：项目 / 内容）。 */
    PROFILE("我的现状"),

    /** §二 目标清单（表：目标 / 状态 / 优先级 / 时间窗 / 说明）。 */
    GOALS("目标清单"),

    /** §三 课程（本学期）（表：课程 / 本周任务 / 截止 / 状态）。 */
    COURSES("课程（本学期）"),

    /** §四 每周底线（表：目标 / 项 / 量 / 怎么安排 / 状态）。 */
    BASELINES("每周底线"),

    /** §六 阶段排期（表：阶段 / 时间窗 / 主攻 / 验收标准；有序表）。 */
    PHASES("阶段排期");

    private final String label;

    PlanSection(String label) {
        this.label = label;
    }

    /** 章节标题里的那一截名字（不含中文序号），例如 {@code 目标清单}。 */
    public String label() {
        return label;
    }
}
