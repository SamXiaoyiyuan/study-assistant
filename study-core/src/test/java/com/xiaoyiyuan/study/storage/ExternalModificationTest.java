package com.xiaoyiyuan.study.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalModificationTest {

    private static FileStore storeAt(Path dir) throws IOException {
        Path f = dir.resolve("plan.md");
        if (!Files.exists(f)) {
            Files.writeString(f, "# 原始\n");
        }
        return new FileStore(f, new BackupRotation(dir.resolve(".backup"), 10));
    }

    @Test
    void saveWithNoExternalChange_succeedsAndBacksUp(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read(); // 记录基线

        assertEquals(SaveOutcome.SAVED, store.save("# 改过了\n", false));
        assertEquals("# 改过了\n", Files.readString(dir.resolve("plan.md")));
        assertEquals(1, new BackupRotation(dir.resolve(".backup"), 10).list(dir.resolve("plan.md")).size(),
                "保存前必须已备份");
    }

    @Test
    void externalChange_isDetected_andNotSilentlyOverwritten(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read();

        Files.writeString(dir.resolve("plan.md"), "# 被别人改了\n"); // 模拟 AI 助手改动

        assertTrue(store.changedExternally());
        assertEquals(SaveOutcome.EXTERNAL_CHANGE_DETECTED, store.save("# App 想写的内容\n", false));
        assertEquals("# 被别人改了\n", Files.readString(dir.resolve("plan.md")),
                "检测到外部修改时，绝不能写下去");
    }

    @Test
    void forceOverwrites_despiteExternalChange(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read();
        Files.writeString(dir.resolve("plan.md"), "# 被别人改了\n");

        assertEquals(SaveOutcome.SAVED, store.save("# 用户确认覆盖\n", true));
        assertEquals("# 用户确认覆盖\n", Files.readString(dir.resolve("plan.md")));
    }

    @Test
    void reload_acceptsExternalContentAsNewBaseline(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read();
        Files.writeString(dir.resolve("plan.md"), "# 外部内容\n");

        store.reload();

        assertFalse(store.changedExternally(), "重新载入后不应再报外部修改");
        assertEquals(SaveOutcome.SAVED, store.save("# 继续工作\n", false));
    }

    @Test
    void contentHash_isStableForSameBytes(@TempDir Path dir) throws IOException {
        FileStore a = storeAt(dir);
        String h1 = a.contentHash();
        String h2 = storeAt(dir).contentHash();
        assertEquals(h1, h2, "同内容必须同哈希");
    }

    // ── 以下三个用例是 brief 之外补的证据，用来钉住 brief 只写在散文字里的两条顺序契约。 ──

    /**
     * 证明顺序是「先备份、后写入」而不是反过来。
     *
     * <p>brief 的用例只数备份份数，写反顺序（先写再备份）时份数同样是 1，抓不住；
     * 再加上「备份内容 = 写入前的内容」这一条，写反就必挂：备份里会变成新内容。
     */
    @Test
    void backupIsTakenBeforeWrite_notAfter(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read();

        assertEquals(SaveOutcome.SAVED, store.save("# 新内容\n", false));

        List<Path> backups = new BackupRotation(dir.resolve(".backup"), 10).list(dir.resolve("plan.md"));
        assertEquals(1, backups.size(), "保存必须留下恰好一份备份");
        assertEquals("# 原始\n", Files.readString(backups.get(0)), "备份必须是写入前的内容");
    }

    /**
     * 把「一个字节都不写」钉到字节层，并证明外部修改检查发生在备份<b>之前</b>。
     *
     * <p>只断言返回了 {@code EXTERNAL_CHANGE_DETECTED} 是不够的——完全可能「先备份、再检测、再返回」，
     * 那样虽然没覆盖用户的新内容，却已经产生了一份多余的备份（也有可能是写了一半才回滚）。
     * 这里断言原始字节逐字节不变、且备份数为 0：把「检查 → 备份 → 写」这个顺序变成可观测的。
     */
    @Test
    void externalChange_leavesBytesAndBackupsUntouched(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("plan.md");
        FileStore store = storeAt(dir);
        store.read();
        Files.writeString(f, "# 被别人改了\n"); // 模拟 AI 助手改动
        byte[] before = Files.readAllBytes(f);

        assertEquals(SaveOutcome.EXTERNAL_CHANGE_DETECTED, store.save("# App 想写的内容\n", false));

        assertArrayEquals(before, Files.readAllBytes(f), "检测到外部修改时一个字节都不能写");
        assertEquals(0, new BackupRotation(dir.resolve(".backup"), 10).list(f).size(),
                "外部修改检查必须在备份之前，否则会白建一份备份");
    }

    /**
     * 端到端证明决策 5：文件不存在不是错误，且「新建文件」路径能走通。
     *
     * <p>这条路径要求 {@code save} 在源文件不存在时<b>跳过</b>备份——{@link BackupRotation#backup}
     * 对不存在的文件是抛异常的，磁盘上本来也没有内容需要保护。
     */
    @Test
    void missingFile_isNotAnError_andCanBeCreated(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("new.md");
        FileStore store = new FileStore(f, new BackupRotation(dir.resolve(".backup"), 10));

        assertEquals("", store.read(), "文件不存在时 read() 返回空串，而不是抛错");
        assertEquals(SaveOutcome.SAVED, store.save("# 新文件\n", false), "新建文件路径必须走得通");
        assertEquals("# 新文件\n", Files.readString(f));
        assertEquals(0, new BackupRotation(dir.resolve(".backup"), 10).list(f).size(),
                "文件原本不存在，没有任何内容需要备份");
    }

    /**
     * 目标文件的<b>目录</b>也还不存在时，{@code save} 必须自己把目录建出来。
     *
     * <p>这是「第一次保存」在磁盘上的真实形态：用户刚把学习目录指到一个空文件夹，那里既没有
     * {@code 学习与生活计划.md}，也没有它可能被放在其中的子目录。少了这一层，第一次保存就会在
     * 最外层炸 {@code NoSuchFileException}——而 {@link TextFileCodec#write} 是全项目唯一的写入点，
     * 所以这条保证要在这里（而不是各调用方）钉住。
     */
    @Test
    void missingParentDirectories_areCreatedOnSave(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("子目录").resolve("更深一层").resolve("计划.md");
        assertFalse(Files.exists(f.getParent()), "前置条件：父目录必须不存在");
        FileStore store = new FileStore(f, new BackupRotation(dir.resolve(".backup"), 10));

        assertEquals(SaveOutcome.SAVED, store.save("# 第一次保存\n", false), "父目录缺失不得让保存失败");
        assertTrue(Files.isRegularFile(f), "保存必须把文件真的建出来");
        assertEquals("# 第一次保存\n", Files.readString(f, StandardCharsets.UTF_8));
    }
}
