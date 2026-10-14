package com.xiaoyiyuan.study.ui.theme;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>护栏 2（禁用色审计）</b>与<b>护栏 5（DPI 安全）</b>。
 *
 * <p>禁用色审计覆盖三层：① 令牌名不得出现 danger/error/warning/success/critical；
 * ② 令牌值不得落在指南 §4.1 的禁用色相区（红 345°–20°、橙 20°–45°、高饱和绿 90°–160°，均以饱和度阈值判定），
 * 也不得出现 Modena 默认图表橙红；③ <b>JavaFX 的默认图表色 {@code CHART_COLOR_1..8} 必须在两套主题里都被覆盖</b>
 * （Modena 的 {@code CHART_COLOR_1} 是橙红）。</p>
 *
 * <p>DPI 审计：令牌文件里 <b>只允许一处 px</b>（{@code .root} 的 {@code -fx-font-size}），其余一律 em；
 * 并且不得出现把控件锁死在 320 逻辑像素以上的宽度写法。</p>
 */
class ColorAuditTest {

    static final List<String> TOKENS_CSS = List.of("/css/tokens-light.css", "/css/tokens-dark.css");
    static final List<String> ALL_CSS = List.of("/css/tokens-light.css", "/css/tokens-dark.css", "/css/app.css");

    /** JavaFX/Modena 的默认图表首色（橙红），指南 §4.2 点名。用码点拼接避免本文件自己被扫。 */
    static final String MODENA_CHART_1 = "#" + "f3622d";

    /** 指南 §4.1 明令禁止的语义令牌名（大小写不敏感）。 */
    static final List<String> FORBIDDEN_SEMANTIC_NAMES =
            List.of("danger", "error", "warning", "success", "critical");

    // ================================================================ ① 令牌名

    @Test
    void tokenFiles_doNotDefineAnyForbiddenSemanticTokenName() {
        for (String css : TOKENS_CSS) {
            String lower = CssTokens.read(css).toLowerCase(Locale.ROOT);
            for (String word : FORBIDDEN_SEMANTIC_NAMES) {
                assertFalse(lower.contains("-fx-c-" + word),
                        css + " 不得定义 -fx-c-" + word + "* 令牌（指南 §4.1：令牌名会诱导后续开发者填入红/绿）");
                assertFalse(lower.contains("-fx-" + word),
                        css + " 不得定义 -fx-" + word + "* 令牌");
            }
        }
    }

    @Test
    void tokenFiles_doNotUseForbiddenColorKeywords() {
        for (String css : TOKENS_CSS) {
            String lower = CssTokens.read(css).toLowerCase(Locale.ROOT);
            for (String keyword : List.of("red", "orange", "crimson", "salmon", "tomato", "gold", "lime", "green",
                    "yellow", "wheat")) {
                assertFalse(lower.contains(": " + keyword + ";") || lower.contains(": " + keyword + " ") ,
                        css + " 不得使用 CSS 颜色关键字 " + keyword);
            }
        }
    }

    // ================================================================ ② 色相 / 饱和度

    /** HSV：h ∈ [0,360), s ∈ [0,1], v ∈ [0,1]。 */
    static double[] hsv(String hex) {
        int[] c = CssTokens.channels(hex);
        double r = c[0] / 255.0;
        double g = c[1] / 255.0;
        double b = c[2] / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double d = max - min;
        double h;
        if (d == 0) {
            h = 0;
        } else if (max == r) {
            h = 60 * (((g - b) / d) % 6);
        } else if (max == g) {
            h = 60 * (((b - r) / d) + 2);
        } else {
            h = 60 * (((r - g) / d) + 4);
        }
        if (h < 0) {
            h += 360;
        }
        double s = max == 0 ? 0 : d / max;
        return new double[] {h, s, max};
    }

    /** 指南 §4.1 的判定规则：红 345°–20° 且 S > 12%；橙 20°–45° 且 S > 12%；高饱和绿 90°–160° 且 S > 20%。 */
    static String forbiddenHueReason(String hex) {
        double[] hsv = hsv(hex);
        double h = hsv[0];
        double s = hsv[1] * 100;
        boolean red = (h >= 345 || h <= 20) && s > 12;
        boolean orange = h > 20 && h <= 45 && s > 12;
        boolean green = h >= 90 && h <= 160 && s > 20;
        if (red) {
            return "红色相（H=" + WcagContrast.fmt(h) + "°, S=" + WcagContrast.fmt(s) + "% > 12%）";
        }
        if (orange) {
            return "橙色相（H=" + WcagContrast.fmt(h) + "°, S=" + WcagContrast.fmt(s) + "% > 12%）";
        }
        if (green) {
            return "高饱和绿（H=" + WcagContrast.fmt(h) + "°, S=" + WcagContrast.fmt(s) + "% > 20%）";
        }
        return null;
    }

