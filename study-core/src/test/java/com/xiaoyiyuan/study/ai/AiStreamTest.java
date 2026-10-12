package com.xiaoyiyuan.study.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AiClient#stream} 的行为测试（spec §7.1 的流式输出、§7.6 的流中断处理）。
 *
 * <p><b>绝不访问真实网络</b>：每个用例都打在 JDK 自带的
 * {@link com.sun.net.httpserver.HttpServer} 本地桩上（绑定 {@code 127.0.0.1:0}）。
 *
 * <p>本测试的桩与 {@code AiClientTest} 的桩有<b>本质区别</b>：这里的桩用
 * {@code sendResponseHeaders(200, 0)}（分块传输）拿到响应体流，然后<b>按脚本指定的字节序列、
 * 指定的节奏</b>一段段 {@code write + flush}。SSE 的真问题全在这些边界上：
 * <ul>
 *   <li>一个事件被拆到多个 TCP 块里（最常见的情况）；</li>
 *   <li>一个 UTF-8 多字节字符被拆到两个块里（「半个汉字」）；</li>
 *   <li>分块边界落在 {@code \r\n} 两个字节之间；</li>
 *   <li>{@code [DONE]} 之后服务端还继续发东西。</li>
 * </ul>
 * 用整包响应体（{@code sendResponseHeaders(200, len)}）是构造不出这些场景的：那时切块由 JDK
 * 决定，只会得到一个「恰好整块到达」的理想输入。
 *
 * <p>四条护栏各由「错误实现下会红」的断言钉住：
 * <ol>
 *   <li><b>UTF-8 边界缓冲</b>：逐块 {@code new String(bytes, UTF_8)} 会得到 U+FFFD；</li>
 *   <li><b>按零字符空行分帧</b>：按到达块切分会把一个事件拆成两半、解析出坏 JSON；</li>
 *   <li><b>{@code data:} 前缀 / {@code [DONE]} / {@code :} 注释</b>：三种形态各有用例；</li>
 *   <li><b>取消要真的关连接</b>：桩在客户端取消后必须观测到「连接断了」（对套接字的读出错）。</li>
 * </ol>
 */
class AiStreamTest {

    /** 桩上验证用的 API Key（写死，避免拿配置值断言配置值）。 */
    private static final String API_KEY = "sk-stream-key-456";

    /** 模型名同理写死。 */
    private static final String MODEL = "deepseek-chat";

    /** 正常的超时；足够本机桩往返。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 轮询等待的默认上限。本地桩正常在毫秒级完成，超时即视为「该到的没到」。 */
    private static final long DEFAULT_AWAIT_MILLIS = 5_000;

    /**
     * 修复轮 1：「必须结束」类用例的等待上界。
     *
     * <p>15 秒是刻意的余量：配置超时只有 1 秒，正常实现应当在 1 秒出头返回；而评审实测
     * 「SSE 开始之后 read() 一直阻塞」时是<b>永不返回</b>。15 秒既远离 1 秒（不会 flaky），
     * 也远离「永不」（真挂住时一定判得出来）。
     */
    private static final long HANG_DEADLINE_MILLIS = 15_000;

