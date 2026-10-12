package com.xiaoyiyuan.study.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容的 AI 客户端（非流式 + 流式；spec §7.1）。
 *
 * <p>职责只有几件：把用户填的 {@code base_url} 规范化成最终请求地址、按 OpenAI 协议发一次
 * POST、把各种「没拿到正文」的情况分门别类地变成 {@link AiFailure}；再加上流式入口
 * {@link #stream(AiRequest, AiStreamListener)}（SSE）。
 *
 * <p><b>架构约束（spec §7.5 / §3.3）：本包不得依赖 {@code storage}，也不持有任何
 * {@link java.nio.file.Path} 写入口。</b>AI 只能返回纯文本；「写进文件」是界面拿到用户确认后
 * 另行触发的事。这不是靠自觉遵守的约定——{@code AiClientTest} 用反射扫描本包所有类的
 * 方法签名、构造器参数与字段类型，一旦有人加进一条 {@code Path} 就必须先改那条测试。
 *
 * <p><b>逐次确认门不在本类里</b>：确认发生在调用方（界面展示完整请求内容并得到用户同意）之后。
 * 本类拿到 {@link AiRequest} 就会发出去——{@link AiRequest} 存的是值快照，
 * 正是为了让「用户确认过的内容」与「真正发出去的内容」是同一份。流式入口同样<b>不新增任何
 * 绕过确认的路径</b>：它只把「同一个已确认的请求」按流式取字，除此之外什么也不做。
 */
public final class AiClient {

    /** OpenAI 兼容的对话补全路径（不含 base 的子路径部分）。 */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /** 规范化的版本段。 */
    private static final String V1_SEGMENT = "v1";

    /** 发出去的 JSON 编码：固定 UTF-8，与全局约束一致。 */
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final AiConfig config;
    private final HttpClient httpClient;

    /**
     * 用配置里的超时同时设置<b>连接超时</b>。
     *
     * <p>连接超时是必须的：请求级 {@code timeout} 覆盖整个请求，但「TCP 连不上」在某些网络下
     * 会安静地耗到系统默认（可达分钟级），期间界面只能显示「正在请求」。把它一起钉在用户配置的
     * 超时上，症状才是可预期的。
     */
    public AiClient(AiConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** 非流式入口（{@link #complete(AiRequest)} 的等价简写，见 {@link #complete(List, boolean)}）。 */
    public String complete(List<AiMessage> messages) throws AiFailure, InterruptedException {
        return complete(messages, false);
    }

    /**
     * 用本客户端自己配置里的模型名组装请求并发一次（非流式 / 流式）。
     *
     * <p><b>为什么需要这个入口</b>：{@link AiRequest} 要求调用方给出模型名，而模型名只有
     * {@code AiConfig} 里有。逐次确认门（{@link ConfirmingAiSession}）手里只有配置好的
     * {@code AiClient}，不该为了拿到一个模型名再去暴露配置（那会把 {@code api_key} 一起摊开）。
     * 让「组装请求」留在这里，模型名就只有这一处来源，
     * 也顺带保证 {@code stream} 字段与所走的入口<b>不可能对不上</b>
     * （{@code AiClient} 会拒绝 {@code stream=false} 走流式入口，反之亦然）。
     *
     * <p>行为与 {@link #complete(AiRequest)} / {@link #stream(AiRequest, AiStreamListener)}
     * 完全一致：失败分类、超时、密钥脱敏都在同一段代码里。
     *
     * @param messages 对话消息；按顺序下发，至少一条
     * @param streaming {@code true} 走流式（SSE），{@code false} 走整包回复
     */
    public String complete(List<AiMessage> messages, boolean streaming)
            throws AiFailure, InterruptedException {
        if (streaming) {
            throw new IllegalArgumentException(
                    "流式请求没有单次返回的正文：请用 stream(messages, listener) 逐段接收。");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空：一次请求至少要带一条消息。");
        }
        return complete(new AiRequest(config.model(), List.copyOf(messages), false));
    }

    /**
     * 用本客户端自己配置里的模型名组装并发出一次流式请求（SSE）。
     *
     * <p>与 {@link #complete(List, boolean)} 同一个理由：模型名只有 {@code AiConfig} 有。
     *
     * @param messages 对话消息；按顺序下发，至少一条
     * @param listener 增量与完成回调；在<b>调用线程</b>上被调用
     */
    public void stream(List<AiMessage> messages, AiStreamListener listener)
            throws AiFailure, InterruptedException {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空：一次请求至少要带一条消息。");
        }
        stream(new AiRequest(config.model(), List.copyOf(messages), true), listener);
    }

    /**
     * 测试注入用的构造器：行为与上面完全一致，只是复用调用方给的 client。
     */
    public AiClient(AiConfig config, HttpClient httpClient) {
        if (config == null) {
            throw new IllegalArgumentException("AiConfig 不能为 null。");
        }
        if (httpClient == null) {
            throw new IllegalArgumentException("HttpClient 不能为 null。");
        }
        this.config = config;
        this.httpClient = httpClient;
    }

    /**
     * 发一次非流式请求，返回助手回复的纯文本。
     *
     * <p><b>绝不返回 null、也绝不返回空串</b>：拿不到正文时一律抛 {@link AiFailure}
     * （{@link AiFailureKind#MALFORMED_RESPONSE}）。「静默地当成空回复」是最坏的一种——
     * 界面会显示一条没有内容的回答，而用户与界面都无从知道刚才到底发生了什么。
     *
     * @param request 请求内容（值快照；{@code stream} 必须为 {@code false}）
     * @return 助手回复正文
     * @throws AiFailure          请求没成功，或成功但响应体不是预期的 JSON 形状
     * @throws InterruptedException 调用线程被中断（用户取消）；原样抛出，不裹成 {@link AiFailure}
     */
    public String complete(AiRequest request) throws AiFailure, InterruptedException {
        if (request == null) {
            throw new IllegalArgumentException("AiRequest 不能为 null。");
        }
        if (request.stream()) {
            throw new IllegalArgumentException(
                    "本方法只处理非流式请求；流式请走流式入口（当前版本尚未提供）。");
        }

        HttpRequest httpRequest = buildRequest(request, false);

        // 这里刻意用 sendAsync + orTimeout，而不是 send(...)：
        // 请求级 timeout 只管到「响应头到达」为止（JDK 的语义如此）。服务端声明了 Content-Length
        // 却少发字节、又保持连接时，读完 body 的那一步不受它约束——评审实测 timeout=PT5S 下
        // 180.2 秒仍 alive，界面会永远停在「正在请求」，用户只能杀进程。
        // 「整次请求（含读完 body）」的期限必须在客户端侧另外钉一层。
        CompletableFuture<HttpResponse<String>> pending = httpClient.sendAsync(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = pending.orTimeout(requestDeadlineMillis(), TimeUnit.MILLISECONDS).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                pending.cancel(true); // 别把连接与读取线程留在后台
                throw timeoutFailure(e);
            }
            if (cause instanceof HttpConnectTimeoutException connectTimeout) {
                // 连都没连上就超时：对用户与「回得太慢」是同一件事——超过了设定的超时时间。
                throw timeoutFailure(connectTimeout);
            }
            if (cause instanceof HttpTimeoutException httpTimeout) {
                throw timeoutFailure(httpTimeout);
            }
            if (cause instanceof IOException io) {
                throw networkFailure(io, "");
            }
            throw networkFailure(new IOException(describe(cause)), "");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        }

        return readContent(response.statusCode(), response.body());
    }

    /**
     * 发一次流式请求（SSE），把增量逐段推给 {@code listener}。
     *
     * <p><b>三条容易被写错的规则，这里各由一段实现守住：</b>
     * <ol>
     *   <li><b>字节先攒齐再解码</b>：网络分块的边界可以落在 UTF-8 多字节字符中间（「半个汉字」）。
     *       逐块 {@code new String(bytes, UTF_8)} 会把它变成替换字符 U+FFFD——界面上一闪而过，
     *       用户看到的是一段乱码而不是报错。所以这里用一个增量 {@link CharsetDecoder}，
     *       解不出来的尾巴留在缓冲区里等下一个块。</li>
     *   <li><b>分帧只由零字符空行产生</b>：一个 {@code data:} 事件被拆到多个 TCP 块里是最常见的
     *       情况（网关缓冲、Nagle、代理都会造成），所以行缓冲必须一直攒到看见空行。按「到达的块」
     *       切分，会把一个 JSON 切成两半，两边都解析不出来。</li>
     *   <li><b>{@code [DONE]} 之后一个字节都不再回调</b>：服务端多发的内容属于协议外，界面绝不能
     *       在「已结束」之后继续追加。</li>
     * </ol>
     *
     * <p><b>失败语义</b>：非 2xx 先分类成 {@link AiFailure}，此时<b>一个增量都没发出去</b>；
     * 流在 {@code [DONE]} 之前断掉则保留已发出的增量（不回滚）并抛 {@link AiFailure}
     * （消息里带「回复不完整」，供界面照抄 spec §7.6 的措辞）。
     *
     * @param request  请求内容（值快照；{@code stream} 必须为 {@code true}）
     * @param listener 增量与完成回调；在<b>调用线程</b>上被调用
     * @throws AiFailure           请求没成功、响应不是 SSE、或流在结束标记之前断了
     * @throws InterruptedException 调用线程被中断（用户取消）；原样抛出，不裹成 {@link AiFailure}
     */
    public void stream(AiRequest request, AiStreamListener listener)
            throws AiFailure, InterruptedException {
        if (request == null) {
            throw new IllegalArgumentException("AiRequest 不能为 null。");
        }
        if (listener == null) {
            throw new IllegalArgumentException("AiStreamListener 不能为 null。");
        }
        if (!request.stream()) {
            throw new IllegalArgumentException(
                    "本方法只处理流式请求；非流式请走 complete（否则服务端回整包 JSON，"
                            + "而这里在等 data: 行，界面会一直停在等待状态）。");
        }

        HttpRequest httpRequest = buildRequest(request, true);
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw timeoutFailure(e);
        } catch (HttpTimeoutException e) {
            throw timeoutFailure(e);
        } catch (IOException e) {
            throw networkFailure(e, "没有拿到任何回复内容。");
        }

        classifyStatus(response.statusCode());
        SseReader reader = new SseReader();
        boolean cleanEof = false;
        InputStream body = response.body();
        // 事件间静默超时（修复轮 1 / 评审 Major-1 的流式那一半）：SSE 开始之后请求级 timeout
        // 就失效了，read() 会一直阻塞。「服务端发了响应头就再也不出字节」必须按超时收场，
        // 而不是把界面永远停在等待上。
        SilenceWatchdog watchdog = new SilenceWatchdog(config.timeout(), body);
        try (InputStream in = body) {
            watchdog.start();
            try {
                reader.consume(in, listener, watchdog::touch);
                cleanEof = true;
            } catch (IOException broken) {
                if (watchdog.fired()) {
                    throw silenceTimeoutFailure(broken);
                }
                if (Thread.currentThread().isInterrupted()) {
                    // 取消路径：JDK 的 HttpClient 在中断时会让这次读以 IOException 收场。
                    // 中断必须原样表达（AiFailureKind 刻意不含「用户取消」），
                    // 否则用户会看到「网络断了」这种与事实不符、且让人再去查网络的提示。
                    throw new InterruptedException("流式请求已被中断。");
                }
                throw networkFailure(broken, "已收到的内容保留在对话里，界面可以标记「回复不完整」。");
            }
        } catch (IOException interruptedClose) {
            if (watchdog.fired()) {
                throw silenceTimeoutFailure(interruptedClose);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("流式请求已被中断。");
            }
            throw networkFailure(interruptedClose, "已收到的内容保留在对话里，界面可以标记"
                    + "「回复不完整」。");
        } finally {
            watchdog.stop(); // 不留悬挂的守夜线程
        }
        if (cleanEof && !reader.sawDone()) {
            // 流干净地结束了（分块传输的终止符），但始终没出现 [DONE]：做收尾解码，
            // 让缓冲区里最后一个完整字符也有机会被交出来，然后按「不是一次有效回复」报错。
            reader.finish(listener);
        }
        if (!reader.sawDone()) {
            // 正常收尾只可能是 [DONE]。没见到它，说明连接在回复中途断了。
            // 已发出去的增量保留（onDelta 不回滚），由界面标记「回复不完整」。
            // 已经收到过正文却断在半路 → 网络问题；一个增量都没有 → 更像是服务端回的不是 SSE，
            // 分类成「响应不是预期形状」对用户更有指向性（该去查 base_url / 模型，而不是查网络）。
            // 收尾时若还丢掉了凑不出字符的字节，消息里点名（修复轮 2：不完整尾巴是显式丢弃的，
            // 丢弃这件事必须让用户看得见，否则那段逻辑与死代码没有区别）。
            String droppedTail = droppedTailNote(reader.droppedTrailingBytes());
            throw reader.sawAnything()
                    ? failure(AiFailureKind.NETWORK,
                            "网络连接在回复过程中断开了，已收到的内容保留在对话里，界面可以标记"
                                    + "「回复不完整」。请重试以获取完整回复。" + droppedTail, null)
                    : failure(AiFailureKind.MALFORMED_RESPONSE,
                            "服务端没有按流式（SSE）格式返回任何内容，也没有结束标记 [DONE]。"
                                    + "请确认 base_url 指向的是 OpenAI 兼容服务，且模型支持流式输出。"
                                    + droppedTail,
                            null);
        }
    }

    /** 被显式丢弃的不完整尾巴的一句话说明（没有丢就返回空串）。 */
    private static String droppedTailNote(int droppedBytes) {
        if (droppedBytes <= 0) {
            return "";
        }
        return "回复末尾有 " + droppedBytes + " 个字节凑不出一个完整字符，已丢弃。";
    }

    /** 组装一次请求。流式与非流式只差 {@code Accept} 头与请求体里的 {@code stream} 字段。 */
    private HttpRequest buildRequest(AiRequest request, boolean streaming) {
        String accept = streaming ? "text/event-stream" : "application/json";
        return HttpRequest.newBuilder(endpoint(config.baseUrl()))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Accept", accept)
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(request),
                        StandardCharsets.UTF_8))
                .build();
    }

    /** 连不上：消息里必须给出可执行的动作（检查 base_url 与网络），{@code partial} 补充取舍说明。 */
    private AiFailure networkFailure(IOException cause, String partial) {
        return failure(AiFailureKind.NETWORK,
                "连接不上服务（" + describe(cause) + "）。请检查 base_url（" + config.baseUrl()
                        + "）与网络是否可用，然后重试。" + partial,
                cause);
    }

    /** 超时消息统一在这里生成，保证两种超时给用户的是同一句话。 */
    private AiFailure timeoutFailure(Exception cause) {
        return failure(AiFailureKind.TIMEOUT,
                "请求超过超时时间（" + config.timeout() + "）。可以在设置里把 request_timeout 调大，"
                        + "然后重试。",
                cause);
    }

    /**
     * 事件间静默超时（修复轮 1）：服务端在约定时间内没有再吐一个字节。
     *
     * <p>措辞刻意与 {@link #timeoutFailure} 区分开：这里失败的原因是「没有任何新内容」，
     * 而不是「整次请求太久」——长回答本身是正常的，用户需要知道的是「卡住了、已收到的部分还在」。
     */
    private AiFailure silenceTimeoutFailure(Exception cause) {
        return failure(AiFailureKind.TIMEOUT,
                "等待服务端返回新内容超过超时时间（" + config.timeout()
                        + "），已停止读取。已收到的内容保留在对话里，界面可以标记「回复不完整」。"
                        + "可以在设置里把 request_timeout 调大，然后重试。",
                cause);
    }

    /**
     * 用户可见失败的唯一出口（修复轮 1 / 评审 Major-2）。
     *
     * <p>消息里会拼进底层异常的文案（{@link #describe}）、Gson 解析异常的文案、以及 base_url——
     * 这些文本的分母不归本类控制。模板本身「碰巧」不带 api_key，但碰巧不是一条性质；
     * 唯一的出口做一次替换，才能让「密钥不出现在任何用户可见消息里」由代码保证。
     */
    private AiFailure failure(AiFailureKind kind, String message, Throwable cause) {
        return new AiFailure(kind, redactSecret(message), cause);
    }

    /** 把消息里出现的 api_key 换成占位符（没出现就原样返回，保持消息可读）。 */
    private String redactSecret(String message) {
        String key = config.apiKey();
        if (key == null || key.isEmpty() || !message.contains(key)) {
            return message;
        }
        return message.replace(key, "***");
    }

    /**
     * 整次请求（含读完 body）的硬期限，毫秒。
     *
     * <p>{@link Duration#toMillis()} 对天文数字会抛 {@link ArithmeticException}：那种配置在
     * 语义上就是「几乎不超时」，绝不能因为一次换算溢出而变成「立刻超时」。亚毫秒的超时则抬到 1ms，
     * 避免「期限为 0」这种会被 JDK 当成「已经到点」的边界值。
     */
    private long requestDeadlineMillis() {
        try {
            return Math.max(1L, config.timeout().toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** 关掉一个流并吞掉 IOException：呼叫方多半已经因为别的原因在失败路径上。 */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 已经断开的连接再关一次会抛，这不是新信息
        }
    }

    /**
     * 把用户填的 {@code base_url} 规范化成最终的请求地址（<b>纯函数</b>：同输入必同输出，
     * 不读配置、不碰网络、不缓存）。
     *
     * <p>规则（spec §7.1 + 计划 2 Review Focus 3）：
     * <ol>
     *   <li>去掉首尾空白，再去掉<b>全部</b>尾部斜杠（只去一个会留下 {@code //v1/...}）；</li>
     *   <li>已经以 {@code /chat/completions} 结尾 → 原样返回；</li>
     *   <li>已经以 {@code /v1} 结尾 → 追加 {@code /chat/completions}；</li>
     *   <li>其余 → 追加 {@code /v1/chat/completions}。</li>
     * </ol>
     *
     * <p>第 2 条是防「用户直接贴了整条地址」被拼成 {@code .../completions/v1/chat/completions}；
     * 第 3 条是防 spec 点名的 {@code /v1/v1}。这两条判定都<b>按段比较</b>（而不是
     * {@code endsWith("/v1")}），否则子路径 {@code /openai/v1} 或主机名里含 {@code v1} 的地址
     * 会被误判。
     *
     * <p>带查询串的地址被<b>拒绝</b>而不是硬拼：追加路径的位置会落到 {@code ?} 之后，
     * 生成一个打到别处的地址——那比当场报错难查得多。
     *
     * <p>「必须能真正发出去」这条承诺落在实测口径上：非 http(s) 协议、主机名里的非 ASCII 字符、
     * 非法端口（{@code :-1}、{@code :0}、{@code :99999}）都在这里当场拒绝并给出中文原因，
     * 而不是留给 {@code HttpRequest.newBuilder} 抛一行英文。
     *
     * @param baseUrl 用户填的地址，如 {@code https://api.deepseek.com} 或 {@code https://x.com/v1}
     * @return 最终请求地址
     * @throws IllegalArgumentException 空白、缺 scheme/主机、带查询串、非 http(s) 协议、
     *                                  非法端口（消息点明原因）
     */
    public static URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "base_url 不能为空：请填写 AI 服务的地址（例如 https://api.deepseek.com）。");
        }
        String raw = baseUrl.trim();
        if (raw.indexOf('?') >= 0 || raw.indexOf('#') >= 0) {
            throw new IllegalArgumentException(
                    "base_url 不能带查询串或片段（? / #）：请只填到主机与可选子路径，"
                            + "例如 https://api.deepseek.com 或 https://x.com/v1；收到：" + raw);
        }
        // 去掉全部尾部斜杠。最后一个字符必定不是 '/'（上面已 trim），所以不会越界。
        String stripped = raw;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(
                    "base_url 只由斜杠组成，无法确定服务地址；收到：" + baseUrl);
        }

        URI parsed;
        try {
            parsed = URI.create(stripped);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "base_url 不是合法的地址（" + describe(e) + "）；收到：" + baseUrl, e);
        }
        // 必须能真正发出去：有 scheme 才谈得上协议，有 authority 才有主机（`https://` 两者都没有）。
        if (parsed.getScheme() == null || parsed.getRawAuthority() == null
                || parsed.getRawAuthority().isEmpty()) {
            throw new IllegalArgumentException(
                    "base_url 必须带上协议与主机（例如 https://api.deepseek.com）；收到：" + baseUrl);
        }
        // 「能真正发出去」这条承诺要落到 HttpClient 真的收不收上（实测口径）：
        //   · `https://x.com:-1`、`https://中文.com` → HttpRequest.newBuilder 抛 "unsupported URI"；
        //   · `ftp://x.com` / `file:///...` → 抛 "invalid URI scheme"。
        // 这些地址在 URI 层能解析，若不在这里拦住，用户看到的就是一行 JDK 英文异常。
        if (parsed.getHost() == null) {
            throw new IllegalArgumentException(
                    "base_url 的主机部分不是能发出去的形状（例如端口写成了 :-1，或主机名里有中文等"
                            + "非 ASCII 字符）；请按 https://api.deepseek.com 这样填写；收到：" + baseUrl);
        }
        String scheme = parsed.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "base_url 的协议必须是 http 或 https（HttpClient 不支持 " + scheme
                            + "）；收到：" + baseUrl);
        }
        int port = parsed.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException(
                    "base_url 的端口号必须在 1–65535 之间；收到：" + baseUrl);
        }

        String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
        String finalPath;
        if (hasChatCompletionsTail(path)) {
            finalPath = path;
        } else if (hasV1Tail(path)) {
            finalPath = path + CHAT_COMPLETIONS_PATH;
        } else {
            finalPath = path + "/" + V1_SEGMENT + CHAT_COMPLETIONS_PATH;
        }

        // 用「单字符串」构造而不是 new URI(scheme, authority, path, query, fragment)：
        // 后者会把空路径段规范化掉（用户填的 `https://x.com//` 会变成 `https://x.com/v1/...`），
        // 也就是悄悄改掉用户写下的地址。字符串拼接则逐字保留 raw path。
        // 这里没有查询串与片段（前面已拒绝），所以"scheme://authority + path"就是完整地址，
        // 不会有歧义。
        String rendered = parsed.getScheme() + "://" + parsed.getRawAuthority() + finalPath;
        try {
            return URI.create(rendered);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "无法由 base_url 拼出合法地址（" + describe(e) + "）：" + rendered, e);
        }
    }

    /**
     * 路径是否已经以 {@code /chat/completions} 结尾（用户直接贴了整条地址的情况）。
     *
     * <p>{@code /v1/chat/completions} 与 {@code /openai/v1/chat/completions} 都算；
     * 同时也接受少了版本段的 {@code /chat/completions}——那种地址已经是完整的调用地址，
     * 再补一次只会变成 {@code /chat/completions/v1/chat/completions}。
     */
    private static boolean hasChatCompletionsTail(String path) {
        return path.equals(CHAT_COMPLETIONS_PATH) || path.endsWith("/" + V1_SEGMENT + CHAT_COMPLETIONS_PATH);
    }

    /**
     * 路径的最后一个段是否恰为 {@code v1}（按段比较，不做子串匹配）。
     *
     * <p>{@code /v1}、{@code /openai/v1}、{@code /v1/} 都算；{@code /v1beta}、
     * {@code /myv1}、{@code /v1/extra} 都不算。
     */
    private static boolean hasV1Tail(String path) {
        int slash = path.lastIndexOf('/');
        String lastSegment = slash < 0 ? path : path.substring(slash + 1);
        return V1_SEGMENT.equals(lastSegment);
    }

    /** 把状态码与响应体翻译成「正文」或一次分类好的失败。 */
    private String readContent(int status, String body) throws AiFailure {
        classifyStatus(status);

        if (body == null || body.isBlank()) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "服务端返回了空响应体（HTTP " + status + "），没有可显示的内容。请稍后重试。", null);
        }
        JsonObject root = parseObject(body, "响应体不是合法的 JSON（HTTP " + status + "）");
        if (!root.has("choices") || !root.get("choices").isJsonArray()) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体里没有 choices 数组（HTTP " + status + "），不是预期的对话补全格式。"
                            + "请确认 base_url 指向的是 OpenAI 兼容服务。", null);
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices 是空数组（HTTP " + status + "），没有可显示的内容。请稍后重试。",
                    null);
        }
        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices 里第 1 项不是对象（HTTP " + status + "），不是预期的对话补全格式。",
                    null);
        }
        JsonObject message = asObject(first.getAsJsonObject().get("message"));
        if (message == null) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0] 里没有 message 对象（HTTP " + status + "），"
                            + "不是预期的对话补全格式。", null);
        }
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0].message.content 是 null（HTTP " + status + "），"
                            + "没有可显示的内容。请稍后重试。", null);
        }
        if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0].message.content 不是字符串（HTTP " + status + "），"
                            + "不是预期的对话补全格式。", null);
        }
        String text = content.getAsString();
        if (text.isBlank()) {
            throw failure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0].message.content 是空白内容（HTTP " + status + "），"
                            + "没有可显示的内容。请稍后重试。", null);
        }
        return text;
    }

    /** 非 2xx 一律先分类成失败：任何状态码下都不把错误页正文当成回复。 */
    private void classifyStatus(int status) throws AiFailure {
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 401 || status == 403) {
            throw failure(AiFailureKind.UNAUTHORIZED,
                    "密钥被拒绝（HTTP " + status + "），请检查 API Key 是否填写正确。", null);
        }
        if (status == 429) {
            throw failure(AiFailureKind.RATE_LIMITED,
                    "请求过于频繁（HTTP 429），请稍后重试。", null);
        }
        if (status >= 500) {
            throw failure(AiFailureKind.SERVER_ERROR,
                    "服务端返回 " + status + "，请稍后重试；若持续如此，可换一个 base_url 试试。", null);
        }
        throw failure(AiFailureKind.RATE_LIMITED,
                "服务端没有接受这次请求（HTTP " + status + "）。请检查 model 名与请求内容，然后重试。",
                null);
    }

    /** 解析成对象；失败时抛 {@link AiFailure}（内部走 {@link #failure} 做密钥脱敏）。 */
    private JsonObject parseObject(String body, String prefix) throws AiFailure {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed == null || !parsed.isJsonObject()) {
                throw failure(AiFailureKind.MALFORMED_RESPONSE, prefix + "：顶层不是 JSON 对象。", null);
            }
            return parsed.getAsJsonObject();
        } catch (AiFailure alreadyClassified) {
            throw alreadyClassified;
        } catch (RuntimeException e) {
            // JsonSyntaxException / IllegalStateException / NumberFormatException……
            // 全部在这里收口：用户需要的是「不是预期格式」，不是一行 Gson 的栈。
            throw failure(AiFailureKind.MALFORMED_RESPONSE, prefix + "：" + describe(e) + "。", e);
        }
    }

    /** 取一个对象字段；不是对象（或不存在）时返回 null。 */
    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** 异常的一句话说明（供消息里拼接），只要类型与消息，不带栈。 */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    // ------------------------------------------------------------------
    // SSE 读取：字节级分帧 + 字节级累积解码
    // ------------------------------------------------------------------

    /**
     * 事件间静默超时的守夜人（修复轮 1 / 评审 Major-1 的流式那一半）。
     *
     * <p><b>为什么不能用「总时长」当超时</b>：一次长回答持续几分钟是正常的，按总时长掐断会把
     * 正常的长回复当成失败。要钉住的是「超过配置超时<b>没有任何新字节到达</b>」——网络断开、
     * 服务端卡死、中间设备静默丢包都是这个形状，而它们在没有读超时的 {@code HttpClient} 上
     * 表现为 {@code read()} 永远阻塞（评审实测 180 秒以上不返回）。
     *
     * <p><b>做法</b>：独立守护线程等待「最近一次收到字节的时间 + 超时」；到点还没有新字节，
     * 就<b>关掉响应体</b>。关流会立刻解除调用线程上 {@code read()} 的阻塞（实测：另一个线程
     * {@code close()} 时阻塞中的读当场以 IOException 收场），调用方据此把这次失败归类成 TIMEOUT。
     * 任何返回值/异常路径最后都会 {@link #stop()} 它，不留悬挂线程。
     */
    private static final class SilenceWatchdog {

        private final Object lock = new Object();
        private final long limitNanos;
        private final InputStream body;
        private final Thread thread;
        private final AtomicBoolean fired = new AtomicBoolean();
        private boolean finished;
        private long deadlineNanos;

        SilenceWatchdog(Duration timeout, InputStream body) {
            this.limitNanos = limitNanos(timeout);
            this.body = body;
            this.deadlineNanos = System.nanoTime() + limitNanos;
            this.thread = new Thread(this::awaitSilence, "ai-stream-silence-watchdog");
            this.thread.setDaemon(true);
        }

        /** 超时的纳秒数；天文数字的配置值按「几乎不超时」处理，避免加法溢出。 */
        private static long limitNanos(Duration timeout) {
            long nanos;
            try {
                nanos = timeout.toNanos();
            } catch (ArithmeticException overflow) {
                nanos = Long.MAX_VALUE;
            }
            return Math.max(1L, Math.min(nanos, Long.MAX_VALUE / 4));
        }

        void start() {
            thread.start();
        }

        /** 有新字节到达：把期限推到「现在 + 超时」。 */
        void touch() {
            synchronized (lock) {
                deadlineNanos = System.nanoTime() + limitNanos;
                lock.notifyAll();
            }
        }

        /** 守夜人是否已经因为静默超时而关掉了响应体（调用方据此把 IOException 认成超时）。 */
        boolean fired() {
            return fired.get();
        }

        /** 收工：叫醒守夜线程并等它退出。 */
        void stop() {
            synchronized (lock) {
                finished = true;
                lock.notifyAll();
            }
            try {
                thread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void awaitSilence() {
            synchronized (lock) {
                while (!finished) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        // 先立旗再关流：调用方靠这面旗子把随之而来的 IOException 认成超时
                        fired.set(true);
                        closeQuietly(body);
                        return;
                    }
                    try {
                        lock.wait(Math.max(1L, remainingNanos / 1_000_000L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * {@code text/event-stream} 的解析状态机。
     *
     * <p>两个缓冲区各管一件事，串成一条流水线：
     * <ol>
     *   <li>{@code pending}：<b>还没解出字符的字节</b>。多字节字符被网络分块切断时，尾巴留在这里，
     *       等下一个块补齐再解码——这是「不能逐个分片 new String」的落点；</li>
     *   <li>{@code text}：<b>解出来但还没成行的字符</b>。行必须一直攒到看见零字符空行，
     *       因为一个事件可能被拆到多个 TCP 块里。</li>
     * </ol>
     * 只有 {@code data:} 行参与事件；空行是<b>唯一</b>的分帧信号（与文件块的规则一致：仅含空白的行
     * 不是空行，它是内容，在这里表现为非法 JSON → 跳过该事件）。
     */
    private static final class SseReader {

        /** 单块读大小。取 1KB 是为了让「一个字节一个字节到达」也能被及时处理。 */
        private static final int READ_BUFFER_BYTES = 1024;

        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder event = new StringBuilder();
        private final StringBuilder full = new StringBuilder();
        private final CharBuffer charBuffer = CharBuffer.allocate(READ_BUFFER_BYTES * 2);
        private final byte[] readBuffer = new byte[READ_BUFFER_BYTES];

        private boolean done;

        /** 是否已经收到过可用的正文或已经结束：用来区分「断在半路」与「根本什么都没回」。 */
        private boolean sawAnything;

        /**
         * 收尾时被<b>显式丢弃</b>的不完整 UTF-8 尾巴的字节数（修复轮 2 / 评审 Minor-4）。
         *
         * <p>这个计数是「丢弃」这个决定唯一的用户可见出口：失败消息里会点名有几个字节凑不出一个
         * 完整字符。修前那个分支是往行缓冲里塞一个 {@code U+FFFD}——评审实测它永远到不了
         * {@code onDelta} / {@code onComplete}（随后被 JSON 解析失败丢掉），也就是一段死代码，
         * 而报告却把它描述成「显式给出替换字符，不静默吞掉」。现在的语义是：
         * <b>绝不凭空造一个替换字符当正文，但丢弃这件事必须说出来</b>。
         */
        private int droppedTrailingBytes;

        /** 一边读一边解码分帧；<b>不做收尾</b>（收尾由 {@link #finish} 在确认流干净结束时做）。 */
        void consume(InputStream body, AiStreamListener listener, Runnable onBytesArrived)
                throws IOException, AiFailure {
            int read;
            while (!done && (read = body.read(readBuffer)) != -1) {
                // 有新字节到达 → 把「事件间静默」的期限推后（修复轮 1：长回答是正常的，
                // 要钉的是「一直没有新字节」）。
                onBytesArrived.run();
                pending.write(readBuffer, 0, read);
                decodePending(false);
                dispatchCompleteLines(listener);
            }
        }

        /**
         * 流<b>干净结束</b>时的收尾：把缓冲区里最后一个完整字符放出来、处理末尾没跟空行的事件。
         *
         * <p><b>为什么必须把没换行的尾巴也当成一个事件（修复轮 2 / 评审 Blocker-1）</b>：
         * 分帧只由零字符空行产生，所以「已经收全、只是没跟空行」的最后一个事件会留在 {@code text}
         * （行缓冲）里。修前这里只把 {@code event} 交给 {@link #handleEvent}，而 {@code event} 是空的
         * ——于是那个事件被静默丢掉：{@code data:{…甲…}\n\n} + {@code data:{…乙…}}（乙缺尾空行）
         * 只投递 {@code 甲}；只有唯一一个事件且缺尾空行时一个都投递不出去，还会被误报成
         * 「服务端没有按流式（SSE）格式返回任何内容」（它与 {@code sawAnything} 为 false 直接相关）。
         * 这与本类自己的注释（「末尾没有空行就结束的最后一个事件」）直接矛盾。
         *
         * <p>投递进来的可能是<b>半个事件</b>（连接在事件中途被截断）。那种情况下 {@code handleEvent}
         * 里「JSON 解析失败就跳过」的那条规则会把坏内容丢掉——所以这里不需要另加形状判断，
         * 但反过来说：{@code text} 里剩的字符必须能凑成一个合法 {@code data:} 载荷才会产生回调。
         *
         * <p>刻意与 {@link #consume} 分开：连接中途断掉时绝不能走到这里。否则「半个汉字」会被
         * 当成「输入结束」而按替换字符吐出来——用户看到的就不是「少了半个字」，而是一个
         * 不该出现的乱码字符（本条由 {@code stream_utf8CharSplitAcrossChunks_doesNotProduceMojibake}
         * 的反向验证钉住）。
         */
        void finish(AiStreamListener listener) throws AiFailure {
            if (done) {
                return;
            }
            decodePending(true);
            dispatchCompleteLines(listener);
            if (!done && !text.isEmpty()) {
                // 末尾没有空行就结束的最后一个事件：行缓冲里剩下的字符就是一个完整事件
                //（或者是被截断的半个事件，交给 handleEvent 的 JSON 解析去丢）
                event.append(text);
                text.setLength(0);
            }
            if (!done && !event.isEmpty()) {
                handleEvent(event.toString(), listener);
                event.setLength(0);
            }
        }

        boolean sawDone() {
            return done;
        }

        boolean sawAnything() {
            return sawAnything || done;
        }

        /** 收尾时被显式丢弃的不完整 UTF-8 尾巴的字节数（供失败消息点名）。 */
        int droppedTrailingBytes() {
            return droppedTrailingBytes;
        }

        // 包内可见的诊断口子：只为测试能看见「缓冲区里到底还剩什么」。
        int pendingSizeForTest() {
            return pending.size();
        }

        String decodedTextForTest() {
            return text.toString();
        }

        /**
         * 把 {@code pending} 里能解出来的字符全部搬进 {@code text}。
         *
         * <p><b>这里是整个流式实现最要紧的一段。</b>网络分块的边界可以落在 UTF-8 多字节字符中间，
         * 所以「解到哪儿为止」必须自己算清楚，并且把没凑齐的字节<b>显式留在 {@code pending} 里</b>：
         * {@link CharsetDecoder} 在 {@code endOfInput=false} 时会把未完成的字节收进自己的内部状态，
         * 一旦我们自己 reset 缓冲，那份状态就再也没有机会与新字节接上——症状是「整块到达就对、
         * 一个字节一个字节到达就一个字都出不来」。
         *
         * <p>所以这里的做法是：先用解码器跑一遍，只取它<b>实际消费掉的字节数</b>
         * （{@code input.position()}）作为「已确认完整」的边界，然后 <b>reset 掉解码器的内部状态</b>，
         * 只对那段确认过的字节做真正的解码。剩下的尾巴回到 {@code pending}，等下一个块。
         * 判断依据绝不能是「这个块读完了就解码」——那会把半个汉字变成替换字符 U+FFFD。
         *
         * <p>收尾（{@code endOfInput=true}）时剩下的字节是<b>真正的半截字符</b>：它们凑不出任何字符，
         * 于是被<b>显式丢弃</b>并记进 {@link #droppedTrailingBytes}（修复轮 2：修前这里塞一个
         * {@code U+FFFD} 进行缓冲，而那个字符永远到不了用户面前，是一段死代码）。
         * 中途<b>绝不能</b>调 {@code decoder.flush()}：它会把攒着的半个字符强行吐出来。
         */
        private void decodePending(boolean endOfInput) throws AiFailure {
            if (pending.size() == 0) {
                return;
            }
            byte[] raw = pending.toByteArray();
            int safeBytes = safeBytePrefix(raw);
            if (safeBytes > 0) {
                // 必须循环到这段字节被解完为止。解码器在字符缓冲区满时返回 OVERFLOW 并只写进
                // 一部分字符——只看一次结果就会<b>静默丢掉</b>后半段正文（症状是「前 32 个字符
                // 凭空消失、后面照常」）。所以这里循环，并且每轮先 clear 掉已取走的字符。
                int offset = 0;
                while (offset < safeBytes) {
                    charBuffer.clear();
                    ByteBuffer input = ByteBuffer.wrap(raw, offset, safeBytes - offset);
                    CoderResult result = decoder.decode(input, charBuffer, true);
                    if (result.isError()) {
                        throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                                "流式回复里出现了无法按 UTF-8 解码的字节，为避免显示乱码而停止。"
                                        + "请重试，或换一个 base_url。");
                    }
                    charBuffer.flip();
                    text.append(charBuffer);
                    int consumed = input.position();
                    if (consumed <= 0) {
                        break; // 保险：不推进就退出，避免这里变成死循环
                    }
                    offset += consumed;
                }
                decoder.reset();
            }
            pending.reset();
            if (safeBytes < raw.length) {
                if (!endOfInput) {
                    // 尾巴是「还没凑齐的字符」：留到下一个块。SSE 的语法字符都是 ASCII，
                    // 所以把它留在缓冲里绝不影响分帧。
                    pending.write(raw, safeBytes, raw.length - safeBytes);
                } else {
                    // 收尾时这几个字节确实凑不出字符：显式丢弃，绝不变成 U+FFFD 混进正文
                    // （那等于把半截字节当成模型输出显示给用户）。丢弃的字节数会被写进失败消息。
                    droppedTrailingBytes += raw.length - safeBytes;
                }
            }
        }

        /**
         * 算出「能独立解码的字节数」：末尾那个不完整的 UTF-8 序列不算在内。
         *
         * <p>两种调用情形共用这一段：读循环中它把没凑齐的尾巴留在 {@code pending} 里等下一个块；
         * 收尾时同一段尾巴由调用方<b>显式丢弃</b>（{@link #droppedTrailingBytes}）。
         * 修前收尾这一侧走的是「按替换字符处理」的分支，而那个替换字符到不了用户面前。
         */
        private int safeBytePrefix(byte[] raw) throws AiFailure {
            int index = 0;
            while (index < raw.length) {
                int lead = raw[index] & 0xFF;
                int length;
                if (lead < 0x80) {
                    length = 1;
                } else if (lead >= 0xC2 && lead <= 0xDF) {
                    length = 2;
                } else if (lead >= 0xE0 && lead <= 0xEF) {
                    length = 3;
                } else if (lead >= 0xF0 && lead <= 0xF4) {
                    length = 4;
                } else {
                    throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                            "流式回复里出现了无法按 UTF-8 解码的字节（位置 " + index
                                    + "，字节 0x" + Integer.toHexString(lead).toUpperCase()
                                    + "），为避免显示乱码而停止。请重试，或换一个 base_url。");
                }
                if (index + length > raw.length) {
                    return index; // 多字节字符被切断了（两种情况都交给调用方决定：留着等下一块 / 收尾丢弃）
                }
                for (int i = 1; i < length; i++) {
                    if ((raw[index + i] & 0xC0) != 0x80) {
                        throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                                "流式回复里出现了无法按 UTF-8 解码的字节（位置 "
                                        + (index + i) + "），为避免显示乱码而停止。"
                                        + "请重试，或换一个 base_url。");
                    }
                }
                index += length;
            }
            return index;
        }

        /** 按行切分，只在零字符空行处分帧。 */
        private void dispatchCompleteLines(AiStreamListener listener) throws AiFailure {
            int start = 0;
            while (start < text.length()) {
                int lf = text.indexOf("\n", start);
                if (lf < 0) {
                    break;
                }
                String line = text.substring(start, lf);
                start = lf + 1;
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.isEmpty()) {
                    // 先取值再清空：反过来会把这一行的事件体清掉，只剩下最后一行。
                    String payload = event.toString();
                    event.setLength(0);
                    handleEvent(payload, listener);
                    if (done) {
                        // 收到 [DONE] 立刻停止分帧：同一块里跟在后面的字节不再当成事件
                        text.setLength(0);
                        return;
                    }
                } else {
                    event.append(line).append('\n');
                }
            }
            text.delete(0, start);
        }

        /** 一个事件的载荷：{@code [DONE]} 结束，{@code data:} 取增量，其余（注释行）忽略。 */
        private void handleEvent(String payload, AiStreamListener listener) throws AiFailure {
            // done 判断在这里是<b>第二道</b>防线（第一道在 consume 的读循环与 dispatchCompleteLines）：
            // 读循环一旦看到 [DONE] 就不再发起下一次 read，所以走到这里时 done 为真只可能来自
            // 「[DONE] 与后续事件落在同一个 TCP 块里」那种情形——而那正是这条判断拦下的东西。
            if (done || payload.isEmpty()) {
                return;
            }
            sawAnything = true;
            String data = null;
            for (String line : payload.split("\n")) {
                String trimmed = line.endsWith("\r")
                        ? line.substring(0, line.length() - 1) : line;
                if (trimmed.startsWith(":")) {
                    continue; // 注释行（心跳）
                }
                if (!trimmed.startsWith("data:")) {
                    continue; // event: / id: / retry: 等字段对本功能无意义
                }
                // `data:` 与 `data: ` 都合法：冒号后那<b>一个</b>空格是分隔符，不是内容
                String value = trimmed.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                data = data == null ? value : data + "\n" + value;
            }
            if (data == null) {
                return;
            }
            if ("[DONE]".equals(data)) {
                done = true;
                listener.onComplete(full.toString());
                return;
            }
            JsonObject root;
            try {
                JsonElement parsed = JsonParser.parseString(data);
                if (parsed == null || !parsed.isJsonObject()) {
                    return;
                }
                root = parsed.getAsJsonObject();
            } catch (RuntimeException notJson) {
                // 网关插进来的非 JSON 行（含「只由空白组成的行」被当成空行的情形）：跳过，不算失败。
                // 这里刻意不像非流式路径那样抛 MALFORMED_RESPONSE：一段无法使用的分片不该把
                // 用户已经看到的整段回复变成一次错误。
                return;
            }
            JsonElement choicesElement = root.get("choices");
            if (choicesElement == null || !choicesElement.isJsonArray()) {
                return;
            }
            JsonArray choices = choicesElement.getAsJsonArray();
            if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
                return;
            }
            JsonObject delta = asObject(choices.get(0).getAsJsonObject().get("delta"));
            if (delta == null) {
                return;
            }
            JsonElement content = delta.get("content");
            if (content == null || content.isJsonNull() || !content.isJsonPrimitive()
                    || !content.getAsJsonPrimitive().isString()) {
                return;
            }
            String piece = content.getAsString();
            if (piece.isEmpty()) {
                return;
            }
            sawAnything = true;
            full.append(piece);
            listener.onDelta(piece);
        }
    }
}
