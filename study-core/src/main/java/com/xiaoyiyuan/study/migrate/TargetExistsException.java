package com.xiaoyiyuan.study.migrate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 目标文件已经存在且非空：迁移拒绝执行（spec §4.6「绝不静默覆盖」）。
 *
 * <p>为什么单独一个异常类型而不是随便抛 {@link IOException}：界面要能<b>分辨</b>这一种情况并给出
 * 「打开已有计划 / 自己决定怎么办」的选择，而不是把它混进「磁盘写失败」里显示成一句错误。
 *
 * <p>抛出本异常时，<b>磁盘上什么都不会多出来</b>：源文件一个字节没动，也没有备份、没有归档、
 * 没有目标文件。检查在 {@code Migrator#apply} 的第一步完成，早于任何写入。
 */
public final class TargetExistsException extends IOException {

    /** 异常会被序列化的场合（例如跨进程传递）里保持兼容；与 SectionMissingException 的写法一致。 */
    private static final long serialVersionUID = 1L;

    /** 被拒绝覆盖的那个路径；界面可以直接拿它去打开或提示。 */
    private final Path target;

    public TargetExistsException(String message, Path target) {
        super(message);
        this.target = Objects.requireNonNull(target, "target");
    }

    /** 已经存在、因此拒绝迁移的那个目标路径。 */
    public Path target() {
        return target;
    }
}
