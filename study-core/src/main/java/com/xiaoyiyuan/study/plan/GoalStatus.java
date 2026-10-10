package com.xiaoyiyuan.study.plan;

/**
 * 目标的四种状态（spec §5.2）。
 *
 * <p><b>四态一律中性</b>：{@code 暂缓} 既不是删除也不是放弃（用户可能大三重新捡起，也可能决定不考），
 * {@code 已取消} 也不是失败。因此这里没有、也不许有任何被建模成「失败 / 落后 / 已放弃」的状态，
 * 界面文案也不得使用禁用词（见 {@code GoalStateTest#deferredAndCancelled_areNotFailures}）。
 *
 * <p>{@link #fromLabel(String)} 遇到不认识的文本<b>抛异常并带上原文</b>，绝不静默降级：
 * 「暂缓」若被悄悄读成「进行中」，用户的目标就会进入本周检查——那是数据损坏，不是容错。
 * 这一点与「未知状态怎么显示」无关：显示是界面的事，读文件必须严格。
 */
public enum GoalStatus {

    /** 当前在做：进入「本周该做什么」。 */
    ACTIVE("进行中"),

    /** 现在不排时间，将来可能启用：不进入「本周」、不计入底线，历史完整保留，一键可恢复。 */
    DEFERRED("暂缓"),

    /** 决定不做：归入「已结束」，不显示为失败，记录保留以备回顾。 */
    CANCELLED("已取消"),

    /** 做完了：归入「已结束」。 */
    DONE("已完成");

    /** 文件与界面里用的中文标签（与 spec 逐字一致，表格里写的就是它）。 */
    private final String label;

    GoalStatus(String label) {
        this.label = label;
    }

    /** 人类可读的状态名；写表格与比对文本时都用它，而不是枚举名。 */
    public String label() {
        return label;
    }

    /**
     * 由文件里的文本取状态。
     *
     * <p><b>严格相等，不做 trim、不做同义词兜底。</b>表格单元格的常规留白已由
     * {@code TableBlock.splitCells} 去掉；剩下的任何差异都是用户的真实输入，猜它就是改数据。
     *
     * @param label 表格单元格里的原文
     * @throws IllegalArgumentException 文本不是四态之一（消息里带原文，方便直接定位到那一格）
     */
    public static GoalStatus fromLabel(String label) {
        if (label == null) {
            throw new IllegalArgumentException("目标状态不能为 null（表格里的状态列必须有值）");
        }
        for (GoalStatus status : values()) {
            if (status.label.equals(label)) {
                return status;
            }
        }
        throw new IllegalArgumentException("无法识别的目标状态：" + label
                + "（只接受 进行中 / 暂缓 / 已取消 / 已完成 之一）");
    }
}
