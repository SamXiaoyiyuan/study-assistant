package com.xiaoyiyuan.study.ui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.Scene;

/**
 * 视觉令牌的<b>唯一来源</b>。
 *
 * <p>Java 代码里一个硬编码十六进制颜色都没有：本枚举只登记令牌<b>名字</b>，值是启动时从
 * {@code /css/tokens-light.css} 与 {@code /css/tokens-dark.css} 里解析出来的
 * （字形是井号加六位十六进制，{@code ThemeTokensTest} 逐条断言这个格式；
 * {@code TokenValueSourceTest} 另有一条断言扫描 {@code src/main/java} 下不存在这种字面量）。
 * 这两个 CSS 文件的每个值又与
 * {@code docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md} 逐字一致（由
 * {@code TokenValueSourceTest} 直接从指南 markdown 解析后比对）。</p>
 *
 * <p>解析走纯文本正则（{@code getResourceAsStream}），<b>不启动 JavaFX 运行时</b>：
 * 用 {@code Scene} 渲染再读 {@code getComputedStyle} 会把单元测试变成一个「本机图形栈起不起得来」的抽奖。</p>
 */
public enum ThemeTokens {

    // ---------- 面 / 描边 ----------
    BG("-fx-c-bg"),
    SURFACE("-fx-c-surface"),
    SURFACE_MUTED("-fx-c-surface-muted"),
    SURFACE_STRIPE("-fx-c-surface-stripe"),
    SURFACE_HOVER("-fx-c-surface-hover"),
    SURFACE_SELECTED("-fx-c-surface-selected"),
    ACCENT_SOFT("-fx-c-accent-soft"),
    BORDER_SUBTLE("-fx-c-border-subtle"),
    BORDER_CONTROL("-fx-c-border-control"),

    // ---------- 文本 ----------
    TEXT("-fx-c-text"),
    TEXT_SECONDARY("-fx-c-text-secondary"),
    TEXT_TERTIARY("-fx-c-text-tertiary"),
    TEXT_DISABLED("-fx-c-text-disabled"),

    // ---------- 主色 ----------
    ACCENT("-fx-c-accent"),
    ACCENT_HOVER("-fx-c-accent-hover"),
    ON_ACCENT("-fx-c-on-accent"),
    FOCUS("-fx-c-focus"),

    // ---------- 中性提示条（替代 Alert.WARNING / ERROR） ----------
    NOTICE_BG("-fx-c-notice-bg"),
    NOTICE_BORDER("-fx-c-notice-border"),
    NOTICE_TEXT("-fx-c-notice-text"),

    // ---------- 四态中性色 + 待回报（全部中性，无红/绿语义） ----------
    STATE_ACTIVE("-fx-c-state-active"),
    STATE_DONE("-fx-c-state-done"),
    STATE_PAUSED("-fx-c-state-paused"),
    STATE_CANCELLED("-fx-c-state-cancelled"),
    STATE_AWAIT("-fx-c-state-await"),
    STATE_AWAIT_LINE("-fx-c-state-await-line"),

    // ---------- 危机资源面板（暖砂中性，非警报色） ----------
    CRISIS_BG("-fx-c-crisis-bg"),
    CRISIS_BORDER("-fx-c-crisis-border"),
    CRISIS_BAR("-fx-c-crisis-bar"),
    CRISIS_TEXT("-fx-c-crisis-text"),
    CRISIS_META("-fx-c-crisis-meta"),

    // ---------- 图表序列色：必须覆盖 Modena 默认（CHART_COLOR_1 默认是橙红） ----------
    CHART_1("CHART_COLOR_1"),
    CHART_2("CHART_COLOR_2"),
    CHART_3("CHART_COLOR_3"),
    CHART_4("CHART_COLOR_4"),
    CHART_5("CHART_COLOR_5"),
    CHART_6("CHART_COLOR_6"),
    CHART_7("CHART_COLOR_7"),
    CHART_8("CHART_COLOR_8");

    /** 令牌文件（classpath 路径），明暗各一份，键完全一致。 */
    static final String TOKENS_LIGHT = "/css/tokens-light.css";
    static final String TOKENS_DARK = "/css/tokens-dark.css";

    /** 组件级样式，必须最后加载（否则组件样式被基线覆盖）。 */
    static final String APP_CSS = "/css/app.css";

    /** 字体族回退链（与 CSS 里 {@code -fx-font-family} 的声明逐字一致）。 */
    public static final List<String> FONT_FAMILIES = List.of(
            "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI");

    /** 非颜色令牌（字号 / 行距 / 间距 / 圆角 / 阴影 / 字体族 / 根字号）的名字，用于 {@link #nonColorValue}。 */
    public static final List<String> NON_COLOR_KEYS = List.of(
            "-fx-font-family", "-fx-font-size",
            "-fx-fs-micro", "-fx-fs-caption", "-fx-fs-small", "-fx-fs-body",
            "-fx-fs-h3", "-fx-fs-h2", "-fx-fs-h1", "-fx-fs-display",
            "-fx-ls-body", "-fx-ls-head", "-fx-ls-read",
            "-fx-sp-1", "-fx-sp-2", "-fx-sp-3", "-fx-sp-4", "-fx-sp-5", "-fx-sp-6", "-fx-sp-7",
            "-fx-r-control", "-fx-r-card", "-fx-r-dialog", "-fx-r-pill",
            "-fx-sh-card", "-fx-sh-raised", "-fx-sh-dialog");

