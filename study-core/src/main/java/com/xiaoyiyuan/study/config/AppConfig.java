package com.xiaoyiyuan.study.config;

import com.xiaoyiyuan.study.wellbeing.CrisisResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * 请求超时上界：24 小时。
     *
     * <p>下界（必须为正）防的是「每次请求立刻失败」，上界防的是另一种更难排查的失败：
     * 存得下 {@code Duration.ofSeconds(Long.MAX_VALUE)}，直到真正发请求、内部算
     * {@code toNanos()} 时才抛 {@link ArithmeticException}——那时已经离「用户改设置」很远，
     * 异常措辞（纳秒溢出）对用户也毫无意义。24 小时是常见用例（60 秒～几分钟）的上千倍，
     * 又是一个「一定跑不完」的量级，所以上界钉在这里，超界当场拒绝。
     */
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofHours(24);

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
     * <p><b>唯一的例外是危机渠道的必需号码</b>（{@code 120} / {@code 110}）：它们不是「回退默认」，
     * 而是<b>无条件保留</b>——文件里少了哪条就按默认文案补回来（其余渠道一律原样保留）。
     * 「必须为正」的道理同 setter：不能因为读取端宽纵，就让一次无关字段的编辑把保底号码写没。
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
        // 保底号码在这里补齐：文件是手改的、也可能被别的程序截断，而「不可删除」是对
        // 任何文件内容都成立的性质（见 setCrisisResources 的说明）。这里让默认那条优先，
        // 保证补回来的是「可用的渠道」，不只是「一个等于 120/110 的字符串」。
        List<CrisisResource> fromFile = parseCrisisResources(lines);
        config.crisisResources =
                unionByNumber(fromFile, missingRequiredResources(fromFile), true);
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
     * <p>setter 拒绝非正数、超过 {@link #MAX_REQUEST_TIMEOUT}（24 小时）的值、也拒绝带亚秒部分的
     * Duration：写出的值必须能被原样读回、也必须能被真的用出去。500 毫秒若悄悄截断成 0，
     * 读回时就变成默认值——「设了 0.5 秒却跑了 60 秒」，无从察觉；天文数字则会在
     * {@code toNanos()} 里溢出，那时早已离设置界面很远。
     */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /**
     * @throws IllegalArgumentException 为 null、非正数、超过 24 小时、或含亚秒部分
     *                                  （无法用整秒无损表示）
     */
    public void setRequestTimeout(Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("请求超时必须为正数；收到：" + requestTimeout
                    + "。零或负超时会让每次请求直接失败，这里拒绝比写进文件后再排查好。");
        }
        if (requestTimeout.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException("请求超时不得超过 " + MAX_REQUEST_TIMEOUT.toHours()
                    + " 小时（" + MAX_REQUEST_TIMEOUT.toSeconds() + " 秒）；收到：" + requestTimeout
                    + "。这么大的超时一定跑不完，还会在内部换算成纳秒时溢出。");
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
     * <p><b>120 与 110 的删除保护有两道，这是第一道</b>（spec §12 的保底规则）：
     * 调用方拿一份缺了必需号码的列表进来，这里当场拒绝（消息点明缺哪个）。第二道在
     * {@link #load}：文件是手改的，也可能只写了一半，那里会把缺的必需号码按默认文案补回来，
     * 所以「读到什么就写回什么」不会把必需号码写没。
     *
     * <p><b>为什么两道都要</b>（修复轮 1 的复核发现）：保护原先只有本方法这一道。于是
     * 「文件里只有 {@code crisis.1.number=120}」＋「用户改一个无关字段」＋「保存」这条路径
     * 完全不经过本方法，磁盘上的 {@code 110} 就此永久消失——而这正是用户最没防备的时刻。
     * 本方法仍然保留「缺就拒绝」的语义：界面上少一条就是一次明确的删除动作，该拦住并告诉用户，
     * 而不是替他静默补回（那会变成「删了又自己冒出来」）；读取端则相反，补回来是为了守住
     * 「任何文件内容保存后都还有这两条」这条性质。
     *
     * <p>存进来的是值快照（{@link List#copyOf}），外部之后改自己那个列表不会影响配置；
     * {@code null} 列表本身视为空列表，同样会被下面的检查拒绝。
     *
     * @throws IllegalArgumentException 列表为 {@code null} 或缺少 120 或 110（消息点明缺哪个），
     *                                  或含 {@code null} 元素（消息点明下标）；抛出时已有配置不变
     */
    public void setCrisisResources(List<CrisisResource> crisisResources) {
        if (crisisResources == null) {
            throw new IllegalArgumentException("危机资源列表不能为 null：请给出完整的渠道列表"
                    + "（其中必须含 " + String.join(" 和 ", REQUIRED_CRISIS_NUMBERS) + "）。");
        }
        for (int i = 0; i < crisisResources.size(); i++) {
            // 先自己查 null 元素：List.copyOf 会抛 NPE，而 NPE 与 javadoc 承诺的 IAE 不符——
            // 调用方按 IAE 写的 catch 会漏接，界面也只能拿到一个光秃秃的下标。
            if (crisisResources.get(i) == null) {
                throw new IllegalArgumentException("危机资源列表的第 " + i + " 个元素（下标从 0 起）是 null："
                        + "列表里不允许出现 null 元素，请检查这一项是从哪里来的。");
            }
        }
        List<CrisisResource> snapshot = List.copyOf(crisisResources);
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
     * 请求超时：值是一个整秒数；缺失、乱写、非正数、<b>超过 24 小时</b>都回退默认 60 秒。
     *
     * <p>与其余各键同一个姿态（「配置坏了也得能启动」）：一个手误不该让 AI 功能不可用。
     * 这里宁可用保守的默认值也不猜——把「120 分钟」猜成 120 秒这类修正，比默认值更坏。
     * 上界与 {@link #setRequestTimeout} 用同一个常量：文件里写着天文数字时若放了进来，
     * 读回的值虽然合法，却会在真正发请求时以纳秒溢出的形式炸掉。
     */
    private static Duration parseRequestTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_REQUEST_TIMEOUT;
        }
        try {
            long seconds = Long.parseLong(raw);
            Duration parsed = seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_REQUEST_TIMEOUT;
            return parsed.compareTo(MAX_REQUEST_TIMEOUT) > 0 ? DEFAULT_REQUEST_TIMEOUT : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_REQUEST_TIMEOUT;
        }
    }

    /** 从默认值里按号码取一条：只有 {@link #DEFAULT_CRISIS_RESOURCES} 里确实存在的号码才可能命中。 */
    private static CrisisResource defaultResourceWithNumber(String number) {
        for (CrisisResource resource : DEFAULT_CRISIS_RESOURCES) {
            if (number.equals(resource.number())) {
                return resource;
            }
        }
        throw new IllegalStateException("保底号码不在默认渠道里：" + number);
    }

    /**
     * 从一份渠道列表里挑出「缺的保底号码」，按 {@link #REQUIRED_CRISIS_NUMBERS} 的顺序给默认那条
     * （文案取默认文案：号码缺了就说明用户没有为它写过任何文案，补默认文案最不意外）。
     *
     * <p>只用在读取端；{@link #setCrisisResources} 的语义是「缺就拒绝」，不走这里。
     */
    private static List<CrisisResource> missingRequiredResources(List<CrisisResource> resources) {
        List<CrisisResource> missing = new ArrayList<>();
        for (String required : REQUIRED_CRISIS_NUMBERS) {
            boolean present = false;
            for (CrisisResource resource : resources) {
                if (required.equals(resource.number())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missing.add(defaultResourceWithNumber(required));
            }
        }
        return missing;
    }

    /**
     * 两份列表的并集：{@code primary} 原样在前（标签、顺序都不动），来自 {@code appended} 的条目
     * 只在「号码还不存在，或它就是保底号码」时进来。
     *
     * <p>按<b>号码</b>判重（与删除保护的判据一致：号码才是身份，文案可以改），所以自定义渠道
     * 换成别的号码加进来时不会被误判成重复。
     *
     * <p>{@code appendedWinsOnSameNumber} 用来区分两种调用：
     * <ul>
     *   <li>{@code false}（界面上「用户改了 120 的文案」）：号码已存在就保留用户那条，一个字段都不动；</li>
     *   <li>{@code true}（读取端补保底号码）：必须保证「120/110 都在且可用」，所以号码相同时
     *       用默认那条替换掉文件里那条——否则一条越界键写出来的 {@code crisis.2147483648.number=110}
     *       会把必需号码占住成一个空文案条目，等于用「数字在」蒙混过「渠道可用」。
     *       这种情况只可能是序号写坏的条目，替换掉它不损失用户能识别的信息。</li>
     * </ul>
     *
     * <p>{@code primary} 为空是特例：没有「用户顺序」需要保，直接交给 {@code appended}
     * （默认五条），保底面板不能只剩两条必需号码。
     */
    private static List<CrisisResource> unionByNumber(List<CrisisResource> primary,
            List<CrisisResource> appended, boolean appendedWinsOnSameNumber) {
        List<CrisisResource> merged = new ArrayList<>(primary);
        for (CrisisResource candidate : appended) {
            boolean present = false;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).number().equals(candidate.number())) {
                    present = true;
                    if (appendedWinsOnSameNumber) {
                        merged.set(i, candidate);
                    }
                    break;
                }
            }
            if (!present) {
                merged.add(candidate);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 危机渠道：收集 {@code crisis.<n>.label} / {@code crisis.<n>.number}，按 <i>n</i> 升序重组；
     * 一个<b>可识别</b>的条目都没有时（整节缺失，或只剩 {@code crisis.7.=} 这类两侧全空的空壳），
     * 回退五条内置默认。
     *
     * <p>用可排序映射，而不是「从 1 开始逐个找、遇到空缺就停」：手改过的文件里编号可能
     * 不连续（删了 {@code crisis.2.*} 却留着 {@code crisis.3.*}），后一种做法会静默丢掉
     * 后面的渠道——丢的偏偏是与安全有关的号码。同一编号重复出现时，首次命中者胜
     * （与 {@link #valueOf} 的首条命中语义一致）。
     *
     * <p>缺一侧的条目用空串补齐（例如只有号码没有文案），不整条丢弃：什么都不丢是读取端
     * 的本分，展示与否交给界面。
     *
     * <p>编号两侧对用户都无意义（文件是手改的），所以这里不收窄认得出的形状：
     * <b>负编号与超出 {@code int} 的编号都不再整条丢弃</b>，它们的值照样进列表，只是排在
     * 所有正常编号之后（写回时由 {@link #render} 重新编号）。理由是「丢的偏偏是安全号码」——
     * 用户手滑敲成 {@code crisis.-1.number=119} 时，他要的是这条渠道留下，而不是消失。
     */
    private static List<CrisisResource> parseCrisisResources(List<String> lines) {
        TreeMap<Integer, String[]> byIndex = new TreeMap<>();
        Map<String, Integer> overflowSlots = new LinkedHashMap<>();
        List<String[]> overflowPairs = new ArrayList<>();
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
            String indexText = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            int slot;
            if (CRISIS_FIELD_LABEL.equals(field)) {
                slot = 0;
            } else if (CRISIS_FIELD_NUMBER.equals(field)) {
                slot = 1;
            } else {
                continue;
            }
            String value = line.substring(separator + 1).trim();
            Integer index = parsePositiveIndex(indexText);
            if (index != null) {
                String[] pair = byIndex.computeIfAbsent(index, k -> new String[2]);
                if (pair[slot] == null) {
                    pair[slot] = value;
                }
                continue;
            }
            // 编号越界/为负：条目本身认得出（键形如 crisis.<整数>.<字段>），就照单收下，
            // 归到溢出桶里按文件出现顺序排在正常条目之后。桶的身份是<b>编号的那段文本</b>
            // （不是整个键）：同一编号下的 label 与 number 必须落进同一个数组，否则两行会
            // 变成两条半截渠道（见过一次：一条只有文案、一条只有号码，还各占面板一行）。
            Integer bucket = overflowSlots.get(indexText);
            if (bucket == null) {
                bucket = overflowPairs.size();
                overflowSlots.put(indexText, bucket);
                overflowPairs.add(new String[2]);
            }
            String[] pair = overflowPairs.get(bucket);
            if (pair[slot] == null) {
                pair[slot] = value;
            }
        }
        List<CrisisResource> resources = new ArrayList<>(byIndex.size() + overflowPairs.size());
        for (String[] pair : byIndex.values()) {
            resources.add(new CrisisResource(pair[0] == null ? "" : pair[0],
                    pair[1] == null ? "" : pair[1]));
        }
        for (String[] pair : overflowPairs) {
            // 两侧都是空的条目不算「可识别的条目」（例如别的程序留下的 crisis.7.= ）：
            // 留在列表里只会渲染出一行空行，反而挤占面板。正常编号下的空条目照旧保留——
            // 那是既有语义（只有号码没文案、或只有文案没号码），这里不动它。
            if (pair[0] != null || pair[1] != null) {
                resources.add(new CrisisResource(pair[0] == null ? "" : pair[0],
                        pair[1] == null ? "" : pair[1]));
            }
        }
        // 一个可识别的条目都没有 → 回退默认五条。注意必须在溢出条目筛掉之后判断：
        // 文件里只有 crisis.7.= 这种空壳时，面板该是默认五条，而不是空列表。
        return resources.isEmpty() ? DEFAULT_CRISIS_RESOURCES : List.copyOf(resources);
    }

    /**
     * 键里的编号：能<b>无损</b>表示成 {@code int} 且为正才算正常编号，其余（负数、
     * 超出 {@code int}、根本不是整数）返回 {@code null}。
     *
     * <p>不用 {@code Integer.parseInt} 判：它对 {@code 2147483648} 抛
     * {@link NumberFormatException}，而这是一个「用户敲出来的大数」，不是乱码——
     * 把它当成乱码整条丢掉，正是修复前的静默丢数据路径。
     */
    private static Integer parsePositiveIndex(String text) {
        try {
            int index = Integer.parseInt(text);
            return index >= 1 ? index : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
