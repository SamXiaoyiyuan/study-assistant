package com.xiaoyiyuan.study.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

/**
 * 测试用的 JavaFX 运行时启动器（Task 2 的 {@code ui} 包副本）。
 *
 * <p>与 {@code com.xiaoyiyuan.study.ui.theme.FxToolkit} 是同一套逻辑、不同的包：
 * 后者是包私有（{@code final class}，无 {@code public}），跨包用不了。
 * 本类额外提供 {@link #onFx(Callable)}——外壳的断言几乎都要在 FX 应用线程上做
 * （构建节点、{@code Scene} 操作都会检查线程）。</p>
 */
final class FxToolkitTest {

    private static volatile boolean started;
    private static volatile Throwable failure;

    private FxToolkitTest() {
    }

    /** 幂等启动 JavaFX 工具包；失败时抛带原始原因的异常（有超时，不挂死）。 */
    static synchronized void ensureStarted() {
        if (failure != null) {
            throw new IllegalStateException("JavaFX 工具包启动失败：" + failure, failure);
        }
        if (started) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            Platform.startup(() -> {
                try {
                    javafx.scene.text.Font.getFamilies();
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    latch.countDown();
                }
            });
        } catch (IllegalStateException alreadyStarted) {
            started = true;
            return;
        } catch (Throwable t) {
            failure = t;
            throw new IllegalStateException("JavaFX 工具包启动失败：" + t, t);
        }
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                failure = new IllegalStateException("Platform.startup 60 秒内没有回调");
                throw new IllegalStateException("Platform.startup 60 秒内没有回调（本机 JavaFX 图形栈可能不可用）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 JavaFX 工具包被中断", e);
        }
        if (error.get() != null) {
            failure = error.get();
            throw new IllegalStateException("JavaFX 字体运行时初始化失败：" + error.get(), error.get());
        }
        started = true;
    }

    /** 在 FX 应用线程上执行并取回结果；断言失败原样带回测试线程，其他异常包一层。 */
    static <T> T onFx(Callable<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
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
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FX 线程 60 秒内没有完成");
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
}
