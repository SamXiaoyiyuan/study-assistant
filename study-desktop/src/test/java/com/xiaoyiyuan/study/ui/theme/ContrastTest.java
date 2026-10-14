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

    // ---------------------------------------------------------------- 图表序列色 vs 绘图区

    @Test
    void chartSeriesColors_reach3To1AgainstThePlotArea_inBothThemes() {
        // 指南 §6.1 要求「图表数据线 vs 绘图区 ≥ 3:1」；绘图区底是 app.css 的
        // .chart-plot-background { -fx-background-color: -fx-c-surface; }。
        // 评审指出这条以前从未被断言（实测 3.27 / 3.80 达标，但属碰巧）。
        record Lowest(String chart, String value, String ratio) {
        }
        for (ThemeMode mode : ThemeMode.values()) {
            String plot = value(mode, ThemeTokens.SURFACE);
            Lowest lowest = null;
            for (int i = 1; i <= 8; i++) {
                String key = "CHART_COLOR_" + i;
                // 注意：不能用 tokens(mode)（那是 projectColors，只含 -fx-c-*）
                String v = CssTokens.byProperty("/css/tokens-"
                        + mode.name().toLowerCase(java.util.Locale.ROOT) + ".css").get(key);
                assertTrue(v != null, mode + " 主题的令牌文件里缺少 " + key);
                v = v.toUpperCase(java.util.Locale.ROOT);
                double ratio = WcagContrast.ratio(v, plot);
                assertTrue(ratio >= 3.0,
                        mode + " 主题：" + key + " (" + v + ") 对绘图区 " + ThemeTokens.SURFACE.key() + " (" + plot
                                + ") 必须 ≥ 3:1（指南 §6.1），实算 " + WcagContrast.fmt(ratio) + ":1");
                if (lowest == null || WcagContrast.fmt(ratio).compareTo(lowest.ratio()) < 0) {
                    lowest = new Lowest(key, v, WcagContrast.fmt(ratio));
                }
            }
            System.out.println("[evidence] " + mode + " 图表序列色对绘图区最低者 = " + lowest.chart()
                    + " " + lowest.value() + " = " + lowest.ratio() + ":1");
            // 评审独立重算的两个最低值（明 CHART_COLOR_7 #8F8CA0 = 3.27、暗 CHART_COLOR_7 #737A8E = 3.80）
            assertEquals(mode == ThemeMode.LIGHT ? "3.27" : "3.80", lowest.ratio(),
                    mode + " 主题：图表序列色对绘图区的最低值（评审独立重算的实测值）");
        }
    }

    // ---------------------------------------------------------------- 危机面板：正文必须落在 crisis-bg 上

    /** app.css 里某个选择器的某个属性值；解析不到就直接失败，不允许静默跳过。 */
    static String appCssValue(String selector, String property) {
        for (CssTokens.Decl d : CssTokens.parse("/css/app.css")) {
            if (d.selector().equals(selector) && d.property().equals(property)) {
                return d.rawValue();
            }
        }
        throw new AssertionError("app.css 的 " + selector + " 里找不到 " + property + " 声明");
    }

    /** 逗号分隔的层列表（{@code -fx-background-color} / {@code -fx-background-insets}）。 */
    static List<String> commaSeparated(String value) {
        return java.util.Arrays.stream(value.split(",")).map(String::trim).toList();
    }

    /** {@code "0 0 0 0.2em"} → {top,right,bottom,left}；1 值简写复制成 4 份，2 值按 CSS 规则展开。 */
    static List<String> fourSides(String value) {
        List<String> parts = java.util.Arrays.stream(value.trim().split("\\s+")).toList();
        return switch (parts.size()) {
            case 1 -> List.of(parts.get(0), parts.get(0), parts.get(0), parts.get(0));
            case 2 -> List.of(parts.get(0), parts.get(1), parts.get(0), parts.get(1));
            case 3 -> List.of(parts.get(0), parts.get(1), parts.get(2), parts.get(1));
            case 4 -> parts;
            default -> throw new AssertionError("不是合法的 CSS 四边值：" + value);
        };
    }

    static double em(String value) {
        assertTrue(value.endsWith("em"), "只接受 em 值，实际：" + value);
        return Double.parseDouble(value.substring(0, value.length() - 2));
    }

    @Test
    void crisisPanelTextIsAlwaysOnCrisisBg_andTheAccentBarIsOnlyANarrowLeftStrip() {
        // 这份 CSS 的两层背景：第 1 层（更底层）= 色条，第 2 层（更上层）= 面板底。
        // 本机实测（task-1-fix1-report.md 的探针输出）：**后声明的那层画在上面**，
        // 所以「正文落在哪层背景上」由第 2 层决定；两层 insets 相同时第 1 层会被完全盖住。
        assertEquals(List.of("-fx-c-crisis-bar", "-fx-c-crisis-bg"),
                commaSeparated(appCssValue(".crisis-panel", "-fx-background-color")),
                "app.css 的 .crisis-panel 必须把色条放在更底层、暖砂底放在更上层");

        List<String> insets = commaSeparated(appCssValue(".crisis-panel", "-fx-background-insets"));
        assertEquals(2, insets.size(), "两层背景必须各给一组 insets，实际 " + insets);
        assertEquals(List.of("0", "0", "0", "0"), fourSides(insets.get(0)),
                "色条层必须铺满整块面板（上层再把它裁成窄条），实际 " + insets.get(0));
        List<String> bodyInsets = fourSides(insets.get(1));
        assertEquals(List.of("0", "0", "0", "0.2em"), bodyInsets,
                "面板底层必须只从左内缩 0.2em（= 根字号 15px 下的 3 逻辑像素色条），其余三边铺满，实际 "
                        + insets.get(1));
        assertTrue(em(bodyInsets.get(3)) > 0,
                "面板底的左内缩必须 > 0，否则色条被完全盖住（实测色条消失、面板失去唯一可识别边界）");

        // 正文的左边距必须大于色条宽度：-fx-padding 必须写字面 em
        // （实测：`-fx-padding: -fx-sp-4 ...` 在 JavaFX 25 下抛 ClassCastException 后被忽略，见报告 N1）
        List<String> padding = fourSides(appCssValue(".crisis-panel", "-fx-padding"));
        assertTrue(padding.stream().noneMatch(v -> v.startsWith("-fx-")),
                ".crisis-panel 的 -fx-padding 必须写字面 em（-fx-sp-* 查找值对 -fx-padding 无效），实际 " + padding);
        assertEquals(List.of(ThemeTokens.nonColorValue("-fx-sp-4", ThemeMode.LIGHT),
                        ThemeTokens.nonColorValue("-fx-sp-4", ThemeMode.LIGHT),
                        ThemeTokens.nonColorValue("-fx-sp-4", ThemeMode.LIGHT),
                        ThemeTokens.nonColorValue("-fx-sp-5", ThemeMode.LIGHT)),
                padding,
                ".crisis-panel 的字面 em 内边距必须等于 -fx-sp-4 / -fx-sp-5（上右下 = sp-4，左 = sp-5），实际 " + padding);
        assertTrue(em(padding.get(3)) > em(bodyInsets.get(3)),
                "正文左内边距必须大于色条宽度，实际 padding-left=" + padding.get(3)
                        + " 色条=" + bodyInsets.get(3));

        // 正文只能落在 crisis-bg 上；色条若铺满，正文会掉到远低于 4.5:1
        for (ThemeMode mode : ThemeMode.values()) {
            String text = value(mode, ThemeTokens.CRISIS_TEXT);
            String meta = value(mode, ThemeTokens.CRISIS_META);
            String bg = value(mode, ThemeTokens.CRISIS_BG);
            String bar = value(mode, ThemeTokens.CRISIS_BAR);
            double textOnBg = WcagContrast.ratio(text, bg);
            double metaOnBg = WcagContrast.ratio(meta, bg);
            double textOnBar = WcagContrast.ratio(text, bar);
            double metaOnBar = WcagContrast.ratio(meta, bar);
            System.out.println("[evidence] " + mode + " 危机面板：text on bg=" + WcagContrast.fmt(textOnBg)
                    + " meta on bg=" + WcagContrast.fmt(metaOnBg)
                    + " | 若色条铺满：text on bar=" + WcagContrast.fmt(textOnBar)
                    + " meta on bar=" + WcagContrast.fmt(metaOnBar));

            assertTrue(textOnBg >= 4.5,
                    mode + " 主题：正文 " + text + " 于面板底 " + bg + " 必须 ≥ 4.5:1，实算 "
                            + WcagContrast.fmt(textOnBg) + ":1");
            assertTrue(metaOnBg >= 4.5,
                    mode + " 主题：元信息 " + meta + " 于面板底 " + bg + " 必须 ≥ 4.5:1，实算 "
                            + WcagContrast.fmt(metaOnBg) + ":1");
            assertTrue(textOnBar < 4.5 && metaOnBar < 4.5,
                    mode + " 主题：色条色本身绝不能当正文底（实测 text=" + WcagContrast.fmt(textOnBar)
                            + ":1 meta=" + WcagContrast.fmt(metaOnBar) + ":1，都远低于 4.5）——"
                            + "所以上面两条 insets/padding 断言不是形式主义，它们正是这条红线的唯一守卫");
        }
    }

    // ---------------------------------------------------------------- 指南的转述数字：自算值与它不一致的地方如实记录

    @Test
    void guidelineTranscribedNumbers_thatDisagreeWithMyOwnComputation_areExactlyThese() {
        // 这条断言的含义是「自算值与指南一致的部分，必须逐位一致」：将来任何颜色被改动，就会红。
        // 指南里有几行的转述数字与本机实算**不符**，它们不进这张表，而是单独列在下面：
        //   ① 指南 §6.1 说暗色 border-subtle 1.25:1，本机实算 1.38:1（指南的 1.25 是对 surface #1E2027）
        //   ② 指南 §6.1 说明色 border-subtle 1.29:1，本机实算 1.20:1（指南的 1.29 是对 surface #FFFFFF）
        //      —— 2026-10-14 fix round 1 已把指南这两处改成「对页底」的数字，并把这两个自算值钉在这里
        //   ③ 指南 §6.1 说「图表线 vs 绘图区 暗 8.33:1」，本机实算 7.57:1（指南的 8.33 是对**页底** #16171C；
        //      绘图区是 -fx-c-surface，所以正确值是 7.57）—— 指南已改正，这里钉自算值
        //   ④ 指南未给暗色 text-tertiary 于次级面的数字，本机实算 4.74:1（仍 ≥ 4.5:1）
        //   ⑤ 指南 §1.1 说暗色 notice-text 于提示底 9.83:1 —— 这条本机实算**就是 9.83**，
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
                new Sample("border-control on surface", ThemeMode.DARK, "-fx-c-border-control", "-fx-c-surface", "3.80"),
                // 2026-10-14 fix round 1 修正后的指南 §6.1 两条（对页底 / 对绘图区）
                new Sample("state-active on surface（绘图区）", ThemeMode.DARK, "-fx-c-state-active", "-fx-c-surface", "7.57"),
                new Sample("border-subtle on bg（指南已把 1.29 改为对页底的 1.20）", ThemeMode.LIGHT,
                        "-fx-c-border-subtle", "-fx-c-bg", "1.20"));
        for (Sample s : samples) {
            String fg = tokens(s.mode()).get(s.fgKey());
            String bg = tokens(s.mode()).get(s.bgKey());
            double actual = WcagContrast.ratio(fg, bg);
            assertEquals(s.computed(), WcagContrast.fmt(actual),
                    s.label() + "（" + s.mode() + "）：本机自算 " + fg + " on " + bg + " = "
                            + WcagContrast.fmt(actual) + ":1");
        }

        // 与指南**修正前**不符的条目，明确钉住「本机自算值是多少」，免得被误当成抄写错误改回去。
        // ① 暗色 border-subtle 对页底 = 1.38（指南修正前写 1.25，那个数字是对 surface）
        // ② 明色 border-subtle 对页底 = 1.20（指南修正前写 1.29，那个数字是对 surface）
        assertEquals("1.38", WcagContrast.fmt(WcagContrast.ratio(
                tokens(ThemeMode.DARK).get("-fx-c-border-subtle"), tokens(ThemeMode.DARK).get("-fx-c-bg"))),
                "暗色 border-subtle 对页底的实算值（装饰性元素，无对比度要求）");
        assertEquals("1.20", WcagContrast.fmt(WcagContrast.ratio(
                tokens(ThemeMode.LIGHT).get("-fx-c-border-subtle"), tokens(ThemeMode.LIGHT).get("-fx-c-bg"))),
                "明色 border-subtle 对页底的实算值（1.29 是对 surface #FFFFFF，不是对页底）");
        // ③ 暗色图表线对**绘图区** = 7.57（指南修正前写 8.33，那个数字是对页底 #16171C）
        assertEquals("7.57", WcagContrast.fmt(WcagContrast.ratio(
                tokens(ThemeMode.DARK).get("-fx-c-state-active"), tokens(ThemeMode.DARK).get("-fx-c-surface"))),
                "暗色图表数据线对绘图区（-fx-c-surface）的实算值（8.33 是对页底 -fx-c-bg）");
        assertEquals("8.33", WcagContrast.fmt(WcagContrast.ratio(
                tokens(ThemeMode.DARK).get("-fx-c-state-active"), tokens(ThemeMode.DARK).get("-fx-c-bg"))),
                "同一个暗色图表线对**页底**才是 8.33——这两个数字必须分开记，混用就是这次 fix 的根因");
        assertEquals("4.74", WcagContrast.fmt(WcagContrast.ratio(
                tokens(ThemeMode.DARK).get("-fx-c-text-tertiary"), tokens(ThemeMode.DARK).get("-fx-c-surface-muted"))),
                "暗色 text-tertiary 于次级面：指南未给数字，本机实算是这个（仍 ≥ 4.5:1）");
    }
}
