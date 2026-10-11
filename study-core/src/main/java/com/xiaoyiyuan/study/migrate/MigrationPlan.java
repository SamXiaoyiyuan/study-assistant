package com.xiaoyiyuan.study.migrate;

import java.util.List;
import java.util.Objects;

/**
 * 一次迁移的<b>方案</b>：读到的源文本、算好的目标文本、以及迁移过程中记下的备注。
 *
 * <p><b>这是纯数据：它不持有文件句柄、不带路径，也不知道文件系统存在。</b>方案之所以要能脱离文件
 * 系统存在，是因为 spec §4.6 的流程要求「先给用户看 diff、用户确认之后才写盘」——
 * 中间那一步（展示与确认）必须能拿着一份完整的产物而不产生任何写入。源文件路径因此刻意不在本记录里
 * （{@code Migrator} 自己记着它，见 {@code Migrator#apply} 的说明）。
 *
 * @param sourceText   源文件读出来的文本（保真：BOM 留在串首、CRLF 原样，与读取时逐字相同）
 * @param targetText   目标文件的完整文本，固定 LF、无 BOM
 * @param sectionNotes 迁移过程中的备注（源文件缺节、列数不符、用了兜底……），按发生顺序；给人看的
 */
public record MigrationPlan(String sourceText, String targetText, List<String> sectionNotes) {

    /**
     * 紧凑构造器：拒绝 {@code null}、把备注拷成不可变列表。
     *
     * <p>拷贝不是形式主义：方案会被「展示 diff」与「确认写入」两步共用，中间若有人往里塞一条备注，
     * 落盘的就不再是用户确认过的那一份。这里拷一次，方案就是真正的值对象。
     */
    public MigrationPlan {
        Objects.requireNonNull(sourceText, "sourceText");
        Objects.requireNonNull(targetText, "targetText");
        sectionNotes = List.copyOf(Objects.requireNonNull(sectionNotes, "sectionNotes"));
    }
}
