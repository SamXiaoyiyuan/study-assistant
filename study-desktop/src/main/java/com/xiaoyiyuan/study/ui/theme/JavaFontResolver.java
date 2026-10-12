package com.xiaoyiyuan.study.ui.theme;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Parent;
import javafx.scene.text.Font;

import javafx.scene.text.Text;

/**
 * 字体族的 Java 侧回退链。
 *
 * <p><b>为什么需要它</b>（本任务实测，见 {@code FxFontResolutionTest} 与 task-1-report.md）：
 * JavaFX CSS 能无错接受 {@code -fx-font-family: "A", "B", "C";}，但
 * {@code FontConverter} 只取<b>列表第一项</b>；列表后面的族名既不参与解析、也不能在 Java 侧当独立族名用。
 * 而本项目要用的是 Windows 原生中文字体「微软雅黑 UI」，它并非所有机器都有，
 * 缺失时 {@code Font.font} 会<b>静默退化成 {@code System}</b>（中文可能变方块）。
 * 因此按 {@code Font.getFamilies()} 逐级挑第一个真实存在的族，用一条 Java 侧的字体放上去。</p>
 *
 * <p>这是对指南 §1.4「若在本机出现族列表解析异常，退路是在 Java 侧 {@code Font.font(...)} 后逐节点
 * {@code setFont()}}」的<b>强化</b>：即使族列表解析没有异常，回退链也只在 Java 侧真实存在。</p>
 */
public final class JavaFontResolver {

    private JavaFontResolver() {
    }

    /** 第一个在 {@code Font.getFamilies()} 里真实存在的族；全都不存在时返回 {@code null}。 */
    public static String primaryAvailableFamily() {
        List<String> available = Font.getFamilies();
        return firstAvailableFamily(available);
    }

    /**
     * 按顺序挑第一个可用族（纯函数，便于测试）。
     *
     * @param availableFamilies {@code Font.getFamilies()} 的内容
     * @return 第一个命中的族名；都缺失时 {@code null}
     */
    static String firstAvailableFamily(List<String> availableFamilies) {
        for (String family : ThemeTokens.FONT_FAMILIES) {
            if (availableFamilies.contains(family)) {
                return family;
            }
        }
        return null;
    }

    /**
     * 给某个子树的根节点放上解析出的族名（字号仍由令牌文件的 {@code -fx-font-size} 决定）。
     *
     * <p>用内联样式而不是 {@code root.setFont(...)}：{@code Parent} 没有 {@code setFont}，
     * 而 {@code -fx-font-family} 是可继承属性，放在根节点上整个子树都会跟随；
     * 放上去的只是<b>族名</b>，字号、间距、颜色仍然全部来自令牌文件。</p>
     *
     * @param root   页面的根节点
     * @param family 已解析出的族名；{@code null} 表示本机一个声明的族都没有，此时不动字体
     */
    public static void applyTo(Parent root, String family) {
        if (family == null) {
            return;
        }
        root.setStyle("-fx-font-family: \"" + safeFamilyName(family) + "\";");
    }

    /** 给单个文本节点设置字体（供后续任务在需要逐节点设置时使用）。 */
    public static void applyTo(Text text, String family) {
        if (family == null) {
            return;
        }
        text.setFont(Font.font(family, rootFontSizePx()));
    }

    /** 族名进内联样式前的自检：不允许引号/分号/花括号，否则可能是注入。 */
    static String safeFamilyName(String family) {
        if (family.indexOf('"') >= 0 || family.indexOf('\'') >= 0
                || family.indexOf(';') >= 0 || family.indexOf('{') >= 0 || family.indexOf('}') >= 0) {
            throw new IllegalArgumentException("字体族名里不许出现引号/分号/花括号：" + family);
        }
        return family;
    }

    /**
     * 本次启动应给全部页面使用的字体。
     *
     * @return 已解析的字体；本机连首选族都没有时返回 {@code null}（交给 CSS 的默认字体）
     */
    public static Font resolveFont() {
        String family = primaryAvailableFamily();
        return family == null ? null : Font.font(family, rootFontSizePx());
    }

    /** 声明的族列表（供设置页/诊断显示）。 */
    public static List<String> declaredFamilies() {
        return ThemeTokens.FONT_FAMILIES;
    }

    /** 本机缺失的声明族（供设置页/诊断显示，不臆造回退成功的假象）。 */
    public static List<String> missingFamilies() {
        List<String> available = Font.getFamilies();
        List<String> missing = new ArrayList<>();
        for (String family : ThemeTokens.FONT_FAMILIES) {
            if (!available.contains(family)) {
                missing.add(family);
            }
        }
        return List.copyOf(missing);
    }

    /** 根字号（逻辑像素），唯一来源是 tokens-*.css 的 {@code -fx-font-size}。 */
    static double rootFontSizePx() {
        String raw = ThemeTokens.nonColorValue("-fx-font-size", ThemeMode.LIGHT);
        return Double.parseDouble(raw.replace("px", "").trim());
    }
}
