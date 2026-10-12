package com.xiaoyiyuan.study.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 逐次确认门的行为测试（spec §7.5；计划 2 Task 7）。
 *
 * <p><b>绝不访问真实网络</b>：每个用例都打在 JDK 自带的
 * {@link com.sun.net.httpserver.HttpServer} 本地桩上（绑定 {@code 127.0.0.1:0} 取随机端口）。
 *
 * <p><b>为什么不能像计划里原来写的那样「用装饰器 spy AiClient」</b>：{@link AiClient} 是
 * {@code final} 类，包装不了；而计划 2 的 pre-flight 裁定明确要求<b>不改它的 {@code final}</b>
 * （那是 spec §7.5 的接口意图）。所以这里的 spy 换成「把 AiClient 指向本地桩、在桩的 handler 上
 * 自增 {@link CountingStub#hits()}」——桩上收到的请求数就是「有没有真的发出去」的物理证据，
 * 比读代码可靠。
 *
 * <p>四条性质，每条都由「错误实现下会红」的断言钉住：
 * <ol>
 *   <li><b>未确认不发请求</b>：{@code hits == 0}（而不是「异常抛出来了就算」）；</li>
 *   <li><b>反证</b>：确认之后 {@code hits == 1}——否则第 1 条可以靠「什么都不发」空转通过；</li>
 *   <li><b>预览 == 实发</b>：gate 记录的 {@code promptText()} 逐字出现在桩收到的请求体里，
 *       且 5000 字的正文一个字不少（不截断、不脱敏、不加工）；</li>
 *   <li><b>逐次确认</b>：连续两次发送要两次确认；第二次未确认时不得复用第一次的确认
 *       （gate 侧计数 + 桩侧计数各自钉住）。</li>
 * </ol>
 */
class ConsentGateTest {

    /** 桩上验证用的 API Key（写死，避免拿配置值断言配置值）。 */
    private static final String API_KEY = "sk-consent-test-789";

    /** 模型名同理写死。 */
    private static final String MODEL = "deepseek-chat";

    /** 本机桩往返足够了。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 一个形状完全正确的 chat.completion 响应体（逐字）。 */
    private static final String GOOD_BODY = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"deepseek-chat",
             "choices":[{"index":0,"message":{"role":"assistant","content":"收到"},"finish_reason":"stop"}]}""";

    /** {@link ConfirmingAiSession#refusedMessage()} 的定义。 */
    private static final String REFUSED =
            ConfirmingAiSession.refusedMessage();

    private CountingStub stub;

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop();
            stub = null;
        }
    }

    // ------------------------------------------------------------------
    // 1. 未确认：一个字节都不许发出去
    // ------------------------------------------------------------------

    @Test
    void notConfirmed_sendsNothingToTheClient() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        ConfirmingAiSession session = session(CountingGate.always(false));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> session.complete(PromptRole.COUNSELOR, List.of(message("user", "把这段情绪记录发给 AI"))));

        // 桩侧证据：网络请求数为 0（不是「异常抛了所以大概没发」）
        assertEquals(0, stub.hits(), "未确认时绝不能发起任何网络请求");
        assertTrue(refused.getMessage().contains(REFUSED),
                "未确认必须给出可识别的固定措辞，实际：" + refused.getMessage());
    }

    @Test
    void notConfirmed_hasNoSideEffectsOnLastSentPreview() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        ConfirmingAiSession session = session(CountingGate.always(false));

        assertEquals("", session.lastSentPreview(), "新建的会话没有已发送内容");
        assertThrows(IllegalStateException.class,
                () -> session.complete(PromptRole.COUNSELOR, List.of(message("user", "hi"))));

        assertEquals("", session.lastSentPreview(),
                "未确认时连 lastSentPreview 都不能变（那不是一次已发送）");
        assertEquals(0, stub.hits(), "未确认时请求数必须是 0");
    }

    @Test
    void gateIsConsultedBeforeTheRequestIsEvenBuilt() throws Exception {
        // 桩侧与门侧往同一本日志里写：网络先发生还是确认先发生，直接看顺序。
        // 日志必须是同步容器：network 由桩的 handler 线程写入，本线程随后读取——
        // 普通 ArrayList 会让这条断言变成看运气的 flaky（写入对本线程不可见）。
        stub = CountingStub.serving(GOOD_BODY);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        stub.shareLogWith(log);
        RecordingGate gate = RecordingGate.logging(log, true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);

        session.complete(PromptRole.PLANNER, List.of(message("user", "hi")));
        awaitTrue(log, 1);

        assertEquals(2, log.size(), "确认与网络各应记一笔，实际：" + log);
        assertEquals("confirm", log.get(0),
                "确认必须排在网络之前（顺序被颠倒时这里直接不对）：" + log);
        assertEquals("network", log.get(1), "第二笔必须是真正的网络请求：" + log);
        assertEquals(1, gate.calls(), "确认恰好一次");
        assertEquals(1, stub.hits(), "反证：确认之后必须真的发一次");
    }

    /** 轮询等待日志变长：桩的 handler 在另一个线程上写，异步完成。 */
    private static void awaitTrue(List<String> log, int expectedSize) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (log.size() < expectedSize && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    // ------------------------------------------------------------------
    // 2. 反证：确认之后必须真的发出去（否则第 1 条是空转的绿灯）
    // ------------------------------------------------------------------

    @Test
    void whenConfirmed_theRequestReallyGoesOut() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        ConfirmingAiSession session = session(CountingGate.always(true));

        assertEquals("收到", session.complete(PromptRole.PLANNER, List.of(message("user", "hi"))));

        assertEquals(1, stub.hits(), "确认之后必须真的发一次请求");
        assertEquals("/v1/chat/completions", stub.lastPath(), "必须打到规范化后的补全路径");

        JsonObject body = JsonParser.parseString(stub.lastBody()).getAsJsonObject();
        assertFalse(body.get("stream").getAsBoolean(), "complete 必须发非流式请求");
        assertEquals(MODEL, body.get("model").getAsString());
    }

    // ------------------------------------------------------------------
    // 3. 预览 == 实发（逐字节）
    // ------------------------------------------------------------------

    @Test
    void confirmed_sendsExactlyThePreviewedText() throws Exception {
        // 分隔符就是实现契约里的那条（{@link PendingRequest#joinContents}）：正文之间一个换行。
        // 「逐字」这件事不能假设实发请求体里会出现裸换行——JSON 会把正文内部的换行转义成 \n。
        // 故意写成 char（不是 short/int）：short 会让 StringBuilder.append 选中 append(int)
        // 而把 '\n' 变成字符串 "10"——这个坑在上一次运行里真的发生了
        char separator = '\n';
        stub = CountingStub.serving(GOOD_BODY);
        RecordingGate gate = new RecordingGate(new ArrayList<>(), true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);

        String firstPart = "你是生活与心理顾问，不做诊断。";
        String secondPart = "2026-10-08：情绪 疲惫 ｜ 强度 3 — 备注：实验报告还没写，有点烦";
        List<AiMessage> messages = List.of(message("system", firstPart), message("user", secondPart));
        String promptText = PendingRequest.joinContents(messages);

        session.complete(PromptRole.COUNSELOR, messages);

        assertEquals(1, gate.calls(), "确认必须被咨询恰好一次");
        assertEquals(promptText, gate.lastSeen().promptText(),
                "gate 看到的必须是完整文本（提示词 + 全部消息），逐字一致");
        assertEquals(separator, promptText.charAt(firstPart.length()),
                "消息之间恰好是一个换行（预览文本的分隔契约）");
        assertEquals(promptText, session.lastSentPreview(),
                "lastSentPreview 必须是当时确认过的同一份文本（供界面回显与审计）");

        // ---- 桩侧证据（全部打在桩真的收到的<b>原始字节</b>上）----
        String wire = stub.lastBody();

        // 证据 1：正文里没有需要 JSON 转义的字符时，它必须逐字（含全角竖线）出现在实发请求体里。
        // 两段正文都这么验：既证明「一个字都没少」，也证明「一个字都没被加工」。
        assertTrue(wire.contains(firstPart),
                "第一段正文必须逐字出现在实发请求体里；实发请求体：\n" + wire);
        assertTrue(wire.contains(secondPart),
                "第二段正文必须逐字出现在实发请求体里；实发请求体：\n" + wire);

        // 证据 2：实发的 messages 就是预览里的那几条（数量、角色、正文逐字）
        JsonArray sent = JsonParser.parseString(wire).getAsJsonObject().getAsJsonArray("messages");
        assertEquals(messages.size(), sent.size(), "实发消息条数必须与待发送的一致");
        StringBuilder reassembled = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            JsonObject one = sent.get(i).getAsJsonObject();
            assertEquals(messages.get(i).role(), one.get("role").getAsString(), "第 " + i + " 条角色");
            assertEquals(messages.get(i).content(), one.get("content").getAsString(),
                    "第 " + i + " 条正文必须逐字一致");
            if (i > 0) {
                reassembled.append(separator);
            }
            reassembled.append(one.get("content").getAsString());
        }
        // 证据 3：把实发正文按预览的分隔规则拼回来，必须等于预览文本——
        // 「预览与实发一致」因此不依赖「JSON 里原样出现换行」这个不成立的前提
        assertEquals(promptText, reassembled.toString(),
                "实发正文拼回来必须等于预览文本（否则界面显示的不是真正会发出去的东西）");
    }

    /**
     * 从原始 JSON 文本里、在 {@code fromIndex} 之后，取出第一段字符串字面量并按 JSON 规则还原。
     *
     * <p>刻意只扫字符串字面量、不解析整份 JSON：本用例要证明的正是「线上写下的字节」，
     * 用一个只认引号与反斜杠转义的极小状态机最贴近事实（解析器可能做规范化，
     * 那样就分不清「实发字节」与「解析结果」了）。
     */
    private static String jsonStringValueAt(String json, int fromIndex) {
        int index = json.indexOf('"', fromIndex);
        assertTrue(index >= 0, "JSON 里找不到字符串字面量；实际：" + json);
        index++;
        StringBuilder value = new StringBuilder();
        while (index < json.length()) {
            char current = json.charAt(index);
            if (current == '\\') {
                char next = json.charAt(index + 1);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(
                                json.substring(index + 2, index + 6), 16));
                        index += 4;
                    }
                    default -> throw new AssertionError("意外的转义：\\" + next
                            + "（位置 " + index + "）；实际：" + json);
                }
                index += 2;
            } else if (current == '"') {
                return value.toString();
            } else {
                value.append(current);
                index++;
            }
        }
        throw new AssertionError("字符串字面量没有收尾；实际：" + json);
    }

    @Test
    void previewIsTheFullText_notTruncated() throws Exception {
        // 5000 字的正文：任何截断 / 省略号 / 摘要都会让下面的长度与内容断言失败
        String longBody = "情绪记录原文".repeat(1000);
        assertEquals(6000, longBody.length(), "测试数据本身要是 6000 字");
        stub = CountingStub.serving(GOOD_BODY);
        RecordingGate gate = new RecordingGate(new ArrayList<>(), true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);

        List<AiMessage> messages = List.of(message("system", "提示词"), message("user", longBody));
        String promptText = joinContents(messages);

        session.complete(PromptRole.COUNSELOR, messages);

        int confirmedLength = gate.lastSeen().promptText().length();
        assertEquals(promptText.length(), confirmedLength,
                "预览长度必须是完整长度（提示词 + 5000 字正文），实际 " + confirmedLength);
        assertTrue(confirmedLength > 6000, "预览长度应当严格大于 6000，实际 " + confirmedLength);

        String wire = stub.lastBody();
        assertTrue(wire.contains(longBody),
                "5000 字的正文必须完整出现在实际请求体里（不截断、不脱敏）");
        // 从请求体里取出第一条消息的正文，长度与内容都必须与原文一致
        JsonArray sent = JsonParser.parseString(wire).getAsJsonObject().getAsJsonArray("messages");
        String sentContent = sent.get(1).getAsJsonObject().get("content").getAsString();
        assertEquals(longBody.length(), sentContent.length(),
                "实发正文长度必须等于原文长度（占位符/省略号会让这里对不上）");
        assertEquals(longBody, sentContent, "实发正文必须与原文逐字节一致");
        assertFalse(wire.contains("…"), "实发内容里不得出现省略号");
        assertFalse(wire.contains("***"), "实发内容里不得出现脱敏占位符");
    }

    @Test
    void previewSurvivesJsonSpecialCharacters_byteForByte() throws Exception {
        // 引号 / 反斜杠 / 换行 / 制表符 / 全角竖线都是用户真的会写进情绪记录的东西。
        // 预览若被「顺手清理」，用户确认过的内容就不再等于实发内容。
        // 这一条打在<b>原始线报</b>上：手工把 wire 里的字符串字面量还原回来，必须逐字符等于原文。
        String tricky = "他说“别撑了”\n\t反斜杠\\ 与换行\n情绪：疲惫 ｜ 强度：3";
        stub = CountingStub.serving(GOOD_BODY);
        RecordingGate gate = new RecordingGate(new ArrayList<>(), true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);
        List<AiMessage> messages = List.of(message("user", tricky));

        session.complete(PromptRole.COUNSELOR, messages);

        assertEquals(tricky, gate.lastSeen().promptText(), "gate 必须看到逐字的原文");
        assertEquals(tricky, session.lastSentPreview(), "lastSentPreview 必须逐字一致");

        String wire = stub.lastBody();
        // 从 messages 数组里那条 user 消息的 content 的<b>值</b>开始，手工还原。
        // 注意不能用 indexOf("content")：那命中的是键名结束处的那个引号，会把整个键读成值。
        int valueAt = wire.indexOf(':', wire.indexOf("\"content\"", wire.indexOf("\"user\"")));
        assertTrue(valueAt > 0, "请求体里找不到 user 消息的 content；实际：" + wire);
        String onTheWire = jsonStringValueAt(wire, valueAt);
        assertEquals(tricky.length(), onTheWire.length(),
                "实发正文还原后的长度必须与原文相同；实际：" + onTheWire);
        assertEquals(tricky, onTheWire,
                "实发正文必须与原文逐字符相同（字符串相等即 UTF-8 字节序列相等）");

        // 反证：还原器不是恒等函数——线报里写的确实是转义形态
        assertTrue(wire.contains("\\n"), "换行在线报里必须写成两个字符 \\n；实际：" + wire);
        assertTrue(wire.contains("\\\\"), "反斜杠在线报里必须写成 \\\\；实际：" + wire);
        // 解析器给出的结果也必须与原文一致（不给「解析器悄悄规范化」留缝）
        JsonArray sent = JsonParser.parseString(wire).getAsJsonObject().getAsJsonArray("messages");
        assertEquals(tricky, sent.get(0).getAsJsonObject().get("content").getAsString(),
                "解析回来的实发正文必须与原文逐字一致");
    }

    @Test
    void preview_aloneSendsNothing_andMatchesWhatIsLaterSent() throws Exception {
        // 计划 3 的界面动作顺序是「先只做预览，用户点头之后才发送」。
        // preview() 必须真的是「什么都不做」，而且它与发送路径必须算出同一份文本。
        stub = CountingStub.serving(GOOD_BODY);
        RecordingGate gate = new RecordingGate(new ArrayList<>(), true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);
        List<AiMessage> messages = List.of(message("user", "先给我看一眼原文"));

        PendingRequest shown = session.preview(PromptRole.COUNSELOR, messages);

        assertEquals(0, stub.hits(), "preview 绝不允许发出任何请求");
        assertEquals(0, gate.calls(), "preview 不得咨询确认门（用户还没做决定）");
        assertEquals("", session.lastSentPreview(), "preview 不是一次「已发送」");
        assertEquals(joinContents(messages), shown.promptText(), "预览文本必须是完整原文");

        // 然后用户确认 → 真正发送：gate 看到的必须与刚才预览的逐字相同
        session.complete(PromptRole.COUNSELOR, messages);
        assertEquals(shown.promptText(), gate.lastSeen().promptText(),
                "预览与真正交给确认门的文本必须一致（两处各算各的就会在这里分开）");
        assertEquals(shown.messages(), gate.lastSeen().messages(),
                "消息列表也必须一致，不能只有文本凑巧相同");
        assertEquals(1, stub.hits());
    }

    @Test
    void promptTextOfMultipleMessages_usesTheNewlineJoinContract() throws Exception {
        // 契约：promptText = 各条消息正文用 "\n" 连接。计划 2 Task 9 的 EmotionAnalysis.build
        // 与计划 3 的界面都按这条契约理解预览文本，所以它必须在单条与多条两种情形下都成立。
        stub = CountingStub.serving(GOOD_BODY);
        RecordingGate gate = new RecordingGate(new ArrayList<>(), true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);
        List<AiMessage> three = List.of(
                message("system", "提示词第一段"),
                message("user", "记录 A"),
                message("user", "记录 B"));

        session.complete(PromptRole.COUNSELOR, three);

        String seen = gate.lastSeen().promptText();
        assertEquals("提示词第一段\n记录 A\n记录 B", seen, "三条消息的完整文本");
        assertEquals(2, seen.chars().filter(c -> c == '\n').count(),
                "两条消息之间恰好一个换行，不多不少");
        // 每条正文都必须逐字出现在实发请求体里（换行本身在 JSON 里转义成 \n，正文不转义）
        for (AiMessage one : three) {
            assertTrue(stub.lastBody().contains(one.content()),
                    "正文「" + one.content() + "」必须逐字出现在实发请求体里");
        }
        assertEquals(joinContents(three), session.lastSentPreview());
    }

    @Test
    void confirmed_recordsLastSentPreview() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        ConfirmingAiSession session = session(CountingGate.always(true));

        assertEquals("", session.lastSentPreview(), "发送前没有「上次发送」");

        List<AiMessage> first = List.of(message("user", "第一份原文"));
        session.complete(PromptRole.PLANNER, first);
        assertEquals(joinContents(first), session.lastSentPreview(), "第一次发送后记录第一份文本");

        List<AiMessage> second = List.of(message("user", "第二份原文"));
        session.complete(PromptRole.PLANNER, second);
        assertEquals(joinContents(second), session.lastSentPreview(),
                "第二次发送后必须换成第二份文本（不能停在第一份）");
        assertNotEquals(joinContents(first), session.lastSentPreview(),
                "lastSentPreview 必须是「上次真正发出的」，不是「历史上某一次」");
    }

    // ------------------------------------------------------------------
    // 4. 逐次确认：两次发送要两次确认，第二次未确认不得复用第一次的确认
    // ------------------------------------------------------------------

    @Test
    void confirmed_requestCounterMatchesGateCallCounter_oneEach() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        CountingGate gate = CountingGate.always(true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);

        session.complete(PromptRole.PLANNER, List.of(message("user", "第一次")));

        assertEquals(1, gate.calls(), "一次发送恰好咨询 gate 一次");
        assertEquals(1, stub.hits(), "一次发送恰好发一次请求");
    }

    @Test
    void secondSendRequiresItsOwnConfirmation_noReuseOfTheFirst() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        CountingGate gate = CountingGate.rejectingSecond();
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);

        // 第一次：确认 → 真的发出去
        session.complete(PromptRole.PLANNER, List.of(message("user", "第一份原文")));
        assertEquals(1, gate.calls(), "第一次发送咨询 gate 一次");
        assertEquals(1, stub.hits(), "第一次确认后必须真的发一次");

        // 第二次：同一个会话、同一个 gate，但这次不确认 → 不得复用第一次的确认
        List<AiMessage> second = List.of(message("user", "第二份更私密的原文"));
        assertThrows(IllegalStateException.class,
                () -> session.complete(PromptRole.COUNSELOR, second));

        assertEquals(2, gate.calls(),
                "第二次发送必须重新咨询 gate（复用第一次的确认会让这里停在 1）");
        assertEquals(1, stub.hits(),
                "第二次未确认时请求数必须仍是 1（变成 2 就是复用了第一次的确认）");
        assertEquals(joinContents(List.of(message("user", "第一份原文"))), session.lastSentPreview(),
                "被拒绝的那次不改写 lastSentPreview");
        assertNotEquals(joinContents(second), session.lastSentPreview(),
                "被拒绝的原文绝不能出现在「已发送」里");
    }

    @Test
    void gateIsConsultedOncePerSend_notTwiceNotZeroTimes() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        CountingGate gate = CountingGate.always(true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);

        session.complete(PromptRole.PLANNER, List.of(message("user", "一")));
        session.complete(PromptRole.PLANNER, List.of(message("user", "二")));

        assertEquals(2, gate.calls(), "两次发送 = 恰好两次确认");
        assertEquals(2, stub.hits(), "两次发送 = 恰好两次请求");
    }

    // ------------------------------------------------------------------
    // 5. 流式同样要过确认门
    // ------------------------------------------------------------------

    @Test
    void stream_alsoRequiresConfirmation() throws Exception {
        stub = CountingStub.streaming(sse(delta("你"), delta("好"), done()));
        ConfirmingAiSession session = session(CountingGate.always(false));
        RecordingListener listener = new RecordingListener();

        assertThrows(IllegalStateException.class,
                () -> session.stream(PromptRole.COUNSELOR, List.of(message("user", "情绪记录")),
                        listener));

        assertEquals(0, stub.hits(), "流式未确认时也不得发请求");
        assertEquals(List.of(), listener.deltas, "未确认时一个增量都不能有");
        assertEquals("", listener.completedWith, "未确认时不得回调 onComplete");
        assertEquals("", session.lastSentPreview(), "未确认时不记录已发送");
    }

    @Test
    void stream_whenConfirmed_sendsAndRecordsPreview() throws Exception {
        stub = CountingStub.streaming(sse(delta("你"), delta("好"), done()));
        RecordingGate gate = new RecordingGate(new ArrayList<>(), true);
        ConfirmingAiSession session = new ConfirmingAiSession(client(), gate);
        RecordingListener listener = new RecordingListener();
        List<AiMessage> messages = List.of(message("user", "流式也要预览"));

        session.stream(PromptRole.COUNSELOR, messages, listener);

        assertEquals(1, stub.hits(), "确认之后流式必须真的发一次");
        assertTrue(JsonParser.parseString(stub.lastBody()).getAsJsonObject().get("stream")
                        .getAsBoolean(),
                "流式请求体里的 stream 必须为 true");
        assertEquals(joinContents(messages), gate.lastSeen().promptText(),
                "流式同样必须先把完整文本交给 gate");
        assertEquals(joinContents(messages), session.lastSentPreview());
        assertEquals(List.of("你", "好"), listener.deltas, "增量必须按顺序到齐");
        assertEquals("你好", listener.completedWith);
    }

    // ------------------------------------------------------------------
    // 6. 形态断言：ConfirmingAiSession 不得暴露绕过确认门的路径
    // ------------------------------------------------------------------

    @Test
    void confirmingSession_hasNoConstructorWithoutAGate() {
        for (Constructor<?> constructor : ConfirmingAiSession.class.getDeclaredConstructors()) {
            if (!Modifier.isPublic(constructor.getModifiers())) {
                continue;
            }
            List<Class<?>> parameters = Arrays.asList(constructor.getParameterTypes());
            assertTrue(parameters.contains(ConsentGate.class),
                    "每一个公开构造器都必须要求一个 ConsentGate（否则可以绕过确认门建会话）："
                            + constructor);
        }
    }

    @Test
    void confirmingSession_exposesNoMethodThatTakesAnAiRequest() {
        for (Method method : ConfirmingAiSession.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                assertNotEquals(AiRequest.class, parameter,
                        "公开方法不得直接收 AiRequest（那是「不经过确认门就发出去」的形状）：" + method);
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("raw") || name.contains("bypass") || name.contains("unsafe")
                            || name.contains("force"),
                    "公开方法名不得暗示绕过确认：" + method);
        }
    }

    /**
     * 拒绝必须是 {@link IllegalStateException}，<b>不能</b>混进 {@link AiFailure}。
     *
     * <p>两件事对用户的意义正好相反：{@code AiFailure} 意味着「真的发过一次但没成功，可以重试」，
     * 而「用户没同意」意味着「一个字节都没发，重试等于偷偷替他做主」。混在一起时界面无法区分。
     */
    @Test
    void refusedSend_isIllegalStateException_notAnAiFailure() throws Exception {
        stub = CountingStub.serving(GOOD_BODY);
        ConfirmingAiSession session = session(CountingGate.always(false));

        assertThrows(IllegalStateException.class,
                () -> session.complete(PromptRole.COUNSELOR, List.of(message("user", "私密原文"))));
        assertThrows(IllegalStateException.class,
                () -> session.stream(PromptRole.COUNSELOR, List.of(message("user", "私密原文")),
                        new RecordingListener()));

        // 反证：确认之后失败才是 AiFailure（这里用一个 401 的桩把它逼出来）
        stub.stop();
        stub = CountingStub.status(401, "{\"error\":{\"message\":\"no\"}}");
        ConfirmingAiSession approved = new ConfirmingAiSession(client(), CountingGate.always(true));
        assertThrows(AiFailure.class,
                () -> approved.complete(PromptRole.COUNSELOR, List.of(message("user", "原文"))));
    }

    @Test
    void pendingRequest_isAnImmutableValueSnapshot() {
        List<AiMessage> mutable = new ArrayList<>();
        mutable.add(message("user", "原文"));
        PendingRequest request = PendingRequest.of(PromptRole.COUNSELOR, mutable, "完整文本");

        mutable.add(message("user", "偷偷加上的一条"));

        assertEquals(1, request.messages().size(), "messages 必须是值快照，不能随后被外部改动");
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(message("user", "再塞一条")),
                "messages 必须是不可变列表（否则用户确认过的东西还能被换掉）");
        assertEquals("完整文本", request.promptText());
        assertEquals("生活与心理顾问", request.role());
        assertThrows(IllegalArgumentException.class,
                () -> PendingRequest.of(PromptRole.COUNSELOR, List.of(), "x"),
                "没有消息的待发送请求不是一次请求");
        assertThrows(IllegalArgumentException.class,
                () -> PendingRequest.of(PromptRole.COUNSELOR, List.of(message("user", "x")), ""),
                "空的 promptText 会让「我确认过了」变成一句空话");
    }

    // ------------------------------------------------------------------
    // 7. 架构约束：ai 包在编译期仍拿不到写文件 / 起进程的能力
    // ------------------------------------------------------------------

    @Test
    void aiPackageBytecode_stillHasNoFileWriteOrProcessExecutionReferences() throws Exception {
        Path aiClasses = mainClassesDirectory().resolve("com/xiaoyiyuan/study/ai");
        assertTrue(Files.isDirectory(aiClasses), "找不到 ai 包的 class 目录：" + aiClasses);
        List<Path> classFiles;
        try (Stream<Path> entries = Files.list(aiClasses)) {
            classFiles = entries.filter(entry -> entry.getFileName().toString().endsWith(".class"))
                    .toList();
        }
        Set<String> names = classFiles.stream().map(entry -> entry.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        // 扫描面必须包含本次新增的类与内部类，否则这条护栏对新代码是空转的
        assertTrue(names.contains("ConfirmingAiSession.class"),
                "必须扫到本次新增的 ConfirmingAiSession.class；实际：" + names);
        assertTrue(names.contains("PendingRequest.class"),
                "必须扫到本次新增的 PendingRequest.class；实际：" + names);
        assertTrue(names.contains("ConsentGate.class"),
                "必须扫到本次新增的 ConsentGate.class；实际：" + names);
        assertTrue(classFiles.size() >= 12,
                "ai 包的 class 个数不对（扫不到东西时不能算通过）：" + names);

        Set<String> offenders = new LinkedHashSet<>();
        int scannedConstants = 0;
        for (Path classFile : classFiles) {
            byte[] bytes = Files.readAllBytes(classFile);
            assertTrue(bytes.length > 8 && (bytes[0] & 0xFF) == 0xCA && (bytes[1] & 0xFF) == 0xFE,
                    "不是 class 文件（魔数不符）：" + classFile);
            for (String constant : constantPoolUtf8(bytes)) {
                scannedConstants++;
                String hit = forbiddenReference(constant);
                if (hit != null) {
                    offenders.add(classFile.getFileName() + " → " + hit);
                }
            }
        }
        assertTrue(scannedConstants > 100,
                "常量池里一条字符串都没扫到（解析器坏了会变成空转的绿灯）：" + scannedConstants);
        assertTrue(offenders.isEmpty(),
                "ai 包的字节码里不得出现「写文件 / 执行进程」的引用（spec §3.3 / §7.5）；命中："
                        + offenders);

        // 阳性对照：同一个判定函数必须能认出真的违禁引用（否则「一个都没命中」毫无意义）
        // 命中时返回的是被命中的那一段，不是整条常量，所以这里只断言「命中了」
        assertNotEquals(null, forbiddenReference("java/nio/file/Files"));
        assertNotEquals(null, forbiddenReference("java/io/FileOutputStream"));
        assertNotEquals(null, forbiddenReference("java/lang/ProcessBuilder"));
        assertNotEquals(null, forbiddenReference("java/lang/Runtime"));
        assertNotEquals(null, forbiddenReference("Ljava/nio/file/Path;"));

        // 阴性对照：Exception 家族不能被误伤（java/lang/RuntimeException 是合法引用）
        assertEquals(null, forbiddenReference("java/lang/RuntimeException"));
        assertEquals(null, forbiddenReference("java/io/IOException"));
        assertEquals(null, forbiddenReference("java/util/List"));

        // 常量池解析器本身也必须真的能读出类引用（阳性对照第二例）
        List<String> clientConstants =
                constantPoolUtf8(Files.readAllBytes(aiClasses.resolve("AiClient.class")));
        assertTrue(clientConstants.contains("java/net/http/HttpClient"),
                "常量池解析器必须能读出真实的类引用（阳性对照失败 = 这条护栏是空转的）");

        // 本次新增的三个类里，明令禁止的东西一个都不能有
        for (String name : List.of("ConfirmingAiSession.class", "PendingRequest.class",
                "ConsentGate.class")) {
            List<String> constants = constantPoolUtf8(Files.readAllBytes(aiClasses.resolve(name)));
            assertTrue(constants.size() > 7, name + " 的常量池太小（解析器没读到东西）："
                    + constants.size());
            for (String constant : constants) {
                assertEquals(null, forbiddenReference(constant),
                        name + " 里出现了违禁引用：" + constant);
            }
        }
    }

    @Test
    void aiPackageDoesNotDependOnStoragePackage() throws Exception {
        Path aiClasses = mainClassesDirectory().resolve("com/xiaoyiyuan/study/ai");
        Set<String> offenders = new LinkedHashSet<>();
        try (Stream<Path> entries = Files.list(aiClasses)) {
            for (Path classFile : entries.filter(e -> e.getFileName().toString().endsWith(".class"))
                    .toList()) {
                for (String constant : constantPoolUtf8(Files.readAllBytes(classFile))) {
                    if (constant.startsWith("com/xiaoyiyuan/study/storage")) {
                        offenders.add(classFile.getFileName() + " → " + constant);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "ai 包不得依赖 storage 包（spec §3.3）；命中：" + offenders);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static AiMessage message(String role, String content) {
        return new AiMessage(role, content);
    }

    /**
     * 测试侧的「完整文本」定义：逐条消息的正文，用 {@code \n} 连接。
     *
     * <p>测试自己算一遍（而不是读实现里的常量），这样实现若改了分隔符，用例必须一起改——
     * 「预览 == 实发」这条性质不能被实现单方面重定义。
     */
    private static String joinContents(List<AiMessage> messages) {
        return messages.stream().map(AiMessage::content).collect(Collectors.joining("\n"));
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    private AiClient client() {
        return new AiClient(new AiConfig(stub.baseUrl(), API_KEY, MODEL, TIMEOUT), httpClient());
    }

    private ConfirmingAiSession session(ConsentGate gate) {
        return new ConfirmingAiSession(client(), gate);
    }

    private static String delta(String text) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}]}\n\n";
    }

    private static String done() {
        return "data: [DONE]\n\n";
    }

    private static String sse(String... events) {
        return String.join("", events);
    }

    // ------------------------------------------------------------------
    // 常量池扫描（与 AiClientTest 的护栏同一套判定；这里独立实现，避免两处一起写错）
    // ------------------------------------------------------------------

    /** 生产 class 目录：由 {@link AiClient} 自己的 CodeSource 决定，不依赖工作目录。 */
    private static Path mainClassesDirectory() {
        URL location = AiClient.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(URI.create(location.toString()));
    }

    private static List<String> constantPoolUtf8(byte[] classBytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            assertEquals(0xCAFEBABE, in.readInt(), "class 文件的魔数不符");
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int constantPoolCount = in.readUnsignedShort();
            String[] pool = new String[constantPoolCount];
            List<String> utf8 = new ArrayList<>();
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> {
                        int length = in.readUnsignedShort();
                        byte[] bytes = new byte[length];
                        in.readFully(bytes);
                        String value = new String(bytes, StandardCharsets.UTF_8);
                        pool[index] = value;
                        utf8.add(value);
                    }
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        index++;
                    }
                    default -> throw new IOException("未知的常量池 tag：" + tag);
                }
            }
            return utf8;
        }
    }

    /**
     * 常量里是否含违禁引用；命中时返回被命中的那个名字（否则 {@code null}）。
     *
     * <p>与 {@code AiClientTest} 的判定同口径：前缀匹配取类名常量与描述符，精确匹配取
     * {@code Runtime} / {@code ProcessBuilder}（{@code RuntimeException} 不算）。
     */
    private static String forbiddenReference(String constant) {
        for (String prefix : List.of("java/nio/file/", "java/io/File")) {
            if (constant.startsWith(prefix)
                    || constant.contains("L" + prefix)
                    || constant.contains("[" + prefix)) {
                return prefix;
            }
        }
        for (String exact : List.of("java/lang/Runtime", "java/lang/ProcessBuilder",
                "java/lang/Process")) {
            if (constant.equals(exact)
                    || constant.contains("L" + exact + ";")
                    || constant.contains(")" + exact)) {
                return exact;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 记录「gate 被咨询了几次」并可切换「这一次是否同意」的门。 */
    private static final class CountingGate implements ConsentGate {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger rejectFrom;
        private final AtomicReference<PendingRequest> lastSeen = new AtomicReference<>();

        private CountingGate(int rejectFrom) {
            this.rejectFrom = new AtomicInteger(rejectFrom);
        }

        static CountingGate always(boolean approve) {
            return new CountingGate(approve ? Integer.MAX_VALUE : 1);
        }

        /** 第一次同意、第二次不同意：用来钉住「不得复用上一次的确认」。 */
        static CountingGate rejectingSecond() {
            return new CountingGate(2);
        }

        @Override
        public boolean confirm(PendingRequest request) {
            lastSeen.set(request);
            int call = calls.incrementAndGet();
            return call < rejectFrom.get();
        }

        int calls() {
            return calls.get();
        }

        PendingRequest lastSeen() {
            return lastSeen.get();
        }
    }

    /** 记录顺序与最后一次请求的门（用来钉住「确认早于任何网络动作」）。 */
    private static final class RecordingGate implements ConsentGate {

        private final List<String> log;
        private final boolean approve;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<PendingRequest> lastSeen = new AtomicReference<>();

        RecordingGate(List<String> log, boolean approve) {
            this.log = log;
            this.approve = approve;
        }

        /** 与桩共用同一本日志的门：{@code confirm} 会被记成 {@code "confirm"}。 */
        static RecordingGate logging(List<String> log, boolean approve) {
            return new RecordingGate(log, approve);
        }

        @Override
        public boolean confirm(PendingRequest request) {
            lastSeen.set(request);
            calls.incrementAndGet();
            if (log != null) {
                log.add("confirm");
            }
            return approve;
        }

        int calls() {
            return calls.get();
        }

        PendingRequest lastSeen() {
            return lastSeen.get();
        }
    }

    /** 流式收取方：把增量按顺序记下来。 */
    private static final class RecordingListener implements AiStreamListener {

        private final List<String> deltas = Collections.synchronizedList(new ArrayList<>());
        private volatile String completedWith = "";

        @Override
        public void onDelta(String text) {
            deltas.add(text);
        }

        @Override
        public void onComplete(String fullText) {
            completedWith = fullText;
        }
    }

    /**
     * 计数桩：{@code hits()} 就是「桩上收到了几次请求」——这是「有没有真的发出去」的物理证据。
     *
     * <p>判定「网络动作」时用 {@code hits} 而不是 {@code bodies.size()}：请求体要到 handler 里
     * 才读得到，而「连上了、发出去了」这件事在 handler 被调用时就已经成立。
     */
    private static final class CountingStub {

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, List<String>> headers = Collections.synchronizedMap(
                new LinkedHashMap<>());
        private final AtomicInteger hits = new AtomicInteger();

        /**
         * 与确认门共享的事件日志（默认是 {@code null}）：handler 一被调用就追加 {@code "network"}。
         *
         * <p>它给的是<b>顺序</b>证据：日志里 {@code network} 排在 {@code confirm} 之后，
         * 就说明第一次 HTTP 请求确实发生在用户点头之后。
         */
        private List<String> sharedLog;

        CountingStub shareLogWith(List<String> log) {
            this.sharedLog = log;
            return this;
        }

        private CountingStub(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        /** 非流式：回一个形状正确的整包 JSON。 */
        static CountingStub serving(String body) throws IOException {
            return status(200, body);
        }

        /** 非流式：回指定状态码与响应体（用来把「已确认但请求没成」逼成 AiFailure）。 */
        static CountingStub status(int status, String body) throws IOException {
            return start(exchange -> {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(status, payload.length);
                    try (java.io.OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                } catch (IOException clientGone) {
                    // 客户端先走时写不回去是预期内的
                }
            });
        }

        /** 流式：把脚本给出的 SSE 事件按 {@code text/event-stream} 发出去。 */
        static CountingStub streaming(String script) throws IOException {
            return start(exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (java.io.OutputStream out = exchange.getResponseBody()) {
                    out.write(script.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException clientGone) {
                    // 同上
                }
            });
        }

        private static CountingStub start(HttpHandler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountingStub stub = new CountingStub(server, executor);
            server.createContext("/", exchange -> {
                stub.hits.incrementAndGet();
                if (stub.sharedLog != null) {
                    stub.sharedLog.add("network");
                }
                try (InputStream in = exchange.getRequestBody()) {
                    stub.bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                stub.paths.add(exchange.getRequestURI().getPath());
                for (Map.Entry<String, List<String>> entry
                        : new Headers(exchange.getRequestHeaders()).entrySet()) {
                    stub.headers.put(entry.getKey(), entry.getValue());
                }
                handler.handle(exchange);
            });
            server.setExecutor(executor);
            server.start();
            return stub;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** 桩上收到的请求数（＝「有没有真的发出去」）。 */
        int hits() {
            return hits.get();
        }

        String lastBody() {
            return bodies.get(bodies.size() - 1);
        }

        String lastPath() {
            return paths.get(paths.size() - 1);
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
