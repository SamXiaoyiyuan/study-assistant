package com.xiaoyiyuan.study.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容的 AI 客户端（非流式；spec §7.1）。
 *
 * <p>职责只有三件：把用户填的 {@code base_url} 规范化成最终请求地址、按 OpenAI 协议发一次
 * POST、把各种「没拿到正文」的情况分门别类地变成 {@link AiFailure}。
 *
 * <p><b>架构约束（spec §7.5 / §3.3）：本包不得依赖 {@code storage}，也不持有任何
 * {@link java.nio.file.Path} 写入口。</b>AI 只能返回纯文本；「写进文件」是界面拿到用户确认后
 * 另行触发的事。这不是靠自觉遵守的约定——{@code AiClientTest} 用反射扫描本包所有类的
 * 方法签名、构造器参数与字段类型，一旦有人加进一条 {@code Path} 就必须先改那条测试。
 *
 * <p><b>逐次确认门不在本类里</b>：确认发生在调用方（界面展示完整请求内容并得到用户同意）之后。
 * 本类拿到 {@link AiRequest} 就会发出去——{@link AiRequest} 存的是值快照，
 * 正是为了让「用户确认过的内容」与「真正发出去的内容」是同一份。
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

    /** 测试注入用的构造器：行为与上面完全一致，只是复用调用方给的 client。 */
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

        URI endpoint = endpoint(config.baseUrl());
        String payload = new Gson().toJson(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", JSON_CONTENT_TYPE)
                .header("Accept", "application/json")
                .timeout(config.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            // 连都没连上就超时：对用户与「回得太慢」是同一件事——超过了设定的超时时间。
            // 单独 catch 一次是为了让这条映射有据可查（它是 HttpTimeoutException 的子类）。
            throw timeoutFailure(e);
        } catch (HttpTimeoutException e) {
            throw timeoutFailure(e);
        } catch (IOException e) {
            throw new AiFailure(AiFailureKind.NETWORK,
                    "连接不上服务（" + describe(e) + "）。请检查 base_url（" + config.baseUrl()
                            + "）与网络是否可用，然后重试。",
                    e);
        }

        return readContent(response.statusCode(), response.body());
    }

    /** 超时消息统一在这里生成，保证两种超时给用户的是同一句话。 */
    private AiFailure timeoutFailure(Exception cause) {
        return new AiFailure(AiFailureKind.TIMEOUT,
                "请求超过超时时间（" + config.timeout() + "）。可以在设置里把 request_timeout 调大，"
                        + "然后重试。",
                cause);
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
     * @param baseUrl 用户填的地址，如 {@code https://api.deepseek.com} 或 {@code https://x.com/v1}
     * @return 最终请求地址
     * @throws IllegalArgumentException 空白、缺 scheme/主机、或带查询串（消息点明原因）
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
    private static String readContent(int status, String body) throws AiFailure {
        classifyStatus(status);

        if (body == null || body.isBlank()) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "服务端返回了空响应体（HTTP " + status + "），没有可显示的内容。请稍后重试。");
        }
        JsonObject root = parseObject(body, "响应体不是合法的 JSON（HTTP " + status + "）");
        if (!root.has("choices") || !root.get("choices").isJsonArray()) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体里没有 choices 数组（HTTP " + status + "），不是预期的对话补全格式。"
                            + "请确认 base_url 指向的是 OpenAI 兼容服务。");
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty()) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices 是空数组（HTTP " + status + "），没有可显示的内容。请稍后重试。");
        }
        JsonElement first = choices.get(0);
        if (!first.isJsonObject()) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices 里第 1 项不是对象（HTTP " + status + "），不是预期的对话补全格式。");
        }
        JsonObject message = asObject(first.getAsJsonObject().get("message"));
        if (message == null) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0] 里没有 message 对象（HTTP " + status + "），"
                            + "不是预期的对话补全格式。");
        }
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0].message.content 是 null（HTTP " + status + "），"
                            + "没有可显示的内容。请稍后重试。");
        }
        if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0].message.content 不是字符串（HTTP " + status + "），"
                            + "不是预期的对话补全格式。");
        }
        String text = content.getAsString();
        if (text.isBlank()) {
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    "响应体的 choices[0].message.content 是空白内容（HTTP " + status + "），"
                            + "没有可显示的内容。请稍后重试。");
        }
        return text;
    }

    /** 非 2xx 一律先分类成失败：任何状态码下都不把错误页正文当成回复。 */
    private static void classifyStatus(int status) throws AiFailure {
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new AiFailure(AiFailureKind.UNAUTHORIZED,
                    "密钥被拒绝（HTTP " + status + "），请检查 API Key 是否填写正确。");
        }
        if (status == 429) {
            throw new AiFailure(AiFailureKind.RATE_LIMITED,
                    "请求过于频繁（HTTP 429），请稍后重试。");
        }
        if (status >= 500) {
            throw new AiFailure(AiFailureKind.SERVER_ERROR,
                    "服务端返回 " + status + "，请稍后重试；若持续如此，可换一个 base_url 试试。");
        }
        throw new AiFailure(AiFailureKind.RATE_LIMITED,
                "服务端没有接受这次请求（HTTP " + status + "）。请检查 model 名与请求内容，然后重试。");
    }

    /** 解析成对象；任何解析问题都收敛成 {@link AiFailureKind#MALFORMED_RESPONSE}，不外泄 JSON 异常。 */
    private static JsonObject parseObject(String body, String prefix) throws AiFailure {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                        prefix + "：顶层不是 JSON 对象。");
            }
            return parsed.getAsJsonObject();
        } catch (AiFailure alreadyClassified) {
            throw alreadyClassified;
        } catch (RuntimeException e) {
            // JsonSyntaxException / IllegalStateException / NumberFormatException……
            // 全部在这里收口：用户需要的是「不是预期格式」，不是一行 Gson 的栈。
            throw new AiFailure(AiFailureKind.MALFORMED_RESPONSE,
                    prefix + "：" + describe(e) + "。", e);
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
}
