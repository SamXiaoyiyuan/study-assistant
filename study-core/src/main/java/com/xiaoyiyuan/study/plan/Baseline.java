package com.xiaoyiyuan.study.plan;

/**
 * 一条每周底线（spec §5.4；对应 §四 每周底线的一行）。
 *
 * <p>字段与表头一一对应：{@code 目标 / 项 / 量 / 怎么安排 / 状态}。比旧档案多了一列「目标」，
 * 这样底线条目可以改挂到别的目标下。
 *
 * <p>{@code amount} 是<b>自由文本</b>（如「每周 2~4 小时，≥2 次，每次 ≥20 分钟」），
 * 刻意不做单位解析：「量」一旦被强行结构化，就会丢语义并限制表达（spec §5.4）。
 *
 * @param goal   挂在哪一个目标下（与 {@link Goal#name()} 对应的文本，不是引用）
 * @param item   项，做什么
 * @param amount 量，自由文本，不做单位解析
 * @param plan   怎么安排，自由文本
 * @param status 状态，自由文本（无数据时用「待回报」/「未回报」）
 */
public record Baseline(String goal, String item, String amount, String plan, String status) {
}
