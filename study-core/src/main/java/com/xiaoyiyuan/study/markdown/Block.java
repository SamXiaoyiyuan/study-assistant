package com.xiaoyiyuan.study.markdown;

/**
 * 文档里的一段内容。
 *
 * <p>它是 P1（不修改就写回，字节完全相同）的承载体：{@link #render()} 是这段内容唯一的
 * 字节来源，{@link MarkdownDocument#render()} 只是把所有块的 {@code render()} 顺序拼接，
 * 自己不做任何收尾修饰。因此只要每个块能逐字节还原本段原文，整篇文档就天然保真。
 *
 * <p>没有「脏」标记就无法同时满足 P1 与 P2：块必须一直留着<b>原文</b>（哪怕后续被改过），
 * 才能保证「没改过的部分一个字节都不动」。所以 {@code dirty()} 为 false 时 {@link #render()}
 * 一律返回原文，只有显式 {@link #markDirty()} 之后才认 {@code currentText}。
 *
 * <p><b>本接口必须 public</b>：Task 4 及之后的表格、计划模型、迁移分别位于
 * {@code com.xiaoyiyuan.study.plan} 与 {@code com.xiaoyiyuan.study.migrate} 等<b>不同包</b>，
 * 它们都要消费这个模型（Task 2 曾因接口默认包可见而返工一轮）。
 */
public interface Block {

    /**
     * 这块内容是否已被显式标记为改动过。
     *
     * <p>{@code false} 时 {@link #render()} 必须逐字节返回原文；这一点是 P1 的基础，
     * 不允许任何实现凭内容比对「猜」脏否。
     */
    boolean dirty();

    /** 显式声明本块已被改动，此后 {@link #render()} 返回新内容。 */
    void markDirty();

    /** 清除脏标记；之后 {@link #render()} 重新返回受保护的原文。 */
    void clearDirty();

    /** 本块当前的字节内容。未改动的块必须返回原文，且不做任何空白修剪或补齐。 */
    String render();
}
