package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoundTripTest {

    private static String fixture() throws IOException {
        try (InputStream in = RoundTripTest.class.getResourceAsStream("/fixtures/sample-plan.md")) {
            assertNotNull(in, "测试样例必须存在");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 把每个块的 render() 顺序拼起来——切块是否完整，用这个看，与具体分块策略无关。 */
    private static String joined(MarkdownDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (Block b : doc.blocks()) {
            sb.append(b.render());
        }
        return sb.toString();
    }

    @Test
    void parseThenRender_isByteIdentical() throws IOException {
        String original = fixture();
        MarkdownDocument doc = MarkdownParser.parse(original);
        assertEquals(original, doc.render(), "P1：不修改就写回，必须逐字符相同");
    }

    @Test
    void parseThenRender_fileWithoutTrailingNewline_isByteIdentical() {
        String original = "# 标题\n\n最后一行没有换行";
        MarkdownDocument doc = MarkdownParser.parse(original);
        assertEquals(original, doc.render(), "不许擅自补末尾换行");
    }

    @Test
    void parseThenRender_emptyAndWhitespaceOnlyInput() {
        assertEquals("", MarkdownParser.parse("").render());
        assertEquals("\n\n", MarkdownParser.parse("\n\n").render());
    }

    @Test
    void parse_keepsEveryLineInSomeBlock() throws IOException {
        String original = fixture();
        MarkdownDocument doc = MarkdownParser.parse(original);
        String joined = doc.blocks().stream().map(Block::render).reduce("", String::concat);
        assertEquals(original, joined, "所有块拼起来必须还原原文");
    }

    // ---- 以下是本次钉死的选择（空行归属、render 不收尾、空白即数据）的守卫测试 ----

    @Test
    void parse_blankLinesBelongToThePrecedingBlock() {
        // 空行归属钉死为「附加到前一块末尾」：若某次重构让空行自成一类块，
        // 这个断言会立刻指出某一块里不再包含它的换行，而不必等到字节比对失败。
        String text = "甲\n\n乙\n";
        MarkdownDocument doc = MarkdownParser.parse(text);

        assertEquals(2, doc.blocks().size(), "空行不得自成块：应只有「甲」与「乙」两块");
        assertEquals("甲\n\n", doc.blocks().get(0).render(), "第一块必须带上它后面的两个换行");
        assertEquals("乙\n", doc.blocks().get(1).render(), "末行的换行属于最后一块");

        String other = "甲\n\n\n乙";
        MarkdownDocument doc2 = MarkdownParser.parse(other);
        assertEquals(2, doc2.blocks().size(), "连续空行仍是前一块的尾部");
        assertEquals("甲\n\n\n", doc2.blocks().get(0).render());
        assertEquals("乙", doc2.blocks().get(1).render());
    }

    @Test
    void parse_booleanFlagIsNotEnough_blankRunEndsTheBlock() {
        // 「甲\n\n乙」里 乙 前面有空行，所以 乙 不在 甲 那一块里——空行是分块边界，
        // 不是「可以跨过去继续攒」的填充物。用错状态的实现（例如只用一个布尔量记「见过非空行」）
        // 会把它合成一块，字节拼接仍然对，块结构却错了，后续 Task 4/5 会踩到。
        String text = "甲\n\n乙";
        MarkdownDocument doc = MarkdownParser.parse(text);

        assertEquals(2, doc.blocks().size(), "空行run 之后的非空行必须开新块");
        assertEquals("甲\n\n", doc.blocks().get(0).render());
        assertEquals("乙", doc.blocks().get(1).render());
    }

    @Test
    void parse_leadingBlankLinesBecomeTheirOwnFirstBlock() {
        String text = "\n\n# 标题\n";
        MarkdownDocument doc = MarkdownParser.parse(text);

        assertEquals(2, doc.blocks().size(), "文档开头的空行没有前一块可挂，必须单独成块");
        assertEquals("\n\n", doc.blocks().get(0).render(), "开头空行块只含那两个换行");
        assertEquals("# 标题\n", doc.blocks().get(1).render(), "标题不得被开头空行污染");
        assertEquals(text, doc.render());
    }

    @Test
    void parse_thenRender_isIdentityForAwkwardWhitespace() {
        // 空白就是数据：行尾空格、只有空格的行、CRLF、BOM 全都原样保留，不做任何规范化。
        String withTrailingSpaces = "# 标题  \n正文\t\n \n末行 ";
        assertEquals(withTrailingSpaces, MarkdownParser.parse(withTrailingSpaces).render(),
                "行尾空白与只含空格的行必须原样保留");

        String crlf = "# 标题\r\n\r\n正文\r\n";
        assertEquals(crlf, MarkdownParser.parse(crlf).render(), "不得把 CRLF 转换或吞掉 CR");

        String bom = "\uFEFF# 标题\n";
        assertEquals(bom, MarkdownParser.parse(bom).render(), "BOM 是普通字符，不能被吞掉");

        String notTerminated = "第一行\n第二行";
        assertEquals(notTerminated, MarkdownParser.parse(notTerminated).render(),
                "文件末尾没有换行时不许补一个");
    }

    @Test
    void parseThenRender_fixture_hasNoSurpriseCleanup() throws IOException {
        // render() 不许收尾：末字节必须还是原文那个换行，长度也不能被修剪过。
        String original = fixture();
        String rendered = MarkdownParser.parse(original).render();

        assertEquals(original.length(), rendered.length(), "render() 不得增删任何字符");
        assertTrue(rendered.endsWith("\n"), "样例以换行结尾，render() 必须保留它");
        assertEquals(original, joined(MarkdownParser.parse(original)));
    }

    @Test
    void rawBlock_rendersOriginalTextUntilMarkedDirty() {
        RawBlock block = new RawBlock("原文一行\n");

        assertFalse(block.dirty(), "新块默认不脏");
        assertEquals("原文一行\n", block.currentText(), "未改动时 currentText 就是原文");
        assertEquals("原文一行\n", block.render(), "干净块必须逐字节返回原文");

        block.setCurrentText("改过的一行\n");
        assertFalse(block.dirty(), "只设 currentText 不改脏标记：脏是显式声明，不能靠猜");
        assertEquals("原文一行\n", block.render(), "未声明脏时，改动一律不生效");

        block.markDirty();
        assertTrue(block.dirty());
        assertEquals("改过的一行\n", block.render(), "脏块返回 currentText");

        block.clearDirty();
        assertFalse(block.dirty());
        assertEquals("原文一行\n", block.render(), "清脏后回到原文");
    }

    @Test
    void markDirty_singleBlock_onlyThatBlockChanges() {
        // P2 的雏形：只改一块，其余块的字节必须原封不动。
        String text = "第一段\n\n第二段\n\n第三段\n";
        MarkdownDocument doc = MarkdownParser.parse(text);

        List<Block> blocks = doc.blocks();
        assertEquals(3, blocks.size());

        // 用 Block 接口编辑（后续任务的语义块也只承诺这一组方法），不依赖 RawBlock 特有 API
        Block first = blocks.get(0);
        assertEquals("第一段\n\n", first.render());

        assertTrue(first instanceof RawBlock, "Task 3 里所有块都还是原样块");
        ((RawBlock) first).setCurrentText("第一段（已编辑）\n\n");
        first.markDirty();

        String changed = "第一段（已编辑）\n\n";
        String expected = changed + "第二段\n\n第三段\n";
        assertEquals(expected, doc.render(), "被标记的块换成新内容，其余块逐字节不变");
        assertEquals("第二段\n\n", ((RawBlock) blocks.get(1)).currentText(),
                "未被标记的块仍保留自己的原文（含归属它的空行）");
    }
}
