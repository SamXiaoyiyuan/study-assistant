package com.xiaoyiyuan.study.ui;

import com.xiaoyiyuan.study.config.AppConfig;
import com.xiaoyiyuan.study.config.AppPaths;
import com.xiaoyiyuan.study.ui.control.StatusModel;
import com.xiaoyiyuan.study.ui.view.Page;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX 应用入口（真正的 {@code main} 在 {@link Launcher}——classpath 方式下 JavaFX 拒绝
 * 启动继承 {@code Application} 的 main 类，计划 1 实测）。
 *
 * <p>启动路径上<b>任何一步都不许把 App 挡住</b>：配置读坏了 {@code AppConfig.load} 已退默认值，
 * 启动检查（{@code StartupCheck}）只读且只产出一条中性提示。所以 {@code start} 里刻意没有
 * try/catch——它依赖的两个入口本身就不抛。</p>
 *
 * <p>本类不含任何内联样式（{@code setStyle}）：那会绕过全部 CSS 护栏，
 * 而 Task 1 评审核出的正是这个盲区——旧冒烟窗口里硬编码过 {@code -fx-font-size: 20px}。</p>
 */
public final class App extends Application {

    @Override
    public void start(Stage stage) {
        AppConfig config = AppConfig.load(AppPaths.configFile());
        StatusModel status = new StatusModel(config.studyDir(), isAiConfigured(config));
        AppShell shell = new AppShell(stage, config, new Navigation(Page.OVERVIEW), status);

        // build() 自己建 Scene（1100×720）并装好主题；生产路径与 ShellTest 完全同一条
        shell.build();
        stage.show();
    }

    /** 「已配置 AI」的唯一判据：填了服务地址与模型（空密钥不算配置完成）。 */
    private static boolean isAiConfigured(AppConfig config) {
        return !config.baseUrl().isBlank() && !config.model().isBlank();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
