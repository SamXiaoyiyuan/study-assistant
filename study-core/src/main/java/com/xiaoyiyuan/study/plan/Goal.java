package com.xiaoyiyuan.study.plan;

/**
 * 一个目标（spec §5.2 的核心模型；对应 §二 目标清单的一行）。
 *
 * <p>字段与表头一一对应：{@code 目标 / 状态 / 优先级 / 时间窗 / 说明}。
 *
 * <p><b>这里不做「表格是否会写坏」的校验</b>：本记录只是值对象，任何调用方都可以构造它。
 * 真正把值写进表格的唯一通道是 {@link PlanDocument} 的写入口，那里有一道统一闸门
 * （拒绝裸 {@code |} 与换行）。把校验放在两处，就一定会出现「这一处放行、那一处没放行」——
 * 而那是本模型唯一能把用户文件写坏的路径。
 *
 * @param name     目标名称，自由文本（如「本学期学校课程」）
 * @param status   四态之一（{@link GoalStatus}）；暂缓 / 已取消 的目标不参与排序
 * @param priority 优先级，数字越小越优先；暂缓或已取消时档案里写的是 {@code —}
 * @param window   时间窗，自由文本（如 {@code 2026.9–2027.1} 或「大三再定」）
 * @param note     一句话说明
 */
public record Goal(String name, GoalStatus status, String priority, String window, String note) {
}
