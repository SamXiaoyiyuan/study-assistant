package com.xiaoyiyuan.study.ui;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.xiaoyiyuan.study.config.AppPaths;
import com.xiaoyiyuan.study.ui.text.UiText;

/**
 * 首次运行检查：<b>只读</b>，绝不创建目录、绝不写文件，也绝不抛给启动路径。
 *
 * <p>这条纪律来自一个很具体的场景：用户要靠 App 才能把配置改回去，所以「目录不存在」
 * 「文件读不动」「没权限」这三种情况都必须让界面<b>照常起来</b>，只多一条中性提示。
 * 因此 {@link #inspect(Path)} 的返回值里没有异常，只有 {@code ready} 与 {@code notices}。</p>
 *
 * <p>{@code ready = false} 的含义是「还没有可用的计划文件」，不是「出错了」——
 * 首次安装本来就是没有计划文件的常态。</p>
 */
public final class StartupCheck {

    /** 计划文件不存在、也没有旧档案时的中性提示。 */
    public static final String NOTICE_NO_PLAN = UiText.STARTUP_NO_PLAN;

    /** 计划文件不存在、但旧档案还在时的中性提示。 */
    public static final String NOTICE_LEGACY_PRESENT = UiText.STARTUP_LEGACY_PRESENT;

    /** 学习目录不可写时的中性提示。 */
    public static final String NOTICE_NOT_WRITABLE = UiText.STARTUP_NOT_WRITABLE;

    /** 计划文件读不动时的提示前缀。 */
    public static final String NOTICE_UNREADABLE_PREFIX = UiText.STARTUP_UNREADABLE_PREFIX;

    /** 计划文件读不动时的补救路径。 */
    public static final String NOTICE_UNREADABLE_SUFFIX = UiText.STARTUP_UNREADABLE_SUFFIX;

    /** 实际显示的那一条（前缀 + 补救路径，避免界面拼字符串）。 */
    public static final String NOTICE_UNREADABLE = NOTICE_UNREADABLE_PREFIX + NOTICE_UNREADABLE_SUFFIX;

    private StartupCheck() {
    }

    /**
     * 检查结果。
     *
     * @param ready      计划文件可用（界面正常进入总览）
     * @param notices    中性提示（按显示顺序；一切正常时为空）
     * @param planFile   主计划文件路径 {@code <studyDir>\学习与生活计划.md}
     * @param legacyFile 旧档案路径 {@code <studyDir>\408-进度.md}
     * @param studyDir   本次检查的学习目录（原样回传，状态栏要用）
     */
    public record Result(boolean ready, List<String> notices, Path planFile, Path legacyFile, Path studyDir) {

        /** 列表不可变：提示条的内容不该被调用方当场改掉。 */
        public Result {
            notices = List.copyOf(notices);
        }
    }

    /**
     * 只读检查（不创建目录、不写文件、不抛异常）。
     *
     * @param studyDir 学习目录（可以还不存在）
     */
    public static Result inspect(Path studyDir) {
        return inspect(studyDir, StartupCheck::isWritable);
    }

    /**
     * {@link #inspect(Path)} 的<b>可注入</b>形式：「该目录可写否」由一个函数决定。
     *
     * <p>抽成这个方法是为了让<b>「不可写 → 只给中性提示、不阻断」这条失败分支真的能被测到</b>
     * （minor 清算批 Minor-1，同 {@code ThemeManager.verify(Function)} 的既定手法）：
     * 旧测试拿 {@code C:\\Windows\\…} 当不可写点，在本机被 {@code assumeFalse} 永久跳过，
     * 变异把判据改成 {@code if (false)} 仍然全绿。现在测试直接注入「不可写」，这条分支恒定承重。</p>
     *
     * @param studyDir     学习目录（可以还不存在）
     * @param writableProbe 「这个目录可写吗」的判定；生产路径是 {@link #isWritable(Path)}
     */
    static Result inspect(Path studyDir, java.util.function.Predicate<Path> writableProbe) {
        Path dir = Objects.requireNonNull(studyDir, "studyDir");
        java.util.function.Predicate<Path> probe =
                Objects.requireNonNull(writableProbe, "writableProbe");
        Path plan = AppPaths.planFile(dir);
        Path legacy = AppPaths.legacyFile(dir);
        List<String> notices = new ArrayList<>();

        if (!probe.test(dir)) {
            // 最要紧的一条：写不进去。其余提示此时都无关紧要（计划文件就算在也存不了）。
            notices.add(NOTICE_NOT_WRITABLE);
            return new Result(false, notices, plan, legacy, dir);
        }

        if (!Files.isRegularFile(plan)) {
            notices.add(Files.exists(plan) ? NOTICE_UNREADABLE : (Files.isRegularFile(legacy)
                    ? NOTICE_LEGACY_PRESENT
                    : NOTICE_NO_PLAN));
            return new Result(false, notices, plan, legacy, dir);
        }

        try {
            Files.readAllBytes(plan);
        } catch (IOException | RuntimeException e) {
            // 计划文件在，但读不出来（被别的程序独占、权限不足……）：起得来 + 一条中性提示。
            notices.add(NOTICE_UNREADABLE);
            return new Result(false, notices, plan, legacy, dir);
        }
        return new Result(true, notices, plan, legacy, dir);
    }

    /**
     * 是否该提供「迁移旧档案」（spec §4.6）：计划文件<b>不存在</b>且 {@code 408-进度.md} <b>存在</b>。
     */
    public static boolean shouldOfferMigration(Path studyDir) {
        Path dir = Objects.requireNonNull(studyDir, "studyDir");
        return !Files.exists(AppPaths.planFile(dir)) && Files.isRegularFile(AppPaths.legacyFile(dir));
    }

    /**
     * 学习目录是否可写。
     *
     * <p><b>目录还不存在不等于不可写</b>——那是全新安装的常态。所以往最近的<b>已存在</b>祖先上探，
     * 看它（或它所在的卷）能不能写；探到根都还没找到时保守地判为可写，交给真正保存时报错。
     * 把「目录还没建」报成不可写，会让第一次启动的用户被劝去改一个根本没问题的路径。</p>
     */
    public static boolean isWritable(Path studyDir) {
        Path dir = Objects.requireNonNull(studyDir, "studyDir").toAbsolutePath();
        Path probe = dir;
        try {
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe != null && !Files.isWritable(probe)) {
                return false;
            }
            return !Files.getFileStore(dir).isReadOnly();
        } catch (IOException | RuntimeException e) {
            // 连「能不能写」都问不出来时保守判为可写：不把一次探询失败升级成启动阻断。
            return true;
        }
    }
}
