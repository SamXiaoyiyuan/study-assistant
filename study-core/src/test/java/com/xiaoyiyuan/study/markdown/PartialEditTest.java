package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2：局部隔离——只改一个字段，只有该块的内容变化，其余块字节不变。
 *
 * <p>与 {@link RoundTripTest} 的分工：那边只用原样块验证 P1；这里把<b>表格</b>放进文档中间，
 * 验证「含结构块的文档」同样满足 P1（未改动时整篇字节相同）与 P2（改一格不动别处）。
 * 这两条同时成立，靠的是：未脏的表格块也返回构造时保存的原文，不重新生成。
 */
class PartialEditTest {

    private static final String MD = """
            # 标题

            手写的一段散文，**不要动我**。

            | 项 | 内容 |
            |---|---|
            | 年级 | 大二上 |
            | 目标 | 未定 |

            另一端散文，含 $1^\\infty$ 公式。
            """;

    @Test
    void editOneCell_leavesEveryOtherBlockByteIdentical() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        t.setCell(0, 1, "大二下");

        String out = doc.render();
        assertTrue(out.contains("大二下"), "改动必须生效");
        assertTrue(out.contains("手写的一段散文，**不要动我**。"), "前一段散文必须逐字保留");
        assertTrue(out.contains("另一端散文，含 $1^\\infty$ 公式。"), "后一段散文必须逐字保留");
        assertTrue(out.contains("# 标题"), "标题必须逐字保留");
    }

    @Test
    void untouchedTable_isRenderedFromOriginalBytes() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        assertEquals(MD, doc.render(), "没有改动时整篇必须字节相同（P1 在含表格时同样成立）");
    }

    @Test
    void dirtyFlag_togglesAsExpected() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        Block first = doc.blocks().get(0);
        assertFalse(first.dirty());
        first.markDirty();
        assertTrue(first.dirty());
        first.clearDirty();
        assertFalse(first.dirty());
    }

    @Test
    void rawBlock_editedTextIsUsed() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        RawBlock raw = doc.blocks().stream()
                .filter(RawBlock.class::isInstance).map(RawBlock.class::cast)
                .filter(b -> b.render().contains("手写的一段散文")).findFirst().orElseThrow();
        raw.setCurrentText(raw.render().replace("不要动我", "已经改了"));
        // 原样块的脏必须显式声明：Task 3 把这条钉死在 Block 的契约里并用 RoundTripTest
        // 守住（「只设 currentText 不改脏标记：脏是显式声明，不能靠猜」）。这里如果省掉
        // markDirty()，要么本用例不成立，要么就得推翻已提交的契约——后者是数据破坏风险，
        // 因为界面只要有一次忘了置脏就会静默丢弃用户的编辑。
        raw.markDirty();
        String out = doc.render();
        assertTrue(out.contains("已经改了"));
        assertTrue(out.contains("另一端散文，含 $1^\\infty$ 公式。"), "未改动的块不受影响");
    }

    /**
     * P2 的强版本：改一个单元格后，把结果按 {@code \n} 切行，**除表格那几行之外的行必须逐字节
     * 等于输入的同名行**。这条比 contains 强得多——contains 抓不住「表格块多吐了一个换行」
     * 或「把别的块顺手重排了」这类破坏。
     */
    @Test
    void editOneCell_otherBlocksAreByteIdenticalLineByLine() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        t.setCell(0, 1, "大二下");
        String out = doc.render();

        String[] before = MD.split("\n", -1);
        String[] after = out.split("\n", -1);
        assertEquals(before.length, after.length, "改一个单元格不得增删任何行");

        for (int i = 0; i < before.length; i++) {
            boolean isTableRow = before[i].strip().startsWith("|");
            if (!isTableRow) {
                assertEquals(before[i], after[i], "第 " + (i + 1) + " 行不是表格行，必须逐字节不变");
            }
        }
        // 表格行本身确实变了（否则上面「不变」的断言是白测的）
        assertNotEquals(MD, out, "表格行必须随编辑而变化，否则这条用例什么都没验证");
        assertTrue(out.contains("| 年级 | 大二下 |"), "同一行的另一个单元格不得被改动");
    }

    /** 表格块被清脏后必须退回原文——脏标记是唯一开关，不能靠内容比对来猜。 */
    @Test
    void clearDirty_restoresOriginalBytesForTable() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();

        t.setCell(0, 1, "改坏了");
        assertNotEquals(MD, doc.render());

        t.clearDirty();
        assertEquals(MD, doc.render(), "清脏后整篇必须回到原文（P1 的锚点还在）");
        // 与 RawBlock 的行为一致：清脏只让回写退回原文，不会回滚已经写进模型的内容
        assertEquals(List.of("年级", "改坏了"), t.rows().get(0), "清脏不还原单元格内容，只影响回写");
    }
}
