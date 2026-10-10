package com.xiaoyiyuan.study.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * 把整篇文本切成块。
 *
 * <p>本任务只切块、不做语义识别（表格识别留给 Task 4），因此切块规则刻意极简，
 * 目标是让 P1 在<b>结构上</b>成立，而不是近似成立。
 *
 * <p>规则（全部集中在 {@link #parse(String)} 里，不散落）：
 * <ol>
 *   <li>连续的<b>非空行</b>（每行含自己的换行符）合成一个块；</li>
 *   <li>一段空行<b>附加到它前面那一块的末尾</b>——空行不单独成块，否则块序列里会出现
 *       一堆只含换行的块，语义为空；</li>
 *   <li>空行run 之后的第一个非空行<b>开新块</b>（空行是分块边界），只是那些换行归前一块；</li>
 *   <li>文档<b>开头</b>的空行没有前一块可挂，单独成为第一个块。</li>
 * </ol>
 *
 * <p>这样任意输入都满足「各块 render() 拼接 == 原文」，因为每一行（连同它的换行符）
 * 恰好落在一个块里：既没有字符被丢弃，也没有字符被重复。
 *
 * <p><b>不要</b>在这里引入任何认识空白的方法：没有 trim / strip / split("\\s+") /
 * isBlank() / lines() / replace("\\r\\n","\\n")。空白就是数据，一次规范化就会毁掉 P1。
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
                blocks.add(new RawBlock(current.toString()));
                current.setLength(0);
                afterBlankRun = false;
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            blocks.add(new RawBlock(current.toString()));
        }
        return new MarkdownDocument(blocks);
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
