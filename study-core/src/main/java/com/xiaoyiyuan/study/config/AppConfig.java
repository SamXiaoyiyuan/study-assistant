package com.xiaoyiyuan.study.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用配置：学习目录、暂缓内容显示方式、情绪选项。
 *
 * <p><b>只有一个字段是「路径」语义</b>（学习目录），其余都是界面偏好。整体策略是
 * 「配置坏了也得能启动」：任何一个键缺失、乱写、或整份文件不存在，都回退到默认值，
 * 绝不因为配置问题抛异常——用户打不开界面，就更没法把配置改回来。
 *
 * <p>文件格式刻意保持极简、可手改、可读，每行一条 {@code 键=值}：
 * <pre>
 * studyDir=E:\别的目录
 * deferredVisibility=COLLAPSED
 * moodOptions=平静,有劲,疲惫,烦躁,低落,焦虑,开心,麻木
 * </pre>
 * <b>值按字面读取</b>，唯一的例外是首尾空白会被去掉（见 {@link #readValues}）。
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

    /** 这份配置对应的文件；{@link #save()} 写的就是它。 */
    private final Path file;

    private Path studyDir;
    private DeferredVisibility deferredVisibility;
    private List<String> moodOptions;

    private AppConfig(Path file, Path studyDir, DeferredVisibility deferredVisibility,
            List<String> moodOptions) {
        this.file = file;
        this.studyDir = studyDir;
        this.deferredVisibility = deferredVisibility;
        this.moodOptions = moodOptions;
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
                DeferredVisibility.DEFAULT, DEFAULT_MOOD_OPTIONS);
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
     * @throws IOException 写盘失败（磁盘满、目录不在、权限不足……）：这一次没保存成功，
     *                     调用方必须让用户看到，不能当作已保存
     */
    public void save() throws IOException {
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
     */
    private String render() {
        return KEY_STUDY_DIR + "=" + stripLineBreaks(studyDir.toString()) + "\n"
                + KEY_DEFERRED_VISIBILITY + "=" + deferredVisibility.name() + "\n"
                + KEY_MOOD_OPTIONS + "=" + stripLineBreaks(joinMoodOptions(moodOptions)) + "\n";
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
}
