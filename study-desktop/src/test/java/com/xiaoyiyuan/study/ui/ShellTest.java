package com.xiaoyiyuan.study.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.xiaoyiyuan.study.config.AppConfig;
import com.xiaoyiyuan.study.config.AppPaths;
import com.xiaoyiyuan.study.ui.control.StatusModel;
import com.xiaoyiyuan.study.ui.theme.ThemeManager;
import com.xiaoyiyuan.study.ui.theme.ThemeMode;
import com.xiaoyiyuan.study.ui.view.Page;
import com.xiaoyiyuan.study.ui.view.PlaceholderPage;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用外壳的四条核心性质（Task 2）。本任务的核心断言全在这里。
 *
 * <ol>
 *   <li><b>导航</b>：8 类页面（+ 求助资源）都能被导航到，且每次只显示一个——旧页不再在场景图里；</li>
 *   <li><b>主题</b>：切到暗色后生效的是暗色那套令牌表，且反复切换不增长 stylesheet 列表、
 *       不留旧主题样式表——外壳必须<b>复用</b> {@code ThemeManager}，不许自己再装一套 CSS；</li>
 *   <li><b>首启引导</b>：{@code studyDir} / 计划文件不存在时给中性提示，禁用词与诊断标签
 *       一律不出现在场景图里任何一个文本上；</li>
 *   <li><b>外壳不直接写文件</b>：{@code ui/} 里不许出现 {@code new FileStore} /
 *       {@code TextFileCodec.write} / {@code Files.write} / {@code PlanDocument.parse}，
 *       也不许出现 {@code setStyle} 内联样式（Task 1 评审核出的护栏盲区）。</li>
 * </ol>
 *
 * <p>只构建节点、不 {@code show()} 窗口：{@code Stage} 不需要显示就能挂 {@code Scene}，
 * 这样测试不弹窗、也不受本机 200% 缩放影响。</p>
 */
class ShellTest {

    @BeforeAll
    static void startFx() {
        FxToolkitTest.ensureStarted();
    }

    // ================================================================================
    // 性质 ①：8 类页面都能被导航到，且每次只显示一个
    // ================================================================================

    @Test
    void navigationMatrix_showsExactlyOnePageAtATime(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.planFile(studyDir), plan(), StandardCharsets.UTF_8);