    private StreamStub stub;

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop();
            stub = null;
        }
    }

    // ------------------------------------------------------------------
    // 1. 正常路径：增量、顺序、拼接
    // ------------------------------------------------------------------

    @Test
    void stream_concatenatesDeltasInOrder() throws Exception {
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("你")), 0),
                chunk(sse(delta("好")), 0),
                chunk(sse("[DONE]"), 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);

        awaitDeltaCount(listener, 2);
        assertEquals("你", listener.delta(0), "第 1 个增量必须是「你」");
        assertEquals("好", listener.delta(1), "第 2 个增量必须是「好」");
        assertEquals("你好", listener.full(), "onComplete 必须拿到拼接后的全文");
        assertEquals(1, listener.completeCount(), "onComplete 只在正常结束时调用一次");
    }

    @Test
    void stream_sendsStreamTrueAndEventStreamAccept_onChatCompletionsPath() throws Exception {
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("好")), 0),
                chunk(sse("[DONE]"), 0)));

        client().stream(streamRequest("hi"), new RecordingListener());

        assertEquals("/v1/chat/completions", stub.lastPath(),
                "流式请求也必须走 endpoint 规范化后的路径");
        assertEquals("POST", stub.lastMethod());
        assertEquals("Bearer " + API_KEY, stub.lastHeader("Authorization"));

        JsonObject body = JsonParser.parseString(stub.lastBody()).getAsJsonObject();
        assertTrue(body.has("stream"), "请求体里必须有 stream 字段");
        assertTrue(body.get("stream").getAsBoolean(),
                "流式入口发出去的 stream 必须是 true，否则服务端会回整包 JSON");
        assertEquals(MODEL, body.get("model").getAsString());
        assertEquals(1, body.getAsJsonArray("messages").size());

        String accept = stub.lastHeader("Accept");
        assertTrue(accept != null && accept.contains("text/event-stream"),
                "必须声明 Accept: text/event-stream，实际：" + accept);

        // 修复轮 1（Major-2）：api_key 只许出现在 Authorization 头里。桩端断言的是收到的
        // <b>完整</b>头部集合，因此「多发一个密钥头」这类改动会被这条抓住。
        Map<String, List<String>> received = stub.lastHeaders();
        for (Map.Entry<String, List<String>> entry : received.entrySet()) {
            for (String value : entry.getValue()) {
                if (value.contains(API_KEY)) {
                    assertEquals("authorization", entry.getKey().toLowerCase(Locale.ROOT),
                            "api_key 只允许出现在 Authorization 头里，实际还在：" + entry.getKey()
                                    + "；收到：" + received);
                }
            }
        }
        assertTrue(received.values().stream().flatMap(List::stream)
                        .anyMatch(value -> value.contains(API_KEY)),
                "密钥必须真的发出去（否则上一条会「因为哪儿都没有」而空转通过）：" + received);
    }

    // ------------------------------------------------------------------
    // 2. UTF-8 边界（Review Focus 2）：多字节字符被网络分块切断
    // ------------------------------------------------------------------

    @Test
    void stream_utf8CharSplitAcrossChunks_doesNotProduceMojibake() throws Exception {
        byte[] sun = sse(delta("好")).getBytes(StandardCharsets.UTF_8); // 「好」= E5 A5 BD
        int contentAt = contentOffset(sun);
        assertEquals((byte) 0xE5, sun[contentAt], "取字节下标的前提：「好」的首字节必须是 E5");
        assertEquals((byte) 0xA5, sun[contentAt + 1]);
        assertEquals((byte) 0xBD, sun[contentAt + 2]);

        stub = StreamStub.scripted(List.of(
                raw(slice(sun, 0, contentAt + 2), 0),         // ...E5 A5 —— 半个汉字
                raw(slice(sun, contentAt + 2, sun.length), 0), // BD"}}]} + 空行 —— 后半个
                chunk(sse("[DONE]"), 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);

        assertEquals("好", listener.full(),
                "「好」的 UTF-8 被拆成两个 TCP 块后必须仍然还原成「好」");
        assertFalse(listener.full().contains("\uFFFD"),
                "绝不能出现替换字符 U+FFFD（那是逐块 new String(bytes, UTF_8) 的症状）");
        assertEquals(1, listener.deltaCount(), "半个字符不得被当成一个增量发出来");
    }

    @Test
    void stream_twoCharsSplitIntoSingleBytes_reconstructsExactly() throws Exception {
        byte[] frame = sse(delta("你好")).getBytes(StandardCharsets.UTF_8); // 「你」= E5 BD A0，「好」= E5 A5 BD
        int contentAt = contentOffset(frame);
        List<Chunk> script = new ArrayList<>();
        // 先发事件头（"data: {"choices":[{"delta":{"content":"" 共 39 字节），再把这 6 个正文字节
        // 一个一个发过去。头与正文分开是刻意的：这样 TCP 块的边界正好落在「正文字节之间」，
        // 把「字节级累积再解码」这条规则钉到最细的粒度上。
        script.add(raw(slice(frame, 0, contentAt), 0));
        for (int i = 0; i < 6; i++) {
            script.add(raw(slice(frame, contentAt + i, contentAt + i + 1), 0));
        }
        script.add(raw(slice(frame, contentAt + 6, frame.length), 0));
        script.add(chunk(sse("[DONE]"), 0));
        stub = StreamStub.scripted(script);
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);

        assertEquals("你好", listener.full(), "逐字节到达的「你好」必须逐字完整还原");
        assertFalse(listener.full().contains("\uFFFD"));
    }

    @Test
    void stream_crlfLineEndings_stillFramedByBlankLine() throws Exception {
        // CRLF 的两个字节还可能被拆到两个块里：以 CR 结尾的块不能当成「空行已经来了」
        byte[] crlfFrame = ("data: " + delta("好") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        int lastLfAt = crlfFrame.length - 1; // 最后一个字节是事件终止空行的 \n
        stub = StreamStub.scripted(List.of(
                raw(slice(crlfFrame, 0, lastLfAt), 0),                 // 以 \r 结尾，\n 还没到
                raw(slice(crlfFrame, lastLfAt, crlfFrame.length), 0),
                chunk("data: [DONE]\r\n\r\n", 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);

        assertEquals("好", listener.full(), "CRLF 的分帧同样只能由零字符空行产生");
    }

    // ------------------------------------------------------------------
    // 3. 分帧：一个事件被拆成多个 TCP 块 / 注释行 / 缺字段
    // ------------------------------------------------------------------

    @Test
    void stream_eventSplitAcrossThreeTcpChunks_isFramedByBlankLineOnly() throws Exception {
        // 一个完整的 data: 事件被切成三段：任何「按到达块切分」的实现都会解析出坏 JSON
        stub = StreamStub.scripted(List.of(
                chunk("data: {\"choices\":[{\"delta\":{\"con", 0),
                chunk("tent\":\"你好\"}}]}", 0),
                chunk("\n\ndata: [DONE]\n\n", 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);

        assertEquals("你好", listener.full(),
                "分帧只能由零字符空行产生；一个事件拆成 3 个 TCP 块也必须解析成一个增量");
        assertEquals(1, listener.deltaCount(), "被拆开的事件不得变成多个增量或坏 JSON");
    }

    @Test
    void stream_ignoresCommentLines_andSemicolonHeartbeats() throws Exception {
        stub = StreamStub.scripted(List.of(
                chunk(": ping\n\n", 0),                                   // 心跳注释行（事件之外）
                chunk(sse("{\"choices\":[{\"delta\":{}}]}"), 0),           // delta 是空对象
                chunk(sse("{\"choices\":[{\"delta\":{\"content\":null}}]}"), 0), // content 为 null
                chunk(sse("{\"choices\":[]}"), 0),                         // choices 为空
                chunk(sse("{\"choices\":[{\"index\":0}]}"), 0),            // 没有 delta
                chunk(": keep-alive\n\ndata: " + delta("在") + "\n\n", 0),  // 事件之间的注释行
                chunk(sse("[DONE]"), 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);

        assertEquals("在", listener.full(),
                "注释行与缺 delta/content 的分片都必须被跳过，只有真正的增量进正文");
        assertEquals(1, listener.deltaCount(), "被跳过的分片不得产生空增量回调");
        assertEquals(1, listener.completeCount());
    }

    @Test
    void stream_linesWithoutColon_areIgnored_notFoldedIntoTheDataPayload() throws Exception {
        // minor 清算批（`ai` 修复轮 2 复审 §「新登记边界」）：按 SSE 规范，一行里没有冒号时
        // 整行是「字段名 = 整行、值为空」——既不是注释（要以 `:` 开头），也不是 data。
        //
        // 关键形状是「无冒号的行夹在<b>同一个事件的 data 行与空行之间</b>」：修前它会被并进
        // 载荷，于是这个事件拼出的文本不再是合法 JSON → 该事件被跳过 → <b>它前后两个事件的
        // 正文一起丢</b>。这里两种噪音各来一遍：只有空白的行 / 有内容但没冒号的行。
        for (String noise : List.of("   ", "dumps map (not a field)", "\t")) {
            // 三段：正常事件 → （同一事件内的 data + 无冒号行）→ 正常事件 → [DONE]
            String body = sse(delta("甲"))
                    + "data: " + delta("乙") + "\n" + noise + "\n\n"
                    + sse(delta("丙"))
                    + "data: [DONE]\n\n";
            stub = StreamStub.scripted(List.of(chunk(body, 0)));
            RecordingListener listener = new RecordingListener();

            client().stream(streamRequest("hi"), listener);

            assertEquals(List.of("甲", "乙", "丙"), listener.deltas(),
                    "无冒号的行（" + escapeForMessage(noise) + "）在同一个事件里必须被忽略，不能并进 "
                            + "data 载荷——并进去会让该事件的 JSON 非法、前后两个事件的正文一起丢；"
                            + "实际：" + listener.deltas());
            assertEquals("甲乙丙", listener.full(), "三个事件都必须正常投递");
            assertEquals(1, listener.completeCount(), "[DONE] 仍必须正常收尾");
        }
    }

    @Test
    void stream_ignoresOtherFieldLines_butKeepsDataPrefixForms() throws Exception {
        // 与上一条同一性质，另加两个「不许顺手改坏」的对照：
        // ① 规范里的其它字段（event: / id: / retry:）必须被忽略而不是当成 data；
        // ② `data:` 与 `data: ` 两种前缀形态仍必须都认（按 SSE 规范，冒号后<b>一个</b>空格
        //    是分隔符，不是内容）。
        stub = StreamStub.scripted(List.of(
                chunk("event: message\nid: 42\nretry: 1000\n" + sse(delta("甲")), 0),
                chunk("data:" + delta("乙") + "\n\n", 0),
                chunk("data: " + delta("丙") + "\n\n", 0),
                chunk(sse("[DONE]"), 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);

        assertEquals(List.of("甲", "乙", "丙"), listener.deltas(),
                "event:/id:/retry: 行必须被忽略，`data:` 与 `data: ` 两种前缀都必须照常投递；实际："
                        + listener.deltas());
        assertEquals("甲乙丙", listener.full());
    }

    @Test
    void stream_dataPrefixWithoutSpace_isAccepted() throws Exception {
        // `data:` 与 `data: ` 是同一件事（OpenAI 用后者，但协议允许前者）
        stub = StreamStub.scripted(List.of(
                chunk("data:{\"choices\":[{\"delta\":{\"content\":\"密\"}}]}\n\n", 0),
                chunk("data: {\"choices\":[{\"delta\":{\"content\":\"钥\"}}]}\n\n", 0),
                chunk("data:[DONE]\n\n", 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 2);

        assertEquals("密钥", listener.full(), "data: 与 `data: ` 两种形态都要认");
    }

    @Test
    void stream_doneTerminates_andNothingAfterItIsDelivered() throws Exception {
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("甲")), 0),
                chunk(sse("[DONE]"), 0),
                // [DONE] 之后服务端还继续发：绝不能回调，否则界面会在「已结束」之后继续追加内容
                chunk(sse(delta("乙")), 50),
                chunk(sse(delta("丙")), 50)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);
        Thread.sleep(400); // 留足时间让「[DONE] 之后的块」真的到达客户端

        assertEquals(1, listener.deltaCount(),
                "[DONE] 之后不得再有任何 onDelta；实际收到：" + listener.deltas());
        assertEquals("甲", listener.full());
        assertEquals(1, listener.completeCount(), "onComplete 恰好一次");
    }

    @Test
    void stream_onCompleteFiresExactlyOnce_forMultiChunkStream() throws Exception {
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("1")), 0),
                chunk(sse(delta("2")), 10),
                chunk(sse(delta("3")), 10),
                chunk(sse("[DONE]"), 10)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 3);
        Thread.sleep(200);

        assertEquals(3, listener.deltaCount());
        assertEquals("123", listener.full());
        assertEquals(1, listener.completeCount(), "正常结束只许回调一次 onComplete");
    }

    @Test
    void stream_doneAndTrailingEventInSameTcpChunk_onlyFirstIsDelivered() throws Exception {
        // 最尖的一种：`[DONE]` 与它后面的一个完整事件<b>落在同一个 TCP 块里</b>。
        // 那种「读到 [DONE] 就不再发下一次 read」的实现会在这里漏掉——因为这一块已经读进来了，
        // 事件只能由行分帧继续喂给解析器。所以这条钉住的是「按事件、而不是按块」的终止语义。
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("甲")) + sse("[DONE]") + sse(delta("乙")), 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);
        Thread.sleep(200);

        assertEquals(1, listener.deltaCount(),
                "[DONE] 与后续事件同块时，后续事件仍不得回调；实际收到：" + listener.deltas());
        assertEquals("甲", listener.full());
        assertEquals(1, listener.completeCount(), "onComplete 仍然只许一次");
    }

    @Test
    void stream_eventArrivingAfterDone_isDiscarded_evenWhenItIsTheLastBufferedOne() throws Exception {
        // 这条与上一条的差别：多发的那一个事件<b>没有</b>以空行收尾，因此它只可能在
        // 「读循环已经因 [DONE] 停下来之后」的收尾阶段被处理。此时看门人只剩 handleEvent 里
        // 那个 done 判断——只反转读循环的护栏是不够的，这里把那条判断也钉住。
        String lateFrame = "data: " + delta("丙"); // 没有 \n\n：不是一个完整事件
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("甲")), 0),
                chunk(sse("[DONE]"), 0),
                chunk(lateFrame, 50)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);
        Thread.sleep(300);

        assertEquals(1, listener.deltaCount(),
                "[DONE] 之后（含收尾阶段）不得再有任何 onDelta；实际收到：" + listener.deltas());
        assertEquals("甲", listener.full());
        assertEquals(1, listener.completeCount());
    }

    // ------------------------------------------------------------------
    // 4. 形态：不能把整包响应缓冲到内存里再回调
    // ------------------------------------------------------------------

    @Test
    void stream_doesNotBufferWholeResponseInMemory() throws Exception {
        // 桩在 [DONE] 之前睡 300ms。若实现是「读完整个响应再回调」，第一个增量这时都还没到。
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("早")), 0),
                chunk(sse(delta("晚")), 300),
                chunk(sse("[DONE]"), 0)));
        RecordingListener listener = new RecordingListener();

        long startedAt = System.nanoTime();
        client().stream(streamRequest("hi"), listener);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(2, listener.deltaCount(), "两个增量都必须到达");
        assertTrue(listener.firstDeltaAtMillis() >= 0 && listener.firstDeltaAtMillis() < 150,
                "第一个增量必须在桩睡眠期间（<150ms）就到达客户端，说明是流式而非整包缓冲；"
                        + "实际首个增量在 " + listener.firstDeltaAtMillis()
                        + "ms，整个调用 " + elapsedMillis + "ms");
        assertTrue(elapsedMillis >= 250,
                "桩确实睡了 300ms；整次调用时长应覆盖它，实际 " + elapsedMillis + "ms");
    }

    // ------------------------------------------------------------------
    // 5. 中断与失败分类
    // ------------------------------------------------------------------

    @Test
    void stream_truncated_keepsPartialContent_andReportsIncomplete() throws Exception {
        // 两个完整事件之后直接断连：没有 [DONE]
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("前半")), 0),
                chunk(sse(delta("后半")), 0)));
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client().stream(streamRequest("hi"), listener));

        assertEquals(2, listener.deltaCount(), "断连之前收到的增量必须已经发出去（不回滚）");
        assertEquals("前半后半", String.join("", listener.deltas()),
                "调用方按顺序拼出来的正文必须是已收到的全部内容");
        assertEquals(0, listener.completeCount(), "流中断不是正常结束，不得调用 onComplete");
        assertEquals(AiFailureKind.NETWORK, failure.kind(),
                "服务端中途断连属于 NETWORK，实际：" + failure.kind());
        assertTrue(failure.getMessage().contains("回复不完整"),
                "流中断的消息要给出「回复不完整」这个界面措辞（spec §7.6），实际："
                        + failure.getMessage());
    }

    @Test
    void stream_truncatedPartial_isVisibleToCallerThroughDeltas() throws Exception {
        // 与上一条的区别：这条把「不回滚」钉在调用方自己维护的变量上（界面就是这样拿部分内容的）
        stub = StreamStub.scripted(List.of(chunk(sse(delta("保留我")), 0)));
        RecordingListener listener = new RecordingListener();
        StringBuilder callerOwned = new StringBuilder();

        AiFailure failure = assertThrows(AiFailure.class, () -> client().stream(
                streamRequest("hi"),
                new AiStreamListener() {
                    @Override
                    public void onDelta(String text) {
                        listener.onDelta(text);
                        callerOwned.append(text);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        listener.onComplete(fullText);
                    }
                }));

        assertEquals("保留我", callerOwned.toString(), "调用方累积的内容不得被回滚");
        assertEquals(AiFailureKind.NETWORK, failure.kind(),
                "服务端中途断连属于 NETWORK，实际：" + failure.kind());
        assertEquals(0, listener.completeCount());
    }

    @Test
    void stream_httpError401_throwsUNAUTHORIZED_beforeAnyDelta() throws Exception {
        stub = StreamStub.error(401, "application/json", "{\"error\":{\"message\":\"bad key\"}}");
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client().stream(streamRequest("hi"), listener));

        assertEquals(AiFailureKind.UNAUTHORIZED, failure.kind());
        assertTrue(failure.getMessage().contains("401"),
                "消息要带上具体状态码，实际：" + failure.getMessage());
        assertEquals(0, listener.deltaCount(), "错误响应绝不能产生增量回调");
        assertEquals(0, listener.completeCount());
    }

    @Test
    void stream_httpError_producesNoDeltaAndNoComplete() throws Exception {
        for (int status : new int[] {429, 500, 400}) {
            stub = StreamStub.error(status, "application/json", "{\"error\":{\"message\":\"x\"}}");
            RecordingListener listener = new RecordingListener();
            AiFailure failure = assertThrows(AiFailure.class,
                    () -> client().stream(streamRequest("hi"), listener),
                    "HTTP " + status + " 应当抛 AiFailure");
            assertTrue(failure.kind() == AiFailureKind.RATE_LIMITED
                            || failure.kind() == AiFailureKind.SERVER_ERROR,
                    "HTTP " + status + " 的分类不符：" + failure.kind());
            assertEquals(0, listener.deltaCount(), "HTTP " + status + " 不得产生增量");
            assertEquals(0, listener.completeCount(), "HTTP " + status + " 不得调用 onComplete");
            stub.stop();
            stub = null;
        }
    }

    @Test
    void stream_emptyBodyWithoutDONE_reportsFailure_andNoComplete() throws Exception {
        // 200 + event-stream 头，响应体长度为 0（服务端干净地表示「没有内容」）：
        // 不能当成「一次成功的空回复」交给界面。
        stub = StreamStub.empty();
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client().stream(streamRequest("hi"), listener));
        assertEquals(AiFailureKind.MALFORMED_RESPONSE, failure.kind(),
                "既没有内容也没有 [DONE] 的响应不是一次有效回复，实际：" + failure.kind());
        assertEquals(0, listener.deltaCount());
        assertEquals(0, listener.completeCount());
    }

    @Test
    void stream_connectionRefused_isNETWORK_andNoListenerCallbacks() {
        int closedPort = freeThenClosedPort();
        AiClient client = new AiClient(
                new AiConfig("http://127.0.0.1:" + closedPort, API_KEY, MODEL, TIMEOUT),
                HttpClient.newHttpClient());
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client.stream(streamRequest("hi"), listener));
        assertEquals(AiFailureKind.NETWORK, failure.kind());
        assertTrue(failure.getMessage().contains("连接不上"),
                "NETWORK 消息要点明「连接不上」，实际：" + failure.getMessage());
        assertEquals(0, listener.deltaCount());
        assertEquals(0, listener.completeCount());
    }

    @Test
    void stream_rejectsNonStreamingRequest_beforeSendingAnything() throws Exception {
        // 逐次确认门只提供「流式取字」这一个新能力，不得顺手放宽参数校验：
        // stream=false 的请求走流式入口，服务端会回整包 JSON，而流式解析器在等 data: 行——
        // 症状是界面一直「正在等待」。所以就在发包之前拒绝。
        stub = StreamStub.scripted(List.of(chunk(sse(delta("好")), 0), chunk(sse("[DONE]"), 0)));
        AiClient client = client();
        AiRequest nonStreaming = new AiRequest(MODEL, List.of(new AiMessage("user", "hi")), false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> client.stream(nonStreaming, new RecordingListener()));
        assertTrue(e.getMessage().contains("流式"),
                "拒绝消息要点明「流式」，实际：" + e.getMessage());
        assertTrue(stub.lastPath() == null, "非流式请求不得被流式入口发出去");
    }

    @Test
    void stream_rejectsNullRequestAndNullListener_beforeSendingAnything() throws Exception {
        stub = StreamStub.scripted(List.of(chunk(sse("[DONE]"), 0)));
        AiClient client = client();

        // 显式转型：计划 2 Task 7 为了确认门加了 stream(List<AiMessage>, listener) 这条重载，
        // 裸 null 会同时匹配两条重载。这里要验的仍是「AiRequest 入口的参数校验」。
        assertThrows(IllegalArgumentException.class,
                () -> client.stream((AiRequest) null, new RecordingListener()));
        assertThrows(IllegalArgumentException.class,
                () -> client.stream(streamRequest("hi"), null));
        assertThrows(IllegalArgumentException.class,
                () -> client.stream((AiRequest) null, null));
        assertTrue(stub.lastPath() == null, "参数不合法时一个请求都不该发出去");
    }

    // ------------------------------------------------------------------
    // 6. 取消：必须真的关掉连接
    // ------------------------------------------------------------------

    @Test
    void stream_cancelledMidStream_closesConnectionAndReportsInterrupt() throws Exception {
        // 桩：发一个完整事件后一直挂着不关（模拟「还在生成」的服务端），
        // 同时在旁边守着套接字——一旦客户端关掉连接，这次读就会出错，桩因此能给出可断言的证据。
        stub = StreamStub.openEnded(sse(delta("一")));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<String> diag = new AtomicReference<>("(not finished)");
        Thread worker = new Thread(() -> {
            try {
                client().stream(streamRequest("hi"), new RecordingListener());
                diag.set("returned normally, interrupted=" + Thread.currentThread().isInterrupted());
            } catch (InterruptedException expected) {
                // 取消用中断表达（AiFailureKind 刻意不含「用户取消」）
                thrown.set(expected);
                diag.set("InterruptedException");
            } catch (Throwable unexpected) {
                thrown.set(unexpected);
                diag.set(unexpected.getClass().getName() + ": " + unexpected.getMessage()
                        + ", interrupted=" + Thread.currentThread().isInterrupted());
            }
        }, "ai-stream-cancel-test");
        worker.setDaemon(true);
        worker.start();

        awaitTrue("期望收到 1 个增量", () -> stub.deltasSeen() >= 1, DEFAULT_AWAIT_MILLIS);
        worker.interrupt();
        worker.join(3_000);
        assertFalse(worker.isAlive(), "取消后 stream() 必须及时返回，不得把调用线程挂住");
        assertInstanceOf(InterruptedException.class, thrown.get(),
                "取消必须原样表达为 InterruptedException，实际：" + diag.get());

        awaitTrue("取消后必须真的关闭 TCP 连接（桩对套接字的守在读时出错才算数）；桩观测到："
                + stub.disconnectFailure(), stub::disconnectObserved, 5_000);
    }

    @Test
    void stream_interruptedBeforeFirstByte_throwsInterruptedException() throws Exception {
        stub = StreamStub.openEnded(null); // 连响应体都不开始写
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                client().stream(streamRequest("hi"), new RecordingListener());
                thrown.set(new AssertionError("取消路径不得正常返回"));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "ai-stream-cancel-early-test");
        worker.setDaemon(true);
        worker.start();

        awaitTrue("桩没有收到请求", stub::requestSeen, DEFAULT_AWAIT_MILLIS);
        Thread.sleep(150); // 让客户端确实进到「等第一个字节」里
        worker.interrupt();
        worker.join(3_000);
        assertFalse(worker.isAlive(), "取消后 stream() 必须及时返回");
        assertInstanceOf(InterruptedException.class, thrown.get(),
                "中断必须原样表达为 InterruptedException，实际：" + thrown.get());
    }

    // ------------------------------------------------------------------
    // 6.5 修复轮 2（评审 Blocker-1）：干净收尾时，末尾差一个空行的完整事件必须投递
    // ------------------------------------------------------------------

    @Test
    void stream_tailEventWithoutTrailingBlankLine_isStillDelivered() throws Exception {
        // 评审 §5.6 的最小复现 E/F：第一个事件带尾空行、第二个事件<b>缺</b>尾空行，然后干净收尾
        // （分块传输的终止块）。SSE 规范要求事件以空行结尾，但库/代理截断与非规范实现都会踩到
        // ——修前这里静默丢掉「乙」，用户看到「回复不完整」而服务端其实把那段发全了。
        stub = StreamStub.scripted(List.of(chunk(sse(delta("甲")) + "data: " + delta("乙"), 0)));
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client().stream(streamRequest("hi"), listener));

        assertEquals(List.of("甲", "乙"), listener.deltas(),
                "流终止前已经收在行缓冲里的完整事件必须送达 onDelta；实际：" + listener.deltas());
        assertEquals(0, listener.completeCount(), "没有 [DONE] 就不是正常结束");
        assertEquals(AiFailureKind.NETWORK, failure.kind(),
                "没有 [DONE] 的干净收尾仍按「回复不完整」收场，实际：" + failure.kind());
        assertTrue(failure.getMessage().contains("回复不完整"), "实际：" + failure.getMessage());
    }

    @Test
    void stream_onlyEventWithoutTrailingBlankLine_isDelivered_notReportedAsMALFORMED()
            throws Exception {
        // 评审 §5.6 的最小复现 G：整条流只有一个事件，且缺尾空行。修前「一个都收不到」，
        // 而且因为 sawAnything 为 false 被报成 MALFORMED_RESPONSE「服务端没有按流式（SSE）格式
        // 返回任何内容」——与服务端实际行为<b>相反</b>，界面会引导用户去查 base_url / 模型。
        stub = StreamStub.scripted(List.of(chunk("data: " + delta("甲"), 0)));
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client().stream(streamRequest("hi"), listener));

        assertEquals(List.of("甲"), listener.deltas(),
                "唯一一个事件缺尾空行时，它仍然是「已经凑齐」的完整事件；实际：" + listener.deltas());
        assertNotEquals(AiFailureKind.MALFORMED_RESPONSE, failure.kind(),
                "收到过正文时不得报「服务端没有按流式格式返回任何内容」；实际：" + failure.kind()
                        + " / " + failure.getMessage());
        assertEquals(AiFailureKind.NETWORK, failure.kind(), "实际：" + failure.kind());
    }

    @Test
    void stream_contentWasReceived_neverReportsMALFORMED_RESPONSE() throws Exception {
        // 性质 ③：只要真的收到过正文，「服务端没有按流式格式返回任何内容」这句话就与事实相反。
        // 三种「收到内容、但没等到 [DONE]」的形状各来一遍（含两个缺尾空行的形态）。
        for (String body : List.of(
                sse(delta("甲")) + sse(delta("乙")),       // 两个事件都带尾空行
                sse(delta("甲")) + "data: " + delta("乙"), // 末尾事件缺尾空行
                "data: " + delta("甲"))) {                 // 唯一事件缺尾空行
            stub = StreamStub.scripted(List.of(chunk(body, 0)));
            RecordingListener listener = new RecordingListener();
            AiFailure failure = assertThrows(AiFailure.class,
                    () -> client().stream(streamRequest("hi"), listener),
                    "没有 [DONE] 时必须失败：" + body);
            assertFalse(listener.deltas().isEmpty(), "这个形状必须有内容送达：" + body);
            assertNotEquals(AiFailureKind.MALFORMED_RESPONSE, failure.kind(),
                    "收到过正文时不得报「服务端没有按流式格式返回任何内容」：" + body + " → "
                            + failure.kind() + " / " + failure.getMessage());
            stub.stop();
            stub = null;
        }
    }

    @Test
    void stream_doneWithoutTrailingBlankLine_stillCompletes() throws Exception {
        // 同一个性质落在 [DONE] 上：末尾是 [DONE]、缺尾空行 + 干净收尾。修前 done 从未置位，
        // 于是收场 NETWORK「回复不完整」且 onComplete 一次都不回调。
        stub = StreamStub.scripted(List.of(chunk(sse(delta("甲")) + "data: [DONE]", 0)));
        RecordingListener listener = new RecordingListener();

        client().stream(streamRequest("hi"), listener); // 已凑齐 [DONE]：正常返回

        assertEquals(List.of("甲"), listener.deltas());
        assertEquals(1, listener.completeCount(), "末尾的 [DONE] 已经凑齐就必须收尾");
        assertEquals("甲", listener.full());
    }

    @Test
    void stream_incompleteTailEvent_isDropped_notDeliveredAsBadContent() throws Exception {
        // 「不完整的尾巴怎么办」的决定：修好 Blocker 之后末尾事件同样要送进解析器，但只有能解析成
        // JSON（或 [DONE]）的内容才会产生回调。<b>半个事件</b>被丢弃，绝不投递出坏内容；
        // 已收到的增量不回滚。分类仍是「回复不完整」——服务端确实按 SSE 发了东西，只是末尾截断了。
        stub = StreamStub.scripted(List.of(chunk(sse(delta("甲")) + "data: {\"choices\":[{", 0)));
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client().stream(streamRequest("hi"), listener));

        assertEquals(List.of("甲"), listener.deltas(),
                "半个事件不得变成增量（也不得变成半个 JSON）：" + listener.deltas());
        assertFalse(listener.deltas().toString().contains("choices"),
                "半个事件的内容（含 JSON 片段）绝不能被当成正文投递：" + listener.deltas());
        assertEquals(0, listener.completeCount());
        assertEquals(AiFailureKind.NETWORK, failure.kind(),
                "末尾被截断不能说成「服务端没有按 SSE 返回任何内容」；实际：" + failure.kind()
                        + " / " + failure.getMessage());
        assertTrue(failure.getMessage().contains("回复不完整"), "实际：" + failure.getMessage());
    }

    @Test
    void stream_cleanEofWithIncompleteTrailingBytes_dropsThemExplicitly() throws Exception {
        // 修复轮 2 的决定（评审 Minor-4）：干净收尾时剩下的<b>不完整</b>字节序列一律显式丢弃，
        // 绝不变成 U+FFFD 混进正文——那等于把「半个字节」当成模型输出显示给用户，而原先那个
        // 替换字符只留在行缓冲里、永远到不了 onDelta/onComplete（评审实测：它是死代码）。
        // 但「丢弃」这件事本身必须让用户看得见：失败消息里点名有几个字节凑不出一个完整字符。
        byte[] prefix = "data: {\"choices\":[{\"delta\":{\"content\":\""
                .getBytes(StandardCharsets.UTF_8);
        byte[] halfChar = {(byte) 0xE5, (byte) 0xA5}; // 「好」的前两个字节，缺最后一个 BD
        stub = StreamStub.scripted(List.of(raw(concat(prefix, halfChar), 0)));
        RecordingListener listener = new RecordingListener();

        AiFailure failure = assertThrows(AiFailure.class,
                () -> client().stream(streamRequest("hi"), listener));

        assertEquals(List.of(), listener.deltas(),
                "凑不出一个完整字符的尾巴不得变成任何增量；实际：" + listener.deltas());
        assertFalse(listener.deltas().toString().contains("\uFFFD"),
                "绝不能凭空造一个 U+FFFD 当正文；实际：" + listener.deltas());
        assertTrue(failure.getMessage().contains("2 个字节"),
                "被丢弃的不完整尾巴必须显式说出来（否则它与死代码没有区别）；实际："
                        + failure.getMessage());
        assertTrue(failure.getMessage().contains("凑不出一个完整字符"),
                "消息要点明丢弃的原因；实际：" + failure.getMessage());
        assertEquals(0, listener.completeCount());
    }

    // ------------------------------------------------------------------
    // 7. 架构护栏复核：加了流式入口之后仍然拿不到文件写入权
    // ------------------------------------------------------------------

    @Test
    void aiPackage_stillHasNoPathEntryPoint_andNoStorageDependency() throws Exception {
        Path sourceRoot = sourceRoot();

        // 1) 新接口本身的签名：只有 String，没有任何 Path / 文件类型
        Class<?> listenerType = AiStreamListener.class;
        assertTrue(listenerType.isInterface(), "AiStreamListener 必须是接口");
        List<String> listenerSignatures = new ArrayList<>();
        for (Method method : listenerType.getMethods()) {
            for (Type parameter : method.getGenericParameterTypes()) {
                assertEquals("java.lang.String", parameter.getTypeName(),
                        "AiStreamListener 的参数只许是可显示的纯文本：" + method.getName());
            }
            assertEquals("void", method.getGenericReturnType().getTypeName(),
                    "onDelta/onComplete 不得返回值：" + method.getName());
            listenerSignatures.add(method.getName() + "(" + String.join(",", Arrays.stream(
                    method.getGenericParameterTypes()).map(Type::getTypeName).toList()) + ")");
        }
        assertTrue(listenerSignatures.contains("onDelta(java.lang.String)"),
                "接口必须原样提供 onDelta(String)，实际：" + listenerSignatures);
        assertTrue(listenerSignatures.contains("onComplete(java.lang.String)"),
                "接口必须原样提供 onComplete(String)，实际：" + listenerSignatures);

        // 2) ai 包内不得出现 java.nio.file / Files / File / storage 的引用（源码层面）
        List<Path> sources = new ArrayList<>();
        try (var stream = Files.list(sourceRoot)) {
            for (Path entry : stream.toList()) {
                if (entry.getFileName().toString().endsWith(".java")) {
                    sources.add(entry);
                }
            }
        }
        assertTrue(sources.size() >= 7,
                "ai 包源码至少应有 7 个文件（含新增的 AiStreamListener），实际 " + sources.size()
                        + "：" + sources);
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.startsWith("\uFEFF"), "源文件不得带 BOM：" + source.getFileName());
            // 只看代码，不看注释：注释里可以（也应该）写出「本包不得依赖 storage」这条约束本身，
            // 那不是依赖。真正的强制力在 AiClientTest 的反射扫描（类型/签名/字段）上。
            String code = stripComments(text);
            for (String forbidden : List.of("java.nio.file", "java.io.File", "Files.",
                    "com.xiaoyiyuan.study.storage")) {
                assertFalse(code.contains(forbidden),
                        source.getFileName() + " 的<b>代码</b>里不得出现「" + forbidden
                                + "」（spec §7.5：ai 包无文件写入权，不依赖 storage）");
            }
        }

        // 3) stream 的签名里没有任何文件类型（反射扫真实字节码，不信源码文本）
        Method streamMethod = AiClient.class.getMethod("stream", AiRequest.class,
                AiStreamListener.class);
        assertTrue(streamMethod.getGenericParameterTypes().length == 2,
                "stream 必须恰好有两个参数");
        for (Type parameter : streamMethod.getGenericParameterTypes()) {
            assertFalse(parameter.getTypeName().contains("java.nio.file"),
                    "stream 的参数里不得出现文件类型：" + parameter.getTypeName());
        }
        assertFalse(streamMethod.getGenericReturnType().getTypeName().contains("java.nio.file"),
                "stream 的返回类型里不得出现文件类型");
        assertEquals("void", streamMethod.getGenericReturnType().getTypeName(),
                "stream 不得返回「已经写好的文件」之类的东西");
    }

    @Test
    void stream_writesNoFile(@TempDir Path tempDir) throws Exception {
        stub = StreamStub.scripted(List.of(
                chunk(sse(delta("不落盘")), 0),
                chunk(sse("[DONE]"), 0)));
        Path target = tempDir.resolve("不该出现的目录");
        assertFalse(Files.exists(target));
        // 修复轮 1（Major-4）：与 complete_doesNotWriteAnyFile 同一个姿态——只查 @TempDir 抓不到
        // 「写到进程工作目录」那种最省事的写法，所以同时快照工作目录的条目集合。
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Set<String> entriesBefore = entryNames(workingDirectory);

        RecordingListener listener = new RecordingListener();
        client().stream(streamRequest("hi"), listener);
        awaitDeltaCount(listener, 1);

        assertFalse(Files.exists(target), "stream 不得创建任何目录/文件");
        try (var children = Files.list(tempDir)) {
            assertEquals(List.of(), children.toList(), "stream 前后 tempDir 必须没有新条目");
        }
        Set<String> entriesAfter = entryNames(workingDirectory);
        assertTrue(entriesAfter.equals(entriesBefore),
                "stream 前后工作目录（" + workingDirectory + "）的<b>递归</b>快照必须一致"
                        + "（修复轮 2：只查一层时写到 target/ 之类的子目录会漏）；差异："
                        + snapshotDifference(entriesBefore, entriesAfter));
    }

    // ------------------------------------------------------------------
    // 修复轮 1（评审 Major-1）：超时必须覆盖流式读取
    // ------------------------------------------------------------------

    @Test
    void stream_silenceAfterOneDelta_failsAsTIMEOUT_withinBoundedTime() throws Exception {
        // 性质 B：不能用「总时长」当超时（长回答是正常的），但「超过配置超时没有任何新字节到达」
        // 必须按 TIMEOUT 收场。SSE 开始之后请求级 timeout 就失效了——没有这条护栏时 read()
        // 会一直阻塞（评审实测 180s+ 不返回），界面永远停在「正在请求」，用户只能杀进程。
        Duration silenceTimeout = Duration.ofSeconds(1);
        stub = StreamStub.openEnded(sse(delta("一")));
        RecordingListener listener = new RecordingListener();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<String> diag = new AtomicReference<>("(not finished)");

        long startedAt = System.nanoTime();
        Thread worker = new Thread(() -> {
            try {
                clientWithTimeout(silenceTimeout).stream(streamRequest("hi"), listener);
                diag.set("returned normally, interrupted=" + Thread.currentThread().isInterrupted());
            } catch (Throwable t) {
                thrown.set(t);
                diag.set(t.getClass().getName() + ": " + t.getMessage());
            }
        }, "ai-stream-silence-test");
        worker.setDaemon(true);
        worker.start();
        worker.join(HANG_DEADLINE_MILLIS);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertFalse(worker.isAlive(),
                "服务端发完一个增量后无限静默时，stream() 必须在配置超时（" + silenceTimeout
                        + "）+ 余量内结束；实际 " + elapsedMillis + "ms 后仍未返回（Major-1 的挂死形状）");
        assertInstanceOf(AiFailure.class, thrown.get(),
                "结束方式必须是 TIMEOUT 类失败，实际：" + diag.get());
        AiFailure failure = (AiFailure) thrown.get();
        assertEquals(AiFailureKind.TIMEOUT, failure.kind(),
                "静默超时必须归为 TIMEOUT，实际：" + failure.kind());
        assertTrue(failure.getMessage().contains("超时"), "实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains(silenceTimeout.toString()),
                "消息要带上具体超时值（" + silenceTimeout + "），实际：" + failure.getMessage());
        assertTrue(elapsedMillis < HANG_DEADLINE_MILLIS,
                "耗时上界必须真的被钉住：实际 " + elapsedMillis + "ms");
        assertEquals(1, listener.deltaCount(), "已经收到的增量不得回滚");
        assertEquals(0, listener.completeCount(), "静默超时不是正常结束，不得调用 onComplete");
        awaitTrue("静默超时后必须真的关掉连接（否则每次卡死都会泄漏一条连接）",
                stub::disconnectObserved, 5_000);
    }

    @Test
    void stream_noResponseHeadersWithinTimeout_failsAsTIMEOUT() throws Exception {
        // 性质 A 在流式入口上的对应面：服务端接了连接却迟迟不给响应头时，也必须有界失败。
        // 桩睡足 60 秒：比用例的 15 秒上界长得多，所以「实现没有遵守配置超时」时一定是
        // 「15 秒后仍没返回」这种可读的红，而不是恰好等到桩自己发响应头。
        Duration headerTimeout = Duration.ofSeconds(1);
        stub = StreamStub.slowHeaders(60_000);
        RecordingListener listener = new RecordingListener();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        long startedAt = System.nanoTime();
        Thread worker = new Thread(() -> {
            try {
                clientWithTimeout(headerTimeout).stream(streamRequest("hi"), listener);
                thrown.set(new AssertionError("响应头都迟迟不来时不得正常返回"));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "ai-stream-header-timeout-test");
        worker.setDaemon(true);
        worker.start();
        worker.join(HANG_DEADLINE_MILLIS);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertFalse(worker.isAlive(),
                "响应头超时后 stream() 必须结束；实际 " + elapsedMillis + "ms 后仍在跑");
        assertInstanceOf(AiFailure.class, thrown.get(), "实际：" + thrown.get());
        AiFailure failure = (AiFailure) thrown.get();
        assertEquals(AiFailureKind.TIMEOUT, failure.kind(), "实际：" + failure.kind());
        assertTrue(failure.getMessage().contains("超时"), "实际：" + failure.getMessage());
        assertTrue(failure.getMessage().contains(headerTimeout.toString()),
                "消息要带上具体超时值（" + headerTimeout + "），实际：" + failure.getMessage());
        assertTrue(elapsedMillis < HANG_DEADLINE_MILLIS, "耗时上界：实际 " + elapsedMillis + "ms");
        assertEquals(0, listener.deltaCount());
        assertEquals(0, listener.completeCount());
    }

    // ------------------------------------------------------------------
    // 辅助：客户端与请求
    // ------------------------------------------------------------------

    private AiClient client() {
        return new AiClient(new AiConfig(stub.baseUrl(), API_KEY, MODEL, TIMEOUT),
                HttpClient.newHttpClient());
    }

    /** 修复轮 1：需要快速触发超时的用例用这个（静默 / 响应头超时）。 */
    private AiClient clientWithTimeout(Duration timeout) {
        return new AiClient(new AiConfig(stub.baseUrl(), API_KEY, MODEL, timeout),
                HttpClient.newHttpClient());
    }

    /**
     * 一个目录的<b>递归</b>快照：相对路径集合（「调用前后不得出现新文件」的对比用）。
     *
     * <p>修复轮 2（评审 §4.4）：只查一层条目名时，写到 {@code <cwd>/target/v.tmp} 这种子目录里
     * 就会漏——评审实测那时字节码护栏与快照双双全绿、而文件真的被写了出来。递归到每一层，
     * 写哪儿都会在快照里露面。
     */
    private static Set<String> entryNames(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
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

    private static AiRequest streamRequest(String content) {
        return new AiRequest(MODEL, List.of(new AiMessage("user", content)), true);
    }

    /** 把一行噪音渲染成可读形态（失败消息里能看清到底是哪种不可见字符）。 */
    private static String escapeForMessage(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\t') {
                out.append("\\t");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 一个 SSE 事件：{@code data: <payload>\n\n}。 */
    private static String sse(String payload) {
        return "data: " + payload + "\n\n";
    }

    /** 一个流式分片的 SSE 载荷（用 Gson 构造，不靠手写转义）。 */
    private static String delta(String content) {
        JsonObject delta = new JsonObject();
        delta.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("delta", delta);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }

    /** 取出正文字节在整个事件字节序列里的偏移（用字节序列反查，避免手数字节位置）。 */
    private static int contentOffset(byte[] frame) {
        byte[] needle = "content\":\"".getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= frame.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (frame[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i + needle.length;
        }
        throw new IllegalStateException("事件字节序列里找不到 content 字段");
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(source, from, out, 0, to - from);
        return out;
    }

    private static Chunk raw(byte[] bytes, int delayMillis) {
        return new Chunk(bytes, delayMillis);
    }

    private static Chunk chunk(String text, int delayMillis) {
        return new Chunk(text.getBytes(StandardCharsets.UTF_8), delayMillis);
    }

    /** 把两段字节接起来（构造「只发了半个字符」这种输入时必须按字节拼，不能用 String）。 */
    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static int freeThenClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 生产源码目录 {@code study-core/src/main/java/com/xiaoyiyuan/study/ai}。 */
    private static Path sourceRoot() {
        Path candidate = Paths.get("src", "main", "java", "com", "xiaoyiyuan", "study", "ai");
        if (Files.isDirectory(candidate)) {
            return candidate.toAbsolutePath();
        }
        throw new IllegalStateException("找不到 ai 包源码目录（当前工作目录："
                + Paths.get("").toAbsolutePath() + "）");
    }

    /**
     * 去掉 {@code //} 行注释与 {@code /* ... *}{@code /} 块注释，保留代码。
     *
     * <p>只用于<b>源码扫描</b>这一类断言，不参与编译；字符串字面量里的 {@code //} 会被误当成注释，
     * 但本包没有那种字面量（要真出现了，测试会变得保守而不是变松）。
     */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < text.length()) {
                        out.append(text.charAt(++i));
                    }
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 辅助：轮询等待（本地桩是异步的）
    // ------------------------------------------------------------------

    private static void awaitDeltaCount(RecordingListener listener, int expected) throws Exception {
        awaitTrue("期望收到 " + expected + " 个增量，实际 " + listener.deltaCount()
                + "：" + listener.deltas(), () -> listener.deltaCount() >= expected,
                DEFAULT_AWAIT_MILLIS);
    }

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

    // ------------------------------------------------------------------
    // 辅助：记录回调的 listener
    // ------------------------------------------------------------------

    /** 记录回调序列的 listener；每个用例一个实例，不用静态状态（用例之间不得互相污染）。 */
    private static final class RecordingListener implements AiStreamListener {

        private final List<String> deltas = new ArrayList<>();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicReference<String> full = new AtomicReference<>();
        private final AtomicReference<Long> firstDeltaAt = new AtomicReference<>();
        private final long startedAt = System.nanoTime();

        @Override
        public synchronized void onDelta(String text) {
            if (firstDeltaAt.get() == null) {
                firstDeltaAt.set((System.nanoTime() - startedAt) / 1_000_000);
            }
            deltas.add(text);
        }

        @Override
        public synchronized void onComplete(String fullText) {
            completions.incrementAndGet();
            full.set(fullText);
        }

        synchronized int deltaCount() {
            return deltas.size();
        }

        synchronized String delta(int index) {
            return deltas.get(index);
        }

        synchronized List<String> deltas() {
            return List.copyOf(deltas);
        }

        int completeCount() {
            return completions.get();
        }

        String full() {
            return full.get();
        }

        long firstDeltaAtMillis() {
            Long at = firstDeltaAt.get();
            return at == null ? -1 : at;
        }
    }

    // ------------------------------------------------------------------
    // 辅助：能逐字节控制发送节奏的桩
    // ------------------------------------------------------------------

    /** 一段要发出去的字节 + 发送前的等待毫秒数。 */
    private record Chunk(byte[] bytes, int delayMillis) {
    }

    /**
     * 按脚本逐段发送响应体的 SSE 桩。
     *
     * <p>用 {@code sendResponseHeaders(200, 0)} 声明<b>分块传输</b>（长度未知），拿到响应体流后
     * 按脚本 {@code write + flush}。整包响应体（{@code sendResponseHeaders(200, len)}）会让 JDK
     * 自己决定怎么切块，构造不出「半个汉字」「一个事件拆三块」这些边界。
     *
     * <p>取消用例另有一条证据链：桩在请求体读完的那一刻起，用一条独立线程守在套接字上读——
     * 客户端真的关掉连接时，这次读会立刻出错（或读回流结束）。「客户端不再读」与「连接被关掉」
     * 是两件不同的事，只有这个信号能区分它们。
     */
    private static final class StreamStub {

        private final HttpServer server;
        private final ExecutorService executor;
        private final ExecutorService probeExecutor;
        private final List<Chunk> script = new ArrayList<>();
        private final Object scriptLock = new Object();
        private final AtomicBoolean moreChunks = new AtomicBoolean();
        private final AtomicBoolean requestArrived = new AtomicBoolean();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final AtomicReference<String> disconnectFailure = new AtomicReference<>();
        private final AtomicInteger deltasSeen = new AtomicInteger();

        private final List<String> paths = new ArrayList<>();
        private final List<String> methods = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private final List<String> authHeaders = new ArrayList<>();
        private final List<String> acceptHeaders = new ArrayList<>();
        private final List<Map<String, List<String>>> allHeaders = new ArrayList<>();

        private StreamStub(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
            this.probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ai-stream-stub-disconnect-probe");
                thread.setDaemon(true);
                return thread;
            });
        }

        /** 按脚本发送，发完就关闭响应体（正常/截断场景）。 */
        static StreamStub scripted(List<Chunk> chunks) throws IOException {
            StreamStub stub = start();
            for (Chunk chunk : chunks) {
                stub.addChunk(chunk);
            }
            stub.moreChunks.set(false);
            return stub;
        }

        /** 发完脚本里的内容后<b>不关闭</b>响应体（模拟服务端还在生成），用于取消用例。 */
        static StreamStub openEnded(String firstEventOrNull) throws IOException {
            StreamStub stub = start();
            if (firstEventOrNull != null) {
                stub.addChunk(chunk(firstEventOrNull, 0));
            }
            stub.moreChunks.set(true);
            return stub;
        }

        /**
         * 200 + event-stream 头，但响应体长度为 0：服务端<b>干净地</b>表示「这次没有内容」。
         *
         * <p>刻意不用分块传输：在 JDK 的 {@code HttpServer} 上，往分块响应体里写
         * {@code "0\r\n\r\n"} 会被再包一层 chunk（变成长度为 3 的块），根本起不到终止块的作用。
         * 想在分块模式下干净收尾，只能让 {@code sendResponseHeaders} 自己写终止块。
         */
        static StreamStub empty() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            StreamStub stub = new StreamStub(server, executor);
            server.createContext("/", exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    stub.record(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                // 长度必须显式给出：0 在 HttpServer 里等于「分块传输」，-1 才是「长度为 0」
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        /**
         * 修复轮 1：读到请求后先睡 {@code delayMillis} 毫秒<b>再</b>发响应头
         * （模拟「服务端接了连接却迟迟不出结果」；评审 Minor-3 说的「没有读超时」的另一半）。
         */
        static StreamStub slowHeaders(int delayMillis) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            StreamStub stub = new StreamStub(server, executor);
            server.createContext("/", exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    stub.record(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return; // 不写响应头就退出：连接由 HttpServer 收掉
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        /** 固定长度的错误响应（走 {@code sendResponseHeaders(status, len)}，与流式无关）。 */
        static StreamStub error(int status, String contentType, String errorBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            StreamStub stub = new StreamStub(server, executor);
            server.createContext("/", exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    stub.record(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                byte[] payload = errorBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                } catch (IOException clientGone) {
                    stub.markDisconnected(clientGone);
                }
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        private static StreamStub start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            StreamStub stub = new StreamStub(server, executor);
            server.createContext("/", stub.new Streaming());
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        private void addChunk(Chunk chunk) {
            synchronized (scriptLock) {
                script.add(chunk);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void record(HttpExchange exchange, String body) {
            requestArrived.set(true);
            paths.add(exchange.getRequestURI().getPath());
            methods.add(exchange.getRequestMethod());
            bodies.add(body);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            acceptHeaders.add(exchange.getRequestHeaders().getFirst("Accept"));
            // 修复轮 1：连全部头部一起记下来（拷贝一份），供「api_key 只许在 Authorization 里」断言
            allHeaders.add(new LinkedHashMap<>(exchange.getRequestHeaders()));
        }

        String lastPath() {
            return paths.isEmpty() ? null : paths.get(paths.size() - 1);
        }

        String lastMethod() {
            return methods.isEmpty() ? null : methods.get(methods.size() - 1);
        }

        String lastBody() {
            return bodies.isEmpty() ? null : bodies.get(bodies.size() - 1);
        }

        String lastHeader(String name) {
            if ("Authorization".equals(name)) {
                return authHeaders.isEmpty() ? null : authHeaders.get(authHeaders.size() - 1);
            }
            return acceptHeaders.isEmpty() ? null : acceptHeaders.get(acceptHeaders.size() - 1);
        }

        /** 最后一次请求收到的<b>完整</b>头部集合（修复轮 1 的密钥位置断言用）。 */
        Map<String, List<String>> lastHeaders() {
            return allHeaders.isEmpty() ? Map.of() : allHeaders.get(allHeaders.size() - 1);
        }

        boolean requestSeen() {
            return requestArrived.get();
        }

        int deltasSeen() {
            return deltasSeen.get();
        }

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

        /**
         * 守在这次连接的套接字上读，直到客户端把连接关掉（读回流结束或读出错）。
         *
         * <p>请求体在调用本方法之前已经读完，所以这次读只可能在「对端关闭」时返回。
         */
        private void monitorForDisconnect(InputStream in) {
            probeExecutor.execute(() -> {
                byte[] scratch = new byte[64];
                try {
                    while (true) {
                        int read = in.read(scratch);
                        if (read < 0) {
                            markDisconnected(new IOException("client closed the socket (EOF)"));
                            return;
                        }
                    }
                } catch (IOException closedByClient) {
                    markDisconnected(closedByClient);
                }
            });
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
            probeExecutor.shutdownNow();
        }

        private final class Streaming implements HttpHandler {

            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try (InputStream in = exchange.getRequestBody()) {
                    record(exchange, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    monitorForDisconnect(in);
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                // 长度 0 = 分块传输：JDK 立刻把响应头发出去，之后每次 flush 的字节数由脚本决定
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                try {
                    while (true) {
                        Chunk next = null;
                        synchronized (scriptLock) {
                            if (!script.isEmpty()) {
                                next = script.remove(0);
                            }
                        }
                        if (next == null) {
                            if (moreChunks.get()) {
                                // 脚本发完了但要求保持连接：挂着等客户端取消
                                Thread.sleep(50);
                                continue;
                            }
                            break;
                        }
                        if (next.delayMillis() > 0) {
                            Thread.sleep(next.delayMillis());
                        }
                        out.write(next.bytes());
                        out.flush();
                        deltasSeen.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException clientGone) {
                    markDisconnected(clientGone);
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // 客户端已断开时关闭流同样会抛；这不是桩的问题
                    }
                }
            }
        }
    }
}
