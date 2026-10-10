package com.xiaoyiyuan.study.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * 把整篇文本切成块，并在块内识别表格（Task 4）。
 *
 * <p>切块规则刻意极简，目标是让 P1 在<b>结构上</b>成立，而不是近似成立。
 *
 * <p>规则（全部集中在 {@link #parse(String)} 与其私有辅助方法里，不散落）：
 * <ol>
 *   <li>连续的<b>非空行</b>（每行含自己的换行符）合成一个「原始片段」；</li>
 *   <li>一段空行<b>附加到它前面那一块的末尾</b>——空行不单独成块，否则块序列里会出现
 *       一堆只含换行的块，语义为空；</li>
 *   <li>空行run 之后的第一个非空行<b>开新块</b>（空行是分块边界），只是那些换行归前一块；</li>
 *   <li>文档<b>开头</b>的空行没有前一块可挂，单独成为第一个块。</li>
 * </ol>
 *
 * <p>于是每个原始片段的结构固定为「<b>非空行前缀 + 纯空行后缀</b>」。{@link #addPiece} 再在
 * 前缀里找出表格 run：识别成功的 run 变成 {@link TableBlock}，其余行归 {@link RawBlock}。
 * 这样任意输入都满足「各块 render() 拼接 == 原文」，因为每一行（连同它的换行符）恰好落在
 * 一个块里：既没有字符被丢弃，也没有字符被重复。
 *
 * <p><b>不要</b>在这里引入任何认识空白的方法：没有 trim / strip / split("\\s+") /
 * isBlank() / lines() / replace("\\r\\n","\\n")。空白就是数据，一次规范化就会毁掉 P1。
 * 表格识别必须看的 {@code strip()} 只用于<b>判断</b>，从不用于改写归属本块的字节。
 */
public final class MarkdownParser {

    private MarkdownParser() {
    }

    /**
     * 切块。对任何输入都保证 {@code parse(text).render().equals(text)}。
     *
     * <p>只按 {@code '\n'} 切：不处理 {@code '\r'}。CRLF 输入里，{@code '\r'} 会作为
     * 上一行内容的最后一个字符被保留；孤立 CR（老 Mac 换行）同理，整篇会成为一个块。
     * 这两种情况下 P1 仍然成立——因为「不认识它」恰恰意味着「不乱动它」。
     */
    public static MarkdownDocument parse(String text) {
        List<Block> blocks = new ArrayList<>();
        // 每行都带着自己的换行符（最后一行可能没有），因此各块拼起来永远等于输入
        List<String> lines = splitKeepingLineEndings(text);

        StringBuilder current = new StringBuilder();
        // 上一个处理过的行是空行吗？空行是分块边界：空行run 之后的非空行要开新块。
        // 用「上一行是否空」这个状态，而不是「此前是否见过非空行」——后者会让
        // 「甲\n\n乙」粘成一块（字节拼接仍对，块结构却是错的，后续任务会踩到）。
        boolean afterBlankRun = false;
        for (String line : lines) {
            if (isBlankLine(line)) {
                // 空行只往「当前段」里塞：可能挂在前面那一块尾部，也可能是文档开头的空行
                current.append(line);
                afterBlankRun = true;
                continue;
            }
            if (afterBlankRun) {
                // 当前段到此为止：若它只含空行，那就是文档开头（或整篇全空）留下的独立块
                addPiece(blocks, current.toString());
                current.setLength(0);
                afterBlankRun = false;
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            addPiece(blocks, current.toString());
        }
        return new MarkdownDocument(blocks);
    }

    /**
     * 把一个原始片段切成若干块：片段里的表格 run 成为 {@link TableBlock}，其余行归原样块。
     *
     * <p>为什么表格识别放在这里而不是塞进上面的主循环：片段结构固定为「非空行前缀 + 纯空行
     * 后缀」（空行是边界），所以表格只可能出现在非空行前缀里，前缀之后的空行归<b>最后</b>一个
     * 内容块所有（保持 Task 3 的「空行不自成块」）。把这条规则集中在一处，主循环就仍然只做
     * 一件最简单的事：按空行切片段。识别规则本身（≥3 行、首尾竖线、第二行是分隔行、列数一致）
     * 只在 {@link #tableRunLength} 里判一次。
     */
    private static void addPiece(List<Block> blocks, String piece) {
        List<String> lines = splitKeepingLineEndings(piece);
        int contentEnd = lines.size();
        while (contentEnd > 0 && isBlankLine(lines.get(contentEnd - 1))) {
            contentEnd--;
        }
        String trailingBlanks = join(lines, contentEnd, lines.size());

        StringBuilder raw = new StringBuilder();
        int i = 0;
        while (i < contentEnd) {
            int run = tableRunLength(lines, i, contentEnd);
            if (run == 0) {
                raw.append(lines.get(i));
                i++;
                continue;
            }
            flushRaw(blocks, raw);
            // 表格是本片段最后一段内容时，它顺理成章地接管尾部空行；否则空行归最后的原样块
            boolean lastContent = (i + run == contentEnd);
            String suffix = lastContent ? trailingBlanks : "";
            blocks.add(toTableBlock(lines, i, run, suffix));
            if (lastContent) {
                trailingBlanks = "";
            }
            i += run;
        }
        raw.append(trailingBlanks);
        flushRaw(blocks, raw);
    }

    /** 累积的原样文本非空时才落块，随后清空缓冲。 */
    private static void flushRaw(List<Block> blocks, StringBuilder raw) {
        if (!raw.isEmpty()) {
            blocks.add(new RawBlock(raw.toString()));
            raw.setLength(0);
        }
    }

    /** 把已确认的表格 run 变成 {@link TableBlock}；原文 = run 各行原文 + suffix。 */
    private static TableBlock toTableBlock(List<String> lines, int start, int run, String suffix) {
        List<String> header = TableBlock.splitCells(lines.get(start));
        List<List<String>> rows = new ArrayList<>(run - 2);
        for (int i = start + 2; i < start + run; i++) {
            rows.add(TableBlock.splitCells(lines.get(i)));
        }
        return new TableBlock(join(lines, start, start + run) + suffix, suffix, header, rows);
    }

    /**
     * 从 {@code start} 起能认成表格的行数；不是表格时返回 0。
     *
     * <p>三个识别条件（Task 4 钉死，全都要满足）：① 连续 ≥3 行；② 每行 {@code strip()} 后
     * 以 {@code |} 开头并以 {@code |} 结尾；③ 第二行是分隔行。
     *
     * <p><b>列数校验</b>：分隔行列数必须与表头一致，且<b>每个数据行的列数都要一致</b>。
     * 任一不符 → <b>整个 run 降级</b>（返回 0，由调用方当原样文本处理）：不部分解析、不修行、
     * 不丢行。宁可少识别一张表，也不能把用户的错行悄悄改掉或吞掉。
     *
     * <p>取的是「最长的连续表格行」这一段，不做「往下找一个分隔行」的子串搜索——后者在
     * 表格附近有零散 {@code |} 行时会切出莫名其妙的块。
     */
    private static int tableRunLength(List<String> lines, int start, int end) {
        int i = start;
        while (i < end && isTableLine(lines.get(i))) {
            i++;
        }
        int run = i - start;
        if (run < 3) {
            return 0;
        }
        if (!isSeparatorLine(lines.get(start + 1))) {
            return 0;
        }
        int columns = TableBlock.splitCells(lines.get(start)).size();
        if (columns == 0 || TableBlock.splitCells(lines.get(start + 1)).size() != columns) {
            return 0;
        }
        for (int j = start + 2; j < i; j++) {
            if (TableBlock.splitCells(lines.get(j)).size() != columns) {
                return 0;
            }
        }
        return run;
    }

    /** 是否是「表格行」：{@code strip()} 后以 {@code |} 开头并以 {@code |} 结尾。 */
    private static boolean isTableLine(String line) {
        String body = line.strip();
        // 长度 ≥2：单个 "|" 既不算表头也不算分隔行，避免把 "|" 这种残缺行拉进表格
        return body.length() >= 2 && body.startsWith("|") && body.endsWith("|");
    }

    /** 是否是分隔行：去掉 {@code |}、{@code -}、{@code :}、空格后什么都不剩。 */
    private static boolean isSeparatorLine(String line) {
        String body = line.strip();
        if (body.isEmpty()) {
            return false;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') {
                return false;
            }
        }
        return true;
    }

    /** 拼接 {@code lines} 的 {@code [from, to)} 区间；只拼接，不加任何修饰。 */
    private static String join(List<String> lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * 按 {@code '\n'} 切成「保留行尾换行符」的行。
     *
     * <p>{@code "甲\n\n乙\n"} → {@code ["甲\n", "\n", "乙\n"]}；末行无换行时就是
     * {@code "乙"}。注意用 {@code String.split} 再用 {@code join} 拼回来是<b>不安全</b>的：
     * split 会丢弃末尾的空元素。所以这里逐字符扫描，保证「各元素拼起来 == 输入」。
     */
    private static List<String> splitKeepingLineEndings(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /**
     * 是否是「空行」：只有换行符，或（文件末行）空字符串。
     *
     * <p>只认 {@code '\n'} 和 {@code '\r'} 两种换行符——刻意<b>不</b>用 {@code isBlank()}。
     * 在本项目里，只含空格或制表符的行是<b>内容</b>：用户可能正在写一段缩进，规范化掉它
     * 就是数据损坏（P1 会当场失败）。误判成空行不影响字节保真，误删字符则不可挽回。
     */
    private static boolean isBlankLine(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }
}