    /** {@code -fx-c-foo: #RRGGBB;} 与 {@code CHART_COLOR_1: #RRGGBB;}。 */
    private static final Pattern COLOR_DECL =
            Pattern.compile("^\\s*(-fx-c-[a-z-]+|CHART_COLOR_[1-8])\\s*:\\s*(#[0-9A-Fa-f]{6})\\s*;");

    /** 任意 {@code name: value;}（值里允许逗号、括号、引号）。 */
    private static final Pattern ANY_DECL =
            Pattern.compile("^\\s*(-fx-[a-z0-9-]+)\\s*:\\s*(.+?)\\s*;\\s*$");

    private final String key;

    ThemeTokens(String key) {
        this.key = key;
    }

    /** looked-up color 名（{@code -fx-c-bg} 这类），不带首尾空白。 */
    public String key() {
        return key;
    }

    /** 本令牌是否为图表序列色（{@code CHART_COLOR_n}，没有 {@code -fx-c-} 前缀）。 */
    public boolean isChartColor() {
        return key.startsWith("CHART_COLOR_");
    }

    /**
     * 某个模式要加载的样式表（顺序即加载顺序，{@code app.css} 永远最后）。
     */
    public static List<String> cssFiles(ThemeMode mode) {
        return List.of(mode == ThemeMode.DARK ? TOKENS_DARK : TOKENS_LIGHT, APP_CSS);
    }

    /** 令牌在指定主题下的十六进制值（井号 + 六位十六进制，大写）。 */
    public static String valueOf(ThemeTokens token, ThemeMode mode) {
        String value = values(mode).get(token.key);
        if (value == null) {
            throw new IllegalStateException(
                    "令牌 " + token.key + " 在 " + mode + " 主题的 " + tokensResource(mode) + " 里没有定义");
        }
        return value;
    }

    /** 非颜色令牌在指定主题下的原始值（如 {@code -fx-sp-4: 1.07em}、{@code -fx-font-size: 15px}）。 */
    public static String nonColorValue(String key, ThemeMode mode) {
        String value = nonColorValues(mode).get(key);
        if (value == null) {
            throw new IllegalStateException("非颜色令牌 " + key + " 在 " + mode + " 主题里没有定义");
        }
        return value;
    }

    /** 令牌文件里全部项目颜色令牌（键 → 值），按文件顺序。 */
    static Map<String, String> values(ThemeMode mode) {
        return holder(mode).parsed().colors();
    }

    /** 令牌文件里全部非颜色令牌（键 → 值）。 */
    static Map<String, String> nonColorValues(ThemeMode mode) {
        return holder(mode).parsed().all();
    }

    /** 每个令牌文件只解析一次；用「不可变记录 + final 字段」保证线程安全与可见性。 */
    private static Tokens holder(ThemeMode mode) {
        return mode == ThemeMode.DARK ? DarkHolder.INSTANCE : LightHolder.INSTANCE;
    }

    private record Tokens(Parsed parsed) {
    }

    private static final class LightHolder {
        static final Tokens INSTANCE = new Tokens(parse(TOKENS_LIGHT));
    }

    private static final class DarkHolder {
        static final Tokens INSTANCE = new Tokens(parse(TOKENS_DARK));
    }

    /** 主题的令牌资源路径。 */
    public static String tokensResource(ThemeMode mode) {
        return mode == ThemeMode.DARK ? TOKENS_DARK : TOKENS_LIGHT;
    }

    /** 从 classpath 读资源文本（UTF-8）。 */
    public static String readResource(String path) {
        try (InputStream in = ThemeTokens.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 解析令牌文件：先剥掉块注释，再逐行取 {@code name: value;}。
     * 只保留 {@code -fx-*} 与 {@code CHART_COLOR_*}，忽略 Modena 重着色以外的任何东西。
     */
    static Parsed parse(String path) {
        String text = readResource(path).replaceAll("(?s)/\\*.*?\\*/", " ");
        Map<String, String> colors = new LinkedHashMap<>();
        Map<String, String> all = new LinkedHashMap<>();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("}") || line.endsWith("{")) {
                continue;
            }
            Matcher color = COLOR_DECL.matcher(line);
            if (color.matches()) {
                colors.put(color.group(1), color.group(2).toUpperCase(Locale.ROOT));
                all.put(color.group(1), color.group(2).toUpperCase(Locale.ROOT));
                continue;
            }
            Matcher any = ANY_DECL.matcher(line);
            if (any.matches()) {
                all.put(any.group(1), any.group(2));
            }
        }
        return new Parsed(Collections.unmodifiableMap(colors), Collections.unmodifiableMap(all));
    }

    /** 一个令牌文件解析出的结果。 */
    record Parsed(Map<String, String> colors, Map<String, String> all) {
    }
}
