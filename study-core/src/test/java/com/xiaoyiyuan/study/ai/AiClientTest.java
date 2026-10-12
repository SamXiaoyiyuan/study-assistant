package com.xiaoyiyuan.study.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

        AiClient client = new AiClient(config(stub.baseUrl()), HttpClient.newHttpClient());
        assertEquals("你好", client.complete(request("hi")));

        assertFalse(Files.exists(target), "complete 不得创建任何目录/文件");
        try (Stream<Path> children = Files.list(tempDir)) {
            assertEquals(List.of(), children.toList(), "complete 前后 tempDir 必须没有新条目");
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 断言某状态码/响应体被归类成 {@code kind}，且消息里带上给定的每一段具体内容。 */
    private void assertFailure(AiFailureKind kind, int status, String body, String... mustContain)
            throws Exception {
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
        private final List<com.sun.net.httpserver.Headers> headers = new ArrayList<>();

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
            return headers.get(headers.size() - 1).getFirst(name);
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
                try (InputStream in = exchange.getRequestBody()) {
                    bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                paths.add(exchange.getRequestURI().getPath());
                methods.add(exchange.getRequestMethod());
                headers.add(exchange.getRequestHeaders());
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
