package com.xiaoyiyuan.study.ui.theme;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

/**
 * 测试用的 JavaFX 运行时启动器。
 *
 * <p>只给「必须看到真实 JavaFX 行为」的测试用（CSS 字体族解析、{@code Platform.getPreferences()}）。
 * 本机是 Windows 桌面，起工具包不需要显示服务器；但如果起不来，测试会以明确消息失败，
 * 而不是挂死（有超时）。</p>
 */
final class FxToolkit {

    private static volatile boolean started;
    private static volatile Throwable failure;

    private FxToolkit() {
    }

    /** 幂等启动 JavaFX 工具包；失败时抛带原始原因的异常。 */
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
                    // 预热字体运行时，触发 Glass 字体加载器初始化
                    javafx.scene.text.Font.getFamilies();
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    latch.countDown();
                }
            });
        } catch (IllegalStateException alreadyStarted) {
            // 同一个 JVM 里第二次调用会抛这个；说明工具包已经在跑
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
}
