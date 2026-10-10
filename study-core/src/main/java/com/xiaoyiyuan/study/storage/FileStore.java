package com.xiaoyiyuan.study.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 单个文本文件的读取、保存与「外部修改」防护。
 *
 * <p>这个类是本应用唯一能毁掉用户数据的入口：档案由本应用、AI 助手、用户自己的编辑器
 * <b>三方共同编辑</b>，谁也不知道另外两方什么时候落盘。所以策略刻意保守：
 * <ul>
 *   <li>只有显式 {@link #save} 才会写盘，没有任何自动保存路径；</li>
 *   <li>写之前先备份，备份失败就不写；</li>
 *   <li>磁盘内容和我们最后确认过的内容不一致时，<b>一个字节都不写</b>，把「重新载入 / 覆盖」的选择交还用户。</li>
 * </ul>
 *
 * <p><b>基线哈希</b>＝我们最后一次确认过的文件字节的 SHA-256，只在三处更新：
 * 成功的 {@link #read()}、{@link #reload()}、{@link #save}。
 * 哈希必须对<b>原始字节</b>计算（{@link #contentHash()}）：对 String 求哈希会随字符集变化，
 * 与「磁盘上这串字节没变过」这个承诺不是一回事。
 */
public final class FileStore {

    /** 空字节的 SHA-256：文件不存在时它就是基线（决策 5），也是「还没确认过」的初始基线。 */
    private static final String EMPTY_HASH = sha256(new byte[0]);

    private final Path file;
    private final BackupRotation rotation;

    /** 基线的初值是空字节的哈希，见构造函数的说明。永不为 null，因此可以安全地直接 equals。 */
    private String baselineHash = EMPTY_HASH;

    /**
     * 构造时<b>不</b>读盘。
     *
     * <p>理由有两条：其一，「基线」的语义是「我们已经确认过的内容」，而构造这一刻还没有任何确认
     * （本构造函数的签名也刻意不抛 {@code IOException}）；其二，基线的初值取空字节的哈希，
     * 正好就是「文件还不存在」时应有的值，新建文件路径天然成立。
     *
     * <p>副作用是：磁盘上已有非空内容时，若调用方一次 {@link #read()} 都没做就直接 {@link #save}，
     * 会因为对不上基线而返回 {@link SaveOutcome#EXTERNAL_CHANGE_DETECTED}。
     * 这是<b>失败关闭</b>（宁可少写一次，也不在没确认过内容的情况下覆盖），不是漏报。
     */
    public FileStore(Path file, BackupRotation rotation) {
        this.file = file;
        this.rotation = rotation;
    }

    /**
     * 读取文件内容，并把当前字节记为新基线。
     *
     * <p>文件不存在<b>不是错误</b>：返回空串，基线取空字节的哈希（决策 5），
     * 这样「新建文件」的路径也能走通。
     *
     * <p>非法 UTF-8 由 {@link TextFileCodec#read} 抛 {@link NotUtf8Exception}：
     * 解码失败不算「成功 read」，基线保持原样，绝不返回被 U+FFFD 替换过的有损内容。
     */
    public String read() throws IOException {
        if (!Files.exists(file)) {
            baselineHash = EMPTY_HASH;
            return "";
        }
        // 字节先读、内容后读，基线的取值时点就是「先读的那一次」。
        // 万一文件恰好在这两次读取之间被改，基线会停在旧版本而拿到的是新内容，
        // 下次 save 会多报一次外部修改（失败关闭）；
        // 反过来先取内容、后取字节，基线就会比内容新，真实的外部修改会被漏报、然后被静默覆盖。
        byte[] bytes = Files.readAllBytes(file);
        String content = TextFileCodec.read(file).content();
        baselineHash = sha256(bytes);
        return content;
    }

    /**
     * 保存内容；返回是否真的写了。
     *
     * <p><b>下面这个顺序是安全契约本身，不可重排：</b>
     * <ol>
     *   <li>读当前磁盘字节并算哈希——比较依据必须是「此刻的磁盘」，不能是任何缓存；</li>
     *   <li>与基线不一致且 {@code force == false}：立刻返回 {@link SaveOutcome#EXTERNAL_CHANGE_DETECTED}，
     *       <b>一个字节都不写，也不建备份</b>（检查在备份之前，所以磁盘上一切照旧）；</li>
     *   <li>否则先 {@link BackupRotation#backup}，备份失败就让异常抛出——绝不「备份失败但照样写」；</li>
     *   <li>再 {@link TextFileCodec#write}；</li>
     *   <li>最后把新字节记为新基线。</li>
     * </ol>
     *
     * @param force 用户已经确认「覆盖」：跳过外部修改检查，直接备份后写下去
     */
    public SaveOutcome save(String content, boolean force) throws IOException {
        byte[] current = readBytesOrEmpty();
        if (!force && !sha256(current).equals(baselineHash)) {
            // 磁盘内容不是我们最后确认过的内容：别人动过这个文件。
            // 用户还没在「重新载入 / 覆盖」里做选择，所以这里什么都不做——
            // 一旦先写了再说，用户的新内容就永久没了，备份也救不回「没保存过的东西」。
            return SaveOutcome.EXTERNAL_CHANGE_DETECTED;
        }
        if (Files.exists(file)) {
            // 写之前先留退路；备份抛异常就让 save 抛出去，调用方必须看到「这次没保存成功」。
            rotation.backup(file);
        }
        // 走到这里才写。文件原本不存在就没有内容可备份（BackupRotation.backup 对不存在的文件抛异常），
        // 于是这一步天然就是决策 5 要的「新建文件」路径。
        TextFileCodec.write(file, content);
        // 基线＝刚刚交给 TextFileCodec.write 的那串字节（TextFileCodec.write 写的就是它的 UTF-8 编码）。
        // 刻意<b>不</b>回读文件来算基线：回读会多出「写完到回读之间别人又改了」的窗口，
        // 一旦踩上，基线会变成别人的内容，下次保存就会把别人的改动当成「没变过」而静默覆盖。
        baselineHash = sha256(content.getBytes(StandardCharsets.UTF_8));
        return SaveOutcome.SAVED;
    }

    /**
     * 磁盘内容是否已不同于基线（即被本应用之外的某一方改过）。
     *
     * <p>基线未知时（构造后还没成功 read/reload/save）初始基线是空字节的哈希，
     * 于是已有内容的文件会直接报 true。这是失败关闭：没确认过就不该写。
     */
    public boolean changedExternally() throws IOException {
        return !contentHash().equals(baselineHash);
    }

    /** 当前磁盘字节的 SHA-256（十六进制小写）；文件不存在时是空字节的哈希。 */
    public String contentHash() throws IOException {
        return sha256(readBytesOrEmpty());
    }

    /**
     * 把磁盘当前内容接受为新基线（用户选择「重新载入」之后调用）。
     *
     * <p>这里只记录字节哈希、不取内容也不解码：内容由 {@link #read()} 提供，
     * 严格 UTF-8 解码也在那里把关，两件事各自只做一次。
     */
    public void reload() throws IOException {
        baselineHash = sha256(readBytesOrEmpty());
    }

    /** 文件不存在时返回空字节数组，而不是抛异常——「不存在」在本类里是一种正常状态。 */
    private byte[] readBytesOrEmpty() throws IOException {
        return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须内置 SHA-256", e);
        }
    }
}
