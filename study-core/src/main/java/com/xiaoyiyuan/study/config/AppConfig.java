package com.xiaoyiyuan.study.config;

import com.xiaoyiyuan.study.wellbeing.CrisisResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 应用配置：学习目录、暂缓内容显示方式、情绪选项、AI 连接与超时、危机求助渠道。
 *
 * <p><b>只有一个字段是「路径」语义</b>（学习目录），其余都是界面偏好或连接参数。
 * 整体策略是「配置坏了也得能启动」：任何一个键缺失、乱写、或整份文件不存在，都回退到
 * 默认值，绝不因为配置问题抛异常——用户打不开界面，就更没法把配置改回来。
 *
 * <p>文件格式刻意保持极简、可手改、可读，每行一条 {@code 键=值}：
 * <pre>
 * studyDir=E:\别的目录
 * deferredVisibility=COLLAPSED
 * moodOptions=平静,有劲,疲惫,烦躁,低落,焦虑,开心,麻木
 * base_url=https://api.deepseek.com
 * api_key=sk-……
 * model=deepseek-chat
 * request_timeout=60
 * schoolCounseling=校医院三楼 心理中心 021-1234-5678
 * crisis.1.label=全国统一心理援助热线
 * crisis.1.number=12356
 * </pre>
 * <b>值按字面读取</b>，唯一的例外是首尾空白会被去掉（见 {@link #readValues}）。
 *
 * <p>键名沿用各自来源的既成命名：AI 三项用 spec §7.1 的字面字段名
 * （{@code base_url} / {@code api_key} / {@code model}），其超时与之同组、随用
 * {@code request_timeout}；其余新键（{@code schoolCounseling}）与既有各键一致，
 * 用方法名的驼峰式；危机渠道逐条编号存储（{@code crisis.N.label} / {@code crisis.N.number}，
 * 不用逗号拼接——标签里可能有逗号）。
 */
public final class AppConfig {

    /** 默认学习目录的配置项名。 */
    private static final String KEY_STUDY_DIR = "studyDir";
    /** 暂缓内容显示方式的配置项名。 */
    private static final String KEY_DEFERRED_VISIBILITY = "deferredVisibility";
    /** 情绪选项的配置项名（逗号分隔一行的那个）。 */
    private static final String KEY_MOOD_OPTIONS = "moodOptions";

    /** 情绪选项默认值。用户可直接编辑这一行增删。 */
    private static final List<String> DEFAULT_MOOD_OPTIONS =
            List.of("平静", "有劲", "疲惫", "烦躁", "低落", "焦虑", "开心", "麻木");

    /** 情绪选项的分隔符（英文逗号）。 */
    private static final String MOOD_SEPARATOR = ",";

    /** AI 端点地址的配置项名（spec §7.1 的字面字段名）。 */
    private static final String KEY_BASE_URL = "base_url";
    /** AI API Key 的配置项名（spec §7.1 的字面字段名；明文存储，界面会如实告知）。 */
    private static final String KEY_API_KEY = "api_key";
    /** AI 模型名的配置项名（spec §7.1 的字面字段名）。 */
    private static final String KEY_MODEL = "model";
    /** 请求超时的配置项名；值是一个整秒数。 */
    private static final String KEY_REQUEST_TIMEOUT = "request_timeout";
    /** 学校心理健康中心的配置项名（spec 附录 A：用户自行填写，可留空）。 */
    private static final String KEY_SCHOOL_COUNSELING = "schoolCounseling";

    /** 危机渠道逐条键的前缀；整键形如 {@code crisis.1.label} / {@code crisis.1.number}。 */
    private static final String CRISIS_KEY_PREFIX = "crisis.";
    /** 逐条键的两个字段名。 */
    private static final String CRISIS_FIELD_LABEL = "label";
    private static final String CRISIS_FIELD_NUMBER = "number";

    /** 请求超时默认值：60 秒。 */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 危机渠道默认值：spec 附录 A 的前五条内置渠道，<b>顺序即展示顺序</b>。
     *
     * <p>号码与文案逐字来自附录 A；不要「顺手优化」顺序或文案——这份默认值同时是
     * 面板的保底（附录 A：若用户清空全部渠道，面板不能空无一物）。
     */
    private static final List<CrisisResource> DEFAULT_CRISIS_RESOURCES = List.of(
            new CrisisResource("全国统一心理援助热线", "12356"),
            new CrisisResource("北京心理援助热线（手机）", "010-82951332"),
            new CrisisResource("北京心理援助热线（座机）", "800-810-1117"),
            new CrisisResource("急救", "120"),
            new CrisisResource("报警", "110"));

    /** 不可删除的保底号码（spec §12：可改文案，不可移除）。 */
    private static final List<String> REQUIRED_CRISIS_NUMBERS = List.of("120", "110");

    /** 这份配置对应的文件；{@link #save()} 写的就是它。 */
    private final Path file;

    private Path studyDir;
    private DeferredVisibility deferredVisibility;
    private List<String> moodOptions;
    private String baseUrl;
    private String apiKey;
    private String model;
    private Duration requestTimeout;
    private String schoolCounseling;
    private List<CrisisResource> crisisResources;

    private AppConfig(Path file, Path studyDir, DeferredVisibility deferredVisibility,
            List<String> moodOptions, String baseUrl, String apiKey, String model,
            Duration requestTimeout, String schoolCounseling, List<CrisisResource> crisisResources) {
        this.file = file;
        this.studyDir = studyDir;
        this.deferredVisibility = deferredVisibility;
        this.moodOptions = moodOptions;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.schoolCounseling = schoolCounseling;
        this.crisisResources = crisisResources;
    }

    /**
     * 读取配置；文件不存在、读不动、或内容乱写时，返回<b>可用的默认配置</b>。
     *
     * <p>刻意不抛 {@code IOException}：调用方是启动路径，这里抛异常等于「配置文件的问题
     * 让 App 起不来」，而用户恰恰要靠 App 才能把配置改回去。所以读盘失败也退默认值。
     *
     * <p>逐键解析、逐个回退：只有一个键写坏时，其余键照常生效——不因为一行乱码丢掉
     * 用户辛苦填的学习目录。
     *
     * @param file 配置文件路径（通常是 {@link AppPaths#configFile()}）；为 null 时用默认路径
     */
    public static AppConfig load(Path file) {
        Path target = file != null ? file : AppPaths.configFile();
        AppConfig config = new AppConfig(target, AppPaths.defaultStudyDir(),
                DeferredVisibility.DEFAULT, DEFAULT_MOOD_OPTIONS,
                "", "", "", DEFAULT_REQUEST_TIMEOUT, "", DEFAULT_CRISIS_RESOURCES);
        if (!Files.isRegularFile(target)) {
            return config;
        }
        List<String> lines;
        try {
            lines = readValues(target);
        } catch (IOException e) {
            // 读不动（被别的程序锁住、编码坏了、权限不足……）：先用默认值让 App 起来。
            return config;
        }
        config.studyDir = parseStudyDir(valueOf(lines, KEY_STUDY_DIR), config.studyDir);
        config.deferredVisibility =
                DeferredVisibility.fromConfigValue(valueOf(lines, KEY_DEFERRED_VISIBILITY));
        config.moodOptions = parseMoodOptions(valueOf(lines, KEY_MOOD_OPTIONS));
        config.baseUrl = orEmpty(valueOf(lines, KEY_BASE_URL));
        config.apiKey = orEmpty(valueOf(lines, KEY_API_KEY));
        config.model = orEmpty(valueOf(lines, KEY_MODEL));
        config.requestTimeout = parseRequestTimeout(valueOf(lines, KEY_REQUEST_TIMEOUT));
        config.schoolCounseling = orEmpty(valueOf(lines, KEY_SCHOOL_COUNSELING));
        config.crisisResources = parseCrisisResources(lines);
        return config;
    }

    /** 当前的学习目录（主计划文件、备份、归档都在它下面）。 */
    public Path studyDir() {
        return studyDir;
    }

    public void setStudyDir(Path studyDir) {
        this.studyDir = studyDir;
    }

    /** 暂缓内容的显示方式。 */
    public DeferredVisibility deferredVisibility() {
        return deferredVisibility;
    }

    public void setDeferredVisibility(DeferredVisibility deferredVisibility) {
        this.deferredVisibility = deferredVisibility;
    }

    /** 情绪选项（周记录里选的词）。返回的列表不可变，改内容请走 {@link #setMoodOptions}。 */
    public List<String> moodOptions() {
        return moodOptions;
    }

    /**
     * 设置情绪选项。
     *
     * <p>存进来的是<b>值快照</b>（{@link List#copyOf}）：外面之后改自己那个 List
     * 不应该偷偷改掉配置里的值。空列表视为「没设置」，回退默认值——
     * 界面上一个情绪都选不出来不是一种可用状态。
     */
    public void setMoodOptions(List<String> moodOptions) {
        this.moodOptions = snapshotMoodOptions(moodOptions);
    }

    /**
     * AI 服务的 base_url（OpenAI 兼容端点；spec §7.1）。默认空串 = 还没配置。
     *
     * <p>本类只存值、不做 URL 规范化：「怎么拼出最终请求地址」是 {@code ai} 包
     * （计划 2 Task 4）的事，那里才有测试；配置层顺手规范化会让同一件事有两个定义。
     */
    public String baseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = orEmpty(baseUrl);
    }

    /**
     * AI 服务的 API Key。默认空串；<b>明文存储</b>，界面必须如实告知（spec §7.1）。
     *
     * <p>不实现「假加密」：客户端能解的密，同一台机器上的任何程序都能解，
     * 只会制造安全假象；如实告知风险比伪造安全性诚实。
     */
    public String apiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = orEmpty(apiKey);
    }

    /** AI 模型名（如 {@code deepseek-chat}）。默认空串。 */
    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = orEmpty(model);
    }

    /**
     * 请求超时。默认 60 秒；文件里存的是<b>整秒数</b>（键 {@code request_timeout}）。
     *
     * <p>setter 拒绝非正数、也拒绝带亚秒部分的 Duration：写出的值必须能被原样读回。
     * 500 毫秒若悄悄截断成 0，读回时就变成默认值——「设了 0.5 秒却跑了 60 秒」，无从察觉。
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * @throws IllegalArgumentException 为 null、非正数、或含亚秒部分（无法用整秒无损表示）
     */
    public void setRequestTimeout(Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("请求超时必须为正数；收到：" + requestTimeout
                    + "。零或负超时会让每次请求直接失败，这里拒绝比写进文件后再排查好。");
        }
        if (requestTimeout.toNanosPart() != 0) {
            throw new IllegalArgumentException("请求超时的文件粒度是整秒，含亚秒的值无法无损保存；收到："
                    + requestTimeout + "。");
        }
        this.requestTimeout = requestTimeout;
    }

    /**
     * 学校心理健康中心的联系方式（自由文本，可为空）。默认空串。
     *
     * <p>留空时危机面板不显示该行（spec §6.5）——各校不同、无法预设，只能由用户填写。
     */
    public String schoolCounseling() {
        return schoolCounseling;
    }

    public void setSchoolCounseling(String schoolCounseling) {
        this.schoolCounseling = orEmpty(schoolCounseling);
    }

    /**
     * 危机求助渠道。默认五条内置（spec 附录 A），顺序即面板展示顺序。
     * 返回的列表不可变；修改请走 {@link #setCrisisResources}。
     */
    public List<CrisisResource> crisisResources() {
        return crisisResources;
    }

    /**
     * 设置危机渠道列表（设置页的增删改都走这里）。
     *
     * <p><b>120 与 110 的删除保护就在这里，且只在这里</b>（spec §12 的保底规则）：
     * 两条号码不许从列表里消失（文案可以改）。理由：若用户清空全部渠道，危机命中时
     * 面板会空无一物，而这两个号码在任何情况下都有意义。
     *
     * <p><b>为什么保护不做在 {@link #load} 或界面层</b>：界面上的每一次增删改最终都汇聚
     * 到这一个入口，拦在这里，就没有任何程序路径能把列表改成缺这两条的形态。反过来，
     * 读取必须忠实——用户手改文件删掉一条时，读取如实反映文件内容（读取不隐藏、不修正）；
     * 若 {@code load} 悄悄补回被删的号码，用户会看到「明明删掉了又自己冒出来」，
     * 比删除本身更令人困惑。真正要守的性质是「<b>程序自己绝不写出一份缺 120/110 的配置</b>」，
     * 而这只有写入口能守。
     *
     * <p>存进来的是值快照（{@link List#copyOf}），外部之后改自己那个列表不会影响配置；
     * {@code null} 视为空列表，同样会被下面的检查拒绝。
     *
     * @throws IllegalArgumentException 列表缺少 120 或 110（消息点明缺哪个）；抛出时已有配置不变
     */
    public void setCrisisResources(List<CrisisResource> crisisResources) {
        List<CrisisResource> snapshot = crisisResources == null ? List.of() : List.copyOf(crisisResources);
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_CRISIS_NUMBERS) {
            boolean present = false;
            for (CrisisResource resource : snapshot) {
                if (required.equals(resource.number())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("危机资源不能缺少 " + String.join(" 和 ", missing)
                    + "：这两条是保底渠道，不可删除（文案可以改）");
        }
        this.crisisResources = snapshot;
    }

    /**
     * 把当前配置写回磁盘。
     *
     * <p>这里是本类唯一写盘的地方，也是全项目唯一像配置序列化的地方，
     * 所以格式只在 {@link #render()} 里定义一次。
     *
     * <p><b>为什么不用 {@link java.util.Properties#store}</b>：{@code store} 会
     * ① 在文件头写上注释与「几点几分保存的」时间戳（于是每次保存都产生无意义的 diff），
     * ② 按 ISO-8859-1 输出，把中文转义成 {@code &#92;u60B2} 之类的序列——
     * 配置文件是给人看、给人手改的，转义之后中文读者的第一反应是「这文件怎么是乱码」。
     *
     * <p><b>自己把所在目录建出来</b>：配置默认落在
     * {@code %APPDATA%\xiaoyiyuan-study\config.properties}，而那个目录<b>全新安装时并不存在</b>
     * （本机实测不存在）。少了这一步，用户第一次改设置就撞 {@code NoSuchFileException}——
     * 「改不动设置」是启动路径上最容易劝退人的失败。{@code createDirectories} 对已存在的目录
     * 是无操作，所以重复保存不会出问题。
     *
     * @throws IOException 写盘失败（磁盘满、权限不足……）：这一次没保存成功，
     *                     调用方必须让用户看到，不能当作已保存
     */
    public void save() throws IOException {
        // 配置目录可能整个都不存在（全新安装）。父路径为 null 时（相对路径且只有一段）
        // 没有目录可建，跳过即可。
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // writeString 用 UTF-8 编码，且 JDK 明确不写 BOM（BOM 必须由调用方自己放进内容里）。
        Files.writeString(file, render(), StandardCharsets.UTF_8);
    }

    /**
     * 渲染整个配置文件的文本（UTF-8、LF、无 BOM 的纯文本）。
     *
     * <p>值一律<b>按字面写出</b>，一个反斜杠都不加倍：文件里的
     * {@code studyDir=E:\别的目录} 就是用户心里想的那条路径。
     *
     * <p><b>为什么格式这样定</b>（这是本任务最容易踩的坑）：曾用
     * {@link java.util.Properties} 读写，两头都不成立——
     * <ul>
     *   <li>写时若转义反斜杠（{@code Properties.store} 的做法），文件里会变成
     *       {@code E:\\别的目录}，中文读者看不懂；</li>
     *   <li>写时不转义，读时 {@code Properties.load} 会把 {@code \X} 当转义序列处理，
     *       认不出来就<b>只留下 {@code X}</b>。本机 JDK 25 实测（同一个值既当输入又当解析结果比较）：
     *       <pre>
     *       E:\我的目录          → E:我的目录          （反斜杠被吞，变成「盘符相对路径」）
     *       E:\new               → E:&lt;换行&gt;ew          （值里真的多了换行）
     *       E:\tabs              → E:&lt;制表符&gt;abs
     *       E:\我的目录 + 尾随空格 → 解析后 Path.of 直接抛 InvalidPathException
     *       %USERPROFILE%\study → D:示例 目录study   ← 本项目自己的默认目录
     *       </pre>
     *       最后一条最要命：默认学习目录 {@code %USERPROFILE%\study} 被吞掉一个反斜杠后
     *       仍然是个「合法路径」，{@code Path.of} 不报错也不抛异常，于是 App 会安安静静地
     *       去一个<b>不存在的目录</b>里找计划文件——用户看到的是「我的计划没了」。</li>
     * </ul>
     * 所以值的读写都<b>不走 properties 转义规则</b>，走字面值（见 {@link #readValues}）。
     * 路径里有空格、反斜杠、中文都不需要任何引号或转义。
     *
     * <p>换行符显式写成 {@code \n}：既让本文件在任何平台读写的字节都一样，
     * 也让保存后的文件永远不含 {@code \r\n}（本项目全库统一 LF）。
     * 值内部的换行会被去掉（见 {@link #stripLineBreaks}）——一行一个键值，
     * 值里带换行就会把下一行吃掉。
     *
     * <p><b>键序固定</b>：基础三键 → AI 三项 → 超时 → 学校心理中心 → 危机渠道（按列表顺序，
     * 逐条两行）。保存产生的 diff 因此永远只是值的变化；测试
     * {@code writtenFileIsPlainReadableText} 把这份逐字形态钉住。
     */
    private String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(KEY_STUDY_DIR).append('=').append(stripLineBreaks(studyDir.toString())).append('\n');
        sb.append(KEY_DEFERRED_VISIBILITY).append('=').append(deferredVisibility.name()).append('\n');
        sb.append(KEY_MOOD_OPTIONS).append('=').append(stripLineBreaks(joinMoodOptions(moodOptions)))
                .append('\n');
        sb.append(KEY_BASE_URL).append('=').append(stripLineBreaks(baseUrl)).append('\n');
        sb.append(KEY_API_KEY).append('=').append(stripLineBreaks(apiKey)).append('\n');
        sb.append(KEY_MODEL).append('=').append(stripLineBreaks(model)).append('\n');
        sb.append(KEY_REQUEST_TIMEOUT).append('=').append(requestTimeout.toSeconds()).append('\n');
        sb.append(KEY_SCHOOL_COUNSELING).append('=').append(stripLineBreaks(schoolCounseling)).append('\n');
        for (int i = 0; i < crisisResources.size(); i++) {
            CrisisResource resource = crisisResources.get(i);
            String itemKey = CRISIS_KEY_PREFIX + (i + 1) + ".";
            sb.append(itemKey).append(CRISIS_FIELD_LABEL).append('=')
                    .append(stripLineBreaks(resource.label())).append('\n');
            sb.append(itemKey).append(CRISIS_FIELD_NUMBER).append('=')
                    .append(stripLineBreaks(resource.number())).append('\n');
        }
        return sb.toString();
    }

    /**
     * 读配置文件，返回每一行的原文（已剥掉行尾的 {@code \r}）。
     *
     * <p>先剥掉文件头的 BOM：带 BOM 的文件如果让 BOM 留在第一行，第一个键就会变成
     * 「BOM + studyDir」，于是整份配置静默失效、全部退回默认值。用户用记事本或
     * 某些编辑器改过配置就可能带上 BOM，这种「配置看起来没问题却全不生效」最难排查。
     */
    private static List<String> readValues(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return lines;
    }

    /**
     * 从各行里取出某个键的值：第一个 {@code =} 之前是键、之后<b>整段</b>是值，值只去首尾空白。
     *
     * <p>只按第一个 {@code =} 切，值里的 {@code =} 因此不受影响。
     * 不把 {@code #}、{@code !} 当注释：配置文件是我们自己写的固定三行，
     * 「猜哪一行是注释」只会引入新的歧义，不如让每行都按字面处理、认不出来的行直接忽略。
     *
     * <p>值只去首尾空白是必须的（实测）：漏掉尾随空格时
     * {@code studyDir=E:\学习 } 会让 Windows 的 {@code Path.of} 抛
     * {@link InvalidPathException}（尾随空格非法），用户多敲一个空格就让整个学习目录
     * 静默退回默认值。去掉首尾空白后，多写的空格只是一种无害的排版习惯。
     */
    private static String valueOf(List<String> lines, String key) {
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator >= 0 && line.substring(0, separator).trim().equals(key)) {
                return line.substring(separator + 1).trim();
            }
        }
        return null;
    }

    /** 一行一个键值，值里不能有换行——去掉它，避免把文件结构写坏。 */
    private static String stripLineBreaks(String value) {
        return value.replace("\r", "").replace("\n", "");
    }

    /**
     * 学习目录：解析成 {@link Path}；空白或不是合法路径时保留传入的兜底值。
     *
     * <p>值除了首尾空白之外不做任何清理，原样交给 {@code Path.of}
     * （Windows 路径里的反斜杠因此不会被当成转义序列吃掉）。
     */
    private static Path parseStudyDir(String raw, Path fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Path.of(raw);
        } catch (InvalidPathException e) {
            return fallback;
        }
    }

    /** 情绪选项：逗号分隔一行；空项丢弃；一个都不剩（或没这个键）时用默认值。 */
    private static List<String> parseMoodOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MOOD_OPTIONS;
        }
        List<String> options = new ArrayList<>();
        for (String part : raw.split(MOOD_SEPARATOR, -1)) {
            String option = part.trim();
            if (!option.isEmpty()) {
                options.add(option);
            }
        }
        return options.isEmpty() ? DEFAULT_MOOD_OPTIONS : List.copyOf(options);
    }

    /** 渲染情绪选项：去掉空项，再拼成一行。 */
    private static String joinMoodOptions(List<String> options) {
        return String.join(MOOD_SEPARATOR, snapshotMoodOptions(options));
    }

    /** 值快照：空/全空项回退默认值，否则复制成不可变列表。 */
    private static List<String> snapshotMoodOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return DEFAULT_MOOD_OPTIONS;
        }
        List<String> kept = new ArrayList<>();
        for (String option : options) {
            if (option != null && !option.isBlank()) {
                kept.add(option);
            }
        }
        return kept.isEmpty() ? DEFAULT_MOOD_OPTIONS : List.copyOf(kept);
    }

    /** 文本值：{@code null} 归一化为空串——AI 三项与学校心理中心的「默认值」就是空。 */
    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 请求超时：值是一个整秒数；缺失、乱写、非正数都回退默认 60 秒。
     *
     * <p>与其余各键同一个姿态（「配置坏了也得能启动」）：一个手误不该让 AI 功能不可用。
     * 这里宁可用保守的默认值也不猜——把「120 分钟」猜成 120 秒这类修正，比默认值更坏。
     */
    private static Duration parseRequestTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_REQUEST_TIMEOUT;
        }
        try {
            long seconds = Long.parseLong(raw);
            return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_REQUEST_TIMEOUT;
        } catch (NumberFormatException e) {
            return DEFAULT_REQUEST_TIMEOUT;
        }
    }

    /**
     * 危机渠道：收集 {@code crisis.<n>.label} / {@code crisis.<n>.number}，按 <i>n</i> 升序重组；
     * 一个这样的键都没有时，回退五条内置默认。
     *
     * <p>用可排序映射，而不是「从 1 开始逐个找、遇到空缺就停」：手改过的文件里编号可能
     * 不连续（删了 {@code crisis.2.*} 却留着 {@code crisis.3.*}），后一种做法会静默丢掉
     * 后面的渠道——丢的偏偏是与安全有关的号码。同一编号重复出现时，首次命中者胜
     * （与 {@link #valueOf} 的首条命中语义一致）。
     *
     * <p>缺一侧的条目用空串补齐（例如只有号码没有文案），不整条丢弃：什么都不丢是读取端
     * 的本分，展示与否交给界面。
     */
    private static List<CrisisResource> parseCrisisResources(List<String> lines) {
        TreeMap<Integer, String[]> byIndex = new TreeMap<>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (!key.startsWith(CRISIS_KEY_PREFIX)) {
                continue;
            }
            String rest = key.substring(CRISIS_KEY_PREFIX.length());
            int dot = rest.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(rest.substring(0, dot));
            } catch (NumberFormatException e) {
                continue;
            }
            if (index < 1) {
                continue;
            }
            String field = rest.substring(dot + 1);
            int slot;
            if (CRISIS_FIELD_LABEL.equals(field)) {
                slot = 0;
            } else if (CRISIS_FIELD_NUMBER.equals(field)) {
                slot = 1;
            } else {
                continue;
            }
            String[] pair = byIndex.computeIfAbsent(index, k -> new String[2]);
            if (pair[slot] == null) {
                pair[slot] = line.substring(separator + 1).trim();
            }
        }
        if (byIndex.isEmpty()) {
            return DEFAULT_CRISIS_RESOURCES;
        }
        List<CrisisResource> resources = new ArrayList<>(byIndex.size());
        for (String[] pair : byIndex.values()) {
            resources.add(new CrisisResource(pair[0] == null ? "" : pair[0],
                    pair[1] == null ? "" : pair[1]));
        }
        return List.copyOf(resources);
    }
}
