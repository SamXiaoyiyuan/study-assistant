package com.xiaoyiyuan.study.ai;

/**
 * 一次 AI 请求的失败：带分类与一句用户可读的中文说明。
 *
 * <p><b>为什么是受检异常</b>：失败是要被界面看见并处理的东西（中性提示 + 重试，spec §7.6）。
 * 用受检异常，调用方在编译期就必须决定「怎么告诉用户」，而不是让一个未被捕获的运行时异常
 * 一路冒到顶层，最后变成一句「出了点问题」。
 *
 * <p><b>消息必须是中性、可执行的</b>（全局约束的禁用词：未完成 / 逾期 / 落后 / 断签 / 失败 /
 * 警告 / 已放弃 / 未坚持）。措辞刻意只说「发生了什么 + 你可以做什么」，不评价用户，
 * 也不给请求本身下结论——例如 5xx 说的是「服务端返回 500」，而不是「你的请求坏了」。
 *
 * <p><b>不携带响应体原文作为消息</b>：响应体可能很长（网关 HTML 错误页），也未必是给人看的；
 * 需要看细节时应该走日志，而不是塞进界面的一句提示。{@code cause} 里保留原始异常，
 * 排查时仍能拿到栈。
 */
public final class AiFailure extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * 禁止出现在用户可见消息里的界面禁用词（spec §8.2）。
     *
     * <p><b>包内可见而不是 private</b>：它是本条款的<b>唯一一份</b>词表。除了本类的构造器，
     * {@link AiClient} 在拼接「底层异常的文案」时也要用同一份清单过滤——两份清单迟早会漂移，
     * 而漂移的代价正好是本条款要避免的那件事（禁用词被显示给用户）。保持包内可见，
     * 既让复用成为可能，又不把它变成对外公开面。
     */
    static final String[] BANNED_WORDS = {
        "未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持"
    };

    private final AiFailureKind kind;

    /**
     * @param kind    失败分类
     * @param message 用户可读的中文说明；不得为空、不得含界面禁用词
     * @param cause   原始异常；没有则为 {@code null}
     * @throws IllegalArgumentException 消息为空或含禁用词——这是编码错误，越早暴露越好
     */
    public AiFailure(AiFailureKind kind, String message, Throwable cause) {
        super(validate(kind, message), cause);
        this.kind = kind;
    }

    /** 无原始异常的失败。 */
    public AiFailure(AiFailureKind kind, String message) {
        this(kind, message, null);
    }

    /** 失败分类；界面据此决定「重试」按钮的含义。 */
    public AiFailureKind kind() {
        return kind;
    }

    /**
     * 校验消息：这是本类唯一值得拦住的地方——禁用词一旦写进消息，就会被原样显示给正在难受的
     * 用户，而「文案问题」在 review 里最难被发现。所以由类型自己守住，而不是靠人工检查。
     */
    private static String validate(AiFailureKind kind, String message) {
        if (kind == null) {
            throw new IllegalArgumentException("AiFailureKind 不能为 null。");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("失败消息不能为空：界面要把它直接显示给用户。");
        }
        for (String banned : BANNED_WORDS) {
            if (message.contains(banned)) {
                throw new IllegalArgumentException(
                        "失败消息不得含界面禁用词「" + banned + "」（spec §8.2）；收到：" + message);
            }
        }
        return message;
    }

    /**
     * 消息里第一个命中的界面禁用词；一个都没有时返回 {@code null}。
     *
     * <p>与 {@link #validate} 用同一份 {@link #BANNED_WORDS}：这里回答的是「有没有」，
     * 构造器回答的是「有就拒绝」。两者分开是为了让「拼接消息的调用方」（{@link AiClient}）
     * 能在<b>交给构造器之前</b>先处理掉禁用词——等到构造器抛异常时，那次请求的
     * {@link AiFailure} 契约就已经破了。
     */
    static String firstBannedWord(String message) {
        if (message == null) {
            return null;
        }
        for (String banned : BANNED_WORDS) {
            if (message.contains(banned)) {
                return banned;
            }
        }
        return null;
    }

    /**
     * 把消息里出现的界面禁用词换成中性占位符 {@value #REDACTION_PLACEHOLDER}；没有命中就原样返回。
     *
     * <p><b>为什么是「替换」而不是「拒绝」</b>：这里是消息<b>出口</b>，不是编码现场。
     * 消息里那段文字的分母不归 {@code ai} 包控制（JDK 的 {@code IOException}／Gson 的解析异常／
     * 用户填的 base_url 都可能带出任何字），所以不能因为其中恰好出现一个禁用词就把整次失败
     * 变成 {@link IllegalArgumentException}——那会让调用方拿不到 {@link AiFailure}，
     * 界面也就无法区分「网络不通」与「程序出错」。替换既守住条款，又保住契约。
     */
    static String redactBannedWords(String message) {
        if (message == null) {
            return null;
        }
        String redacted = message;
        for (String banned : BANNED_WORDS) {
            redacted = redacted.replace(banned, REDACTION_PLACEHOLDER);
        }
        return redacted;
    }

    /** 被过滤掉的禁用词的替身：本身中性、且在界面上看得出「此处原本有一处不对劲的措辞」。 */
    static final String REDACTION_PLACEHOLDER = "［措辞已中性化］";
}
