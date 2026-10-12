package com.xiaoyiyuan.study.ai;

import java.util.List;

/**
 * 带逐次确认门的 AI 会话（spec §7.5）：<b>本类唯一的职责是「不确认就不发」</b>。
 *
 * <p>{@link AiClient} 拿到 {@link AiRequest} 就会发出去——它不该关心「用户同意了吗」。
 * 于是「同意」这件事必须发生在两者之间，且只有一条路：本类的每个发送方法都先组装
 * {@link PendingRequest}、交给 {@link ConsentGate}、得到 {@code true} 之后才构造
 * {@link AiRequest} 并调用客户端。<b>返回 {@code false} 时不构造请求、不碰网络、不留副作用。</b>
 *
 * <p><b>为什么拒绝用 {@link IllegalStateException} 而不是专门的返回值</b>（计划 2 Task 7 的
 * 规则允许二者择一）：本类的发送方法已经有受检异常出口（{@link AiFailure} 表示「发出去了但没成」）。
 * 若把「用户没同意」也塞进同一个出口，界面就无法区分「被拒绝」与「网络失败」，而这两种情况对
 * 用户的意义完全相反——一个该重试，另一个不该。所以拒绝走运行期异常，并且带一句<b>固定措辞</b>
 * （{@link #refusedMessage()}），界面可以据此识别；{@link AiFailure} 则保持「真的发过一次但没成功」
 * 的严格含义。
 *
 * <p><b>逐次</b>的含义落在代码形状上：每次都重新问 gate，本类<b>不缓存任何同意</b>。同理，
 * 本类不暴露任何收 {@link AiRequest} 的公开方法、也没有 {@code raw} / {@code bypass} 之类
 * 的后门；这两个形状由 {@code ConsentGateTest} 用反射钉住。
 *
 * <p><b>{@link #lastSentPreview()} 是审计口</b>：它只在「真的发出去」的那一刻被写入，
 * 因此它永远等于最近一次用户同意过的那份文本。被拒绝的尝试不会改写它——否则界面回显出来的
 * 会是一段<b>从未发出</b>的内容，审计价值当场归零。
 */
public final class ConfirmingAiSession {

    /** 用户没同意时抛出的一句话；界面据此识别（不许绕过确认门）。 */
    private static final String REFUSED_MESSAGE =
            "用户没有确认这次发送，已停止：请求内容没有发给任何服务，"
                    + "也不会保留为「已发送」。确认门要求每次发送都单独获得确认。";

    private final AiClient client;
    private final ConsentGate gate;

    /** 最近一次<b>真正发出</b>的完整文本；初值为空串（还没有发过任何东西）。 */
    private volatile String lastSentPreview = "";

    /**
     * @param client 真正发请求的客户端
     * @param gate   确认门；<b>必填</b>——没有它这个类就没有存在的理由
     */
    public ConfirmingAiSession(AiClient client, ConsentGate gate) {
        if (client == null) {
            throw new IllegalArgumentException("AiClient 不能为 null。");
        }
        if (gate == null) {
            throw new IllegalArgumentException(
                    "ConsentGate 不能为 null：没有确认门就不该用这个类（spec §7.5）。");
        }
        this.client = client;
        this.gate = gate;
    }

    /**
     * 确认后发一次非流式请求。
     *
     * @param role     角色（供确认界面标注「这是在跟哪个助手说话」）
     * @param messages 将要下发的消息；其正文用 {@code \n} 连接后就是展示给用户的完整文本
     * @return 助手回复正文
     * @throws IllegalStateException 用户没有确认；此时<b>没有发出任何请求</b>
     * @throws AiFailure             已确认、真的发过，但请求没成功
     * @throws InterruptedException  调用线程被中断（用户取消）
     */
    public String complete(PromptRole role, List<AiMessage> messages)
            throws AiFailure, InterruptedException {
        return client.complete(confirmed(role, messages), false);
    }

    /**
     * 确认后发一次流式请求（SSE）。确认门与 {@link #complete} <b>完全相同</b>：
     * 流式不是「更轻的」请求，它同样会把情绪记录原文送出去。
     *
     * @throws IllegalStateException 用户没有确认；此时一个增量都不会产生、桩上不会有任何请求
     * @throws AiFailure             已确认、真的发过，但请求没成功（流中断时已收到的增量不回滚）
     * @throws InterruptedException  调用线程被中断（用户取消）
     */
    public void stream(PromptRole role, List<AiMessage> messages, AiStreamListener listener)
            throws AiFailure, InterruptedException {
        if (listener == null) {
            throw new IllegalArgumentException("AiStreamListener 不能为 null。");
        }
        client.stream(confirmed(role, messages), listener);
    }

    /**
     * 组装好「将要发送的内容」但<b>什么都不发</b>：供界面在用户还没决定前先把原文显示出来。
     *
     * <p>这是确认门的第一半（界面动作顺序的第 1 步：只做预览）。它不改动 {@link #lastSentPreview()}、
     * 不咨询 gate、不碰网络；但它与发送路径共用同一段组装代码，
     * 所以「界面上显示的那份」与「真正会发出去的那份」不可能各算各的。
     *
     * @throws IllegalArgumentException 角色为 {@code null} 或消息为空
     */
    public PendingRequest preview(PromptRole role, List<AiMessage> messages) {
        return pending(role, messages);
    }

    /**
     * 最近一次<b>真正发出</b>的完整文本；没有发过任何东西时是空串。
     *
     * <p>界面用它回显「刚才发出去的是这段」，审计也用它——所以它只由发送路径写入。
     */
    public String lastSentPreview() {
        return lastSentPreview;
    }

    /** 用户没同意时的固定措辞，供界面识别这次「什么都没发」。 */
    public static String refusedMessage() {
        return REFUSED_MESSAGE;
    }

    /**
     * 组装 → 过确认门 → 返回可发送的消息快照。这是本类唯一通往网络的入口。
     *
     * <p>顺序是刻意的：<b>先</b>确认、<b>后</b>交给客户端组装请求。反过来（先组装再确认）
     * 在行为上看不出差别，但它意味着「一份可以发出去的请求已经在手里了」，
     * 任何一处漏掉判断都会静默地把它发出去。
     */
    private List<AiMessage> confirmed(PromptRole role, List<AiMessage> messages) {
        PendingRequest pending = pending(role, messages);

        boolean approved;
        try {
            approved = gate.confirm(pending);
        } catch (RuntimeException gateBlewUp) {
            // 确认门自己出错（例如弹窗构建失败）时绝不能默认放行——失败要往「不发」的方向倒
            throw new IllegalStateException(
                    REFUSED_MESSAGE + "（确认界面本身出了状况：" + gateBlewUp + "）", gateBlewUp);
        }
        if (!approved) {
            // 到此为止：不调用 client、不动 lastSentPreview
            throw new IllegalStateException(REFUSED_MESSAGE);
        }

        lastSentPreview = pending.promptText();
        return pending.messages();
    }

    /**
     * 组装待发送内容（值快照 + 逐字预览文本）。发送路径与 {@link #preview} 共用这一处，
     * 所以界面预览过的就是将要发送的。
     */
    private static PendingRequest pending(PromptRole role, List<AiMessage> messages) {
        if (role == null) {
            throw new IllegalArgumentException("PromptRole 不能为 null。");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空：一次请求至少要带一条消息。");
        }
        // List.copyOf 会在含 null 元素时抛 NPE：一条空消息不是一次能发出去的请求
        List<AiMessage> snapshot = List.copyOf(messages);
        return PendingRequest.of(role, snapshot, PendingRequest.joinContents(snapshot));
    }
}
