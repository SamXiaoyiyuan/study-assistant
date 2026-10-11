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
        assertEquals(Path.of("%USERPROFILE%\\study"), c.studyDir());
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
        //   %USERPROFILE%\study → "D:示例 目录study"（反斜杠被吞掉）。
        // 后者仍然是个「合法路径」，Path.of 不报错，App 会安静地去一个不存在的目录找计划文件。
        // 所以这里刻意用带空格、带反斜杠的真实形状，而不是一个只有一段的目录名。
        Path f = dir.resolve("backslash.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("%USERPROFILE%\\study"));
        c.save();

        assertEquals(Path.of("%USERPROFILE%\\study"), AppConfig.load(f).studyDir());

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
}
