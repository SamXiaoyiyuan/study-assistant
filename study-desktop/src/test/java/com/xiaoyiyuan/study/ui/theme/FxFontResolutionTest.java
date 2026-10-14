package com.xiaoyiyuan.study.ui.theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>悬置项 1 的实跑结论</b>（指南 §1.4 结尾：「此点需在实施计划第一步的最小窗口里实测确认」）：
 * JavaFX CSS 的 {@code -fx-font-family} <b>逗号分隔族列表在本机是否被正常解析</b>。
 *
 * <p>实测结论（证据见 task-1-report.md）：
 * ① 逗号族列表被 JavaFX CSS 解析器<b>无错接受</b>；
 * ② 只有<b>列表第一项</b>参与族名解析（{@code FontConverter} 取 {@code values[0]}），
 * <b>没有浏览器式回退链</b>；
 * ③ 列表后面的族名既不会被 CSS 使用，也不能在 Java 侧作为独立族名拿到（缺失时静默退化成
 * {@code System}）；
 * ④ 因此 CSS 声明的族列表之外，还需要 Java 侧按 {@code Font.getFamilies()} 逐级挑第一个真实存在的族。</p>
 *
 * <p>这些断言钉的是<b>实测语义</b>，不是我们希望有的语义。</p>
 */
class FxFontResolutionTest {

    static final String FAMILY_LIST_CSS =
            "\"Microsoft YaHei UI\", \"Microsoft YaHei\", \"Noto Sans SC\", \"Segoe UI\"";

    static final List<String> FAMILIES = List.of(
            "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI");

    @BeforeAll
    static void startFx() {
        FxToolkit.ensureStarted();
    }

    static String declaredFamilyList(String resourcePath) {
        Matcher m = Pattern.compile("-fx-font-family\\s*:\\s*([^;]+);").matcher(CssTokens.read(resourcePath));
        assertTrue(m.find(), resourcePath + " 里找不到 -fx-font-family 声明");
        return m.group(1).trim();
    }

    @Test
    void familyList_isDeclaredExactlyOnRootOfBothTokenFilesAndAppCss() {
        for (String css : List.of("/css/tokens-light.css", "/css/tokens-dark.css", "/css/app.css")) {
            assertEquals(FAMILY_LIST_CSS, declaredFamilyList(css),
                    css + " 的 -fx-font-family 必须逐字等于指南 §1.4 给的族列表");
        }
        // tokens 里声明一次 + app.css 里声明一次（更具体的 .root 规则在最后生效，且不依赖加载顺序）
        assertEquals(1, Pattern.compile("-fx-font-family").matcher(CssTokens.read("/css/app.css")).results().count());
        assertEquals(1, Pattern.compile("-fx-font-family").matcher(CssTokens.read("/css/tokens-light.css")).results().count());
        assertEquals(1, Pattern.compile("-fx-font-family").matcher(CssTokens.read("/css/tokens-dark.css")).results().count());
        int rootIndex = CssTokens.read("/css/app.css").indexOf(".root");
        int familyIndex = CssTokens.read("/css/app.css").indexOf("-fx-font-family");
        assertTrue(rootIndex >= 0 && familyIndex > rootIndex, "app.css 里 -fx-font-family 必须在 .root 里");
    }

    @Test
    void commaFamilyList_isAcceptedByTheJavaFxCssParser_withoutErrors() throws Exception {
        for (String css : List.of("/css/tokens-light.css", "/css/tokens-dark.css", "/css/app.css")) {
            javafx.css.CssParser.errorsProperty().clear();
            javafx.css.Stylesheet sheet = new javafx.css.CssParser()
                    .parse(ThemeTokens.class.getResource(css));
            List<String> errors = javafx.css.CssParser.errorsProperty().stream()
                    .map(javafx.css.CssParser.ParseError::getMessage).toList();
            assertEquals(List.of(), errors, css + " 的逗号族列表必须被无错解析：" + errors);
            assertFalse(sheet.getRules().isEmpty(), css + " 解析出的规则数为 0");
        }
    }

