package com.xiaoyiyuan.study.config;

import java.nio.file.Path;

/**
 * 应用的固定路径与「由学习目录派生出来的路径」。
 *
 * <p>为什么单独一个类：路径一旦散落在界面代码里，就会出现「界面 A 写
 * {@code %USERPROFILE%\study}、界面 B 写 {@code ~/study}」这种
 * 谁也说不清哪份才是用户数据的情况。计划文件里装的是用户真实的档案，
 * 路径猜错的代价是「打开了一个空文件」。所以全部收在这一处，任何地方要路径都从这里取。
 *
 * <p>{@code AppPaths} 只回答「路径在哪」，不读盘、不建目录、不判断存在性——
 * 「要不要建、建不建得成」是调用方的事（例如备份目录由存储层在真正需要时创建）。
 */
public final class AppPaths {

    /** 应用数据目录名（位于 {@code %APPDATA%} 之下）。 */
    private static final String APP_DIR_NAME = "xiaoyiyuan-study";

    /** 默认学习目录：用户主目录下的 {@code study}（见全局约束「路径」一节）。 */
    private static final Path DEFAULT_STUDY_DIR = Path.of(System.getProperty("user.home"), "study");

    /** 主计划文件名；由旧档案 {@code 408-进度.md} 迁移改名而来。 */
    private static final String PLAN_FILE_NAME = "学习与生活计划.md";

    /** 迁移来源文件名；迁移后<b>保留不动</b>，绝不改名、绝不删除。 */
    private static final String LEGACY_FILE_NAME = "408-进度.md";

    private AppPaths() {
    }

    /**
     * 应用数据目录 {@code %APPDATA%\xiaoyiyuan-study}。
     *
     * <p>取不到 {@code APPDATA}（例如非 Windows 环境、或环境被裁剪）时退到
     * {@code user.home\.xiaoyiyuan-study}：配置是「起不来也得能起来」的东西，
     * 这里抛异常等于把路径问题升级成启动问题。
     */
    public static Path appDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return Path.of(System.getProperty("user.home", "."), "." + APP_DIR_NAME);
        }
        return Path.of(appData, APP_DIR_NAME);
    }

    /** 配置文件 {@code config.properties}，与对话记录一起放在应用数据目录下。 */
    public static Path configFile() {
        return appDataDir().resolve("config.properties");
    }

    /** 对话记录目录。本类只给路径，目录的创建由使用方负责。 */
    public static Path conversationsDir() {
        return appDataDir().resolve("conversations");
    }

    /** 默认学习目录 {@code %USERPROFILE%\study}。 */
    public static Path defaultStudyDir() {
        return DEFAULT_STUDY_DIR;
    }

    /** 主计划文件 {@code <studyDir>\学习与生活计划.md}。 */
    public static Path planFile(Path studyDir) {
        return studyDir.resolve(PLAN_FILE_NAME);
    }

    /** 迁移来源 {@code <studyDir>\408-进度.md}（迁移后仍原样保留）。 */
    public static Path legacyFile(Path studyDir) {
        return studyDir.resolve(LEGACY_FILE_NAME);
    }

    /** 备份目录 {@code <studyDir>\.backup}。 */
    public static Path backupDir(Path studyDir) {
        return studyDir.resolve(".backup");
    }

    /** 不可变归档目录 {@code <studyDir>\.archive}。 */
    public static Path archiveDir(Path studyDir) {
        return studyDir.resolve(".archive");
    }
}
