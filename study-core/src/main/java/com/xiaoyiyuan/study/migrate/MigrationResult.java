package com.xiaoyiyuan.study.migrate;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 迁移落盘之后得到的三个路径（spec §4.6：备份 + 不可变归档 + 新计划文件）。
 *
 * <p>三个都返回，是为了让界面能原样告诉用户「东西都在哪」——迁移是不可逆感的操作，
 * 用户需要能自己去 {@code .backup\} 与 {@code .archive\} 里核对。源文件路径刻意不在其中：
 * 它<b>没有被改动、没有被删除、也没有被改名</b>，还在原来的位置。
 *
 * @param target  新写入的计划文件
 * @param backup  {@code .backup\<原文件名>.<yyyyMMdd-HHmmss>.bak}
 * @param archive {@code .archive\<原文件名>}，与迁移前的源文件逐字节相同
 */
public record MigrationResult(Path target, Path backup, Path archive) {

    public MigrationResult {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(archive, "archive");
    }
}