    /**
     * 危机面板色族的豁免（<b>有据可依，不是放水</b>）：指南 §1.3 把
     * {@code -fx-c-crisis-bg / bar / text / meta / border} 明确定为「暖砂」色族
     * （实测 H=33–40°、S=4.9%–41.8%），并说明「刻意极轻、靠强调条定位」。
     * 指南 §4.1 的色相禁令针对的是<b>状态语义层</b>（红=error、橙=warning、绿=success），
     * 而指南自己就要求这五个颜色存在——把它判违规等于同时要求「必须有」和「不得有」。
     *
     * <p>因此它们被<b>显式列出</b>豁免，并且另外用
     * {@link #crisisFamily_isWarmSand_withNoRedAlarmHue} 单独钉住：整体落在暖砂色域、
     * 无一落在红/橙警报区（H ≤ 20°）、且饱和度受控。</p>
     */
    static final Set<String> CRISIS_FAMILY_EXEMPT = Set.of(
            "-fx-c-crisis-bg", "-fx-c-crisis-bar", "-fx-c-crisis-text",
            "-fx-c-crisis-meta", "-fx-c-crisis-border");

    @Test
    void everyNonCrisisTokenColor_isOutsideTheForbiddenHueBands_inBothThemes() {
        int checkedTotal = 0;
        int exemptedTotal = 0;
        for (String css : TOKENS_CSS) {
            Map<String, String> colors = CssTokens.byProperty(css);
            int checked = 0;
            int exempted = 0;
            Set<String> checkedNames = new LinkedHashSet<>();
            for (var e : colors.entrySet()) {
                String value = e.getValue().toUpperCase(Locale.ROOT);
                if (!CssTokens.isSixDigitHex(value)) {
                    continue;
                }
                if (CRISIS_FAMILY_EXEMPT.contains(e.getKey())) {
                    exempted++;
                    continue;
                }
                checked++;
                checkedNames.add(e.getKey());
                String reason = forbiddenHueReason(value);
                assertTrue(reason == null,
                        css + " 的 " + e.getKey() + " = " + value + " 落在禁用色相区：" + reason);
            }
            // 逐项实测：31 个项目令牌 - 5 个危机豁免 = 26 个非危机项目色，
            // + 7 个 Modena 重着色派生色（-fx-base / -fx-background / -fx-control-inner-background /
            //   -fx-text-background-color / -fx-accent / -fx-focus-color / -fx-selection-bar / -fx-selection-bar-non-focused）
            // + 8 个图表序列色 = 42
            assertEquals(42, checked, css + " 里被审计的非危机色值数，实际名单=" + checkedNames);
            assertEquals(5, exempted, css + " 里被豁免的危机面板色值数");
            checkedTotal += checked;
            exemptedTotal += exempted;
        }
        assertEquals(84, checkedTotal, "两套主题合计被审计的色值数");
        assertEquals(10, exemptedTotal, "两套主题合计豁免的色值数");
    }

    @Test
    void crisisFamily_isWarmSand_withNoRedAlarmHue() {
        for (String css : TOKENS_CSS) {
            Map<String, String> colors = CssTokens.byProperty(css);
            for (String key : CRISIS_FAMILY_EXEMPT) {
                String value = colors.get(key);
                assertNotNull(value, css + " 缺少 " + key);
                double[] hsv = hsv(value);
                double hue = hsv[0];
                double sat = hsv[1] * 100;
                assertTrue(hue > 20 && hue <= 50,
                        css + " 的 " + key + " = " + value + " 必须落在暖砂色域（H 20–50°），实测 H="
                                + WcagContrast.fmt(hue) + "°");
                assertTrue(sat <= 45,
                        css + " 的 " + key + " = " + value + " 饱和度必须 ≤ 45%（低刺激），实测 S="
                                + WcagContrast.fmt(sat) + "%");
                assertFalse(hsv(value)[2] < 0.05, css + " 的 " + key + " 不能是纯黑");
            }
        }
    }

