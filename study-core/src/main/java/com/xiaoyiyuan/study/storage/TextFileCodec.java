package com.xiaoyiyuan.study.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文本文件的编码保真读写。
 *
 * <p>本项目的最高优先级是字节保真：用户的档案由本应用与 AI 助手共同编辑，
 * 读写不逐字节一致就会损坏用户数据。因此读取时<b>不做任何规范化</b>
 * （CRLF 原样留在字符串里，BOM 作为 \uFEFF 留在串首），写入时<b>直接写字符串本身</b>。
 */
public final class TextFileCodec {

    /** UTF-8 BOM 的三个字节。 */
    private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private TextFileCodec() {
    }

    /**
     * 读取文件，返回内容及其字节层特征（BOM / CRLF）。
     *
     * <p>这里刻意用 {@code Files.readAllBytes} + 严格解码，<b>不用</b>
     * {@code Files.readString}：后者会把 BOM 吞掉，我们就再也无法识别它，也就无法在写回时保真。
     *
     * <p>解码用 {@link CodingErrorAction#REPORT}：只有合法 UTF-8 才返回内容，
     * 非法字节序列一律抛 {@link NotUtf8Exception}，绝不返回被 U+FFFD 替换过的有损字符串
     * （那会让用户一保存就永久改写文件）。合法输入的行为与逐字节保真完全不变。
     *
     * @throws NotUtf8Exception 文件不是合法 UTF-8（消息含路径与首个非法字节偏移）
     */
    public static TextFile read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new TextFile(decodeStrictly(bytes, path), hasBom(bytes), hasCrlf(bytes));
    }

    /**
     * 把内容按 UTF-8 写回文件。
     *
     * <p>直接写字符串自身的字节，<b>不做任何替换</b>：不补/不去 BOM、不转换换行符、
     * 不 trim。CRLF 与 BOM 的保真正是靠「什么都不做」实现的。
     */
    public static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 规范化为 LF 且去掉开头的 BOM。
     *
     * <p><b>只允许由用户显式触发</b>（界面上点「规范化为 LF（无 BOM）」），
     * 绝不能在 read / write 路径上自动调用——自动调用就是静默转换。
     */
    public static String normalizeToLf(String content) {
        String withoutBom = content.startsWith("\uFEFF") ? content.substring(1) : content;
        return withoutBom.replace("\r\n", "\n");
    }

    /**
     * 严格按 UTF-8 解码；遇到非法字节序列立即报告，并给出首个非法字节的真实偏移。
     *
     * <p>为什么用增量 API（{@code decode(in, out, endOfInput)}）而不是 {@code decode(ByteBuffer)}
     * 便利方法：{@code MalformedInputException} / {@code UnmappableCharacterException} 在 JDK 里
     * <b>只有</b> {@code getInputLength()}、没有 {@code getInputOffset()}，便利方法拿不到出错位置。
     *
     * <p>为什么偏移就是 {@code in.position()}（已按 JDK 25 源码与实测两点核实）：REPORT 模式下
     * 解码器把 position 停在非法序列的<b>起点</b>——
     * {@code sun.nio.cs.UTF_8$Decoder.malformedForLength(...)} 调
     * {@code updatePositions(src, sp, ...)}，等价于 {@code src.position(sp)}，而 sp 正是该序列起点；
     * 结尾截断时由 {@code CharsetDecoder.decode} 合成 malformed（长度取 {@code in.remaining()}），
     * 而此前 {@code xflow} 也已把 position 置回该序列起点。
     * 「position 停在非法字节之后」只发生在 IGNORE/REPLACE 路径
     * （{@code CharsetDecoder.decode} 里的 {@code in.position(in.position() + cr.length())}）——
     * REPORT 在那之前就 {@code return cr} 了。所以这里不能再减 {@code result.length()}：
     * 那会得到「起点 − 长度」（实测 GBK 首字节在偏移 0 的文件会报出 -1 而非 0）。
     *
     * <p>注意 BOM 不受影响：{@code EF BB BF} 是合法的 UTF-8 三字节序列（U+FEFF），
     * 严格解码只是拒绝<b>非法</b>序列，不会因此吞掉或拒绝 BOM。
     */
    private static String decodeStrictly(byte[] bytes, Path path) throws NotUtf8Exception {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        // UTF-8 解码后的码元数不会超过字节数；+1 是无害的余量，确保不会因 OVERFLOW 而误判
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        CoderResult result = decoder.decode(in, out, true);
        if (result.isError()) {
            int offset = in.position();
            throw new NotUtf8Exception(
                    "文件不是合法 UTF-8，已拒绝读取（未做任何替换）：" + path
                            + "，首个非法字节偏移 " + offset, null);
        }
        // 输入已在 endOfInput=true 下全部消费，UTF-8 无残留状态，flush 不会失败
        decoder.flush(out);
        out.flip();
        return out.toString();
    }

    private static boolean hasBom(byte[] bytes) {
        if (bytes.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (bytes[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否有 CRLF。直接查字节而不查解码后的字符串：UTF-8 不会把 \r\n 编码到别的字节序列里，
     * 字节层判定与内容层判定等价，且不受字符串解码影响。
     */
    private static boolean hasCrlf(byte[] bytes) {
        for (int i = 1; i < bytes.length; i++) {
            if (bytes[i - 1] == '\r' && bytes[i] == '\n') {
                return true;
            }
        }
        return false;
    }
}
