package com.xiaoyiyuan.study.ui.theme;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ThemeManager#attach(Scene)} / {@link ThemeManager#setMode(ThemeMode)} 的<b>语义</b>护栏（fix round 1）。
 *
 * <p>评审的 M13 / M14 证明这半以前完全没被守住：把 {@code applyTo} 里的 {@code setAll} 换成
 * {@code addAll}、或者把 {@code app.css} 从样式表列表里丢掉，56 条测试<b>全绿</b>。
 * 后果在真机上很难查：「切了没反应」（旧令牌后加载、反而生效）／「组件样式整体消失」。</p>
 *
 * <p>不需要新的测试基建：{@code new Scene(new StackPane())} 就是纯 Java 对象操作，
 * 只要 JavaFX 工具包起来了就能用（{@code FxToolkit} 本任务已有）。</p>
 */
class ThemeManagerAttachTest {

    @BeforeAll
    static void startFx() {
        FxToolkit.ensureStarted();
    }

    static Scene scene() {
        return new Scene(new StackPane());
    }

    static boolean has(Scene scene, String suffix) {
        return scene.getStylesheets().stream().anyMatch(s -> s.endsWith(suffix));
    }

    @Test
    void attach_installsExactlyTwoStylesheets_tokenFileFirstAndAppCssLast() {
        Scene scene = scene();
        new ThemeManager(ThemeMode.LIGHT).attach(scene);
        assertEquals(2, scene.getStylesheets().size(),
                "attach 必须恰好装 2 张表，实际 " + scene.getStylesheets());
        assertTrue(scene.getStylesheets().get(0).endsWith("/css/tokens-light.css"),
                "第一张必须是令牌表，实际 " + scene.getStylesheets().get(0));
        assertTrue(scene.getStylesheets().get(1).endsWith("/css/app.css"),
                "attach 必须把 app.css 一起装上（丢掉它 = 只有令牌、没有组件样式，且不会有别的测试报警），实际 "
                        + scene.getStylesheets().get(1));
    }

    @Test
    void attachingTwice_doesNotGrowTheStylesheetList() {
        Scene scene = scene();
        ThemeManager manager = new ThemeManager(ThemeMode.LIGHT);
        manager.attach(scene);
        manager.attach(scene);
        assertEquals(2, scene.getStylesheets().size(),
                "连续 attach 两次必须整体替换（setAll 语义）：add/addAll 会变成 4 张，实际 " + scene.getStylesheets());
    }

    @Test
    void switchingThemeTwice_doesNotGrowTheStylesheetList() {
        Scene scene = scene();
        ThemeManager manager = new ThemeManager(ThemeMode.LIGHT);
        manager.attach(scene);
        manager.setMode(ThemeMode.DARK);
        assertEquals(2, scene.getStylesheets().size(),
                "切换主题必须整体替换（setAll 语义）：add/addAll 会叠加成 4 张，而更晚加载的旧令牌反而生效——"
                        + "表现是「切了没反应」，实际 " + scene.getStylesheets());
        manager.setMode(ThemeMode.LIGHT);
        assertEquals(2, scene.getStylesheets().size(),
                "再切回来仍然只能有 2 张，实际 " + scene.getStylesheets());
    }

    @Test
    void switchingTheme_leavesNoStylesheetOfTheOldTheme() {
        Scene scene = scene();
        ThemeManager manager = new ThemeManager(ThemeMode.DARK);
        manager.attach(scene);
        assertTrue(has(scene, "/css/tokens-dark.css"));
        assertFalse(has(scene, "/css/tokens-light.css"),
                "初值是 DARK，不该出现明色令牌表：" + scene.getStylesheets());

        manager.setMode(ThemeMode.LIGHT);
        assertTrue(has(scene, "/css/tokens-light.css"));
        assertFalse(has(scene, "/css/tokens-dark.css"),
                "切到明色后，暗色令牌表不得残留（残留的那份后加载、会盖住新主题）：" + scene.getStylesheets());
        assertTrue(has(scene, "/css/app.css"), "切换后 app.css 仍必须在：" + scene.getStylesheets());
    }

    @Test
    void detach_stopsApplyingToTheOldScene() {
        Scene scene = scene();
        ThemeManager manager = new ThemeManager(ThemeMode.LIGHT);
        manager.attach(scene);
        manager.detach();
        manager.setMode(ThemeMode.DARK);
        assertFalse(has(scene, "/css/tokens-dark.css"),
                "detach 之后 setMode 不该再动那个场景：" + scene.getStylesheets());
    }
}
