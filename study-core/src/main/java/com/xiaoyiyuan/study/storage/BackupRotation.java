package com.xiaoyiyuan.study.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 备份轮转：每次保存前把文件复制到 {@code .backup\}，每个文件只保留最近 {@code keep} 份。
 *
 * <p>这是写入路径的「退路」：用户的档案由本应用、AI 助手、用户自己的编辑器三方共同编辑，
 * 一旦本应用写错了内容，备份是唯一能救回来的东西。所以命名与保留份数是<b>规格</b>，
 * 不是实现细节——用户会按名字去找备份。
 */
public final class BackupRotation {

    /**
     * 备份文件名里的时间戳格式，规格钉死为 {@code yyyyMMdd-HHmmss}（定宽，秒精度）。
     *
     * <p>定宽这一点是有用的性质：同一文件的备份名前缀完全相同，于是「按文件名倒序」
     * 就等价于「按时间从新到旧」，排序不必再解析时间。
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final String SUFFIX = ".bak";

    private final Path backupDir;
    private final int keep;

    /**
     * @param backupDir 备份目录（可以还不存在，{@link #backup} 会创建）
     * @param keep      每个文件保留的备份份数，超出部分删最旧
     */
    public BackupRotation(Path backupDir, int keep) {
        this.backupDir = backupDir;
        this.keep = keep;
    }

    /**
     * 备份 {@code source}，返回备份文件路径，并按 {@code keep} 轮转删除最旧的几份。
     *
     * <p><b>同一秒内重复备份会撞名</b>：文件名精度是秒，两次调用落在同一秒就得到同一个目标名。
     * 这里用 {@link StandardCopyOption#REPLACE_EXISTING} 接受覆盖——刻意<b>不</b>加
     * {@code AtomicInteger} 计数器之类的后缀，因为规格钉死了命名格式，
     * 「名字可预期」比「绝不撞名」更重要。
     *
     * <p>源文件不存在时由 {@code Files.copy} 抛 {@link java.nio.file.NoSuchFileException}：
     * 备份不出来的事实必须暴露给调用方（{@link FileStore#save} 会因此放弃写入），
     * 绝不能吞掉异常后返回一个并不存在的备份路径。
     */
    public Path backup(Path source) throws IOException {
        Files.createDirectories(backupDir);
        Path target = backupDir.resolve(source.getFileName() + "." + LocalDateTime.now().format(STAMP) + SUFFIX);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        rotate(source);
        return target;
    }

    /**
     * 列出<b>属于 {@code source} 这一个文件</b>的备份，按时间从新到旧。
     *
     * <p>筛选条件严格限定为「以 {@code <原文件名>.} 开头且以 {@code .bak} 结尾」，
     * 否则同一备份目录里别的文件的备份会被算进来，轮转时就会误删别人的备份。
     *
     * <p>备份目录还不存在时返回空列表而不是抛异常：从未保存过的文件本来就没有备份，
     * 「还没有备份」不是错误状态，界面要能直接显示成空列表。
     */
    public List<Path> list(Path source) throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        String prefix = source.getFileName() + ".";
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> entries = Files.list(backupDir)) {
            entries.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(SUFFIX)) {
                    matches.add(p);
                }
            });
        }
        // 时间戳定宽 + 前缀相同 ⇒ 文件名倒序就是时间从新到旧（见 STAMP 的说明）
        matches.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return matches;
    }

    /** 从第 {@code keep} 份（0 基）开始删，也就是只留最新的 {@code keep} 份。 */
    private void rotate(Path source) throws IOException {
        List<Path> all = list(source);
        for (int i = keep; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i));
        }
    }
}