    @Test
    void forbiddenHueRule_scoresKnownViolationsAndKnownGoodValues_honestly() {
        // 反例：指南 §4.1 点名或被否决的默认值。
        // 注意：本条断言的是「规则本身诚实」——命中的理由必须与指南给出的理由一致。
        // 注释里的 H/S 都是**实测值**（2026-10-14 fix round 1 逐条重算；旧注释里的 81.9/80.4/69.4/21.9 都不对）。
        assertNotNull(forbiddenHueReason("#DC2626"), "#DC2626（destructive 红，实测 H=0.00° S=82.73%）必须命中");
        assertNotNull(forbiddenHueReason("#EF4444"), "#EF4444（暗色红，实测 H=0.00° S=71.55%）必须命中");
        assertNotNull(forbiddenHueReason("#22C55E"), "#22C55E（暗色绿，实测 H=142.09° S=82.74%）必须命中");
        assertNotNull(forbiddenHueReason(MODENA_CHART_1), "Modena 默认图表橙红（实测 H=16.06° S=81.48%）必须命中");

        // 如实记录一个被指南<b>误归类</b>的值：#059669 的 H=161.38°、S=96.67%，
        // 恰好落在指南 §4.1「高饱和绿 90°–160°」的边界外 1.38°，
        // 且并不落在蓝紫主色相区（230°）里，所以按 §4.1 的色相规则本身<b>抓不住</b>它。
        // 指南对它的否决理由其实是「于明色底仅 3.50:1，既不达标又语义错」（§4.1 正文），
        // 因此这里改用那两条真实理由来断言，而不是把色相阈值偷偷放宽到 165° 去凑绿。
        assertEquals("161.38", WcagContrast.fmt(hsv("#059669")[0]),
                "#059669 的实测色相（报告旧版写 160.1°、评审写 161.36°，精确值是 161.3793° → 161.38°）");
        assertEquals("96.67", WcagContrast.fmt(hsv("#059669")[1] * 100), "#059669 的实测饱和度");
        assertTrue(forbiddenHueReason("#059669") == null,
                "#059669 按 §4.1 的色相阈值本不该命中（H=161.38° 超出 160° 边界）；若这里命中说明阈值被改了");
        assertTrue(WcagContrast.ratio("#059669", "#F7F6FA") < 4.5,
                "指南否决 #059669 的真实理由之一是明色底对比度不足；实测 "
                        + WcagContrast.fmt(WcagContrast.ratio("#059669", "#F7F6FA")) + ":1");
        double hueOfAccentGreen = hsv("#059669")[0];
        assertTrue(hueOfAccentGreen < 230 - 20 || hueOfAccentGreen > 230 + 20,
                "#059669 的色相必须远离本项目唯一的强调色相区（实测 H=" + WcagContrast.fmt(hueOfAccentGreen) + "°）");

        // 合规样例：本项目实际使用的非危机令牌与图表色必须全部 0 命中
        for (String ok : List.of("#4A55A2", "#3F6B9E", "#565A6E", "#63677A", "#6B5F8A", "#5A6478",
                "#A8B2F0", "#93B4D9", "#E6DCCE", "#2F4A66",
                "#C6D2E0", "#EDF1F6", "#8F8CA0", "#ADB2C0", "#B3A6CC", "#9AA0B0", "#FFFFFF", "#16171C")) {
            assertTrue(forbiddenHueReason(ok) == null,
                    "合规样例 " + ok + " 被误判为禁用色相：" + forbiddenHueReason(ok));
        }
    }

    // ================================================================ ③ 图表默认色

    @Test
    void chartColor1To8_areOverriddenInBothTokenFiles() {
        for (String css : TOKENS_CSS) {
            Map<String, String> props = CssTokens.byProperty(css);
            for (int i = 1; i <= 8; i++) {
                String key = "CHART_COLOR_" + i;
                String value = props.get(key);
                assertNotNull(value, css + " 必须覆盖 " + key + "（否则 Modena 默认序列色生效）");
                assertTrue(CssTokens.isSixDigitHex(value), key + " 的值必须是 #RRGGBB：" + value);
                assertFalse(MODENA_CHART_1.equalsIgnoreCase(value),
                        css + " 的 " + key + " 不得等于 Modena 默认橙红");
                String reason = forbiddenHueReason(value);
                assertTrue(reason == null, css + " 的 " + key + " = " + value + " 落在禁用色相区：" + reason);
            }
            // 8 个序列色互不相同
            Set<String> distinct = new LinkedHashSet<>();
            for (int i = 1; i <= 8; i++) {
                distinct.add(props.get("CHART_COLOR_" + i).toUpperCase(Locale.ROOT));
            }
            assertEquals(8, distinct.size(), css + " 的 CHART_COLOR_1..8 必须互不相同：" + distinct);
        }
    }

    @Test
    void appCss_hasNoLiteralColorValues_atAll() {
        List<String> hexes = CssTokens.hexLiterals("/css/app.css");
        assertEquals(List.of(), hexes, "app.css 必须只用 -fx-c-* 查找色，不得出现字面色值");
        String lower = CssTokens.read("/css/app.css").toLowerCase(Locale.ROOT);
        for (String keyword : List.of("red", "orange", "green", "crimson", "gold", "lime")) {
            assertFalse(Pattern.compile(":\\s*" + keyword + "\\s*;").matcher(lower).find(),
                    "app.css 不得使用颜色关键字 " + keyword);
        }
    }

