package com.xiaoyiyuan.study.conversation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.xiaoyiyuan.study.ai.AiMessage;
import com.xiaoyiyuan.study.storage.TextFileCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * 对话历史的存取（计划 2 Task 8）：一段对话一个文件，{@code <conversationsDir>/<id>.json}。
 *
 * <p>默认目录是 {@code %APPDATA%\xiaoyiyuan-study\conversations}（{@code AppPaths.conversationsDir()}）：
 * 历史<b>不进</b> {@code study\} 数据目录，既不污染用户档案，也避免历史被连带分享出去（spec §7.6）。
 *
 * <h2>文件格式</h2>
 * <pre>{@code
 * {
 *   "id": "3f782f3a-2e3f-4f3b-b2b7-e1a6c4cb2134",
 *   "title": "需求梳理",
 *   "startedAt": "2026-10-11T01:30:00Z",
 *   "messages": [
 *     { "role": "user", "content": "帮我把这周的安排捋一遍" }
 *   ]
 * }
 * }</pre>
 * 未知字段一律忽略（旧版本读新文件不炸），未知 role 原样保留；缺字段/类型不对则算坏文件。
 *
 * <h2>三条刻意选定的行为（每条都有测试钉住）</h2>
 * <ol>
 *   <li><b>构造器只记路径；{@code save} 在写前创建父目录。</b>这样「目录还不存在」是一个有意义的状态：
 *       {@link #list()} 返回空列表而不是抛异常。目录创建交给 {@link TextFileCodec#write}
 *       ——它是全项目唯一的写入点，两层缺失的子目录也建得出来（计划 1 的同款教训：写入方负责建父目录）。</li>
 *   <li><b>坏文件只坑它自己。</b>{@link #list()} 跳过读不出来的文件（坏的 JSON、非法 UTF-8、
 *       形状不对、内部 id 与文件名不符），照常返回其余记录，并把跳过的 id 记进
 *       {@link #lastSkippedIds()} 供界面提示——一个坏文件不该让整份历史消失。
 *       同一个文件走 {@link #load(String)} 则抛带路径的 {@code IOException}：这里必须响亮，
 *       因为调用方点名要这一条，静默返回「没有」会让人以为对话被删了。</li>
 *   <li><b>同 id 再 {@code save} 是覆盖（更新这段对话），不是新建、也不是拒绝。</b>
 *       界面在每轮问答后都要把同一个 id 的对话写回去，覆盖正是唯一能表达「这段对话变长了」的语义；
 *       目录里始终只有一份文件。id 是调用方给的，本类不生成、不改写。</li>
 * </ol>
 *
 * <h2>写盘一律复用 storage</h2>
 * 写走 {@link TextFileCodec#write}（UTF-8 无 BOM、不做换行转换、自己建父目录），读走
 * {@link TextFileCodec#read}（非法 UTF-8 一律拒绝，绝不返回被 U+FFFD 替换过的内容）——
 * 本类不发明第二条写入路径。刻意<b>不</b>用 {@code FileStore}：它的「外部改动基线」是给
 * 用户拿编辑器一起改的档案用的，用在对话历史上会让「同一 id 的第二次保存」被判成
 * {@code EXTERNAL_CHANGE_DETECTED} 而<b>静默不写</b>（除非一路 force），那正是这里最不想要的行为。
 *
 * <p>本类不在 {@code com.xiaoyiyuan.study.ai} 包：它要读写 {@link Path}，而 {@code ai} 包的字节码护栏
 * 禁止那里出现 {@code java/nio/file/*}（见 {@code AiClientTest}）。AI 也不该知道历史存在哪里。
 */
public final class ConversationStore {

    /** 对话文件后缀。只有「普通文件 + 这个后缀」才算一条对话记录。 */
    private static final String SUFFIX = ".json";

    /**
     * JSON 读写器：缩进换行、显式 null、不动 HTML 转义。
     *
     * <p>{@code serializeNulls} 让 {@code "title": null} 明确写出来；{@code disableHtmlEscaping}
     * 让中文与 {@code < > &} 原样落盘，用户打开文件能直接看懂（Gson 默认会把它们写成 {@code \u003c}）。
     * 无论转义与否，往返都逐字符一致——转义只影响可读性。
     */
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    /** id 里不允许出现的字符：路径分隔符与 Windows 上语义特殊的字符（{@code a:b} 是 NTFS 数据流）。 */
    private static final String FORBIDDEN_ID_CHARS = "/\\:*?\"<>|";

    /**
     * Windows 保留设备名（大小写不敏感）。
     *
     * <p>{@code CON.json} 这类名字在 Windows 上会被解释成设备而不是文件，写下去要么失败、
     * 要么落到谁也找不到的地方。宁可在这里拒绝，也不要让用户在历史列表里发现一条打不开的对话。
     */
    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Path conversationsDir;

    /** 最近一次 {@link #list()} 跳过的 id（不可变；只由 list() 更新，别处不碰）。 */
    private List<String> lastSkippedIds = List.of();

    /**
     * 只记住路径，<b>不创建目录</b>（计划 2 pre-flight 裁定 4）。
     *
     * <p>「目录还没有」是全新安装的常态，也是一个必须能被表达的状态：构造完直接 {@link #list()}
     * 应当得到空列表。所以目录的创建发生在 {@link #save}（写入方负责）。
     */
    public ConversationStore(Path conversationsDir) {
        this.conversationsDir = Objects.requireNonNull(conversationsDir, "conversationsDir");
    }

    /**
     * 把整段对话写到 {@code <conversationsDir>/<id>.json}（同 id 覆盖）。
     *
     * <p>先校验 id 与可编码性，再写盘：两个校验都失败在「一个字节都没写」之前，
     * 绝不留半个文件。父目录由 {@link TextFileCodec#write} 创建。
     *
     * @throws IllegalArgumentException id 不能安全地当文件名（含 {@code ..}、路径分隔符、控制字符……）
     * @throws IOException              id 或正文含孤立代理项（UTF-8 表示不出来，落盘会静默变成 {@code ?}）、
     *                                  或写盘本身失败
     */
    public void save(Conversation conversation) throws IOException {
        Objects.requireNonNull(conversation, "conversation");
        Path file = fileFor(conversation.id());
        requireEncodable(conversation, file);
        TextFileCodec.write(file, toJson(conversation));
    }

    /**
     * 读回一条对话。
     *
     * <p>文件不存在（含目录不存在）→ {@link Optional#empty()}；存在但读不出来 → 抛异常，
     * 消息里带文件路径。绝不返回「空对话」来冒充读成功。
     *
     * @throws IllegalArgumentException id 不安全
     * @throws IOException              非法 UTF-8（{@code NotUtf8Exception}）、坏 JSON、形状不对、
     *                                  内部 id 与文件名不符，或该位置上不是普通文件
     */
    public Optional<Conversation> load(String id) throws IOException {
        Path file = fileFor(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("对话记录的位置上不是一个普通文件（可能是目录）：" + file);
        }
        return Optional.of(readConversation(file, id));
    }

    /**
     * 列出全部对话，按 {@code startedAt} <b>最新在前</b>（同一时刻按 id 升序，保证顺序确定）。
     *
     * <p>只认「普通文件 + {@code .json}」：{@code notes.txt}、{@code x.json.bak}、同名目录一律忽略，
     * 也不算「跳过」——它们本来就不是对话记录。
     *
     * <p>单个文件读不出来时跳过它、把 id 记进 {@link #lastSkippedIds()}，其余照常返回；
     * 目录不存在（或还是空的）时返回空列表，不抛异常。
     *
     * @throws IOException 该是目录的位置上是个文件（明确的配置错误，必须报出来）
     */
    public List<Conversation> list() throws IOException {
        if (!Files.exists(conversationsDir)) {
            lastSkippedIds = List.of();
            return List.of();
        }
        if (!Files.isDirectory(conversationsDir)) {
            throw new IOException("对话记录目录的位置上不是目录（可能是同名文件占着）：" + conversationsDir);
        }
        List<Conversation> conversations = new ArrayList<>();
        Set<String> skipped = new TreeSet<>();
        try (Stream<Path> entries = Files.list(conversationsDir)) {
            for (Path entry : entries.toList()) {
                String fileName = entry.getFileName().toString();
                if (!fileName.endsWith(SUFFIX) || !Files.isRegularFile(entry)) {
                    continue;
                }
                String id = fileName.substring(0, fileName.length() - SUFFIX.length());
                if (!isSafeId(id)) {
                    // 名字派生出的 id 连 load() 都会拒绝：不能把它端给界面，只当坏文件上报。
                    skipped.add(id);
                    continue;
                }
                try {
                    conversations.add(readConversation(entry, id));
                } catch (IOException | RuntimeException e) {
                    // 一个坏文件不该让整份历史消失（这是本方法存在的前提）。
                    // 这里连 RuntimeException 一起兜住，是因为「坏文件不炸整体」这条承诺
                    // 必须对任意字节成立，而不是只对「我们想到过的那几种损坏」成立。
                    skipped.add(id);
                }
            }
        }
        conversations.sort(Comparator.comparing(Conversation::startedAt).reversed()
                .thenComparing(Conversation::id));
        lastSkippedIds = List.copyOf(skipped);
        return List.copyOf(conversations);
    }

    /**
     * 删除一条对话；文件不存在（甚至目录不存在）都是 no-op。
     *
     * @throws IllegalArgumentException id 不安全（否则 {@code delete("../x")} 能删掉别人的文件）
     * @throws IOException              删除本身失败
     */
    public void delete(String id) throws IOException {
        Files.deleteIfExists(fileFor(id));
    }

    /**
     * 供「导出到资料」使用：渲染成 markdown（标题、时间、条数，然后每条消息一个
     * {@code ## 用户} / {@code ## 助手} 分段）。只返回字符串，<b>不落盘</b>——
     * 写到 {@code study\资料\} 是界面拿到批准之后的事。
     *
     * <p>分段标题沿用主计划文件的风格（{@code #} 标题 + {@code >} 说明 + {@code ---} 分隔 + {@code ##} 小节）。
     * 消息正文逐字嵌入、绝不审查：用户与助手说过的话是记录，不是文案，哪怕里面出现界面禁用词。
     * 时间用 UTC（{@code Instant} 的原样形态），不猜时区。
     */
    public String toMarkdown(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        String title = conversation.title() == null || conversation.title().isBlank()
                ? "（未命名对话）"
                : conversation.title();
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(title).append("\n\n");
        markdown.append("> 本文件由「学习助手」App 从对话历史导出。\n");
        markdown.append("> 开始时间（UTC）：").append(conversation.startedAt())
                .append(" ｜ 共 ").append(conversation.messages().size()).append(" 条消息\n\n");
        markdown.append("---\n");
        for (AiMessage message : conversation.messages()) {
            markdown.append("\n## ").append(sectionTitle(message.role())).append("\n\n");
            if (message.content() != null) {
                markdown.append(message.content());
            }
            markdown.append("\n");
        }
        return markdown.toString();
    }

    /**
     * 最近一次 {@link #list()} 跳过的对话 id（升序，不可变）。
     *
     * <p>每次 {@code list()} 都重新算一遍（上一次的结果不残留）；还没调用过 {@code list()} 时是空列表。
     * 界面据此提示「有 N 条历史读不出来」，而不是让它们无声消失。
     */
    public List<String> lastSkippedIds() {
        return lastSkippedIds;
    }

    // ------------------------------------------------------------------
    // 内部：路径与 id
    // ------------------------------------------------------------------

    /** 校验 id 并拼出 {@code <conversationsDir>/<id>.json}。所有磁盘入口都必须先过这里。 */
    private Path fileFor(String id) {
        requireSafeId(id);
        return conversationsDir.resolve(id + SUFFIX);
    }

    private static void requireSafeId(String id) {
        if (!isSafeId(id)) {
            throw new IllegalArgumentException(
                    "id 必须是安全的文件名（不得为空、不得含 \"..\"、路径分隔符、Windows 保留字符或保留设备名，"
                            + "因为 id 会直接拼成 <conversationsDir>/<id>.json）：「" + id + "」");
        }
    }

    private static boolean isSafeId(String id) {
        if (id == null || id.isBlank() || id.contains("..")) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (FORBIDDEN_ID_CHARS.indexOf(c) >= 0 || c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return !RESERVED_DEVICE_NAMES.contains(id.toUpperCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // 内部：写
    // ------------------------------------------------------------------

    private static String toJson(Conversation conversation) {
        JsonObject root = new JsonObject();
        root.addProperty("id", conversation.id());
        root.add("title", conversation.title() == null
                ? JsonNull.INSTANCE
                : new JsonPrimitive(conversation.title()));
        root.addProperty("startedAt", conversation.startedAt().toString());
        JsonArray messages = new JsonArray();
        for (AiMessage message : conversation.messages()) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.add("content", message.content() == null
                    ? JsonNull.INSTANCE
                    : new JsonPrimitive(message.content()));
            messages.add(item);
        }
        root.add("messages", messages);
        // 结尾补一个 LF：文本文件以换行收尾，diff 与各种编辑器都更友好。
        return GSON.toJson(root) + "\n";
    }

    /**
     * 拒绝 UTF-8 表示不出来的文本：孤立的代理项（{@code D800-DFFF} 单飞）。
     *
     * <p>这种字符在 {@code String} 里合法，但编码成 UTF-8 时会被替换成 {@code ?}——一次静默的
     * 数据损坏，而且下次读回来就再也回不去了。本项目的口径是「非法输入一律拒绝，绝不返回有损内容」，
     * 于是这里选择响亮地失败：宁可这次保存没成，也不要用户的历史里多一个问号。
     * 合法的代理对（emoji 等）不受影响。
     */
    private static void requireEncodable(Conversation conversation, Path file) throws IOException {
        checkEncodable(conversation.id(), "id", file);
        checkEncodable(conversation.title(), "title", file);
        for (int i = 0; i < conversation.messages().size(); i++) {
            AiMessage message = conversation.messages().get(i);
            checkEncodable(message.role(), "第 " + i + " 条消息的 role", file);
            checkEncodable(message.content(), "第 " + i + " 条消息的 content", file);
        }
    }

    private static void checkEncodable(String text, String where, Path file) throws IOException {
        if (text == null) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                throw notEncodable(file, where, i, c);
            }
            if (Character.isLowSurrogate(c)) {
                throw notEncodable(file, where, i, c);
            }
        }
    }

    private static IOException notEncodable(Path file, String where, int index, char codeUnit) {
        return new IOException("正文里有一个孤立代理项（unpaired surrogate U+"
                + String.format("%04X", (int) codeUnit) + "，位置：" + where + " 第 " + index
                + " 个码元），它无法用 UTF-8 表示、落盘会静默变成 '?'，因此拒绝保存：" + file);
    }

    // ------------------------------------------------------------------
    // 内部：读
    // ------------------------------------------------------------------

    /**
     * 读一个对话文件。
     *
     * @param expectedId 文件名派生的 id；文件里写的 id 必须与它一致
     */
    private static Conversation readConversation(Path file, String expectedId) throws IOException {
        String content = TextFileCodec.read(file).content();
        // 串首的 BOM 不在这里手工剥：Gson 的 JsonReader 自己会把它当空白跳过。这不是推测——
        // 变异验证 M18 实测：把下面那段「显式 strip BOM」删掉之后 load_toleratesBom 仍然全绿，
        // 说明那是不可观测的死代码，已移除。这条性质现在由 load_toleratesBom 守着
        // （哪天 Gson 不再跳过 BOM，那条测试会红）。我们自己的写入端永远不写 BOM
        // （TextFileCodec.write），而用户拿编辑器另存为「UTF-8 带 BOM」之后历史照样读得回来。
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(content);
        } catch (RuntimeException e) {
            // Gson 的解析失败都是 RuntimeException 家族（JsonSyntaxException / JsonIOException）。
            // 这里兜住整个家族：坏文件的形状由外部字节决定，不该假设它只会抛哪几个类。
            throw corrupt(file, "内容不是合法 JSON", e);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw corrupt(file, "顶层不是 JSON 对象（应形如 {\"id\":…,\"messages\":[…]}）", null);
        }
        JsonObject root = parsed.getAsJsonObject();

        String storedId = requireString(root, "id", file);
        if (!storedId.equals(expectedId)) {
            throw corrupt(file, "文件里的 id（" + storedId + "）与文件名派生的 id（" + expectedId + "）不一致", null);
        }
        String title = optionalString(root, "title", file);
        Instant startedAt = parseInstant(requireString(root, "startedAt", file), file);

        JsonElement messagesElement = root.get("messages");
        if (messagesElement == null || !messagesElement.isJsonArray()) {
            throw corrupt(file, "messages 必须是数组", null);
        }
        List<AiMessage> messages = new ArrayList<>();
        JsonArray array = messagesElement.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw corrupt(file, "messages 的第 " + i + " 项必须是对象（{role, content}）", null);
            }
            JsonObject item = element.getAsJsonObject();
            messages.add(new AiMessage(requireString(item, "role", file), optionalString(item, "content", file)));
        }
        return new Conversation(storedId, title, startedAt, messages);
    }

    private static Instant parseInstant(String raw, Path file) throws IOException {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw corrupt(file, "startedAt 不是 ISO-8601 时刻（应形如 2026-10-11T01:30:00Z）：「" + raw + "」", e);
        }
    }

    /** 取一个必填字符串字段；缺失、为 null、或不是字符串都算坏文件。 */
    private static String requireString(JsonObject object, String field, Path file) throws IOException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            throw corrupt(file, "缺少 " + field + " 字段", null);
        }
        if (!isString(element)) {
            throw corrupt(file, field + " 字段必须是字符串，实际是：" + element, null);
        }
        return element.getAsString();
    }

    /** 取一个可选字符串字段；缺失或 JSON null 都是 {@code null}（原样保留，不替换成空串）。 */
    private static String optionalString(JsonObject object, String field, Path file) throws IOException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!isString(element)) {
            throw corrupt(file, field + " 字段必须是字符串或 null，实际是：" + element, null);
        }
        return element.getAsString();
    }

    private static boolean isString(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static IOException corrupt(Path file, String reason, Throwable cause) {
        return new IOException("对话记录文件读不出来（" + reason + "）：" + file, cause);
    }

    private static String sectionTitle(String role) {
        if (role == null) {
            return "（未知角色）";
        }
        return switch (role) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "system" -> "系统";
            // 未知角色原样成段：绝不因为「不认识」就把消息丢掉。
            default -> role;
        };
    }
}
