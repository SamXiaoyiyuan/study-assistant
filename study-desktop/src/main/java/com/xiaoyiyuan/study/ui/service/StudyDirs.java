package com.xiaoyiyuan.study.ui.service;

import com.xiaoyiyuan.study.config.AppConfig;
import com.xiaoyiyuan.study.config.AppPaths;
import com.xiaoyiyuan.study.storage.BackupRotation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 界面侧「由学习目录派生出来的路径」。
 *
 * <p>为什么另起一个类而不是改 {@link AppPaths}：{@code AppPaths} 是计划 1 的<b>冻结面</b>，
 * 已经交付并被多处消费；改它等于动一份已经验收过的契约。而本计划新增的三条派生
 * （情绪记录、提示词目录、资料目录）只有在界面层才被用到，所以收在这里。
 *
 * <p>维持 {@code AppPaths} 的一贯姿态：<b>只回答「路径在哪」</b>，不读盘、不建目录、不判存在性
 * ——「要不要建、建不建得成」是写入方的事（{@code FileStore} / {@code BackupRotation}
 * 在真正需要时才建目录）。
 *
 * <p><b>提示词目录不做成配置项</b>：它只由 {@code studyDir} 派生。多一个配置项就多一处
 * 「配置指向 A、实际读 B」的可能，而提示词是 AI 行为的一部分，路径猜错的后果不比计划文件轻。
 */
public final class StudyDirs {

    /** 情绪记录文件名（{@code AppPaths} 没有这条派生）。 */
    private static final String EMOTION_FILE_NAME = "生活与情绪记录.md";

    /** 提示词目录名（用户可编辑；内置回退在 {@code study-core/src/main/resources/prompts}）。 */
    private static final String PROMPTS_DIR_NAME = "提示词";

    /** 资料目录名。 */
    private static final String MATERIALS_DIR_NAME = "资料";

    /** 每个文件保留的备份份数（spec §4.5）。 */
    private static final int BACKUP_KEEP = 10;

    private StudyDirs() {
    }

    /** 当前的学习目录。 */
    public static Path studyDir(AppConfig config) {
        return Objects.requireNonNull(config, "config").studyDir();
    }

    /** 主计划文件 {@code <studyDir>\学习与生活计划.md}。 */
    public static Path planFile(AppConfig config) {
        return AppPaths.planFile(studyDir(config));
    }

    /** 迁移来源（保留不动）{@code <studyDir>\408-进度.md}。 */
    public static Path legacyFile(AppConfig config) {
        return AppPaths.legacyFile(studyDir(config));
    }

    /** 情绪记录 {@code <studyDir>\生活与情绪记录.md}。 */
    public static Path emotionFile(AppConfig config) {
        return studyDir(config).resolve(EMOTION_FILE_NAME);
    }

    /** 用户可编辑的提示词目录 {@code <studyDir>\提示词}。 */
    public static Path promptsDir(AppConfig config) {
        return studyDir(config).resolve(PROMPTS_DIR_NAME);
    }

    /** 资料目录 {@code <studyDir>\资料}（含心理观察记录、导出的对话）。 */
    public static Path materialsDir(AppConfig config) {
        return studyDir(config).resolve(MATERIALS_DIR_NAME);
    }

    /** 备份目录 {@code <studyDir>\.backup}。 */
    public static Path backupDir(AppConfig config) {
        return AppPaths.backupDir(studyDir(config));
    }

    /** 不可变归档目录 {@code <studyDir>\.archive}。 */
    public static Path archiveDir(AppConfig config) {
        return AppPaths.archiveDir(studyDir(config));
    }

    /** 计划文件与情绪记录共用的备份轮转：每份文件保留最近 10 份（spec §4.5）。 */
    public static BackupRotation backupRotation(AppConfig config) {
        return new BackupRotation(backupDir(config), BACKUP_KEEP);
    }
}
