package com.xiaoyiyuan.study.plan;

/**
 * 一个阶段（spec §5.5；对应 §六 阶段排期的一行）。
 *
 * <p>字段与表头一一对应：{@code 阶段 / 时间窗 / 主攻 / 验收标准}。
 *
 * <p><b>行序是语义</b>（阶段 A → A+ → B → C…），因此这张表是有序表：{@link PlanDocument}
 * 读出来的顺序就是文件里的行序，界面用 {@code movePhase} 上移/下移，绝不按阶段名或时间窗重排。
 * 本条模型里没有「归属目标」字段：spec §5.5 提到阶段可归属于某个目标，但表结构里没有这一列，
 * 凭空加一列会改坏用户的表——归属信息留在 §八 的散文里。
 *
 * @param phase      阶段名（如 {@code A}）
 * @param window     时间窗，自由文本
 * @param focus      主攻，这一阶段做什么
 * @param acceptance 验收标准
 */
public record Phase(String phase, String window, String focus, String acceptance) {
}
