package com.xiaoyiyuan.study.ui.service;

import com.xiaoyiyuan.study.storage.BackupRotation;
import com.xiaoyiyuan.study.storage.FileStore;
import com.xiaoyiyuan.study.storage.SaveOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 一份文本文件在界面侧的「打开着的那一份」：内容、脏判定与保存决策都在这里，且只在这里。
 *
 * <h2>唯一入口是 {@link #open}</h2>
 * 构造器是 {@code private}，且它<b>不读盘</b>；真正读盘的是 {@link #open}。这不是形式主义：
 * 「{@code read()} 先于 {@code save()}」这条安全性质，其落点正是「任何 save 之前，都先在一个
 * 成功返回的 {@code read()} 里确认过文件内容」。把这一步藏在 {@code open} 里，页面就不可能
 * 绕过它——因为除了 {@code open}，没有任何路径能造出一个 {@code DocumentSession}。
 *
 * <h2>每份文件全局只有一个 session</h2>
 * 由 {@code AppShell} 持有并注入各页面（见全局约束）。两个页面各持一份内存副本时，
 * 第二个保存的人会静默覆盖第一个——{@link #save} 的基线比对会把这种情况变成
 * {@link SaveResult#BLOCKED_EXTERNAL_CHANGE}，而不是一次安静的覆盖。
 *
 * <h2>save 只有一条落盘路径</h2>
 * 「比对 → 决策 → 调 {@code FileStore}」写成一条直下的分支链：<b>全类只有一处</b>
 * {@code store.save(...)} 调用。任何第二条能落到写盘的分支，都会让「外部改动一个字节都不写」
 * 这条承诺出现例外，而例外迟早会被人踩到。
 *
 * <h2>界面层不许自己维护第二套脏标记</h2>
 * 脏与否一律由「当前文本 vs 基线文本」判定（{@link #isDirty()}）。{@code EmotionLog} 没有
 * {@code dirty()}，且对被编辑过的条目重新生成时会规范化字段顺序——若界面各自记一份「我改过」，
 * 两套判断迟早分岔，而分岔的表现是「文件里的字节与界面显示的不一样」。
 */
public final class DocumentSession {

    /** 这份文件。 */
    private final Path file;

    /** 底层读写与外部改动防护；本类不自己碰文件系统。 */
    private final FileStore store;

    /** 我们最后一次确认过的文本（成功 {@link #open} / {@link #save} / {@link #reload} 时更新）。 */
    private String originalText;

    /** 界面上正在编辑的文本。 */
    private String currentText;

    /** 上一次成功保存的时刻；从未保存过为 {@code null}。 */
    private LocalDateTime lastSaved;

    /**
     * 构造器里什么都不做（不读盘、不建目录）。真正读盘的是 {@link #open}。
     */
    private DocumentSession(Path file, FileStore store, String text) {
        this.file = file;
        this.store = store;
        this.originalText = text;
        this.currentText = text;
        this.lastSaved = null;
    }

    /**
     * 打开一份文件：<b>先读盘建基线</b>，再把内容交给界面。
     *
     * <p>这里是「{@code read()} 先于 {@code save()}」的落点：{@code store.read()} 先成功返回，
     * 之后才构造出对象。所以任何能调到 {@link #save} 的实例，其创建路径上都已经成功读过一次盘。
     *
     * @throws IOException      读盘本身出错（权限、被占用……）
     * @throws com.xiaoyiyuan.study.storage.NotUtf8Exception 文件不是合法 UTF-8：
     *         <b>不写任何内容</b>、不做替换、不猜编码，直接上抛（消息带路径与非法字节偏移）
     */
    public static DocumentSession open(Path file, BackupRotation rotation) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(rotation, "rotation");
        FileStore store = new FileStore(file, rotation);
        // read() 先于任何 save()：建立基线，并把非法 UTF-8 挡在门外
        String text = store.read();
        return new DocumentSession(file, store, text);
    }

    /** 本 session 对应的文件。 */
    public Path file() {
        return file;
    }

    /** 最后一次确认过的文本（打开时读到的那份，或上次成功写下去的那份）。 */
    public String originalText() {
        return originalText;
    }

    /** 界面上正在编辑的文本。 */
    public String currentText() {
        return currentText;
    }

    /** 当前文本是否不同于基线（有没有未保存的改动）。 */
    public boolean isDirty() {
        return !currentText.equals(originalText);
    }

    /** 上次成功保存的时刻；从未保存过返回 {@code null}。 */
    public LocalDateTime lastSaved() {
        return lastSaved;
    }

    /**
     * 基线字节的 SHA-256（十六进制小写）。
     *
     * <p>等于「我们最后一次确认过的那串字节」的哈希。因为读写全程不做规范化、BOM 与 CRLF 都
     * 原样保留在文本里（{@code TextFileCodec} 的职责），对文本重新按 UTF-8 编码就得到原来那串
     * 字节，所以这里可以直接对 {@link #originalText} 求哈希。
     */
    public String baselineHash() {
        return sha256(originalText.getBytes(StandardCharsets.UTF_8));
    }

    /** 界面把编辑结果交回来。这里只改内存，不碰磁盘。 */
    public void update(String newText) {
        this.currentText = Objects.requireNonNull(newText, "newText");
    }

    /**
     * 保存内存里的内容，返回这次请求的结局。
     *
     * <p>决策顺序（下面就是全部逻辑，只有一处 {@code store.save} 调用）：
     * <ol>
     *   <li>磁盘内容与基线不一致（别人动过）：
     *     <ul>
     *       <li>{@link ExternalChangePolicy#RELOAD}：重新读盘接受那一份为新基线，
     *           <b>不写一个字节</b>，返回 {@link SaveResult#BLOCKED_EXTERNAL_CHANGE}；</li>
     *       <li>{@link ExternalChangePolicy#FORCE_OVERWRITE}：用户已确认覆盖，备份后写下去；</li>
     *       <li>其余（{@link ExternalChangePolicy#ASK_USER}）：<b>不写一个字节</b>，
     *           返回 {@link SaveResult#BLOCKED_EXTERNAL_CHANGE}。</li>
     *     </ul>
     *   </li>
     *   <li>没有外部改动，且内容与基线相同：返回 {@link SaveResult#UNCHANGED}，
     *       根本不调 {@code store.save}——否则会在备份目录里堆一份无意义快照。</li>
     *   <li>只剩「有真实改动且没有外部冲突」一种情况：备份后写下去。</li>
     * </ol>
     *
     * @throws IOException 写盘本身出错：这一次没保存成功，调用方必须让用户看到
     */
    public SaveResult save(ExternalChangePolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");

        boolean changedOnDisk = store.changedExternally();
        if (changedOnDisk) {
            if (policy == ExternalChangePolicy.RELOAD) {
                // 「重新载入」不写盘：只把磁盘那一份接受为新基线，编辑由调用方自行丢弃
                store.reload();
                return SaveResult.BLOCKED_EXTERNAL_CHANGE;
            }
            if (policy != ExternalChangePolicy.FORCE_OVERWRITE) {
                // ASK_USER：把「重新载入 / 覆盖」的选择交回界面，一个字节都不写
                return SaveResult.BLOCKED_EXTERNAL_CHANGE;
            }
        } else if (currentText.equals(originalText)) {
            // 没有任何改动：不调 store.save，避免在 .backup 里堆无意义快照
            return SaveResult.UNCHANGED;
        }

        // —— 到这里才可能写盘，全类唯一的落盘点 ——
        // force 取 changedOnDisk：只有「检测到外部改动且已授权覆盖」时才跳过 FileStore 的比对。
        SaveOutcome outcome = store.save(currentText, changedOnDisk);
        if (outcome == SaveOutcome.EXTERNAL_CHANGE_DETECTED) {
            // 只可能是「比对」与「写入」之间又被人改了（时序竞争）：保守拒绝，一个字节都没写
            return SaveResult.BLOCKED_EXTERNAL_CHANGE;
        }
        originalText = currentText;
        lastSaved = LocalDateTime.now();
        return SaveResult.SAVED;
    }

    /**
     * 丢弃内存编辑，重新读盘，返回新的文本。
     *
     * <p>{@code store.read()} 会重新建立基线并严格校验 UTF-8；非法 UTF-8 直接上抛，
     * 内存与磁盘两边都不动。
     *
     * @throws IOException 读盘出错或文件不是合法 UTF-8
     */
    public String reload() throws IOException {
        String text = store.read();
        originalText = text;
        currentText = text;
        return text;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须内置 SHA-256", e);
        }
    }
}
