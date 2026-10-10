package com.xiaoyiyuan.study.plan;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 一条周记录：周序号、它覆盖的日期区间、原始标题行、以及该周下面的项目符号行（spec §5.7）。
 *
 * <p>这是<b>从文件里读出来的东西</b>，不是「应当长什么样」的模板：{@code heading} 保留文件里的
 * 原文，日期也按原文解析。这样界面可以把用户手写的标题原样显示，而不会把「档案里怎么写的」
 * 与「App 会怎么写」混为一谈——两者不一致时（用户改过日期、或改了全角括号）必须看得见。
 *
 * <p>{@code bullets} 里的每一项都<b>保留 {@code "- "} 前缀</b>，是整行原文。刻意不拆成
 * 「前缀 + 内容」：周记录里的项目行将来可能被界面直接编辑后写回，一旦拆开又要负责把它拼回去，
 * 而拼回去的写法（缩进、前缀后有几个空格）未必等于用户原来那一行——那正是本项目不允许的字节漂移。
 *
 * @param weekNumber 第几周（锚点周 2026-09-14 起算，见 {@link WeekCalendar}）
 * @param start      该周周一（按标题里的日期解析，不做「按周序号反推」）
 * @param end        该周周日
 * @param heading    标题整行原文（不含行尾换行符），例如
 *                   {@code ### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）}
 * @param bullets    该标题之后、下一个标题之前的 {@code - } 行，含前缀，按文件顺序
 */
public record WeekRecord(int weekNumber, LocalDate start, LocalDate end, String heading, List<String> bullets) {

    /**
     * 紧凑构造器：只做两件事——拒绝 {@code null}、把 {@code bullets} 拷成不可变列表。
     *
     * <p>为什么必须拷贝（这不是形式主义）：record 默认只是把引用存下来，而
     * {@code parseWeeks} 返回给界面的列表若与解析过程中的可变列表共用同一个底层数组，
     * 界面一次「顺手排序」就会改到别人手里的对象。这里拷一次，{@link WeekRecord} 就是真正的
     * 值对象：拿到它的人可以随便读，读不出坑。
     */
    public WeekRecord {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(heading, "heading");
        bullets = List.copyOf(Objects.requireNonNull(bullets, "bullets"));
    }
}
