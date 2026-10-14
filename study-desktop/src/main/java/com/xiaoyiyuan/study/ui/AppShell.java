package com.xiaoyiyuan.study.ui;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.xiaoyiyuan.study.config.AppConfig;
import com.xiaoyiyuan.study.ui.control.StatusModel;
import com.xiaoyiyuan.study.ui.text.UiText;
import com.xiaoyiyuan.study.ui.theme.ThemeManager;
import com.xiaoyiyuan.study.ui.theme.ThemeMode;
import com.xiaoyiyuan.study.ui.view.Page;
import com.xiaoyiyuan.study.ui.view.PlaceholderPage;
import com.xiaoyiyuan.study.ui.view.StartupNoticeBar;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 应用外壳：单窗口 + 左侧导航 + 内容区 + 底部状态栏 + 顶部中性提示条。
 *
 * <p>布局骨架照指南 §3.3：</p>
 * <pre>
 * BorderPane (root)
 * ├── top    : 提示条（首启引导；只有需要时才存在）+ 页头
 * ├── left   : 侧栏导航（.nav）
 * ├── center : 内容区（.content，恰好一个页面节点）
 * └── bottom : 状态栏（.status-bar；占自己的空间，不浮在内容上）
 * </pre>
 *
 * <p><b>三件事是外来的</b>，外壳只订阅：导航状态来自 {@link Navigation}（纯逻辑、可单测）、
 * 主题来自 {@link ThemeManager}（Task 1 已交付，<b>不许</b>在外壳里再装一套 CSS）、
 * 状态栏三个事实来自 {@link StatusModel}。页面切换因此只有一条代码路径——
 * {@code navigation.addListener} 那一个回调，而不是每个按钮各写一遍。</p>
 *
 * <p><b>不碰文件</b>：外壳只调 {@link StartupCheck}（只读）。唯一读写入口
 * {@code DocumentSession} 是 Task 3 的产物，Task 2 连它的名字都不出现（有源码扫描断言守着）。</p>
 */
public final class AppShell {

    /** 窗口初始尺寸（逻辑像素；不设最大尺寸、不锁死宽高相等）。 */
    public static final double INITIAL_WIDTH = 1100.0;
    public static final double INITIAL_HEIGHT = 720.0;

    /** 窗口最小尺寸（逻辑像素；200% 缩放下仍能容纳侧栏 + 内容）。 */
    private static final double MIN_WIDTH = 900.0;
    private static final double MIN_HEIGHT = 600.0;

    private final Stage stage;
    private final AppConfig config;
    private final Navigation navigation;
    private final StatusModel status;
    private final ThemeManager themeManager;

    private final Map<Page, Button> navButtons = new EnumMap<>(Page.class);
    private final StringProperty pageTitle = new SimpleStringProperty();

    private BorderPane root;
    private VBox navBox;
    private VBox contentArea;
    private HBox header;
    private ToggleButton themeToggle;
    private StartupNoticeBar noticeBar;

