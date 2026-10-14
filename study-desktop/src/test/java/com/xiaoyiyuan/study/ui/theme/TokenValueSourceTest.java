package com.xiaoyiyuan.study.ui.theme;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>护栏 1</b>：CSS 令牌文件里的每个颜色值都必须与
 * {@code docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md}（1179 行的中文实施指南）
 * <b>逐字一致</b>。
 *
 * <p>做法：<b>在测试里从指南的 markdown 表格解析出「令牌 → 明值 / 暗值」</b>，再与 tokens CSS 解析出的值比对。
 * 指南是唯一来源，所以这条护栏是机器可查的，不是「我看过了」。</p>
 *
 * <p>为了让「解析器坏掉 → 期望表为空 → 比对被静默跳过」这种情况不可能发生，前两条断言把
 * <b>解析结果本身的规模与内部一致性</b>也钉死了：令牌数少一个就红，指南两张表自相矛盾也红。</p>
 */
class TokenValueSourceTest {

    static final String GUIDELINES = "docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md";
    static final String MASTER = "design-system/study-assistant/MASTER.md";

    /** 指南 1.1 两张表里各有的颜色令牌数（各 20 行，逐行实测）。 */
    static final int TABLE_TOKENS = 20;

    /** 指南 1.2 四态表（含「待回报」的文字色与虚线边色）与 1.3 危机表的颜色令牌数。 */
    static final int STATE_TOKENS = 6;
    static final int CRISIS_TOKENS = 5;

    /**
     * 指南里出现的全部 {@code -fx-c-*} 令牌（去重后 31 个）。
     *
     * <p>注意：<b>不是</b> 22+6+5=33——{@code -fx-c-state-await} 在 1.1 与 1.2 里都出现
     * （1.1 明色表写「文字 + 虚线边」两列，1.2 把「待回报」拆成 {@code -fx-c-state-await} 与
     * {@code -fx-c-state-await-line}），去重后正好 31。这份去重结果同时也是 tokens CSS 里应有的键数。</p>
     */
    static final int ALL_GUIDELINE_COLOR_TOKENS = 31;

    private static final Pattern HEX6 = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern TOKEN_DECL = Pattern.compile(
            "^\\s*(-fx-c-[a-z-]+|CHART_COLOR_[1-8])\\s*:\\s*(#[0-9A-Fa-f]{6})\\s*;", Pattern.MULTILINE);

