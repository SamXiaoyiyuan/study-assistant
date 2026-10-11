package com.xiaoyiyuan.study.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 把<b>真实档案</b>的迁移结果写到指定位置，供人工逐节通读（spec §4.6 第 3 步「展示完整 diff」）。
 *
 * <p>为什么必须有它、而且必须留在仓库里：脱敏样例再全，也不能替代「拿真实档案跑一遍、人眼看一遍」——
 * 真实档案里有 4 周手写周记录与 LaTeX，形状是样例写不出来的。但真实档案含个人信息，绝不能提交进仓库，
 * 所以本测试只在显式给出路径时才跑：
 *
 * <pre>
 * mvn -pl study-core test -Dtest=RealMigrationPreviewTest `
 *   "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md" `
 *   "-Dstudy.previewOut=C:\Users\&lt;你&gt;\AppData\Local\Temp\migrated-preview.md"
 * </pre>
 *
 * <p><b>它只读真实档案</b>：{@code plan()} 不写源文件、不建备份、不建归档，Preview 也写在别处。
 * 通读完成后删掉预览文件即可。
 *
 * <h2>为什么除了「写预览」还要有一条断言</h2>
 * 人工通读是权威，但它不是回归网：今天的结论「121 非空行里只有 24 行没进目标，且那 24 行全是
 * 分隔线 / 旧标题 / 形状合法改变的表格行」不会被任何测试记住。将来某个映射改动只破坏真实形状的
 * 内容（样例形状测不出来）时，测试全绿而用户的散文已经丢了。<b>这条断言就是那 24 行结论的可执行版本，
 * 且刻意只钉性质、不钉具体行</b>——不复制个人档案进仓库，档案改了也不用改测试。
 *
 * <p>判据：源文件里每一条非空行，要么<b>逐字</b>出现在目标里（按 {@code strip()} 后整行相等比较，
 * 不做子串匹配——子串口径会把「加了一列的新表格行」当成旧行还在，实测差距 12 条），
 * 要么属于以下三类<b>声明过的</b>缺席：
 * <ul>
 *   <li>{@code ---} 分隔线 —— 目标以空行分节，规格要求每个 {@code ## } 标题前有空行（见 Migrator）；</li>
 *   <li>标题行（{@code #} 开头）—— 旧标题与旧 {@code ## } 章节号整体换成新结构；</li>
 *   <li>表格行（{@code |} 开头）—— 表格被重新生成（例如底线表加了「目标」列）。
 *       表格行是<b>结构</b>，其内容由「旧行的每个单元格仍是新行的子串」这条更弱的性质守着，
 *       本测试不重复它。</li>
 * </ul>
 * <b>换言之：任何一行散文（非空、非表格、非标题、非分隔线）缺席，本测试就红。</b>
 * 这正是控制器与审查者手工核过、而当时没有任何测试记住的那条性质。
 */
@EnabledIfSystemProperty(named = "study.realFile", matches = ".+")
class RealMigrationPreviewTest {

    /**
     * 负向验证钩子之一：指向一个 UTF-8 文本文件（一行一条，空行忽略）时，只有文件里列出的行算
     * 「允许缺席」，其余缺席一律违规。
     *
     * <p>用来证明上面的断言不是空转——列出几条<b>真实存在</b>的源行，测试必须红。
     * 正常运行时该属性为空，测试行为完全不受影响；它只影响断言，不碰档案，也不碰迁移。
     *
     * <p>用文件而不是逗号分隔的 {@code -D} 值：真实档案的行里有中文、空格与全角符号，
     * 从命令行传一串行文本会被 shell 拆散（实测 exit 255），文件没有这个问题。
     */
    private static final String ALLOWED_OVERRIDE_PROPERTY = "study.previewAllowedAbsentFile";

    /**
     * 负向验证钩子之二：给出一条<b>真实存在</b>的源行原文时，断言用的目标文本会先把该行删掉，
     * 于是断言必须红。
     *
     * <p>这是「故意弄丢一行散文」的最直接版本：同一个判据、同一份真实档案，只把目标里的一行拿掉。
     * 它证明的不只是「运行时断言通过了」，而是「这条断言真的会为一行丢失的散文亮红」。
     * 正常运行时该属性为空。
     */
    private static final String DROP_LINE_PROPERTY = "study.previewDropLine";

    @Test
    void writePreviewForHumanReview(@TempDir Path dir) throws IOException {
        Path legacy = Path.of(System.getProperty("study.realFile"));
        MigrationPlan plan = new Migrator(dir).plan(legacy);   // 只读

        Path out = Path.of(System.getProperty("study.previewOut"));
        Files.writeString(out, plan.targetText(), StandardCharsets.UTF_8);

        assertTrue(Files.size(out) > 0, "预览文件不能是空的");
    }

    /**
     * 真实档案的散文一行都不许缺席（理由见类注释）。断言失败时会逐条列出违规行，
     * 直接告诉下一个人「哪一行散文没进新文件」。
     */
    @Test
    void noProseLineFromTheRealArchiveIsAbsentFromTheTarget(@TempDir Path dir) throws IOException {
        Path legacy = Path.of(System.getProperty("study.realFile"));
        MigrationPlan plan = new Migrator(dir).plan(legacy);   // 只读

        String targetText = plan.targetText();
        String drop = System.getProperty(DROP_LINE_PROPERTY);
        if (drop != null && !drop.isBlank()) {
            // 负向验证：把目标里的这一行删掉，再走完全相同的断言。
            targetText = targetText.replace(drop.strip() + "\n", "");
        }
        requireNoProseAbsent(plan.sourceText(), targetText);
    }

    /** 判据本体：源文件里不许有一行散文（非空、非表格、非标题、非分隔线）缺席于目标。 */
    private static void requireNoProseAbsent(String sourceText, String targetText) throws IOException {
        List<String> sourceLines = strippedLines(sourceText);
        List<String> targetLines = strippedLines(targetText);
        List<String> allowedOverride = allowedOverride();

        List<String> violations = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (String line : sourceLines) {
            if (targetLines.contains(line)) {
                continue;
            }
            absent.add(line);
            if (!isAllowedToBeAbsent(line, allowedOverride)) {
                violations.add(line);
            }
        }

        assertTrue(violations.isEmpty(),
                "真实档案里有 " + violations.size() + " 行散文没进目标（非表格、非标题、非分隔线）："
                        + violations + "；本档案共缺席 " + absent.size() + " 行");
        // 反向哨兵：若判据哪天被改成「什么都不算缺席」，上面那条会静默变成空转。真实档案的旧结构
        // 决定了缺席行必然存在（分隔线 + 旧标题 + 重生成的表格行），所以这里必须非空。
        assertFalse(absent.isEmpty(), "判据失效：真实档案的迁移不可能一行都不缺席（旧标题都被换掉了）");
    }

    /** {@code strip()} 后的非空行，保持原文档顺序（诊断信息要能直接对照原文找）。 */
    private static List<String> strippedLines(String text) {
        String withoutBom = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<String> lines = new ArrayList<>();
        for (String line : withoutBom.split("\n", -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    /** 允许缺席的三类（见类注释）；负向验证属性给定时以属性指向的那份清单为准。 */
    private static boolean isAllowedToBeAbsent(String line, List<String> allowedOverride) {
        if (allowedOverride != null) {
            return allowedOverride.contains(line);
        }
        return line.equals("---") || line.startsWith("#") || line.startsWith("|");
    }

    /** 负向验证模式下的「允许缺席」清单；未开启时返回 {@code null}（用内置的三类判据）。 */
    private static List<String> allowedOverride() throws IOException {
        String path = System.getProperty(ALLOWED_OVERRIDE_PROPERTY);
        if (path == null || path.isBlank()) {
            return null;
        }
        return strippedLines(Files.readString(Path.of(path), StandardCharsets.UTF_8));
    }
}
