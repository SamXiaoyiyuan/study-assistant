package com.xiaoyiyuan.study.plan;

import java.util.Objects;

/**
 * 某个建模章节定位失败（Review Focus 4）。
 *
 * <p><b>名字叫 Missing，管的却是「定位失败」这一整类情况</b>，共四种，都是「不能改」：
 * <ol>
 *   <li>标题一次都没出现——文档不是这个结构，或者用户把标题改掉了；</li>
 *   <li>标题出现了两次以上——<b>不知道该改哪一张表，猜就是写错表</b>，这比缺一节更危险；</li>
 *   <li>标题在，但下面没有可识别的结构表（被删了，或列数不一致被降级成原样块）；
 *       此时若「顺手造一张表」，就等于改写了不属于本模型的块；</li>
 *   <li>表在，但表头与规格不符——列对不上就无法判断哪一列是什么，按位置硬读就是读错字段。</li>
 * </ol>
 *
 * <p>这四条共同支撑同一句硬要求：<b>定位不到就不改，更不能写。</b>宁可让用户看到一句能读懂的
 * 报错去修文件，也不许 App 挑一张「差不多」的表凑合着写。
 *
 * <p>消息由本类自己拼：<b>必定包含</b> {@link PlanSection#label()}，因此不可能出现「异常提示里
 * 只有枚举名」这种看不懂的报错。调用方只负责给「为什么定位失败」这一句原因。
 */
public final class SectionMissingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 定位失败的是哪一节；界面据此把用户直接带到那一节。 */
    private final PlanSection section;

    /**
     * @param section 未能定位的章节
     * @param reason  人能读懂的原因（例如「标题出现了 2 次」）；前缀由本类补，调用方不必重复章节名
     */
    public SectionMissingException(PlanSection section, String reason) {
        super("章节「" + Objects.requireNonNull(section, "section").label() + "」无法定位：" + reason);
        this.section = section;
    }

    /** 未能定位的章节。 */
    public PlanSection section() {
        return section;
    }
}
