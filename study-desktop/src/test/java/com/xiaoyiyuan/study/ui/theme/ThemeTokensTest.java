package com.xiaoyiyuan.study.ui.theme;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简报点名的测试类：令牌解析、明暗键集合、theme mode 切换、reduceMotion 开关、样式表整体替换。
 *
 * <p>它<b>不启动 JavaFX 运行时</b>（JavaFX 缺显示栈时会让测试变成抽奖），
 * 只解析 CSS 文本；需要真实 JavaFX 字体解析的断言在 {@code FxFontResolutionTest}。</p>
 */
class ThemeTokensTest {

    @Test
    void everyToken_resolvesInBothModes() {
        for (ThemeMode mode : ThemeMode.values()) {
            for (ThemeTokens token : ThemeTokens.values()) {
                String value = ThemeTokens.valueOf(token, mode);
                assertTrue(value.matches("#[0-9A-Fa-f]{6}"),
                        token.key() + " 在 " + mode + " 下必须解析到 #RRGGBB，实际：" + value);
                assertEquals(value.toUpperCase(java.util.Locale.ROOT), value,
                        token.key() + " 在 " + mode + " 下的值应当是大写十六进制：" + value);
            }
        }
    }

    @Test
    void lightAndDark_defineTheSameTokenSet() {
        // 键集合完全一致：逐键核对，缺一个就报出是哪个（不是只比个数）
        for (ThemeTokens t : ThemeTokens.values()) {
            if (t.isChartColor()) {
                continue; // 图表序列色不是 -fx-c-*，由 ColorAuditTest 单独审
            }
            assertTrue(CssTokens.projectColors("/css/tokens-light.css").containsKey(t.key()),
                    "tokens-light.css 缺少 " + t.key());
            assertTrue(CssTokens.projectColors("/css/tokens-dark.css").containsKey(t.key()),
                    "tokens-dark.css 缺少 " + t.key());
        }
        java.util.Set<String> light = new java.util.TreeSet<>(CssTokens.projectColors("/css/tokens-light.css").keySet());
        java.util.Set<String> dark = new java.util.TreeSet<>(CssTokens.projectColors("/css/tokens-dark.css").keySet());
        assertEquals(light, dark, "明暗两套的令牌键集合必须完全一致");
    }

    @Test
    void specPinnedValues_areExact() {
        assertEquals("#F3EFE7", ThemeTokens.valueOf(ThemeTokens.CRISIS_BG, ThemeMode.LIGHT), "危机面板暖砂底，不是红");
        assertEquals("#16171C", ThemeTokens.valueOf(ThemeTokens.BG, ThemeMode.DARK));
        assertEquals("#4A55A2", ThemeTokens.valueOf(ThemeTokens.ACCENT, ThemeMode.LIGHT));
        assertEquals("#A8B2F0", ThemeTokens.valueOf(ThemeTokens.ACCENT, ThemeMode.DARK));
        assertEquals("#7A6647", ThemeTokens.valueOf(ThemeTokens.CRISIS_BAR, ThemeMode.LIGHT));
        assertEquals("#4A4238", ThemeTokens.valueOf(ThemeTokens.CRISIS_TEXT, ThemeMode.LIGHT));
        assertEquals("#565A6E", ThemeTokens.valueOf(ThemeTokens.STATE_AWAIT, ThemeMode.LIGHT));
        assertEquals("#8F8CA0", ThemeTokens.valueOf(ThemeTokens.STATE_AWAIT_LINE, ThemeMode.LIGHT));
        assertEquals("#3F6B9E", ThemeTokens.valueOf(ThemeTokens.STATE_ACTIVE, ThemeMode.LIGHT));
        assertEquals("#5A6478", ThemeTokens.valueOf(ThemeTokens.STATE_DONE, ThemeMode.LIGHT));
        assertEquals("#6B5F8A", ThemeTokens.valueOf(ThemeTokens.STATE_PAUSED, ThemeMode.LIGHT));
        assertEquals("#63677A", ThemeTokens.valueOf(ThemeTokens.STATE_CANCELLED, ThemeMode.LIGHT));
    }

    @Test
    void noForbiddenSemanticTokens() {
        String both = CssTokens.read("/css/tokens-light.css") + CssTokens.read("/css/tokens-dark.css");
        String lower = both.toLowerCase(java.util.Locale.ROOT);
        for (String word : List.of("danger", "error", "warning", "success", "critical")) {
            assertFalse(lower.contains(word), "令牌文件里不得出现语义令牌名/关键字 " + word);
        }
    }

