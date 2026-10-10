package com.xiaoyiyuan.study.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结构化表格块（界面按类型化表单编辑，spec §4.2 的「结构块」）。
 *
 * <p><b>为什么未脏时必须返回原文，而不是「生成一遍，反正一样」</b>：这个类既能解析表格，
 * 也能重新生成表格，但两者的字节<b>不会</b>相同——重新生成会把列按统一显示宽度对齐、
 * 把分隔行统一成 {@code ---}。手写表格平时是怎么排的就怎么排（缩进、分隔行用几个短横、
 * 单元格里多出来的空格），一旦用生成结果回写，用户的排版就没了，而且 P1 会静默失效：
 * 「没改过的地方一个字节都不许动」才是这个项目要守的东西。所以：
 *
 * <ul>
 *   <li>{@link #render()} 未脏 → 返回构造时保存的 {@link #originalText}（逐字节，含行尾符）；</li>
 *   <li>{@link #render()} 已脏 → 才重新生成（对齐空格变化是 spec §4.3 明确接受的代价）。</li>
 * </ul>
 *
 * <p>脏标记只由本类自己的编辑方法（{@link #setCell} 等）自动置上；这和 {@link RawBlock}
 * 的 {@code setCurrentText} 刻意不同——那边的 {@code currentText} 是字节缓冲，脏必须由调用方
 * 显式声明；这边的编辑是语义操作（改一格/加一行），调用方不可能「只想改内容不想生效」。
 *
 * <p><b>本类与所有对外方法必须 public</b>：Task 6（{@code plan} 包）、Task 9（{@code migrate} 包）
 * 等在不同包里消费它（见 {@link Block} 的说明）。
 */
public final class TableBlock implements Block {

    /**
     * 本块的原文，且此后<b>永不被改写</b>。等于「表格 run 的原始行原文 + 本块吸收的尾部空行」。
     *
     * <p>这是 P1 的锚点：只要没被标记脏，{@link #render()} 就逐字节返回它。
     */
    private final String originalText;

    /**
     * 表格 run 之后、仍属于本块的尾部纯空行文本（可能为空串）。
     *
     * <p>Task 3 已钉死「空行附加到前一块末尾、空行不自成块」，所以表格块后面的空行归它所有；
     * 重新生成时必须原样接回去，否则会凭空多出/少掉一个空块。
     */
    private final String suffix;

    /** 重新生成时用的行尾符（从原文探测：{"\n"} 或 {"\r\n"}）。 */
    private final String lineEnding;

    /** 原文里表格 run 的末行是否带行尾符；决定重新生成后最后一行要不要补换行。 */
    private final boolean finalLineHasLineEnding;

    /** 表头单元格；不可变。 */
    private final List<String> header;

    /** 数据行（不含表头与分隔行）；外层可变（供增删行），内层不可变。 */
    private final List<List<String>> rows;

    /** 是否被显式标记为改动过；false 时 {@link #render()} 返回原文。 */
    private boolean dirty;

    /**
     * 用「已切好的结构 + 原文」构造。
     *
     * <p>典型调用方是 {@link MarkdownParser}：它负责识别与列数校验，校验不过就把整段降级成
     * {@link RawBlock}，<b>不会</b>调用本构造器。所以这里的列数校验只是最后一道自检。
     *
     * @param originalText 本块原文（表格 run 各行 + 尾部空行），逐字节保存
     * @param suffix       表格 run 之后的尾部纯空行文本（可为 ""）
     * @param header       表头单元格（非空）
     * @param rows         数据行；每行列数必须与表头一致
     */
    public TableBlock(String originalText, String suffix, List<String> header, List<List<String>> rows) {
        this.originalText = Objects.requireNonNull(originalText, "originalText");
        this.suffix = Objects.requireNonNull(suffix, "suffix");
        List<String> headerCopy = List.copyOf(Objects.requireNonNull(header, "header"));
        if (headerCopy.isEmpty()) {
            throw new IllegalArgumentException("表格至少要有一列");
        }
        List<List<String>> rowCopies = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            List<String> rowCopy = List.copyOf(Objects.requireNonNull(row, "row"));
            if (rowCopy.size() != headerCopy.size()) {
                throw new IllegalArgumentException("数据行列数 " + rowCopy.size()
                        + " 与表头列数 " + headerCopy.size() + " 不一致：调用方应把整段降级为原样块");
            }
            rowCopies.add(rowCopy);
        }
        this.header = headerCopy;
        this.rows = rowCopies;
        this.lineEnding = detectLineEnding(originalText);
        // 末行有没有行尾符，只能看原文末字符（最后一行在文件末尾且没换行时就没有）。
        // 注意不能只看 '\n'：尾部空行可能是孤立的 '\r'（老 Mac 换行），它同样是原文的一部分。
        this.finalLineHasLineEnding = originalText.endsWith("\n") || originalText.endsWith("\r");
    }

    /** 表头单元格；返回不可变列表。 */
    public List<String> header() {
        return header;
    }

    /**
     * 数据行（不含表头与分隔行）；返回不可变副本。
     *
     * <p>刻意不给外部可变视图：所有改动都必须走 {@link #setCell}/{@link #addRow} 等方法，
     * 否则脏标记会被绕过——脏标记一旦被绕过，P2 就失去意义。
     */
    public List<List<String>> rows() {
        return List.copyOf(rows);
    }

    /** 列数（表头列数）。 */
    public int columnCount() {
        return header.size();
    }

    /**
     * 改一个单元格，并自动置脏。
     *
     * @throws IndexOutOfBoundsException 行或列下标越界
     */
    public void setCell(int rowIndex, int colIndex, String value) {
        Objects.requireNonNull(value, "value");
        checkRowIndex(rowIndex);
        if (colIndex < 0 || colIndex >= header.size()) {
            throw new IndexOutOfBoundsException("列下标 " + colIndex + " 越界（本表 "
                    + header.size() + " 列）");
        }
        List<String> row = new ArrayList<>(rows.get(rowIndex));
        row.set(colIndex, value);
        rows.set(rowIndex, List.copyOf(row));
        markDirty();
    }

    /**
     * 在末尾追加一行，并自动置脏。
     *
     * @throws IllegalArgumentException 列数与表头不一致（宁可报错，也不写坏表结构）
     */
    public void addRow(List<String> cells) {
        List<String> copy = List.copyOf(Objects.requireNonNull(cells, "cells"));
        if (copy.size() != header.size()) {
            throw new IllegalArgumentException("新行有 " + copy.size() + " 列，但本表是 "
                    + header.size() + " 列");
        }
        rows.add(copy);
        markDirty();
    }

    /** 删除一行，并自动置脏。 */
    public void removeRow(int rowIndex) {
        checkRowIndex(rowIndex);
        rows.remove(rowIndex);
        markDirty();
    }

    /**
     * 把一行从 {@code from} 移到 {@code to}（spec §4.4：阶段排期是有序表，界面支持上移/下移）。
     *
     * <p>{@code to} 是「移完之后所在的位置」，语义同 {@code List.add(to, remove(from))}。
     */
    public void moveRow(int from, int to) {
        checkRowIndex(from);
        checkRowIndex(to);
        rows.add(to, rows.remove(from));
        markDirty();
    }

    @Override
    public boolean dirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
    }

    @Override
    public void clearDirty() {
        this.dirty = false;
    }

    /**
     * 未脏返回原文（P1），脏了才重新生成。
     *
     * <p>重新生成的行尾符沿用原文风格（CRLF 表格不会因为改一格就变成 LF）：全局约束里
     * 「不得做换行符转换」对「脏块回写」同样应当成立，而这件事几乎零成本。
     */
    @Override
    public String render() {
        return dirty ? regenerate() : originalText;
    }

    /** 原文（不受脏标记影响）；调试与 P1 取证用。 */
    public String originalText() {
        return originalText;
    }

    // ---- 重新生成（只在脏的时候走这条路径） ----

    private String regenerate() {
        int columns = header.size();
        int[] widths = columnWidths(columns);
        StringBuilder sb = new StringBuilder();
        appendRow(sb, header, widths);
        sb.append(lineEnding);
        appendSeparator(sb, columns);
        sb.append(lineEnding);
        for (int r = 0; r < rows.size(); r++) {
            appendRow(sb, rows.get(r), widths);
            if (r < rows.size() - 1 || finalLineHasLineEnding) {
                sb.append(lineEnding);
            }
        }
        return sb.append(suffix).toString();
    }

    /** 一行写成 {@code | 单元格 | 单元格 |}，每列按显示宽度左侧补空格对齐。 */
    private static void appendRow(StringBuilder sb, List<String> cells, int[] widths) {
        sb.append('|');
        for (int c = 0; c < cells.size(); c++) {
            String cell = cells.get(c);
            sb.append(' ').append(cell);
            int pad = widths[c] - displayWidth(cell);
            for (int k = 0; k < pad; k++) {
                sb.append(' ');
            }
            sb.append(" |");
        }
    }

    /** 分隔行统一用 {@code ---}（不按列宽补短横，spec §4.3 已接受重新排版的代价）。 */
    private static void appendSeparator(StringBuilder sb, int columns) {
        sb.append('|');
        for (int c = 0; c < columns; c++) {
            sb.append(" --- |");
        }
    }

    private int[] columnWidths(int columns) {
        int[] widths = new int[columns];
        for (int c = 0; c < columns; c++) {
            widths[c] = displayWidth(header.get(c));
        }
        for (List<String> row : rows) {
            for (int c = 0; c < columns; c++) {
                int w = displayWidth(row.get(c));
                if (w > widths[c]) {
                    widths[c] = w;
                }
            }
        }
        return widths;
    }

    /**
     * 显示宽度：CJK / 全角字符算 2，其余算 1。
     *
     * <p>为什么不能用 {@code String.length()}：等宽字体里一个汉字的宽度是两个半角字符，
     * 用字符数对齐会让含中文的表格歪掉。按码点算，避免把增补平面字符（如 CJK 扩展 B）拆开。
     */
    public static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            width += isWideCodePoint(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return width;
    }

    /** East Asian Wide / Fullwidth 的主要区段（含常用 emoji）。 */
    private static boolean isWideCodePoint(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)      // 谚文字母
                || (cp >= 0x2E80 && cp <= 0x303E)  // 康熙部首、CJK 符号（不含 U+303F）
                || (cp >= 0x3041 && cp <= 0x33FF)  // 假名、注音、韩文兼容字母、CJK 兼容
                || (cp >= 0x3400 && cp <= 0x4DBF)  // CJK 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF)  // CJK 基本区
                || (cp >= 0xA000 && cp <= 0xA4CF)  // 彝文
                || (cp >= 0xAC00 && cp <= 0xD7A3)  // 韩文音节
                || (cp >= 0xF900 && cp <= 0xFAFF)  // CJK 兼容表意文字
                || (cp >= 0xFE10 && cp <= 0xFE19)  // 竖排标点
                || (cp >= 0xFE30 && cp <= 0xFE6F)  // CJK 兼容形式、小写变体
                || (cp >= 0xFF00 && cp <= 0xFF60)  // 全角 ASCII 变体
                || (cp >= 0xFFE0 && cp <= 0xFFE6)  // 全角符号
                || (cp >= 0x1F300 && cp <= 0x1F64F) // emoji（图形、表情）
                || (cp >= 0x1F900 && cp <= 0x1F9FF) // emoji（补充符号）
                || (cp >= 0x20000 && cp <= 0x3FFFD); // CJK 扩展 B 及以后
    }

    // ---- 切分与杂项 ----

    /**
     * 把一行表格文本切成单元格。
     *
     * <p>规则（Task 4 钉死）：
     * <ol>
     *   <li>先扫 {@code \|}——转义竖线属于内容，不是列分隔符；</li>
     *   <li>再按 {@code |} 切；</li>
     *   <li>去掉外侧竖线切出来的首尾空元素；</li>
     *   <li>每个单元格<b>只</b>去掉前后各一个空格（Markdown 表格惯例），其余空白原样保留——
     *       空白就是数据，多一个空格也不能替用户决定它不存在。</li>
     * </ol>
     *
     * <p>本方法同时被 {@link MarkdownParser} 用于列数校验，因此它必须与识别逻辑用的是同一套
     * 切分规则：两处各写一份，就会出现「校验说有 3 列、解析出来 2 列」这种最难查的错。
     */
    public static List<String> splitCells(String rowText) {
        String body = rowText.strip();
        List<String> raw = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                cell.append("\\|");
                i++;
                continue;
            }
            if (c == '|') {
                raw.add(cell.toString());
                cell.setLength(0);
                continue;
            }
            cell.append(c);
        }
        raw.add(cell.toString());

        if (!raw.isEmpty() && raw.get(0).isEmpty()) {
            raw.remove(0);
        }
        if (!raw.isEmpty() && raw.get(raw.size() - 1).isEmpty()) {
            raw.remove(raw.size() - 1);
        }

        List<String> cells = new ArrayList<>(raw.size());
        for (String r : raw) {
            cells.add(trimOneSpaceEachSide(r));
        }
        return cells;
    }

    /** 只去前后各一个空格：多出来的空格是内容。 */
    private static String trimOneSpaceEachSide(String s) {
        int from = s.startsWith(" ") ? 1 : 0;
        int to = (s.endsWith(" ") && s.length() > from) ? s.length() - 1 : s.length();
        return s.substring(from, to);
    }

    /** 探测原文用的行尾符；没有 CRLF 时用 LF（新建文件的规定就是 LF）。 */
    private static String detectLineEnding(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private void checkRowIndex(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new IndexOutOfBoundsException("行下标 " + rowIndex + " 越界（本表 "
                    + rows.size() + " 行数据）");
        }
    }
}
