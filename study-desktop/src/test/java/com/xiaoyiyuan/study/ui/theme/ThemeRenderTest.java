package com.xiaoyiyuan.study.ui.theme;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「暗色主题在<b>真场景</b>里生效」的渲染断言（minor 清算批 Minor-2）。
 *
 * <p>为什么需要这条：Task 2 评审（task-2-review.md §2.2）指出，外壳层的主题证据
 * <b>全在令牌值 / 对比度层</b>——把 {@code tokens-dark.css} 的 {@code -fx-c-bg} 改成
 * {@code #00FF00} 会有 6 条变红，但<b>没有一条</b>断言「外壳起来后暗色真的在场景里生效」；
 * {@code scene.getStylesheets()} 只证明「哪张表被装上」，不证明它算出来的样式是什么。</p>
 *
 * <p>本测试用外壳同一条路径（{@link ThemeManager#attach(Scene)} + {@link ThemeManager#setMode}）
 * 在一个真 {@link Scene} 上 {@code applyCss() + layout()}，再读回<b>计算后的</b>结果：
 * 卡片面 {@code .card → -fx-c-surface}、正文字色 {@code .body → -fx-c-text}、实测根字号。
 * 三者都必须等于对应主题令牌换算出来的值，且切换主题后计算样式必须真的变。</p>
 *
 * <p><b>它抓得住什么</b>：把 {@code ThemeManager} 的主题选表写死成明色（或让暗色表不生效），
 * 这里立刻红——而令牌值/对比度层的那 6 条<b>不会</b>红（令牌文件本身没动）。</p>
 */
class ThemeRenderTest {

    @BeforeAll
    static void startFx() {
        FxToolkit.ensureStarted();
    }

    /** 一次真渲染的计算样式读数（都是 hex 字符串 / 逻辑像素，便于与令牌逐字比对）。 */
    record Readout(String cardSurface, String textFill, double fontSize) {
    }

    /** 在 FX 线程上 {@code applyCss() + layout()} 后读回 {@code .card} 与 {@code .body} 的计算样式。 */
    static Readout read(StackPane root, VBox card, Label body) {
        root.applyCss();
        root.layout();
        String surface = CrisisPanelRenderTest.hex(
                (Color) card.getBackground().getFills().get(0).getFill());
        String fill = CrisisPanelRenderTest.hex((Color) body.getTextFill());
        return new Readout(surface, fill, body.getFont().getSize());
    }

    @Test
    void darkTheme_isReallyAppliedInARealScene_andSwitchingChangesComputedStyle() {
        record Readings(Readout light, Readout dark, Readout backToLight) {
        }

        Readings readings = CrisisPanelRenderTest.onFxThread(() -> {
            VBox card = new VBox();
            card.getStyleClass().add("card");
            Label body = new Label("计划");
            body.getStyleClass().add("body");
            card.getChildren().add(body);

            StackPane root = new StackPane(card);
            root.setPrefSize(400, 300);
            Scene scene = new Scene(root, 400, 300);

            // 外壳装主题用的正是这一对调用（AppShell.build → attach；AppShell.setThemeMode → setMode）
            ThemeManager theme = new ThemeManager(ThemeMode.LIGHT);
            theme.attach(scene);

            Readout light = read(root, card, body);
            theme.setMode(ThemeMode.DARK);
            Readout dark = read(root, card, body);
            theme.setMode(ThemeMode.LIGHT);
            Readout back = read(root, card, body);
            return new Readings(light, dark, back);
        });

        String lightSurface = ContrastTest.value(ThemeMode.LIGHT, ThemeTokens.SURFACE);
        String darkSurface = ContrastTest.value(ThemeMode.DARK, ThemeTokens.SURFACE);
        String lightText = ContrastTest.value(ThemeMode.LIGHT, ThemeTokens.TEXT);
        String darkText = ContrastTest.value(ThemeMode.DARK, ThemeTokens.TEXT);

        System.out.println("[evidence] 真场景计算样式：明 card=" + readings.light().cardSurface()
                + " text=" + readings.light().textFill() + " font=" + readings.light().fontSize()
                + " | 暗 card=" + readings.dark().cardSurface()
                + " text=" + readings.dark().textFill() + " font=" + readings.dark().fontSize());

        assertEquals(lightSurface, readings.light().cardSurface(),
                "明色下 .card 的实测背景色必须等于 " + ThemeTokens.SURFACE.key() + " = " + lightSurface);
        assertEquals(darkSurface, readings.dark().cardSurface(),
                "暗色下 .card 的实测背景色必须等于暗色令牌 " + ThemeTokens.SURFACE.key() + " = " + darkSurface
                        + "（若这里等于明色值，说明暗色令牌没有在场景里生效）");
        assertNotEquals(readings.light().cardSurface(), readings.dark().cardSurface(),
                "切到暗色后 .card 的计算样式必须真的改变");

        assertEquals(lightText, readings.light().textFill(),
                "明色下 .body 的实测字色必须等于 " + ThemeTokens.TEXT.key() + " = " + lightText);
        assertEquals(darkText, readings.dark().textFill(),
                "暗色下 .body 的实测字色必须等于暗色令牌 " + ThemeTokens.TEXT.key() + " = " + darkText);
        assertNotEquals(readings.light().textFill(), readings.dark().textFill(),
                "切到暗色后正文色的计算样式必须真的改变");

        // 令牌里的根字号（.root -fx-font-size: 15px）必须在真场景里被算出来
        assertTrue(Math.abs(readings.light().fontSize() - 15.0) < 0.5,
                "实测根字号必须 ≈ 15px（令牌 .root -fx-font-size），实际 " + readings.light().fontSize());
        assertTrue(Math.abs(readings.dark().fontSize() - 15.0) < 0.5,
                "暗色下根字号同样必须是 15px，实际 " + readings.dark().fontSize());

        // 切回明色必须还原（证明 setMode 是双向的、且计算样式跟着走）
        assertEquals(readings.light().cardSurface(), readings.backToLight().cardSurface(),
                "切回明色后 .card 的计算样式必须还原成明色令牌值");
    }
}