    @Test
    void noPixelLockedSizesInCss() {
        for (String css : List.of("/css/tokens-light.css", "/css/tokens-dark.css", "/css/app.css")) {
            String text = CssTokens.read(css);
            var m = java.util.regex.Pattern.compile("min-width\\s*:\\s*(\\d+)").matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                assertTrue(n <= 320, css + " 的 min-width: " + n + " 锁死了控件（> 320）");
            }
        }
    }

    @Test
    void cssFiles_differOnlyInTokenFile() {
        assertEquals("/css/tokens-light.css", ThemeTokens.cssFiles(ThemeMode.LIGHT).get(0));
        assertEquals("/css/tokens-dark.css", ThemeTokens.cssFiles(ThemeMode.DARK).get(0));
        assertEquals(ThemeTokens.cssFiles(ThemeMode.LIGHT).subList(1, ThemeTokens.cssFiles(ThemeMode.LIGHT).size()),
                ThemeTokens.cssFiles(ThemeMode.DARK).subList(1, ThemeTokens.cssFiles(ThemeMode.DARK).size()));
        assertEquals(List.of("/css/tokens-light.css", "/css/app.css"), ThemeTokens.cssFiles(ThemeMode.LIGHT));
        assertEquals(List.of("/css/tokens-dark.css", "/css/app.css"), ThemeTokens.cssFiles(ThemeMode.DARK));
    }

    @Test
    void cssFiles_areActuallyOnTheClasspath_andParseWithoutErrors() throws Exception {
        for (ThemeMode mode : ThemeMode.values()) {
            for (String path : ThemeTokens.cssFiles(mode)) {
                assertTrue(ThemeTokens.class.getResource(path) != null, "classpath 上找不到 " + path);
            }
        }
        // 用 JavaFX 自己的 CSS 解析器解析（不启动工具包）：语法错误会被记进 errors 列表
        for (String path : List.of("/css/tokens-light.css", "/css/tokens-dark.css", "/css/app.css")) {
            javafx.css.CssParser.errorsProperty().clear();
            javafx.css.Stylesheet sheet = new javafx.css.CssParser().parse(ThemeTokens.class.getResource(path));
            List<String> errors = javafx.css.CssParser.errorsProperty().stream()
                    .map(javafx.css.CssParser.ParseError::getMessage).toList();
            assertEquals(List.of(), errors, path + " 必须能被 JavaFX CSS 解析器无错解析：" + errors);
            assertFalse(sheet.getRules().isEmpty(), path + " 解析出的规则数为 0");
        }
    }

    @Test
    void themeMode_otherAndLabel() {
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.other());
        assertEquals(ThemeMode.LIGHT, ThemeMode.DARK.other());
        assertEquals("明", ThemeMode.LIGHT.label());
        assertEquals("暗", ThemeMode.DARK.label());
        assertEquals(2, ThemeMode.values().length);
    }

    @Test
    void reduceMotion_defaultsToFalse_andToggles() {
        ThemeManager manager = new ThemeManager(ThemeMode.LIGHT);
        assertFalse(manager.reduceMotion(), "reduceMotion 默认必须是 false（默认跟随系统设置由设置页写入）");
        manager.setReduceMotion(true);
        assertTrue(manager.reduceMotion());
        manager.setReduceMotion(false);
        assertFalse(manager.reduceMotion());
    }

    @Test
    void themeManager_currentAndSetMode() {
        ThemeManager manager = new ThemeManager(ThemeMode.LIGHT);
        assertEquals(ThemeMode.LIGHT, manager.current());
        manager.setMode(ThemeMode.DARK);
        assertEquals(ThemeMode.DARK, manager.current());
        manager.setMode(ThemeMode.LIGHT);
        assertEquals(ThemeMode.LIGHT, manager.current());
        assertNotEquals(ThemeMode.LIGHT, ThemeMode.LIGHT.other());
    }

    @Test
    void verifyAllTokensResolve_isTrue_andWouldBeFalseIfATokenWereMissing() {
        assertTrue(new ThemeManager(ThemeMode.LIGHT).verifyAllTokensResolve(),
                "每个令牌都必须在两个模式下解析到合法 #RRGGBB");
    }

    @Test
    void styleSheetsFor_bothModesAreDistinctAndEndWithAppCss() {
        List<String> light = ThemeTokens.cssFiles(ThemeMode.LIGHT);
        List<String> dark = ThemeTokens.cssFiles(ThemeMode.DARK);
        assertNotEquals(light.get(0), dark.get(0), "明暗必须加载不同的令牌文件");
        assertEquals("/css/app.css", light.get(light.size() - 1), "app.css 必须最后加载");
        assertEquals("/css/app.css", dark.get(dark.size() - 1), "app.css 必须最后加载");
        assertEquals(2, light.size(), "本任务只加载 tokens + app 两张表");
    }
}