    @Test
    void realScene_resolvesTheCommaFamilyListToTheFirstDeclaredFamilyAt15px() {
        Text node = new Text("中文渲染");
        Scene scene = new Scene(new StackPane(node));
        scene.getStylesheets().setAll(
                ThemeTokens.class.getResource("/css/tokens-light.css").toExternalForm(),
                ThemeTokens.class.getResource("/css/app.css").toExternalForm());
        node.applyCss();

        Font applied = node.getFont();
        assertNotNull(applied, "Text 节点必须解析出一个 Font");
        System.out.println("[evidence] declared = " + FAMILY_LIST_CSS);
        System.out.println("[evidence] Text.getFont() = " + applied);
        System.out.println("[evidence] resolved family = " + applied.getFamily()
                + " | size = " + applied.getSize());
        System.out.println("[evidence] Font.getDefault() = " + Font.getDefault());

        assertEquals("Microsoft YaHei UI", applied.getFamily(),
                "实测：逗号族列表只有第一个族名生效；实际解析到 " + applied.getFamily());
        assertEquals(15.0, applied.getSize(), 1e-6, "根字号 15px 必须生效（实测 " + applied.getSize() + "）");
        assertFalse(applied.getFamily().equals(Font.getDefault().getFamily()),
                "解析结果不能悄悄退化成默认字体（默认是 " + Font.getDefault().getFamily() + "）");
    }

    @Test
    void appCssCanBeLoadedLast_tokensFirst_isWhatActuallyHappens() {
        // 指南 §2.2 规定的加载顺序：tokens-*.css → app.css。
        // 实测：两张表同时挂上时族与字号都正确（app.css 不会把 tokens 的族覆盖掉）。
        for (ThemeMode mode : ThemeMode.values()) {
            Text node = new Text("中文");
            Scene scene = new Scene(new StackPane(node));
            scene.getStylesheets().setAll(ThemeTokens.cssFiles(mode).stream()
                    .map(p -> ThemeTokens.class.getResource(p).toExternalForm())
                    .toList());
            node.applyCss();
            System.out.println("[evidence] " + mode + " tokens+app → " + node.getFont());
            assertEquals("Microsoft YaHei UI", node.getFont().getFamily(),
                    mode + " 下两张表同时加载时族必须正确");
            assertEquals(15.0, node.getFont().getSize(), 1e-6,
                    mode + " 下根字号必须由 tokens-*.css 的 -fx-font-size 决定");
        }
    }

    @Test
    void tokensStylesheetIsMandatory_forBothColoursAndRootFontSize() {
        // 反证：只挂 app.css 会让 -fx-c-* 找不到定义。
        // 实测两种后果（本条把它钉住，免得后来的人以为 app.css 能单独用）：
        //   ① -fx-background-color: -fx-c-bg 解析失败 → JavaFX 只打一行
        //      "Caught ClassCastException ... while converting value for '-fx-background-color' from rule '*.root'"
        //   ② 字号退回 JavaFX 默认（实测 12.0，而不是令牌里的 15）
        Text appOnly = new Text("中文");
        Scene scene = new Scene(new StackPane(appOnly));
        scene.getStylesheets().setAll(ThemeTokens.class.getResource("/css/app.css").toExternalForm());
        appOnly.applyCss();
        System.out.println("[evidence] app only → " + appOnly.getFont());
        assertNotEquals(15.0, appOnly.getFont().getSize(),
                "只挂 app.css 时字号不该是 15（说明它确实没有拿到 tokens 的根字号）");

        // 反证的另一半：tokens 单独挂上就够「颜色 + 字号」，app.css 只提供组件样式
        Text tokensOnly = new Text("中文");
        Scene scene2 = new Scene(new StackPane(tokensOnly));
        scene2.getStylesheets().setAll(ThemeTokens.class.getResource("/css/tokens-light.css").toExternalForm());
        tokensOnly.applyCss();
        System.out.println("[evidence] tokens only → " + tokensOnly.getFont());
        assertEquals(15.0, tokensOnly.getFont().getSize(), 1e-6,
                "tokens-*.css 单独挂上就必须提供根字号");
    }