    private static String repoFile(String relative) {
        Path fromModule = Path.of("..", relative);
        Path target = Files.exists(fromModule) ? fromModule : Path.of(relative);
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 把一行 markdown 表格拆成单元格。
     *
     * <p><b>必须只去掉首尾的空单元，保留中间的空单元</b>：指南 §1.2 的「待回报」行是
     * {@code | 待回报 | | 文字 `#565A6E`，虚线边 `#8F8CA0` | ... |} —— 明色值那一格是空的
     * （因为明色值写在文字描述里），如果按「过滤所有空单元」拆，列会左移一格，
     * 那一行就会被整行跳过（第一版测试正是这样漏掉了「待回报」的 2 个令牌）。</p>
     */
    private static List<String> cells(String markdownLine) {
        List<String> raw = new ArrayList<>(Arrays.asList(markdownLine.split("\\|", -1)).stream()
                .map(String::trim)
                .toList());
        while (!raw.isEmpty() && raw.get(0).isEmpty()) {
            raw.remove(0);
        }
        while (!raw.isEmpty() && raw.get(raw.size() - 1).isEmpty()) {
            raw.remove(raw.size() - 1);
        }
        return raw;
    }

    private static String firstHex(String cell) {
        Matcher m = HEX6.matcher(cell);
        return m.find() ? m.group().toUpperCase(Locale.ROOT) : null;
    }

    private static List<String> allHex(String cell) {
        List<String> out = new ArrayList<>();
        Matcher m = HEX6.matcher(cell);
        while (m.find()) {
            out.add(m.group().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static Map<String, String> tableBetween(String from, String to, int minCells) {
        String text = repoFile(GUIDELINES);
        int a = text.indexOf(from);
        int b = text.indexOf(to);
        assertTrue(a >= 0, "指南里找不到小节标题：" + from);
        assertTrue(b > a, "指南里找不到小节结束标记：" + to);
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : text.substring(a, b).split("\n", -1)) {
            String line = raw.trim();
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> c = cells(line);
            if (c.size() >= minCells && c.get(0).startsWith("`-fx-c-") && c.get(1).startsWith("`#")) {
                String hex = firstHex(c.get(1));
                if (hex != null) {
                    out.put(c.get(0).replace("`", "").trim(), hex);
                }
            }
        }
        return out;
    }

    /** 指南 1.1 明色表（每行：令牌 | 十六进制 | 用途 | 对比度）。 */
    static Map<String, String> lightTable() {
        return tableBetween("#### 明色主题（Light）", "#### 暗色主题（Dark）", 3);
    }

    /** 指南 1.1 暗色表（与明色表同结构，多出一列用于放下「面底/页底」两个对比度数字）。 */
    static Map<String, String> darkTable() {
        return tableBetween("#### 暗色主题（Dark）", "### 1.2", 3);
    }

    /** 指南 1.2 四态表（明值与暗值同表）。 */
    static Map<String, String> stateTable(boolean dark) {
        String text = repoFile(GUIDELINES);
        int a = text.indexOf("### 1.2 四态中性色");
        int b = text.indexOf("### 1.3");
        assertTrue(a >= 0 && b > a, "指南里找不到 1.2 四态表");
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : text.substring(a, b).split("\n", -1)) {
            String line = raw.trim();
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> c = cells(line);
            if (c.size() < 3) {
                continue;
            }
            // 先按状态名判定这是不是一行状态，再取色值：
            // 「待回报」行的明色值是「文字 `#565A6E`，虚线边 `#8F8CA0`」，不以 `# 开头
            String token = switch (c.get(0)) {
                case "进行中" -> "-fx-c-state-active";
                case "已完成" -> "-fx-c-state-done";
                case "暂缓" -> "-fx-c-state-paused";
                case "已取消" -> "-fx-c-state-cancelled";
                case "待回报" -> "-fx-c-state-await";
                default -> null;
            };
            if (token == null) {
                continue;
            }
            List<String> lightHexes = allHex(c.get(1));
            List<String> darkHexes = allHex(c.get(2));
            if (lightHexes.isEmpty() || darkHexes.isEmpty()) {
                continue;
            }
            out.put(token, dark ? darkHexes.get(0) : lightHexes.get(0));
            if ("待回报".equals(c.get(0)) && lightHexes.size() >= 2 && darkHexes.size() >= 2) {
                out.put("-fx-c-state-await-line", dark ? darkHexes.get(1) : lightHexes.get(1));
            }
        }
        return out;
    }

    /** 指南 1.3 危机资源面板表。 */
    static Map<String, String> crisisTable(boolean dark) {
        String text = repoFile(GUIDELINES);
        int a = text.indexOf("### 1.3 危机资源面板");
        int b = text.indexOf("### 1.4");
        assertTrue(a >= 0 && b > a, "指南里找不到 1.3 危机表");
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : text.substring(a, b).split("\n", -1)) {
            String line = raw.trim();
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> c = cells(line);
            if (c.size() >= 3 && c.get(0).startsWith("`-fx-c-") && c.get(1).startsWith("`#")) {
                List<String> hexes = allHex(c.get(dark ? 2 : 1));
                if (!hexes.isEmpty()) {
                    out.put(c.get(0).replace("`", "").trim(), hexes.get(0));
                }
            }
        }
        return out;
    }

    /** 指南 2.3 的可粘贴样例块。 */
    static Map<String, String> specimenBlock(boolean dark) {
        String text = repoFile(GUIDELINES);
        int a = text.indexOf(dark ? "**`tokens-dark.css`**（暗色）" : "**`tokens-light.css`**（明色）");
        int b = text.indexOf(dark ? "### 2.4" : "**`tokens-dark.css`**（暗色）");
        assertTrue(a >= 0 && b > a, "指南里找不到 2.3 的 tokens-" + (dark ? "dark" : "light") + ".css 粘贴块");
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = TOKEN_DECL.matcher(text.substring(a, b));
        while (m.find()) {
            out.put(m.group(1), m.group(2).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * 指南 §2.3 样例块里的 <b>Modena 重着色 9 条</b>（含 8 位十六进制的 {@code -fx-faint-focus-color}）。
     *
     * <p>2026-10-14 fix round 1 补：{@code CssTokens.projectColors} 只看 {@code -fx-c-*}，
     * 所以指南 §2.3 里明/暗都给了的 {@code -fx-faint-focus-color (#3D4AA822 / #9AA6F522)}
     * 从来没有任何断言守着——这正是它被整份实现漏掉的原因。这里改用
     * {@code byProperty} 读全量声明，并把「键集合 = 指南的 9 条」也钉住。</p>
     */
    static final List<String> MODENA_RECOLOR_KEYS = List.of(
            "-fx-base", "-fx-background", "-fx-control-inner-background", "-fx-text-background-color",
            "-fx-accent", "-fx-focus-color", "-fx-faint-focus-color",
            "-fx-selection-bar", "-fx-selection-bar-non-focused");

    static Map<String, String> specimenRecolorBlock(boolean dark) {
        String text = repoFile(GUIDELINES);
        int a = text.indexOf(dark ? "**`tokens-dark.css`**（暗色）" : "**`tokens-light.css`**（明色）");
        int b = text.indexOf(dark ? "### 2.4" : "**`tokens-dark.css`**（暗色）");
        assertTrue(a >= 0 && b > a, "指南里找不到 2.3 的 tokens-" + (dark ? "dark" : "light") + ".css 粘贴块");
        Map<String, String> out = new LinkedHashMap<>();
        // 长名字在前，避免 -fx-background 抢先匹配 -fx-text-background-color 的前缀
        Matcher m = Pattern.compile("^\\s*(-fx-(?:selection-bar-non-focused|control-inner-background"
                        + "|text-background-color|faint-focus-color|selection-bar|focus-color|background|base|accent))"
                        + "\\s*:\\s*(#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?)\\s*;", Pattern.MULTILINE)
                .matcher(text.substring(a, b));
        while (m.find()) {
            out.put(m.group(1), m.group(2).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** 把指南的四处来源合成「明/暗 期望表」，并把指南内部的矛盾当失败。 */
    private static Map<String, String> expected(boolean dark) {
        Map<String, String> table = dark ? darkTable() : lightTable();
        Map<String, String> out = new LinkedHashMap<>(table);
        for (var e : stateTable(dark).entrySet()) {
            String clash = out.put(e.getKey(), e.getValue());
            if (clash != null) {
                assertEquals(clash, e.getValue(), "指南自相矛盾（1.1 表 vs 1.2 表）：" + e.getKey());
            }
        }
        for (var e : crisisTable(dark).entrySet()) {
            String clash = out.put(e.getKey(), e.getValue());
            if (clash != null) {
                assertEquals(clash, e.getValue(), "指南自相矛盾（1.1 表 vs 1.3 表）：" + e.getKey());
            }
        }
        for (var e : specimenBlock(dark).entrySet()) {
            String clash = out.get(e.getKey());
            if (clash != null) {
                assertEquals(clash, e.getValue(), "指南自相矛盾（表格 vs 2.3 样例块）：" + e.getKey());
            }
            out.put(e.getKey(), e.getValue());
        }
        out.keySet().removeIf(k -> k.startsWith("CHART_COLOR_"));
        return out;
    }

    // ================================================================ 护栏自身

    @Test
    void guidelineTables_parseToTheExpectedSize_soTheGuardCannotBeSilentlySkipped() {
        assertEquals(TABLE_TOKENS, lightTable().size(), "指南 1.1 明色表应有的颜色令牌数");
        assertEquals(TABLE_TOKENS, darkTable().size(), "指南 1.1 暗色表应有的颜色令牌数");
        assertEquals(STATE_TOKENS, stateTable(false).size(), "指南 1.2 明色应有的状态令牌数：" + stateTable(false));
        assertEquals(STATE_TOKENS, stateTable(true).size(), "指南 1.2 暗色应有的状态令牌数：" + stateTable(true));
        assertEquals(CRISIS_TOKENS, crisisTable(false).size(), "指南 1.3 明色应有的危机令牌数");
        assertEquals(CRISIS_TOKENS, crisisTable(true).size(), "指南 1.3 暗色应有的危机令牌数");
        System.out.println("[evidence] stateTable(false) = " + stateTable(false));
        System.out.println("[evidence] stateTable(true)  = " + stateTable(true));

        Set<String> all = new TreeSet<>();
        all.addAll(lightTable().keySet());
        all.addAll(darkTable().keySet());
        all.addAll(stateTable(false).keySet());
        all.addAll(crisisTable(false).keySet());
        all.addAll(specimenBlock(false).keySet().stream().filter(k -> k.startsWith("-fx-c-")).toList());
        assertEquals(ALL_GUIDELINE_COLOR_TOKENS, all.size(),
                "指南里应当恰好有 " + ALL_GUIDELINE_COLOR_TOKENS + " 个 -fx-c-* 颜色令牌，实际 " + all.size());
    }

    @Test
    void guidelineLightAndDarkTables_defineTheSameTokenNames() {
        assertEquals(lightTable().keySet(), darkTable().keySet(),
                "指南 1.1 明色表与暗色表必须定义同名令牌集合");
    }

    @Test
    void everyGuidelineTable_hasSixDigitHexValuesOnly() {
        for (Map<String, String> m : List.of(lightTable(), darkTable(), stateTable(false),
                stateTable(true), crisisTable(false), crisisTable(true))) {
            for (var e : m.entrySet()) {
                assertTrue(CssTokens.isSixDigitHex(e.getValue()),
                        e.getKey() + " 的值不是 #RRGGBB：" + e.getValue());
            }
        }
    }

    // ================================================================ 真正被守的性质

    @Test
    void lightTokenFile_definesExactlyTheGuidelineColorTokensWithExactlyTheGuidelineValues() {
        Map<String, String> actual = upper(CssTokens.projectColors("/css/tokens-light.css"));
        Map<String, String> expected = expected(false);
        assertTrue(expected.size() >= 31, "期望表太小，护栏形同虚设：" + expected.size());
        for (var e : expected.entrySet()) {
            assertEquals(e.getValue(), actual.get(e.getKey()),
                    "明色令牌 " + e.getKey() + " 必须与 javafx-visual-guidelines.md 逐字一致");
        }
        assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(actual.keySet()),
                "tokens-light.css 的 -fx-c-* 令牌集合必须与指南完全一致");
    }

    @Test
    void darkTokenFile_definesExactlyTheGuidelineColorTokensWithExactlyTheGuidelineValues() {
        Map<String, String> actual = upper(CssTokens.projectColors("/css/tokens-dark.css"));
        Map<String, String> expected = expected(true);
        assertTrue(expected.size() >= 31, "期望表太小，护栏形同虚设：" + expected.size());
        for (var e : expected.entrySet()) {
            assertEquals(e.getValue(), actual.get(e.getKey()),
                    "暗色令牌 " + e.getKey() + " 必须与 javafx-visual-guidelines.md 逐字一致");
        }
        assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(actual.keySet()),
                "tokens-dark.css 的 -fx-c-* 令牌集合必须与指南完全一致");
    }

    @Test
    void masterOverlay_doesNotContradictTheGuidelines() {
        String master = repoFile(MASTER);
        int a = master.indexOf("## 2. 采纳的调色板");
        assertTrue(a >= 0, "MASTER.md 里找不到「采纳的调色板」一节");
        Map<String, String> masterValues = new LinkedHashMap<>();
        // 行形如 | `-fx-c-bg` | `#F7F6FA` | 页面背景 |
        // 或     | `-fx-c-state-await` | `#565A6E` + 虚线边 `#8F8CA0` | 条目状态「待回报」… |
        // 统一取「令牌名后面第一个十六进制」；后者会得到文字色，虚线边色在指南 §1.3 有独立令牌。
        Matcher m = Pattern.compile("`(-fx-c-[a-z-]+)`[^|]*\\|\\s*`(#[0-9A-Fa-f]{6})`")
                .matcher(master.substring(a));
        while (m.find()) {
            masterValues.putIfAbsent(m.group(1), m.group(2).toUpperCase(Locale.ROOT));
        }
        assertTrue(masterValues.size() >= 18,
                "MASTER.md 覆盖层解析出的令牌太少（该文件只列了明色表的一部分）：" + masterValues.size()
                        + " → " + masterValues.keySet());

        Map<String, String> light = upper(CssTokens.projectColors("/css/tokens-light.css"));
        int compared = 0;
        for (var e : masterValues.entrySet()) {
            String actual = light.get(e.getKey());
            if (actual == null) {
                continue;
            }
            assertEquals(e.getValue(), actual, "MASTER.md 覆盖层与 javafx-visual-guidelines.md 冲突：" + e.getKey());
            compared++;
        }
        // MASTER.md 的明色表覆盖 20 个令牌里的 18 个（-fx-c-accent-hover 与 -fx-c-text-disabled 缺席）
        assertTrue(compared >= 17, "真正比对上的令牌太少，护栏形同虚设：" + compared);
    }

    @Test
    void modenaRecolorBlock_matchesTheGuidelineSpecimenBlock_includingTheFaintFocusColor() {
        for (boolean dark : List.of(false, true)) {
            String css = dark ? "/css/tokens-dark.css" : "/css/tokens-light.css";
            Map<String, String> fromGuideline = specimenRecolorBlock(dark);
            assertEquals(new TreeSet<>(MODENA_RECOLOR_KEYS), new TreeSet<>(fromGuideline.keySet()),
                    "指南 §2.3 的" + (dark ? "暗色" : "明色") + "样例块必须给出全部 9 条 Modena 重着色键，实际 "
                            + fromGuideline.keySet());
            Map<String, String> actual = new TreeMap<>(CssTokens.byProperty(css));
            for (String key : MODENA_RECOLOR_KEYS) {
                assertEquals(fromGuideline.get(key), upperValue(actual.get(key)),
                        css + " 的 " + key + " 必须与指南 §2.3 逐字一致");
            }
            // 8 位十六进制（带 alpha）的淡焦点晕：明 #3D4AA822 / 暗 #9AA6F522
            assertEquals(dark ? "#9AA6F522" : "#3D4AA822", upperValue(actual.get("-fx-faint-focus-color")),
                    css + " 的 -fx-faint-focus-color 必须按指南 §2.3 给足 8 位（否则 Modena 的淡蓝焦点晕与项目色不一致）");
        }
    }

    private static String upperValue(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    @Test
    void chartColors_areOverriddenToTheGuidelineChartPalette() {
        Map<String, String> light = upper(CssTokens.byProperty("/css/tokens-light.css"));
        Map<String, String> dark = upper(CssTokens.byProperty("/css/tokens-dark.css"));
        Map<String, String> lightSample = specimenBlock(false);
        Map<String, String> darkSample = specimenBlock(true);
        for (int i = 1; i <= 8; i++) {
            String key = "CHART_COLOR_" + i;
            assertNotNull(lightSample.get(key), "指南 2.3 明色样例块缺少 " + key);
            assertNotNull(darkSample.get(key), "指南 2.3 暗色样例块缺少 " + key);
            assertEquals(lightSample.get(key), light.get(key), key + " 必须按指南覆盖 Modena 默认色");
            assertEquals(darkSample.get(key), dark.get(key), key + " 暗色必须按指南覆盖 Modena 默认色");
        }
        // 指南 §4.2 正文点名的 Modena 默认橙红
        String modenaDefaultChart = "#f3622d";
        assertFalse(light.containsValue(modenaDefaultChart), "tokens-light.css 里不得残留 Modena 默认图表色");
        assertFalse(dark.containsValue(modenaDefaultChart), "tokens-dark.css 里不得残留 Modena 默认图表色");
    }

    private static Map<String, String> upper(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, v.toUpperCase(Locale.ROOT)));
        return out;
    }
}
