package com.xiaoyiyuan.study.storage;

/**
 * 一次保存的结果。
 *
 * <p>只有两种结局：写下去了，或者因为检测到别人改过而<b>一个字节都没写</b>。
 * 刻意没有「写失败」这一项——写失败会以 {@link java.io.IOException} 抛出，
 * 而不是伪装成一个正常返回值。
 */
public enum SaveOutcome {
    /** 已保存（保存前已备份）。 */
    SAVED,
    /** 检测到外部修改：磁盘内容与基线不一致，未获用户确认，因此没有写入任何字节。 */
    EXTERNAL_CHANGE_DETECTED
}
