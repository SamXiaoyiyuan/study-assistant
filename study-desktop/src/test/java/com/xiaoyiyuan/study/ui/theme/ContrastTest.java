package com.xiaoyiyuan.study.ui.theme;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>性质 3 + 性质 4</b>：四态中性色必须<b>存在且互不相同</b>，每个都 ≥ 4.5:1；
 * 明暗两套主题都必须提供正文 ≥ 4.5:1 的令牌。
 *
 * <p>对比度是<b>测试现算</b>的（{@link WcagContrast}），不是抄指南里的转述数字；算出来的数字写进断言消息，
 * 失败时能直接看到「哪个令牌、哪个底色、多少比多少」。角色 → 令牌的选择也钉在这里，
 * 换错令牌（例如把「已完成」换成红色系的某个值）会同时红在「值不一致」「对比度」「禁用色审计」三条上。</p>
 */
class ContrastTest {

    /** 四态 + 「待回报」的令牌名（出现顺序与 spec §5.2 的四态一致）。 */
    static final List<ThemeTokens> FOUR_STATES = List.of(
            ThemeTokens.STATE_ACTIVE, ThemeTokens.STATE_DONE,
            ThemeTokens.STATE_PAUSED, ThemeTokens.STATE_CANCELLED);

    static Map<String, String> tokens(ThemeMode mode) {
        return CssTokens.projectColors("/css/tokens-" + mode.name().toLowerCase(java.util.Locale.ROOT) + ".css");
    }

