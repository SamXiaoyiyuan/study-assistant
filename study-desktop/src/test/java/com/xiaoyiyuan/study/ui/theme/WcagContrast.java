package com.xiaoyiyuan.study.ui.theme;

/**
 * WCAG 2.x 对比度，自己实现（不读指南转述的数字，不引入第三方依赖）。
 *
 * <p>相对亮度按 WCAG 定义：通道先做 sRGB 反伽马
 * （{@code c <= 0.04045 ? c/12.92 : ((c+0.055)/1.055)^2.4}），再按
 * {@code 0.2126R + 0.7152G + 0.0722B} 加权。对比度为
 * {@code (Llight + 0.05) / (Ldark + 0.05)}。</p>
 */
final class WcagContrast {

    private WcagContrast() {
    }

    /** 单个 0–255 通道 → 线性分量。 */
    static double linearize(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** {@code #RRGGBB} → 相对亮度。 */
    static double relativeLuminance(String hex) {
        int[] rgb = CssTokens.channels(hex);
        return 0.2126 * linearize(rgb[0]) + 0.7152 * linearize(rgb[1]) + 0.0722 * linearize(rgb[2]);
    }

    /** 两个 {@code #RRGGBB} 的对比度，顺序无关。 */
    static double ratio(String a, String b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 固定 2 位小数的字符串（用于断言消息里带上可核对的具体数字）。 */
    static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