    @Test
    void declaredFamilies_availabilityOnThisMachine_isPinnedAsAMeasuredFact() {
        Map<String, Boolean> availability = new LinkedHashMap<>();
        for (String f : FAMILIES) {
            availability.put(f, Font.getFamilies().contains(f));
        }
        System.out.println("[evidence] Font.getFamilies() 命中情况 = " + availability);
        System.out.println("[evidence] Font.getDefault().getFamily() = " + Font.getDefault().getFamily());

        assertTrue(availability.get("Microsoft YaHei UI"), "首选族必须在本机真实存在：" + availability);
        assertTrue(availability.get("Microsoft YaHei"), "第二族必须在本机真实存在：" + availability);
        assertEquals(List.of("Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI"),
                availability.entrySet().stream().filter(Map.Entry::getValue)
                        .map(Map.Entry::getKey).toList(),
                "本机可用的声明族（实测，作为事实钉住）：" + availability);
        assertEquals(List.of("Noto Sans SC"),
                availability.entrySet().stream().filter(e -> !e.getValue())
                        .map(Map.Entry::getKey).toList(),
                "本机缺席的声明族（实测）：" + availability);
    }

    @Test
    void javaSideFallbackChain_picksTheFirstAvailableFamily_avoidingTheSilentSystemFallback() {
        // 本机实测：第一个可用族就是首选族
        assertEquals("Microsoft YaHei UI", JavaFontResolver.primaryAvailableFamily());
        // 纯函数部分：可用族集合变化时按顺序退让，而不是直接掉到 System
        assertEquals("Microsoft YaHei UI",
                JavaFontResolver.firstAvailableFamily(List.of("Microsoft YaHei UI", "Segoe UI")));
        assertEquals("Microsoft YaHei",
                JavaFontResolver.firstAvailableFamily(List.of("Microsoft YaHei", "Segoe UI")));
        assertEquals("Noto Sans SC",
                JavaFontResolver.firstAvailableFamily(List.of("Noto Sans SC")));
        assertEquals(null, JavaFontResolver.firstAvailableFamily(List.of("SimSun", "KaiTi")),
                "一个声明的族都没有时必须返回 null（由调用方决定怎么办），不得假装成功");
        assertEquals(List.of("Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI"),
                JavaFontResolver.declaredFamilies());
        assertEquals(List.of("Noto Sans SC"), JavaFontResolver.missingFamilies());
        assertEquals(15.0, JavaFontResolver.rootFontSizePx(), 1e-9,
                "根字号必须取自 tokens-*.css 的 -fx-font-size，而不是 Java 里的字面量");
    }

