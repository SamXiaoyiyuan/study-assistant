package com.xiaoyiyuan.study.ui.control;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.xiaoyiyuan.study.ui.text.UiText;

/**
 * 状态栏的三个事实：当前 {@code studyDir}、当前工作模式、最后保存时间。
 *
 * <p>抽成<b>纯逻辑</b>（不碰 JavaFX、不碰文件系统）的理由：状态栏此前只可能被人工目视检查，
 * 而「最后保存时间」这类东西最典型的错法就是「显示了 null」或「永远显示尚未保存」。
 * 有了这个类，三件事的取值都可以被逐字断言。</p>
 *
 * <p>最后保存时间由 Task 3 的 {@code DocumentSession} 提供；Task 2 先留接口，
 * {@code null} 一律显示「{@link UiText#NOT_SAVED}」——不是「未保存成功」这类会踩禁用词的写法。</p>
 */
public final class StatusModel {

    private static final DateTimeFormatter SAVE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private Path studyDir;
    private boolean aiConfigured;
    private LocalDateTime lastSave;

    /**
     * @param studyDir     当前学习目录（可为 null：配置读坏时）
     * @param aiConfigured 是否已配置 AI（离线可用 / 已配置 AI 两档）
     */
    public StatusModel(Path studyDir, boolean aiConfigured) {
        this.studyDir = studyDir;
        this.aiConfigured = aiConfigured;
        this.lastSave = null;
    }

    /** 第一段：当前学习目录。空 / null 时退到占位文本，绝不出现空位或 null。 */
    public String studyDirText() {
        if (studyDir == null) {
            return UiText.NO_STUDY_DIR;
        }
        String text = studyDir.toString();
        return text.isBlank() ? UiText.NO_STUDY_DIR : text;
    }

    /** 第二段：工作模式。 */
    public String modeText() {
        return aiConfigured ? UiText.AI_CONFIGURED : UiText.OFFLINE;
    }

    /** 第三段：最后保存时间。 */
    public String lastSaveText() {
        return lastSave == null ? UiText.NOT_SAVED : SAVE_TIME.format(lastSave);
    }

    /** 状态栏一整行（三段用中点分隔），状态栏控件直接绑它。 */
    public String statusLine() {
        return studyDirText() + UiText.STATUS_SEPARATOR + modeText()
                + UiText.STATUS_SEPARATOR + lastSaveText();
    }

    /** 当前学习目录。 */
    public Path studyDir() {
        return studyDir;
    }

    public void setStudyDir(Path studyDir) {
        this.studyDir = studyDir;
    }

    /** 是否已配置 AI。 */
    public boolean aiConfigured() {
        return aiConfigured;
    }

    public void setAiConfigured(boolean aiConfigured) {
        this.aiConfigured = aiConfigured;
    }

    /** 最后保存时间（null = 还没保存过）。 */
    public LocalDateTime lastSave() {
        return lastSave;
    }

    public void setLastSave(LocalDateTime lastSave) {
        this.lastSave = lastSave;
    }
}
