package com.xiaoyiyuan.study.ui.view;

/**
 * 单窗口应用里的页面清单（spec §8.1 的 8 类页面，本计划裁定 A2 拆成 10 项）。
 *
 * <p>侧栏顺序 = {@link #values()} 的声明顺序里 {@link #inSidebar()} 为真的那些；
 * {@code Navigation} 直接按声明顺序过滤，因此「顺序」这件事只有<b>一个</b>定义处——
 * 改这里就是改侧栏，不需要同步第二个列表。</p>
 *
 * <p>措辞取 spec §8.1：是「周检查」，不是 MASTER.md 的「周计划」（裁定 A2）。</p>
 */
public enum Page {

    /** 总览：本周该做什么。 */
    OVERVIEW("总览", true),

    /** 目标与阶段。 */
    GOALS("目标", true),

    /** 课程表。 */
    COURSES("课程", true),

    /** 周检查（spec 的措辞；MASTER.md 写「周计划」）。 */
    WEEK_CHECK("周检查", true),

    /** 周记录时间线。 */
    WEEK_LOG("周记录", true),

    /** 情绪记录。 */
    EMOTION("情绪", true),

    /** 情绪趋势（本计划把 spec 的「情绪」拆成记录与趋势两页）。 */
    EMOTION_TREND("情绪趋势", true),

    /** AI 对话助手。 */
    ASSISTANT("助手", true),

    /** 设置（含迁移向导入口、显示偏好、危机资源号码）。 */
    SETTINGS("设置", true),

    /** 求助资源：覆盖层，侧栏里没有它（Task 10），从设置页与危机命中处可达。 */
    CRISIS("求助资源", false);

    private final String label;
    private final boolean inSidebar;

    Page(String label, boolean inSidebar) {
        this.label = label;
        this.inSidebar = inSidebar;
    }

    /**
     * 界面上的页面名。
     *
     * <p>这里刻意是枚举里逐字写死的中文，而不是 {@code UiText} 的常量：`DisabledWordTest`
     * 与 {@code ShellTest} 都<b>逐字</b>断言这些名字（含「周检查」这一处 spec 措辞），
     * 名字换成常量引用后，断言就不再能证明「界面上真的显示这几个字」。</p>
     */
    public String label() {
        return label;
    }

    /** 是否出现在左侧导航里。 */
    public boolean inSidebar() {
        return inSidebar;
    }

    /**
     * 页面名即 {@code toString()}：枚举名出现在日志或异常消息里时是可读的中文，
     * 而不会让「WEEK_CHECK」这种英文字样溜到界面标题上。
     */
    @Override
    public String toString() {
        return label;
    }
}