    @Test
    void tickLabelFontSize_isALiveFontSubProperty_notDeadCode() {
        // 评审的 M12：把 app.css 的 `-fx-tick-label-font-size: 0.87em` 改成 0.88em → 56 条全绿，
        // 据此判定它是「死代码」。实测**相反**：JavaFX 的 `FontCssMetaData("-fx-tick-label-font")`
        // 会自动派生 `-fx-tick-label-font-size` 等子属性（javafx.css.FontCssMetaData 源码注释：
        // "includes the font sub-properties: weight, style, family and size"），0.87em 真的生效。
        // 所以正确的修法不是删掉它，而是把它纳入「字号字面值 == 某个 -fx-fs-* 令牌值」的焊接
        // （ColorAuditTest.appCssFontSizes_useLiteralEmValuesThatEqualTheirDesignTokens 已扩到所有 -fx-*font-size）。
        for (ThemeMode mode : ThemeMode.values()) {
            javafx.scene.chart.NumberAxis axis = new javafx.scene.chart.NumberAxis();
            javafx.scene.chart.LineChart<Number, Number> chart =
                    new javafx.scene.chart.LineChart<>(axis, new javafx.scene.chart.NumberAxis());
            Scene scene = new Scene(new StackPane(chart), 600, 400);
            scene.getStylesheets().setAll(ThemeTokens.cssFiles(mode).stream()
                    .map(path -> ThemeTokens.class.getResource(path).toExternalForm())
                    .toList());
            chart.applyCss();
            chart.layout();

            Font tick = axis.getTickLabelFont();
            double captionEm = Double.parseDouble(
                    ThemeTokens.nonColorValue("-fx-fs-caption", mode).replace("em", ""));
            double expected = captionEm * JavaFontResolver.rootFontSizePx();
            System.out.println("[evidence] " + mode + " 坐标轴刻度字体 = " + tick
                    + "（-fx-fs-caption=" + captionEm + "em × 根字号 " + JavaFontResolver.rootFontSizePx() + "px = " + expected + "）");

            assertEquals(expected, tick.getSize(), 0.1,
                    mode + " 主题：-fx-tick-label-font-size 必须按 -fx-fs-caption × 根字号生效，实测 " + tick.getSize());
            assertNotEquals(8.0, tick.getSize(),
                    "若等于 8.0，说明刻度退回了 JavaFX 的默认 Font(\"System\",8)——那才是「死代码」的样子");
        }
    }

    @Test
    void safeFamilyName_rejectsInlineStyleInjection() {
        assertEquals("Microsoft YaHei", JavaFontResolver.safeFamilyName("Microsoft YaHei"));
        for (String bad : List.of("Bad\"Name", "Bad;Name", "Bad{Name", "Bad}Name", "Bad'Name")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> JavaFontResolver.safeFamilyName(bad),
                    "族名 " + bad + " 含引号/分号/花括号，必须被拒绝");
        }
    }

    @Test
    void absentFamily_degeneratesToTheSystemFontSilently_insteadOfFallingThroughTheList() {
        Font absent = Font.font("Noto Sans SC", 15);
        System.out.println("[evidence] Font.font(\"Noto Sans SC\",15) = " + absent.getFamily()
                + " / " + absent.getName());
        assertEquals(Font.getDefault().getFamily(), absent.getFamily(),
                "本机缺失的族名会静默退化成默认族（" + Font.getDefault().getFamily() + "），不会顺延到列表下一项");
    }

    @Test
    void cjkText_measuredWidthShowsRealGlyphsAtTheResolvedFont() {
        Text cjk = new Text("考研");
        Scene scene = new Scene(new StackPane(cjk));
        scene.getStylesheets().setAll(ThemeTokens.class.getResource("/css/tokens-light.css").toExternalForm());
        cjk.applyCss();
        double cjkWidth = cjk.getLayoutBounds().getWidth();

        Text latin = new Text("KaoYan");
        latin.setFont(cjk.getFont());
        double latinWidth = latin.getLayoutBounds().getWidth();

        System.out.println("[evidence] \"考研\" width @ " + cjk.getFont() + " = " + cjkWidth);
        System.out.println("[evidence] \"KaoYan\" width @ same font = " + latinWidth);

        assertTrue(Double.isFinite(cjkWidth) && cjkWidth > 0, "中文必须量出正的有限宽度，实测 " + cjkWidth);
        assertTrue(cjkWidth >= 25,
                "两个汉字在 15px 下应量出 ≥ 25px（全宽字形，实测恰好 30 = 2 × 15em），实测 " + cjkWidth
                        + "（小于它通常意味着没拿到中文字形）");
        assertEquals(30.0, cjkWidth, 0.5,
                "两个汉字在 15px 下必须正好是两个全宽字形（30px）；实测 " + cjkWidth);
        System.out.println("[evidence] 拉丁串 \"KaoYan\" 宽 " + latinWidth
                + "（比中文串宽是正常的，字母串更长）");
    }
}
