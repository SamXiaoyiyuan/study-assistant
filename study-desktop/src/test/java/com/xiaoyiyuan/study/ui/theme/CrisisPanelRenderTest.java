package com.xiaoyiyuan.study.ui.theme;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blocker 的<b>真实渲染</b>护栏（fix round 1）。
 *
 * <p>{@code app.css} 的 {@code .crisis-panel} 用两层 background 叠出「暖砂底 + 左侧色条」。
 * 两层 {@code -fx-background-insets} 一旦写成同一组值，其中一层就会被另一层完全盖住——
 * 后果只有两种，而<b>两种都是缺陷</b>：</p>
 * <ul>
 *   <li>色条盖住面板底 → 正文落到 {@code -fx-c-crisis-bar} 上（明 1.80:1 / 暗 1.65:1，元信息 1.13/1.14）；</li>
 *   <li>面板底盖住色条 → 色条完全不显示，而它是指南 §1.3 指定的「面板唯一的可识别边界」
 *       （面板底与页底只有 1.07:1，没有色条就看不出这是个面板）。</li>
 * </ul>
 *
 * <p><b>为什么必须真渲染</b>：{@code CssParser.errorsProperty()} 只报<b>语法</b>错误；
 * 层的上下顺序、insets 的裁切、以及属性值是否真被转换器接受（见报告 N1），
 * 都只有把像素画出来才看得见。本机实测：<b>后声明的那层画在上面</b>。</p>
 */
class CrisisPanelRenderTest {

    static final int W = 300;
    static final int H = 120;

    /** {@code -fx-background-insets} 上层左内缩 {@code 0.2em}，根字号 15px → 3 逻辑像素。 */
    static final int STRIP = 3;

    @BeforeAll
    static void startFx() {
        FxToolkit.ensureStarted();
    }

    /** 一次渲染的取样结果：色条位置的颜色、面板正中颜色、正文左边界、正文区域里出现的杂色。 */
    record Rendered(String barPixel, String bodyPixel, double contentX, int strayBarPixels,
                    java.util.List<String> straySamples) {
    }

    static String hex(Color c) {
        return String.format(java.util.Locale.ROOT, "#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    /**
     * 把 {@code .crisis-panel} 画出来：一个装着文字的 HBox，尺寸固定为 {@link #W} × {@link #H}。
     *
     * <p>用 HBox 而不是 StackPane，是因为 HBox 从左边开始摆子节点——正文的左边界（{@code layoutX}）
     * 正好能量出「正文有没有压在色条上」。</p>
     *
     * <p>渲染与取像素必须在 FX 应用线程上做（{@code Node.snapshot} 非 FX 线程会抛
     * {@code IllegalStateException: Not on FX application thread}，本测试第一版就是这样红的）。</p>
     */
    static Rendered render(ThemeMode mode) {
        String barColor = ContrastTest.value(mode, ThemeTokens.CRISIS_BAR);
        return onFxThread(() -> renderOnFxThread(mode, barColor));
    }

    private static Rendered renderOnFxThread(ThemeMode mode, String barColor) {
        HBox panel = new HBox();
        panel.getStyleClass().add("crisis-panel");
        Label content = new Label("危机资源 120");
        panel.getChildren().add(content);
        panel.setPrefSize(W, H);
        StackPane root = new StackPane(panel);
        root.setPrefSize(W, H);
        Scene scene = new Scene(root, W, H);
        scene.getStylesheets().setAll(ThemeTokens.cssFiles(mode).stream()
                .map(path -> ThemeTokens.class.getResource(path).toExternalForm())
                .toList());
        root.applyCss();
        root.layout();
        panel.resize(W, H);
        panel.layout();

        WritableImage image = panel.snapshot(null, new WritableImage(W, H));
        PixelReader pixels = image.getPixelReader();
        String bar = hex(pixels.getColor(2, H / 2));      // 落在外层（色条）3px 之内
        String body = hex(pixels.getColor(W / 2, H / 2)); // 面板正中

        // 正文区域（避开 1px 描边与正文文字可能占用的左上角）：不得再出现色条色
        java.util.List<String> stray = new java.util.ArrayList<>();
        int strayCount = 0;
        for (int x = STRIP + 1; x < W - 2; x++) {
            for (int y = H / 2; y < H - 2; y += 3) {
                String h = hex(pixels.getColor(x, y));
                if (barColor.equals(h)) {
                    strayCount++;
                    if (stray.size() < 8) {
                        stray.add("(" + x + "," + y + ")=" + h);
                    }
                }
            }
        }
        return new Rendered(bar, body, content.getLayoutX(), strayCount, stray);
    }

    /** 在 FX 应用线程上执行并取回结果；线程上的异常原样带回测试线程。 */
    static <T> T onFxThread(java.util.concurrent.Callable<T> body) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
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
                throw new IllegalStateException("渲染 60 秒内没有完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (error.get() != null) {
            if (error.get() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw new IllegalStateException("FX 线程上抛异常：" + error.get(), error.get());
        }
        return result.get();
    }

    @Test
    void crisisPanel_keepsTheAccentBarAsANarrowLeftStrip_andTheBodyIsCrisisBg() {
        for (ThemeMode mode : ThemeMode.values()) {
            String bar = ContrastTest.value(mode, ThemeTokens.CRISIS_BAR);
            String bg = ContrastTest.value(mode, ThemeTokens.CRISIS_BG);
            Rendered r = render(mode);
            System.out.println("[evidence] " + mode + " crisis-panel: barPixel=" + r.barPixel()
                    + " bodyPixel=" + r.bodyPixel() + " contentX=" + r.contentX()
                    + " strayBarPixels=" + r.strayBarPixels());

            assertEquals(bar, r.barPixel(),
                    mode + " 主题：面板左侧必须真的画出色条 " + ThemeTokens.CRISIS_BAR.key() + " = " + bar
                            + "（指南 §1.3：它是面板唯一的可识别边界，面板底与页底只有 1.07:1）；实测 " + r.barPixel());
            assertEquals(bg, r.bodyPixel(),
                    mode + " 主题：面板正文底必须是 " + ThemeTokens.CRISIS_BG.key() + " = " + bg
                            + "；若这里是色条色，说明色条铺满了整块（正文只有 1.80/1.65:1）；实测 " + r.bodyPixel());
            assertEquals(0, r.strayBarPixels(),
                    mode + " 主题：色条只能占最左 " + STRIP + " 像素，正文区域里不得再出现色条色；实测 "
                            + r.strayBarPixels() + " 个，样例 " + r.straySamples());
        }
    }

    @Test
    void crisisPanel_contentStartsToTheRightOfTheBar() {
        for (ThemeMode mode : ThemeMode.values()) {
            Rendered r = render(mode);
            assertTrue(r.contentX() > STRIP,
                    mode + " 主题：面板内容的左边界必须落在色条右侧（色条 " + STRIP + "px），实测 "
                            + r.contentX() + "——正文压在色条上就等于把 1.80/1.65:1 的字放在用户眼前");
        }
    }
}