    @Test
    void appCss_lookedUpColorNames_allExistInTheTokenFiles() {
        // 这条是评审点名的「本任务最有价值的缺口」：app.css 里把 -fx-c-surface 拼成
        // -fx-c-surface-typo，JavaFX 遇到未定义的查找色只会**静默回退**（不抛异常、不打日志、
        // CssParser 也不报错），表现是「这条样式没生效」，而以前没有任何测试会红。
        // Java 侧也不会有反应：ui/view 与 ui/chart 按 Global Constraints 不写单元测试。
        Set<String> defined = new TreeSet<>();
        for (ThemeTokens t : ThemeTokens.values()) {
            defined.add(t.key());
        }
        defined.addAll(CssTokens.byProperty("/css/tokens-light.css").keySet());
        defined.addAll(CssTokens.byProperty("/css/tokens-dark.css").keySet());

        Pattern reference = Pattern.compile("-fx-c-[a-z-]+|CHART_COLOR_[1-8]");
        List<String> checked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (CssTokens.Decl d : CssTokens.parse("/css/app.css")) {
            Matcher m = reference.matcher(d.rawValue());
            while (m.find()) {
                checked.add(m.group());
                if (!defined.contains(m.group())) {
                    missing.add(d.selector() + " { " + d.property() + ": " + d.rawValue() + " } → " + m.group());
                }
            }
        }
        assertTrue(checked.size() >= 90, "app.css 里扫到的查找色引用太少，护栏形同虚设：" + checked.size());
        assertEquals(List.of(), missing,
                "app.css 引用了 tokens-*.css / ThemeTokens 里不存在的查找色（JavaFX 会静默回退，样式只是「没生效」）："
                        + missing);
        System.out.println("[evidence] app.css 查找色引用数 = " + checked.size()
                + "，全部存在于 " + defined.size() + " 个已定义名字里");
    }

    // ================================================================ ⑤ DPI 安全

    /**
     * N1 护栏（2026-10-14 fix round 2，Blocker 级）：
     * <b>长度类属性一律不得引用 {@code -fx-*} 查找值</b>。
     *
     * <p>为什么这条护栏是承重的，而不是风格偏好：JavaFX 25 的长度转换器要的是
     * {@code javafx.css.Size} / {@code Number}，而查找值在运行时被解析成 {@code Double}，
     * 于是每渲染一次就抛
     * {@code ClassCastException: class java.lang.Double cannot be cast to class javafx.css.Size}
     * 并<b>静默忽略该声明</b>。{@code CssParser.errorsProperty()} 只报语法错，这条完全看不见——
     * 修复前 31 处声明全被忽略，全站 padding 与圆角实际为 0。
     *
     * <p>本机实测（2026-10-14，探针在 {@code %TEMP%\p3t1fix2\}）：
     * 修复前 {@code .card} 的 {@code getPadding()} = {@code top=0 right=0 bottom=0 left=0}、
     * {@code getBackground().getRadii()} = {@code tl=0 tr=0 br=0 bl=0}；修复后分别是
     * {@code 16.05} 四边与 {@code 10} 四角。
     *
     * <p>合法取值只有两种：字面数值（可带 {@code em} / {@code px}）。{@code px} 仍只允许
     * {@code .root} 的 {@code -fx-font-size} 那一处（由 {@link #allOtherLengths_useEmOnly} 守着），
     * 所以这条断言只可能被判红的写法就是查找值本身。
     */
    @Test
    void appCss_lengthProperties_neverReferenceLookupValues() {
        List<String> offenders = CssTokens.lengthClassDeclarationsWithLookupValue("/css/app.css");
        assertEquals(List.of(), offenders,
                "app.css 里长度类属性（" + CssTokens.LENGTH_CLASS_PROPERTIES + "）不得引用 -fx-* 查找值："
                        + "JavaFX 25 会抛 ClassCastException 并静默忽略整条声明（N1：修复前 31 处，"
                        + "全站 padding/圆角实际为 0）。必须写成字面数值（em / 裸数值）或改到 Java 侧设定。"
                        + "违规声明：" + offenders);
        // 反向证据：护栏必须真的扫到了那些属性，否则「0 违规」可能只是「0 命中」
        long scanned = CssTokens.parse("/css/app.css").stream()
                .filter(d -> CssTokens.LENGTH_CLASS_PROPERTIES.contains(d.property()))
                .count();
        assertTrue(scanned >= 40,
                "app.css 里被扫描的长度类声明太少（" + scanned + "），护栏形同虚设");
        System.out.println("[evidence] app.css 长度类声明共 " + scanned
                + " 条，其中引用查找值的 0 条（N1 修复前是 31 条）");
    }

