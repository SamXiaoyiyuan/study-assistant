package com.xiaoyiyuan.study.ai;

/**
 * AI 助手的两种角色（spec §7.2 的表格两行）。
 *
 * <p><b>为什么这个枚举住在 {@code ai} 包、而 {@link com.xiaoyiyuan.study.prompt.PromptLibrary}
 * 住在别处</b>：角色只是一个名字与一个文件名（纯值），{@code ai} 包需要它（逐次确认门要按角色取提示词）；
 * 而提示词的加载要读外部文件，那件事在 {@code ai} 包里是被结构性禁止的（spec §7.5：AI 无文件写入权，
 * 由 {@code AiClientTest} 的字节码护栏与 {@code PromptFallbackTest} 一起守住）。
 * 依赖方向因此是 {@code prompt → ai}，而不是反过来。
 *
 * <p>文件名从展示名派生，且是唯一来源：spec §7.2 的「外部文件」与「内置回退」两列用的是同一个名字，
 * 分成两个常量迟早会漂移。
 */
public enum PromptRole {

    /** 角色 1：学习与生活规划师。服务对象是以学校课程为主线的学习者，长线目标可能暂缓或取消。 */
    PLANNER("学习与生活规划师"),

    /** 角色 2：生活与心理顾问。做情绪与生活话题的分析，不做诊断。 */
    COUNSELOR("生活与心理顾问");

    private final String displayName;

    PromptRole(String displayName) {
        this.displayName = displayName;
    }

    /** 界面展示用的角色名（设置页要显示「现在用的是哪个角色的外部文件」）。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 提示词文件名，例如 {@code 学习与生活规划师.md}。
     *
     * <p>外部文件落在 {@code <studyDir>\提示词\} 下，内置回退在
     * {@code classpath:/prompts/} 下的同名文件（spec §7.2）。
     */
    public String fileName() {
        return displayName + ".md";
    }
}
