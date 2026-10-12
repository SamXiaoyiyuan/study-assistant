package com.xiaoyiyuan.study.ui.theme;

import java.util.Locale;

/**
 * 主题模式：只有明 / 暗两档。
 *
 * <p><b>为什么没有 {@code SYSTEM}</b>：指南 §2.5 把「JavaFX 22+ 的 {@code Platform.getPreferences()}
 * 能否读到系统深浅色偏好」列为必须实测的悬置项。本任务实测结论是<b>不能</b>——
 * JavaFX 暴露的全部偏好键里没有 Windows 的「应用模式」开关（{@code AppsUseLightTheme}），
 * {@code getColorScheme()} 只是由系统背景/前景色亮度推导出的值。本机
 * {@code SystemUsesLightTheme = 0}（系统已是深色）而 JavaFX 仍报 {@code LIGHT}，
 * 背景/前景是纯白/纯黑。因此设置页只提供手动两档，不去臆造「跟随系统」。</p>
 *
 * <p>证据见 {@code task-1-report.md} 与 {@code PlatformPreferencesTest}。</p>
 */
public enum ThemeMode {

    /** 明色主题：{@code tokens-light.css}。 */
    LIGHT("明"),

    /** 暗色主题：{@code tokens-dark.css}（降饱和 + 提亮同色相，不是反相）。 */
    DARK("暗");

    private final String label;

    ThemeMode(String label) {
        this.label = label;
    }

    /** 切换用：返回另一个模式（两档互为切换目标）。 */
    public ThemeMode other() {
        return this == LIGHT ? DARK : LIGHT;
    }

    /** 界面文案（设置页的明/暗切换用）。 */
    public String label() {
        return label;
    }

    /** 令牌文件名的后缀（{@code light} / {@code dark}）。 */
    String tokensSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
