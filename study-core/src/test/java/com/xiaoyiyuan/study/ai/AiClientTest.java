package com.xiaoyiyuan.study.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.xiaoyiyuan.study.config.AppConfig;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AiClient} 的行为测试。
 *
 * <p><b>绝不访问真实网络</b>：每个用例都打在 JDK 自带的
 * {@link com.sun.net.httpserver.HttpServer} 本地桩上（绑定 {@code 127.0.0.1:0} 取随机端口）。
 * 「连不上」的用例也是本机行为——绑一个端口再关掉，让 127.0.0.1 上确实没人监听。
 *
 * <p>本测试关注四件事，其余（逐次确认门、流式、历史存储）在别的任务里：
 * <ol>
 *   <li><b>URL 规范化</b>（{@link AiClient#endpoint(String)}，纯函数）：用户填的 base_url
 *       形状五花八门，最终必须落到同一个 URL，且绝不出现 {@code /v1/v1}；</li>
 *   <li><b>请求形态</b>：Bearer 头、JSON 内容类型、请求体里的 model 与 {@code stream:false}、目标路径；</li>
 *   <li><b>失败分类</b>：连接不上 / 超时 / 4xx（401、429、其余 4xx）/ 5xx / 响应体不是预期 JSON 形状，
 *       每一类都要能定位到具体 {@link AiFailureKind} 与消息里的具体内容；</li>
 *   <li><b>架构护栏</b>：{@code ai} 包在签名与字段层面拿不到任何 {@link Path}，调用前后不产生任何新文件。</li>
 * </ol>
 */
class AiClientTest {

    /** 桩上验证用的 API Key：断言里写死，避免「拿配置里的值去断言配置里的值」这种自证。 */
    private static final String API_KEY = "sk-test-key-123";

    /** 模型名同理写死。 */
    private static final String MODEL = "deepseek-chat";

    /** 正常的超时；足够本机桩往返。 */
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(10);

    /** 超时用例专用：短到桩的 3 秒 sleep 一定跑不完。 */
    private static final Duration TIMEOUT_FOR_SLOW_STUB = Duration.ofMillis(250);

    /** 修复轮 1：挂起类用例（谎报 Content-Length / 截断响应体）用的超时。 */
    private static final Duration TIMEOUT_FOR_HANGING_STUB = Duration.ofSeconds(1);

    /**
     * 修复轮 1：「必须结束」类用例的等待上界。
     *
     * <p>取 15 秒是刻意的余量：配置超时只有 1 秒，正常实现应当在 1 秒出头返回；
     * 而评审实测「挂死」的那次在 180 秒后仍然活着。15 秒既远离 1 秒（不会 flaky），
     * 也远离 180 秒（真挂住时一定判得出来）。
     */
    private static final long HANG_DEADLINE_MILLIS = 15_000;

    /** 一个形状完全正确的 chat.completion 响应体（逐字，含嵌套 message）。 */
    private static final String GOOD_BODY = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"deepseek-chat",
             "choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private Stub stub;

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop();
            stub = null;
        }
    }

    // ------------------------------------------------------------------
    // endpoint：URL 规范化（纯函数）
    // ------------------------------------------------------------------

    @Test
    void endpoint_normalizesAllDocumentedShapes() {
        assertEquals(URI.create("https://api.deepseek.com/v1/chat/completions"),
                AiClient.endpoint("https://api.deepseek.com"));
        assertEquals(URI.create("https://api.deepseek.com/v1/chat/completions"),
                AiClient.endpoint("  https://api.deepseek.com/  "));
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com/v1"));
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com/v1/chat/completions"));
        assertEquals(URI.create("http://127.0.0.1:8080/v1/chat/completions"),
                AiClient.endpoint("http://127.0.0.1:8080"));
        // 反例：绝不能出现 /v1/v1
        assertFalse(AiClient.endpoint("https://x.com/v1").toString().contains("/v1/v1"));
        assertFalse(AiClient.endpoint("https://x.com/v1/chat/completions").toString().contains("/v1/v1"));
    }

    @Test
    void endpoint_removesEveryTrailingSlash_andSurroundingWhitespace() {
        // 去掉的是「全部」尾部斜杠，不是只去一个：末尾留一个 `/` 会拼出 `//v1/...`
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com/"));
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com///"));
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com/v1/"));
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com/v1//"));
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com/v1/chat/completions/"));
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("https://x.com/v1/chat/completions///"));
        // 制表符/换行也算首尾空白
        assertEquals(URI.create("https://x.com/v1/chat/completions"),
                AiClient.endpoint("\thttps://x.com/v1/\n"));
    }

    @Test
    void endpoint_keepsPathPrefix_soSelfHostedSubpathsStillWork() {
        // 自建网关常挂在子路径下（如 https://gw.example.com/openai）
        assertEquals(URI.create("https://gw.example.com/openai/v1/chat/completions"),
                AiClient.endpoint("https://gw.example.com/openai"));
        assertEquals(URI.create("https://gw.example.com/openai/v1/chat/completions"),
                AiClient.endpoint("https://gw.example.com/openai/"));
        // 已经带整条路径时原样返回（不多不少）
        assertEquals(URI.create("https://gw.example.com/openai/v1/chat/completions"),
                AiClient.endpoint("https://gw.example.com/openai/v1/chat/completions"));
        // 少了版本段的完整调用地址也不再追加版本段（否则会拼成 /chat/completions/v1/...）
        assertEquals(URI.create("https://gw.example.com/chat/completions"),
                AiClient.endpoint("https://gw.example.com/chat/completions"));
    }

    @Test
    void endpoint_withExtraInnerSlashes_keepsThemRaw_andNeverBuildsV1Twice() {
        // 「含多余斜杠」的口径：只清理<b>尾部</b>斜杠；path 里剩下的斜杠原样保留（不猜用户想改成什么）。
        // 这里刻意比较最终 URI 的字符串：一旦用 new URI(scheme, authority, path, ...) 构造，
        // 空路径段会被规范化掉（//openai → /openai），用户填的地址就被悄悄改了。
        URI withInnerSlash = AiClient.endpoint("https://x.com//openai//");
        assertEquals("https://x.com//openai/v1/chat/completions", withInnerSlash.toString());
        assertFalse(withInnerSlash.toString().contains("/v1/v1"));

        // 纯尾部斜杠（`https://x.com//`）剥完之后就是「没有 path」，与不带斜杠等价
        assertEquals(URI.create("https://x.com/v1/chat/completions"), AiClient.endpoint("https://x.com//"));
        assertEquals(AiClient.endpoint("https://x.com"), AiClient.endpoint("https://x.com//"));

        // 幂等：把结果再当输入喂一次，结果不变（纯函数 + 已带整条路径的分支）
        assertEquals(withInnerSlash, AiClient.endpoint(withInnerSlash.toString()));
        URI result = AiClient.endpoint("https://api.deepseek.com");
        assertEquals(result, AiClient.endpoint(result.toString()));
        assertEquals(result, AiClient.endpoint(" https://api.deepseek.com/ "), "同一输入形状必须落到同一 URI");
    }

    @Test
    void endpoint_withQueryString_isRejectedInsteadOfSilentlyMangled() {
        // 带查询串时无法安全拼接路径：追加的位置会落在 `?` 之后（`...?x=1/v1/chat/completions`），
        // 请求会打到错的地方。宁可当场给出可读的拒绝，也不静默拼错。
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AiClient.endpoint("https://x.com?key=1"));
        assertTrue(e.getMessage().contains("?") || e.getMessage().contains("查询"),
                "拒绝消息应点明查询串这个原因，实际：" + e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AiClient.endpoint("https://x.com/v1?a=1"));
    }

    @Test
    void endpoint_withBlankOrMalformedInput_isRejectedWithSpecificMessage() {
        assertThrows(IllegalArgumentException.class, () -> AiClient.endpoint(""));
        assertThrows(IllegalArgumentException.class, () -> AiClient.endpoint("   "));
        assertThrows(IllegalArgumentException.class, () -> AiClient.endpoint(null));
        // 不带 scheme、或 scheme 后面没主机：都不是能发的地址
        assertThrows(IllegalArgumentException.class, () -> AiClient.endpoint("api.deepseek.com"));
        assertThrows(IllegalArgumentException.class, () -> AiClient.endpoint("https://"));
        // 中间夹空格：URI.create 会抛，我们把它收敛成同一个可读异常
        IllegalArgumentException spaced = assertThrows(IllegalArgumentException.class,
                () -> AiClient.endpoint("https://x.com/a b"));
        assertTrue(spaced.getMessage() != null && !spaced.getMessage().isBlank());
    }

    // ------------------------------------------------------------------
    // complete：正常路径
    // ------------------------------------------------------------------

    @Test
    void complete_returnsAssistantContent_onWellFormedResponse() throws Exception {
        stub = Stub.serving(200, "application/json", GOOD_BODY);
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());

        assertEquals("你好", client.complete(request("hi")));

        // 目标路径就是 endpoint 规范化后的路径（不是 base_url 原样）
        assertEquals("/v1/chat/completions", stub.lastPath());
        assertEquals("POST", stub.lastMethod());
    }

    @Test
    void complete_sendsBearerTokenJsonContentTypeAndModel() throws Exception {
        stub = Stub.serving(200, "application/json", GOOD_BODY);
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());

        client.complete(new AiRequest(MODEL,
                List.of(new AiMessage("system", "你是助手"), new AiMessage("user", "hi")), false));

        assertEquals("Bearer " + API_KEY, stub.lastHeader("Authorization"),
                "必须逐字发 Authorization: Bearer <apiKey>");
        assertTrue(stub.lastHeader("Content-Type").startsWith("application/json"),
                "必须声明 JSON 内容类型，实际：" + stub.lastHeader("Content-Type"));

        JsonObject body = JsonParser.parseString(stub.lastBody()).getAsJsonObject();
        assertEquals(MODEL, body.get("model").getAsString());
        assertTrue(body.has("messages"), "请求体里必须有 messages");
        assertEquals(2, body.getAsJsonArray("messages").size());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("你是助手", body.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("content").getAsString());
        assertEquals("user", body.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("role").getAsString());
        // 本任务是非流式：请求体里的 stream 必须是 false
        assertTrue(body.has("stream"), "请求体里必须有 stream 字段");
        assertFalse(body.get("stream").getAsBoolean(), "非流式请求的 stream 必须为 false");
    }

    // ------------------------------------------------------------------
    // complete：失败分类（每一类都钉住枚举值 + 消息里的具体内容）
    // ------------------------------------------------------------------

    @Test
    void complete_classifies_401_asUNAUTHORIZED() throws Exception {
        assertFailure(AiFailureKind.UNAUTHORIZED, 401, "{\"error\":{\"message\":\"bad key\"}}",
                "401", "API Key");
    }

    @Test
    void complete_classifies_403_asUNAUTHORIZED_and400_asServerRejection() throws Exception {
        // 401/403 同属「这个密钥不行」；其余 4xx（400/404/422）是「服务端没接受这次请求」，各归一类
        assertFailure(AiFailureKind.UNAUTHORIZED, 403, "{\"error\":{\"message\":\"forbidden\"}}",
                "403", "API Key");
        assertFailure(AiFailureKind.RATE_LIMITED, 400, "{\"error\":{\"message\":\"bad request\"}}",
                "400");
    }

    @Test
    void complete_classifies_429_asRATE_LIMITED() throws Exception {
        assertFailure(AiFailureKind.RATE_LIMITED, 429, "{\"error\":{\"message\":\"slow down\"}}",
                "429");
    }

    @Test
    void complete_classifies_5xx_asSERVER_ERROR() throws Exception {
        assertFailure(AiFailureKind.SERVER_ERROR, 500, "{\"error\":{\"message\":\"oops\"}}",
                "500");
        assertFailure(AiFailureKind.SERVER_ERROR, 503, "upstream busy", "503");
    }

    @Test
    void complete_htmlErrorPage_isMALFORMED_RESPONSE_notCrash() throws Exception {
        // 网关在 200 里回 HTML 错误页（真实世界常见）：不得抛裸 JSON 异常，也不得当成空回复
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "<html>Bad Gateway</html>",
                "响应体不是");
    }

    @Test
    void complete_truncatedJson_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200,
                "{\"choices\":[{\"message\":{\"content\":\"你", "响应体不是");
    }

    @Test
    void complete_emptyChoices_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "{\"choices\":[]}", "choices");
    }

    @Test
    void complete_nullContent_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200,
                "{\"choices\":[{\"message\":{\"content\":null}}]}", "content");
    }

    @Test
    void complete_blankContent_isMALFORMED_RESPONSE() throws Exception {
        // 不把「空白回复」静默地当成有效结果交给界面
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200,
                "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}", "content");
    }

    @Test
    void complete_choicesNotArray_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "{\"choices\":{}}", "choices");
    }

    @Test
    void complete_choicesOnNonObjectElement_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "{\"choices\":[\"nope\"]}", "choices");
    }

    @Test
    void complete_missingMessage_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "{\"choices\":[{\"index\":0}]}",
                "message");
    }

    @Test
    void complete_emptyBody_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "", "空响应体");
        // 空白响应体（只有换行/空格）同样不能当成「内容为空白的一次成功回复」
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "  \n ", "空响应体");
    }

    @Test
    void complete_rejectsStreamingRequest_beforeSendingAnything() throws Exception {
        // 本任务只交付非流式（spec §7.1 的 SSE 是 Task 5）。若这条护栏被删掉，一个 stream=true
        // 的请求会被照发出去，而客户端拿 BodyHandlers.ofString 等整包 JSON——服务端按 SSE 回、
        // 客户端等一个永远不来的结束标记，两边都像在正常等待。故这里钉住：请求根本没发出去。
        stub = Stub.serving(200, "application/json", GOOD_BODY);
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());

        AiRequest streaming = new AiRequest(MODEL, List.of(new AiMessage("user", "hi")), true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> client.complete(streaming));
        assertTrue(e.getMessage().contains("流式"),
                "拒绝消息要点明「流式」，实际：" + e.getMessage());

        // 桩上一个请求都没收到：这是在发包之前拦下的，不是发出去之后再报错
        assertThrows(IndexOutOfBoundsException.class, stub::lastPath,
                "complete 不得把 stream=true 的请求发出去");
    }

    @Test
    void complete_connectionRefused_isNETWORK() {
        int closedPort = freeThenClosedPort();
        AiClient client = new AiClient(
                config("http://127.0.0.1:" + closedPort), HttpClient.newHttpClient());

        AiFailure failure = assertThrows(AiFailure.class, () -> client.complete(request("hi")));
        assertEquals(AiFailureKind.NETWORK, failure.kind());
        assertTrue(failure.getMessage().contains("连接不上"),
                "NETWORK 消息要点明「连接不上」，实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("base_url"),
                "NETWORK 消息要给出可执行的动作（检查 base_url），实际：" + failure.getMessage());
    }

    @Test
    void complete_timeout_isTIMEOUT() throws Exception {
        stub = Stub.slow(3_000);
        AiClient client = new AiClient(
                new AiConfig(stub.baseUrl(), API_KEY, MODEL, TIMEOUT_FOR_SLOW_STUB),
                HttpClient.newHttpClient());

        AiFailure failure = assertThrows(AiFailure.class, () -> client.complete(request("hi")));
        assertEquals(AiFailureKind.TIMEOUT, failure.kind());
        assertTrue(failure.getMessage().contains("超时"),
                "TIMEOUT 消息要点明超时，实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("PT0.25S"),
                "TIMEOUT 消息要带上具体超时值（PT0.25S = 250ms），实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("request_timeout"),
                "TIMEOUT 消息要告诉用户去哪里改，实际：" + failure.getMessage());
        assertNotEquals(AiFailureKind.NETWORK, failure.kind(), "超时不得被归成 NETWORK");
    }

    // ------------------------------------------------------------------
    // 配置与请求的值语义
    // ------------------------------------------------------------------

    @Test
    void aiConfig_rejectsValuesThatCouldOnlyProduceABrokenRequest() {
        assertEquals(Duration.ofSeconds(10), new AiConfig("https://x.com", API_KEY, MODEL,
                Duration.ofSeconds(10)).timeout(), "构造器不得偷偷改超时值");

        assertThrows(IllegalArgumentException.class,
                () -> new AiConfig("  ", API_KEY, MODEL, SHORT_TIMEOUT), "空 base_url 应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfig(null, API_KEY, MODEL, SHORT_TIMEOUT), "null base_url 应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfig("https://x.com", "  ", MODEL, SHORT_TIMEOUT), "空 apiKey 应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfig("https://x.com", API_KEY, "", SHORT_TIMEOUT), "空 model 应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfig("https://x.com", API_KEY, MODEL, Duration.ZERO), "零超时应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfig("https://x.com", API_KEY, MODEL, Duration.ofSeconds(-1)),
                "负超时应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new AiConfig("https://x.com", API_KEY, MODEL, null), "null 超时应被拒绝");
    }

    @Test
    void aiConfig_trimsWhitespaceOnBaseUrlButKeepsKeyLiteral() {
        // base_url 是为拼接而存的：首尾空白去掉（否则会拼出 %20）；api_key 是凭据，逐字节照发
        AiConfig config = new AiConfig("  https://x.com  ", " " + API_KEY + " ", MODEL,
                SHORT_TIMEOUT);
        assertEquals("https://x.com", config.baseUrl());
        assertEquals(" " + API_KEY + " ", config.apiKey(), "api_key 不得被改写");
    }

    @Test
    void aiRequest_isNonStreamingByDefault_andSnapshotsMessages() {
        List<AiMessage> mutable = new ArrayList<>();
        mutable.add(new AiMessage("user", "hi"));
        AiRequest request = new AiRequest(MODEL, mutable, false);
        assertFalse(request.stream(), "本任务的请求必须是非流式");
        mutable.add(new AiMessage("user", "偷偷追加"));
        assertEquals(1, request.messages().size(), "messages 必须是值快照");
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(new AiMessage("user", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> new AiRequest("  ", List.of(new AiMessage("user", "hi")), false),
                "空 model 的请求应被拒绝");
        assertThrows(IllegalArgumentException.class, () -> new AiRequest(MODEL, List.of(), false),
                "没有消息的请求应被拒绝");
    }

    @Test
    void aiFailure_rejectsMessagesWithInterfaceBannedWords() {
        // 消息里出现禁用词是编码错误，由类型自己拦住（spec §8.2）——这是文案层唯一可靠的关口
        for (String banned : List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new AiFailure(AiFailureKind.NETWORK, "这次请求" + banned + "了，请重试。"));
            assertTrue(e.getMessage().contains(banned),
                    "拒绝消息应点明是哪个禁用词，实际：" + e.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new AiFailure(AiFailureKind.NETWORK, "   "), "空白消息应被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new AiFailure(null, "连接不上。"), "null 分类应被拒绝");
        assertEquals(AiFailureKind.NETWORK,
                new AiFailure(AiFailureKind.NETWORK, "连接不上。").kind());
    }

    /**
     * 收尾修复波（Minor-5）：{@code AiClient} 与 {@code AiFailure} 共用<b>同一份</b>禁用词清单。
     *
     * <p><b>为什么这条必须有</b>：修前 {@code failure()} 只做 {@code redactSecret}，
     * {@code AiFailure} 的 {@code BANNED_WORDS} 是 private 的——两份口径各自演化，
     * 而漂移的代价正好是本条款要避免的那件事。这里同时钉住「是一份」与「这一份真的在过滤」。
     */
    @Test
    void bannedWordListIsTheSingleSource_ofTruth_forBothAiFailureAndAiClient() {
        // 1) 词表就是 spec §8.2 那 8 个，一个不多一个不少
        assertEquals(List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持"),
                List.of(AiFailure.BANNED_WORDS),
                "禁用词清单必须与 spec §8.2 逐字一致（顺序也钉住，方便比对）");

        // 2) 出口用的过滤器就是这份清单：每个词都必须被换掉
        for (String banned : AiFailure.BANNED_WORDS) {
            String filtered = AiFailure.redactBannedWords("前半" + banned + "后半");
            assertFalse(filtered.contains(banned),
                    "redactBannedWords 漏掉了「" + banned + "」；实际：" + filtered);
            assertEquals("前半" + AiFailure.REDACTION_PLACEHOLDER + "后半", filtered,
                    "「" + banned + "」必须被换成占位符，而不是整段丢掉");
        }

        // 3) 干净消息与非字符串入参都不许被改坏
        assertEquals("连接不上服务，请检查 base_url。",
                AiFailure.redactBannedWords("连接不上服务，请检查 base_url。"), "没命中就必须原样返回");
        assertEquals(null, AiFailure.redactBannedWords(null), "null 进 null 出");
        assertEquals(null, AiFailure.firstBannedWord(null), "null 里没有禁用词");
        assertEquals("失败", AiFailure.firstBannedWord("这条消息里有失败两个字"));
        assertEquals(null, AiFailure.firstBannedWord("这条消息很干净"));
    }

    /**
     * 收尾修复波（Minor-5）：{@code AiClient} 会把「不归本包控制」的文字拼进用户可见消息——
     * 底层异常的文案（{@code describe(e)}）与用户填的 {@code base_url}。这段文字里出现界面禁用词时，
     * 出口必须把它换掉，而不是让 {@link AiFailure} 的构造器抛 {@link IllegalArgumentException}。
     *
     * <p><b>为什么载体是 base_url 的路径而不是解析异常</b>：实测 Gson 2.14.0 的解析异常
     * <b>不会</b>把出错处的原文写进 message——截断的 {@code ["甲失败乙"} 得到的是
     * {@code java.io.EOFException: End of input at line 1 column 8 path $[1]}，一个禁用词都没有
     * （2026-10-11 实测，见终审修复报告）。而 {@code networkFailure} 的模板里拼着
     * {@code config.baseUrl()}，这个字串完全由用户决定；一段带「失败」的路径是线上真能出现的形状
     * （用户把网关路径填成中文），也是本包<b>唯一</b>能自证禁用词过滤的入口。
     *
     * <p><b>红在哪（修前）</b>：{@code failure()} 只做 {@code redactSecret}，于是
     * {@code "http://127.0.0.1:<port>/失败"} 原样进了 {@code new AiFailure(...)} → 构造器抛 IAE。
     * 调用方拿到的是一次运行时异常而不是一次 {@link AiFailure}：既没有 {@link AiFailureKind}，
     * 界面也无法归类成「连不上」并给「重试」按钮（spec §7.6）。
     *
     * <p>流式入口那半边（同一出口）在
     * {@code AiStreamTest#failure_stripsInterfaceBannedWordsOutOfWrappedExceptionText_insteadOfBlowingUp}。
     */
    @Test
    void failure_returnsAiFailure_evenWhenParsedFragmentTextCarriesABannedWord() throws Exception {
        stopCurrentStub();
        // 绑一个端口再关掉：127.0.0.1 上确实没人监听 → 稳定的 NETWORK 失败，
        // 而失败消息的模板里会拼进这段带「失败」的 base_url。
        String bannedWord = "失败";
        String baseUrl;
        try (ServerSocket closedPort = new ServerSocket(0)) {
            baseUrl = "http://127.0.0.1:" + closedPort.getLocalPort() + "/" + bannedWord;
        }
        assertTrue(AiClient.endpoint(baseUrl).toString().contains(bannedWord),
                "前置条件：这段 base_url 能通过规范化、且禁用词保留在最终地址里；实际："
                        + AiClient.endpoint(baseUrl));
        AiClient client = new AiClient(
                new AiConfig(baseUrl, API_KEY, MODEL, SHORT_TIMEOUT), HttpClient.newHttpClient());

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client.complete(request("hi")),
                "含禁用词的失败消息必须以 AiFailure 表达；修前这里抛 IAE（禁用词来自 base_url）");
        assertEquals(AiFailureKind.NETWORK, failure.kind(),
                "分类必须照常给出；实际：" + failure.kind() + " / " + failure.getMessage());
        for (String banned : AiFailure.BANNED_WORDS) {
            assertFalse(failure.getMessage().contains(banned),
                    "出口必须过滤掉禁用词「" + banned + "」，实际：" + failure.getMessage());
        }
        assertTrue(failure.getMessage().contains("措辞已中性化"),
                "被过滤的位置要留下可见痕迹；实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("连接不上服务"),
                "既有措辞不受影响：过滤只动禁用词那一段；实际：" + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 架构护栏：ai 包拿不到文件写入权
    // ------------------------------------------------------------------

    @Test
    void aiPackage_hasNoPathInAnySignatureOfField() throws Exception {
        Class<?>[] classes = aiPackageClasses();
        // 先钉住「确实扫到了这个包的类」——loader 失效时不能变成一条空转的绿灯
        assertTrue(classes.length >= 5,
                "ai 包应当至少扫到 5 个类（AiClient/AiConfig/AiRequest/AiMessage/AiFailure/AiFailureKind），"
                        + "实际 " + classes.length + " 个：" + Arrays.toString(classes));

        Set<String> offenders = new HashSet<>();
        int scannedMembers = 0;
        for (Class<?> type : classes) {
            // 1) 自己的方法（返回类型、参数、异常）里不得出现 Path
            for (Method method : type.getDeclaredMethods()) {
                scannedMembers++;
                collect(offenders, type, "方法 " + method.getName(), method.getGenericReturnType());
                for (Type parameter : method.getGenericParameterTypes()) {
                    collect(offenders, type, "方法 " + method.getName(), parameter);
                }
                for (Type thrown : method.getGenericExceptionTypes()) {
                    collect(offenders, type, "方法 " + method.getName(), thrown);
                }
            }
            // 2) 构造器参数里不得出现 Path
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                scannedMembers++;
                for (Type parameter : constructor.getGenericParameterTypes()) {
                    collect(offenders, type, "构造器", parameter);
                }
            }
            // 3) 字段类型里不得出现 Path（record 组件也算，它的类型就是字段类型）
            for (Field field : type.getDeclaredFields()) {
                scannedMembers++;
                collect(offenders, type, "字段 " + field.getName(), field.getGenericType());
            }
            RecordComponent[] components = type.getRecordComponents();
            for (RecordComponent component : components == null ? new RecordComponent[0] : components) {
                scannedMembers++;
                collect(offenders, type, "record 组件 " + component.getName(),
                        component.getGenericType());
            }
        }

        assertTrue(offenders.isEmpty(),
                "ai 包内不得出现 java.nio.file.Path（spec §7.5：AI 无文件写入权）；命中：" + offenders);
        // 扫描面本身也要非空：上面若因开关写错而一个成员都没扫到，这条也必须红
        assertTrue(scannedMembers > 0, "反射扫描没有覆盖到任何成员");
    }

    @Test
    void complete_doesNotWriteAnyFile(@TempDir Path tempDir) throws Exception {
        stub = Stub.serving(200, "application/json", GOOD_BODY);
        // 目标目录刻意不存在：客户端不该替任何人建目录，也不该往里写任何东西
        Path target = tempDir.resolve("不该出现的目录");
        assertFalse(Files.exists(target));

        // 修复轮 1（Major-4）：只查 @TempDir 抓不到最省事的那种写法——直接把字符串变成 Path 写到
        // 进程工作目录（评审 M8d：`Files.write(Path.of("cwd-write-probe.tmp"))` 当时全绿，文件却真的
        // 写出来了）。所以同时快照工作目录，调用前后必须完全一致。
        // 修复轮 2（评审 §4.4）：快照必须是<b>递归</b>的——只查一层条目名时，写到 <cwd>/target/v.tmp
        // 这种子目录里会双双漏过（评审实测：字节码护栏与快照全绿、文件真的被写了出来）。
        // （没有一并快照 java.io.tmpdir：那是多个进程共用的目录，前后对比会引入 flaky；
        //   方法体内的写入由 aiPackageBytecode_hasNoFileWriteOrProcessExecutionReferences 结构性覆盖。）
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Set<String> entriesBefore = entryNames(workingDirectory);

        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        assertEquals("你好", client.complete(request("hi")));

        assertFalse(Files.exists(target), "complete 不得创建任何目录/文件");
        try (Stream<Path> children = Files.list(tempDir)) {
            assertEquals(List.of(), children.toList(), "complete 前后 tempDir 必须没有新条目");
        }
        Set<String> entriesAfter = entryNames(workingDirectory);
        assertTrue(entriesAfter.equals(entriesBefore),
                "complete 前后工作目录（" + workingDirectory + "）的<b>递归</b>快照必须一致；差异："
                        + snapshotDifference(entriesBefore, entriesAfter));
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 Major-1 / 性质 A）：complete() 必须在「超时 + 余量」内结束
    // ------------------------------------------------------------------

    @Test
    void complete_lyingContentLengthAndOpenConnection_endsWithinTimeout() throws Exception {
        // 评审 B1 的最小复现：桩声明 10MB、只发 43 字节、然后<b>保持连接</b>。
        // 请求级 HttpRequest.timeout 只管到响应头为止，body 读取不受它约束——评审实测 180.2 秒
        // 仍然 alive。这条断言不是在「检查抛了异常」，而是在检查<b>耗时上界</b>。
        stub = Stub.lyingContentLength(10_000_000L, GOOD_BODY, 60_000);
        AiClient client = new AiClient(
                new AiConfig(stub.baseUrl(), API_KEY, MODEL, TIMEOUT_FOR_HANGING_STUB),
                HttpClient.newHttpClient());

        long startedAt = System.nanoTime();
        CallOutcome outcome = runWithDeadline(() -> client.complete(request("hi")),
                HANG_DEADLINE_MILLIS);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(outcome.finished(),
                "服务端谎报 Content-Length 并保持连接时，complete() 必须在配置超时（"
                        + TIMEOUT_FOR_HANGING_STUB + "）+ 余量内结束；实际 " + elapsedMillis
                        + "ms 后仍未返回（Major-1 的挂死复现）");
        assertInstanceOf(AiFailure.class, outcome.thrown(),
                "结束方式必须是超时类失败，实际：" + outcome.thrown());
        AiFailure failure = (AiFailure) outcome.thrown();
        assertEquals(AiFailureKind.TIMEOUT, failure.kind(),
                "「响应头已到、body 不给足」必须归为 TIMEOUT，实际：" + failure.kind());
        assertTrue(failure.getMessage().contains("超时"), "实际：" + failure.getMessage());
        assertTrue(elapsedMillis < HANG_DEADLINE_MILLIS,
                "耗时上界必须真的被钉住：实际 " + elapsedMillis + "ms");
    }

    @Test
    void complete_contentLengthLargerThanSent_thenClose_endsWithinTimeout() throws Exception {
        // 评审 B2：声明长度大于实发字节、然后<b>正常关闭</b>（真实世界「代理截断响应」的形态）。
        // 同样要求有界结束，且不允许把半截 body 当成一次成功回复。
        stub = Stub.overlongContentLength(GOOD_BODY.getBytes(StandardCharsets.UTF_8).length * 10L,
                GOOD_BODY);
        AiClient client = new AiClient(
                new AiConfig(stub.baseUrl(), API_KEY, MODEL, TIMEOUT_FOR_HANGING_STUB),
                HttpClient.newHttpClient());

        long startedAt = System.nanoTime();
        CallOutcome outcome = runWithDeadline(() -> client.complete(request("hi")),
                HANG_DEADLINE_MILLIS);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(outcome.finished(),
                "响应体被截断（声明长度大于实发）时 complete() 必须有界结束；实际 " + elapsedMillis
                        + "ms 后仍未返回");
        assertInstanceOf(AiFailure.class, outcome.thrown(),
                "半截响应体绝不能当成成功回复，实际：" + outcome.thrown());
        AiFailureKind kind = ((AiFailure) outcome.thrown()).kind();
        assertTrue(kind == AiFailureKind.TIMEOUT || kind == AiFailureKind.NETWORK,
                "截断的响应体要么按超时、要么按网络中断收场，实际：" + kind);
        assertTrue(elapsedMillis < HANG_DEADLINE_MILLIS,
                "耗时上界必须真的被钉住：实际 " + elapsedMillis + "ms");
    }

    @Test
    void complete_interruptedWhileWaiting_throwsInterruptedException() throws Exception {
        // 评审 Minor-10：complete 声明会原样抛 InterruptedException，但此前没有一条用例覆盖它。
        // 取消路径必须让调用线程及时返回——否则界面只能杀进程。
        stub = Stub.slow(5_000);
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<String> diag = new AtomicReference<>("(not finished)");
        Thread worker = new Thread(() -> {
            try {
                client.complete(request("hi"));
                diag.set("returned normally, interrupted=" + Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                thrown.set(t);
                diag.set(t.getClass().getName() + ": " + t.getMessage());
            }
        }, "ai-complete-interrupt-test");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(200); // 让请求确实进到「等服务端」里
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "中断后 complete() 必须及时返回，不得把调用线程挂住");
        assertInstanceOf(InterruptedException.class, thrown.get(),
                "取消必须原样表达为 InterruptedException，实际：" + diag.get());
    }

    @Test
    void complete_interruptedWhileWaiting_closesTheConnectionToTheServer() throws Exception {
        // 修复轮 2（评审 M10 / Minor-2）：complete() 的中断路径此前<b>没有任何</b>「连接是否真的被关」
        // 的断言——去掉 pending.cancel(true) 之后整个 AiClientTest 50/50 全绿，而那条连接与它的读取
        // 线程会一直留在后台。桩这里不看客户端抛了什么，只看「往这条连接里再写还会不会成功」：
        // 写失败 = 对端真的把连接关掉了；一直写成功 = 连接还开着。
        stub = Stub.hangingBody(10_000_000L, GOOD_BODY, 20_000);
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                client.complete(request("hi"));
                thrown.set(new AssertionError("中断路径不得正常返回"));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "ai-complete-cancel-test");
        worker.setDaemon(true);
        worker.start();

        awaitTrue("桩必须先把响应头与前半段 body 发出去（客户端这时卡在读完 body 上）",
                stub::responseCommitted, 5_000);
        Thread.sleep(200); // 让客户端确实进到「等剩余 body」里
        worker.interrupt();
        worker.join(3_000);

        assertFalse(worker.isAlive(), "中断后 complete() 必须及时返回");
        assertInstanceOf(InterruptedException.class, thrown.get(), "实际：" + thrown.get());
        awaitTrue("中断必须真的关掉这条 TCP 连接（否则连接与读取线程会留在后台累积）；桩观测到："
                + stub.disconnectFailure(), stub::disconnectObserved, 5_000);
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 Major-2）：api_key 只许出现在 Authorization 头里
    // ------------------------------------------------------------------

    @Test
    void complete_sendsOnlyTheAllowedHeaders_andKeyOnlyInAuthorization() throws Exception {
        stub = Stub.serving(200, "application/json", GOOD_BODY);
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        assertEquals("你好", client.complete(request("hi")));

        Map<String, List<String>> received = stub.lastHeaders();

        // 1) 桩端断言收到的<b>完整</b>头部集合：除传输层自己加的头之外，客户端只许加这三个。
        //    多一个 X-Debug-Api-Key（评审 M10）之类的头会当场让这条变红。
        Set<String> frameworkHeaders = Set.of("host", "content-length", "user-agent", "connection",
                "upgrade", "http2-settings");
        Set<String> clientHeaders = new TreeSet<>();
        for (String name : received.keySet()) {
            if (!frameworkHeaders.contains(name.toLowerCase(Locale.ROOT))) {
                clientHeaders.add(name.toLowerCase(Locale.ROOT));
            }
        }
        assertEquals(new TreeSet<>(Set.of("authorization", "content-type", "accept")), clientHeaders,
                "客户端只许发 Authorization / Content-Type / Accept 三个头；实际收到：" + received);

        // 2) 任何头（名字与值都算）里出现 api_key 的，只能是 Authorization
        for (Map.Entry<String, List<String>> entry : received.entrySet()) {
            for (String value : entry.getValue()) {
                if (value.contains(API_KEY)) {
                    assertEquals("authorization", entry.getKey().toLowerCase(Locale.ROOT),
                            "api_key 只允许出现在 Authorization 头里，实际还在：" + entry.getKey());
                }
            }
        }
        assertFalse(received.keySet().stream()
                        .anyMatch(name -> name.toLowerCase(Locale.ROOT).contains("key")
                                && !name.equalsIgnoreCase("Authorization")),
                "不得存在任何名字里带 key 的头（Authorization 除外）：" + received.keySet());

        // 3) 反面保险：密钥必须真的在 Authorization 里，否则上面两条会「因为哪儿都没有」而空转
        assertTrue(received.get("Authorization") != null
                        && received.get("Authorization").contains("Bearer " + API_KEY),
                "Authorization 必须原样承载密钥，实际：" + received.get("Authorization"));
    }

    @Test
    void complete_neverPutsApiKeyInFailureMessageOrCauseChain() throws Exception {
        // 成功路径：返回值里也不该出现密钥
        stub = Stub.serving(200, "application/json", GOOD_BODY);
        AiClient ok = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        assertFalse(ok.complete(request("hi")).contains(API_KEY), "回复正文里不该出现密钥");
        stopCurrentStub();

        // 各类失败路径：NETWORK / TIMEOUT / UNAUTHORIZED / RATE_LIMITED / SERVER_ERROR(4xx其它) / MALFORMED
        int closedPort = freeThenClosedPort();
        AiClient refused = new AiClient(config("http://127.0.0.1:" + closedPort),
                HttpClient.newHttpClient());
        assertNoSecretInFailure("连接不上", () -> refused.complete(request("hi")));

        stub = Stub.slow(3_000);
        AiClient slow = new AiClient(
                new AiConfig(stub.baseUrl(), API_KEY, MODEL, TIMEOUT_FOR_SLOW_STUB),
                HttpClient.newHttpClient());
        assertNoSecretInFailure("超时", () -> slow.complete(request("hi")));
        stopCurrentStub();

        for (int status : new int[] {401, 403, 429, 500, 400}) {
            stub = Stub.serving(status, "application/json",
                    "{\"error\":{\"message\":\"key=" + API_KEY + "\"}}");
            AiClient failing = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
            assertNoSecretInFailure("HTTP " + status, () -> failing.complete(request("hi")));
            stopCurrentStub();
        }

        // 响应体里带着密钥、且走的是「解析失败」这条路（消息里会拼进 Gson 的文案）：
        // 用户可见消息是消息分母最不可控的一处，必须也守得住。
        stub = Stub.serving(200, "application/json", "<html>bad gateway " + API_KEY + "</html>");
        AiClient malformed = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        assertNoSecretInFailure("响应体不是 JSON", () -> malformed.complete(request("hi")));
        stopCurrentStub();

        stub = Stub.serving(200, "application/json",
                "{\"choices\":[]}{\"echo\":\"" + API_KEY + "\"");
        AiClient truncated = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        assertNoSecretInFailure("截断 JSON", () -> truncated.complete(request("hi")));
        stopCurrentStub();
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 Major-3）：AiConfig 不得明文打印 api_key
    // ------------------------------------------------------------------

    @Test
    void aiConfig_toStringNeverPrintsTheApiKey() {
        AiConfig clientConfig = config("https://x.com");

        assertFalse(clientConfig.toString().contains(API_KEY),
                "record 自动实现的 toString() 会明文打印密钥（评审实测）；实际：" + clientConfig);
        assertFalse(String.valueOf((Object) clientConfig).contains(API_KEY));
        assertFalse(("" + clientConfig).contains(API_KEY));

        // 脱敏不得影响真正的取值，否则就是「靠改坏功能来换绿」
        assertEquals(API_KEY, clientConfig.apiKey(), "toString 的脱敏不得影响取值");
        // 仍然要有排查价值：地址 / 模型 / 超时都要在，密钥位置要有占位符
        assertTrue(clientConfig.toString().contains("https://x.com"),
                "toString 应当保留 base_url，实际：" + clientConfig);
        assertTrue(clientConfig.toString().contains(MODEL),
                "toString 应当保留 model，实际：" + clientConfig);
        assertTrue(clientConfig.toString().contains(SHORT_TIMEOUT.toString()),
                "toString 应当保留 timeout，实际：" + clientConfig);
        assertTrue(clientConfig.toString().contains("***"),
                "密钥位置应给出占位符（让读日志的人知道「这里有密钥」），实际：" + clientConfig);
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 Major-4）：ai 包的<b>字节码</b>里不得有写文件 / 执行进程
    // 修复轮 2（评审 Major-1）：补上「纯反射 + 拼接类名」那一半
    // ------------------------------------------------------------------

    /**
     * 禁止清单之一：<b>包前缀</b>（写文件的入口，以及「纯反射」的入口）。
     *
     * <p>每条同时按三种形态匹配：斜杠类名（{@code java/nio/file/Files}）、描述符/签名里的类型
     * （{@code Ljava/nio/file/Path;}）、以及<b>点号</b>形态（{@code java.nio.file.Files}）。
     * 点号形态是修复轮 2 补的：{@code Class.forName} / {@code Lookup.findClass} 的实参必须是点号，
     * 而评审实测「只匹配斜杠形式」时连最平凡的 {@code Class.forName("java.nio.file.Files")}
     * 都抓不住（上一版报告 §2.4 说它会抓住，那句话不成立）。
     */
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "java/nio/file/",      // Files / Path / Paths / …：写文件的入口
            "java/io/File",        // File / FileOutputStream / FileWriter …（顺带挡住 InputStream 一侧，偏保守）
            "java/nio/channels/",  // FileChannel（修复轮 2 增补：评审 §4.5 点名的绕过面）
            "java/lang/reflect/"); // Method / Constructor / Field / Array：纯反射入口

    /** 与 {@link #FORBIDDEN_PACKAGE_PREFIXES} 一一对应的点号形态。 */
    private static final List<String> FORBIDDEN_DOTTED_PREFIXES = List.of(
            "java.nio.file.", "java.io.File", "java.nio.channels.", "java.lang.reflect.");

    /** 必须<b>精确</b>匹配的类：{@code java/lang/RuntimeException} 不是执行进程的能力。 */
    private static final List<String> FORBIDDEN_EXACT_TYPES = List.of(
            "java/lang/Runtime", "java/lang/ProcessBuilder", "java/lang/Process");

    /**
     * 禁止清单之三：<b>「从一个字符串拿到一个 {@code Class}」的入口方法名</b>
     * （修复轮 2 加入，minor 清算批补齐）。
     *
     * <p>把类名拆开拼（{@code "java.nio." + "file.Fi" + "les"}）能躲过字面量匹配，但躲不过这层：
     * 无论怎么拼，最后都要调用这几个方法之一才能把名字变成 {@code Class}。
     *
     * <p><b>它们不是全部——「字节 → Class」还有第四个入口</b>（minor 清算批 / Task 4 修复轮 2
     * 复审 §3.4）：{@code MethodHandles.Lookup#defineClass(byte[])} 与
     * {@code defineHiddenClass(byte[],boolean,ClassOption...)} <b>不需要类名字符串</b>，
     * 方法名也不在上面那三个里；拿到 {@code Class} 之后靠<b>编译期已知接口的 cast</b> 就能调用，
     * <b>完全不需要 {@code java.lang.reflect}</b>。所以「三条入口是全部」这个说法不成立，
     * 上一版注释里那句「{@code Class} 到手之后想调用任何方法都要经过 {@code java/lang/reflect/}」
     * 同样不成立——评审用一个放进 {@code ai} 包的探针证明过：判定与真实用例断言<b>双双全绿</b>，
     * 而文件真的被写了出来。现在这两个方法名也进了清单。
     *
     * <p>接受风险（如实记录）：清单是「已知入口」的枚举，不是「能力封闭」的证明——
     * JNI / {@code Unsafe} / java agent 仍在范围外（见报告 §7 的已知边界）。
     */
    private static final List<String> FORBIDDEN_CLASS_LOOKUP_METHODS = List.of(
            "forName",         // java.lang.Class.forName(String)
            "loadClass",       // java.lang.ClassLoader.loadClass(String)
            "findClass",       // java.lang.invoke.MethodHandles.Lookup.findClass(String)
            "defineClass",     // java.lang.invoke.MethodHandles.Lookup.defineClass(byte[])：无需类名
            "defineHiddenClass"); // ……defineHiddenClass(byte[], boolean, ClassOption...)：同上

    @Test
    void aiPackageBytecode_hasNoFileWriteOrProcessExecutionReferences() throws Exception {
        // 禁止清单本身也要被断言（评审 M4a 实测：清单里少一项时，只靠剩下的前缀仍然全绿）。
        // 下面四条是<b>等值</b>断言：删掉清单里任一项，这里立刻变红。
        assertEquals(List.of("java/nio/file/", "java/io/File", "java/nio/channels/",
                        "java/lang/reflect/"), FORBIDDEN_PACKAGE_PREFIXES,
                "禁止清单被改动：少一项就等于少守一整类能力");
        assertEquals(List.of("java.nio.file.", "java.io.File", "java.nio.channels.",
                        "java.lang.reflect."), FORBIDDEN_DOTTED_PREFIXES,
                "点号形态清单被改动（Class.forName / findClass 用的就是点号）");
        assertEquals(List.of("java/lang/Runtime", "java/lang/ProcessBuilder", "java/lang/Process"),
                FORBIDDEN_EXACT_TYPES, "精确匹配清单被改动");
        assertEquals(List.of("forName", "loadClass", "findClass", "defineClass",
                        "defineHiddenClass"), FORBIDDEN_CLASS_LOOKUP_METHODS,
                "「字节/名 → Class」入口清单被改动：少一项就等于那一条路可以绕过"
                        + "（defineClass / defineHiddenClass 是评审实测的第四条路，不需要类名字符串，"
                        + "也不需要 java.lang.reflect）");

        Path aiClasses = mainClassesDirectory().resolve("com/xiaoyiyuan/study/ai");
        assertTrue(Files.isDirectory(aiClasses), "找不到 ai 包的 class 目录：" + aiClasses);
        List<Path> classFiles;
        try (Stream<Path> entries = Files.list(aiClasses)) {
            classFiles = entries.filter(entry -> entry.getFileName().toString().endsWith(".class"))
                    .toList();
        }
        Set<String> names = classFiles.stream().map(entry -> entry.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));

        // 扫描面必须<b>逐个等于</b>磁盘上真实存在的 class 集合（含每个内部类）。
        // 修复轮 2（评审 M4b）：这里原先只断言「≥ 8 个」+「名字里有 AiClient$SseReader.class」，
        // 把那条断言改成「扫到 AiClient.class 也算数」之后仍然全绿——也就是说它并没有守住
        // 「内部类被扫到」。现在用一次独立枚举（递归 walk）做集合等值：少一个（例如把 $ 内部类
        // 过滤掉）立刻变红。
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> walked = Files.walk(aiClasses)) {
            walked.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .forEach(onDisk::add);
        }
        assertEquals(onDisk, names,
                "扫描面必须逐个等于磁盘上实际存在的 class（含每个内部类，例如 AiClient$SseReader.class"
                        + " 与 AiClient$SilenceWatchdog.class）；少一个就等于那个类的方法体没被扫");
        assertTrue(names.contains("AiClient$SseReader.class"),
                "必须连内部类的 class 一起扫，否则方法体藏在内部类里就漏了；实际扫到：" + names);
        // minor 清算批（Task 4 修复轮 2 复审 X12）：扫描面<b>逐个具名</b>钉住内部类。
        // 上面的集合等值已经能抓住「收窄扫描面」，但它依赖两处枚举恰好写对：一处
        // Files.list、一处 Files.walk。评审者证明把<b>收窄</b>那侧（假设有人把等值改成「≥10」）
        // 之后全绿——所以这里再加一道独立的、指名道姓的断言：SSE 解析器是「方法体里最可能
        // 藏写文件」的地方（它处理外部输入），它必须出现在被扫集合里。
        for (String innerClass : List.of("AiClient$SseReader.class", "AiClient$SilenceWatchdog.class")) {
            assertTrue(names.contains(innerClass),
                    "内部类 " + innerClass + " 必须逐个出现在扫描集合里（等值断言之外的独立一道防线）；"
                            + "实际扫到：" + names);
        }
        assertEquals(List.of("AiClient$SilenceWatchdog.class", "AiClient$SseReader.class"),
                names.stream().filter(name -> name.contains("$")).sorted().toList(),
                "内部类清单逐个具名断言：ai 包里目前恰好这两个内部类，"
                        + "多一个（新加的内部类）或少一个都必须在这里显式登记；实际：" + names);

        Set<String> offenders = new LinkedHashSet<>();
        int scannedConstants = 0;
        for (Path classFile : classFiles) {
            byte[] bytes = Files.readAllBytes(classFile);
            assertTrue(bytes.length > 8 && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE,
                    "不是 class 文件（魔数不符）：" + classFile);
            List<String> constants = constantPoolUtf8(bytes);
            scannedConstants += constants.size();
            for (String constant : constants) {
                String hit = forbiddenReference(constant);
                if (hit != null) {
                    offenders.add(classFile.getFileName() + " → " + hit);
                }
            }
        }
        assertTrue(scannedConstants > 100,
                "常量池里一条字符串都没扫到（解析器坏了会变成空转的绿灯）：" + scannedConstants);
        assertTrue(offenders.isEmpty(),
                "ai 包的字节码里不得出现「写文件 / 执行进程 / 纯反射」的引用（spec §3.3 / §7.5）；命中："
                        + offenders);

        // 阳性对照（修复轮 2 新增的三类绕过形态都必须被这条判定抓住）
        assertNotEquals(null, forbiddenReference("java/nio/channels/FileChannel"),
                "FileChannel 必须被挡住");
        assertNotEquals(null, forbiddenReference("java/lang/reflect/Method"),
                "反射入口必须被挡住（纯反射写文件的必经之路）");
        assertNotEquals(null, forbiddenReference("java.nio.file.Files"),
                "点号形态必须被挡住（Class.forName 的实参就是这个形状）");
        assertNotEquals(null, forbiddenReference("java.nio.channels.FileChannel"),
                "点号形态的 FileChannel 必须被挡住");
        assertNotEquals(null, forbiddenReference("java.lang.reflect.Method"),
                "点号形态的反射入口必须被挡住");
        assertNotEquals(null, forbiddenReference("forName"), "Class.forName 必须被挡住");
        assertNotEquals(null, forbiddenReference("findClass"),
                "MethodHandles.Lookup.findClass 必须被挡住（拼接类名的另一条路）");
        assertNotEquals(null, forbiddenReference("defineClass"),
                "MethodHandles.Lookup.defineClass 必须被挡住——它是清单外的第四条「字节 → Class」"
                        + "入口，不需要类名字符串、也不需要 java.lang.reflect（minor 清算批补入）");
        assertNotEquals(null, forbiddenReference("defineHiddenClass"),
                "defineHiddenClass 与 defineClass 同一族，必须一起挡住");

        // 阴性对照：合法引用不得被误伤（记录类与 lambda 都要走 java/lang/invoke）
        assertEquals(null, forbiddenReference("java/lang/RuntimeException"));
        assertEquals(null, forbiddenReference("java/io/IOException"));
        assertEquals(null, forbiddenReference("java/util/List"));
        assertEquals(null, forbiddenReference("java/lang/invoke/LambdaMetafactory"));
        assertEquals(null, forbiddenReference("java/lang/invoke/MethodHandles$Lookup"));
        assertEquals(null, forbiddenReference("java/nio/ByteBuffer"));
        assertEquals(null, forbiddenReference("java/nio/CharBuffer"));
        assertEquals(null, forbiddenReference("java/lang/ClassLoader"));
        assertEquals(null, forbiddenReference("java/lang/Class"));

        // 阳性对照：同一个解析器必须能读出 AiClient 真的引用的类（否则「什么都没扫到」也会绿）
        List<String> clientConstants =
                constantPoolUtf8(Files.readAllBytes(aiClasses.resolve("AiClient.class")));
        assertTrue(clientConstants.contains("java/net/http/HttpClient"),
                "常量池解析器必须能读出真实的类引用（阳性对照失败 = 这条护栏是空转的）");
        assertTrue(clientConstants.contains("java/net/http/HttpRequest"),
                "阳性对照第二例（避免只对了一个巧合的字符串）");
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 §5.1）：URL 规范化仍未被覆盖的形状
    // ------------------------------------------------------------------

    @Test
    void endpoint_preservesSchemeAndAuthorityCase_andTreatsUppercaseV1AsASubpath() {
        // 评审 M13：把 scheme/authority 小写化曾是全绿变异。URI 的大小写是语义的一部分，
        // 逐字保留（这条断言是字符级的，任何小写化渲染都会被抓住）。
        assertEquals("HTTPS://X.COM/v1/chat/completions",
                AiClient.endpoint("HTTPS://X.COM").toString());
        assertEquals("https://X.COM/v1/chat/completions",
                AiClient.endpoint("https://X.COM").toString());
        // 版本段判定区分大小写：`/V1` 不被当成 `/v1`，因此会补一个规范化的 `/v1/...`
        assertEquals("HTTPS://X.COM/V1/v1/chat/completions",
                AiClient.endpoint("HTTPS://X.COM/V1").toString());
    }

    @Test
    void endpoint_ipv6AndInternationalForms_matchWhatTheJdkCanActuallySend() {
        assertEquals("http://[::1]:8080/v1/chat/completions",
                AiClient.endpoint("http://[::1]:8080").toString());
        assertEquals("https://[2001:db8::1]/v1/chat/completions",
                AiClient.endpoint("https://[2001:db8::1]").toString());
        // 路径里的非 ASCII 是可发的（主机名里的不行，见下一条用例）
        assertEquals("https://x.com/\u4e2d\u6587/v1/chat/completions",
                AiClient.endpoint("https://x.com/\u4e2d\u6587").toString());
        // 用户信息（user:pass@）保留现状：属规格未规定，这里只钉住「不静默改地址」
        assertEquals("http://user:pass@host/v1/chat/completions",
                AiClient.endpoint("http://user:pass@host").toString());
    }

    @Test
    void endpoint_v1SubstringTraps_areNotTreatedAsVersionSegment() {
        // 评审 M14：`/v1` 尾判定必须是<b>按段</b>比较。退化成 contains("v1") / endsWith("v1")
        // 之类的子串匹配时，下面这些地址会被误判成「已经带版本段」，拼出来的 URL 打到错的地方。
        assertEquals("https://x.com/v1beta/v1/chat/completions",
                AiClient.endpoint("https://x.com/v1beta").toString());
        assertEquals("https://x.com/myv1/v1/chat/completions",
                AiClient.endpoint("https://x.com/myv1").toString());
        assertEquals("https://x.com/v1/extra/v1/chat/completions",
                AiClient.endpoint("https://x.com/v1/extra").toString());
        assertEquals("https://v1.example.com/v1/chat/completions",
                AiClient.endpoint("https://v1.example.com").toString());
        // 反面对照：真正的版本段照旧原样保留（否则上面几条可以靠「永不加版本段」蒙过去）
        assertEquals("https://x.com/v1/chat/completions",
                AiClient.endpoint("https://x.com/v1").toString());
        assertEquals("https://x.com/openai/v1/chat/completions",
                AiClient.endpoint("https://x.com/openai/v1").toString());
    }

    @Test
    void endpoint_rejectsAddressesThatCouldNotBeSentAtAll() {
        // 修复轮 1：`endpoint()` 的既有承诺是「必须能真正发出去」。下面这些地址 URI 能解析，
        // 但 HttpRequest.newBuilder 会拒绝（实测：:-1 / 中文主机 → "unsupported URI"，
        // ftp:// → "invalid URI scheme"）；:99999 与 :65536 则连不上。
        // 在 endpoint() 里用中文说清原因，比让用户在几秒后看到一行 JDK 英文异常好。
        for (String bad : List.of("https://x.com:-1", "https://x.com:99999", "https://x.com:65536",
                "https://x.com:0", "https://\u4e2d\u6587.com", "ftp://x.com",
                "file:///etc/passwd", "//x.com")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> AiClient.endpoint(bad), "这个地址必须当场被拒绝：" + bad);
            assertTrue(e.getMessage() != null && !e.getMessage().isBlank(),
                    "拒绝消息必须可读：" + bad + " → " + e.getMessage());
        }
        // 合法端口照收（否则上面那条可以靠「见到端口就拒绝」蒙过去）
        assertEquals("https://x.com:8443/v1/chat/completions",
                AiClient.endpoint("https://x.com:8443").toString());
        assertEquals("http://127.0.0.1:65535/v1/chat/completions",
                AiClient.endpoint("http://127.0.0.1:65535").toString());
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 §5.2）：失败分类矩阵的其余格子
    // ------------------------------------------------------------------

    @Test
    void complete_classifiesRedirects_asServerRejection_withTheStatusCodeInTheMessage()
            throws Exception {
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            final int current = status;
            stub = Stub.redirecting(current);
            AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
            AiFailure failure = assertThrows(AiFailure.class,
                    () -> client.complete(request("hi")),
                    "HTTP " + current + " 应当抛 AiFailure");
            assertEquals(AiFailureKind.RATE_LIMITED, failure.kind(),
                    "HTTP " + current + " 的分类不符：" + failure.kind());
            assertTrue(failure.getMessage().contains(String.valueOf(current)),
                    "消息必须带上具体状态码，实际：" + failure.getMessage());
            stopCurrentStub();
        }
    }

    @Test
    void complete_defaultClientFollowsASameHostRedirect() throws Exception {
        // 评审 Minor-11：`Redirect.NORMAL` 这条策略此前无人守。生产构造器 AiClient(AiConfig)
        // 就是用它——重定向网关（常见于自建/代理）要么被跟随、要么被当成失败，不能看运气。
        stub = Stub.redirectingOnce("/v1/chat/completions", GOOD_BODY);
        AiClient client = new AiClient(config(stub.baseUrl()));

        assertEquals("你好", client.complete(request("hi")), "302 应当被生产构造器跟随");
        assertEquals(2, stub.requestCount(), "跟随重定向意味着桩应当收到两次请求");
        assertEquals("/v1/chat/completions", stub.lastPath(), "第二次请求应打到 Location 指向的路径");
    }

    @Test
    void complete_classifiesEveryRemaining4xxStatus() throws Exception {
        // 401/403/429 已单独覆盖；其余 4xx 一律是「服务端没有接受这次请求」，
        // 消息里必须能定位到具体状态码（此前 404/408/413/451/499 一个都没有）。
        for (int status : new int[] {404, 405, 408, 409, 413, 422, 451, 499}) {
            assertFailure(AiFailureKind.RATE_LIMITED, status,
                    "{\"error\":{\"message\":\"nope\"}}", String.valueOf(status));
        }
    }

    @Test
    void complete_neverTreatsAnInterim1xxAsSuccess() throws Exception {
        // 1xx 是中间响应：客户端看不到「最终结果」。JDK 的 HttpServer 发不出真正的 1xx
        // （评审实测），所以这里钉住的性质是「1xx 绝不被当成一次成功回复」，且<b>有界结束</b>：
        // 本机实测这一段由请求级超时兜住（TIMEOUT，约 1 秒），服务端若直接收掉连接则是 NETWORK。
        stub = Stub.interimOnly(100);
        AiClient client = new AiClient(
                new AiConfig(stub.baseUrl(), API_KEY, MODEL, TIMEOUT_FOR_HANGING_STUB),
                HttpClient.newHttpClient());

        long startedAt = System.nanoTime();
        CallOutcome outcome = runWithDeadline(() -> client.complete(request("hi")),
                HANG_DEADLINE_MILLIS);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(outcome.finished(), "只有中间响应时 complete() 必须有界结束；实际 "
                + elapsedMillis + "ms 后仍未返回");
        assertInstanceOf(AiFailure.class, outcome.thrown(),
                "只有中间响应、没有最终响应时必须失败，实际：" + outcome.thrown());
        AiFailureKind kind = ((AiFailure) outcome.thrown()).kind();
        assertTrue(kind == AiFailureKind.TIMEOUT || kind == AiFailureKind.NETWORK,
                "1xx 只能收场为超时或网络类失败，实际：" + kind);
        assertTrue(elapsedMillis < 5_000,
                "1 秒超时之下必须在 5 秒内结束，实际 " + elapsedMillis + "ms");
    }

    @Test
    void complete_nonStringContent_isMALFORMED_RESPONSE_forEveryJsonKind() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200,
                "{\"choices\":[{\"message\":{\"content\":123}}]}", "content", "不是字符串");
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200,
                "{\"choices\":[{\"message\":{\"content\":{\"a\":1}}}]}", "content", "不是字符串");
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200,
                "{\"choices\":[{\"message\":{\"content\":[\"x\"]}}]}", "content", "不是字符串");
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200,
                "{\"choices\":[{\"message\":{\"content\":true}}]}", "content", "不是字符串");
    }

    @Test
    void complete_topLevelNonObject_isMALFORMED_RESPONSE() throws Exception {
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "[]", "顶层不是 JSON 对象");
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "\"just a string\"", "顶层不是 JSON 对象");
        assertFailure(AiFailureKind.MALFORMED_RESPONSE, 200, "123", "顶层不是 JSON 对象");
    }

    @Test
    void complete_duplicateJsonKeys_lastOneWins_pinnedBoundary() throws Exception {
        // 规格未规定重复键的取舍。Gson 取「后出现的那个」——固定住现状，避免依赖升级后
        // 悄悄变成另一种取舍而无人察觉。
        stub = Stub.serving(200, "application/json",
                "{\"choices\":[],\"choices\":[{\"message\":{\"content\":\"后者\"}}]}");
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        assertEquals("后者", client.complete(request("hi")), "重复键取后者（Gson 现状）");
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 Minor-9）：与 AppConfig 的超时口径
    // ------------------------------------------------------------------

    @Test
    void aiConfigTimeout_acceptsEveryValueAppConfigCanProduce(@TempDir Path tempDir) {
        // 口径不同是刻意的（见 AiConfig 的 javadoc），但方向只有一个是对的：
        // <b>AppConfig 能产出的任何超时值，AiConfig 都必须接受</b>——否则 Task 7 的接线
        // （timeout 取自 AppConfig.requestTimeout()）会当场抛异常。
        AppConfig appConfig = AppConfig.load(tempDir.resolve("不存在.properties"));
        for (Duration timeout : List.of(Duration.ofSeconds(1), Duration.ofSeconds(60),
                Duration.ofHours(24), appConfig.requestTimeout())) {
            appConfig.setRequestTimeout(timeout); // 先证明 AppConfig 真的接受这个值
            assertEquals(timeout, appConfig.requestTimeout(), "AppConfig 应原样保存：" + timeout);
            assertEquals(timeout,
                    new AiConfig("https://x.com", API_KEY, MODEL, timeout).timeout(),
                    "AppConfig 能产出的超时值，AiConfig 必须原样接受：" + timeout);
        }
    }

    @Test
    void aiConfigTimeout_rejectsADifferentSetThanAppConfig_intentionalAndPinned(
            @TempDir Path tempDir) {
        // 修复轮 1 的选择：不把 AiConfig 收紧到 AppConfig 的文件粒度上，而是把两者的口径差异
        // <b>显式钉住</b>。理由：AppConfig 的下界与粒度来自<b>文件格式</b>（request_timeout 存的是
        // 整秒，500ms 会被截断成 0 → 「设了 0.5 秒却跑 60 秒」），而 AiConfig 是内存里的传输层
        // 值对象：亚秒超时是合法的传输配置（也是本测试能快速触发超时的唯一手段），它只需要「为正」。
        AppConfig appConfig = AppConfig.load(tempDir.resolve("不存在.properties"));
        for (Duration tooSmall : List.of(Duration.ofMillis(500), Duration.ofNanos(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> appConfig.setRequestTimeout(tooSmall),
                    "AppConfig 应拒绝亚秒超时（无法无损写进文件）：" + tooSmall);
            assertEquals(tooSmall,
                    new AiConfig("https://x.com", API_KEY, MODEL, tooSmall).timeout(),
                    "AiConfig 按传输层口径接受亚秒超时：" + tooSmall);
        }
        for (Duration tooLarge : List.of(Duration.ofHours(100),
                Duration.ofHours(999_999_999L))) {
            assertThrows(IllegalArgumentException.class,
                    () -> appConfig.setRequestTimeout(tooLarge),
                    "AppConfig 应拒绝超过 24 小时的超时：" + tooLarge);
            assertEquals(tooLarge,
                    new AiConfig("https://x.com", API_KEY, MODEL, tooLarge).timeout(),
                    "AiConfig 按传输层口径接受更大的超时：" + tooLarge);
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 一次调用的结果：是否在期限内结束、抛了什么、耗时多少。 */
    private record CallOutcome(boolean finished, Throwable thrown, long elapsedMillis) {
    }

    /** 会抛异常的一次调用（供断言在调用方捕获）。 */
    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    /**
     * 在守护线程里执行一次调用，最多等 {@code deadlineMillis}。
     *
     * <p>「必须结束」这类性质不能靠「断言抛了异常」来证明：实现挂死时，测试自己也会一起挂死，
     * 于是 CI 上表现为超时而不是一条清晰的失败。用带期限的等待，把「挂住」变成一条可读的红。
     */
    private static CallOutcome runWithDeadline(ThrowingCall call, long deadlineMillis)
            throws InterruptedException {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        long startedAt = System.nanoTime();
        Thread worker = new Thread(() -> {
            try {
                call.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                finished.set(true);
            }
        }, "ai-client-deadline-probe");
        worker.setDaemon(true);
        worker.start();
        worker.join(deadlineMillis);
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        return new CallOutcome(finished.get(), thrown.get(), elapsed);
    }

    /** 断言一次失败的消息、{@code toString()} 与整个 cause 链里都没有 api_key。 */
    private void assertNoSecretInFailure(String where, ThrowingCall call) {
        AiFailure failure = assertThrows(AiFailure.class, call::run, where + " 应当抛 AiFailure");
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            assertFalse(current.toString().contains(API_KEY),
                    where + "：" + current.getClass().getSimpleName() + ".toString() 泄漏了 api_key");
            assertFalse(String.valueOf(current.getMessage()).contains(API_KEY),
                    where + "：消息泄漏了 api_key：" + current.getMessage());
            current = current.getCause();
        }
    }

    /** 停掉当前桩（循环里每个用例各起一个桩时必须逐个收掉，否则端口与线程会堆积）。 */
    private void stopCurrentStub() {
        if (stub != null) {
            stub.stop();
            stub = null;
        }
    }

    /** 轮询等待一个条件成立（本地桩是异步的）：超时抛 {@link AssertionError}。 */
    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition,
            long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("等待超时（" + timeoutMillis + "ms）：" + what);
    }

    /** 生产 class 目录：由 {@code AiClient} 自己的 CodeSource 决定，不依赖工作目录。 */
    private static Path mainClassesDirectory() {
        URL location = AiClient.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(URI.create(location.toString()));
    }

    /**
     * 解析 class 文件的常量池，取出全部 {@code CONSTANT_Utf8} 字符串。
     *
     * <p>刻意手写而不是引 ASM：{@code pom.xml} 不该为了「一条架构护栏」多一个第三方依赖，
     * 而常量池的解析只有几十行。这也是唯一能看到<b>方法体</b>引用了什么的手段——
     * 反射只看得到签名与字段（评审 M8/M8d 正是从那里漏过去的）。
     */
    private static List<String> constantPoolUtf8(byte[] classBytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            assertEquals(0xCAFEBABE, in.readInt(), "class 文件的魔数不符");
            in.readUnsignedShort(); // minor version
            in.readUnsignedShort(); // major version
            int poolCount = in.readUnsignedShort();
            List<String> constants = new ArrayList<>();
            for (int index = 1; index < poolCount; index++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> constants.add(in.readUTF());
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);   // Class/String/MethodType/Module/Package
                    case 15 -> in.skipBytes(3);                  // MethodHandle
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {                               // Long/Double 占两个槽位
                        in.skipBytes(8);
                        index++;
                    }
                    default -> throw new IOException(
                            "未知的常量池 tag " + tag + "（下标 " + index + "）");
                }
            }
            return constants;
        }
    }

    /**
     * 一条常量池字符串是否指向「写文件 / 执行进程 / 纯反射」；命中就返回命中的那一段，否则 {@code null}。
     *
     * <p>四类判定，覆盖评审实测过的全部绕过形态：
     * <ol>
     *   <li><b>斜杠类名与描述符</b>（修复轮 1）：{@code java/nio/file/Files}、
     *       {@code Ljava/nio/file/Path;}、{@code ()Ljava/nio/file/Path;}；</li>
     *   <li><b>点号形态</b>（修复轮 2）：{@code java.nio.file.Files}——{@code Class.forName} /
     *       {@code Lookup.findClass} 的实参只能是点号，这是最有价值的信号之一；</li>
     *   <li><b>精确类名</b>（修复轮 1）：{@code Runtime} / {@code ProcessBuilder} / {@code Process}。
     *       {@code Runtime} 必须精确匹配，否则 {@code RuntimeException} 会被误伤；</li>
     *   <li><b>「名/字节 → Class」入口方法名</b>（修复轮 2 + minor 清算批）：{@code forName} /
     *       {@code loadClass} / {@code findClass} / {@code defineClass} / {@code defineHiddenClass}。
     *       前三个吃类名字符串（拼接类名也躲不过），后两个吃<b>字节码</b>、不需要类名，
     *       也不需要 {@code java.lang.reflect}——所以它们必须单列在清单里。</li>
     * </ol>
     */
    private static String forbiddenReference(String constant) {
        for (String prefix : FORBIDDEN_PACKAGE_PREFIXES) {
            if (constant.startsWith(prefix)
                    || constant.contains("L" + prefix)
                    || constant.contains("[" + prefix)) {
                return prefix;
            }
        }
        for (String dotted : FORBIDDEN_DOTTED_PREFIXES) {
            if (constant.contains(dotted)) {
                return dotted;
            }
        }
        for (String exact : FORBIDDEN_EXACT_TYPES) {
            if (constant.equals(exact)
                    || constant.contains("L" + exact + ";")
                    || constant.contains(")" + exact)) {
                return exact;
            }
        }
        for (String lookup : FORBIDDEN_CLASS_LOOKUP_METHODS) {
            if (constant.equals(lookup)) {
                return lookup;
            }
        }
        return null;
    }

    /**
     * {@code user.dir} 的<b>递归</b>快照：相对路径集合。
     *
     * <p>修复轮 2（评审 §4.4）：只查一层条目名时，写到 {@code <cwd>/target/v.tmp} 这种子目录里
     * 就漏了——评审实测那时字节码护栏与快照双双全绿、而文件真的被写了出来。
     */
    private static Set<String> entryNames(Path directory) throws IOException {
        try (Stream<Path> entries = Files.walk(directory)) {
            return entries.filter(entry -> !entry.equals(directory))
                    .map(entry -> directory.relativize(entry).toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** 两份快照的差异（只列新增/消失的相对路径，避免失败信息被几千条条目刷屏）。 */
    private static String snapshotDifference(Set<String> before, Set<String> after) {
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);
        if (added.isEmpty() && removed.isEmpty()) {
            return "";
        }
        return "新增：" + added + "；消失：" + removed;
    }

    /** 断言某状态码/响应体被归类成 {@code kind}，且消息里带上给定的每一段具体内容。 */
    private void assertFailure(AiFailureKind kind, int status, String body, String... mustContain)
            throws Exception {
        stopCurrentStub();
        stub = Stub.serving(status, "application/json", body);
        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());

        AiFailure failure = assertThrows(AiFailure.class, () -> client.complete(request("hi")),
                "HTTP " + status + " / body=" + body + " 应当抛 AiFailure");
        assertEquals(kind, failure.kind(), "HTTP " + status + " / body=" + body + " 的失败分类不符");
        // 消息必须中文、中性、可执行——这里只钉「中性」与「禁用词」，具体内容由各用例逐段断言
        for (String banned : List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持")) {
            assertFalse(failure.getMessage().contains(banned),
                    "失败消息不得含界面禁用词「" + banned + "」，实际：" + failure.getMessage());
        }
        for (String fragment : mustContain) {
            assertTrue(failure.getMessage().contains(fragment),
                    "失败消息应包含「" + fragment + "」，实际：" + failure.getMessage());
        }
    }

    private AiConfig config(String baseUrl) {
        return new AiConfig(baseUrl, API_KEY, MODEL, SHORT_TIMEOUT);
    }

    private AiRequest request(String content) {
        return new AiRequest(MODEL, List.of(new AiMessage("user", content)), false);
    }

    private static void collect(Set<String> offenders, Class<?> type, String where, Type candidate) {
        if (candidate instanceof Class<?> clazz) {
            if (Path.class.isAssignableFrom(clazz) || isJdkPathBound(clazz)) {
                offenders.add(type.getSimpleName() + " 的 " + where + "：" + clazz.getName());
            }
        } else if (candidate != null && candidate.getTypeName().contains("java.nio.file")) {
            offenders.add(type.getSimpleName() + " 的 " + where + "：" + candidate.getTypeName());
        }
    }

    /** 兜住「包装过的 Path」：Optional&lt;Path&gt;、List&lt;Path&gt;、Supplier&lt;Path&gt; 也算。 */
    private static boolean isJdkPathBound(Class<?> clazz) {
        return clazz.getTypeName().contains("java.nio.file");
    }

    /**
     * 枚举 {@code com.xiaoyiyuan.study.ai} 包里的类：从 classpath 上该包对应的目录逐一加载。
     *
     * <p>刻意<b>不读源码文件</b>：源码扫描依赖工作目录，换台机器/换个启动方式就静默失效，
     * 而失效的表现恰恰是「测试照样绿」。classpath 目录是编译期就确定的事实。
     *
     * <p>只扫{@code .class} 文件本身，不递归——{@code AiClientTest} 就在同一个目录里，
     * 但那是测试类（后缀 {@code Test}），这里显式排除，免得用测试类自己的签名去判生产包。
     */
    private static Class<?>[] aiPackageClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> found = new ArrayList<>();
        ClassLoader loader = AiClientTest.class.getClassLoader();
        for (URL resource : Collections.list(loader.getResources("com/xiaoyiyuan/study/ai"))) {
            Path directory = Path.of(URI.create(resource.toString()));
            if (!Files.isDirectory(directory)) {
                continue;
            }
            List<Path> entries;
            try (Stream<Path> stream = Files.list(directory)) {
                entries = stream.toList();
            }
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$") || name.endsWith("Test.class")) {
                    continue;
                }
                String className = "com.xiaoyiyuan.study.ai."
                        + name.substring(0, name.length() - ".class".length());
                Class<?> loaded = Class.forName(className, false, loader);
                if (!found.contains(loaded)) {
                    found.add(loaded);
                }
            }
        }
        return found.toArray(new Class<?>[0]);
    }

    /** 绑一个随机端口再立刻关掉：该端口上因此确实没有人监听。 */
    private static int freeThenClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 本地 HTTP 桩（{@code com.sun.net.httpserver.HttpServer}，绑定 127.0.0.1:0）。
     *
     * <p>用 {@code http://127.0.0.1:port} 这个「不带 /v1」的形状，顺带让每个用例都走过
     * URL 规范化的拼接分支——桩只会把 {@code /v1/chat/completions} 当成命中路径。
     */
    private static final class Stub {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<String> paths = new ArrayList<>();
        private final List<String> methods = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<Map<String, List<String>>> headers = new ArrayList<>();
        private final AtomicBoolean responseCommitted = new AtomicBoolean();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final AtomicReference<String> disconnectFailure = new AtomicReference<>();

        private Stub(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static Stub serving(int status, String contentType, String body) throws IOException {
            return start(status, contentType, body, 0);
        }

        /** 收到请求后先睡 {@code delayMillis}，用于触发客户端超时。 */
        static Stub slow(int delayMillis) throws IOException {
            return start(200, "application/json", GOOD_BODY, delayMillis);
        }

        /**
         * 修复轮 1：<b>谎报</b> {@code Content-Length}（声明 {@code declaredLength} 字节、只写
         * {@code body} 的字节），然后<b>保持连接</b> {@code holdMillis} 毫秒不关。
         *
         * <p>这是评审 B1 的最小复现：请求级 {@code HttpRequest.timeout} 只管到响应头，
         * 之后 body 少发且不关连接时，{@code read()} 会一直等下去（实测 180.2s 仍 alive）。
         */
        static Stub lyingContentLength(long declaredLength, String body, int holdMillis)
                throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Stub stub = new Stub(server, executor);
            server.createContext("/", exchange -> {
                stub.record(exchange);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, declaredLength);
                OutputStream out = exchange.getResponseBody();
                out.write(payload);
                out.flush();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // 客户端先放弃时这里写不回去是预期内的
                    }
                }
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        /**
         * 修复轮 1：声明比实发更大的 {@code Content-Length}，写完就<b>正常关闭</b>（评审 B2：
         * 真实世界里「代理截断响应」就是这个形状）。
         */
        static Stub overlongContentLength(long declaredLength, String body) throws IOException {
            return lyingContentLength(declaredLength, body, 0);
        }

        /**
         * 修复轮 2（评审 M10）：发响应头、写一小段 body，然后<b>持续往响应体里写</b>。
         *
         * <p>客户端若真的把这条连接收掉了，下一次写就会以 {@link IOException} 失败——桩以此给出
         * 「连接真的被关」的证据，而不是只看客户端有没有抛异常。写一直成功则说明连接还活着
         * （中断路径没关连接时就是这样，然后那条连接的读取会永远留在后台）。
         */
        static Stub hangingBody(long declaredLength, String partialBody, int holdMillis)
                throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Stub stub = new Stub(server, executor);
            server.createContext("/", exchange -> {
                stub.record(exchange);
                byte[] payload = partialBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, declaredLength);
                OutputStream out = exchange.getResponseBody();
                out.write(payload);
                out.flush();
                stub.responseCommitted.set(true);
                long deadline = System.nanoTime() + holdMillis * 1_000_000L;
                try {
                    while (System.nanoTime() < deadline) {
                        Thread.sleep(20);
                        out.write('x');
                        out.flush();
                    }
                } catch (IOException clientGone) {
                    stub.markDisconnected(clientGone);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // 客户端已断开时关闭流同样会抛；这不是桩的问题
                    }
                }
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        /** 修复轮 1：指定状态码 + {@code Location} 头（3xx 分类用），响应体长度为 0。 */
        static Stub redirecting(int status) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Stub stub = new Stub(server, executor);
            server.createContext("/", exchange -> {
                stub.record(exchange);
                exchange.getResponseHeaders().set("Location", "/elsewhere");
                exchange.sendResponseHeaders(status, -1);
                exchange.getResponseBody().close();
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        /** 修复轮 1：第一次请求回 302 + {@code Location}，之后回 200 + {@code finalBody}。 */
        static Stub redirectingOnce(String location, String finalBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Stub stub = new Stub(server, executor);
            server.createContext("/", exchange -> {
                boolean first = stub.record(exchange);
                if (first) {
                    exchange.getResponseHeaders().set("Location", location);
                    exchange.sendResponseHeaders(302, -1);
                    exchange.getResponseBody().close();
                    return;
                }
                byte[] payload = finalBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                } catch (IOException clientGone) {
                    // 与 Recorder 同一个姿态：客户端先走时不算桩的问题
                }
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        /** 修复轮 1：只回一个中间响应（1xx）就收工，没有最终响应。 */
        static Stub interimOnly(int status) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Stub stub = new Stub(server, executor);
            server.createContext("/", exchange -> {
                stub.record(exchange);
                exchange.sendResponseHeaders(status, -1);
                exchange.getResponseBody().close();
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        private static Stub start(int status, String contentType, String body, int delayMillis)
                throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            Stub stub = new Stub(server, executor);
            server.createContext("/", stub.new Recorder(status, contentType, body, delayMillis));
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        /**
         * 记下一次请求（路径 / 方法 / 请求体 / <b>全部头部</b>）。
         *
         * @return 这是不是第一次请求（用于「只重定向一次」的桩）
         */
        private synchronized boolean record(HttpExchange exchange) throws IOException {
            try (InputStream in = exchange.getRequestBody()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            paths.add(exchange.getRequestURI().getPath());
            methods.add(exchange.getRequestMethod());
            // 拷贝一份：Headers 是 JDK 复用的对象，断言时不该读一个可能还在被写的活对象
            headers.add(new LinkedHashMap<>(new Headers(exchange.getRequestHeaders())));
            return paths.size() == 1;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String lastPath() {
            return paths.get(paths.size() - 1);
        }

        String lastMethod() {
            return methods.get(methods.size() - 1);
        }

        String lastBody() {
            return bodies.get(bodies.size() - 1);
        }

        String lastHeader(String name) {
            Map<String, List<String>> last = lastHeaders();
            for (Map.Entry<String, List<String>> entry : last.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().isEmpty() ? null : entry.getValue().get(0);
                }
            }
            return null;
        }

        /** 最后一次请求收到的<b>完整</b>头部集合（修复轮 1：断言「只有允许的那些」）。 */
        Map<String, List<String>> lastHeaders() {
            return headers.isEmpty() ? Map.of() : headers.get(headers.size() - 1);
        }

        int requestCount() {
            return paths.size();
        }

        /** 桩是否已经把响应头与前半段 body 发出去了（客户端此时卡在读完 body 上）。 */
        boolean responseCommitted() {
            return responseCommitted.get();
        }

        /** 桩是否观测到「客户端关掉了这条连接」（继续写响应体时失败）。 */
        boolean disconnectObserved() {
            return disconnected.get();
        }

        String disconnectFailure() {
            return disconnectFailure.get();
        }

        private void markDisconnected(Throwable cause) {
            if (disconnected.compareAndSet(false, true)) {
                disconnectFailure.set(cause.getClass().getSimpleName()
                        + (cause.getMessage() == null ? "" : "：" + cause.getMessage()));
            }
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private final class Recorder implements HttpHandler {

            private final int status;
            private final String contentType;
            private final String body;
            private final int delayMillis;

            Recorder(int status, String contentType, String body, int delayMillis) {
                this.status = status;
                this.contentType = contentType;
                this.body = body;
                this.delayMillis = delayMillis;
            }

            @Override
            @SuppressWarnings("PMD.EmptyCatchBlock")
            public void handle(HttpExchange exchange) throws IOException {
                record(exchange);
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                try {
                    exchange.sendResponseHeaders(status, payload.length);
                    try (java.io.OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                } catch (IOException clientGone) {
                    // 超时用例里客户端会先掐断连接，这里写不回去是预期内的，不是桩的问题
                }
            }
        }
    }
}
