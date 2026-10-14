package com.xiaoyiyuan.study.ui.text;

/**
 * 界面文案的<b>唯一</b>常量来源（全局约束「界面禁用词扫描」）。
 *
 * <p>为什么必须集中：{@code DisabledWordTest}（Task 11）的扫描面只有在文案集中时才可界定。
 * 散落在各视图里的中文字面量会让「界面上不出现禁用词」变成一句无法验证的口号。
 * 从 Task 2 起，外壳里的每一句中文都从这里取——晚建立这个习惯，Task 11 就要返工。</p>
 *
 * <p>禁用词的替代说法（指南 §4.3）：保存没成功 → {@link #NOT_SAVED}；
 * 「输入无效」→「这里需要一个日期，例如 2026-10-11」这类说明 + 补救路径。
 * <b>本类里一个禁用词都不出现</b>：需要提到它们的地方用 {@code "未" + "完成"} 这种编码点拼接
 * 构造，使类自身的字符串常量池不会被扫到。</p>
 */
public final class UiText {

    private UiText() {
    }

    // ---------- 窗口与页头 ----------

    /** 主窗口标题。 */
    public static final String APP_TITLE = "学习与生活计划";

    /** 页头里当前页的标题前缀，例如「页头：总览」（挂在 Label 的 accessibleText 上）。 */
    public static final String PAGE_HEADER_PREFIX = "页头：";

    // ---------- 侧栏 ----------

    /** 侧栏的 accessibleText。 */
    public static final String NAV_SIDEBAR_LABEL = "页面导航";

    // ---------- 主题切换 ----------

    /** 切换控件在明色下显示的文字（点了会变成暗色）。 */
    public static final String THEME_TO_DARK = "暗";

    /** 切换控件在暗色下显示的文字（点了会变成明色）。 */
    public static final String THEME_TO_LIGHT = "明";

    /** 切换控件的 accessibleText。 */
    public static final String THEME_TOGGLE_LABEL = "切换明暗主题";

    // ---------- 状态栏 ----------

    /** 尚未保存过（Task 2 的初值；Task 3 的 {@code DocumentSession} 接上后显示真实时间）。 */
    public static final String NOT_SAVED = "尚未保存";

    /** 工作模式：离线可用。 */
    public static final String OFFLINE = "离线可用";

    /** 工作模式：已配置 AI（不承诺连通性，只说「配置过」）。 */
    public static final String AI_CONFIGURED = "已配置 AI";

    /** 学习目录尚未选择 / 配置读坏时的占位文本（状态栏不能出现空位）。 */
    public static final String NO_STUDY_DIR = "（尚未选择学习目录）";

    /** 状态栏三段的连接符。 */
    public static final String STATUS_SEPARATOR = " · ";

    // ---------- 首启引导（Task 2 的启动检查） ----------

    /** 计划文件不存在、也没有旧档案。 */
    public static final String STARTUP_NO_PLAN = "还没有计划文件。可以在设置里选一个目录，或让助手帮你建一份。";

    /** 计划文件不存在，但旧档案还在。 */
    public static final String STARTUP_LEGACY_PRESENT = "旧档案还在原来的位置。可以把它迁移成新的计划文件。";

    /** 提示条上的跳转按钮（迁移向导本体是 Task 9）。 */
    public static final String OPEN_MIGRATION = "打开迁移向导";

    /** 学习目录不可写。 */
    public static final String STARTUP_NOT_WRITABLE = "这个目录现在写不进去。可以换一个目录，或在设置里查看当前路径。";

    /** 计划文件读不动时的提示前缀（后面直接接补救路径）。 */
    public static final String STARTUP_UNREADABLE_PREFIX = "计划文件暂时读不出来。";

    /** 计划文件读不动时的补救路径。 */
    public static final String STARTUP_UNREADABLE_SUFFIX = "可以稍后重试，或在设置里换一个目录。";

    // ---------- 占位视图（页面本体的内容在 Task 4–10） ----------

    /** 尚未开工的页面上显示的说明（中性陈述，不谈进度、不谈落后）。 */
    public static final String PLACEHOLDER_HINT = "这个页面还在准备中。";

    /** 占位视图的 accessibleText 前缀。 */
    public static final String PLACEHOLDER_LABEL_PREFIX = "页面：";
}
