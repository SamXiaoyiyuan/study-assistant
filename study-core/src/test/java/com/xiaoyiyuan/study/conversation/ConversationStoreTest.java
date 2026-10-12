package com.xiaoyiyuan.study.conversation;

import com.xiaoyiyuan.study.ai.AiMessage;
import com.xiaoyiyuan.study.storage.NotUtf8Exception;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConversationStore} 的行为测试（计划 2 Task 8）。
 *
 * <p>本类要钉住的性质，每一条都是「换一种实现就会红」的：
 * <ol>
 *   <li><b>往返保真</b>：{@code save → load} 之后 role 与 content <b>逐字符</b>相等
 *       （中文、换行、引号、反斜杠、全角、控制字符、emoji、超长正文）；</li>
 *   <li><b>目录语义</b>：构造器只记路径（不建目录）；目录不存在时 {@code list()} 返回空列表；
 *       <b>由 {@code save} 创建父目录</b>——两件事同时成立（控制器的 pre-flight 裁定）；</li>
 *   <li><b>坏文件不炸整体</b>：{@code list()} 跳过坏文件并把 id 记进 {@link ConversationStore#lastSkippedIds()}，
 *       其余记录照常返回；同一个坏文件走 {@code load()} 则抛带路径的 {@code IOException}（两种入口两种语义）；</li>
 *   <li><b>不静默覆盖</b>：同 id 的 {@code save} 是「更新同一段对话」——覆盖，且目录里仍然只有一个文件；</li>
 *   <li><b>不得越权</b>：本包<b>不在</b> {@code com.xiaoyiyuan.study.ai} 之下（{@code ai} 包的字节码护栏
 *       禁止那里出现 {@code java/nio/file/*}），且 {@code ai} 包不引用本包任何类型。</li>
 * </ol>
 *
 * <p>全程只用 {@code @TempDir}，绝不触碰 {@code %APPDATA%} 或真实数据目录。
 */
class ConversationStoreTest {

    private static final Instant T1 = Instant.parse("2026-10-11T01:30:00Z");
    private static final Instant T2 = Instant.parse("2026-10-11T02:30:00Z");
    private static final Instant T3 = Instant.parse("2026-10-11T03:30:00Z");

    /** 中文正文。 */
    private static final String CN = "今天电路分析实验拖到六点，我不想说话。";

    /** 换行（LF 与 CRLF 混用）与制表符。 */
    private static final String MULTILINE = "第一行\n第二行\r\n第三行\t带制表符";

    /** 各类引号。 */
    private static final String QUOTES = "他说：“这不行”，然后说 'no' 和 \"yes\"";

    /** 反斜杠（Windows 路径与正则）。 */
    private static final String BACKSLASH = "路径 %USERPROFILE%\\study\\学习与生活计划.md，正则 \\d+，末尾单个 \\";

    /** 全角字符（含全角竖线 U+FF5C，与主计划文件同款）。 */
    private static final String FULLWIDTH = "全角：｜（）【】、。；：“”‘’！？　全角空格";

    /** 控制字符、行/段分隔符、emoji（代理对）。刻意用 (char) 拼接，避免源码里的 \\uXXXX 被提前翻译。 */
    private static final String CONTROL_CHARS =
            "NUL(" + ((char) 0) + ") US(" + ((char) 0x1F) + ") LS(" + ((char) 0x2028)
                    + ") PS(" + ((char) 0x2029) + ") emoji(📚✅🧠)";

    /** 超长正文（12 000 个字符）。 */
    private static final String LONG = "长".repeat(12_000);

    private static final String SYSTEM_PROMPT = "你是学习与生活规划师。\n只输出中文，不诊断、不贴标签。";

    private static final List<String> UI_BANNED_WORDS =
            List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持");

    // ------------------------------------------------------------------
    // 往返保真
    // ------------------------------------------------------------------

    @Test
    void saveThenLoad_roundTripsExactly_messageByMessage(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);
        List<AiMessage> messages = List.of(
                new AiMessage("system", SYSTEM_PROMPT),
                new AiMessage("user", CN + "\n" + MULTILINE),
                new AiMessage("assistant", QUOTES + "\n" + BACKSLASH),
                new AiMessage("user", FULLWIDTH),
                new AiMessage("assistant", CONTROL_CHARS),
                new AiMessage("user", LONG));
        Conversation saved = new Conversation("c-1", "需求梳理", T1, messages);

        store.save(saved);
        assertTrue(Files.isRegularFile(root.resolve("c-1.json")),
                "save 之后必须存在 <conversationsDir>/c-1.json，实际目录内容：" + listFileNames(root));

        Conversation loaded = store.load("c-1").orElseThrow(
                () -> new AssertionError("load(\"c-1\") 必须返回记录，实际是 Optional.empty()"));

        assertEquals("c-1", loaded.id(), "id 必须原样往返");
        assertEquals("需求梳理", loaded.title(), "title 必须原样往返");
        assertEquals(T1, loaded.startedAt(), "startedAt 必须原样往返（同一瞬时）");
        assertEquals(6, messages.size(), "本用例的消息条数（断言自身的前提）");
        assertEquals(messages.size(), loaded.messages().size(),
                "消息条数必须一致：期望 " + messages.size() + " 条，实际 " + loaded.messages().size() + " 条");

        for (int i = 0; i < messages.size(); i++) {
            AiMessage expected = messages.get(i);
            AiMessage actual = loaded.messages().get(i);
            String where = "第 " + i + " 条消息";
            assertEquals(expected.role(), actual.role(), where + " 的 role 必须逐字符相等");
            assertContentCharByChar(where + "（role=" + expected.role() + "）", expected.content(), actual.content());
        }

        // record 的 equals 是逐字段深比较：这一条把「id/title/startedAt/整串消息」一次性钉死
        assertEquals(saved, loaded, "save → load 之后整个 Conversation 必须 equals");
    }

    @Test
    void saveThenLoad_isStableAcrossTwoRounds(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);
        Conversation saved = new Conversation("c-2", "两轮", T1,
                List.of(new AiMessage("user", BACKSLASH + "\n" + QUOTES + "\n" + CONTROL_CHARS)));

        store.save(saved);
        byte[] firstBytes = Files.readAllBytes(root.resolve("c-2.json"));
        Conversation once = store.load("c-2").orElseThrow();
        store.save(once);
        byte[] secondBytes = Files.readAllBytes(root.resolve("c-2.json"));

        assertArrayEquals(firstBytes, secondBytes,
                "load → save 的第二轮必须写出与第一轮完全相同的字节（否则就是某一轮做了有损规范化）");
        assertEquals(once, store.load("c-2").orElseThrow(), "第二轮 load 必须与第一轮逐字段相同");
    }

    @Test
    void saveThenLoad_preservesNullTitleAndNullContent(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);
        Conversation saved = new Conversation("c-3", null, T1,
                List.of(new AiMessage("assistant", null), new AiMessage("user", "")));

        store.save(saved);
        Conversation loaded = store.load("c-3").orElseThrow();

        assertNull(loaded.title(), "null 标题必须原样返回 null，不得被替换成 \"\"");
        assertEquals(2, loaded.messages().size(), "两条消息都必须往返（空内容不是「没有消息」）");
        assertNull(loaded.messages().get(0).content(), "null 正文必须原样返回 null");
        assertEquals("", loaded.messages().get(1).content(), "空串正文必须原样返回空串");
        assertEquals(saved, loaded, "null 也要逐字段往返一致");
    }

    @Test
    void saveThenLoad_emptyMessageList_roundTrips(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);
        Conversation saved = new Conversation("c-4", "还没说话", T1, List.of());

        store.save(saved);
        Conversation loaded = store.load("c-4").orElseThrow();

        assertEquals(0, loaded.messages().size(), "空消息列表必须仍是 0 条，不得凭空多出占位消息");
        assertEquals(saved, loaded, "空消息列表也必须逐字段相等");
    }

    // ------------------------------------------------------------------
    // 目录语义（pre-flight 裁定）
    // ------------------------------------------------------------------

    @Test
    void constructor_doesNotCreateDirectory(@TempDir Path dir) {
        Path root = dir.resolve("conversations");

        new ConversationStore(root);

        assertFalse(Files.exists(root),
                "构造器只记路径，不得创建目录（裁定 4）；实际存在：" + listFileNames(root));
    }

    @Test
    void save_createsMissingDirectory_twoLevelsDeep(@TempDir Path dir) throws IOException {
        // 两层缺失：<tmp>/a/b/conversations——计划 1 的同款教训
        Path root = dir.resolve("a").resolve("b").resolve("conversations");
        assertFalse(Files.exists(root), "前置条件：目录本来不存在");
        ConversationStore store = new ConversationStore(root);

        store.save(new Conversation("c-5", "第一次保存", T1, List.of(new AiMessage("user", CN))));

        assertTrue(Files.isDirectory(root), "save 必须把父目录建出来（写入方负责），实际：" + listFileNames(root));
        assertTrue(Files.isRegularFile(root.resolve("c-5.json")), "文件必须真的落盘");
        assertEquals(1, store.list().size(), "刚写下的那条必须能被 list() 看见");
    }

    @Test
    void save_writesUtf8NoBomLf(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);
        String content = "第一行\r\n第二行\n第三行";

        store.save(new Conversation("c-6", "编码", T1, List.of(new AiMessage("user", content))));

        Path file = root.resolve("c-6.json");
        byte[] bytes = Files.readAllBytes(file);
        assertFalse(hasUtf8Bom(bytes), "不得写 BOM（前三个字节不能是 EF BB BF）");
        assertEquals(0, countCr(bytes),
                "文件里不得出现裸 CR 字节：内容里的 \\r\\n 必须以转义 \\r\\n（反斜杠 + r/n 两个字符）落盘");
        assertEquals('\n', (char) bytes[bytes.length - 1], "文件必须以单个 LF 结尾");

        String text = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(text.contains("第一行"),
                "中文必须以 UTF-8 原文落盘，不得被转义成 \\uXXXX；实际文件前 200 字符：" + preview(text));
        assertTrue(text.contains("\\r\\n"),
                "内容里的 CR-LF 必须以转义形式 \\r\\n 出现在 JSON 文本里；实际：" + preview(text));
        assertEquals(content, store.load("c-6").orElseThrow().messages().get(0).content(),
                "转义与否不影响往返：读回来的正文必须与写下去的一模一样");
    }

    @Test
    void save_twiceWithSameId_overwrites_andKeepsExactlyOneFile(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);

        store.save(new Conversation("c-7", "第一版", T1, List.of(new AiMessage("user", "第一次"))));
        store.save(new Conversation("c-7", "第二版", T1,
                List.of(new AiMessage("user", "第一次"), new AiMessage("assistant", "第二次"))));

        Conversation loaded = store.load("c-7").orElseThrow();
        assertEquals("第二版", loaded.title(), "同 id 再 save 是「更新这段对话」：必须覆盖成后写的内容");
        assertEquals(2, loaded.messages().size(), "覆盖后消息条数必须是后写的 2 条，实际 " + loaded.messages().size());
        assertEquals("第二次", loaded.messages().get(1).content(), "第二条必须是后写的内容");
        assertEquals(List.of("c-7.json"), listFileNames(root),
                "覆盖不得留下第二份文件（不新建 id、不追加后缀）");

        // 说清楚「覆盖」不等于「静默多发一份」：目录里只有 1 个 .json，list() 也只有 1 条
        assertEquals(1, store.list().size(), "list() 必须只有 1 条");
    }

    @Test
    void save_sameId_isIdempotent_bytesIdentical(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);
        Conversation conversation = new Conversation("c-8", "幂等", T1,
                List.of(new AiMessage("user", CN), new AiMessage("assistant", QUOTES)));

        store.save(conversation);
        byte[] first = Files.readAllBytes(root.resolve("c-8.json"));
        store.save(conversation);
        byte[] second = Files.readAllBytes(root.resolve("c-8.json"));

        assertArrayEquals(first, second, "同一份内容重复 save，字节必须稳定（无时间戳/无随机顺序）");
    }

    @Test
    void save_rejectsContentThatCannotBeUtf8Encoded(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);
        String loneSurrogate = "半个字符：" + ((char) 0xD83D) + "（这是一个孤立的代理项）";

        IOException ex = assertThrows(IOException.class,
                () -> store.save(new Conversation("c-9", "代理项", T1,
                        List.of(new AiMessage("user", loneSurrogate)))),
                "含孤立代理项的内容无法用 UTF-8 表示，必须响亮地拒绝，而不是落盘成 '?'");

        assertTrue(ex.getMessage().contains("c-9.json"), "异常消息必须带文件名，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("代理项"), "异常消息必须说清是哪一类问题，实际：" + ex.getMessage());
        assertFalse(Files.exists(root.resolve("c-9.json")),
                "拒绝必须是「一个字节都不写」，实际目录：" + listFileNames(root));
    }

    @Test
    void save_loneSurrogateInTitleAndRole_isAlsoRejected(@TempDir Path dir) throws IOException {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        String bad = "x" + ((char) 0xDC00) + "y";   // 孤立低代理项

        assertThrows(IOException.class, () -> store.save(new Conversation("t", bad, T1, List.of())),
                "标题里的孤立代理项同样必须拒绝");
        assertThrows(IOException.class,
                () -> store.save(new Conversation("t", "标题", T1, List.of(new AiMessage(bad, "正文")))),
                "role 里的孤立代理项同样必须拒绝");
    }

    // ------------------------------------------------------------------
    // list()
    // ------------------------------------------------------------------

    @Test
    void list_missingDirectory_isEmpty_notThrowing(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);

        List<Conversation> result = store.list();

        assertTrue(result.isEmpty(), "目录不存在时 list() 必须返回空列表，实际 " + result.size() + " 条");
        assertTrue(store.lastSkippedIds().isEmpty(), "目录不存在不是「跳过了坏文件」，不得报告跳过");
        assertFalse(Files.exists(root), "list() 不得顺手创建目录，实际存在：" + listFileNames(root));
    }

    @Test
    void list_emptyDirectory_isEmpty(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);

        assertTrue(store.list().isEmpty(), "空目录（没有 .json）必须返回空列表");
        assertTrue(store.lastSkippedIds().isEmpty(), "空目录不得报告跳过");
    }

    @Test
    void list_isNewestFirst(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);

        // 故意把「最新」的那条直接手写落盘（它的 mtime 最早）——排序只能来自 startedAt，不能来自文件时间
        writeRaw(root.resolve("newest.json"), rawJson("newest", "最新", T3, "[]"));
        store.save(new Conversation("oldest", "最旧", T1, List.of(new AiMessage("user", "old"))));
        store.save(new Conversation("middle", "中间", T2, List.of(new AiMessage("user", "mid"))));

        List<Conversation> result = store.list();

        assertEquals(3, result.size(), "三条记录都必须被 list() 看见，实际 " + result.size());
        assertEquals(List.of("newest", "middle", "oldest"),
                result.stream().map(Conversation::id).toList(),
                "必须按 startedAt 最新在前（与文件系统时间顺序无关）");
        assertEquals(T3, result.get(0).startedAt(), "第一条的 startedAt 必须是最新的那个瞬时");
        assertEquals("old", result.get(2).messages().get(0).content(), "最旧那条的正文必须被正确读出");
        assertTrue(store.lastSkippedIds().isEmpty(), "没有坏文件时不得报告跳过");
    }

    @Test
    void list_sameStartedAt_isOrderedById_deterministically(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        // id 的次序刻意与「文件系统的枚举次序」不一致：NTFS 按名字（大小写不敏感）排，
        // 于是 'a' 排在 'B' 前面；而本类的次序定义是 id 的 String 自然序（大小写敏感），'B' 在前。
        // 这样「只按 startedAt 排、不做 id 兜底」的实现就无法靠 File.list 的顺序蒙对。
        store.save(new Conversation("B", "大写 B", T1, List.of()));
        store.save(new Conversation("a", "小写 a", T1, List.of()));
        store.save(new Conversation("c", "小写 c", T1, List.of()));

        assertEquals(List.of("B", "a", "c"), store.list().stream().map(Conversation::id).toList(),
                "startedAt 相同必须按 id 的 String 自然序升序（否则列表顺序由文件系统决定，界面每次刷新都可能跳）");
        assertEquals(List.of("B", "a", "c"), store.list().stream().map(Conversation::id).toList(),
                "第二次调用必须给出同样的顺序");
    }

    @Test
    void list_skipsCorruptJson_keepsOthers_andReportsId(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("good-1", "好的一", T1, List.of(new AiMessage("user", "一"))));
        writeRaw(root.resolve("broken.json"), "{\"id\": \"broken\", \"title\": \"截断的\"\n");
        store.save(new Conversation("good-2", "好的二", T2, List.of(new AiMessage("user", "二"))));

        List<Conversation> result = store.list();

        assertEquals(List.of("good-2", "good-1"), result.stream().map(Conversation::id).toList(),
                "一个坏文件不得让历史整体失败：两条好记录必须照常返回且顺序正确");
        assertEquals(List.of("broken"), store.lastSkippedIds(),
                "被跳过的文件必须按 id 如实上报（供界面提示），实际 " + store.lastSkippedIds());
    }

    @Test
    void list_skipsNotUtf8File_keepsOthers_andReportsId(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("good", "好的", T1, List.of(new AiMessage("user", "一"))));
        // GBK 的「中文」是 D6 D0 CE C4，不是合法 UTF-8
        Files.write(root.resolve("gbk.json"),
                new byte[] {'{', '"', (byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4, '"', '}'});

        List<Conversation> result = store.list();

        assertEquals(List.of("good"), result.stream().map(Conversation::id).toList(),
                "非法 UTF-8 的文件也必须只跳过它自己，好记录照常返回");
        assertEquals(List.of("gbk"), store.lastSkippedIds(), "非法 UTF-8 的 id 必须上报");
    }

    @Test
    void list_skipsEveryKindOfShapeError_andReportsAllIds(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("ok", "好的", T1, List.of(new AiMessage("user", "一"))));

        // ① 根不是对象 ② 缺 startedAt ③ id 不是字符串 ④ startedAt 不是时刻 ⑤ 空文件 ⑥ 只有空白
        writeRaw(root.resolve("not-object.json"), "[1, 2, 3]\n");
        writeRaw(root.resolve("no-started.json"), rawJsonNoStarted("no-started", "缺字段"));
        writeRaw(root.resolve("id-not-string.json"),
                "{\"id\": 5, \"title\": \"t\", \"startedAt\": \"" + T1 + "\", \"messages\": []}\n");
        writeRaw(root.resolve("bad-time.json"),
                "{\"id\": \"bad-time\", \"title\": \"t\", \"startedAt\": \"前天下午\", \"messages\": []}\n");
        writeRaw(root.resolve("empty.json"), "");
        writeRaw(root.resolve("blank.json"), "  \n\t\n");
        // ⑦ messages 不是数组 ⑧ 消息缺 role ⑨ 消息不是对象 ⑩ 内部 id 与文件名不符
        writeRaw(root.resolve("messages-not-array.json"),
                "{\"id\": \"messages-not-array\", \"title\": \"t\", \"startedAt\": \"" + T1 + "\", \"messages\": {}}\n");
        writeRaw(root.resolve("message-without-role.json"),
                "{\"id\": \"message-without-role\", \"title\": \"t\", \"startedAt\": \"" + T1
                        + "\", \"messages\": [{\"content\": \"没有 role\"}]}\n");
        writeRaw(root.resolve("message-not-object.json"),
                "{\"id\": \"message-not-object\", \"title\": \"t\", \"startedAt\": \"" + T1
                        + "\", \"messages\": [\"裸字符串\"]}\n");
        writeRaw(root.resolve("id-mismatch.json"), rawJson("别的 id", "标题", T1, "[]"));

        List<Conversation> result = store.list();

        assertEquals(List.of("ok"), result.stream().map(Conversation::id).toList(),
                "十种损坏都必须只影响它们自己：唯一的合法记录必须照常返回");
        assertEquals(
                List.of("bad-time", "blank", "empty", "id-mismatch", "id-not-string", "message-not-object",
                        "message-without-role", "messages-not-array", "no-started", "not-object"),
                store.lastSkippedIds(),
                "每个坏文件都必须按 id 上报（升序，便于界面逐条提示），实际 " + store.lastSkippedIds());
    }

    @Test
    void list_ignoresNonConversationFiles(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("real", "真的", T1, List.of()));

        Files.writeString(root.resolve("notes.txt"), "随手记的一行");
        Files.writeString(root.resolve("real.json.bak"), "备份，不该被当成对话");
        Files.createDirectory(root.resolve("sub.json"));
        Files.writeString(root.resolve("sub.json").resolve("inner.json"), "目录里的东西不算");

        List<Conversation> result = store.list();

        assertEquals(List.of("real"), result.stream().map(Conversation::id).toList(),
                "只有「普通文件 + .json 后缀」才算对话记录");
        assertTrue(store.lastSkippedIds().isEmpty(),
                "非 .json 文件与目录不是「坏对话文件」，不得混进跳过列表，实际 " + store.lastSkippedIds());
    }

    @Test
    void list_skipsFileWhoseNameIsNotAUsableId(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("good", "好的", T1, List.of()));
        // 文件名含 ".."：它的 id 连 load() 都会拒绝，list() 不能把它端出去
        writeRaw(root.resolve("a..b.json"), rawJson("a..b", "穿越形状", T2, "[]"));

        List<Conversation> result = store.list();

        assertEquals(List.of("good"), result.stream().map(Conversation::id).toList(),
                "文件名派生出的 id 不合法时，该文件不得进入结果（否则界面拿到一个 load() 会抛的 id）");
        assertEquals(List.of("a..b"), store.lastSkippedIds(), "这类文件必须如实上报为跳过");
        assertThrows(IllegalArgumentException.class, () -> store.load("a..b"),
                "前提复核：load(\"a..b\") 确实会拒绝这个 id");
    }

    @Test
    void list_secondCallReplacesSkippedIds(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("good", "好的", T1, List.of()));
        Path broken = root.resolve("broken.json");
        writeRaw(broken, "{ 这不是 JSON");

        assertEquals(1, store.list().size(), "第一次 list()：好记录仍返回");
        assertEquals(List.of("broken"), store.lastSkippedIds(), "第一次 list() 必须报告 broken");

        Files.delete(broken);
        assertEquals(1, store.list().size(), "删掉坏文件后 list() 只剩 1 条");
        assertTrue(store.lastSkippedIds().isEmpty(),
                "每次 list() 都重新计算跳过列表（上一次的结果不得残留），实际 " + store.lastSkippedIds());
    }

    @Test
    void list_pathIsAFile_notDirectory_throwsWithPath(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        Files.writeString(root, "我本来应该是目录，却被建成了文件");

        ConversationStore store = new ConversationStore(root);
        IOException ex = assertThrows(IOException.class, store::list,
                "「该是目录的位置上是个文件」是明确的配置错误，必须响亮地报出来，而不是假装历史是空的");

        assertTrue(ex.getMessage().contains("conversations"),
                "异常消息必须带上出问题的路径，实际：" + ex.getMessage());
    }

    @Test
    void list_andSkippedIds_areUnmodifiable(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("good", "好的", T1, List.of()));
        writeRaw(root.resolve("broken.json"), "{ 坏");

        List<Conversation> result = store.list();
        List<String> skipped = store.lastSkippedIds();
        assertEquals(1, result.size(), "前置条件：1 条好记录");
        assertEquals(List.of("broken"), skipped, "前置条件：1 条被跳过");

        assertThrows(UnsupportedOperationException.class, () -> result.add(null),
                "list() 的返回值必须是不可变视图（内部状态不得从外部被改）");
        assertThrows(UnsupportedOperationException.class, () -> skipped.add("x"),
                "lastSkippedIds() 的返回值必须是不可变视图");
    }

    // ------------------------------------------------------------------
    // load()
    // ------------------------------------------------------------------

    @Test
    void load_missingId_isEmpty_andDoesNotCreateDirectory(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);

        assertTrue(store.load("nope").isEmpty(), "目录不存在时 load 必须返回 Optional.empty()");
        assertFalse(Files.exists(root), "load 不得创建目录，实际存在：" + listFileNames(root));

        Files.createDirectories(root);
        assertTrue(store.load("nope").isEmpty(), "目录存在但文件不存在时同样返回 Optional.empty()");
        assertTrue(store.load("nope").isEmpty(), "重复调用行为一致");
    }

    @Test
    void load_corruptJson_throwsIoException_withFilePath(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        Path file = root.resolve("broken.json");
        writeRaw(file, "{\"id\": \"broken\", \"title\": \"截断\"\n");

        IOException ex = assertThrows(IOException.class, () -> store.load("broken"),
                "load 遇到损坏 JSON 必须抛 IOException，不得返回空对象或静默忽略");

        assertTrue(ex.getMessage().contains(file.toString()),
                "异常消息必须含完整文件路径，便于用户定位；实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("broken"), "异常消息里要能看出是哪条对话，实际：" + ex.getMessage());
    }

    @Test
    void load_notUtf8_throwsNotUtf8Exception_withPath(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        Files.write(root.resolve("gbk.json"),
                new byte[] {'{', '"', (byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4, '"', '}'});

        NotUtf8Exception ex = assertThrows(NotUtf8Exception.class, () -> store.load("gbk"),
                "非法 UTF-8 必须沿用 TextFileCodec 的拒绝语义（不返回被 U+FFFD 替换过的内容）");

        assertTrue(ex.getMessage().contains("gbk.json"), "异常消息必须带文件名，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("偏移"), "异常消息必须带非法字节偏移，实际：" + ex.getMessage());
    }

    @Test
    void load_jsonWithMismatchedId_throwsWithPathAndBothIds(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        writeRaw(root.resolve("asked.json"), rawJson("stored", "标题", T1, "[]"));

        IOException ex = assertThrows(IOException.class, () -> store.load("asked"),
                "文件里的 id 与文件名不一致时必须可读地拒绝，而不是把文件里的 id 端给界面");

        assertTrue(ex.getMessage().contains("asked.json"), "消息必须含文件路径，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("asked") && ex.getMessage().contains("stored"),
                "消息必须同时说出文件名派生的 id 与文件里写的 id，实际：" + ex.getMessage());
    }

    @Test
    void load_wrongShapeJson_throws_withFilePath(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        writeRaw(root.resolve("shape.json"), "[1, 2, 3]\n");

        IOException ex = assertThrows(IOException.class, () -> store.load("shape"),
                "「是合法 JSON 但不是一段对话」也必须抛 IOException");
        assertTrue(ex.getMessage().contains("shape.json"), "异常消息必须含文件路径，实际：" + ex.getMessage());
    }

    @Test
    void load_pathIsDirectory_throws(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        Files.createDirectory(root.resolve("weird.json"));
        ConversationStore store = new ConversationStore(root);

        IOException ex = assertThrows(IOException.class, () -> store.load("weird"),
                "该是文件的位置上是个目录：必须报错，而不是返回 Optional.empty() 假装没有这条");
        assertTrue(ex.getMessage().contains("weird.json"), "异常消息必须含路径，实际：" + ex.getMessage());
    }

    @Test
    void load_toleratesBom(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("bom", "带 BOM", T1, List.of(new AiMessage("user", CN))));
        Path file = root.resolve("bom.json");
        byte[] plain = Files.readAllBytes(file);
        byte[] withBom = new byte[plain.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(plain, 0, withBom, 3, plain.length);
        Files.write(file, withBom);

        Conversation loaded = store.load("bom").orElseThrow(
                () -> new AssertionError("用户用编辑器另存为带 BOM 的 UTF-8 之后，历史不该整条消失"));

        assertEquals("带 BOM", loaded.title(), "BOM 只是前缀，不影响内容");
        assertEquals(CN, loaded.messages().get(0).content(), "BOM 兼容读取的内容必须逐字符正确");
        assertEquals(1, store.list().size(), "带 BOM 的文件在 list() 里也必须算数，不是坏文件");

        store.save(loaded);
        assertFalse(hasUtf8Bom(Files.readAllBytes(file)),
                "我们自己的写入端仍然严格无 BOM：再存一次就把 BOM 去掉了");
    }

    @Test
    void load_ignoresUnknownFields(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        writeRaw(root.resolve("future.json"),
                "{\n"
                        + "  \"schemaVersion\": 99,\n"
                        + "  \"id\": \"future\",\n"
                        + "  \"title\": \"未来字段\",\n"
                        + "  \"startedAt\": \"" + T1 + "\",\n"
                        + "  \"extra\": {\"a\": [1, 2]},\n"
                        + "  \"messages\": [{\"role\": \"user\", \"content\": \"正文\", \"tokens\": 12}]\n"
                        + "}\n");

        Conversation loaded = store.load("future").orElseThrow(
                () -> new AssertionError("未知字段必须被忽略，而不是让整条历史读不出来"));

        assertEquals("未来字段", loaded.title(), "已知字段照常读出");
        assertEquals(1, loaded.messages().size(), "消息照常读出");
        assertEquals("正文", loaded.messages().get(0).content(), "消息正文照常读出");
        assertEquals(1, store.list().size(), "带未知字段的文件不是坏文件，不得被跳过");
        assertTrue(store.lastSkippedIds().isEmpty(), "不得把未知字段当坏文件上报");
    }

    // ------------------------------------------------------------------
    // id 校验（防目录穿越）
    // ------------------------------------------------------------------

    @Test
    void idWithPathSeparator_isRejected(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));

        // 计划里的原句：save 必须抛 IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
                () -> store.save(new Conversation("../evil", "t", T1, List.of())),
                "save(\"../evil\") 必须抛 IllegalArgumentException");

        for (String bad : List.of("../evil", "..", "a/b", "a\\b", "..\\..\\windows\\system32", "/abs", "C:\\x")) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.save(new Conversation(bad, "t", T1, List.of())),
                    "save 必须拒绝不安全的 id：" + bad);
            assertThrows(IllegalArgumentException.class, () -> store.load(bad),
                    "load 必须拒绝不安全的 id：" + bad);
            assertThrows(IllegalArgumentException.class, () -> store.delete(bad),
                    "delete 必须拒绝不安全的 id：" + bad);
        }
        assertThrows(IllegalArgumentException.class, () -> store.load("a" + ((char) 0) + "b"),
                "id 里不得有 NUL");
    }

    @Test
    void idTraversal_isRejectedBeforeTouchingDisk(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        Path outside = dir.resolve("outside.json");
        Files.writeString(outside, "我是受害者，谁都不许碰我");
        ConversationStore store = new ConversationStore(root);

        assertThrows(IllegalArgumentException.class,
                () -> store.save(new Conversation("../outside", "t", T1, List.of(new AiMessage("user", "改写我")))),
                "向上一级写文件必须被拒绝");
        assertThrows(IllegalArgumentException.class, () -> store.load("../outside"),
                "读上一级文件同样必须被拒绝（即使那个文件真的存在）");
        assertThrows(IllegalArgumentException.class, () -> store.delete("../outside"),
                "删除上一级文件同样必须被拒绝");

        assertEquals("我是受害者，谁都不许碰我", Files.readString(outside), "越权操作必须一个字节都没碰");
        assertEquals(List.of(), Files.list(root).map(p -> p.getFileName().toString()).sorted().toList(),
                "被拒绝的 save 不得在目标目录里留下任何文件");
    }

    @Test
    void idWithDotDot_isRejected_evenWithoutSeparator(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        for (String bad : List.of("a..b", "..a", "a..", "...")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> store.save(new Conversation(bad, "t", T1, List.of())),
                    "含 \"..\" 的 id 一律拒绝（计划的要求），实际放过了：" + bad);
            assertTrue(ex.getMessage().contains(bad), "消息里必须带上被拒的 id，实际：" + ex.getMessage());
        }
    }

    @Test
    void idWithWindowsForbiddenChar_isRejected(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        for (String bad : List.of("a:b", "a*b", "a?b", "a|b", "a\"b", "a<b", "a>b",
                "a" + ((char) 1) + "b", "a\nb")) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.save(new Conversation(bad, "t", T1, List.of())),
                    "Windows 上语义特殊或控制字符的 id 必须拒绝：" + bad.replace("\n", "\\n"));
        }
    }

    @Test
    void id_reservedDeviceName_isRejected(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        for (String bad : List.of("CON", "con", "nul", "COM1", "LPT9", "aux")) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.save(new Conversation(bad, "t", T1, List.of())),
                    "Windows 保留设备名（base name 大小写不敏感）必须拒绝：" + bad);
        }
        // 反例：带后缀的普通名字不受影响，别把 CON-cept 这类 id 一起误伤
        assertDoesNotThrow(() -> store.save(new Conversation("con-cept", "t", T1, List.of())),
                "只有「恰好是保留设备名」才拒绝");
    }

    @Test
    void blankId_isRejected(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        for (String bad : new String[] {"", "   ", "\t"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> store.save(new Conversation(bad, "t", T1, List.of())),
                    "空/纯空白的 id 必须拒绝（它会产生 \".json\" 这种藏起来的文件）");
        }
        assertThrows(NullPointerException.class, () -> store.save(new Conversation(null, "t", T1, List.of())),
                "null id 由 Conversation 的 requireNonNull 挡住");
    }

    @Test
    void id_normalUuid_isAccepted(@TempDir Path dir) throws IOException {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        String uuid = "3f782f3a-2e3f-4f3b-b2b7-e1a6c4cb2134";   // 界面用的就是 UUID.randomUUID().toString()

        store.save(new Conversation(uuid, "UUID", T1, List.of(new AiMessage("user", "hi"))));

        assertTrue(Files.isRegularFile(dir.resolve("conversations").resolve(uuid + ".json")),
                "UUID 形态的 id 必须原样可用");
        assertEquals(uuid, store.load(uuid).orElseThrow().id(), "UUID id 必须能读回来");
    }

    // ------------------------------------------------------------------
    // delete()
    // ------------------------------------------------------------------

    @Test
    void delete_removesFile_missingIsNoOp(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("conversations"));
        ConversationStore store = new ConversationStore(root);
        store.save(new Conversation("del-1", "要删的", T1, List.of(new AiMessage("user", "内容"))));
        store.save(new Conversation("keep", "留着的", T2, List.of()));

        store.delete("del-1");

        assertFalse(Files.exists(root.resolve("del-1.json")), "delete 必须真的把文件删掉");
        assertEquals(List.of("keep"), store.list().stream().map(Conversation::id).toList(),
                "只能删掉指定的那一条");
        assertDoesNotThrow(() -> store.delete("del-1"), "重复删除必须是 no-op，不抛异常");
        assertDoesNotThrow(() -> store.delete("从来没有过"), "删不存在的 id 同样是 no-op");

        ConversationStore fresh = new ConversationStore(dir.resolve("never-created"));
        assertDoesNotThrow(() -> fresh.delete("whatever"), "目录都不存在时 delete 也必须是 no-op");
        assertFalse(Files.exists(dir.resolve("never-created")), "delete 不得创建目录");
    }

    // ------------------------------------------------------------------
    // toMarkdown()
    // ------------------------------------------------------------------

    @Test
    void toMarkdown_hasExpectedSections(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        Conversation conversation = new Conversation("m-1", "需求梳理", T1,
                List.of(new AiMessage("system", "你是规划师。"),
                        new AiMessage("user", "第一行\n第二行"),
                        new AiMessage("assistant", "好的，我来梳理。")));

        String md = store.toMarkdown(conversation);

        assertTrue(md.startsWith("# 需求梳理\n\n"), "必须以一级标题开头（沿用主计划文件风格），实际：" + preview(md));
        assertTrue(md.contains("> 开始时间（UTC）：2026-10-11T01:30:00Z ｜ 共 3 条消息"),
                "引用块里要有时间与条数，实际：" + preview(md));
        assertTrue(md.contains("\n---\n"), "标题区与正文之间要有 --- 分隔（主计划文件风格）");

        int system = md.indexOf("## 系统");
        int user = md.indexOf("## 用户");
        int assistant = md.indexOf("## 助手");
        assertTrue(system >= 0, "system 消息必须有自己的分段，实际：" + preview(md));
        assertTrue(user >= 0, "user 消息必须渲染成 ## 用户，实际：" + preview(md));
        assertTrue(assistant >= 0, "assistant 消息必须渲染成 ## 助手，实际：" + preview(md));
        assertTrue(system < user && user < assistant, "分段的先后必须与消息顺序一致");

        assertTrue(md.contains("## 用户\n\n第一行\n第二行\n"),
                "正文必须逐字在分段标题之下（含换行），实际：" + preview(md));
        assertTrue(md.endsWith("好的，我来梳理。\n"), "必须以最后一条正文 + 一个 LF 结尾，实际：" + preview(md));
    }

    @Test
    void toMarkdown_blankTitle_usesFallback(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));

        assertTrue(store.toMarkdown(new Conversation("m-2", null, T1, List.of())).startsWith("# （未命名对话）\n"),
                "null 标题要有可读的占位，不能渲染成 \"# null\"");
        assertTrue(store.toMarkdown(new Conversation("m-3", "   ", T1, List.of())).startsWith("# （未命名对话）\n"),
                "纯空白标题同样用占位");
        assertTrue(store.toMarkdown(new Conversation("m-4", " 有内容 ", T1, List.of())).startsWith("#  有内容 \n"),
                "有内容的标题必须逐字保留（不做 trim 静默改写）");
    }

    @Test
    void toMarkdown_unknownRole_keepsRoleVerbatim(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        String md = store.toMarkdown(new Conversation("m-5", "工具调用", T1,
                List.of(new AiMessage("tool", "{\"a\":1}"), new AiMessage("weird-role", "x"))));

        assertTrue(md.contains("## tool\n\n{\"a\":1}\n"), "未知 role 必须原样成段，绝不丢消息；实际：" + preview(md));
        assertTrue(md.contains("## weird-role\n\nx\n"), "未知 role 必须逐字保留；实际：" + preview(md));
    }

    @Test
    void toMarkdown_noMessages_stillHasHeader(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));

        String md = store.toMarkdown(new Conversation("m-6", "空对话", T1, List.of()));

        assertTrue(md.startsWith("# 空对话\n\n"), "空对话也要有标题");
        assertTrue(md.contains("｜ 共 0 条消息"), "空对话的条数必须是 0，实际：" + preview(md));
        assertFalse(md.contains("\n## "), "没有消息时不得凭空造出分段，实际：" + preview(md));
    }

    @Test
    void toMarkdown_keepsBannedWordsThatAreMessageContent(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        Conversation conversation = new Conversation("m-7", "复盘", T1,
                List.of(new AiMessage("user", "这周实验失败了两次，感觉落后了"),
                        new AiMessage("assistant", "我注意到你提到「失败」与「落后」。")));

        String md = store.toMarkdown(conversation);
        String frame = md.substring(0, md.indexOf("\n## "));

        for (String banned : UI_BANNED_WORDS) {
            assertFalse(frame.contains(banned),
                    "导出模板自身的文字不得含界面禁用词「" + banned + "」，实际框架文字：" + frame);
        }
        assertTrue(md.contains("这周实验失败了两次，感觉落后了"),
                "用户/AI 自己的原话绝不审查、绝不改写（导出的是记录，不是文案）");
        assertTrue(md.contains("我注意到你提到「失败」与「落后」。"), "助手原话同样逐字保留");
    }

    @Test
    void toMarkdown_multilineContent_isVerbatim(@TempDir Path dir) {
        ConversationStore store = new ConversationStore(dir.resolve("conversations"));
        String content = MULTILINE + "\n" + QUOTES + "\n" + BACKSLASH;

        String md = store.toMarkdown(new Conversation("m-8", "换行", T1, List.of(new AiMessage("user", content))));

        assertTrue(md.contains("## 用户\n\n" + content + "\n"),
                "多行正文必须逐字嵌入（不得转义、不得重排、不得吃掉 \\r），实际：" + preview(md));
    }

    @Test
    void toMarkdown_doesNotTouchDisk(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("conversations");
        ConversationStore store = new ConversationStore(root);

        String md = store.toMarkdown(new Conversation("m-9", "只是渲染", T1,
                List.of(new AiMessage("user", "内容"))));

        assertFalse(md.isEmpty(), "前置条件：确实渲染出了东西");
        assertFalse(Files.exists(root), "toMarkdown 只返回字符串：不得建目录、不得落盘，实际存在：" + listFileNames(root));
    }

    // ------------------------------------------------------------------
    // 架构形态（不得越权）
    // ------------------------------------------------------------------

    @Test
    void conversationClasses_liveOutsideAiPackage() {
        assertEquals("com.xiaoyiyuan.study.conversation", ConversationStore.class.getPackageName(),
                "ConversationStore 必须读写 Path，而 ai 包的字节码护栏禁止那里出现 java/nio/file/*");
        assertEquals("com.xiaoyiyuan.study.conversation", Conversation.class.getPackageName(),
                "本任务的两个类同处一包，界面只 import 一个包");
    }

    @Test
    void aiPackage_neitherReferencesConversationTypesNorJavaNioFile() throws Exception {
        Class<?>[] types = aiPackageClasses();
        List<String> names = new ArrayList<>();
        for (Class<?> type : types) {
            names.add(type.getSimpleName());
        }
        assertTrue(types.length >= 6,
                "ai 包必须至少枚举到 6 个生产类，否则这条护栏会因为「扫不到东西」而假绿；实际只有 " + names);
        assertTrue(names.contains("AiClient"), "枚举必须包含 AiClient，实际 " + names);
        assertTrue(names.contains("AiMessage"), "枚举必须包含 AiMessage，实际 " + names);

        List<String> offenders = new ArrayList<>();
        for (Class<?> type : types) {
            for (Field field : type.getDeclaredFields()) {
                checkNoForbiddenReference(type, "字段 " + field.getName(), field.getGenericType(), offenders);
            }
            for (Method method : type.getDeclaredMethods()) {
                checkNoForbiddenReference(type, "方法 " + method.getName() + " 的返回类型",
                        method.getGenericReturnType(), offenders);
                for (Type parameter : method.getGenericParameterTypes()) {
                    checkNoForbiddenReference(type, "方法 " + method.getName() + " 的参数", parameter, offenders);
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Type parameter : constructor.getGenericParameterTypes()) {
                    checkNoForbiddenReference(type, "构造器参数", parameter, offenders);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "ai 包内不得出现 java.nio.file 类型，也不得引用 conversation 包（ai 不该知道历史怎么存）；违规：" + offenders);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void checkNoForbiddenReference(Class<?> owner, String where, Type candidate, List<String> offenders) {
        String name = candidate.getTypeName();
        if (name.contains("java.nio.file") || name.contains("com.xiaoyiyuan.study.conversation")) {
            offenders.add(owner.getSimpleName() + " 的 " + where + "：" + name);
        }
    }

    /** 逐字符比较两条正文，失败时给到具体下标与码点。 */
    private static void assertContentCharByChar(String where, String expected, String actual) {
        assertNotNull(actual, where + "：正文不得为 null");
        assertEquals(expected.length(), actual.length(),
                where + "：UTF-16 码元数不符——期望 " + expected.length() + "，实际 " + actual.length());
        for (int i = 0; i < expected.length(); i++) {
            char e = expected.charAt(i);
            char a = actual.charAt(i);
            if (e != a) {
                fail(where + "：第 " + i + " 个码元不符——期望 U+" + hex(e) + "（" + describe(e)
                        + "），实际 U+" + hex(a) + "（" + describe(a) + "）");
            }
        }
    }

    private static String hex(char c) {
        return String.format("%04X", (int) c);
    }

    private static String describe(char c) {
        if (c < 0x20 || c == 0x7F) {
            return "控制字符";
        }
        if (c == 0x2028) {
            return "行分隔符";
        }
        if (c == 0x2029) {
            return "段分隔符";
        }
        return "「" + c + "」";
    }

    private static String rawJson(String id, String title, Instant startedAt, String messagesJson) {
        return "{\n  \"id\": " + quote(id) + ",\n  \"title\": " + (title == null ? "null" : quote(title))
                + ",\n  \"startedAt\": \"" + startedAt + "\",\n  \"messages\": " + messagesJson + "\n}\n";
    }

    private static String rawJsonNoStarted(String id, String title) {
        return "{\n  \"id\": " + quote(id) + ",\n  \"title\": " + quote(title) + ",\n  \"messages\": []\n}\n";
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static void writeRaw(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
    }

    private static int countCr(byte[] bytes) {
        int count = 0;
        for (byte b : bytes) {
            if (b == '\r') {
                count++;
            }
        }
        return count;
    }

    /** 给断言失败信息用：列出目录里的条目名（目录不存在时给出说明而不是抛异常）。 */
    private static List<String> listFileNames(Path directory) {
        if (!Files.exists(directory)) {
            return List.of("<目录不存在>");
        }
        try (var entries = Files.list(directory)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of("<列目录失败：" + e.getMessage() + ">");
        }
    }

    private static String preview(String text) {
        String flat = text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…（共 " + flat.length() + " 字符）";
    }

    /**
     * 枚举 {@code com.xiaoyiyuan.study.ai} 包里的生产类。
     *
     * <p>从 classpath 上的包目录逐一加载，刻意不读源码（源码扫描依赖工作目录，
     * 换个启动方式就会静默失效，而失效的表现恰恰是「测试照样绿」）。
     */
    private static Class<?>[] aiPackageClasses() throws IOException, ClassNotFoundException {
        List<Class<?>> found = new ArrayList<>();
        ClassLoader loader = ConversationStoreTest.class.getClassLoader();
        for (URL resource : Collections.list(loader.getResources("com/xiaoyiyuan/study/ai"))) {
            Path directory = Path.of(URI.create(resource.toString()));
            if (!Files.isDirectory(directory)) {
                continue;
            }
            List<Path> entries;
            try (var stream = Files.list(directory)) {
                entries = stream.toList();
            }
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$") || name.endsWith("Test.class")) {
                    continue;
                }
                Class<?> loaded = Class.forName(
                        "com.xiaoyiyuan.study.ai." + name.substring(0, name.length() - ".class".length()),
                        false, loader);
                if (!found.contains(loaded)) {
                    found.add(loaded);
                }
            }
        }
        return found.toArray(new Class<?>[0]);
    }
}