    @Test
    void appCss_lengthLiterals_areNumericallyEqualOrEquivalentToTheirDesignTokens() {
        // 上面那条只保证「不再引用查找值」，这条保证「换成字面值之后仍然等于原来的设计令牌」。
        // 判据对**所有长度类属性**一致：把这些长度声明按空白/逗号拆成一个个分量，
        //   · 每个分量要么是某个令牌的字面值（间距族 em 值 / 圆角族裸数值），
        //   · 要么是 `0`（清零：.table-view / .list-view / .scroll-pane / .chart / .crisis-bar 的角）；
        // 否则就是「手写了一个既不是令牌值、也不是清零」的数——那正是漂移。
        //
        // 于是「改令牌不改 app.css」会红，「把 1.07em 手写成 1.08em」也会红，
        // 而 `.chip` 的 0.2em 0.6em / `.table-cell` 的 0.4em 0.7em /
        // `.chart-line-symbol` 的 0.36em（指南未给令牌的细调）被显式记为**豁免**，
        // 豁免条数被钉死——将来谁把它们也换成令牌值，这条会红着提醒同步更新这份清单。
        Set<String> allowedLengths = new LinkedHashSet<>();
        for (int i = 1; i <= 7; i++) {
            allowedLengths.add(ThemeTokens.nonColorValue("-fx-sp-" + i, ThemeMode.LIGHT));
        }
        for (String key : List.of("-fx-r-control", "-fx-r-card", "-fx-r-dialog", "-fx-r-pill")) {
            allowedLengths.add(ThemeTokens.nonColorValue(key, ThemeMode.LIGHT));
        }
        // 豁免清单：指南未给长度令牌的字面值。**逐个写死**，不是「凡是认不出来就放过」——
        // 实际用量的值集合与它必须完全相等（下面断言），所以多一个少一个都会红。
        Set<String> declaredExempt = new LinkedHashSet<>(List.of(
                "0.2em", "0.6em",           // .chip 内边距（细调）
                "0.4em", "0.7em",           // .table-view .table-cell 内边距（细调）
                "0.36em",                   // .default-color0.chart-line-symbol 尺寸
                "0.7em",                    // .scroll-bar 的 pref-width / pref-height
                "2.9em", "2.4em", "6em",    // .button / .text-field / .text-area 的 min-height
                "46em",                     // .reading 的 max-width（指南 §3.2 的可读行宽）
                "1", "2", "-2"));           // 描边宽度与焦点 insets（guides §2.3）

        List<String> checked = new ArrayList<>();
        Set<String> actualExempt = new LinkedHashSet<>();
        for (CssTokens.Decl d : CssTokens.parse("/css/app.css")) {
            if (!CssTokens.LENGTH_CLASS_PROPERTIES.contains(d.property())) {
                continue;
            }
            for (String part : d.rawValue().replace(",", " ").split("\\s+")) {
                if (part.startsWith("-fx-")) {
                    continue; // 查找值由上面那条断言负责，这里只看「已经换成字面值之后对不对」
                }
                if (allowedLengths.contains(part) || "0".equals(part)) {
                    checked.add(d.property() + "@" + d.selector() + "=" + part);
                } else if (declaredExempt.contains(part)) {
                    actualExempt.add(part);
                } else {
                    fail(d.selector() + " { " + d.property() + ": " + d.rawValue() + " } 的 " + part
                            + " 既不是任何令牌值（" + allowedLengths + "），也不是清零 0，"
                            + "也不在豁免清单 " + declaredExempt + " 里");
                }
            }
        }
        assertTrue(checked.size() >= 40,
                "被核对「字面值 = 令牌值」的分量太少（" + checked.size() + "），护栏形同虚设：" + checked);
        assertEquals(declaredExempt, actualExempt,
                "豁免清单必须与实际用量逐值相等（写死的目的就是让「新加一个字面长度」必须显式登记）");
        System.out.println("[evidence] 与令牌逐字核对的字面长度分量共 " + checked.size()
                + " 个；豁免（指南未给令牌的字面值）实际用到 " + actualExempt.size() + " 个：" + actualExempt);
    }

