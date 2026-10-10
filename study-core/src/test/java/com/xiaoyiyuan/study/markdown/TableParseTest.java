package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 表格识别（Task 4）。
 *
 * <p>核心风险只有一条：<b>识别不能让原本逐字节保真的文本被重新排版</b>。因此这里的用例分成三层：
 * <ol>
 *   <li>识别本身：什么被认成表格、单元格怎么切（转义竖线、空单元格）；</li>
 *   <li>降级：列数对不上时整块退回原样块，<b>不部分解析、不修、不丢行</b>；</li>
 *   <li>未脏表格必须返回原文——即使「重新生成」会得到另一串字节。第 3 层是本任务最值钱的
 *       负向检查：只要有人把未脏表格改成重新生成，{@link #raggedPaddingCleanTable_rendersOriginalBytes()}
 *       会立刻失败。</li>
 * </ol>
 */
class TableParseTest {

    @Test
    void parsesSimpleTable_intoTableBlock() {
        String md = "| 项 | 内容 |\n|---|---|\n| 年级 | 大二上 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        assertEquals(java.util.List.of("项", "内容"), t.header());
        assertEquals(java.util.List.of(java.util.List.of("年级", "大二上")), t.rows());
    }

    @Test
    void noTrailingNewline_roundTripsExactly() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |";
        assertEquals(md, MarkdownParser.parse(md).render());
    }

    @Test
    void malformedRow_degradesToRawBlock_andNeverLosesText() {
        // 3 列表头，数据行只有 2 个单元格 —— 不崩溃、不丢行、降级为原样文本
        String md = "| a | b | c |\n|---|---|---|\n| 1 | 2 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        assertEquals(md, doc.render(), "错列表格必须原样保留");
        assertTrue(doc.blocks().stream().noneMatch(TableBlock.class::isInstance),
                "错列表格应降级为原样块，而不是被解析成结构块");
    }

    @Test
    void escapedPipe_isNotAColumnSeparator() {
        String md = "| 项 | 内容 |\n|---|---|\n| 公式 | a \\| b |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        assertEquals(1, t.rows().size());
        assertEquals("a \\| b", t.rows().get(0).get(1), "转义竖线属于单元格内容");
    }

    @Test
    void emptyCell_isPreserved() {
        String md = "| a | b |\n|---|---|\n|  | 2 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        assertEquals("", t.rows().get(0).get(0));
    }

    // ---- 以下是 Step 5 要点里要求的补充断言与负向检查 ----

    /**
     * 未脏的表格必须逐字节返回原文，<b>哪怕「按统一列宽重新生成」会得到另一串字节</b>。
     *
     * <p>本用例刻意把手写表格造成不整齐的样子：表头单元格带多余空格、分隔行是 6 个短横。
     * 重新生成会得到 {@code | 项  | 内容 |} + {@code | --- | --- |}，与原文不同。
     * 因此这条断言是「未脏表格不重新生成」这个决定性性质唯一的守门人——没有它，
     * P1 在含表格的文件上会静默失效，而普通往返测试仍可能碰巧通过。
     */
    @Test
    void raggedPaddingCleanTable_rendersOriginalBytes() {
        String md = "| 项   | 内容 |\n|------|------|\n| 年级 | 大二上 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);

        assertTrue(doc.blocks().stream().anyMatch(TableBlock.class::isInstance),
                "这是一个合法表格（列数一致），必须被识别出来，而不是降级掉");
        assertEquals(md, doc.render(), "未脏表格必须返回原文，不能按统一列宽重新生成");
    }

    /** 表格块吸收自己后面的纯空行，不产生「只含换行」的空块（Task 3 已钉死：空行不自成块）。 */
    @Test
    void tableBlockOwnsItsTrailingBlankLine_noBlankOnlyBlock() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |\n\n";
        MarkdownDocument doc = MarkdownParser.parse(md);

        assertEquals(1, doc.blocks().size(), "空行属于前一块，不得自成一块");
        assertEquals(md, doc.render());
        assertEquals(md, doc.blocks().get(0).render(), "表格块连同尾部空行一起持有原文");
    }

    /** 表格块必须是「结构块」：脏了才重新生成，且按显示宽度对齐（CJK 算 2）。 */
    @Test
    void dirtyTable_regeneratesWithDisplayWidthPadding() {
        String md = "| 项 | 内容 |\n|---|---|\n| 年级 | 大二上 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();

        assertFalse(t.dirty(), "刚解析出来的表格不脏");
        t.setCell(0, 1, "大二下");

        assertTrue(t.dirty(), "改单元格必须自动置脏，否则改动不会生效");
        // 列宽：第 0 列 max(项=2, 年级=4)=4；第 1 列 max(内容=4, 大二下=6)=6。
        // 中文按显示宽度 2 计，所以「项」后面要补到 4 列宽（2 个空格）。分隔行统一用 ---。
        assertEquals("| 项   | 内容   |\n"
                        + "| --- | --- |\n"
                        + "| 年级 | 大二下 |\n",
                doc.render(), "脏表格按显示宽度重新生成；对齐空格变化是 spec §4.3 接受的代价");
    }

    /** 第 2 行不是分隔行 -> 整 run 不是表格（不做「往下找一个分隔行」的子串搜索）。 */
    @Test
    void secondLineNotSeparator_isNotATable() {
        String md = "| a | b |\n| 1 | 2 |\n| 3 | 4 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);

        assertTrue(doc.blocks().stream().noneMatch(TableBlock.class::isInstance),
                "第 2 行不是分隔行，整段必须保持原样文本");
        assertEquals(md, doc.render());
    }

    /** 分隔行列数与表头不一致 -> 整块降级（决策 2：不修、不部分解析）。 */
    @Test
    void separatorColumnMismatch_degradesToRawBlock() {
        String md = "| a | b |\n|---|---|---|\n| 1 | 2 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);

        assertTrue(doc.blocks().stream().noneMatch(TableBlock.class::isInstance),
                "分隔行列数与表头不一致，必须整块降级");
        assertEquals(md, doc.render());
    }

    /** 只有 2 行（表头 + 分隔行）不算表格：识别门槛是连续 ≥3 行。 */
    @Test
    void twoLineRun_isNotATable() {
        String md = "| a | b |\n|---|---|\n";
        MarkdownDocument doc = MarkdownParser.parse(md);

        assertTrue(doc.blocks().stream().noneMatch(TableBlock.class::isInstance));
        assertEquals(md, doc.render());
    }

    /** 表格前后各有一段散文时，散文逐字节留在原样块里，只有表格成为结构块。 */
    @Test
    void proseAroundTable_staysRaw() {
        String md = "前面一段散文。\n| a | b |\n|---|---|\n| 1 | 2 |\n后面一段散文。\n";
        MarkdownDocument doc = MarkdownParser.parse(md);

        assertEquals(md, doc.render(), "识别出表格也必须逐字节还原整篇");
        assertEquals(1, doc.blocks().stream().filter(TableBlock.class::isInstance).count(),
                "表格 run 之外的非空行仍是原样块，不并入表格");
        assertTrue(doc.blocks().stream().filter(RawBlock.class::isInstance)
                        .map(Block::render).anyMatch(r -> r.contains("前面一段散文。")),
                "表头之前的散文必须原样保留");
    }

    /** 行序调整（spec §4.4「保留行序」）：moveRow 后行序按新顺序生成。 */
    @Test
    void rowMutations_markDirtyAndKeepOrder() {
        // 所有单元格显示宽度都是 4，这样重新生成不产生补位空格，断言能只盯行序
        String md = "| 阶段 | 主攻 |\n|---|---|\n| 甲乙 | 数学 |\n| 丙丁 | 英语 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();

        t.moveRow(1, 0);
        assertEquals(List.of(List.of("丙丁", "英语"), List.of("甲乙", "数学")), t.rows(), "行序必须按新顺序");

        t.removeRow(0);
        assertEquals(List.of(List.of("甲乙", "数学")), t.rows());

        t.addRow(List.of("戊己", "政治"));
        assertTrue(t.dirty(), "行序调整属于结构块编辑，必须自动置脏");
        assertEquals(List.of(List.of("甲乙", "数学"), List.of("戊己", "政治")), t.rows());

        String out = doc.render();
        assertEquals("| 阶段 | 主攻 |\n"
                + "| --- | --- |\n"
                + "| 甲乙 | 数学 |\n"
                + "| 戊己 | 政治 |\n", out, "重新生成必须反映调整后的行序");
    }

    /** 行/列的非法下标与错列的新行必须当场报错，不能悄悄写坏结构。 */
    @Test
    void mutations_rejectInvalidArguments() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();

        assertThrows(IndexOutOfBoundsException.class, () -> t.setCell(9, 0, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> t.setCell(0, 9, "x"));
        assertThrows(IllegalArgumentException.class, () -> t.addRow(List.of("只有一列")),
                "新行的列数必须与表头一致，否则表格结构会被写坏");
        assertThrows(IndexOutOfBoundsException.class, () -> t.moveRow(0, 5));
        assertFalse(t.dirty(), "失败的编辑不得留下脏标记");
    }

    /**
     * 块边界定义（Global Constraints 已钉死）：块边界<b>只由零字符的空行</b>产生，
     * 只含空格的行是<b>内容</b>。
     *
     * <p>Task 3 只有字节级的往返断言，没有结构断言；Task 4 改写了 MarkdownParser，
     * 因此在这里把结构显式钉住：{@code "甲\n\n \n乙\n"} 必须是 <b>2</b> 个块（不是 3 个）。
     */
    @Test
    void blockBoundary_onlyZeroCharBlankLineSeparates_whitespaceOnlyLineIsContent() {
        String md = "甲\n\n \n乙\n";
        MarkdownDocument doc = MarkdownParser.parse(md);

        assertEquals(2, doc.blocks().size(), "只含空格的行是内容，不是边界：必须只有 2 个块");
        assertEquals("甲\n\n", doc.blocks().get(0).render());
        assertEquals(" \n乙\n", doc.blocks().get(1).render(), "空格行属于它后面的那一块");
        assertEquals(md, doc.render(), "结构改了也必须是逐字节往返");
    }

    /** 单元格切分规则：扫过 {@code \|}，去掉外侧竖线产生的首尾空元素，各去一个前后空格。 */
    @Test
    void splitCells_followsThePinnedRules() {
        assertEquals(List.of("a", "b"), TableBlock.splitCells("| a | b |"));
        assertEquals(List.of(""), TableBlock.splitCells("|  |"));
        assertEquals(List.of("a \\| b", "c"), TableBlock.splitCells("| a \\| b | c |"));
        // 只去前后各一个空格：多出来的空格属于内容（空白就是数据，不能顺手 trim 掉）
        assertEquals(List.of(" 两个前导空格只去掉一个"), TableBlock.splitCells("|  两个前导空格只去掉一个 |"));
        assertEquals(List.of("尾空格留一个 "), TableBlock.splitCells("| 尾空格留一个  |"));
    }

    /**
     * CRLF 的表格：识别要成立，且<b>重新生成时不得把行尾符改成 LF</b>
     * （Global Constraints：读写固定 UTF-8 + {@code \n} 是「新文件」的规定，既有文件
     * 「不得做换行符转换」；脏块回写同样不该悄悄换掉用户的行尾符）。
     */
    @Test
    void crlfTable_isRecognized_andRegeneratesWithCrlf() {
        String md = "| a | b |\r\n|---|---|\r\n| 1 | 2 |\r\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();

        assertEquals(md, doc.render(), "CRLF 表格未修改时必须逐字节返回原文");
        assertEquals(List.of("a", "b"), t.header(), "单元格内容里不得混进 \\r");
        assertEquals(List.of("1", "2"), t.rows().get(0));

        t.setCell(0, 1, "3");
        assertEquals("| a | b |\r\n| --- | --- |\r\n| 1 | 3 |\r\n", doc.render(),
                "重新生成必须沿用原文的行尾符，不得把 CRLF 换成 LF");
    }

    /**
     * 表格在文件末尾且原文件没有末尾换行：重新生成也<b>不许补一个换行</b>。
     *
     * <p>「render() 收尾」（补/删末尾换行）是 P1 最容易被顺手做坏的一处，未脏路径由
     * {@link #noTrailingNewline_roundTripsExactly} 守住，这里补上脏路径。
     */
    @Test
    void dirtyTableWithoutTrailingNewline_doesNotAddOne() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();

        t.setCell(0, 0, "x");
        assertEquals("| a | b |\n| --- | --- |\n| x | 2 |", doc.render(),
                "原文件末尾没有换行时，重新生成也不许补一个");
    }
}
