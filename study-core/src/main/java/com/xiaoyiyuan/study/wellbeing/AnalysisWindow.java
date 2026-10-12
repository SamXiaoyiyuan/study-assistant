package com.xiaoyiyuan.study.wellbeing;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次情绪 AI 分析的<b>时间窗</b>（spec §6.4 第 1 步：近 7 天 / 近 30 天 / 自定义区间）。
 *
 * <h2>含两端</h2>
 * {@code from} 与 {@code to} <b>都算在窗口里</b>。{@code lastDays(today, 7)} 因此是
 * {@code today.minusDays(6)} 到 {@code today}——共 7 天，不是 8 天，也不是 6 天。
 * 这条不是随手定的：「近 7 天」在用户心里就是「今天和之前六天」，差一天会让他在预览里
 * 看到一条自己以为不该发的记录（或看不到一条以为会发的），而那时他已经点过「同意」了。
 *
 * <h2>非法窗口当场拒绝，不静默当成空窗</h2>
 * {@code from > to} 抛 {@link IllegalArgumentException}（消息里带两个端点）。
 * 静默当成「空窗」是最坏的一种做法：界面会显示「将发送 0 条记录」，
 * 用户以为这几天真的没记录——而实际上是他把区间填反了。
 *
 * <h2>本类不碰文件系统</h2>
 * 没有 {@code Path}、没有 {@code Files}。它只从一个已经解析好的 {@link EmotionLog} 里挑条目。
 * spec §6.4 说得很清楚：分析结果<b>不写回</b>情绪记录文件，所以这个模块从形态上就不该有写入口。
 *
 * <h2>公开面是计划 3 的契约</h2>
 * 计划 3 的趋势页会用 {@link #from()} / {@link #to()} 逐日画点、用 {@link #label()} 写中立文案，
 * 用 {@link #select(EmotionLog)} 取「这一段里到底记了哪几天」。这些方法因此都必须是 public，
 * 而不是包内可见。
 *
 * @param from 窗口起始日（含），非 null
 * @param to   窗口结束日（含），非 null，且不得早于 {@code from}
 */
public record AnalysisWindow(LocalDate from, LocalDate to) {

    /** 紧凑构造器：任何造出时间窗的路径都绕不过端点校验。 */
    public AnalysisWindow {
        Objects.requireNonNull(from, "from（时间窗的起始日不能为 null）");
        Objects.requireNonNull(to, "to（时间窗的结束日不能为 null）");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "时间窗的起始日不能晚于结束日：收到 " + from + " ~ " + to
                            + "。不把它当成一个空窗口——那样界面会显示「将发送 0 条记录」，"
                            + "用户会以为这几天真的没记录。");
        }
    }

    /**
     * 「今天往前数 {@code days} 天」的窗口，<b>含两端</b>。
     *
     * <p>{@code lastDays(today, 7)} = {@code [today-6, today]}；
     * {@code lastDays(today, 1)} = {@code [today, today]}。
     *
     * @param today 窗口的结束日（通常是「今天」）
     * @param days  天数，必须 ≥ 1
     * @throws IllegalArgumentException {@code days < 1}——「0 天」或负数不是一个时间窗，
     *                                  静默返回空窗会让用户以为「这几天没记录」
     */
    public static AnalysisWindow lastDays(LocalDate today, int days) {
        Objects.requireNonNull(today, "today（时间窗要有一个结束日）");
        // 天数校验必须排在最前，而且理由要说「天数」：把它放到构造器后面靠 from>to 兜住，
        // 抛出来的会是「起始日不能晚于结束日」——那是回绕出来的假象，会把用户引到错误的方向
        // （评审 V4：days=0 时窗口回绕成 [明天, 今天]，消息与 javadoc 承诺的完全不是一回事）。
        if (days < 1) {
            throw new IllegalArgumentException(
                    "时间窗的天数必须是至少 1 天的整数，收到 " + days
                            + " 天。这里既不返回一个空窗口（会被读成「这几天没有记录」），"
                            + "也不靠窗口回绕之后拿两个端点的大小关系当理由——那描述的不是真正的问题。");
        }
        return new AnalysisWindow(today.minusDays(days - 1L), today);
    }

    /**
     * 本窗口内的情绪记录，<b>保持文件顺序（最新在前）</b>；窗口内没有记录时返回空列表。
     *
     * <p>这是「只发用户选定的那一窗」这条隐私约束的唯一落点：窗口外的条目<b>一条都不返回</b>，
     * 于是它们连进入待发送文本的机会都没有（而不是「拼进去之后再过滤掉」——那种写法迟早会漏一处）。
     *
     * @param log 已经解析好的情绪记录；非 null
     * @return 不可变列表（快照）；两端日期都算在窗口里
     */
    public List<EmotionEntry> select(EmotionLog log) {
        Objects.requireNonNull(log, "log（时间窗要从一份已解析的记录里挑条目）");
        List<EmotionEntry> selected = new ArrayList<>();
        for (EmotionEntry entry : log.entries()) {
            LocalDate date = entry.date();
            if (!date.isBefore(from) && !date.isAfter(to)) {
                selected.add(entry);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 中立的窗口文案，例如 {@code 2026-10-04 – 2026-10-10}。
     *
     * <p>刻意用 en dash 相连的纯日期串，不写「近 7 天」之类的推断：本类不知道自己是怎么被造出来的
     * （自定义区间与 {@code lastDays} 造出来的窗口在这里长得一样），而确认页上要显示的正是
     * 「实际会发哪一段」。界面需要「近 7 天」那种说法时由界面自己加。
     */
    public String label() {
        return from + " – " + to;
    }

    /** 窗口内的天数（含两端），供界面显示「这个区间共 N 天」。 */
    public long dayCount() {
        return to.toEpochDay() - from.toEpochDay() + 1;
    }
}
