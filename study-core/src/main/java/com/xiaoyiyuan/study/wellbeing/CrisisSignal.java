package com.xiaoyiyuan.study.wellbeing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一次危机判定的结果（spec §6.5、计划 2 Task 3）。
 *
 * <p><b>只装事实，不装文案。</b>它说的只有两件事：是否命中、命中了哪些词。
 * 面板上的每一句用户可见的话都由<b>界面</b>渲染（计划 3），并且必须中性：无红色、
 * 无「警告」、不追问、不评判。判定层一旦开始生成文案，这句约束就散落成两处实现，
 * 迟早有一处漂移成「你看起来很糟糕」。
 *
 * <p><b>命中词保持输入原文的字面</b>（只做去空白、按 {@link Locale#ROOT} 折小写）。
 * 界面因此能如实告诉用户「是哪个词触发了这个面板」——透明换来信任，
 * 也换来用户知道「下次怎么写可以不触发」的可能。重写成别的说法就做不到了。
 *
 * <p>{@code null} 列表归一化为空列表，元素里的 {@code null} 与空白项直接丢弃：
 * 「没命中」只有「空列表」一种表示，调用方不必写两处判空。
 *
 * <p><b>刻意没有「阻止继续」这一类字段。</b>spec §6.5 的关键决定是「命中不阻断 AI」：
 * 不锁定、不中断、不强制。若将来有人想加 {@code blocked()}，请先读那一节——
 * 一旦形成「说了真话 App 就锁死」的体验，下次用户就开始写假的，
 * 保护机制不能以让人闭嘴为代价。
 *
 * <p>列表是 {@link List#copyOf} 出来的，不可变、防御性拷贝：判定之后没人能改这次的结果。
 *
 * @param triggered       是否命中任一关键词；命中即为 {@code true}，与命中词的多少无关
 * @param matchedKeywords 命中的关键词，按它们在关键词表里的顺序；
 *                        无命中时为空列表；元素是输入原文里的字面，不是判定层生成的词
 */
public record CrisisSignal(boolean triggered, List<String> matchedKeywords) {

    /**
     * 紧凑构造器：任何构造路径都绕不过这次归一化。
     *
     * <p>元素级的 {@code null} 也在这里清掉——若放过去，界面渲染命中词时会得到字面的
     * {@code "null"}，或者一处难以定位的 NPE。
     */
    public CrisisSignal {
        List<String> kept = new ArrayList<>();
        if (matchedKeywords != null) {
            for (String k : matchedKeywords) {
                if (k != null && !k.isBlank()) {
                    kept.add(k.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        matchedKeywords = List.copyOf(kept);
    }

    /** 便捷判定：{@code !triggered()}。它只表达事实，不表达任何「该怎么办」。 */
    public boolean none() {
        return !triggered;
    }

    /** 便于定位失败：把命中词原样带出来，而不是打印一个对象地址。 */
    @Override
    public String toString() {
        return "CrisisSignal[triggered=" + triggered + ", matchedKeywords=" + matchedKeywords + "]";
    }
}
