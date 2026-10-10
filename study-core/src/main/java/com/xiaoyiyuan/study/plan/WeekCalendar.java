package com.xiaoyiyuan.study.plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 周界与周序号的纯日期计算（spec §5.6）。
 *
 * <p>周界固定为<b>周一–周日</b>，第 1 周锚点是 {@code 2026-09-14}–{@code 2026-09-20}，
 * 与用户档案里已经写死的周标题一致。本类只有静态方法、不做任何 I/O，因此可以被
 * 后续的周签、阶段排期与界面直接消费。
 *
 * <p><b>为什么整类都是 {@code public static} 且类为 {@code final}</b>：这是领域日历规则，
 * 不是某个对象的实例状态。Task 6 起的 {@code plan} 包实现、以及界面层都在别的包里调用它，
 * 包级私有会让它们够不着；而允许被继承会让人「重写一个自己版本的周界」，
 * 那正是本项目最不能出的错（周序号一旦算错，历史数据会被归到错误的周）。
 */
public final class WeekCalendar {

    /**
     * 第 1 周的周一，全项目周序号的共同参照点。
     *
     * <p>已核实 {@code 2026-09-14} 是周一。它是<b>唯一</b>的锚点：其它任何日期都只通过
     * 「与它相差几个整周」来定周号，绝不允许出现第二个参照点。
     */
    public static final LocalDate ANCHOR_MONDAY = LocalDate.of(2026, 9, 14);

    /** 一周七天；给 {@link #weekNumber(LocalDate)} 与 {@link #weekStart(int)} 共用，避免两处各写一个 7。 */
    private static final int DAYS_PER_WEEK = 7;

    /** 工具类，不实例化。 */
    private WeekCalendar() {
    }

    /**
     * 取该日期所在周的周一。
     *
     * <p>公式：{@code d − ((dayOfWeek(d) + 6) mod 7)}。这里依赖
     * {@link DayOfWeek#getValue()} 是 <b>ISO-8601</b> 编号（周一 = 1 … 周日 = 7）——
     * 若按 {@link java.util.Calendar} 那套「周日 = 1」的老习惯来算，周界会整体错一天。
     * 两个边界情形已在测试中钉死：周一 {@code (1+6)%7 = 0} 原地不动，
     * 周日 {@code (7+6)%7 = 6} 往回退到本周周一。
     */
    public static LocalDate mondayOf(LocalDate d) {
        return d.minusDays((d.getDayOfWeek().getValue() + 6) % DAYS_PER_WEEK);
    }

    /**
     * 取该日期所在周的周日。
     *
     * <p>刻意写成「周一 + 6 天」而不是「周一 − 1 天」之类的另一个公式：周界只能有一套定义，
     * 两处各算一次迟早会有一天对不上。
     */
    public static LocalDate sundayOf(LocalDate d) {
        return mondayOf(d).plusDays(DAYS_PER_WEEK - 1);
    }

    /**
     * 该日期属于第几周（锚点所在周为第 1 周）。
     *
     * <p><b>为什么仍要写 {@link Math#floorDiv} 而不是 {@code /}</b>：实测过一件事，必须说清楚，
     * 否则下一个人会以为这里有个测试守着的坑，其实没有——{@link #mondayOf(LocalDate)} 返回的
     * 必然是周一，于是「锚点 → 该周一」的天数差永远是 7 的整数倍，<b>在这个输入域里整除与
     * floorDiv 结果完全一样</b>（用 {@code /} 实跑过全部用例：61 个测试无一阵亡，全绿）。
     * 但整除在这里是错的语义：Java 的 {@code /} 向零取整，一旦以后有人把被除数换成
     * 「与锚点的原始天数差」（非 7 的倍数、负的天数），它就会把锚点前的日期算成偏大一岁的结果。
     * floorDiv 是「第几周」这个量本该有的定义，留着它是为了让正确的写法成为唯一写得出来的写法。
     *
     * <p>周号只由「与锚点相差几个整周」决定，因此 {@code weekNumber(weekStart(w)) == w}
     * 对任何 w 都成立（测试里 1..60 全程往返验证，并有锚点前的第 0 / 负周用例）。
     */
    public static int weekNumber(LocalDate d) {
        long daysFromAnchor = ChronoUnit.DAYS.between(ANCHOR_MONDAY, mondayOf(d));
        // 除数必须显式写成 long：Math.floorDiv 只有 (int,int) 与 (long,long) 两个重载，
        // Java 不会为方法实参做拓宽，传 (long, int) 会解析失败。结果落回 int 是安全的——
        // 天数差要到 ±20 亿天才可能溢出，那已经在 LocalDate 的范围之外。
        return (int) Math.floorDiv(daysFromAnchor, (long) DAYS_PER_WEEK) + 1;
    }

    /**
     * 第 {@code weekNumber} 周的周一（{@link #weekNumber(LocalDate)} 的逆运算）。
     *
     * <p>直接复用同一套「周号 ↔ 周界」定义，而不是另写一个分支公式：往返自洽
     * （{@code weekNumber(weekStart(w)) == w}）正是靠这一点保证的。
     */
    public static LocalDate weekStart(int weekNumber) {
        return ANCHOR_MONDAY.plusDays((long) (weekNumber - 1) * DAYS_PER_WEEK);
    }

    /**
     * 该周的显示标签，形如 {@code 2026-10-05 – 2026-10-11}。
     *
     * <p><b>中间必须是短破折号 {@code –}（U+2013），两边各一个空格。</b>这不是排版偏好：
     * 用户档案里的周标题就是这么写的，用连字符 {@code -} 生成出来的标签与文档对不上，
     * 界面与文件会出现两种「看起来一样」的周标题。改这里之前先去看档案原文。
     */
    public static String weekRangeLabel(int weekNumber) {
        LocalDate start = weekStart(weekNumber);
        return start + " – " + start.plusDays(DAYS_PER_WEEK - 1);
    }
}
