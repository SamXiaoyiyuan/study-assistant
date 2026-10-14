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
import java.util.stream.Stream;

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
     * 第 ① 类长度属性：查找值会被 {@code ClassCastException} <b>静默忽略</b>（N1 本身）。
     *
     * <p>这些属性的转换器要的是 {@code javafx.css.Size}，而查找值（{@code -fx-sp-*} /
     * {@code -fx-r-*} 这类被定义成 {@code 0.27em} 的自定义令牌）在运行时被解析成 {@code Double}，
     * 于是 JavaFX 25 抛 {@code ClassCastException: Double cannot be cast to Size} 并忽略该声明——
     * 全站内边距与圆角实际为 0，而 {@code CssParser.errorsProperty()} 一声不响。
     * 修复前 {@code app.css} 里这类声明共 31 处（内边距 10 / 圆角 21），全部失效。</p>
     */
    static final List<String> CCE_IGNORED_LENGTH_PROPERTIES = List.of(
            "-fx-padding",
            "-fx-background-radius", "-fx-border-radius",
            "-fx-background-insets", "-fx-border-insets",
            "-fx-border-width");

    /**
     * 第 ② 类长度属性：查找值<b>合法且生效</b>（{@code Region} 的转换器接受 {@code Number}），
     * 把它们也写成字面值只是<b>统一约定</b>——与 em 缩放一致、便于审计，
     * <b>不是</b>因为它们会失效。
     *
     * <p>2026-10-14 minor 清算批 MEDIUM-1 的实测结论（本机）：{@code -fx-min-height: -fx-sp-6} →
     * {@code minHeight=32.0}、{@code -fx-spacing: -fx-sp-4} → {@code spacing=16.1}，
     * 渲染期 {@code ClassCastException} 计数为 0。旧 javadoc 把这两类混为一谈、说它们「同样被忽略」，
     * 那条理由已被证伪（`CLAUDE.md` 的铁律文字由控制器另改）。</p>
     */
    static final List<String> CONVENTION_LENGTH_PROPERTIES = List.of(
            "-fx-spacing",
            "-fx-min-width", "-fx-max-width", "-fx-pref-width",
            "-fx-min-height", "-fx-max-height", "-fx-pref-height");

    /**
     * <b>长度类</b> JavaFX CSS 属性名（不含单位后缀）的完整白名单 = 上面两类之和。
     *
     * <p>两类「为什么写字面值」的<b>理由不同</b>（一类是被 JavaFX 静默忽略，一类是个人约定），
     * 但<b>判定集合与结论完全相同</b>：护栏对名单内的属性一律判「不得引用 {@code -fx-*} 查找值」。
     * 拆理由不改判据——这条由
     * {@code ColorAuditTest.lengthClassProperties_areSplitByTheirRealReason_disjointAndComplete} 守着。</p>
     *
     * <p>名单是<b>显式白名单</b>：上游新增一个长度属性而这里没有收录，护栏不会因此变松——
     * {@link #lengthClassDeclarationsWithLookupValue} 只对名单内的属性判红，误报只会出现在名单外。</p>
     */
    static final List<String> LENGTH_CLASS_PROPERTIES = List.copyOf(
            Stream.concat(CCE_IGNORED_LENGTH_PROPERTIES.stream(),
                    CONVENTION_LENGTH_PROPERTIES.stream()).toList());

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
        String noComments = stripCommentsPreservingLines(read(resourcePath));
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

    /**
     * 剥掉块注释，<b>保留注释内的换行</b>，使 {@link #parse} 报出的行号与真实文件行号一一对应。
     *
     * <p>LOW-1（2026-10-14 minor 清算批）：旧实现把整段注释替换成单个空格，多行注释里的换行被吃掉，
     * 于是位于注释之后的声明报出的行号偏小（{@code app.css} 头部 15 行注释 → 偏移 −14：
     * {@code .card} 的 {@code -fx-padding} 报 {@code line 29}、真实行号 43）。
     * 现在把注释里的每个换行原样补回、其余字符合并成空格，因此行数不变、也不会把相邻 token 粘起来。</p>
     */
    static String stripCommentsPreservingLines(String text) {
        Matcher matcher = Pattern.compile("(?s)/\\*.*?\\*/").matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            // 注释内的换行原样保留，其余字符逐个换成空格（不改行号、不粘连相邻声明）
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(matcher.group().replaceAll("[^\\n]", " ")));
        }
        matcher.appendTail(out);
        return out.toString();
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
        String text = stripCommentsPreservingLines(read(resourcePath));
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
