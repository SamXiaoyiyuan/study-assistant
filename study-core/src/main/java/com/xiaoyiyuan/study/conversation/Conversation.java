package com.xiaoyiyuan.study.conversation;

import com.xiaoyiyuan.study.ai.AiMessage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一段 AI 对话（计划 2 Task 8）。
 *
 * <p>字段与计划逐字一致：{@code id} 由调用方给（界面用 {@code UUID.randomUUID().toString()}），
 * 本类<b>不</b>生成 id，也不做任何路径处理——把 id 拼成文件名、以及「什么样的 id 才安全」，
 * 全部收在 {@link ConversationStore} 一处。
 *
 * <p><b>为什么不在 {@code com.xiaoyiyuan.study.ai} 包里</b>：{@code ai} 包有一条可执行的字节码护栏
 * （见 {@code AiClientTest}）——那里<b>不得出现</b> {@code java/nio/file/*}。对话历史要落盘、
 * 要读目录，于是它与 {@link ConversationStore} 一起放在本包；{@code conversation} 可以依赖 {@code ai}
 * （它要用 {@link AiMessage}），反向不成立（{@code ai} 不该知道历史是怎么存的）。这条方向由
 * {@code ConversationStoreTest} 里的形态断言守着。
 *
 * <p>紧凑构造器只做两件事：非空校验与 {@code List.copyOf} 的防御性拷贝。
 * record 的不变性靠的是「拿到手就没人能再改它」——调用方随后往自己的 {@code ArrayList} 里
 * 追加消息，不该顺带改掉这段对话的内容。
 */
public record Conversation(String id, String title, Instant startedAt, List<AiMessage> messages) {

    public Conversation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
    }
}
