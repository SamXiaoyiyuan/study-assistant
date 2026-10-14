package com.xiaoyiyuan.study.config;

import com.xiaoyiyuan.study.wellbeing.CrisisResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void defaults_whenFileMissing(@TempDir Path dir) {
        AppConfig c = AppConfig.load(dir.resolve("不存在.properties"));
        assertEquals(AppPaths.defaultStudyDir(), c.studyDir());
        assertEquals(DeferredVisibility.COLLAPSED, c.deferredVisibility(), "默认折叠");
        assertEquals(List.of("平静", "有劲", "疲惫", "烦躁", "低落", "焦虑", "开心", "麻木"),
                c.moodOptions());
    }

    @Test
    void roundTripsThroughFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("E:\\别的目录"));
        c.setDeferredVisibility(DeferredVisibility.HIDDEN);
        c.setMoodOptions(List.of("还行", "很累"));
        c.save();

        AppConfig again = AppConfig.load(f);
        assertEquals(Path.of("E:\\别的目录"), again.studyDir());
        assertEquals(DeferredVisibility.HIDDEN, again.deferredVisibility());
        assertEquals(List.of("还行", "很累"), again.moodOptions());
    }

    @Test
    void fileIsUtf8_noBom_lfOnly(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setMoodOptions(List.of("疲惫"));
        c.save();

        byte[] bytes = Files.readAllBytes(f);
        assertNotEquals((byte) 0xEF, bytes[0], "不得写 BOM");
        assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("\r\n"),
                "不得写 CRLF");
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("疲惫"),
                "中文必须可读，不能被转义成 \\uXXXX");
    }

    @Test
    void unknownVisibilityValue_fallsBackToDefault(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        Files.writeString(f, "deferredVisibility=乱写的\n");
        assertEquals(DeferredVisibility.COLLAPSED, AppConfig.load(f).deferredVisibility());
    }

    @Test
    void appPaths_areUnderStudyDir(@TempDir Path dir) {
        assertEquals(dir.resolve("学习与生活计划.md"), AppPaths.planFile(dir));
        assertEquals(dir.resolve("408-进度.md"), AppPaths.legacyFile(dir));
        assertEquals(dir.resolve(".backup"), AppPaths.backupDir(dir));
        assertEquals(dir.resolve(".archive"), AppPaths.archiveDir(dir));
    }

    // 以下三条超出 brief 的逐字测试，是「配置坏了也得能启动」与「文件格式可读」这两条要求的防回归网。

    /**
     * 全新安装：配置所在的目录<b>整个都不存在</b>，`save()` 必须自己把它建出来。
     *
     * <p>用「两层都不存在」而不是一层：只建最后一段父目录的实现（例如只对 {@code getParent()}
     * 调一次不带 {@code createDirectories} 的创建）在只缺一层时会侥幸通过。默认路径
     * {@code %APPDATA%\xiaoyiyuan-study\} 在全新机器上正是「连 {@code xiaoyiyuan-study} 都没有」
     * 的形态，所以这里必须两层都缺。
     */
    @Test
    void save_createsMissingParentDirectories(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("xiaoyiyuan-study").resolve("nested").resolve("config.properties");
        assertFalse(Files.exists(f.getParent()), "前置条件：父目录必须不存在");

        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("E:\\别的目录"));
        c.save();

        assertTrue(Files.isRegularFile(f), "首次保存必须把文件建出来，而不是抛 NoSuchFileException");
        assertEquals(Path.of("E:\\别的目录"), AppConfig.load(f).studyDir(), "建目录后往返必须成立");
    }

    @Test
    void valueWithWindowsPathBackslash_survivesRoundTrip(@TempDir Path dir) throws IOException {
        // 值里的反斜杠必须按字面往返。用 java.util.Properties 读写时（本机 JDK 25 实测）：
        //   E:\new\my-dir  → "E:" + 换行 + "ew" + 制表符 + "abs" 之类，
        //   %USERPROFILE%\study → "%USERPROFILE%study"（反斜杠被吞掉）。
        // 后者仍然是个「合法路径」，Path.of 不报错，App 会安静地去一个不存在的目录找计划文件。
        // 所以这里刻意用带中文、带反斜杠的真实形状，而不是一个只有一段的目录名。
        Path f = dir.resolve("backslash.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("C:", "示例", "study"));
        c.save();

        assertEquals(Path.of("C:", "示例", "study"), AppConfig.load(f).studyDir());

        AppConfig c2 = AppConfig.load(f);
        c2.setStudyDir(Path.of("E:\\new\\tabs"));
        c2.save();
        assertEquals(Path.of("E:\\new\\tabs"), AppConfig.load(f).studyDir());
    }

    @Test
    void load_toleratesBomCommentsAndBlankLines(@TempDir Path dir) throws IOException {
        // BOM + 注释 + 空行 + 值两侧多余空白 + 没有结尾换行：手改过的配置文件长这样。
        // 带 BOM 时若剥不掉，第一个键会变成「BOM + studyDir」，整份配置静默退回默认值；
        // 值尾部多一个空格若不去掉，Path.of 会抛 InvalidPathException，学习目录同样静默退回默认值。
        Path f = dir.resolve("hand-edited.properties");
        Files.writeString(f, "\uFEFF# 手改过的配置\n\n studyDir = E:\\我的目录 \n\n",
                java.nio.charset.StandardCharsets.UTF_8);

        AppConfig c = AppConfig.load(f);
        assertEquals(Path.of("E:\\我的目录"), c.studyDir());
        assertEquals(DeferredVisibility.COLLAPSED, c.deferredVisibility());
        assertEquals(List.of("平静", "有劲", "疲惫", "烦躁", "低落", "焦虑", "开心", "麻木"),
                c.moodOptions());
    }

    @Test
    void writtenFileIsPlainReadableText(@TempDir Path dir) throws IOException {
        // 文件内容逐字可预测：没有注释行、没有时间戳、没有中文被写成 Unicode 转义。
        Path f = dir.resolve("plain.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("E:\\别的目录"));
        c.save();

        assertEquals("studyDir=E:\\别的目录\n"
                        + "deferredVisibility=COLLAPSED\n"
                        + "moodOptions=平静,有劲,疲惫,烦躁,低落,焦虑,开心,麻木\n"
                        + "base_url=\n"
                        + "api_key=\n"
                        + "model=\n"
                        + "request_timeout=60\n"
                        + "schoolCounseling=\n"
                        + "crisis.1.label=全国统一心理援助热线\n"
                        + "crisis.1.number=12356\n"
                        + "crisis.2.label=北京心理援助热线（手机）\n"
                        + "crisis.2.number=010-82951332\n"
                        + "crisis.3.label=北京心理援助热线（座机）\n"
                        + "crisis.3.number=800-810-1117\n"
                        + "crisis.4.label=急救\n"
                        + "crisis.4.number=120\n"
                        + "crisis.5.label=报警\n"
                        + "crisis.5.number=110\n",
                Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
    }

    // —— 计划 2 · Task 2：AI 配置与危机渠道（brief 逐字要求的断言 + 防回归网） ——

    /**
     * 危机渠道默认值：spec 附录 A 的五条内置渠道，**顺序固定**（顺序即面板展示顺序）。
     *
     * <p>文案也逐字断言：附录 A 写的是什么、默认值就该是什么——号码写错是安全问题，
     * 顺序被「优化」过同样是问题，两者用同一条断言一起钉住。
     */
    @Test
    void crisisDefaults_areTheFiveBuiltins(@TempDir Path dir) {
        AppConfig c = AppConfig.load(dir.resolve("不存在.properties"));
        assertEquals(List.of("12356", "010-82951332", "800-810-1117", "120", "110"),
                c.crisisResources().stream().map(CrisisResource::number).toList());
        assertEquals(List.of("全国统一心理援助热线", "北京心理援助热线（手机）", "北京心理援助热线（座机）",
                        "急救", "报警"),
                c.crisisResources().stream().map(CrisisResource::label).toList());
    }

    /**
     * 删除保护（spec §12 保底规则）：{@code 120} 与 {@code 110} 不可删除，判据是**号码**。
     *
     * <p>四条断言各堵一个漏洞：①只缺一条也要拦；②两条都缺时消息要把两个号码都点出来——
     * 界面直接展示这条消息，说「缺了一个」却不说缺哪个，等于没说；③只缺 120 时消息不得
     * 捎带上 110（否则「点明缺哪个」就没做到）；④文案写成 120/110 但号码不是，仍算缺。
     * 最后再断言：被拒绝的编辑必须让已有配置原封不动。
     */
    @Test
    void crisis_cannotDrop120Or110(@TempDir Path dir) {
        AppConfig c = AppConfig.load(dir.resolve("config.properties"));

        assertThrows(IllegalArgumentException.class, () -> c.setCrisisResources(List.of(
                new CrisisResource("全国统一心理援助热线", "12356"))), "只缺 120/110 也必须拦");

        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                () -> c.setCrisisResources(List.of()));
        assertTrue(both.getMessage().contains("120") && both.getMessage().contains("110"),
                "两条都缺时消息必须点明两个号码；实际消息：" + both.getMessage());

        IllegalArgumentException only120 = assertThrows(IllegalArgumentException.class,
                () -> c.setCrisisResources(List.of(new CrisisResource("报警", "110"),
                        new CrisisResource("学校心理中心", "1234-5678"))));
        assertTrue(only120.getMessage().contains("120"), "缺 120 时消息要点明 120");
        assertFalse(only120.getMessage().contains("110"), "只缺 120 时消息不该混入 110");

        assertThrows(IllegalArgumentException.class, () -> c.setCrisisResources(List.of(
                new CrisisResource("120", "999"), new CrisisResource("110", "888"))),
                "身份是号码：文案写成 120/110 但号码不是，仍算缺");

        assertEquals(5, c.crisisResources().size(), "被拒绝的编辑不得改动已有配置");
    }

    /** 其余渠道可自由增删改（改文案、换号码、加自定义），并逐条、按序往返。 */
    @Test
    void crisis_canBeExtendedAndRelabelled(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setCrisisResources(List.of(new CrisisResource("我改的名字", "120"),
                new CrisisResource("报警", "110"),
                new CrisisResource("学校心理中心", "1234-5678")));
        c.save();

        AppConfig again = AppConfig.load(f);
        assertEquals(3, again.crisisResources().size());
        assertEquals(new CrisisResource("我改的名字", "120"), again.crisisResources().get(0), "文案可改");
        assertEquals(new CrisisResource("报警", "110"), again.crisisResources().get(1));
        assertEquals(new CrisisResource("学校心理中心", "1234-5678"), again.crisisResources().get(2),
                "顺序保持");
    }

    /** 文件在、但一个 crisis.* 键都没有 → 回退五条内置（全新安装的文件就是这种形态）。 */
    @Test
    void crisisResources_fileWithoutSection_usesDefaults(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("no-crisis-section.properties");
        Files.writeString(f, "studyDir=E:\\我的目录\n", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(5, AppConfig.load(f).crisisResources().size());
    }

    /**
     * 手改过的文件里编号不连续（删了 crisis.2.* 却留着 crisis.3.*）时，
     * 后面的渠道**不能**被静默丢掉——「从 1 扫到第一个空缺就停」的实现会在这里变红。
     */
    @Test
    void crisisResources_gapInIndices_isNotDropped(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("gapped.properties");
        Files.writeString(f, "crisis.1.label=急救\ncrisis.1.number=120\n"
                        + "crisis.3.label=报警\ncrisis.3.number=110\n",
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(List.of(new CrisisResource("急救", "120"), new CrisisResource("报警", "110")),
                AppConfig.load(f).crisisResources());
    }

    /** 值快照：外部列表之后再改，不动配置里的值；getter 返回的列表不可变。 */
    @Test
    void crisisResources_snapshotIsDefensive(@TempDir Path dir) {
        AppConfig c = AppConfig.load(dir.resolve("config.properties"));
        List<CrisisResource> external = new ArrayList<>(List.of(new CrisisResource("急救", "120"),
                new CrisisResource("报警", "110")));
        c.setCrisisResources(external);
        external.clear();
        assertEquals(2, c.crisisResources().size(), "快照：外部清空不得影响配置");
        assertThrows(UnsupportedOperationException.class,
                () -> c.crisisResources().add(new CrisisResource("另一个", "119")));
    }

    /** AI 三字段：默认空串；按字面往返（含非 ASCII 的假密钥）。 */
    @Test
    void aiFields_defaultEmpty_andRoundTrip(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        assertEquals("", c.baseUrl());
        assertEquals("", c.apiKey());
        assertEquals("", c.model());

        // 密钥是明文存储的（spec §7.1），这里用一个一眼能看出是假的占位值；
        // 真实密钥永远不会出现在仓库里。
        c.setBaseUrl("https://api.deepseek.com");
        c.setApiKey("sk-仅测试用的假密钥");
        c.setModel("deepseek-chat");
        c.save();

        AppConfig again = AppConfig.load(f);
        assertEquals("https://api.deepseek.com", again.baseUrl());
        assertEquals("sk-仅测试用的假密钥", again.apiKey());
        assertEquals("deepseek-chat", again.model());
    }

    /** 学校心理健康中心：默认空；填了中文与空格后往返。 */
    @Test
    void schoolCounseling_blankByDefault_andRoundTrips(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        assertEquals("", c.schoolCounseling());

        c.setSchoolCounseling("校医院三楼 心理中心 021-1234-5678");
        c.save();
        assertEquals("校医院三楼 心理中心 021-1234-5678", AppConfig.load(f).schoolCounseling());
    }

    /**
     * 请求超时：默认 60 秒；整秒往返；文件里的乱写值回退默认（「配置坏了也得能启动」）。
     *
     * <p>另钉住 setter 的两条护栏：非正数、以及无法用整秒无损表示的 Duration 必须当场拒绝——
     * 文件里存的是整秒数，把 500ms 悄悄截断成 0 再读回默认值，比拒绝更危险。
     */
    @Test
    void requestTimeout_defaultsTo60Seconds_andRoundTrips(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        assertEquals(Duration.ofSeconds(60), c.requestTimeout());

        c.setRequestTimeout(Duration.ofSeconds(30));
        c.save();
        assertEquals(Duration.ofSeconds(30), AppConfig.load(f).requestTimeout());

        Files.writeString(f, "request_timeout=90\n", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(Duration.ofSeconds(90), AppConfig.load(f).requestTimeout());

        Files.writeString(f, "request_timeout=abc\n", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(Duration.ofSeconds(60), AppConfig.load(f).requestTimeout(), "乱写回退默认");

        Files.writeString(f, "request_timeout=0\n", java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(Duration.ofSeconds(60), AppConfig.load(f).requestTimeout(), "非正数回退默认");

        assertThrows(IllegalArgumentException.class, () -> c.setRequestTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> c.setRequestTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> c.setRequestTimeout(Duration.ofMillis(500)),
                "文件粒度是整秒，500ms 存不回去，必须拒绝而不是截断");
    }

    /** 新增字段全部写出来后，文件仍是 UTF-8 无 BOM + LF，中文逐字可读。 */
    @Test
    void writtenFile_staysUtf8NoBomLf_withReadableChinese(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setBaseUrl("https://api.deepseek.com");
        c.setSchoolCounseling("学校心理中心");
        c.setCrisisResources(List.of(new CrisisResource("急救", "120"), new CrisisResource("报警", "110")));
        c.save();

        byte[] bytes = Files.readAllBytes(f);
        assertNotEquals((byte) 0xEF, bytes[0], "不得写 BOM");
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(text.contains("\r\n"), "不得写 CRLF");
        assertTrue(text.contains("schoolCounseling=学校心理中心"), "中文必须可读，不能被转义");
        assertTrue(text.contains("crisis.1.label=急救"), "危机渠道逐条写出");
        assertTrue(text.contains("crisis.2.number=110"), "危机渠道逐条写出");
        assertTrue(text.contains("base_url=https://api.deepseek.com"), "值按字面写出");
    }

    // —— 修复轮 1（复核 Major-1 / Minor-1 / Minor-2）：把删除保护从「API 层」钉到「文件层」 ——

    /**
     * 对抗性文件内容表：任何一份能被程序识别的危机渠道文件，保存之后都必须<b>仍然含有 120 与 110</b>。
     *
     * <p>复核发现的最小复现：文件里只有 {@code crisis.1.number=120}（用户手改、或半截写入、或别的
     * 程序截断），{@code load} 忠实返回 1 条 → 用户只改了一个<b>无关字段</b> → {@code save()} →
     * 磁盘上 110 永久消失。全程不经过 {@code setCrisisResources}，所以「删除保护只在 setter 里」
     * 的旧实现对这个路径毫无办法，而旧测试全绿。
     *
     * <p>每条用例都走同一条路：写文件 → load → 改一个无关字段（学校心理中心）→ save → 重新读盘，
     * 然后两路断言：① 重新 load 出来的号码表；② 磁盘原文里每个号码都有一条
     * {@code crisis.N.number=<号码>} 行——只断言 ① 的话，「渲染时丢行」这类 bug 会漏网。
     *
     * <p>用例的选取各有分工：①② 各缺一条必需号码；③ 编号越界（{@code Integer.parseInt} 抛
     * {@code NumberFormatException}，旧实现 {@code continue} 整条丢弃）；④ 负编号（旧实现直接丢）；
     * ⑤ 编号跳跃（旧实现保留，是回归网）；⑥ 正常自定义列表，钉住「标签原样保留、顺序不变」；
     * ⑦ 非整数编号（{@code crisis.abc.number=119}，旧实现整条丢弃——判据是「值本身可辨识」，
     * 见 minor 清算批）。前五条与第七条都不在旧实现的保护范围内，而 {@code 120}/{@code 110}
     * 的存在是硬要求。
     */
    @Test
    void crisisSave_fromAnyFileContent_keeps120And110(@TempDir Path dir) throws IOException {
        assertEquals(List.of("只有 120 的文件", "只有 110 的文件", "越界编号", "负编号", "编号跳跃",
                        "正常自定义列表", "非整数编号"),
                ADVERSARIAL_CRISIS_FILES.stream().map(AdversarialCrisisFile::name).toList(),
                "①–⑦ 一条都不能少：少一条等于这张性质表少钉一个角");

        for (AdversarialCrisisFile item : ADVERSARIAL_CRISIS_FILES) {
            String context = "[" + item.name() + "] 文件原文：" + item.fileContent().replace("\n", "\\n");
            Path f = dir.resolve("adversarial-" + item.name().hashCode() + ".properties");
            writeConfig(f, item.fileContent());

            AppConfig loaded = AppConfig.load(f);
            loaded.setSchoolCounseling("校医院三楼 心理中心 021-1234-5678"); // 与危机渠道无关的一个字段
            loaded.save();

            AppConfig again = AppConfig.load(f);
            List<String> numbers = again.crisisResources().stream().map(CrisisResource::number).toList();
            String diskText = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);

            for (String number : List.of("120", "110")) {
                assertTrue(numbers.contains(number),
                        context + " → 保存后重新 load 少了 " + number + "；实际号码表：" + numbers);
                assertTrue(diskText.contains("crisis.") && diskText.contains(".number=" + number + "\n"),
                        context + " → 保存后的文件里没有 " + number + " 的那一行；文件原文："
                                + diskText.replace("\n", "\\n"));
            }

            assertEquals(item.expectedNumbers(), numbers, context + " → 保存后重新 load 的号码表/顺序");
            for (String labelKey : item.preservedLabelKeys()) {
                assertTrue(diskText.contains(labelKey),
                        context + " → 用户可识别的条目被静默丢弃：" + labelKey + "；文件原文："
                                + diskText.replace("\n", "\\n"));
            }
        }
    }

    /** {@link #crisisSave_fromAnyFileContent_keeps120And110} 的表行：文件原文 + 期望号码表 + 必须留存的标签/号码行。 */
    private record AdversarialCrisisFile(String name, String fileContent, List<String> expectedNumbers,
            List<String> preservedLabelKeys) {
    }

    /** 表本体。{@code expectedNumbers} 里的 120/110 由 load 的保底补齐，其余条目一律原样保留。 */
    private static final List<AdversarialCrisisFile> ADVERSARIAL_CRISIS_FILES = List.of(
            new AdversarialCrisisFile("只有 120 的文件",
                    "studyDir=E:\\我的目录\ncrisis.1.label=急救\ncrisis.1.number=120\n",
                    List.of("120", "110"),
                    List.of("crisis.1.label=急救")),
            new AdversarialCrisisFile("只有 110 的文件",
                    "studyDir=E:\\我的目录\ncrisis.1.label=报警\ncrisis.1.number=110\n",
                    List.of("110", "120"),
                    List.of("crisis.1.label=报警")),
            new AdversarialCrisisFile("越界编号",
                    "crisis.2147483648.label=报警\ncrisis.2147483648.number=110\n",
                    List.of("110", "120"),
                    List.of("crisis.1.label=报警", "crisis.1.number=110")),
            new AdversarialCrisisFile("负编号",
                    "crisis.-1.label=学校附近求助点\ncrisis.-1.number=119\n",
                    List.of("119", "120", "110"),
                    List.of("crisis.1.label=学校附近求助点", "crisis.1.number=119")),
            new AdversarialCrisisFile("编号跳跃",
                    "crisis.5.number=110\n",
                    List.of("110", "120"),
                    List.of("crisis.1.number=110")),
            new AdversarialCrisisFile("正常自定义列表",
                    "crisis.1.label=我改的名字\ncrisis.1.number=120\n"
                            + "crisis.2.label=报警\ncrisis.2.number=110\n"
                            + "crisis.3.label=学校心理中心\ncrisis.3.number=1234-5678\n",
                    List.of("120", "110", "1234-5678"),
                    List.of("crisis.1.label=我改的名字", "crisis.3.label=学校心理中心",
                            "crisis.3.number=1234-5678")),
            // ⑦ 非整数编号（minor 清算批 / Task 2 复审 Minor-3）：号码值 119 本身可辨识，
            // 用户写的这条渠道必须留在文件里，不得静默消失。
            // 顺序规则：正常编号（升序）→ 非整数/越界/负编号（按文件出现顺序）→ 补齐的必需号码。
            // 这条护栏是「别把它改回静默丢弃」，不是「修一个当前存在的缺陷」——写这条时我先
            // 实测过工作树版本（见 task-minors-report.md §A2）。
            new AdversarialCrisisFile("非整数编号",
                    "crisis.abc.label=校园热线\ncrisis.abc.number=119\n"
                            + "crisis.1.label=急救\ncrisis.1.number=120\n",
                    List.of("120", "119", "110"),
                    List.of("crisis.2.label=校园热线", "crisis.2.number=119")));

    /**
     * 契约（Minor-2）：{@code setCrisisResources} 收到<b>含 null 元素</b>的列表时，必须抛
     * {@link IllegalArgumentException} 并点明位置，不得让 {@link List#copyOf} 抛
     * {@link NullPointerException}——javadoc 承诺的是 IAE，调用方按 IAE 写 catch 时会漏接 NPE，
     * 而 NPE 的消息里只有一个下标数字，界面拿它没法给用户任何可读的解释。
     */
    @Test
    void crisis_setWithNullElement_throwsIaeWithPosition(@TempDir Path dir) {
        AppConfig c = AppConfig.load(dir.resolve("config.properties"));

        IllegalArgumentException atZero = assertThrows(IllegalArgumentException.class,
                () -> c.setCrisisResources(java.util.Arrays.asList(
                        null, new CrisisResource("急救", "120"), new CrisisResource("报警", "110"))),
                "含 null 元素必须按契约抛 IAE（旧实现抛的是 NPE）");
        assertTrue(atZero.getMessage().contains("0"),
                "消息必须点明位置（下标 0）；实际消息：" + atZero.getMessage());

        IllegalArgumentException atOne = assertThrows(IllegalArgumentException.class,
                () -> c.setCrisisResources(java.util.Arrays.asList(new CrisisResource("急救", "120"),
                        null, new CrisisResource("报警", "110"))),
                "null 出现在中间位置同样要拦");
        assertTrue(atOne.getMessage().contains("1"),
                "消息必须点明位置（下标 1）；实际消息：" + atOne.getMessage());

        assertEquals(5, c.crisisResources().size(), "被拒绝的编辑不得改动已有配置");
    }

    /**
     * 契约（Minor-2）：请求超时必须有上界——{@code Duration.ofSeconds(Long.MAX_VALUE)} 能被存下来，
     * 直到真正发请求时 {@code toNanos()} 才抛 {@link ArithmeticException}，那时已经离「用户改设置」
     * 很远，且异常用的是纳秒溢出这种对用户毫无意义的措辞。这里把上界钉在 24 小时：既是常见用例
     * （60 秒～几分钟）的上千倍，又是「一定跑不完」的量级。
     */
    @Test
    void requestTimeout_rejectsAbove24Hours_andKeeps24hBoundary(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);

        c.setRequestTimeout(Duration.ofHours(24)); // 上界本身合法，避免「一律拒绝大值」的假守护
        c.save();
        assertEquals(Duration.ofHours(24), AppConfig.load(f).requestTimeout(), "24 小时是闭区间上界");

        IllegalArgumentException tooBig =
                assertThrows(IllegalArgumentException.class, () -> c.setRequestTimeout(Duration.ofHours(24)
                        .plusSeconds(1)), "超过 24 小时必须当场拒绝");
        assertTrue(tooBig.getMessage().contains("24"),
                "消息要能看懂：得说出上界是多少；实际消息：" + tooBig.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> c.setRequestTimeout(Duration.ofSeconds(Long.MAX_VALUE)),
                "Long.MAX_VALUE 秒：存下去会在 toNanos() 溢出，必须在设置时就拒绝");
        assertThrows(IllegalArgumentException.class, () -> c.setRequestTimeout(Duration.ofHours(25)));
        assertThrows(IllegalArgumentException.class, () -> c.setRequestTimeout(Duration.ofDays(365)));

        assertEquals(Duration.ofHours(24), c.requestTimeout(), "被拒绝的编辑不得改动已有配置");

        // 文件里写了超界值时同样回退默认（「配置坏了也得能启动」），而不是把天文数字搬进内存。
        writeConfig(f, "request_timeout=" + Long.MAX_VALUE + "\n");
        assertEquals(Duration.ofSeconds(60), AppConfig.load(f).requestTimeout(), "文件里的超界值回退默认");
        writeConfig(f, "request_timeout=86401\n");
        assertEquals(Duration.ofSeconds(60), AppConfig.load(f).requestTimeout(), "86401 秒 > 24 小时，回退默认");
        writeConfig(f, "request_timeout=86400\n");
        assertEquals(Duration.ofHours(24), AppConfig.load(f).requestTimeout(), "86400 秒 = 24 小时，合法");
    }

    // —— minor 清算批（Task 2 复审 Minor-1 / Minor-3 / Minor-4） ——

    /**
     * <b>幂等性：同一文件连续两轮 {@code load → save} 必须逐字节相等</b>（minor 清算批，Task 2 复审
     * Minor-4）。
     *
     * <p>复审者用变异验证证明了它的必要性：把「补齐必需号码」的两个方法<b>同时</b>反转
     * （缺失判定恒真 + 不判重直接追加）之后，每一轮都会把 120/110 再追加一遍，
     * {@code crisis.N.number} 行逐轮增殖（第 1 轮 3 条、第 2 轮 5 条、第 3 轮 7 条），
     * 而当时击杀它的只是「号码表/顺序」类断言，没有一条断言直接盯着「保存是不是收敛」。
     * 单看某一轮的磁盘文本看不出问题（它自洽），只有<b>两轮对比</b>才暴露「每保存一次就变长」。
     *
     * <p>三条断言各堵一个漏洞：① 两轮文本逐字相等；② 磁盘上确实有危机行（否则空转式全绿）；
     * ③ 两轮之后条目数一模一样（不增殖）。
     */
    @Test
    void crisisSave_isIdempotent_secondLoadSaveIsByteIdentical(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("idempotent.properties");
        writeConfig(f, "studyDir=E:\\我的目录\n"
                + "crisis.1.label=我改的名字\ncrisis.1.number=120\n");

        AppConfig firstRound = AppConfig.load(f);
        firstRound.setSchoolCounseling("校医院三楼 心理中心 021-1234-5678");
        firstRound.save();
        String roundOne = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
        int countAfterFirstRound = AppConfig.load(f).crisisResources().size();

        AppConfig secondRound = AppConfig.load(f);
        secondRound.save();
        String roundTwo = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
        int countAfterSecondRound = AppConfig.load(f).crisisResources().size();

        assertEquals(roundOne, roundTwo,
                "第二轮 load→save 必须与第一轮逐字节相同；否则「保存」本身在改文件，"
                        + "每次点保存都会产生无意义的 diff（疑似必需号码每轮增殖）。第一轮：\n"
                        + roundOne.replace("\n", "\\n") + "\n第二轮：\n" + roundTwo.replace("\n", "\\n"));
        assertTrue(roundOne.contains("crisis.") && roundOne.contains(".number=120"),
                "前置条件：磁盘上必须有危机行，否则上面的等值是「空对空」；实际："
                        + roundOne.replace("\n", "\\n"));
        assertEquals(countAfterFirstRound, countAfterSecondRound,
                "条目数不得逐轮增殖（把补齐的两个方法同时写错就会每轮多一组 120/110）");
    }

    /**
     * 幂等性不止对「只有一个 120」的文件成立，而要对整张对抗性表成立（minor 清算批）。
     *
     * <p>表里的形状各有一个容易写错的补齐路径：缺 110、缺 120、越界编号、负编号、非整数编号。
     * 统一要求：第 2 轮与第 3 轮的磁盘文本逐字相同、且条目数不变。
     */
    @Test
    void crisisSave_isIdempotent_forEveryAdversarialFileContent(@TempDir Path dir) throws IOException {
        for (AdversarialCrisisFile item : ADVERSARIAL_CRISIS_FILES) {
            String context = "[" + item.name() + "] 文件原文：" + item.fileContent().replace("\n", "\\n");
            Path f = dir.resolve("idem-" + item.name().hashCode() + ".properties");
            writeConfig(f, item.fileContent());

            AppConfig first = AppConfig.load(f);
            first.save();
            String roundTwo = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
            int countAfterRoundTwo = AppConfig.load(f).crisisResources().size();

            AppConfig second = AppConfig.load(f);
            second.save();
            String roundThree = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
            int countAfterRoundThree = AppConfig.load(f).crisisResources().size();

            assertEquals(roundTwo, roundThree,
                    context + " → 第二轮与第三轮 load→save 必须逐字节相同；第二轮：\n"
                            + roundTwo.replace("\n", "\\n") + "\n第三轮：\n"
                            + roundThree.replace("\n", "\\n"));
            assertEquals(countAfterRoundTwo, countAfterRoundThree,
                    context + " → 条目数不得逐轮增殖");
        }
    }

    /**
     * 幂等性不许为了「看起来稳定」而<b>减</b>内容：每一轮保存后，用户原始文件里可识别的条目
     * 都还必须在，必需号码也还必须在（与 {@link #crisisSave_fromAnyFileContent_keeps120And110}
     * 是同一性质，这里盯的是「第二轮」）。
     */
    @Test
    void crisisSave_secondRoundStillKeepsEverything(@TempDir Path dir) throws IOException {
        for (AdversarialCrisisFile item : ADVERSARIAL_CRISIS_FILES) {
            String context = "[" + item.name() + "]";
            Path f = dir.resolve("keep2-" + item.name().hashCode() + ".properties");
            writeConfig(f, item.fileContent());

            AppConfig first = AppConfig.load(f);
            first.save();
            AppConfig second = AppConfig.load(f);
            second.save();

            AppConfig reloaded = AppConfig.load(f);
            List<String> numbers = reloaded.crisisResources().stream()
                    .map(CrisisResource::number).toList();
            String diskText = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);

            assertEquals(item.expectedNumbers(), numbers,
                    context + " → 第二轮保存后的号码表/顺序；实际磁盘："
                            + diskText.replace("\n", "\\n"));
            assertTrue(numbers.contains("120") && numbers.contains("110"),
                    context + " → 任何轮次保存后都必须仍有 120 与 110；实际：" + numbers);
        }
    }

    /**
     * 合法自定义文案<b>永不</b>被默认文案顶掉——这条是给 {@code unionByNumber} 的 javadoc
     * 说法做实证（minor 清算批，Task 2 复审 Minor-1；该 javadoc 已改成与实现一致）。
     *
     * <p>复审者指出的措辞风险是：「读取端号码相同时用默认那条替换掉文件里那条」会让下一位
     * 维护者以为用户自定义的 120 文案会被顶掉，而正确行为恰恰相反。实测四种文件形态下
     * 「我的热线」都必须逐字保留；替换分支只有在「该号码本就被判为缺失」时才可能走到。
     */
    @Test
    void crisisSave_customLabelSurvivesEveryFileShape(@TempDir Path dir) throws IOException {
        for (String content : List.of(
                "crisis.1.label=我的热线\ncrisis.1.number=120\ncrisis.2.label=报警\ncrisis.2.number=110\n",
                "crisis.1.label=我的热线\ncrisis.1.number=120\n",
                "crisis.1.number=120\ncrisis.1.label=我的热线\ncrisis.9.label=远端\ncrisis.9.number=119\n",
                "crisis.abc.label=我的热线\ncrisis.abc.number=120\n")) {
            Path f = dir.resolve("label-" + content.hashCode() + ".properties");
            writeConfig(f, content);

            AppConfig loaded = AppConfig.load(f);
            assertTrue(loaded.crisisResources().stream()
                            .anyMatch(r -> "我的热线".equals(r.label()) && "120".equals(r.number())),
                    "合法自定义 120 文案不得被默认文案顶掉；文件：" + content.replace("\n", "\\n")
                            + " → 实际：" + loaded.crisisResources());

            loaded.save();
            String diskText = Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(diskText.contains("我的热线"),
                    "自定义 120 文案必须留在磁盘上；文件：" + content.replace("\n", "\\n")
                            + " → 磁盘：" + diskText.replace("\n", "\\n"));

            AppConfig again = AppConfig.load(f);
            assertTrue(again.crisisResources().stream()
                            .anyMatch(r -> "我的热线".equals(r.label()) && "120".equals(r.number())),
                    "第二轮读回时自定义 120 文案仍必须在；磁盘："
                            + diskText.replace("\n", "\\n") + " → 实际：" + again.crisisResources());
        }
    }

    /** 测试用：把配置文件原文按 UTF-8（无 BOM）写到磁盘。 */
    private static void writeConfig(Path file, String content) throws IOException {
        Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
    }
}