    /**
     * @param stage      目标窗口（本类会设置标题、初始/最小尺寸，并把场景挂上去）
     * @param config     已加载的应用配置（读坏了也是「可用默认值」，见 {@code AppConfig.load}）
     * @param navigation 导航状态（纯逻辑）
     * @param status     状态栏的三个事实
     */
    public AppShell(Stage stage, AppConfig config, Navigation navigation, StatusModel status) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.config = Objects.requireNonNull(config, "config");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.status = Objects.requireNonNull(status, "status");
        this.themeManager = new ThemeManager(ThemeMode.LIGHT);
    }

    /**
     * 建出外壳并返回根节点。
     *
     * <p>场景的贴合是通过监听 {@code Stage.sceneProperty()} 完成的：调用方无论先
     * {@code setScene} 还是先 {@code build}，主题都会装上（且只装一次）。</p>
     */
    public Parent build() {
        stage.setTitle(UiText.APP_TITLE);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);

        // 场景可能在 build() 之前就挂好了（App 的路径），也可能是照约束定的顺序先 build（测试路径）。
        // attach(null) 会在 ThemeManager 里被 requireNonNull 拒掉，所以这里先判空。
        if (stage.getScene() != null) {
            themeManager.attach(stage.getScene());
        }
        stage.sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene != null) {
                themeManager.attach(newScene);
                syncThemeToggle();
            }
        });

        root = new BorderPane();
        root.getStyleClass().add("root");

        navBox = buildNav();
        contentArea = new VBox();
        contentArea.getStyleClass().add("content");
        contentArea.setSpacing(24.0);
        header = buildHeader();

        VBox top = new VBox();
        noticeBar = buildNoticeBar();
        if (noticeBar != null) {
            top.getChildren().add(noticeBar.node());
        }
        top.getChildren().add(header);

        root.setTop(top);
        root.setLeft(navBox);
        root.setCenter(contentArea);
        root.setBottom(new StatusBar(status).node());

        navigation.addListener(this::showPage);
        showPage(navigation.current());

        if (stage.getScene() == null) {
            stage.setScene(new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT));
            themeManager.attach(stage.getScene());
        }
        return root;
    }

    // ================================================================================
    // 零件
    // ================================================================================

    private VBox buildNav() {
        VBox box = new VBox();
        box.getStyleClass().add("nav");
        box.setSpacing(4.0);
        box.setAlignment(Pos.TOP_LEFT);
        box.setAccessibleText(UiText.NAV_SIDEBAR_LABEL);

        for (Page page : navigation.sidebarItems()) {
            Button item = new Button(page.label());
            item.getStyleClass().add("nav-item");
            item.setId(page.name());
            item.setMaxWidth(Double.MAX_VALUE);
            item.setOnAction(event -> navigation.go(page));
            navButtons.put(page, item);
            box.getChildren().add(item);
        }
        return box;
    }

    private HBox buildHeader() {
        HBox box = new HBox();
        box.getStyleClass().add("header");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setSpacing(16.0);

        Label title = new Label();
        title.getStyleClass().add("page-title");
        title.textProperty().bind(pageTitle);
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        themeToggle = new ToggleButton(themeText());
        themeToggle.getStyleClass().add("theme-toggle");
        themeToggle.setAccessibleText(UiText.THEME_TOGGLE_LABEL);
        // 只负责「把选择交给 ThemeManager」——换样式表与换按钮文字都在 toggleTheme() 一处
        themeToggle.setOnAction(event -> toggleTheme());

        box.getChildren().addAll(title, spacer, themeToggle);
        return box;
    }

    private StartupNoticeBar buildNoticeBar() {
        StartupCheck.Result result = StartupCheck.inspect(config.studyDir());
        status.setStudyDir(config.studyDir());
        if (result.notices().isEmpty()) {
            return null;
        }
        return new StartupNoticeBar(result.notices(), () -> navigation.go(Page.SETTINGS));
    }

    private String themeText() {
        return themeManager.current() == ThemeMode.LIGHT ? UiText.THEME_TO_DARK : UiText.THEME_TO_LIGHT;
    }

    /**
     * 切换主题：把选择交给 {@link ThemeManager}（它负责换样式表），并让控件文字跟上。
     *
     * <p>控件是主题的<b>视图</b>，不是真相来源——所以主题变了就得刷新它，
     * 无论这次变化来自点击控件、还是来自设置页/其它代码路径。</p>
     */
    public void toggleTheme() {
        themeManager.toggle();
        syncThemeToggle();
    }

    /**
     * 直接设成某个主题（设置页的「显示偏好」用明/暗两档，不是「切换」）。
     *
     * @param mode 目标主题
     */
    public void setThemeMode(ThemeMode mode) {
        themeManager.setMode(mode);
        syncThemeToggle();
    }

    /** 按当前主题刷新切换控件的文字。 */
    private void syncThemeToggle() {
        themeToggle.setText(themeText());
    }

    // ================================================================================
    // 导航 → 内容区
    // ================================================================================

    /** 换内容区与页头；<b>唯一</b>的一处页面切换代码。 */
    private void showPage(Page page) {
        contentArea.getChildren().setAll(new PlaceholderPage(page).root());
        pageTitle.set(page.label());
        for (Map.Entry<Page, Button> entry : navButtons.entrySet()) {
            Button item = entry.getValue();
            // remove + add，而不是 setAll：JavaFX 25 的 getStyleClass() 返回不可变 List，
            // setAll 会抛 UnsupportedOperationException（在 FX 线程上被 runLater 吞掉，
            // 表现是「active 类始终没加上」，非常难查——本任务实测踩到过）
            item.getStyleClass().remove("nav-item-active");
            if (entry.getKey() == page) {
                item.getStyleClass().add("nav-item-active");
            }
        }
        // 非侧栏页（求助资源是覆盖层）没有任何侧栏项高亮——这是「侧栏里没有它」在界面上的
        // 直接体现；此时一个都没有是正确状态，不是「忘记更新高亮」。
    }

    // ================================================================================
    // 供后续任务 / 测试使用
    // ================================================================================

    /** 主题管理者（设置页的「显示偏好」与主题切换都走它）。 */
    public ThemeManager themeManager() {
        return themeManager;
    }

    /** 内容区（测试用：断言「每次只显示一个页面」）。 */
    public VBox contentAreaForTesting() {
        return contentArea;
    }

    /** 提示条（测试用；无提示时为 null）。 */
    public StartupNoticeBar noticeBarForTesting() {
        return noticeBar;
    }

    /** 侧栏（测试用）。 */
    public VBox navForTesting() {
        return navBox;
    }

    /** 页头（测试用）。 */
    public HBox headerForTesting() {
        return header;
    }

    /** 与 {@link #contentAreaForTesting()} 同义，供 Task 3 起注入真实页面。 */
    public VBox contentArea() {
        return contentArea;
    }
}
