package com.xiaoyiyuan.study.ui.theme;

import java.util.TreeMap;

import javafx.application.ColorScheme;
import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>悬置项 2 的实跑结论</b>（指南 §2.5「未验证项（必须实测）」）：JavaFX 22+ 的
 * {@code Platform.getPreferences()} 能否读到<b>系统深浅色偏好</b>，从而让设置页的「跟随系统」可实现。
 *
 * <p>实测结论（证据见 task-1-report.md）：API <b>存在且可读</b>，但
 * ① 它不是 Windows「应用模式」开关（{@code AppsUseLightTheme}）——JavaFX
 * 暴露的偏好键里根本没有这一项（已把全部键打印出来核对）；
 * ② {@code getColorScheme()} 是由 {@code backgroundColor} / {@code foregroundColor} 的亮度<b>推导</b>出来的；
 * ③ 本机 {@code SystemUsesLightTheme = 0}（系统已是深色）而 {@code getColorScheme() = LIGHT}，
 * 且背景/前景是纯 {@code #FFFFFF} / {@code #000000}。</p>
 *
 * <p>因此：本项目<b>不实现「跟随系统」</b>，明/暗两档手动切换（{@code ThemeMode} 只有 LIGHT / DARK）。
 * 这条测试把这个结论钉住：将来 JavaFX 行为变化时它会红，逼后来的人重新评估。</p>
 */
class PlatformPreferencesTest {

    @BeforeAll
    static void startFx() {
        FxToolkit.ensureStarted();
    }

    /**
     * 在 FX 应用线程上执行并取回结果。
     *
     * <p><b>实测细节</b>：{@code Platform.getPreferences()} 及其返回对象上的读取
     * <b>必须</b>在 FX 应用线程上做——从 main 线程直接读会抛
     * {@code IllegalStateException: Not on FX application thread}（第一版测试就是这么红的）。
     * 这也意味着后续任务在页面里读它时不需要额外处理（页面本来就跑在 FX 线程）。</p>
     */
    private static <T> T onFxThread(java.util.concurrent.Callable<T> body) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(body.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("FX 线程 60 秒内没有完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (error.get() != null) {
            throw new IllegalStateException("FX 线程上抛异常：" + error.get(), error.get());
        }
        return result.get();
    }

    @Test
    void readingPreferencesFromANonFxThread_isRejected_soCallersMustBeOnTheFxThread() {
        // 这条不是教条：它就是第一版测试红掉的原因，钉住它免得后来的人又踩
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, Platform::getPreferences);
        assertTrue(thrown.getMessage().contains("Not on FX application thread"),
                "从非 FX 线程读偏好必须抛 'Not on FX application thread'，实际：" + thrown.getMessage());
    }

    @Test
    void getPreferences_isReadableButProvidesNoWindowsAppsUseLightThemeKey() {
        TreeMap<String, Object> keys = onFxThread(() -> new TreeMap<>(Platform.getPreferences()));
        assertFalse(keys.isEmpty(), "偏好键集合不得为空（实测非空）");
        System.out.println("[evidence] preference key count = " + keys.size());
        keys.forEach((k, v) -> System.out.println("[evidence]   " + k + " = " + v));

        for (String key : keys.keySet()) {
            assertFalse(key.toLowerCase(java.util.Locale.ROOT).contains("appsuselighttheme"),
                    "JavaFX 不得直接暴露 AppsUseLightTheme（若出现，说明行为变了，需重新评估「跟随系统」）：" + key);
            assertFalse(key.toLowerCase(java.util.Locale.ROOT).contains("systemuseslighttheme"),
                    "JavaFX 不得直接暴露 SystemUsesLightTheme：" + key);
        }
        // JavaFX 真正给的与深浅色有关的东西：系统色 + HighContrast 标志
        assertTrue(keys.keySet().stream().anyMatch(k -> k.equals("Windows.SPI.HighContrast")),
                "实测应存在 Windows.SPI.HighContrast；实际键=" + keys.keySet());
        assertFalse(keys.containsKey("Windows.SPI.HighContrastColorScheme"),
                "非高对比度模式下不出现 HighContrastColorScheme（实测缺席）");
    }

    @Test
    void colorScheme_isDerivedFromSystemColors_andOnThisMachineDoesNotFollowTheDarkSystemTheme() {
        record Snapshot(ColorScheme scheme, String background, String foreground, String accent,
                        boolean reducedMotion, Object clientAreaAnimation, boolean highContrast) {
        }
        Snapshot snap = onFxThread(() -> {
            Platform.Preferences prefs = Platform.getPreferences();
            return new Snapshot(prefs.getColorScheme(), prefs.getBackgroundColor().toString(),
                    prefs.getForegroundColor().toString(), prefs.getAccentColor().toString(),
                    prefs.isReducedMotion(), prefs.get("Windows.SPI.ClientAreaAnimation"),
                    Boolean.TRUE.equals(prefs.get("Windows.SPI.HighContrast")));
        });
        System.out.println("[evidence] getColorScheme() = " + snap.scheme());
        System.out.println("[evidence] backgroundColor = " + snap.background());
        System.out.println("[evidence] foregroundColor = " + snap.foreground());
        System.out.println("[evidence] accentColor = " + snap.accent());
        System.out.println("[evidence] isReducedMotion() = " + snap.reducedMotion()
                + " (Windows.SPI.ClientAreaAnimation=" + snap.clientAreaAnimation() + ")");
        System.out.println("[evidence] Windows.SPI.HighContrast = " + snap.highContrast());

        assertNotNull(snap.scheme(), "getColorScheme() 必须返回 ColorScheme（其 @defaultValue 是 LIGHT）");

        // 本机实测：系统已是深色（SystemUsesLightTheme=0）却报 LIGHT —— 这正是不能拿它做「跟随系统」的理由
        assertEquals(ColorScheme.LIGHT, snap.scheme(),
                "本机实测值；若这里变了（例如 JavaFX 开始跟随 AppsUseLightTheme），「跟随系统」需要重新评估");
        assertEquals("0xffffffff", snap.background(), "实测背景是纯白，不随系统深色变化");
        assertEquals("0x000000ff", snap.foreground(), "实测前景是纯黑，不随系统深色变化");
        assertFalse(snap.highContrast(), "本机未开高对比度（实测），因此 HighContrastColorScheme 缺席");
    }
}
