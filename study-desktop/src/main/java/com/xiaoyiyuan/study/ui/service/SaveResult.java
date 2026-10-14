package com.xiaoyiyuan.study.ui.service;

/**
 * 界面侧一次保存请求的结局。
 *
 * <p>刻意与 {@code storage} 包的 {@code SaveOutcome} 分开：{@code SaveOutcome} 只回答
 * 「写盘这一步做没做」，而界面还需要区分「内容压根没变，所以没必要写」——那既不是保存成功、
 * 也不是被拦下，混进 {@code SaveOutcome} 会让存储层多背一层界面语义。
 *
 * <p>没有「写盘出错」这一项：写不动会以 {@code IOException} 上抛，调用方必须看到，
 * 而不是伪装成一个正常返回值。
 */
public enum SaveResult {

    /** 真的写了盘（写前已备份），并且内存里的内容成了新基线。 */
    SAVED,

    /**
     * 检测到外部改动且没有获得「覆盖」的授权，<b>一个字节都没写</b>。
     *
     * <p>界面据此提示用户选择「重新载入 / 覆盖」。
     */
    BLOCKED_EXTERNAL_CHANGE,

    /**
     * 内容与打开（或上次保存）时完全相同，<b>没有调用写盘</b>。
     *
     * <p>这不是优化而是正确性：{@code FileStore} 每次保存都先备份再写，
     * 一次「什么都没改的保存」会在 {@code .backup} 里堆一份无意义快照，
     * 把每文件那有限的十份真正有用的备份挤掉。
     */
    UNCHANGED
}