    @Test
    void tokenFiles_containExactlyOnePxOccurrence_andItIsTheRootFontSize() {
        for (String css : TOKENS_CSS) {
            List<Map.Entry<String, String>> px = CssTokens.units(css).stream()
                    .filter(e -> "px".equals(e.getKey()))
                    .toList();
            assertEquals(1, px.size(),
                    css + " 里只允许出现一处 px（.root 的 -fx-font-size），实际 " + px.size() + " 处：" + px);
            assertEquals("15", px.get(0).getValue(), css + " 里唯一允许的 px 必须是根字号的 15");
            List<CssTokens.Decl> decls = CssTokens.parse(css);
            List<String> pxDecls = decls.stream()
                    .filter(d -> d.rawValue().toLowerCase(Locale.ROOT).contains("px"))
                    .map(d -> d.selector() + " { " + d.property() + ": " + d.rawValue() + " }")
                    .toList();
            assertEquals(List.of(".root { -fx-font-size: 15px }"), pxDecls,
                    css + " 里唯一允许的 px 必须是 .root 的 -fx-font-size: 15px");
        }
    }

    @Test
    void allOtherLengths_useEmOnly() {
        // 百分号不是长度单位（本项目的 % 全部出现在注释的对比度说明里），所以只审 px / pt 这类真实长度单位
        for (String css : TOKENS_CSS) {
            List<Map.Entry<String, String>> bad = CssTokens.units(css).stream()
                    .filter(e -> !"em".equals(e.getKey()) && !"px".equals(e.getKey()) && !"%".equals(e.getKey()))
                    .toList();
            assertEquals(List.of(), bad, css + " 除字号外只允许 em 单位，实际出现了：" + bad);
        }
        // app.css：一个 px 都不许有
        List<Map.Entry<String, String>> appPx = CssTokens.units("/css/app.css").stream()
                .filter(e -> "px".equals(e.getKey()))
                .toList();
        assertEquals(List.of(), appPx, "app.css 不得出现任何 px（200% 缩放下会破版）");
    }

