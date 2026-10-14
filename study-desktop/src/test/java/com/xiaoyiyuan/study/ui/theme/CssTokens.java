package com.xiaoyiyuan.study.ui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从令牌 CSS 里把 {@code 属性名: 值} 逐条抽出来（纯文本解析，不启动 JavaFX 运行时）。
 *
 * <p>只服务于测试：这是「CSS 令牌 ↔ 指南」这条护栏的取值入口。断言必须建立在
 * <b>解析结果</b>上，不能建立在「我看过了」上。</p>
 */
final class CssTokens {

    /** 一条声明的原始值（已去掉行尾的 {@code ;} 与首尾空白，块注释已剥离）。 */
    record Decl(String selector, String property, String rawValue, int line) {
    }

    /** {@code #RRGGBB} 或 {@code #RRGGBBAA} 字面量。 */
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?");

    /** 任意「数字 + 单位」：px / em / % / pt。 */
    private static final Pattern LENGTH = Pattern.compile("(\\d+(?:\\.\\d+)?)(px|em|%)");

    /**
     * <b>长度类</b> JavaFX CSS 属性名（不含单位后缀）。
     *
     * <p>N1（2026-10-14 fix round 2）的教训：这类属性的转换器要的是 {@code javafx.css.Size} 或
     * {@code Number}，而查找值（{@code -fx-sp-*} / {@code -fx-r-*} 这类被定义成 {@code 0.27em} 的
     * 自定义令牌）在运行时被解析成 {@code Double}，于是 JavaFX 25 抛
     * {@code ClassCastException: Double cannot be cast to Size} 并<b>忽略该声明</b>——
     * 全站内边距与圆角实际为 0，而 {@code CssParser.errorsProperty()} 一声不响。
     *
     * <p>因此凡是出现在这里的属性，值一律只能是<b>字面数值</b>（可带 {@code em} / {@code px} 单位）。
     * 名单是<b>显式白名单</b>：上游新增一个长度属性而这里没有收录，护栏不会因此变松——
     * {@link #lengthClassDeclarationsWithLookupValue} 只对名单内的属性判红，误报只会出现在名单外。
     */
    static final List<String> LENGTH_CLASS_PROPERTIES = List.of(
            "-fx-padding",
            "-fx-background-radius", "-fx-border-radius",
            "-fx-background-insets", "-fx-border-insets",
            "-fx-border-width",
            "-fx-spacing",
            "-fx-min-width", "-fx-max-width", "-fx-pref-width",
            "-fx-min-height", "-fx-max-height", "-fx-pref-height");

    /**
     * 找出「长度类属性被赋了查找值（值里出现 {@code -fx-*}）」的声明，按文件顺序返回。
     *
     * @return 每条描述成 {@code selector { property: value } (line N)}，便于直接写进断言消息
     */
    static List<String> lengthClassDeclarationsWithLookupValue(String resourcePath) {
        List<String> offenders = new ArrayList<>();
        for (Decl d : parse(resourcePath)) {
            if (!LENGTH_CLASS_PROPERTIES.contains(d.property())) {
                continue;
            }
            Matcher m = Pattern.compile("-fx-[a-z][a-z0-9-]*").matcher(d.rawValue());
            if (m.find()) {
                offenders.add(d.selector() + " { " + d.property() + ": " + d.rawValue() + " } (line "
                        + d.line() + "，引用 " + m.group() + ")");
            }
        }
        return offenders;
    }

    private CssTokens() {
    }

    static String read(String resourcePath) {
        try (InputStream in = CssTokens.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到 " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Decl> parse(String resourcePath) {
        String text = read(resourcePath);
        String noComments = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        List<Decl> out = new ArrayList<>();
        String selector = null;
        int line = 1;
        for (String rawLine : noComments.split("\n", -1)) {
            String trimmed = rawLine.trim();
            if (trimmed.endsWith("{")) {
                selector = trimmed.substring(0, trimmed.length() - 1).trim();
            } else if (trimmed.startsWith("}")) {
                selector = null;
            } else if (selector != null && trimmed.contains(":")) {
                String body = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
                int colon = body.indexOf(':');
                String property = body.substring(0, colon).trim();
                String value = body.substring(colon + 1).trim();
                if (!property.isEmpty() && !value.isEmpty()) {
                    out.add(new Decl(selector, property, value, line));
                }
            }
            line++;
        }
        return out;
    }

    /** 属性名 → 原始值（同一属性重复定义时保留最后一个，与 CSS 级联一致）。 */
    static Map<String, String> byProperty(String resourcePath) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Decl d : parse(resourcePath)) {
            map.put(d.property, d.rawValue);
        }
        return map;
    }

    /** 只保留 {@code -fx-c-*} 项目颜色令牌。 */
    static Map<String, String> projectColors(String resourcePath) {
        Map<String, String> map = new LinkedHashMap<>();
        byProperty(resourcePath).forEach((k, v) -> {
            if (k.startsWith("-fx-c-")) {
                map.put(k, v);
            }
        });
        return map;
    }

    static List<String> hexLiterals(String resourcePath) {
        List<String> out = new ArrayList<>();
        Matcher m = HEX.matcher(read(resourcePath));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** 资源文件里出现过的所有「数字+单位」，按出现顺序（数字保留原样字符串，避免精度/解析歧义）。 */
    static List<Map.Entry<String, String>> units(String resourcePath) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        String text = read(resourcePath).replaceAll("(?s)/\\*.*?\\*/", " ");
        Matcher m = LENGTH.matcher(text);
        while (m.find()) {
            out.add(Map.entry(m.group(2), m.group(1)));
        }
        return out;
    }

    /** 裸形式的值是否合法 {@code #RRGGBB}。 */
    static boolean isSixDigitHex(String value) {
        return value.matches("#[0-9A-Fa-f]{6}");
    }

    /** {@code #RRGGBB} → 三个 0–255 通道；非法输入抛 {@link IllegalArgumentException}。 */
    static int[] channels(String hex) {
        if (!isSixDigitHex(hex)) {
            throw new IllegalArgumentException("不是 6 位十六进制色值：" + hex);
        }
        return new int[] {
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)
        };
    }
}