    static String value(ThemeMode mode, ThemeTokens token) {
        String v = tokens(mode).get(token.key());
        assertTrue(v != null, "令牌 " + token.key() + " 在 " + mode + " 的 CSS 里不存在");
        return v.toUpperCase(java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------- 对比度引擎自检

    @Test
    void wcagEngine_matchesHandComputedReferencePoints() {
        // 黑白 21:1、同色 1:1 是 WCAG 的两个端点，写死它们是为了证明公式没写反
        assertEquals(21.0, WcagContrast.ratio("#000000", "#FFFFFF"), 0.005,
                "黑白对比度必须是 21:1，实际 " + WcagContrast.fmt(WcagContrast.ratio("#000000", "#FFFFFF")));
        assertEquals(1.0, WcagContrast.ratio("#4A55A2", "#4A55A2"), 1e-9,
                "同色对比度必须是 1:1");
        assertEquals(0.2126 * 1.0 + 0.7152 * 1.0 + 0.0722 * 1.0, WcagContrast.relativeLuminance("#FFFFFF"), 1e-9,
                "白色的相对亮度必须是 1.0");
        // 明色主色 #4A55A2 对页底 #F7F6FA：本机实算 6.27
        assertEquals(6.27, WcagContrast.ratio("#4A55A2", "#F7F6FA"), 0.005,
                "本机实算 #4A55A2 on #F7F6FA = " + WcagContrast.fmt(WcagContrast.ratio("#4A55A2", "#F7F6FA")));
    }

    // ---------------------------------------------------------------- 性质 4：正文

    @Test
    void bodyTextTokens_reachAtLeast4_5To1_onEveryBackgroundTheySitOn_inBothThemes() {
        record Pair(ThemeTokens text, ThemeTokens background) {
        }
        List<Pair> pairs = List.of(
                new Pair(ThemeTokens.TEXT, ThemeTokens.BG),
                new Pair(ThemeTokens.TEXT, ThemeTokens.SURFACE),
                new Pair(ThemeTokens.TEXT, ThemeTokens.SURFACE_MUTED),
                new Pair(ThemeTokens.TEXT_SECONDARY, ThemeTokens.BG),
                new Pair(ThemeTokens.TEXT_SECONDARY, ThemeTokens.SURFACE),
                new Pair(ThemeTokens.TEXT_TERTIARY, ThemeTokens.BG),
                new Pair(ThemeTokens.TEXT_TERTIARY, ThemeTokens.SURFACE),
                new Pair(ThemeTokens.TEXT_TERTIARY, ThemeTokens.SURFACE_MUTED),
                new Pair(ThemeTokens.NOTICE_TEXT, ThemeTokens.NOTICE_BG),
                new Pair(ThemeTokens.CRISIS_TEXT, ThemeTokens.CRISIS_BG),
                new Pair(ThemeTokens.CRISIS_META, ThemeTokens.CRISIS_BG),
                new Pair(ThemeTokens.ACCENT, ThemeTokens.BG),
                new Pair(ThemeTokens.ACCENT, ThemeTokens.SURFACE),
                new Pair(ThemeTokens.ON_ACCENT, ThemeTokens.ACCENT),
                new Pair(ThemeTokens.STATE_AWAIT, ThemeTokens.SURFACE),
                new Pair(ThemeTokens.STATE_AWAIT, ThemeTokens.BG));

        for (ThemeMode mode : ThemeMode.values()) {
            for (Pair p : pairs) {
                String fg = value(mode, p.text());
                String bg = value(mode, p.background());
                double ratio = WcagContrast.ratio(fg, bg);
                assertTrue(ratio >= 4.5,
                        mode + " 主题：" + p.text().key() + " (" + fg + ") 在 " + p.background().key() + " (" + bg
                                + ") 上的对比度必须 ≥ 4.5:1，实算 " + WcagContrast.fmt(ratio) + ":1");
            }
        }
    }

    // ---------------------------------------------------------------- 性质 3：四态

    @Test
    void fourStateTokens_existAreMutuallyDistinctAndEachReaches4_5To1() {
        for (ThemeMode mode : ThemeMode.values()) {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            for (ThemeTokens t : FOUR_STATES) {
                String v = value(mode, t);
                values.put(t.key(), v);
                double onBg = WcagContrast.ratio(v, value(mode, ThemeTokens.BG));
                double onSurface = WcagContrast.ratio(v, value(mode, ThemeTokens.SURFACE));
                double onMuted = WcagContrast.ratio(v, value(mode, ThemeTokens.SURFACE_MUTED));
                assertTrue(onBg >= 4.5 && onSurface >= 4.5 && onMuted >= 4.5,
                        mode + " 主题：" + t.key() + " (" + v + ") 的对比度必须 ≥ 4.5:1；实算 bg="
                                + WcagContrast.fmt(onBg) + ":1 surface=" + WcagContrast.fmt(onSurface)
                                + ":1 muted=" + WcagContrast.fmt(onMuted) + ":1");
            }
            assertEquals(FOUR_STATES.size(), new java.util.HashSet<>(values.values()).size(),
                    mode + " 主题：四态令牌必须互不相同，实际值 " + values);
        }
    }

    @Test
    void fourStateTokens_areTheSameNamesInBothThemes_andDifferInValue() {
        for (ThemeTokens t : FOUR_STATES) {
            String light = value(ThemeMode.LIGHT, t);
            String dark = value(ThemeMode.DARK, t);
            assertTrue(!light.equals(dark),
                    t.key() + " 明暗两套必须不同值（明暗切换不能是同一份值）：light=" + light + " dark=" + dark);
        }
    }

    @Test
    void stateAwait_hasNoOwnHue_butItsEdgeIsVisibleOnSurfaceAndBackground() {
        for (ThemeMode mode : ThemeMode.values()) {
            String line = value(mode, ThemeTokens.STATE_AWAIT_LINE);
            double onSurface = WcagContrast.ratio(line, value(mode, ThemeTokens.SURFACE));
            double onBg = WcagContrast.ratio(line, value(mode, ThemeTokens.BG));
            assertTrue(onSurface >= 3.0 && onBg >= 3.0,
                    mode + " 主题：「待回报」虚线边 " + line + " 必须 ≥ 3:1（形状可辨识）；实算 surface="
                            + WcagContrast.fmt(onSurface) + ":1 bg=" + WcagContrast.fmt(onBg) + ":1");
        }
    }

    // ---------------------------------------------------------------- 指南的转述数字：自算值与它不一致的地方如实记录

    @Test
    void guidelineTranscribedNumbers_thatDisagreeWithMyOwnComputation_areExactlyThese() {
        // 这条断言的含义是「自算值与指南一致的部分，必须逐位一致」：将来任何颜色被改动，就会红。
        // 指南里有三行的转述数字与本机实算**不符**，它们不进这张表，而是单独列在下面：
        //   ① 指南 §6.1 说暗色 border-subtle 1.25:1，本机实算 1.38:1
        //   ② 指南未给暗色 text-tertiary 于次级面的数字，本机实算 4.74:1（仍 ≥ 4.5:1）
        //   ③ 指南 §1.1 说暗色 notice-text 于提示底 9.83:1 —— 这条本机实算**就是 9.83**，
        //      与 §6.1 里 accent-hover 的 9.83 重复只是同一个巧合，不是抄写错误
        //      （第一版测试里把期望写成 11.42 是我自己的笔误，已被这次实跑纠正）
        record Sample(String label, ThemeMode mode, String fgKey, String bgKey, String computed) {
        }
        List<Sample> samples = List.of(
                new Sample("notice-text on notice-bg", ThemeMode.DARK, "-fx-c-notice-text", "-fx-c-notice-bg", "9.83"),
                new Sample("text on bg", ThemeMode.LIGHT, "-fx-c-text", "-fx-c-bg", "14.84"),
                new Sample("text-secondary on bg", ThemeMode.LIGHT, "-fx-c-text-secondary", "-fx-c-bg", "6.33"),
                new Sample("accent on bg", ThemeMode.LIGHT, "-fx-c-accent", "-fx-c-bg", "6.27"),
                new Sample("on-accent on accent", ThemeMode.LIGHT, "-fx-c-on-accent", "-fx-c-accent", "6.75"),
                new Sample("accent on bg", ThemeMode.DARK, "-fx-c-accent", "-fx-c-bg", "8.77"),
                new Sample("focus on bg", ThemeMode.DARK, "-fx-c-focus", "-fx-c-bg", "7.80"),
                new Sample("crisis-text on crisis-bg", ThemeMode.LIGHT, "-fx-c-crisis-text", "-fx-c-crisis-bg", "8.61"),
                new Sample("crisis-text on crisis-bg", ThemeMode.DARK, "-fx-c-crisis-text", "-fx-c-crisis-bg", "11.08"),
                new Sample("crisis-bar on crisis-bg", ThemeMode.LIGHT, "-fx-c-crisis-bar", "-fx-c-crisis-bg", "4.79"),
                new Sample("border-control on surface", ThemeMode.LIGHT, "-fx-c-border-control", "-fx-c-surface", "3.27"),
                new Sample("border-control on surface", ThemeMode.DARK, "-fx-c-border-control", "-fx-c-surface", "3.80"));
        for (Sample s : samples) {
            String fg = tokens(s.mode()).get(s.fgKey());
            String bg = tokens(s.mode()).get(s.bgKey());
            double actual = WcagContrast.ratio(fg, bg);
            assertEquals(s.computed(), WcagContrast.fmt(actual),
                    s.label() + "（" + s.mode() + "）：本机自算 " + fg + " on " + bg + " = "
                            + WcagContrast.fmt(actual) + ":1");
        }

        // 与指南不符的两条，明确钉住「本机自算值是多少」，免得被误当成抄写错误改回去
        assertEquals("1.38", WcagContrast.fmt(WcagContrast.ratio(
                tokens(ThemeMode.DARK).get("-fx-c-border-subtle"), tokens(ThemeMode.DARK).get("-fx-c-bg"))),
                "指南 §6.1 说暗色 border-subtle 1.25:1，本机实算是这个数（装饰性元素，无对比度要求）");
        assertEquals("4.74", WcagContrast.fmt(WcagContrast.ratio(
                tokens(ThemeMode.DARK).get("-fx-c-text-tertiary"), tokens(ThemeMode.DARK).get("-fx-c-surface-muted"))),
                "暗色 text-tertiary 于次级面：指南未给数字，本机实算是这个（仍 ≥ 4.5:1）");
    }
}
