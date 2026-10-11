package com.xiaoyiyuan.study.wellbeing;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 一条情绪记录（spec §6.1）。
 *
 * <p>它是<b>文件的映射</b>，不是「编辑中的表单」：每个组件对应文件里的一行（{@code unknownLines}
 * 例外，见下），而「那一行有没有」由 {@code null} 表示。所以 {@code null} 与空串在这里<b>不是</b>
 * 同一回事：用户手写 {@code - 睡眠：} 会被如实读成空串（他确实留了这一行，只是没写内容），
 * 而 {@link #sleep()} 为 {@code null} 表示文件里根本没有这一行。两者回写出来一个是
 * {@code "- 睡眠：\n"}、一个是空——若把空串也当成「没填」抹掉，就会静默删掉用户写下的一行。
 *
 * <h2>强度</h2>
 * 只允许 {@code 1..5} 或 {@code null}（用户在界面上自己打分，也可以不打分）。
 * 校验放在紧凑构造器里，于是<b>任何</b>造出本记录的路径都绕不过它——包括以后从别的界面、
 * 别的文件格式导入的路径。宁可当场抛错，也不能让一个 {@code 9} 悄悄写进用户文件。
 *
 * <h2>未知行不丢</h2>
 * {@link #unknownLines()} 装的是条目里那些「属于 {@code - } 列表、但字段名不是本项目五个字段」
 * 的行（连 {@code "- "} 前缀一起，<b>不含</b>行尾换行符）。为什么必须留着：用户可能用同一个
 * 文件记天气、记咖啡、记任何他在意的东西；那些行是<b>数据</b>，不是噪音。丢掉它们等于删用户的字，
 * 而「一个字节都不静默丢」正是本文件模型存在的全部理由。
 *
 * <p><b>为什么列表里不带行尾换行符</b>：调用方几乎一定会用 {@code contains}/{@code equals}
 * 去找「用户写的那一行」，而带着 {@code "\n"} 的字符串永远不等于他看见的那一行（计划 1 的
 * {@code WeekRecord.bullets()} 就踩过这个坑）。回写时那个换行符由 {@link #toBlockText} 补回来，
 * 因此「逐字往返」不受影响。
 *
 * <p><b>本记录不校验文本字段里的换行符或竖线</b>：解析路径必须能如实承载文件里的任意内容
 * （哪怕用户手改成了畸形写法）。写入口的闸门在 {@link EmotionLog} 与界面层，那里才有拒绝的机会。
 *
 * <p><b>public 是硬要求</b>：计划 2 的 Task 9（时间窗选取与发送文本渲染）在 {@code ai} 包里
 * 消费它，界面层也消费它。
 *
 * @param date         日期，非 null（它与标题行是同一个身份的两面）
 * @param mood         情绪词，非 null（界面上是点选，必有一值）
 * @param intensity    强度 1..5；{@code null} 表示用户没有打分
 * @param sleep        睡眠，可选；{@code null} 表示文件里没有这一行
 * @param exercise     运动，可选；{@code null} 表示文件里没有这一行
 * @param event        生活事件，可选；{@code null} 表示文件里没有这一行
 * @param note         备注，可选；{@code null} 表示文件里没有这一行
 * @param unknownLines 条目里未识别的 {@code - } 行（含 {@code "- "} 前缀，不含行尾换行符），按原文顺序
 */
public record EmotionEntry(
        LocalDate date,
        String mood,
        Integer intensity,
        String sleep,
        String exercise,
        String event,
        String note,
        List<String> unknownLines) {

    /** 日期标题的形态：{@code ## 2026-10-10（周六）}。 */
    public static final String HEADING_PREFIX = "## ";

    /** 强度下界与上界（spec §6.2：1–5，由用户自己打分）。 */
    private static final int MIN_INTENSITY = 1;
    private static final int MAX_INTENSITY = 5;

    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * ISO-8601 星期序号（周一 = 1 … 周日 = 7）到中文单字的映射。
     *
     * <p>按 {@link DayOfWeek#getValue()} 直接取下标，而不是自己写一套 {@code switch}：星期序号
     * 的定义只有一处（{@link DayOfWeek}），两处各写一份迟早会错开一天——那种错会在文件里
     * 写出「2026-10-10（周日）」，字面上完全看不出来，最是难查。
     */
    private static final String[] WEEKDAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    /** 紧凑构造器：所有校验都在这里，任何构造路径都绕不过去。 */
    public EmotionEntry {
        Objects.requireNonNull(date, "date（条目日期不能为 null：它是标题行的身份）");
        Objects.requireNonNull(mood, "mood（情绪不能为 null：界面上是点选，必有一值）");
        if (intensity != null && (intensity < MIN_INTENSITY || intensity > MAX_INTENSITY)) {
            throw new IllegalArgumentException("强度只能是 " + MIN_INTENSITY + "–" + MAX_INTENSITY
                    + "，或 null（用户没打分）；收到：" + intensity
                    + "。不在这里拦，一个越界的分数就会写进用户的情绪记录。");
        }
        // 防御性复制：记录一旦造出就是不可变的，外部再动传入的 List 不会改变它
        unknownLines = List.copyOf(unknownLines);
    }

    /**
     * 文本字段是否「空到不必输出那一行」。
     *
     * <p>只把 {@code null} 与<b>长度为零</b>的串当空，<b>不当</b>修剪过的空白当空：
     * 用户写了一个空格也是一个字符，替他判定「这等于没写」就是改他的字。这条规则只有这一处
     * 定义，界面与回写都调它，免得两处各判一次而分岔。
     */
    public static boolean isBlankText(String text) {
        return text == null || text.isEmpty();
    }

    /**
     * 该日期的条目标题（不含行尾换行符），例如 {@code 2026-10-10（周六）}。
     *
     * <p>格式只有这一处定义：解析与生成共用它，才不会出现「App 写出的标题自己读不回来」。
     */
    public static String headingLabel(LocalDate date) {
        return date.format(DATE_PATTERN) + "（周" + WEEKDAY_NAMES[date.getDayOfWeek().getValue() - 1] + "）";
    }

    /**
     * 把本条目渲染成文件里的块文本（不含开头空行；末尾的间隔由 {@link EmotionLog} 按原文补）。
     *
     * <p>字段顺序固定：情绪（含 {@code ｜ 强度：N} 同行）、睡眠、运动、生活事件、备注，
     * 未知行排在<b>已知字段之后</b>并按原文顺序。空字段整行不输出（见 {@link #isBlankText}）。
     *
     * <p>{@code ｜} 是全角 U+FF5C，与主计划文件现有用法一致——写成半角竖线会让本文件与其它
     * 文件看起来「差不多但其实不同」，下游按分隔符切分的地方就会对不上。
     */
    public String toBlockText(String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADING_PREFIX).append(headingLabel(date)).append('\n').append('\n');
        sb.append("- 情绪：").append(mood);
        if (intensity != null) {
            sb.append(" ｜ 强度：").append(intensity);
        }
        sb.append('\n');
        appendField(sb, "睡眠", sleep);
        appendField(sb, "运动", exercise);
        appendField(sb, "生活事件", event);
        appendField(sb, "备注", note);
        for (String line : unknownLines) {
            sb.append(line).append('\n');
        }
        return sb.append(suffix).toString();
    }

    /** 有内容才输出这一行；{@code null} 与空串都当「没有这一行」（理由见 {@link #isBlankText}）。 */
    private static void appendField(StringBuilder sb, String label, String value) {
        if (!isBlankText(value)) {
            sb.append("- ").append(label).append('：').append(value).append('\n');
        }
    }
}
