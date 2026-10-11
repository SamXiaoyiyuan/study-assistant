package com.xiaoyiyuan.study.migrate;

import com.xiaoyiyuan.study.markdown.TableBlock;
import com.xiaoyiyuan.study.plan.WeekCalendar;
import com.xiaoyiyuan.study.storage.BackupRotation;
import com.xiaoyiyuan.study.storage.TextFile;
import com.xiaoyiyuan.study.storage.TextFileCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次性迁移：旧档案 {@code 408-进度.md} → 新计划 {@code 学习与生活计划.md}（spec §4.6）。
 *
 * <h2>两个方法的分工（这是本类最重要的结构）</h2>
 * <ul>
 *   <li>{@link #plan(Path)}：<b>只读</b>。读源文件、在内存里算出目标文本，不写、不备份、不归档、
 *       不建目录。<b>可以反复调用</b>，调用它的代价里没有任何副作用。</li>
 *   <li>{@link #apply(MigrationPlan, Path)}：<b>唯一会写文件的方法</b>，顺序固定：
 *       <ol>
 *         <li>目标已存在且非空 → 抛 {@link TargetExistsException}（<b>在任何写入之前</b>）；</li>
 *         <li>备份源文件到 {@code .backup\}&lt;原文件名&gt;.&lt;时间戳&gt;.bak；</li>
 *         <li>归档源文件到 {@code .archive\&lt;原文件名&gt;}（逐字节副本，不可变）；</li>
 *         <li>写目标文件；</li>
 *         <li>返回三个路径。</li>
 *       </ol>
 *       全程<b>不修改、不删除、不重命名源文件</b>（spec §4.6 第 5 条：「原文件保留不动」）。</li>
 * </ul>
 *
 * <h2>为什么用字符串装配，而不是 {@code PlanDocument}</h2>
 * {@code PlanDocument} 只建模五张结构表；而目标文件里 §五 / §七 / §八 / §九 / §十 与文末 HTML 注释
 * 都是原样块，模型承载不了它们。若「解析成模型再 render」，那些内容会被直接丢掉——那正是本任务
 * 存在的理由。所以这里只按<b>行</b>装配：能原样搬的一行不改地搬（§六 周记录整段搬进 §九，一个字符
 * 都不动），只有真正需要改结构的几张表才重新生成。
 *
 * <h2>方案自带源路径：apply 校验的是「路径 + 内容」，不是「谁产出的」</h2>
 * {@link MigrationPlan} 是纯数据（它要能脱离文件系统被展示与确认），但它<b>自带源文件路径</b>：
 * apply 要备份与归档，必须知道源文件在哪，而这个答案属于方案本身，不属于碰巧调用了 apply 的那个实例。
 *
 * <p>因此 apply 的闸门是<b>证据</b>而不是<b>身份</b>：源文件此刻是否仍逐字节等于方案所依据的那份内容。
 * 用对象身份做闸门（早期实现）有两个坏处：① brief 公开的用法
 * {@code new Migrator(dir).apply(plan, target)} 直接抛 {@link IllegalArgumentException}，而它本该只是
 * 正常迁移一次——这是留给 Task 10 的地雷；② 身份闸门排在目标检查之前，会让「外来方案 + 目标非空」
 * 抛错类型变成 {@code IllegalArgumentException} 而不是 {@link TargetExistsException}，
 * 「绝不静默覆盖」这条规格就被一个无关的检查遮住了。内容闸门两条都解掉，且不放松任何安全性质：
 * 归档写的字节、目标文本、备份的源文件仍然是同一份内容。
 */
public final class Migrator {

    /** 备份目录名（全局约束钉死）；备份的命名与保留份数由 {@link BackupRotation} 负责。 */
    private static final String BACKUP_DIR = ".backup";

    /** 不可变归档目录名（全局约束钉死）。 */
    private static final String ARCHIVE_DIR = ".archive";

    /** 每个文件保留的备份份数（spec §4.5：最近 10 份，超出删最旧）。 */
    private static final int BACKUP_KEEP = 10;

    /**
     * §九 的标题，必须与 {@code WeekRecordAppender.WEEKLY_SECTION_HEADING} <b>逐字一致</b>。
     *
     * <p>那个常量是 private，所以这里只能重复一次字符串——重复的风险由测试钉住：
     * {@code MigrationFidelityTest.migratedTarget_stillAcceptsAnAppendedWeek} 会对迁移产物调用
     * {@code appendWeek}，标题只要差一个字符，App 就再也找不到周记录章节，测试当场红。
     */
    private static final String WEEKLY_HEADING = "## 九、周记录（每周追加，不覆盖）";

    /** 旧档案里参与映射的二级章节序号；其它序号（用户自己加的节）原样并入 §八。 */
    private static final List<String> LEGACY_NUMERALS = List.of("一", "二", "三", "四", "五", "六", "七");

    /** spec §5.3 钉死的本学期 6 门课；只在源 §一 没有可用的课程清单时使用。 */
    private static final List<String> DEFAULT_COURSES = List.of(
            "数据库", "Java", "线性代数", "物理学", "电路分析基础", "马克思主义基本原理");

    /** 源 §二 底线条目统一挂到的目标（源 §二 就是「考研不断线」的底线表）。 */
    private static final String BASELINE_GOAL = "考研（408）";

    /** 源 §四 里「学校课程处理原则」一段的起始标记；它之前的部分属考研，之后的部分留在 §四 附近。 */
    private static final String SCHOOL_RULES_MARKER = "**学校课程的处理原则";

    /** 分块标号（源 §五 用它们把「本周」切成前导 / ① 课程 / ② 考研轻量 / ③ 生活保障）。 */
    private static final String CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩";

    private final Path studyDir;

    /**
     * @param studyDir 用户的 study 目录：备份落在 {@code studyDir\.backup\}，归档落在
     *                 {@code studyDir\.archive\}。构造器<b>不碰盘</b>（plan 只读的语义从这里开始）。
     */
    public Migrator(Path studyDir) {
        this.studyDir = Objects.requireNonNull(studyDir, "studyDir");
    }

    // =====================================================================
    // I/O：plan 只读，apply 是唯一的写入路径
    // =====================================================================

    /**
     * 读源文件并算出目标文本，<b>不产生任何写入</b>。
     *
     * <p>本方法刻意不创建目录、不写备份、不碰目标文件：spec §4.6 要求「先展示 diff、用户确认后
     * 才写入」，而「确认」这一步可能被用户取消、可能停留很久、界面也可能刷新多次。只要 plan 是无副
     * 作用的，「取消了也没关系」就是构造上成立的，而不是靠调用方自觉。
     *
     * <p>严格 UTF-8 由 {@link TextFileCodec#read} 保证：非法 UTF-8 一律抛
     * {@code NotUtf8Exception}，不会返回被 U+FFFD 替换过的字符串（那会让迁移写出一份坏档案）。
     *
     * @throws IOException 读失败、或文件不是合法 UTF-8
     */
    public MigrationPlan plan(Path legacyFile) throws IOException {
        Objects.requireNonNull(legacyFile, "legacyFile");
        TextFile file = TextFileCodec.read(legacyFile);
        byte[] bytes = Files.readAllBytes(legacyFile);
        // 两次读取之间文件若被别的程序改过，解码结果与字节就不是同一份内容——那种情况下
        // 「目标文本按旧内容算、归档写新字节」会自相矛盾。合法 UTF-8 解码后再编码必然得到原字节，
        // 所以这条比对就是「两次读的是同一份内容」的证明。
        if (!Arrays.equals(file.content().getBytes(StandardCharsets.UTF_8), bytes)) {
            throw new IOException("读取源文件时内容发生了变化（两次读取结果不一致），请重试：" + legacyFile);
        }
        List<String> notes = new ArrayList<>();
        String targetText = buildTarget(file, notes);

        // 源路径进方案：apply 靠它知道去备份/归档谁，也靠它复核「源文件还是不是这一份」。
        // 本方法这里仍只读——把路径记进返回值不等于碰盘。
        return new MigrationPlan(legacyFile, file.content(), targetText, notes);
    }

    /**
     * 落盘：先拒绝（目标 → 源内容 → 归档冲突，三步全部<b>只读</b>）、再备份、再归档、最后写目标。
     *
     * <p><b>顺序是规格，不是实现细节</b>（spec §4.6 第 1–5 条）：
     * <ol>
     *   <li>目标存在且非空 → 立刻抛 {@link TargetExistsException}。「绝不静默覆盖」的检查必须
     *       排在<b>第一步</b>：放到备份之后，异常照样会抛，但用户的目录里已经多出一份备份，
     *       而备份本身会轮转、会删掉最旧的一份——一次「什么都没做」的拒绝不该有这个后果。
     *       它排第一还有一个直接后果：{@code new Migrator(dir).apply(plan, target)} 这种
     *       「方案不是本实例产的」用法，在目标已存在时抛的仍然是 {@code TargetExistsException}，
     *       而不是被别人抢先抛出的参数错误（brief 的公开用法不能被无关检查遮住）。</li>
     *   <li>源文件内容与方案不符 → 拒绝。本项目里同一个档案由 App、AI 助手、用户自己的
     *       编辑器三方共同改，照着过期方案写目标、却把新内容归档掉，是把用户的最新内容
     *       留在了一个没人会去看的地方。</li>
     *   <li>归档已存在且内容不同 → 拒绝。它和上一条共用同一条理由：<b>拒绝的代价必须是零</b>，
     *       所以这条也必须是只读的、且排在备份之前。</li>
     *   <li>备份 → 归档 → 写目标。归档是<b>不可变</b>历史快照，内容不同时拒绝覆盖。</li>
     * </ol>
     *
     * @throws TargetExistsException 目标已存在且非空（或存在但不是普通文件）
     * @throws IOException 源文件内容与方案不符、归档冲突、或读写失败
     */
    public MigrationResult apply(MigrationPlan plan, Path targetFile) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(targetFile, "targetFile");
        // 源路径来自方案本身。apply 因此不依赖任何实例现场信息，也就没有「哪个实例产的」这回事。
        Path source = plan.sourceFile();

        // ① 先检查目标：已存在且非空就到此为止，此时磁盘上什么都没多出来。
        requireAbsentTarget(targetFile);

        // ② 源文件必须仍是这份方案所依据的那一份（字节级）。方案是纯数据，判据只能从方案自己来：
        //    sourceText 按 UTF-8 编码回去必然等于 plan() 当时读到的原始字节（plan() 里已核对过
        //    这一点，BOM 与 CRLF 都在其中）——所以这条比对就是「现在是同一份内容」的证明。
        byte[] sourceBytes = plan.sourceText().getBytes(StandardCharsets.UTF_8);
        byte[] currentBytes = Files.readAllBytes(source);
        if (!Arrays.equals(currentBytes, sourceBytes)) {
            throw new IOException("源文件已不是这份方案所依据的那一份（内容被改过），拒绝按过期方案迁移："
                    + source + "（目标文件未写入，也没有产生备份与归档；请重新 plan() 并确认新的目标文本）");
        }

        // ③ 归档冲突的只读预检查，排在备份之前：见方法上的说明与上面的顺序表第 3 条。
        //    此处还没有备份（null），拒绝消息里那句「也没有产生备份」在这个调用点是真的。
        requireArchiveCoherent(source, sourceBytes, null);

        // ④ 备份（命名、保留 10 份由 BackupRotation 按规格执行）。
        Path backup = new BackupRotation(studyDir.resolve(BACKUP_DIR), BACKUP_KEEP).backup(source);

        // ⑤ 归档：写的是 ② 里核对过的那份字节，而不是「再去读一次文件」——后者给别的程序留了
        //    一个把两边改得不一致的窗口。
        Path archive = archiveSource(source, sourceBytes, backup);

        // ⑥ 写目标文件。目标文本固定 LF、无 BOM（新建文件一律如此，见全局约束）。
        TextFileCodec.write(targetFile, plan.targetText());

        return new MigrationResult(targetFile, backup, archive);
    }

    /**
     * 目标必须是「不存在」，或「存在但是空文件」。
     *
     * <p>空文件允许覆盖是刻意的：那是「新建后还没写过」的形态，覆盖它不损失任何内容。
     * 非空则一律拒绝——App 无从知道那份内容是不是用户手写的。
     */
    private static void requireAbsentTarget(Path targetFile) throws IOException {
        if (!Files.exists(targetFile)) {
            return;
        }
        if (!Files.isRegularFile(targetFile)) {
            throw new TargetExistsException("目标路径已存在，且不是普通文件，拒绝迁移（绝不覆盖）："
                    + targetFile + "（源文件一个字节都没动，也没有产生备份与归档）", targetFile);
        }
        if (Files.size(targetFile) > 0) {
            throw new TargetExistsException("目标文件已存在且非空，拒绝迁移（绝不静默覆盖已有内容）："
                    + targetFile + "（源文件一个字节都没动，也没有产生备份与归档）", targetFile);
        }
    }

    /** 归档路径：{@code .archive\<原文件名>}。纯路径运算，不碰盘。 */
    private Path archiveFileFor(Path source) {
        return studyDir.resolve(ARCHIVE_DIR).resolve(source.getFileName());
    }

    /**
     * <b>只读</b>：归档已存在且内容与源文件不同就拒绝。
     *
     * <p>单独抽出来，是为了让它能排在备份<b>之前</b>（见 {@link #apply} 的顺序表第 3 条）：
     * 拒绝的代价必须是零。本方法不建目录、不写文件，冲突时磁盘上连 {@code .archive\} 都不会出现。
     *
     * <p><b>消息必须按调用点的现场分岔</b>（这是本方法唯一的坑）：调用点有两个，磁盘状态不同——
     * <ul>
     *   <li>{@link #apply} 里那次排在备份<b>之前</b>：此刻确实「也没有产生备份」；</li>
     *   <li>{@link #archiveSource} 里那次排在备份<b>之后</b>：备份已经写好躺在 {@code .backup\} 里了，
     *       此时若照抄前一句，消息就<b>假称没有备份</b>——而用户读完那句话正该去 {@code .backup\}
     *       找自己的内容。所以这条路必须给出备份的真实路径。</li>
     * </ul>
     * 同一个方法、同一个判据，只有这句关于磁盘状态的描述随调用点变化；这样两个调用点的消息都为真，
     * 又不会各自维护一套会漂移的措辞。
     *
     * @param backupAlreadyMade 调用点<b>已经</b>建好的那份备份的路径；排在备份之前、
     *                          磁盘上还没有备份时传 {@code null}
     */
    private void requireArchiveCoherent(Path source, byte[] sourceBytes, Path backupAlreadyMade)
            throws IOException {
        Path archive = archiveFileFor(source);
        if (Files.exists(archive) && !Arrays.equals(Files.readAllBytes(archive), sourceBytes)) {
            String backupClause = backupAlreadyMade == null
                    ? "目标文件未写入，也没有产生备份"
                    : "目标文件未写入；备份已建立：" + backupAlreadyMade;
            throw new IOException("不可变归档已存在，且内容与当前源文件不同，拒绝迁移：" + archive
                    + "（它保存的是迁移前那一份历史快照。" + backupClause
                    + "；源文件一个字节都没动。请先自行确认并处理该归档，再重新迁移）");
        }
    }

    /**
     * 把源文件的那份字节写成不可变归档，返回归档路径。
     *
     * <p>已存在且内容相同时保持原样（用户删掉目标后重跑一次迁移是合法操作）；内容不同则拒绝：
     * 全局约束把它定义为「不可变归档」，而它正是「迁移前那份档案」的唯一快照——静默改写它，
     * 等于把用户回头核对历史的依据换掉。
     *
     * <p>这里再查一次冲突：apply 里那次是「拒绝无副作用」的前置闸门，这次是「写入前最后一瞬」的
     * 复查——两者之间隔着一次备份，别的程序有可能已经动过归档。两次判据同一个方法，不会漂移。
     *
     * <p>{@code backup} 是调用方<b>已经</b>建好的那份备份：拒绝消息要如实告诉用户它在哪
     * （见 {@link #requireArchiveCoherent} 的说明）。
     */
    private Path archiveSource(Path source, byte[] sourceBytes, Path backup) throws IOException {
        Path archive = archiveFileFor(source);
        requireArchiveCoherent(source, sourceBytes, backup);
        Files.createDirectories(studyDir.resolve(ARCHIVE_DIR));
        if (!Files.exists(archive)) {
            Files.write(archive, sourceBytes);
        }
        return archive;
    }

    // =====================================================================
    // 映射（纯内存：下面这些方法一个都不碰文件系统）
    // =====================================================================

    /**
     * 按 spec §4.6 的映射表装配目标文本。
     *
     * <p>骨架（顺序即文档顺序）：头部 → §一 我的现状 → §二 目标清单 → §三 课程 → §四 每周底线 →
     * §五 生活准则 → §六 阶段排期 → §七 本周 → §八 考研（暂缓）→ §九 周记录 → §十 待确认 → 文末注释。
     * 每个 `## ` 标题前保证一个空行（块边界只由零字符空行产生）。
     */
    private static String buildTarget(TextFile file, List<String> notes) {
        if (file.hasBom()) {
            notes.add("源文件带 UTF-8 BOM；目标文件按规范写为「UTF-8 无 BOM + LF」（源文件一个字节都没改）。");
        }
        if (file.hasCrlf()) {
            notes.add("源文件用 CRLF 换行；目标文件按规范写为 LF（源文件一个字节都没改）。");
        }
        List<String> lines = linesOf(file.content());
        if (lines.stream().anyMatch(line -> line.strip().equals("---"))) {
            notes.add("源文件里的 `---` 分隔线没有搬入目标：目标以空行分节，保证每个 `## ` 标题前都是空行"
                    + "（分隔线紧贴标题会让「上一节末尾」与「下一节标题」落进同一个原样块）。");
        }
        List<String> tailComments = new ArrayList<>();
        lines = extractTrailingComments(lines, tailComments);

        ParsedSource source = parseSource(lines, notes);
        SourceSection two = source.section("二");
        SourceSection four = source.section("四");
        SourceSection five = source.section("五");
        MixedSection mixed = splitMixed(five);
        BaselineTable baselines = baselineTable(two, notes);
        Map<String, String> profile = profileValues(source.section("一"), notes);
        List<String> courses = courseNames(profile, notes);
        SchoolRules rules = splitSchoolRules(four, notes);

        List<Section> sections = new ArrayList<>();
        sections.add(headerSection(source, profile, notes));
        sections.add(profileSection(profile, courses, notes));
        sections.add(goalsSection());
        sections.add(coursesSection(courses));
        sections.add(baselinesSection(two, baselines, rules.school(), notes));
        sections.add(lifeRulesSection(mixed));
        sections.add(phasesSection(notes));
        sections.add(thisWeekSection(mixed, baselines.table()));
        sections.add(examDeferredSection(source, profile, mixed, rules.exam(), baselines));
        sections.add(weeklyRecordsSection(source.section("六"), notes));
        sections.add(pendingSection(profile, source.section("七")));

        String rendered = render(sections, tailComments);
        return applyLostLineBackstop(rendered, file.content(), sections, tailComments, notes);
    }

    // ---- 文档头部 ----

    /** 头部：新标题 + 源头部逐行保留 + 规格要求补的「当前学期 / 最近修订 / 周界锚点」。 */
    private static Section headerSection(ParsedSource source, Map<String, String> profile, List<String> notes) {
        Section section = new Section("# 学习与生活计划");
        List<String> carried = trimBlankEdges(dropDividers(source.header()));
        // 旧标题（`# 408 考研备考进度 · 小忆元`）与分隔线不搬：标题换成了新文件的名字，
        // 分隔线见本文件顶部的格式要求。其余头部行（建档、预计初试、周界约定、当前主攻）逐行保留。
        carried.removeIf(line -> line.strip().startsWith("# "));
        section.addAll(carried);
        if (!carried.isEmpty() && !carried.get(carried.size() - 1).strip().startsWith(">")) {
            section.blank();
        }
        String semester = profile.get("年级");
        if (semester == null) {
            semester = "待填";
            notes.add("源文件 §一 没有「年级」行，头部的「当前学期」先写「待填」（可在 App 里改）。");
        }
        section.add("> 当前学期：" + semester);
        section.add("> 最近修订：" + LocalDate.now() + "（主域切换：学校课程第一，考研暂缓）");
        section.add("> 周界：周一 – 周日（第 1 周 = " + WeekCalendar.weekRangeLabel(1) + "）");
        return section;
    }

    // ---- §一 我的现状 ----

    /** §一：源 §一 里年级 / 大一学过 / 大二在学 / 可用时间 四项，换成新的项目名。 */
    private static Section profileSection(Map<String, String> profile, List<String> courses, List<String> notes) {
        Section section = new Section("## 一、我的现状");
        section.add("| 项目 | 内容 |");
        section.add("|---|---|");
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> row : profileRows(profile, courses).entrySet()) {
            String value = row.getValue();
            if (value == null || value.isEmpty()) {
                missing.add(row.getKey());
                continue;
            }
            section.add("| " + row.getKey() + " | " + value + " |");
        }
        if (!missing.isEmpty()) {
            notes.add("源文件 §一 里缺这几行：" + String.join("、", missing)
                    + "，§一 我的现状 未包含它们（不凭空编内容）。");
        }
        return section;
    }

    /** §一 的四行：新的项目名 → 取值（取值来自源 §一；「本学期课程」没有来源时用课程表那份清单）。 */
    private static Map<String, String> profileRows(Map<String, String> profile, List<String> courses) {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("年级 / 学期", profile.get("年级"));
        String enrolled = profile.get("大二在学");
        rows.put("本学期课程", enrolled == null || enrolled.isBlank() ? String.join("、", courses) : enrolled);
        rows.put("大一学过", profile.get("大一学过"));
        rows.put("可用时间", profile.get("可用时间"));
        return rows;
    }

    // ---- §二 目标清单 ----

    /**
     * §二：spec §5.2 钉死的初始三行（本学期学校课程 / 生活与身心 / 考研（408））。
     *
     * <p>「说明」一列的文字取自源档案 §二 的优先级清单口径（上课、作业、实验、期末 / 睡眠、吃饭、
     * 运动、社交 / 不排时间、将来可能启用），而那份清单本身在 §四 里逐行原样保留，因此这一步
     * 只是换了一种结构表达，没有丢字。
     */
    private static Section goalsSection() {
        Section section = new Section("## 二、目标清单");
        section.add("| 目标 | 状态 | 优先级 | 时间窗 | 说明 |");
        section.add("|---|---|---|---|---|");
        section.add("| 本学期学校课程 | 进行中 | 1 | 2026.9–2027.1 | 现阶段主域：上课、作业、实验、期末 |");
        section.add("| 生活与身心 | 进行中 | 2 | 长期 | 睡眠、吃饭、运动、社交；每天留出，不用于补学习 |");
        section.add("| 考研（408） | 暂缓 | — | 大三再定 | 现在不排时间，将来可能启用；历史原样保留 |");
        return section;
    }

    // ---- §三 课程（本学期） ----

    /** §三：按 §一 的课程清单生成课程表（spec §5.3 的单源约定在迁移这一步先落地）。 */
    private static Section coursesSection(List<String> courses) {
        Section section = new Section("## 三、课程（本学期）");
        section.add("| 课程 | 本周任务 | 截止 | 状态 |");
        section.add("|---|---|---|---|");
        for (String course : courses) {
            section.add("| " + course + " |  |  | 待回报 |");
        }
        return section;
    }

    // ---- §四 每周底线 ----

    /**
     * §四：源 §二 的正文<b>就地</b>装配——说明文字留在原位，只有那张底线表换成加了「目标」列的新表。
     *
     * <p>为什么不「先写表再抄正文」：原文里「**每周底线（只看周，不看天）：**」紧跟在表前，
     * 顺序一乱，用户读到的就是另一篇东西。位置变化在 P3 里是允许的，但没有理由去白换。
     */
    private static Section baselinesSection(SourceSection two, BaselineTable baselines,
                                            List<String> schoolRules, List<String> notes) {
        Section section = new Section("## 四、每周底线");
        List<String> body = two == null ? List.of() : trimBlankEdges(dropDividers(two.body()));
        section.addAll(replaceFirstTable(body, baselines.table(), notes));
        if (!schoolRules.isEmpty()) {
            section.blank();
            section.addAll(schoolRules);
        }
        if (!baselines.unplacedRows().isEmpty()) {
            section.blank();
            section.add("**源 §二 底线表里列数与表头不符的行（原样保留，未塞进上面的表）**");
            section.blank();
            section.addAll(baselines.unplacedRows());
        }
        return section;
    }

    /**
     * 源 §二 的底线表 → 新表：加「目标」列，数据行原样搬。
     *
     * <p>列数不符的数据行<b>不塞进新表</b>：一行多一列会让 {@code MarkdownParser} 把整张表降级成
     * 原样文本，于是 {@code PlanDocument} 再也定位不到 §四（App 打开计划就失败）。所以这种行被挪到
     * 表后原样保留——宁可它不好看，也不能让它把整张表弄坏，更不能丢掉它。
     */
    private static BaselineTable baselineTable(SourceSection two, List<String> notes) {
        List<String> table = new ArrayList<>(List.of(
                "| 目标 | 项 | 量 | 怎么安排 | 状态 |",
                "|---|---|---|---|---|"));
        List<String> unplaced = new ArrayList<>();
        if (two == null) {
            notes.add("源文件里找不到「## 二、优先级与每周底线」这一节，§四 每周底线 没有可承接的条目"
                    + "（保留表头，便于之后填写）。");
            return new BaselineTable(table, unplaced);
        }
        List<String> body = trimBlankEdges(dropDividers(two.body()));
        int[] run = firstTableRun(body);
        if (run == null) {
            notes.add("源文件 §二 里找不到底线的表格（表头 + 分隔行 + 数据行），§四 每周底线 保留为表头。");
            return new BaselineTable(table, unplaced);
        }
        for (int i = run[0] + 2; i < run[1]; i++) {
            List<String> cells = TableBlock.splitCells(body.get(i));
            if (cells.size() != 4) {
                unplaced.add(body.get(i));
                continue;
            }
            table.add("| " + BASELINE_GOAL + " | " + cells.get(0) + " | " + cells.get(1)
                    + " | " + cells.get(2) + " | " + cells.get(3) + " |");
        }
        if (!unplaced.isEmpty()) {
            notes.add("源文件 §二 的底线表里有 " + unplaced.size() + " 行列数与表头（4 列）不符，"
                    + "没有塞进新表（会让整张表降级成原样文本），已原样保留在 §四 表后（一行都没丢）。");
        }
        return new BaselineTable(table, unplaced);
    }

    /**
     * 把源 §四 切成两半（spec §4.6 把它拆到两个去处）。
     *
     * <p>分界是「学校课程的处理原则」那一行：它之前的考研清单（408 / 数学 / 四级 / 政治）整段进 §八，
     * 之后的课程处理原则留在 §四 附近（它讲的是课程主域，不该被塞进考研收纳区）。
     * 没有这个标记时整节进 §八——宁可整段保守地留在收纳区，也不去猜哪一半是考研。
     */
    private static SchoolRules splitSchoolRules(SourceSection four, List<String> notes) {
        if (four == null) {
            return new SchoolRules(List.of(), List.of());
        }
        List<String> body = trimBlankEdges(dropDividers(four.body()));
        int at = indexOfPrefix(body, SCHOOL_RULES_MARKER);
        if (at < 0) {
            if (!body.isEmpty()) {
                notes.add("源 §四 里没有「学校课程的处理原则」一段，整节原样进了 §八。");
            }
            return new SchoolRules(body, List.of());
        }
        return new SchoolRules(trimBlankEdges(body.subList(0, at)),
                trimBlankEdges(body.subList(at, body.size())));
    }

    // ---- §五 生活准则 ----

    /** §五：源 §五 ③ 生活保障那一段原样搬（spec §4.6：§五 ③ → §五 生活准则）。 */
    private static Section lifeRulesSection(MixedSection mixed) {
        Section section = new Section("## 五、生活准则");
        if (mixed.life().isEmpty()) {
            // 空节不显示成空白：全局约束规定无数据文案是「待回报」，避免制造「缺了什么」的暗示。
            section.add("- 待回报");
            section.blank();
            return section;
        }
        section.addAll(mixed.life());
        return section;
    }

    // ---- §六 阶段排期 ----

    /**
     * §六：迁移后是<b>空表</b>（只有表头 + 分隔行）。
     *
     * <p>原 §三 的阶段 A–E 全是考研排期，按映射整体移入 §八；这里留一张空表而不是删掉这一节：
     * ① spec §5.5 要求「无进行中阶段时输出提示句而不显示为空白或错误」；
     * ② {@code PlanDocument} 认「表头 + 分隔行」的空表并就地升级，之后 App 增删阶段才有落点。
     * 注意这里<b>不能</b>再补一句提示文字：空表升级要求整块恰为表格行 + 空行，多一行就不认了。
     */
    private static Section phasesSection(List<String> notes) {
        Section section = new Section("## 六、阶段排期");
        section.add("| 阶段 | 时间窗 | 主攻 | 验收标准 |");
        section.add("|---|---|---|---|");
        notes.add("§六 阶段排期 迁移后是空表：考研「暂缓」，原 §三 的阶段 A–E 已整表移入 §八"
                + "（界面按 spec §5.5 显示「当前无进行中的阶段排期」）。");
        return section;
    }

    // ---- §七 本周 ----

    /**
     * §七：源 §五 的前导引用块 + ① 学校课程 原样搬，② 处放与 §四 同一张底线表（「本周」视角），
     * 末尾接周检查 / 调整机制 / 修订周期几条通用规则。
     *
     * <p>源 §五 ② 的「考研轻量」表不在这里：考研已暂缓，它按 spec §4.6 的另一条去向整段移入 §八。
     * ③ 生活保障按映射进 §五 生活准则，不在这里重复一份（同一段文字有两份，改一处就会不一致）。
     */
    private static Section thisWeekSection(MixedSection mixed, List<String> baselineTable) {
        Section section = new Section("## 七、本周");
        if (!mixed.leading().isEmpty()) {
            section.addAll(mixed.leading());
            section.blank();
        }
        if (!mixed.courseTasks().isEmpty()) {
            section.addAll(mixed.courseTasks());
        } else {
            section.add("**① 课程任务**");
        }
        section.blank();
        section.add("**② 每周底线**");
        section.blank();
        section.addAll(baselineTable);
        if (!mixed.rules().isEmpty()) {
            section.blank();
            section.addAll(mixed.rules());
        }
        return section;
    }

    // ---- §八 考研（暂缓） ----

    /**
     * §八：考研语境的收纳区——源 §一 的考研字段、源 §三 整节、源 §四 的考研部分、源 §五 ②，
     * 以及源档案里没有对应位置的章节，全部原样保留。
     *
     * <p>这里是「暂缓」这个状态的落地：内容一行不删，只是不再进入「本周」。
     */
    private static Section examDeferredSection(ParsedSource source, Map<String, String> profile,
                                               MixedSection mixed, List<String> examRules,
                                               BaselineTable baselines) {
        Section section = new Section("## 八、考研（暂缓）");
        section.add("> 以下内容来自原档案的考研部分（原 §一 的考研相关字段、原 §三 阶段排期、"
                + "原 §四 的考研清单、原 §五 ② 考研轻量）。");
        section.add("> 考研现在是「暂缓」：这些内容不进入「本周」、不计入底线，历史原样保留、一字未改。");
        section.blank();

        section.add("**原 §一 我的档案（考研相关字段）**");
        section.blank();
        section.add("| 项目 | 内容 |");
        section.add("|---|---|");
        section.add("| 目标 | " + orPlaceholder(profile.get("目标")) + " |");
        section.add("| 弱项自评 | " + orPlaceholder(profile.get("弱项自评")) + " |");

        addCarried(section, "原 §三 阶段排期", source.section("三") == null
                ? List.of() : trimBlankEdges(dropDividers(source.section("三").body())));
        addCarried(section, "原 §四 现在明确不碰（考研部分）", examRules);
        // 这一段原文自带 `**② 考研轻量（有量即可）**` 标签（原样块行，必须一字不动地保留），
        // 所以来源标签只写章节号，不重复同一个名字。
        addCarried(section, "原 §五 ②", mixed.examLight());

        for (SourceSection extra : source.unmapped()) {
            section.blank();
            section.add("### " + extra.heading().substring(3) + "（原档案里没有对应的目标位置，原样保留）");
            section.blank();
            section.addAll(trimBlankEdges(dropDividers(extra.body())));
        }
        if (!baselines.unplacedRows().isEmpty()) {
            // 列数不符的底线条目已经在 §四 表后保留；这里只补一句指向，避免两处各放一份。
            section.blank();
            section.add("> 源 §二 底线表里列数与表头不符的行，原样保留在 §四 表后。");
        }
        return section;
    }

    private static String orPlaceholder(String value) {
        return value == null || value.isEmpty() ? "待回报" : value;
    }

    /** 把一段原文原样搬进 §八，前面加一行来源标签（标签是新增的说明，原文一行不动）。 */
    private static void addCarried(Section section, String label, List<String> carried) {
        List<String> body = trimBlankEdges(carried);
        section.blank();
        section.add("**" + label + "**");
        section.blank();
        if (body.isEmpty()) {
            section.add("待回报");
            return;
        }
        section.addAll(body);
    }

    // ---- §九 周记录 ----

    /**
     * §九：源 §六 的正文<b>整段原样搬运</b>，一个字符都不改（P3 的核心）。
     *
     * <p>标题必须换成 {@link #WEEKLY_HEADING}：App 的 {@code appendWeek} 只认这个锚点。
     * 正文只做两件与内容无关的事——去掉首尾空行、丢掉 `---` 分隔线（目标以空行分节）。
     */
    private static Section weeklyRecordsSection(SourceSection six, List<String> notes) {
        Section section = new Section(WEEKLY_HEADING);
        if (six == null) {
            notes.add("源文件里找不到「## 六、周记录（每周追加，不覆盖）」这一节，§九 周记录 迁移后是空的"
                    + "（App 之后可以按锚点追加新周）。");
            return section;
        }
        List<String> body = trimBlankEdges(dropDividers(six.body()));
        if (body.isEmpty()) {
            notes.add("源文件 §六 周记录 里没有任何内容，§九 周记录 迁移后是空的。");
            return section;
        }
        section.addAll(body);
        return section;
    }

    // ---- §十 待确认 ----

    /** §十：源 §七 的引用块与编号列表原样搬；源 §一「待确认」行的取值也收在这里。 */
    private static Section pendingSection(Map<String, String> profile, SourceSection seven) {
        Section section = new Section("## 十、待确认");
        if (seven != null) {
            List<String> body = trimBlankEdges(dropDividers(seven.body()));
            if (!body.isEmpty()) {
                section.addAll(body);
            }
        }
        String pending = profile.get("待确认");
        if (pending != null && !pending.isBlank()) {
            section.blank();
            section.add("- 原 §一 我的档案「待确认」：" + pending);
        }
        if (section.lines.isEmpty()) {
            section.add("- 待回报");
        }
        return section;
    }

    // =====================================================================
    // 源文件解析（只读文本，不碰盘）
    // =====================================================================

    private record SourceSection(String numeral, String heading, List<String> body) {
    }

    private record ParsedSource(List<String> header, Map<String, SourceSection> sections,
                                List<SourceSection> unmapped) {
        SourceSection section(String numeral) {
            return sections.get(numeral);
        }
    }

    /** 源 §五 的五个片段：前导引用块、① 课程任务、② 考研轻量、③ 生活保障、以及 ③ 之后的通用规则。 */
    private record MixedSection(List<String> leading, List<String> courseTasks, List<String> examLight,
                                List<String> life, List<String> rules) {
    }

    /** §四 / §七 共用的底线表，以及没能进表的行（列数不符，原样保留）。 */
    private record BaselineTable(List<String> table, List<String> unplacedRows) {
    }

    /** 源 §四 的两个去处：{@code exam} 进 §八，{@code school} 留在 §四 附近。 */
    private record SchoolRules(List<String> exam, List<String> school) {
    }

    /** 把一个文档切成「头部 + 按中文序号索引的二级章节 + 没有对应位置的章节」。 */
    private static ParsedSource parseSource(List<String> lines, List<String> notes) {
        List<String> header = new ArrayList<>();
        Map<String, SourceSection> sections = new LinkedHashMap<>();
        List<SourceSection> unmapped = new ArrayList<>();
        int i = 0;
        while (i < lines.size() && !isSectionHeading(lines.get(i))) {
            header.add(lines.get(i));
            i++;
        }
        while (i < lines.size()) {
            String heading = lines.get(i).strip();
            i++;
            List<String> body = new ArrayList<>();
            while (i < lines.size() && !isSectionHeading(lines.get(i))) {
                body.add(lines.get(i));
                i++;
            }
            SourceSection section = new SourceSection(numeralOf(heading), heading, body);
            String numeral = section.numeral();
            if (numeral == null || !LEGACY_NUMERALS.contains(numeral)) {
                unmapped.add(section);
                notes.add("源文件的「" + heading + "」没有对应的目标位置，其内容原样并入 §八（一个字都没丢）。");
            } else if (sections.containsKey(numeral)) {
                unmapped.add(section);
                notes.add("源文件里「" + numeral + "」开头的章节出现了不止一次，只映射第一份；"
                        + "后面的「" + heading + "」原样并入 §八（一个字都没丢）。");
            } else {
                sections.put(numeral, section);
            }
        }
        return new ParsedSource(header, sections, unmapped);
    }

    /** `## 一、我的档案` → `一`；不是二级标题或没有「、」时返回 null。 */
    private static String numeralOf(String heading) {
        String body = heading.strip();
        if (!body.startsWith("## ")) {
            return null;
        }
        body = body.substring(3);
        int separator = body.indexOf('、');
        return separator <= 0 ? null : body.substring(0, separator).strip();
    }

    /** 按 `**①` / `**②` / `**③` 把源 §五 切段；③ 里尾部的通用规则再切到 {@code rules}。 */
    private static MixedSection splitMixed(SourceSection five) {
        if (five == null) {
            return new MixedSection(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<String> body = trimBlankEdges(dropDividers(five.body()));
        List<List<String>> chunks = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        List<String> current = new ArrayList<>();
        chunks.add(current);
        for (String line : body) {
            String marker = markerOf(line);
            if (marker != null) {
                current = new ArrayList<>();
                chunks.add(current);
                markers.add(marker);
            }
            current.add(line);
        }
        Map<String, List<String>> byMarker = new LinkedHashMap<>();
        for (int i = 0; i < markers.size(); i++) {
            byMarker.computeIfAbsent(markers.get(i), key -> new ArrayList<>()).addAll(chunks.get(i + 1));
        }
        List<String> life = byMarker.getOrDefault("③", new ArrayList<>());
        List<String> rules = new ArrayList<>();
        splitTrailingRules(life, rules);
        // 每段都去掉首尾空行：段与段之间由装配器统一补一个空行，否则会出现连续两个空行
        // （空行本身不是内容，但它会让产物的排版看起来像出了错）。
        return new MixedSection(trimBlankEdges(chunks.get(0)),
                trimBlankEdges(byMarker.getOrDefault("①", List.of())),
                trimBlankEdges(byMarker.getOrDefault("②", List.of())),
                trimBlankEdges(life), trimBlankEdges(rules));
    }

    /** 行首（可带 `**`）的圈号标号；不是分块标号时返回 null。 */
    private static String markerOf(String line) {
        String stripped = line.strip();
        int at = stripped.startsWith("**") ? 2 : 0;
        if (at >= stripped.length()) {
            return null;
        }
        char c = stripped.charAt(at);
        return CIRCLED.indexOf(c) >= 0 ? String.valueOf(c) : null;
    }

    /**
     * 把一段里的「通用规则」（周检查 / 调整机制 / 修订周期）切出去。
     *
     * <p>判据刻意简单：第一行之后的第一个以 `**` 开头的行就是规则段的开始。源档案的写法
     * （③ 的标题行 + bullet 列表 + 三条加粗规则）在真实档案与脱敏样例里都是这个形状；
     * 判错也不会丢字——切出去的规则段照样被搬进 §七，兜底还会再查一遍。
     */
    private static void splitTrailingRules(List<String> lines, List<String> rules) {
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("**")) {
                rules.addAll(lines.subList(i, lines.size()));
                lines.subList(i, lines.size()).clear();
                return;
            }
        }
    }

    /** 源 §一 的「项目 → 内容」；找不到表或行时为缺项（调用方会记备注，不编内容）。 */
    private static Map<String, String> profileValues(SourceSection one, List<String> notes) {
        Map<String, String> values = new LinkedHashMap<>();
        if (one == null) {
            notes.add("源文件里找不到「## 一、我的档案」这一节，§一 我的现状 只能留「本学期课程」一行。");
            return values;
        }
        List<String> body = trimBlankEdges(dropDividers(one.body()));
        int[] run = firstTableRun(body);
        if (run == null) {
            notes.add("源文件 §一 里找不到档案表（表头 + 分隔行 + 数据行），§一 我的现状 未包含它的行。");
            return values;
        }
        for (int i = run[0] + 2; i < run[1]; i++) {
            List<String> cells = TableBlock.splitCells(body.get(i));
            if (cells.size() == 2) {
                values.put(cells.get(0).strip(), cells.get(1).strip());
            }
        }
        return values;
    }

    /** §一「大二在学」→ 课程名列表；缺失或解析不出时退回 spec 的 6 门课，并记一条备注。 */
    private static List<String> courseNames(Map<String, String> profile, List<String> notes) {
        String declared = profile.get("大二在学");
        List<String> names = declared == null ? List.of() : parseCourseNames(declared);
        if (names.isEmpty()) {
            notes.add(declared == null
                    ? "源文件 §一 里没有「大二在学」行，§三 课程表 按 spec §5.3 用 6 门课初始化。"
                    : "源文件 §一「大二在学」行里没解析出课程名（原文：" + declared + "），"
                            + "§三 课程表 按 spec §5.3 用 6 门课初始化。");
            return DEFAULT_COURSES;
        }
        return names;
    }

    /**
     * 从「大二在学」的取值里取课程名：以 `、` 分隔，每项在第一个空白 / 破折号 / 加粗标记处截断。
     *
     * <p>源档案写的是 `数据库、Java、…、马克思主义基本原理 —— **这 6 门是本学期第一优先级**`，
     * 课程名只有破折号之前那一段。这里只做「截断装饰」，不改动名字本身。
     */
    private static List<String> parseCourseNames(String value) {
        List<String> names = new ArrayList<>();
        for (String token : value.split("、")) {
            String name = cutDecoration(token);
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static String cutDecoration(String token) {
        String text = stripChar(stripChar(token.strip(), '*'), '`');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '—' || c == '–' || c == '*') {
                return text.substring(0, i).strip();
            }
        }
        return text.strip();
    }

    private static String stripChar(String text, char c) {
        int from = 0;
        int to = text.length();
        while (from < to && text.charAt(from) == c) {
            from++;
        }
        while (to > from && text.charAt(to - 1) == c) {
            to--;
        }
        return text.substring(from, to);
    }

    // =====================================================================
    // 兜底：一行都不许丢
    // =====================================================================

    /**
     * P3 的兜底：装配完成后逐行核对，任何原样块文本行没进目标，就原样并进 §八，并记一条备注。
     *
     * <p>为什么要有兜底：旧档案被用户改过，形状无法穷举，而「少一行」正是这个任务唯一不可接受的
     * 结果。兜底把 P3 从「映射写全了才成立」变成「<b>构造上</b>成立」。
     *
     * <p>兜底一动就说明映射漏了东西，因此它<b>必须出声</b>（写 sectionNotes）：测试里有一条断言
     * 专门要求「脱敏样例的迁移不许动用兜底」，把「映射正确」与「兜底掩盖」分开。
     */
    private static String applyLostLineBackstop(String rendered, String sourceText, List<Section> sections,
                                                List<String> tailComments, List<String> notes) {
        List<String> missing = new ArrayList<>();
        for (String line : preservedLines(sourceText)) {
            if (!rendered.contains(line)) {
                missing.add(line);
            }
        }
        if (missing.isEmpty()) {
            return rendered;
        }
        Section eight = null;
        for (Section section : sections) {
            if (section.heading.startsWith("## 八、")) {
                eight = section;
                break;
            }
        }
        if (eight == null) {
            throw new IllegalStateException("内部错误：装配结果里没有 §八，兜底无处安放");
        }
        eight.blank();
        eight.add("**迁移时未能归类的原文（原样保留，一个字都没有丢）**");
        eight.blank();
        eight.addAll(missing);
        notes.add("有 " + missing.size() + " 行原文没能自动归到某一节，已原样并入 §八"
                + "（兜底：迁移绝不丢字）。");
        return render(sections, tailComments);
    }

    /**
     * 原样块文本行：非空、且不是表格行、不是标题行、不是分隔线。
     *
     * <p>口径<b>必须</b>与 {@code MigrationFidelityTest.preservedLines} 一致——那边是 P3 的验收
     * 定义，这边是兜底的判据；两处口径一旦漂移，兜底就会漏掉测试认为必须存在的行。
     */
    private static List<String> preservedLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : linesOf(text)) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("|") || stripped.startsWith("#")
                    || stripped.equals("---")) {
                continue;
            }
            out.add(stripped);
        }
        return out;
    }

    // =====================================================================
    // 文本装配与行工具（全部只处理内存里的字符串）
    // =====================================================================

    /** 目标文档里的一节：标题 + 正文行（行内不含行尾符）。 */
    private static final class Section {

        private final String heading;
        private final List<String> lines = new ArrayList<>();

        Section(String heading) {
            this.heading = heading;
        }

        void add(String line) {
            lines.add(line);
        }

        void addAll(List<String> more) {
            lines.addAll(more);
        }

        void blank() {
            lines.add("");
        }
    }

    /**
     * 把各节拼成整篇文本，并保证格式硬要求：<b>每个 `## ` 标题的前一行是空行</b>。
     *
     * <p>块边界只由零字符空行产生，缺了它就是「上一节末尾」与「下一节标题」落进同一个原样块。
     * 这里由装配器统一保证，而不是靠每一节自己记得补。
     */
    private static String render(List<Section> sections, List<String> tailComments) {
        StringBuilder out = new StringBuilder();
        for (Section section : sections) {
            if (out.length() > 0 && !endsWithBlankLine(out)) {
                out.append('\n');
            }
            out.append(section.heading).append('\n').append('\n');
            for (String line : trimBlankEdges(section.lines)) {
                out.append(line).append('\n');
            }
        }
        if (!tailComments.isEmpty()) {
            if (!endsWithBlankLine(out)) {
                out.append('\n');
            }
            for (String line : tailComments) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean endsWithBlankLine(StringBuilder text) {
        int n = text.length();
        return n >= 2 && text.charAt(n - 1) == '\n' && text.charAt(n - 2) == '\n';
    }

    /**
     * 文末的 HTML 注释：从文档末尾往前收集连续的注释行（中间允许空行与 `---`）。
     *
     * <p>这些注释是「填写方式 / 周界变更史 / 编制原则」，位置有语义（spec §4.4：文末），所以它们
     * 先被摘出来、最后放回文档末尾；文档中间出现的注释不动，跟着它所在的那一节原样搬。
     */
    private static List<String> extractTrailingComments(List<String> lines, List<String> comments) {
        List<String> rest = new ArrayList<>(lines);
        for (int i = rest.size() - 1; i >= 0; i--) {
            String stripped = rest.get(i).strip();
            if (stripped.isEmpty() || stripped.equals("---")) {
                continue;
            }
            if (stripped.startsWith("<!--")) {
                comments.add(0, rest.remove(i));
                continue;
            }
            break;
        }
        return rest;
    }

    /**
     * 文本 → 行（不含行尾符）：去掉可能的 BOM，把 CRLF / 孤立 CR 规范化为 LF。
     *
     * <p><b>只作用于内存里要写出的目标文本</b>：源文件本身的字节由 {@link TextFileCodec} 保真读取，
     * 全程不被改写。新建文件固定 UTF-8 无 BOM + LF（全局约束）。
     */
    private static List<String> linesOf(String text) {
        String withoutBom = text.startsWith("\uFEFF") ? text.substring(1) : text;
        String lf = withoutBom.replace("\r\n", "\n").replace('\r', '\n');
        return new ArrayList<>(Arrays.asList(lf.split("\n", -1)));
    }

    /** 去掉首尾空行（内部空行是内容，一格不动）。 */
    private static List<String> trimBlankEdges(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).strip().isEmpty()) {
            from++;
        }
        while (to > from && lines.get(to - 1).strip().isEmpty()) {
            to--;
        }
        return new ArrayList<>(lines.subList(from, to));
    }

    /** 丢掉 `---` 分隔线（原因见 {@link #buildTarget} 记的那条备注）。 */
    private static List<String> dropDividers(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!line.strip().equals("---")) {
                out.add(line);
            }
        }
        return out;
    }

    private static int indexOfPrefix(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** 第一段表格 run 的 {@code [start, end)}（表头 + 分隔行 + 数据行）；没有则返回 null。 */
    private static int[] firstTableRun(List<String> body) {
        for (int i = 0; i + 1 < body.size(); i++) {
            if (!isTableLine(body.get(i)) || !isTableLine(body.get(i + 1))
                    || !isSeparatorLine(body.get(i + 1))) {
                continue;
            }
            int end = i + 2;
            while (end < body.size() && isTableLine(body.get(end))) {
                end++;
            }
            return new int[] { i, end };
        }
        return null;
    }

    /**
     * 把正文里第一段表格 run 就地换成新表；其余行逐行原样保留（顺序也不动）。
     *
     * <p>找不到表格时把新表放最前、正文全留在后面，并记一条备注——这样 §四 永远有一张可被
     * {@code PlanDocument} 定位的表，而原文一行都不会因此消失。
     */
    private static List<String> replaceFirstTable(List<String> body, List<String> newTable, List<String> notes) {
        int[] run = firstTableRun(body);
        if (run == null) {
            if (!body.isEmpty()) {
                notes.add("源 §二 里没找到可替换的底线表（表头 + 分隔行 + 数据行），§四 的新表放在最前，"
                        + "原文全部原样保留在表后。");
            }
            List<String> out = new ArrayList<>(newTable);
            out.add("");
            out.addAll(body);
            return out;
        }
        List<String> out = new ArrayList<>(body.subList(0, run[0]));
        out.addAll(newTable);
        out.addAll(body.subList(run[1], body.size()));
        return out;
    }

    /** 与 {@code MarkdownParser} 同口径：{@code strip()} 后以 `|` 开头并以 `|` 结尾。 */
    private static boolean isTableLine(String line) {
        String body = line.strip();
        return body.length() >= 2 && body.startsWith("|") && body.endsWith("|");
    }

    /** 与 {@code MarkdownParser} 同口径：去掉 `|` `-` `:` 与空格后什么都不剩。 */
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

    /** 二级标题：{@code "## "} 之后必须有空格，所以 {@code "### …"} 不会被误当章节边界。 */
    private static boolean isSectionHeading(String line) {
        return line.strip().startsWith("## ");
    }
}
