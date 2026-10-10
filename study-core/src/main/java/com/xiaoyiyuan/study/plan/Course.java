package com.xiaoyiyuan.study.plan;

/**
 * 一门课（spec §5.3；对应 §三 课程（本学期）的一行）。
 *
 * <p>字段与表头一一对应：{@code 课程 / 本周任务 / 截止 / 状态}。
 *
 * <p>{@code status} 刻意是自由文本而不是枚举：课程状态里既有「待回报」这类本项目的无数据文案，
 * 也有用户自己写的话，硬编码成枚举会把用户的话变成解析错误。目标状态（{@link GoalStatus}）不同——
 * 它的四态是领域规则，且状态决定「进不进本周」，所以那边必须严格。
 *
 * @param name       课程名
 * @param weeklyTask 本周任务，自由文本（如「第 4 章作业」）
 * @param due        截止，自由文本；<b>仅作展示，不触发提醒</b>，因此不做日期解析
 * @param status     状态，自由文本（无数据时用「待回报」/「未回报」）
 */
public record Course(String name, String weeklyTask, String due, String status) {
}