        FxToolkitTest.onFx(() -> {
            Shell shell = shell(studyDir);

            List<Page> offSidebar = new ArrayList<>();
            for (Page page : Page.values()) {
                if (!page.inSidebar()) {
                    offSidebar.add(page);
                }
            }
            assertEquals(List.of(Page.CRISIS), offSidebar, "本计划只有一个非侧栏页（求助资源）");

            List<Page> reachable = List.of(Page.OVERVIEW, Page.GOALS, Page.COURSES, Page.WEEK_CHECK,
                    Page.WEEK_LOG, Page.EMOTION, Page.EMOTION_TREND, Page.ASSISTANT, Page.SETTINGS,
                    Page.CRISIS);
            assertEquals(10, reachable.size(), "简报的清单 = 9 个侧栏项 + 求助资源");

            // 侧栏按钮与页面一一对应（顺序 = Navigation.sidebarItems()）
            List<Button> navButtons = new ArrayList<>();
            visit(shell.left(), node -> {
                if (node instanceof Button button && button.getStyleClass().contains("nav-item")) {
                    navButtons.add(button);
                }
            });
            assertEquals(shell.navigation().sidebarItems().size(), navButtons.size(),
                    "侧栏按钮数必须等于 sidebarItems() 的项数");
            for (int i = 0; i < navButtons.size(); i++) {
                assertEquals(shell.navigation().sidebarItems().get(i), Page.valueOf(navButtons.get(i).getId()),
                        "第 " + i + " 个侧栏按钮的 id 必须是 " + shell.navigation().sidebarItems().get(i));
                assertEquals(shell.navigation().sidebarItems().get(i).label(), navButtons.get(i).getText(),
                        "第 " + i + " 个侧栏按钮的文字必须是页面名");
            }

            List<String> matrix = new ArrayList<>();
            for (Page page : reachable) {
                shell.navigation().go(page);
                assertEquals(page, shell.navigation().current(), "go(" + page + ") 之后 current() 必须是它");

                // 每次只显示一个：内容区恰好 1 个孩子，且就是当前页的占位视图
                assertEquals(1, shell.content().getChildren().size(),
                        "切到 " + page + " 之后内容区必须恰好一个孩子，实际 " + tree(shell.content()));
                List<Node> inContent = withStyleClass(shell.content(), "placeholder-page");
                assertEquals(1, inContent.size(),
                        "切到 " + page + " 之后内容区必须恰好一个占位视图，实际 " + tree(shell.content()));
                assertEquals(page, placeholderPageOf(inContent.get(0)),
                        "可见的占位视图必须属于当前页 " + page + "，实际 "
                                + placeholderPageOf(inContent.get(0)));

                // 旧页不再在场景图里：整个场景图里同样只有一个占位视图
                assertEquals(1, withStyleClass(shell.root(), "placeholder-page").size(),
                        "整个场景图里也只能有当前页那一个占位视图（旧页必须已移除），实际 " + tree(shell.root()));

                // 侧栏高亮：侧栏页恰好一个 nav-item-active 且 id 就是当前页；非侧栏页（求助资源）
                // 一个都没有——因为侧栏里根本没有这一项。CSS 里只有 .nav-item-active 这一条高亮
                // 规则，所以类名必须逐字是这个（写成 .nav-item.active 就永远不生效）。
                List<Node> active = withStyleClass(shell.root(), "nav-item-active");
                if (page.inSidebar()) {
                    assertEquals(1, active.size(),
                            "侧栏页 " + page + " 必须恰好一个 nav-item-active，实际 " + active.size()
                                    + " 个（" + tree(shell.left()) + "）");
                    assertEquals(page, Page.valueOf(active.get(0).getId()),
                            "高亮的侧栏项必须正是当前页 " + page + "，实际 id=" + active.get(0).getId());
                } else {
                    assertEquals(0, active.size(),
                            "非侧栏页 " + page + " 不该让任何侧栏项高亮（它是覆盖层，不在侧栏里），实际 "
                                    + active.size() + " 个");
                }

                matrix.add(page.label() + "→占位视图 " + inContent.size() + " 个 / 其他页残留 "
                        + (withStyleClass(shell.root(), "placeholder-page").size() - 1)
                        + " 个 / 侧栏高亮 " + active.size() + " 个");
            }
            System.out.println("[evidence] 导航矩阵（10 页，逐页实测）：" + matrix);
            return null;
        });
    }

    @Test
    void shellShape_isTheThreePartBorderPane_ofTheGuidelines(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.planFile(studyDir), plan(), StandardCharsets.UTF_8);

        FxToolkitTest.onFx(() -> {
            Shell shell = shell(studyDir);
            assertNotNull(shell.pane().getLeft(), "left = 侧栏导航（指南 §3.3 布局骨架）");
            assertNotNull(shell.pane().getCenter(), "center = 内容区");
            assertNotNull(shell.pane().getBottom(), "bottom = 状态栏（不是浮层：focus-not-obscured）");
            assertNotNull(shell.scene(), "Scene 已挂到 Stage 上");
            assertEquals(Page.OVERVIEW, shell.navigation().current(), "外壳起来时停在「总览」");
            assertEquals(1, withStyleClass(shell.root(), "placeholder-page").size(),
                    "起来时恰好一个占位视图");

            List<String> texts = textsOf(shell.root());
            assertTrue(texts.stream().anyMatch(t -> t.contains(studyDir.toString())),
                    "状态栏必须显示当前 studyDir（" + studyDir + "），实际文本 " + texts);
            assertTrue(texts.contains(Page.OVERVIEW.label()), "页头必须显示当前页标题，实际 " + texts);
            return null;
        });
    }

    // ================================================================================
    // 性质 ②：主题切换
    // ================================================================================

    @Test
    void themeSwitch_makesDarkTokensEffective_andNeverGrowsNorLeavesStaleStylesheets(@TempDir Path studyDir)
            throws IOException {
        Files.writeString(AppPaths.planFile(studyDir), plan(), StandardCharsets.UTF_8);

        FxToolkitTest.onFx(() -> {
            Shell shell = shell(studyDir);
            Scene scene = shell.scene();
            ThemeManager theme = shell.themeManager();

            assertEquals(List.of("/css/tokens-light.css", "/css/app.css"), cssSuffixes(scene),
                    "起步是明色：令牌表在前、app.css 在后");
            assertEquals(ThemeMode.LIGHT, theme.current(), "外壳里的 ThemeManager 初值必须是明色");

            shell.appShell().setThemeMode(ThemeMode.DARK);

            assertEquals(2, scene.getStylesheets().size(),
                    "切换主题必须整体替换：叠加会变成 4 张，而更晚加载的旧令牌反而生效（表现是「切了没反应」），实际 "
                            + scene.getStylesheets());
            assertEquals(List.of("/css/tokens-dark.css", "/css/app.css"), cssSuffixes(scene),
                    "切到暗色后生效的必须是暗色令牌表：" + scene.getStylesheets());
            assertFalse(hasStylesheet(scene, "/css/tokens-light.css"),
                    "旧主题样式表不得残留：" + scene.getStylesheets());

            for (int i = 0; i < 3; i++) {
                shell.appShell().setThemeMode(ThemeMode.LIGHT);
                assertEquals(2, scene.getStylesheets().size(), "第 " + i + " 次切回明色后仍只能有 2 张");
                assertEquals(List.of("/css/tokens-light.css", "/css/app.css"), cssSuffixes(scene));
                assertFalse(hasStylesheet(scene, "/css/tokens-dark.css"), "暗色表不得残留");

                shell.appShell().setThemeMode(ThemeMode.DARK);
                assertEquals(2, scene.getStylesheets().size(), "第 " + i + " 次切到暗色后仍只能有 2 张");
                assertEquals(List.of("/css/tokens-dark.css", "/css/app.css"), cssSuffixes(scene));
                assertFalse(hasStylesheet(scene, "/css/tokens-light.css"), "明色表不得残留");
            }

            // 外壳里那个可见的明/暗切换控件必须真的驱动这条路径（不是只改自己的文字）
            ToggleButton toggle = onlyToggleWithClass(shell.root(), "theme-toggle");
            assertNotNull(toggle, "外壳必须有一个可见的明/暗切换控件（theme-toggle）");
            assertEquals(com.xiaoyiyuan.study.ui.text.UiText.THEME_TO_LIGHT, toggle.getText(),
                    "暗色下切换控件的文字应当是「明」（点它切回明色）");
            toggle.fire();
            assertEquals(ThemeMode.LIGHT, theme.current(), "点这个控件必须真的切主题");
            assertEquals(com.xiaoyiyuan.study.ui.text.UiText.THEME_TO_DARK, toggle.getText(),
                    "切到明色后切换控件的文字必须变成「暗」（跟着主题走，不是写死的标签）");
            assertEquals(List.of("/css/tokens-light.css", "/css/app.css"), cssSuffixes(scene),
                    "点按钮切主题同样走 setAll，不许让列表增长：" + scene.getStylesheets());
            assertEquals(2, scene.getStylesheets().size());

            // 只允许一条主题来源：场景图里的样式表恰好是 ThemeTokens.cssFiles 的那两张
            assertEquals(new HashSet<>(List.of("tokens-light.css", "app.css")),
                    new HashSet<>(scene.getStylesheets().stream()
                            .map(url -> url.substring(url.lastIndexOf('/') + 1)).toList()),
                    "外壳不许自己再 add 一条样式表（那会让主题出现第二条真相来源）：" + scene.getStylesheets());
            return null;
        });
    }

    // ================================================================================
    // 性质 ③：首启引导（中性、不阻断、不含禁用词）
    // ================================================================================

    @Test
    void noPlanFile_firstRunNotice_isNeutral_andTheAppStillComesUp(@TempDir Path studyDir) {
        FxToolkitTest.onFx(() -> {
            Shell shell = shell(studyDir);

            assertEquals(Page.OVERVIEW, shell.navigation().current(), "没有计划文件也照常起来并进入总览");
            assertNotNull(shell.scene(), "场景必须已经挂上");
            List<Node> notices = withStyleClass(shell.root(), "notice");
            assertEquals(1, notices.size(), "首启要恰好一条中性提示条，实际 " + notices.size());
            assertEquals(List.of(StartupCheck.NOTICE_NO_PLAN), textsOf(notices.get(0)),
                    "提示条文字必须逐字是这个中性提示：" + StartupCheck.NOTICE_NO_PLAN);
            assertTrue(textsOf(shell.root()).contains(StartupCheck.NOTICE_NO_PLAN),
                    "提示条必须真的挂在场景图里");
            return null;
        });
    }

    @Test
    void legacyFileOnly_offersMigration_andTheButtonLeadsToSettings(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.legacyFile(studyDir), "# 旧档案\n", StandardCharsets.UTF_8);

        FxToolkitTest.onFx(() -> {
            Shell shell = shell(studyDir);

            assertEquals(1, withStyleClass(shell.root(), "notice").size(), "旧档案在时同样是一条中性提示条");
            assertTrue(textsOf(shell.root()).contains(StartupCheck.NOTICE_LEGACY_PRESENT),
                    "必须显示迁移提示：" + StartupCheck.NOTICE_LEGACY_PRESENT);

            Button migration = onlyButtonWithText(shell.root(), "打开迁移向导");
            assertNotNull(migration, "提示条上必须有一个「打开迁移向导」按钮");

            migration.fire();
            assertEquals(Page.SETTINGS, shell.navigation().current(),
                    "点「打开迁移向导」必须把用户送到「设置」页（迁移向导本体是 Task 9）；实际停在 "
                            + shell.navigation().current());
            return null;
        });
    }

    @Test
    void planFilePresent_noNoticeBarAtAll(@TempDir Path studyDir) throws IOException {
        Files.writeString(AppPaths.planFile(studyDir), plan(), StandardCharsets.UTF_8);

        FxToolkitTest.onFx(() -> {
            Shell shell = shell(studyDir);
            assertTrue(withStyleClass(shell.root(), "notice").isEmpty(),
                    "一切正常时不该有提示条，实际 " + textsOf(shell.root()));
            return null;
        });
    }

    @Test
    void sceneGraphText_neverContainsForbiddenWordsNorDiagnosticLabels(@TempDir Path studyDir) {
        List<String> forbidden =
                List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持");
        List<String> diagnostic = List.of("抑郁", "焦虑症", "中度", "心理健康得分", "得分");

        boolean[] hasPlan = { false, false, true, true };
        boolean[] hasLegacy = { false, true, false, true };

        FxToolkitTest.onFx(() -> {
            int scanned = 0;
            for (int i = 0; i < hasPlan.length; i++) {
                Path dir = studyDir.resolve("形态" + i);
                Files.createDirectories(dir);
                if (hasPlan[i]) {
                    Files.writeString(AppPaths.planFile(dir), plan(), StandardCharsets.UTF_8);
                }
                if (hasLegacy[i]) {
                    Files.writeString(AppPaths.legacyFile(dir), "# 旧档案\n", StandardCharsets.UTF_8);
                }
                Shell shell = shell(dir);
                List<Page> pages = new ArrayList<>(shell.navigation().sidebarItems());
                pages.add(Page.CRISIS);
                for (Page page : pages) {
                    shell.navigation().go(page);
                    for (String text : textsOf(shell.root())) {
                        scanned++;
                        for (String word : forbidden) {
                            assertFalse(text.contains(word),
                                    "形态 " + i + " / " + page + "：场景图里出现界面禁用词「" + word + "」：" + text);
                        }
                        for (String label : diagnostic) {
                            assertFalse(text.contains(label),
                                    "形态 " + i + " / " + page + "：场景图里出现诊断性标签「" + label + "」：" + text);
                        }
                    }
                }
            }
            assertTrue(scanned >= 4 * 10, "四种首启形态 × 10 页的文本都要扫到，实际只扫了 " + scanned + " 条");
            System.out.println("[evidence] 禁用词扫描：4 种首启形态 × 10 页，共检查 " + scanned + " 条界面文本，0 命中");
            return null;
        });
    }

    /**
     * 学习目录不可写时，外壳照常起来、只多一条中性提示（minor 清算批 Minor-1）。
     *
     * <p>旧用例拿 {@code C:\Windows\…} 当不可写点，在本机被 {@code assumeFalse} 永久跳过
     * （task-2-review.md 的 M4b 证明这半没被守住）。现在「可写判定」由测试注入，
     * 恒定承重、也不依赖本机权限。</p>
     */
    @Test
    void unwritableStudyDir_shellStillComesUp_withANeutralNotice(@TempDir Path studyDir) {
        FxToolkitTest.onFx(() -> {
            Shell shell = shell(studyDir, dir -> false);

            assertNotNull(shell.root(), "学习目录不可写时外壳照样要建出来（不阻断、不报警）");
            assertEquals(Page.OVERVIEW, shell.navigation().current(), "不可写也照常起来并进入总览");
            assertEquals(1, withStyleClass(shell.root(), "notice").size(), "恰好一条中性提示条，实际 "
                    + textsOf(shell.root()));
            assertTrue(textsOf(shell.root()).contains(StartupCheck.NOTICE_NOT_WRITABLE),
                    "要给一条中性提示：" + StartupCheck.NOTICE_NOT_WRITABLE + "，实际 " + textsOf(shell.root()));
            return null;
        });
    }

    // ================================================================================
    // 性质 ④：外壳不直接写文件，也不许 setStyle 绕过 CSS 护栏
    // ================================================================================

    /** 「界面代码禁止直接调」的入口（裁定 A5）+ 两个仅限例外（迁移 / 对话历史，Task 2 也不许有）。 */
    static final List<String> FORBIDDEN_FILE_APIS = List.of(
            "new FileStore",
            "TextFileCodec.write",
            "Files.write",
            "PlanDocument.parse",
            "Migrator.apply",
            "ConversationStore");

    static final String SET_STYLE_NEEDLE = ".setStyle" + "(";

    @Test
    void uiViewLayer_hasNoDirectFileWriteApi() throws IOException {
        List<Path> sources = uiSources("main");
        assertTrue(sources.size() >= 8, "至少应扫到 8 个 ui 源文件，实际 " + sources.size());

        List<String> offenders = new ArrayList<>();
        for (Path file : sources) {
            String text = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String api : FORBIDDEN_FILE_APIS) {
                if (text.contains(api)) {
                    offenders.add(file + " 出现 " + api);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "ui/ 下禁止直接读写文件（计划 3 裁定 A5：唯一入口是 ui/service/DocumentSession，Task 3 才建）："
                        + offenders);
    }

    /**
     * {@code setStyle} 会绕过<b>全部</b> CSS 护栏——包括 Task 1 修复轮 2 才焊住的
     * 「长度类属性不得引用查找值」与色值审计。
     *
     * <p>Task 1 评审核出的真实盲区：{@code App.java:13} 的
     * {@code label.setStyle("-fx-font-family: 'Microsoft YaHei'; -fx-font-size: 20px;")}
     * 里那个硬编码的 {@code 20px} 没有任何测试能看见。Task 2 删掉它，并用本断言 +
     * {@link #uiSources_neverHardcodeAFontSize()} 防止它回来。</p>
     *
     * <p><b>唯一的例外是 {@code ui/theme/JavaFontResolver}</b>：它按 Task 1 的实测结论
     * （JavaFX 的 {@code -fx-font-family} 逗号族列表只取第一项、没有回退链）在 Java 侧
     * 给根节点放一个<b>族名</b>。它只写 {@code -fx-font-family}，不写字号、不写颜色、不写长度
     * （有 {@code FxFontResolutionTest} 单独守着）；除此之外任何 {@code setStyle} 都是违规。
     * 例外是<b>写死的文件名</b>，不是「凡是主题包就放过」。</p>
     */
    @Test
    void uiSources_neverUseInlineSetStyle() throws IOException {
        List<String> allowed = List.of(
                Path.of("src", "main", "java", "com", "xiaoyiyuan", "study", "ui", "theme",
                        "JavaFontResolver.java").toString());

        List<String> offenders = new ArrayList<>();
        for (Path file : uiSources("main")) {
            String text = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            if (text.contains(SET_STYLE_NEEDLE) && !allowed.contains(file.toString())) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders,
                "ui/ 下不许内联 setStyle：它绕过所有 CSS 护栏（尺寸 / 色值 / 长度类查找值），"
                        + "样式一律走 app.css + 样式类。违规文件：" + offenders);
    }

    /**
     * 反规避断言：Java 源码里不许出现 {@code -fx-font-size} 声明；真出现了，值也只能来自
     * 指南 §3.3 允许的字号档位（{@code 15px / 17px / 19px}）。
     *
     * <p>这正是 Task 1 那个 {@code 20px} 的错处：既不在档位里，也不随字号设置整体缩放
     * （字号档位只改 CSS 里 {@code .root} 的 {@code -fx-font-size}）。</p>
     */
    @Test
    void uiSources_neverHardcodeAFontSize() throws IOException {
        Pattern pattern = Pattern.compile("-fx-font-size\\s*:\\s*([^;\"'}]+)");
        List<String> offenders = new ArrayList<>();
        for (Path file : uiSources("main")) {
            String text = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String value = matcher.group(1).strip();
                if (!List.of("15px", "17px", "19px").contains(value)) {
                    offenders.add(file + " → -fx-font-size: " + value);
                }
            }
        }
        assertEquals(List.of(), offenders,
                "字号只允许指南 §3.3 的三档（15px / 17px / 19px）且只写在 CSS 里；"
                        + "Java 源码里出现别的字号说明有人绕开了令牌体系：" + offenders);
    }

    @Test
    void shellSources_areUtf8WithoutBom_andUseLf() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : uiSources("main")) {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF) {
                offenders.add(file + " 有 UTF-8 BOM");
            }
            if (new String(bytes, StandardCharsets.UTF_8).contains("\r\n")) {
                offenders.add(file + " 含 CRLF");
            }
        }
        assertEquals(List.of(), offenders, "本仓库一律 UTF-8 无 BOM + LF：" + offenders);
    }

    @Test
    void appShell_rejectsNullArguments() {
        FxToolkitTest.onFx(() -> {
            Stage stage = new Stage();
            AppConfig config = config(Path.of("."));
            Navigation navigation = new Navigation(Page.OVERVIEW);
            StatusModel status = new StatusModel(Path.of("."), false);
            assertThrows(NullPointerException.class,
                    () -> new AppShell(null, config, navigation, status), "Stage 为 null 必须拒绝");
            assertThrows(NullPointerException.class,
                    () -> new AppShell(stage, null, navigation, status), "AppConfig 为 null 必须拒绝");
            assertThrows(NullPointerException.class,
                    () -> new AppShell(stage, config, null, status), "Navigation 为 null 必须拒绝");
            assertThrows(NullPointerException.class,
                    () -> new AppShell(stage, config, navigation, null), "StatusModel 为 null 必须拒绝");
            return null;
        });
    }

    // ================================================================================
    // 测试基建
    // ================================================================================

    /** 外壳与其零件的测试句柄（避免每条断言都在场景图里翻）。 */
    record Shell(AppShell appShell, Parent root, BorderPane pane, Navigation navigation, StatusModel status,
                 VBox content, Scene scene, ThemeManager themeManager) {

        VBox left() {
            return (VBox) pane.getLeft();
        }
    }

    static Shell shell(Path studyDir) {
        return shell(studyDir, StartupCheck::isWritable);
    }

    /** 「学习目录可写否」可注入（Minor-1）：让「不可写 → 提示条」这条分支可控地承重。 */
    static Shell shell(Path studyDir, java.util.function.Predicate<Path> writableProbe) {
        Stage stage = new Stage();
        AppConfig config = config(studyDir);
        Navigation navigation = new Navigation(Page.OVERVIEW);
        StatusModel status = new StatusModel(studyDir, false);
        AppShell appShell = new AppShell(stage, config, navigation, status, writableProbe);
        Parent root = appShell.build();
        // build() 在「Stage 还没有 Scene」时会自己建一个（1100×720）——生产路径与这里完全一致。
        Scene scene = stage.getScene();
        assertNotNull(scene, "build() 必须给还没有 Scene 的 Stage 建好一个");
        return new Shell(appShell, root, (BorderPane) root, navigation, status,
                appShell.contentAreaForTesting(), scene, appShell.themeManager());
    }

    /**
     * 占位视图节点 → 它代表的页面。用 {@code id} 认页（{@code AppShell} 把 {@code Page} 的名字设为 id），
     * 而不是把节点强转成某个具体控件——将来占位视图换成真页面时这条断言仍然成立。
     */
    static Page placeholderPageOf(Node node) {
        assertTrue(node.getStyleClass().contains("placeholder-page"),
                "节点必须带 placeholder-page 样式类，实际 " + node.getStyleClass());
        return Page.valueOf(node.getId());
    }

    static AppConfig config(Path studyDir) {        AppConfig config = AppConfig.load(studyDir.resolve("no-such-config.properties"));
        config.setStudyDir(studyDir);
        return config;
    }

    static String plan() {
        return "# 学习与生活计划\n\n## 一、现状\n\n| 项 | 内容 |\n|---|---|\n| 阶段 | 起步 |\n";
    }

    /** 只保留代码：去掉块注释与行注释（断言扫描的是代码，不是注释里的说明文字）。 */
    static String withoutComments(String java) {
        String noBlock = java.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder();
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("//");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }

    static List<Path> uiSources(String mainOrTest) throws IOException {
        Path root = Path.of("src", mainOrTest, "java", "com", "xiaoyiyuan", "study", "ui");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("找不到 " + root.toAbsolutePath());
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static List<String> textsOf(Node node) {
        List<String> texts = new ArrayList<>();
        visit(node, n -> {
            if (n instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
                texts.add(labeled.getText());
            }
        });
        return texts;
    }

    static void visit(Node node, Consumer<Node> visitor) {
        visitor.accept(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                visit(child, visitor);
            }
        }
    }

    static List<Node> withStyleClass(Node root, String styleClass) {
        List<Node> out = new ArrayList<>();
        visit(root, node -> {
            if (node.getStyleClass().contains(styleClass)) {
                out.add(node);
            }
        });
        return out;
    }

    static ToggleButton onlyToggleWithClass(Node root, String styleClass) {        List<ToggleButton> found = new ArrayList<>();
        visit(root, node -> {
            if (node instanceof ToggleButton toggle && toggle.getStyleClass().contains(styleClass)) {
                found.add(toggle);
            }
        });
        assertEquals(1, found.size(), "期望恰好一个 ." + styleClass + " 切换控件，实际 " + found.size());
        return found.get(0);
    }

    static Button onlyButtonWithText(Node root, String text) {
        List<Button> found = new ArrayList<>();
        visit(root, node -> {
            if (node instanceof Button button && text.equals(button.getText())) {
                found.add(button);
            }
        });
        assertEquals(1, found.size(), "期望恰好一个文字为「" + text + "」的按钮，实际 " + found.size());
        return found.get(0);
    }

    /** 场景图的可读快照，用于断言消息（默认的 Node.toString 里没有文字）。 */
    static List<String> tree(Node node) {
        List<String> out = new ArrayList<>();
        visit(node, n -> {
            if (n instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
                out.add(n.getClass().getSimpleName() + labeled.getStyleClass() + "「" + labeled.getText() + "」");
            }
        });
        return out;
    }

    static boolean hasStylesheet(Scene scene, String suffix) {
        return scene.getStylesheets().stream().anyMatch(s -> s.endsWith(suffix));
    }

    /** 只留下 {@code /css/} 之后的路径（筛掉 JavaFX 内建样式表，并去掉平台相关的 file: 前缀）。 */
    static List<String> cssSuffixes(Scene scene) {
        return scene.getStylesheets().stream().filter(s -> s.contains("/css/"))
                .map(s -> s.substring(s.lastIndexOf("/css/"))).toList();
    }
}
