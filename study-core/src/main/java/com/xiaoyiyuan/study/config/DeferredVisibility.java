package com.xiaoyiyuan.study.config;

/**
 * 「考研（暂缓）」区在界面上的显示方式。
 *
 * <p>暂缓是一个<b>中性状态</b>，不是失败、不是拖延。所以这里只有三种「怎么显示」，
 * 没有「要不要显示」之外的任何评价性取值：用户可以选择让它显眼一点（{@link #VISIBLE_DIM}）、
 * 收起来（{@link #COLLAPSED}）或完全不看（{@link #HIDDEN}），
 * 三者都不代表「放弃了考研」。
 *
 * <p>{@link #COLLAPSED} 是默认值：暂缓内容默认收起来，避免占着视线，
 * 但又在界面上留一个入口，用户随时能展开。
 */
public enum DeferredVisibility {

    /** 显示但弱化（灰一点），让暂缓的事保持可见但不抢注意力。 */
    VISIBLE_DIM,

    /** 默认：折叠起来，点开才看。 */
    COLLAPSED,

    /** 完全隐藏，界面上不出现。 */
    HIDDEN;

    /** 未知取值（配置被手改坏）时用的兜底值。 */
    public static final DeferredVisibility DEFAULT = COLLAPSED;

    /**
     * 宽松解析：认不出来就返回 {@link #DEFAULT}，<b>不抛异常</b>。
     *
     * <p>理由：配置文件是纯文本，用户（或 AI 助手）随时可能手改。一个拼错的
     * {@code deferredVisibility=Collapsedd} 如果让 App 起不来，用户看到的是「软件坏了」，
     * 而实际只是配置里多了个字母——他连改回来的界面都打不开。所以这里一律回退默认值，
     * 让 App 先能启动，配置问题留到用户看得见的时候再说。
     *
     * @param raw 配置里的原始字符串（可为 null/空白/大小写不一）
     */
    public static DeferredVisibility fromConfigValue(String raw) {
        if (raw == null) {
            return DEFAULT;
        }
        String trimmed = raw.trim();
        for (DeferredVisibility value : values()) {
            if (value.name().equalsIgnoreCase(trimmed)) {
                return value;
            }
        }
        return DEFAULT;
    }
}
