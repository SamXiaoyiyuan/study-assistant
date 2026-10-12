package com.xiaoyiyuan.study.ai;

import java.util.List;

/**
 * 一份「将要发送、但还没有获得用户确认」的请求（spec §7.5 的逐次确认门）。
 *
 * <p><b>{@link #promptText()} 是界面唯一该显示的东西</b>：它是这次要发出去的完整文本
 * （提示词 + 全部消息），不是摘要、不是节选、不是脱敏版本。用户看着它点「同意」，
 * 所以它必须与真正发出去的正文逐字一致——情绪记录属于私密数据，「预览被加工过」
 * 会让「我确认过了」这句话当场失效。为此本类是<b>不可变值快照</b>：
 * {@code messages} 是 {@link List#copyOf} 的结果，构造完成后谁也无法再改它。
 *
 * <p><b>文本由调用方组装、不在这里读盘</b>：提示词的取用属于 {@code prompt.PromptLibrary}
 * 的责任（那要读盘，而 {@code ai} 包在编译期就拿不到文件能力，由 {@code AiClientTest} 的
 * 字节码护栏守着）。所以「完整文本」必须由调用方算好之后传进来，
 * 这里只负责把它与消息列表一起冻结成一份可审计的快照。
 *
 * @param role       角色名（{@link PromptRole#displayName()}），供界面标注与事后审计；
 *                   它是<b>标签</b>，不参与 {@link #promptText()} 的组装
 * @param promptText 将要发送的完整文本，逐字展示给用户确认
 * @param messages   将要下发的消息（值快照，不可变）
 */
public record PendingRequest(String role, String promptText, List<AiMessage> messages) {

    public PendingRequest {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException(
                    "待发送请求必须带上角色名：确认界面要让用户知道这是在跟哪个助手说话。");
        }
        if (promptText == null || promptText.isEmpty()) {
            throw new IllegalArgumentException(
                    "待发送请求必须带上完整文本：空的预览会让「我确认过了」变成一句空话。");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("待发送请求至少要有一条消息。");
        }
        messages = List.copyOf(messages);
    }

    /**
     * 用角色枚举组装一份待发送请求。
     *
     * @param role       角色
     * @param promptText 将要发送的完整文本（由调用方组装，含提示词）
     * @param messages   将要下发的消息
     */
    public static PendingRequest of(PromptRole role, List<AiMessage> messages, String promptText) {
        if (role == null) {
            throw new IllegalArgumentException("PromptRole 不能为 null。");
        }
        return new PendingRequest(role.displayName(), promptText, messages);
    }

    /**
     * 由消息列表算出「完整文本」：逐条消息的正文，用 {@code \n} 连接。
     *
     * <p>这里刻意<b>不加任何角色前缀</b>：{@link AiClient} 发出去的就是 {@code messages} 数组，
     * 而确认门要保证的是「用户看到的正文」＝「发出去的正文」。多一个 {@code 用户:} 之类的装饰，
     * 预览里就会多出一些不会被发送的字符——那正是「预览与实发不一致」的最常见来源。
     *
     * <p>这条规则是 {@link ConfirmingAiSession} 与计划 2 Task 9
     * （{@code EmotionAnalysis.build}）之间的契约：谁组装文本，谁就用它。
     */
    public static String joinContents(List<AiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空：一次请求至少要带一条消息。");
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            if (index > 0) {
                text.append('\n');
            }
            text.append(messages.get(index).content());
        }
        return text.toString();
    }
}
