package com.xiaoyiyuan.study.ai;

/**
 * 逐次确认门（spec §7.5）：<b>界面实现</b>，每次发送前被咨询一次；返回 {@code true} 才允许发出去。
 *
 * <p>把它做成一个只有一件方法的接口，是为了让「谁来决定」留在界面侧，而「不确认就一个字节都不发」
 * 留在 {@link ConfirmingAiSession} 侧。于是这条约束的两半各有各的测试：
 * 界面侧测弹窗与「取消」，核心侧测「未确认时桩上的请求数为 0」。
 *
 * <p><b>必须是函数式接口</b>：计划 3 的界面会传一个弹窗实现，测试里则传
 * {@code request -> false} / {@code request -> true} 这样的 lambda。接口里刻意<b>没有</b>
 * 默认方法——多一个 {@code alwaysAllow()} 之类的便利方法，就是给未来留一条绕过确认的路。
 *
 * <p><b>拒绝不是异常</b>：{@code false} 是正常返回值（用户按了「取消」）。调用方
 * （{@link ConfirmingAiSession}）据此当场停手，不构造请求、不碰网络。
 */
@FunctionalInterface
public interface ConsentGate {

    /**
     * 把将要发送的完整内容交给用户过目，并取回他的决定。
     *
     * <p>实现方（界面）应当把 {@link PendingRequest#promptText()} <b>原文</b>显示出来：
     * 可滚动、可选中、可复制，不摘要、不截断、不折叠。本方法<b>可能被调用多次</b>
     * ——每一次发送各问一次，实现方不得记住上一次的答案。
     *
     * <p>实现方是<b>阻塞</b>的（等用户在弹窗上做决定），所以它会在调用发送的那条线程上被调用。
     *
     * @param request 将要发送的完整内容（含逐字预览文本）
     * @return {@code true} 才允许发送；{@code false} 表示用户没有同意
     */
    boolean confirm(PendingRequest request);
}
