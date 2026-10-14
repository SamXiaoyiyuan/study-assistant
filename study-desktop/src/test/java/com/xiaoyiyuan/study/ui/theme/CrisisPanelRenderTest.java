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

    // ================================================================================
    // N1 修复轮 2：真渲染测量——全站 padding / 圆角是否真的生效
    // ================================================================================

    /** 一个真实控件在 CSS 生效后的实测值。 */
    record Styled(String selector, double fontSize, javafx.geometry.Insets padding,
                  javafx.geometry.Insets insets, double bgRadius, double borderRadius,
                  double layoutWidth, double layoutHeight) {
    }

    /**
     * 把 {@code selector} 对应的真实控件挂进一个带两套样式表的 {@link Scene}，
     * {@code applyCss()} + 两次 {@code layout()} 之后读回实测值。
     *
     * <p>读的是 {@link javafx.scene.layout.Region#getPadding()} / {@code getInsets()} /
     * {@code getBackground().getRadii()}——这些值只有在 CSS 声明<b>真的被转换器接受</b>时才会出现；
     * 声明被忽略（N1）时它们保持 JavaFX 的默认值，也就是 0。
     */
    static Styled measure(ThemeMode mode, String styleClass, java.util.function.Supplier<javafx.scene.layout.Region> factory) {
        return onFxThread(() -> {
            javafx.scene.layout.Region node = factory.get();
            node.getStyleClass().add(styleClass);
            StackPane root = new StackPane(node);
            Scene scene = new Scene(root, 400, 300);
            scene.getStylesheets().setAll(ThemeTokens.cssFiles(mode).stream()
                    .map(path -> ThemeTokens.class.getResource(path).toExternalForm())
                    .toList());
            root.applyCss();
            root.layout();
            root.layout();
            javafx.geometry.Insets padding = node.getPadding();
            double bgRadius = 0;
            if (node.getBackground() != null && !node.getBackground().getFills().isEmpty()) {
                bgRadius = node.getBackground().getFills().get(0).getRadii().getTopLeftHorizontalRadius();
            }
            double borderRadius = -1;
            if (node.getBorder() != null && !node.getBorder().getStrokes().isEmpty()) {
                borderRadius = node.getBorder().getStrokes().get(0).getRadii().getTopLeftHorizontalRadius();
            }
            return new Styled(styleClass + "@" + mode, rootFontSizePx(),
                    padding == null ? javafx.geometry.Insets.EMPTY : padding,
                    node.getInsets() == null ? javafx.geometry.Insets.EMPTY : node.getInsets(),
                    bgRadius, borderRadius, node.getWidth(), node.getHeight());
        });
    }

    /**
     * 根字号（em 换算的基准），从令牌文件里那条唯一的 {@code px} 声明读出来。
     *
     * <p>刻意<b>不</b>用 {@code Font.getDefault().getSize()}：那读的是「没有 CSS 时的默认字号」，
     * 而不是 {@code .root} 上那条令牌解析出来的根字号——用它做 em 换算会把期望值算错，
     * 于是「断言红」与「实现坏」就分不清了。也不用 {@code node.getStyle()}：{@code Node.getStyle()}
     * 返回的是<b>内联</b>样式字符串，不是 computed style。
     *
     * <p>护栏：解析出来的值必须与「唯一允许的 px 声明」是同一个数，否则测试自己先红。
     */
    static double rootFontSizePx() {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("-fx-font-size\\s*:\\s*(\\d+(?:\\.\\d+)?)px\\s*;")
                        .matcher(CssTokens.read("/css/tokens-light.css"));
        assertTrue(m.find(), "tokens-light.css 里找不到 .root 的 -fx-font-size: <n>px");
        double size = Double.parseDouble(m.group(1));
        // 与 ColorAuditTest.tokenFiles_containExactlyOnePxOccurrence_... 同一条红线：全文件唯一允许的
        // px 就是这一处，所以 em 换算的基准只能是它。
        assertEquals(1, CssTokens.units("/css/tokens-light.css").stream()
                        .filter(e -> "px".equals(e.getKey())).count(),
                "tokens-light.css 里的 px 必须恰好一处（根字号）");
        return size;
    }

    /** 把 {@code 1.07em} 这类令牌值按实测根字号换算成逻辑像素；无单位值原样返回。 */
    static double resolvePx(String tokenValue, double fontSize) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)(em|px)?").matcher(tokenValue.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("不是长度令牌值：" + tokenValue);
        }
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        if ("em".equals(unit)) {
            return n * fontSize;
        }
        return n;
    }

    /**
     * 比较实测逻辑像素与令牌换算值。
     *
     * <p>容差 {@code 0.5} 而不是 {@code 0.02}：JavaFX 内部用 {@code float} 存尺寸
     * （本机实测 {@code 1.07em × 15px} 读回来是 {@code 16.0} 而不是 {@code 16.05}，
     * {@code 0.2em × 15px} 读回来是 {@code 2.6} 而不是 {@code 3.0}），这是精度而不是
     * 「样式没生效」——后者差的是整整一个令牌值（最小 3.98px）。
     */
    static void assertClose(String what, double expected, double actual) {
        assertTrue(Math.abs(expected - actual) < 0.5,
                what + "：期望 " + expected + "，实测 " + actual);
    }

    /**
     * <b>N1 的真渲染证据</b>：{@code .card} 的 {@code -fx-padding: -fx-sp-4} 与
     * {@code -fx-background-radius: -fx-r-card} 在修复前<b>整条被忽略</b>（实测全 0），
     * 修复后必须等于 {@code -fx-sp-4} / {@code -fx-r-card} 换算出的逻辑像素。
     */
    @Test
    void card_paddingAndRadiusAreActuallyApplied_notZero() {
        for (ThemeMode mode : ThemeMode.values()) {
            Styled s = measure(mode, "card", javafx.scene.layout.VBox::new);
            double sp4 = resolvePx(ThemeTokens.nonColorValue("-fx-sp-4", ThemeMode.LIGHT), s.fontSize());
            double rCard = resolvePx(ThemeTokens.nonColorValue("-fx-r-card", ThemeMode.LIGHT), s.fontSize());
            System.out.println("[evidence] " + s.selector() + " 根字号=" + s.fontSize()
                    + " padding=" + s.padding() + " insets=" + s.insets()
                    + " bgRadius=" + s.bgRadius() + " borderRadius=" + s.borderRadius()
                    + "（-fx-sp-4 → " + sp4 + "px，-fx-r-card → " + rCard + "px）");

            assertClose("N1（" + s.selector() + "）上内边距必须 = -fx-sp-4", sp4, s.padding().getTop());
            assertClose("N1（" + s.selector() + "）右内边距必须 = -fx-sp-4", sp4, s.padding().getRight());
            assertClose("N1（" + s.selector() + "）下内边距必须 = -fx-sp-4", sp4, s.padding().getBottom());
            assertClose("N1（" + s.selector() + "）左内边距必须 = -fx-sp-4", sp4, s.padding().getLeft());
            assertClose("N1（" + s.selector() + "）外框 insets 上必须 = 内边距 + 1px 描边",
                    sp4 + 1, s.insets().getTop());
            assertClose("N1（" + s.selector() + "）背景圆角必须 = -fx-r-card", rCard, s.bgRadius());
            assertClose("N1（" + s.selector() + "）描边圆角必须 = -fx-r-card", rCard, s.borderRadius());
            assertTrue(s.padding().getTop() > 0,
                    "N1 修复前 " + s.selector() + " 的 padding 是 0（查找值被 ClassCastException 忽略）");
        }
    }

    /**
     * 第二条真渲染证据：{@code .button} 的 {@code -fx-padding: -fx-sp-3 -fx-sp-5}
     * 与 {@code -fx-min-height: 2.9em}。
     *
     * <p>后者是「统一约定」那一类的落点（minor 清算批 MEDIUM-1）：{@code -fx-min-height}
     * <b>本身接受查找值</b>（Region 的转换器收 {@code Number}，实测 {@code -fx-min-height: -fx-sp-6}
     * → {@code minHeight=32.0}、CCE=0），它写字面 {@code 2.9em} 不是因为它会失效，而是与全站
     * 「长度类属性一律写字面值」的约定一致。修复前按钮高度是「内容 + 0 内边距」（padding 那半才是
     * 真被 CCE 忽略的），修复后必须是「内容 + 上下内边距」，且不低于 2.9em。
     */
    @Test
    void button_paddingAndMinHeightAreActuallyApplied_notZero() {
        for (ThemeMode mode : ThemeMode.values()) {
            Styled s = measure(mode, "button", javafx.scene.control.Button::new);
            double sp3 = resolvePx(ThemeTokens.nonColorValue("-fx-sp-3", ThemeMode.LIGHT), s.fontSize());
            double sp5 = resolvePx(ThemeTokens.nonColorValue("-fx-sp-5", ThemeMode.LIGHT), s.fontSize());
            double minHeight = resolvePx("2.9em", s.fontSize());
            System.out.println("[evidence] " + s.selector() + " 根字号=" + s.fontSize()
                    + " padding=" + s.padding() + " height=" + s.layoutHeight()
                    + "（-fx-sp-3 → " + sp3 + "px，-fx-sp-5 → " + sp5 + "px，2.9em → " + minHeight + "px）");

            assertClose("N1（" + s.selector() + "）上内边距必须 = -fx-sp-3", sp3, s.padding().getTop());
            assertClose("N1（" + s.selector() + "）下内边距必须 = -fx-sp-3", sp3, s.padding().getBottom());
            assertClose("N1（" + s.selector() + "）左内边距必须 = -fx-sp-5", sp5, s.padding().getLeft());
            assertClose("N1（" + s.selector() + "）右内边距必须 = -fx-sp-5", sp5, s.padding().getRight());
            assertTrue(s.layoutHeight() > 2 * sp3,
                    "N1 修复前按钮高度里没有任何内边距（实测 " + s.layoutHeight() + "px）；"
                            + "修复后必须至少容纳上下各 " + sp3 + "px");
            assertTrue(s.layoutHeight() >= minHeight - 0.5,
                    "2.9em 的 -fx-min-height 必须生效：实测 " + s.layoutHeight()
                            + "px，期望 ≥ " + minHeight + "px（按统一约定写字面值；"
                            + "注意 -fx-min-height 的查找值本身是合法生效的，这里不是「因为它会失效」）");
        }
    }

    /** {@code .chip} 的 {@code -fx-padding: 0.2em 0.6em} 本来就是字面值：它必须一直是生效的（对照组）。 */
    @Test
    void chip_literalPaddingWasAlwaysApplied_controlGroup() {
        for (ThemeMode mode : ThemeMode.values()) {
            Styled s = measure(mode, "chip", javafx.scene.control.Label::new);
            double vertical = 0.2 * s.fontSize();
            double horizontal = 0.6 * s.fontSize();
            System.out.println("[evidence] " + s.selector() + " 根字号=" + s.fontSize()
                    + " padding=" + s.padding() + "（0.2em → " + vertical + "px，0.6em → " + horizontal
                    + "px；JavaFX 以 float 运算，实测会略小）");
            // 这条是对照组：修复前它也**不是** 0（因为 0.2em 是字面值），所以它证明的是
            // 「同一套测量在同一台机器上，字面值读得出真实数字、查找值读出 0」这个区分力本身。
            assertTrue(s.padding().getTop() > vertical * 0.8 && s.padding().getTop() < vertical * 1.2,
                    "对照组（" + s.selector() + "）上内边距应约为 " + vertical + "px，实测 " + s.padding().getTop());
            assertTrue(s.padding().getLeft() > horizontal * 0.8 && s.padding().getLeft() < horizontal * 1.2,
                    "对照组（" + s.selector() + "）左内边距应约为 " + horizontal + "px，实测 " + s.padding().getLeft());
        }
    }
}