    @Test
    void appCssFontSizes_useLiteralEmValuesThatEqualTheirDesignTokens() {
        // 实测：JavaFX 25 的 -fx-font-size **不接受**查找色引用——
        //   `-fx-font-size: -fx-fs-h1;`  → CSS 错误 "Expected '<number>'"
        //   `-fx-font-size: em(-fx-fs-h1);` → CSS 错误 "Expected '<font-size>'"
        // （样例与实测见 task-1-report.md；指南 §2.4 的 `-fx-font-size: -fx-fs-h1;` 写法在本机不生效）
        // 因此 app.css 写**字面 em**，而这条断言把它与 §1.4 的令牌表钉在一起：
        // 字面值必须是某个 -fx-fs-* 令牌的值，改令牌不改 app.css 就会红。
        //
        // 2026-10-14 fix round 1：扫描范围从「只认 -fx-font-size」扩到**所有** `-fx-*font-size` 属性。
        // 覆盖 `-fx-tick-label-font-size`（JavaFX 的 FontCssMetaData 会自动派生
        // `-fx-<font属性>-size` 子属性，实测 0.87em 真的生效——评审的「死代码」判定不对，
        // 但评审「它逃过了字号焊接」这一半是对的：改 0.87em → 0.88em 曾让 56 条全绿）。
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String key : List.of("-fx-fs-micro", "-fx-fs-caption", "-fx-fs-small", "-fx-fs-body",
                "-fx-fs-h3", "-fx-fs-h2", "-fx-fs-h1", "-fx-fs-display")) {
            tokens.put(ThemeTokens.nonColorValue(key, ThemeMode.LIGHT), key);
        }
        List<String> checked = new ArrayList<>();
        for (CssTokens.Decl d : CssTokens.parse("/css/app.css")) {
            if (!d.property().matches("-fx-[a-z-]*font-size")) {
                continue;
            }
            String value = d.rawValue();
            assertTrue(tokens.containsKey(value),
                    "app.css 的 " + d.selector() + " { " + d.property() + ": " + value
                            + " } 必须等于某个 -fx-fs-* 令牌的值，允许集合=" + tokens.keySet());
            checked.add(d.property() + "@" + d.selector() + "=" + value + "=" + tokens.get(value));
        }
        assertEquals(21, checked.size(), "app.css 里的字号声明数（20 处 -fx-font-size + 1 处 -fx-tick-label-font-size）：" + checked);
        assertTrue(checked.stream().anyMatch(s -> s.startsWith("-fx-tick-label-font-size")),
                "app.css 必须显式声明 -fx-tick-label-font-size（否则坐标轴刻度退回 JavaFX 默认 8px）：" + checked);
        assertFalse(checked.contains("15px"), "app.css 不得再声明根字号（根字号只在 tokens-*.css）");
        // 不止 -fx-font-size：任何把 -fx-fs-* 当值直接引用的写法在 JavaFX 25 上都解析失败
        List<String> bareRefs = CssTokens.parse("/css/app.css").stream()
                .filter(d -> d.rawValue().startsWith("-fx-fs-"))
                .map(d -> d.selector() + " { " + d.property() + ": " + d.rawValue() + " }")
                .toList();
        assertEquals(List.of(), bareRefs,
                "app.css 不得把 -fx-fs-* 令牌当值直接引用（JavaFX 25 报 Expected '<number>'）：" + bareRefs);
    }

    @Test
    void appCss_doesNotLockAnyControlToAFixedWidthOrHeight() {
        // 指南 §3.2：禁止 setMinSize(96,32)+setMaxSize(96,32)、禁止 min-width: 900 这类锁死。
        // 本项目在 CSS 侧一律用 em；这里额外把「任何 > 46 的裸数值宽度」与「px 数值 > 320」钉死。
        for (String css : ALL_CSS) {
            for (CssTokens.Decl d : CssTokens.parse(css)) {
                String prop = d.property().toLowerCase(Locale.ROOT);
                if (!prop.matches(".*(min-width|max-width|pref-width|width|min-height|pref-height)")) {
                    continue;
                }
                String raw = d.rawValue().toLowerCase(Locale.ROOT);
                Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(raw);
                while (m.find()) {
                    double n = Double.parseDouble(m.group(1));
                    assertTrue(!(n > 320 && raw.contains("px")),
                            css + " 的 " + d.selector() + " { " + d.property() + ": " + d.rawValue()
                                    + " } 把控件锁死在 " + n + "px 以上（指南 §3.2 禁止）");
                    assertTrue(!(n > 46 && !raw.contains("em")),
                            css + " 的 " + d.selector() + " { " + d.property() + ": " + d.rawValue()
                                    + " } 是裸数值宽度（" + n + "），必须用 em 或约束式布局");
                }
            }
        }
    }

    // ================================================================ Java 源码里不许有字面色值

    @Test
    void javaSources_containNoHexColorLiteral() {
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "找不到 " + root.toAbsolutePath());
        // 模式用拼接构造：这样本测试文件自己也不含字面量色值（本文件在 src/test 下，不参与扫描，但仍保持诚实）
        Pattern forbidden = Pattern.compile("#" + "[0-9A-Fa-f]{6}");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = forbidden.matcher(lines.get(i));
                    if (m.find()) {
                        offenders.add(p + ":" + (i + 1) + " → " + m.group());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(scanned >= 5, "扫到的 Java 源文件太少，护栏形同虚设：" + scanned);
        assertEquals(List.of(), offenders,
                "src/main/java 下不许出现字面量十六进制颜色（令牌值只写在 tokens-*.css 里）：" + offenders);
    }

    @Test
    void javaSources_doNotHardCodePixelSizesOnCommonLayoutSetters() {
        Path root = Path.of("src", "main", "java");
        Pattern forbidden = Pattern.compile(
                "\\.set(Min|Max|Pref)(Width|Height)\\(\\s*\\d+(\\.\\d+)?\\s*\\)");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (forbidden.matcher(lines.get(i)).find()) {
                        offenders.add(p + ":" + (i + 1) + " → " + lines.get(i).trim());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(List.of(), offenders, "不得写死像素尺寸（指南 §3.2）：" + offenders);
    }

    // ================================================================ 键集合

    @Test
    void lightAndDark_colorTokenKeySets_areIdentical() {
        Map<String, String> light = CssTokens.projectColors("/css/tokens-light.css");
        Map<String, String> dark = CssTokens.projectColors("/css/tokens-dark.css");
        assertEquals(new TreeSet<>(light.keySet()), new TreeSet<>(dark.keySet()),
                "明暗两套必须提供同名令牌；明色多出=" + diff(light.keySet(), dark.keySet())
                        + "，暗色多出=" + diff(dark.keySet(), light.keySet()));
        assertEquals(31, light.size(),
                "tokens-light.css 的 -fx-c-* 令牌数（指南 §1.1 明色表 22 + §1.2 四态 6 + §1.3 危机 5，去重后 31）");
        assertEquals(31, dark.size(),
                "tokens-dark.css 的 -fx-c-* 令牌数（与明色键集合完全一致）");
    }

    @Test
    void themeTokensEnum_coversEveryProjectColorTokenKey() {
        Map<String, String> light = CssTokens.projectColors("/css/tokens-light.css");
        Set<String> enumKeys = new LinkedHashSet<>();
        for (ThemeTokens t : ThemeTokens.values()) {
            if (t.key().startsWith("-fx-c-")) {
                enumKeys.add(t.key());
            }
        }
        assertEquals(new TreeSet<>(light.keySet()), new TreeSet<>(enumKeys),
                "ThemeTokens 必须覆盖 tokens-*.css 里的每一个项目颜色令牌（Java 侧要能按名取值）");
    }

    @Test
    void everyThemeTokenKey_isUniquePointingAtTheRealCssProperty() {
        Set<String> keys = new LinkedHashSet<>();
        for (ThemeTokens t : ThemeTokens.values()) {
            assertTrue(keys.add(t.key()), "token key 重复：" + t.key());
            assertFalse(t.key().isBlank(), "token key 不得为空");
            assertEquals(t.key(), t.key().trim(), "token key 不得带首尾空白：" + t.key());
            assertTrue(t.key().startsWith("-fx-c-") || t.key().startsWith("CHART_COLOR_"),
                    "token key 命名越界：" + t.key());
            assertFalse(t.key().contains("_") && t.key().startsWith("-fx-c-"),
                    "项目颜色令牌用小写连字符，不用下划线：" + t.key());
        }
        assertEquals(ThemeTokens.values().length, keys.size());
    }

    @Test
    void nonColorTokens_arePresentInBothFilesWithTheGuidelineValues() {
        // 指南 §1.4/1.5/1.6/1.7 的非颜色令牌：字号/行距/间距/圆角/阴影
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("-fx-fs-micro", "0.80em");
        expected.put("-fx-fs-caption", "0.87em");
        expected.put("-fx-fs-small", "0.93em");
        expected.put("-fx-fs-body", "1.00em");
        expected.put("-fx-fs-h3", "1.07em");
        expected.put("-fx-fs-h2", "1.20em");
        expected.put("-fx-fs-h1", "1.40em");
        expected.put("-fx-fs-display", "1.73em");
        expected.put("-fx-ls-body", "0.5em");
        expected.put("-fx-ls-head", "0.35em");
        expected.put("-fx-ls-read", "0.6em");
        expected.put("-fx-sp-1", "0.27em");
        expected.put("-fx-sp-2", "0.53em");
        expected.put("-fx-sp-3", "0.80em");
        expected.put("-fx-sp-4", "1.07em");
        expected.put("-fx-sp-5", "1.60em");
        expected.put("-fx-sp-6", "2.13em");
        expected.put("-fx-sp-7", "3.20em");
        expected.put("-fx-r-control", "6");
        expected.put("-fx-r-card", "10");
        expected.put("-fx-r-dialog", "14");
        expected.put("-fx-r-pill", "999");
        expected.put("-fx-font-family", "\"Microsoft YaHei UI\", \"Microsoft YaHei\", \"Noto Sans SC\", \"Segoe UI\"");
        expected.put("-fx-font-size", "15px");

        for (String css : TOKENS_CSS) {
            Map<String, String> props = CssTokens.byProperty(css);
            for (var e : expected.entrySet()) {
                assertEquals(e.getValue(), props.get(e.getKey()),
                        css + " 的 " + e.getKey() + " 必须与 javafx-visual-guidelines.md 一致");
            }
            assertTrue(props.containsKey("-fx-sh-card"), css + " 缺少 -fx-sh-card");
            assertTrue(props.containsKey("-fx-sh-raised"), css + " 缺少 -fx-sh-raised");
            assertTrue(props.containsKey("-fx-sh-dialog"), css + " 缺少 -fx-sh-dialog");
        }
        // 明色：指南 §1.7 给的那一条卡片阴影；暗色：一律 none（层级靠面抬升 + 描边）
        assertEquals("dropshadow(gaussian, rgba(30,33,48,0.07), 3, 0, 0, 1)",
                CssTokens.byProperty("/css/tokens-light.css").get("-fx-sh-card"),
                "明色卡片阴影必须与指南 §1.7 逐字一致");
        assertEquals("none", CssTokens.byProperty("/css/tokens-dark.css").get("-fx-sh-card"),
                "暗色主题不用卡片阴影（指南 §1.7 / §2.3 的暗色样例块）");
    }

    @Test
    void fonts_containNoRemoteImportAndNoNonCjkSerifFamilies() {
        for (String css : ALL_CSS) {
            String text = CssTokens.read(css).toLowerCase(Locale.ROOT);
            assertFalse(text.contains("@import"), css + " 不得 @import（离线可用，裁定 A10）");
            assertFalse(text.contains("fonts.googleapis.com"), css + " 不得引远程字体");
            assertFalse(text.contains("lora"), css + " 不得引入 Lora（无 CJK 字形）");
            assertFalse(text.contains("raleway"), css + " 不得引入 Raleway（无 CJK 字形）");
        }
    }

    private static String diff(Set<String> a, Set<String> b) {
        Set<String> d = new TreeSet<>(a);
        d.removeAll(b);
        return d.toString();
    }
}
