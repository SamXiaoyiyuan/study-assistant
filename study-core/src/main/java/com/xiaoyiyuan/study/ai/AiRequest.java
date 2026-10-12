package com.xiaoyiyuan.study.ai;

import java.util.List;

/**
 * 一次发往 OpenAI 兼容端点的请求体。
 *
 * <p>字段名与 OpenAI 协议一致（{@code model} / {@code messages} / {@code stream}），由 Gson 逐字
 * 序列化——所以这里<b>不能</b>加任何额外字段或改名，否则请求体就不兼容了。
 *
 * <p><b>刻意只提供 {@code stream=false} 的构造</b>：本任务交付的是非流式客户端（spec §7.1 的
 * 流式在后续任务）。把 {@code stream} 作为不可绕过的值，比留一个布尔参数更安全——
 * 后者只要有一个调用点传了 {@code true}，就会得到一个「服务端按 SSE 回、客户端却等整包 JSON」
 * 的请求，症状是两边都像在正常等待。
 *
 * <p>{@code messages} 存的是<b>值快照</b>（{@link List#copyOf}）：请求一旦构造完成，
 * 「将要发给服务端的内容」就固定了。逐次确认门（spec §7.5）要求界面先把完整请求展示给用户，
 * 若列表还能被外部改，用户确认过的内容和真正发出去的内容就可能不是同一份。
 *
 * @param model    模型名
 * @param messages 对话消息，按顺序下发；至少一条
 * @param stream   是否流式；本类只产出 {@code false}
 */
public record AiRequest(String model, List<AiMessage> messages, boolean stream) {

    public AiRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空：请先填写模型名（例如 deepseek-chat）。");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空：一次请求至少要带一条消息。");
        }
        messages = List.copyOf(messages);
    }
}
