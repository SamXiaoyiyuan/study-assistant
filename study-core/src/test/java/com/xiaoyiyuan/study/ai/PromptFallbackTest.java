package com.xiaoyiyuan.study.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xiaoyiyuan.study.prompt.PromptLibrary;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 提示词的「外部文件 + 内置回退」测试（计划 2 Task 6，spec §7.2 / §7.3 / §7.4 / §6.5）。
 *
 * <p>四组断言，每一组都能被一条具体的反向改动证伪：
 * <ol>
 *   <li><b>外部优先</b>：外部文件存在时逐字以它为准（改完立刻生效、不必重新编译），
 *       缺失 / 为空 / 读不动 / 非法 UTF-8 时逐字回退内置，且四种情况都不抛异常、不返回空串；</li>
 *   <li><b>内置回退确实随包发布</b>：直接从 classpath 资源字节读一遍，逐字比较
 *       {@code builtIn().load(...)}，并钉住 UTF-8 无 BOM + 纯 LF；</li>
 *   <li><b>五种模式与措辞约束是内容</b>：A/B/C/D/E 五个落点在提示词里可指认（各自的正文非空），
 *       10 条硬约束、顾问的措辞约束逐字在；8 个禁用词、3 个诊断性标签一个都不出现——</li>
 *   <li><b>架构边界</b>：{@code PromptLibrary} 必须住在 {@code ai} 包之外（它要读盘），
 *       而 {@code ai} 包的字节码里既没有文件写入能力、也没有对 {@code storage} 包的引用。</li>
 * </ol>
 *
 * <p>本测试<b>不碰真实数据目录</b>（{@code %USERPROFILE%\study} 只读）：
 * 外部提示词一律造在 {@link TempDir} 里，内置提示词从 classpath 读。
 */
class PromptFallbackTest {

    /** 界面禁用词（spec §8.2）。提示词是「会显示给用户看的内容」，一个都不许出现。 */
    private static final List<String> BANNED_WORDS =
            List.of("未完成", "逾期", "落后", "断签", "失败", "警告", "已放弃", "未坚持");

    /**
     * 诊断性标签（spec §6.5）。
     *
     * <p>注意这里断言的是<b>这些词本身不出现</b>，所以提示词里连「不许写『某病名』」
     * 这种举例也不行——提示词是用户要读、要改的文本，举例就等于把那几个词摆在他眼前。
     */
    private static final List<String> DIAGNOSTIC_WORDS = List.of("抑郁", "焦虑症", "中度");

    /** 五种模式在（角色 1）提示词里的落点标题（spec §7.3）。 */
    private static final List<String> MODE_HEADINGS =
            List.of("### A 建档", "### B 滚动", "### C 资料查找", "### D 决策咨询", "### E 答疑讲解");

    /** 每个模式标题下的正文至少这么长：只留一个标题不算「落点」。 */
    private static final int MIN_MODE_BODY_LENGTH = 20;

    // ------------------------------------------------------------------
    // 1. 外部文件优先 / 缺失回退：三种来源的完整覆盖
    // ------------------------------------------------------------------

    @Test
    void missingDirectory_usesBuiltIn_andDoesNotThrow(@TempDir Path temp) {
        Path missing = temp.resolve("没有这个目录");
        PromptLibrary lib = new PromptLibrary(missing);

        assertTrue(lib.isUsingBuiltIn(PromptRole.PLANNER), "目录不存在时角色 1 必须报告为内置默认");
        assertTrue(lib.isUsingBuiltIn(PromptRole.COUNSELOR), "目录不存在时角色 2 必须报告为内置默认");
        assertTrue(lib.load(PromptRole.PLANNER).contains("不制造压迫感"),
                "回退的必须是内置规划师提示词，实际：" + preview(lib.load(PromptRole.PLANNER)));
        assertTrue(lib.load(PromptRole.COUNSELOR).contains("可能相关"),
                "回退的必须是内置顾问提示词，实际：" + preview(lib.load(PromptRole.COUNSELOR)));
        assertFalse(Files.exists(missing), "读取提示词不得创建目录：" + missing);
    }

    @Test
    void existingFile_overridesBuiltIn_andTakesEffectImmediately(@TempDir Path temp) throws IOException {
        Path dir = Files.createDirectories(temp.resolve("提示词"));
        Path plannerFile = dir.resolve(PromptRole.PLANNER.fileName());
        Files.writeString(plannerFile, "只回一个字", StandardCharsets.UTF_8);

        PromptLibrary lib = new PromptLibrary(dir);
        assertEquals("只回一个字", lib.load(PromptRole.PLANNER).strip(),
                "外部文件存在时必须逐字以它为准，而不是内置内容");
        assertFalse(lib.isUsingBuiltIn(PromptRole.PLANNER), "用了外部文件就不能再报告为内置默认");

        // 改后立即生效：不重新编译、不重启、不缓存旧内容
        Files.writeString(plannerFile, "只回两个字", StandardCharsets.UTF_8);
        assertEquals("只回两个字", lib.load(PromptRole.PLANNER).strip(),
                "外部文件改完必须立刻生效（同一实例、无需重新编译）");

        // 单角色的外部文件不得影响另一个角色
        assertTrue(lib.isUsingBuiltIn(PromptRole.COUNSELOR), "只有角色 1 有外部文件，角色 2 仍应是内置");
        assertTrue(lib.load(PromptRole.COUNSELOR).contains("观察到"),
                "角色 2 未被外部覆盖时应拿到完整内置顾问提示词");
    }

    @Test
    void emptyBlankUnreadableOrInvalidUtf8File_fallsBackToBuiltIn_withoutThrowing(@TempDir Path temp)
            throws IOException {
        Path dir = Files.createDirectories(temp.resolve("提示词"));
        Path plannerFile = dir.resolve(PromptRole.PLANNER.fileName());

        // (a) 空文件（0 字节）
        Files.createFile(plannerFile);
        assertFallsBackToBuiltIn(new PromptLibrary(dir), PromptRole.PLANNER, "空文件");

        // (b) 只有空白字符
        Files.writeString(plannerFile, "   \n\t\n  \n", StandardCharsets.UTF_8);
        assertFallsBackToBuiltIn(new PromptLibrary(dir), PromptRole.PLANNER, "只有空白的文件");

        // (c) 非法 UTF-8：GBK 的「中」是 D6 D0，第二个字节不是 UTF-8 续字节，严格解码必须拒绝。
        //     硬编码字节而不用 Charset.forName("GBK")，免得测试自己依赖一件环境相关的事。
        Files.write(plannerFile, new byte[] { (byte) 0xD6, (byte) 0xD0 });
        assertFallsBackToBuiltIn(new PromptLibrary(dir), PromptRole.PLANNER, "非法 UTF-8（GBK 字节）的文件");

        // (d) 「读不动」：同名路径是个目录。目录里还有内容，避免空目录在个别文件系统上的边界行为。
        Files.delete(plannerFile);
        Files.createDirectories(plannerFile);
        Files.writeString(plannerFile.resolve("占位.txt"), "x", StandardCharsets.UTF_8);
        assertFallsBackToBuiltIn(new PromptLibrary(dir), PromptRole.PLANNER, "同名路径是目录（读不动）");

        // (e) 对照：同一目录下确实能读出外部文件，证明上面四条不是「因为整个目录都读不了」而侥幸过关
        Files.delete(plannerFile.resolve("占位.txt"));
        Files.delete(plannerFile);
        Files.writeString(plannerFile, "只回一个字", StandardCharsets.UTF_8);
        assertEquals("只回一个字", new PromptLibrary(dir).load(PromptRole.PLANNER).strip(),
                "外部文件恢复正常后必须重新以它为准");
    }

    @Test
    void builtInFactory_neverTouchesDisk_andIsAlwaysBuiltIn() {
        PromptLibrary lib = PromptLibrary.builtIn();

        for (PromptRole role : PromptRole.values()) {
            assertTrue(lib.isUsingBuiltIn(role), role + " 在 builtIn() 下必须是内置来源");
            assertFalse(lib.load(role).isBlank(), role + " 的内置提示词不得为空");
            assertEquals(PromptLibrary.builtIn().load(role), lib.load(role),
                    role + " 的内置内容必须每次都一致（不读盘、不受当前目录影响）");
        }
        // 传入 null 目录的语义 = 只用内置（与 builtIn() 等价），这是测试与回退的默认走法
        PromptLibrary nullDir = new PromptLibrary(null);
        assertTrue(nullDir.isUsingBuiltIn(PromptRole.PLANNER), "null 目录必须视为「没有外部文件」");
        assertEquals(lib.load(PromptRole.PLANNER), nullDir.load(PromptRole.PLANNER),
                "null 目录与 builtIn() 必须给出同一份内置提示词");
    }

    // ------------------------------------------------------------------
    // 2. 内置回退确实随包发布（不是「读不到就返回空串」）
    // ------------------------------------------------------------------

    @Test
    void builtInPrompts_areShippedAsClasspathResources_verbatimUtf8LfNoBom() throws IOException {
        for (PromptRole role : PromptRole.values()) {
            String resourcePath = "/prompts/" + role.fileName();
            URL resource = PromptLibrary.class.getResource(resourcePath);
            assertNotNull(resource,
                    "内置提示词必须真的随包发布（classpath 上找不到 " + resourcePath + "）");

            byte[] bytes;
            try (InputStream in = resource.openStream()) {
                bytes = in.readAllBytes();
            }
            assertTrue(bytes.length > 500,
                    resourcePath + " 太短（" + bytes.length + " 字节），不像一份能用的内置提示词");
            assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                            && (bytes[2] & 0xFF) == 0xBF,
                    resourcePath + " 不得带 UTF-8 BOM");
            for (int i = 0; i < bytes.length; i++) {
                assertFalse(bytes[i] == (byte) 0x0D,
                        resourcePath + " 必须是纯 LF：偏移 " + i + " 处出现了 CR");
            }

            String fromResource = new String(bytes, StandardCharsets.UTF_8);
            assertTrue(java.util.Arrays.equals(bytes, fromResource.getBytes(StandardCharsets.UTF_8)),
                    resourcePath + " 必须是合法 UTF-8（往返编码不一致说明解码时有字节被替换）");
            assertFalse(fromResource.isBlank(), resourcePath + " 不得是空白内容");

            // 关键断言：内置回退就是这份随包发布的资源本身，逐字一致
            assertEquals(fromResource, PromptLibrary.builtIn().load(role),
                    role + " 的内置回退必须来自随包发布的资源文件 " + resourcePath
                            + "，而不是别处（返回空串或另一份硬编码内容都不算）");
        }
    }

    // ------------------------------------------------------------------
    // 3. 内容：10 条硬约束、顾问措辞约束、五种模式、禁用词
    // ------------------------------------------------------------------

    @Test
    void builtInPlanner_containsAllTenHardConstraints_verbatim() {
        String planner = PromptLibrary.builtIn().load(PromptRole.PLANNER);

        int index = 0;
        for (String constraint : List.of("不评价过去", "信息不足", "空洞口号", "可支配时间", "可检验",
                "不制造压迫感", "可核实", "单源", "砍单", "长期目标中立")) {
            index++;
            assertTrue(planner.contains(constraint),
                    "内置规划师提示词必须逐字包含第 " + index + " 条硬约束的关键词「" + constraint + "」");
        }
        // 十条第 4 条的另一半：时间少时主动给「可舍弃项」
        assertTrue(planner.contains("可舍弃项"), "第 4/9 条：必须写明给出「可舍弃项」");
        assertTrue(planner.contains("验收标准"), "第 3 条：空洞口号必须展开到验收标准");
    }

    @Test
    void builtInCounselor_forbidsDiagnosticLabels_andRequiresHedgedWording() {
        String counselor = PromptLibrary.builtIn().load(PromptRole.COUNSELOR);

        assertTrue(counselor.contains("观察到"), "spec §6.5：必须逐字要求用「观察到」");
        assertTrue(counselor.contains("可能相关"), "spec §6.5：必须逐字要求用「可能相关」");
        assertTrue(counselor.contains("不替代专业"), "spec §6.5：必须写明不替代专业帮助");
        assertTrue(counselor.contains("不计算") && counselor.contains("分数"),
                "spec §6.5：必须写明不计算任何心理状态分数");
        for (String label : DIAGNOSTIC_WORDS) {
            assertFalse(counselor.contains(label),
                    "顾问提示词本身不得出现诊断性标签「" + label + "」（提示词是用户会读到的文本）");
        }
    }

    @Test
    void builtIn_isNotFramedAsExamOnlyPlanner_andKeepsLongTermGoalsNeutral() {
        String planner = PromptLibrary.builtIn().load(PromptRole.PLANNER);

        assertFalse(planner.contains("考研规划师"),
                "角色定位是「学习与生活规划师」，不得写成考研规划师（spec §7.2）");
        assertTrue(planner.contains("学习与生活规划师"), "提示词必须点明自己的角色名");
        assertTrue(planner.contains("长期目标中立"), "第 10 条硬约束必须逐字在位");
        assertTrue(planner.contains("不得劝说"),
                "第 10 条：长线目标暂缓时不得劝说继续或放弃");
        assertTrue(planner.contains("如果启用") && planner.contains("如果取消"),
                "第 10 条：只呈现「如果启用 / 如果取消」两条路径的差别");
    }

    @Test
    void allFiveModes_areReachable_fromEachOfTheThreeSources(@TempDir Path temp) throws IOException {
        String builtIn = PromptLibrary.builtIn().load(PromptRole.PLANNER);
        assertAllFiveModesReachable(builtIn, "内置来源");

        // 缺失来源：目录不存在 → 回退内置 → 五个模式仍在
        String fromMissing = new PromptLibrary(temp.resolve("不存在")).load(PromptRole.PLANNER);
        assertAllFiveModesReachable(fromMissing, "缺失来源（回退内置）");
        assertEquals(builtIn, fromMissing, "缺失来源必须逐字等于内置来源");

        // 外部来源：用户自己写的五个模式段落逐字生效（这是「改文件即可改行为」的可执行化）
        Path dir = Files.createDirectories(temp.resolve("提示词"));
        StringBuilder external = new StringBuilder();
        for (String heading : MODE_HEADINGS) {
            external.append(heading).append("（外部版本）\n")
                    .append("这是外部文件里为").append(heading.substring(4))
                    .append("写的说明，长度足够构成一段真正的正文。\n\n");
        }
        Files.writeString(dir.resolve(PromptRole.PLANNER.fileName()), external.toString(),
                StandardCharsets.UTF_8);
        PromptLibrary lib = new PromptLibrary(dir);
        String fromExternal = lib.load(PromptRole.PLANNER);
        assertAllFiveModesReachable(fromExternal, "外部来源");
        assertFalse(lib.isUsingBuiltIn(PromptRole.PLANNER), "有外部文件时不得报告为内置");
        assertFalse(fromExternal.contains("不制造压迫感"),
                "外部文件必须整体取代内置内容，而不是与内置拼接");
    }

    @Test
    void searchPromptTemplate_containsTopicAndKeywords_andNoNetwork() throws Exception {
        String topic = "数据结构";
        String template = PromptLibrary.searchPromptTemplate(topic);

        assertTrue(template.contains(topic), "搜索模板必须带上用户给的主题，实际：" + preview(template));
        assertTrue(template.contains("搜索关键词"), "模板必须有一段「搜索关键词」候选");
        assertTrue(template.contains("提问模板"), "模板必须有一段「提问模板」");

        // 关键词候选行：`1. ` / `2. ` … 这种编号行，至少 3 条（spec §7.3 的 C 资料查找）
        List<String> keywordLines = new ArrayList<>();
        for (String line : template.lines().toList()) {
            if (line.matches("^\\s*\\d+[.、]\\s*\\S.*")) {
                keywordLines.add(line.strip());
            }
        }
        assertTrue(keywordLines.size() >= 3,
                "搜索关键词候选至少要有 3 行，实际 " + keywordLines.size() + " 行：" + keywordLines);
        for (String expected : List.of("网课", "教材", "真题")) {
            assertTrue(template.contains(expected), "关键词候选应覆盖「" + expected + "」这一类");
        }
        assertTrue(template.contains("出处") || template.contains("可溯源"),
                "提问模板必须要求给出可核实的出处（spec §7.4 第 7 条：资源可核实）");

        // 方法签名里不许出现任何网络 / IO 类型：它只生成文本，不发请求、不读文件。
        // 断言扫描的是**所有**叫这个名字的方法：偷偷加一个 (String, URL) 的重载就等于开了口子，
        // 只钉住 (String) 那一个会漏掉它。
        List<Method> overloads = new ArrayList<>();
        for (Method candidate : PromptLibrary.class.getDeclaredMethods()) {
            if (candidate.getName().equals("searchPromptTemplate")) {
                overloads.add(candidate);
            }
        }
        assertEquals(1, overloads.size(),
                "只允许一个 searchPromptTemplate（多一个带网络/IO 类型的重载就是开了口子）：" + overloads);
        Method method = overloads.get(0);
        assertTrue(Modifier.isStatic(method.getModifiers()), "searchPromptTemplate 必须是静态方法");
        assertEquals(String.class, method.getReturnType(), "searchPromptTemplate 必须返回 String");
        assertEquals(List.of(String.class), List.of(method.getParameterTypes()),
                "searchPromptTemplate 只接受一个 String 参数");
        for (Class<?> type : List.of(method.getReturnType(), method.getParameterTypes()[0])) {
            String name = type.getName();
            assertFalse(name.startsWith("java.net.") || name.startsWith("java.io.")
                            || name.startsWith("java.nio.") || type == byte[].class,
                    "searchPromptTemplate 的签名不得出现网络/IO 类型：" + name);
        }
        assertFalse(template.contains("http://") || template.contains("https://"),
                "搜索模板只是文本，不得内嵌任何要访问的地址");

        // 主题的空白与非法输入
        assertTrue(PromptLibrary.searchPromptTemplate("  电路分析  ").contains("电路分析"),
                "主题应当去首尾空白后使用");
        assertThrows(IllegalArgumentException.class, () -> PromptLibrary.searchPromptTemplate(null),
                "null 主题应被拒绝");
        assertThrows(IllegalArgumentException.class, () -> PromptLibrary.searchPromptTemplate("   "),
                "空白主题应被拒绝");
    }

    @Test
    void promptContent_andSearchTemplate_carryNoBannedWordsAndNoDiagnosticLabels() {
        List<String> sources = List.of(
                "内置规划师提示词",
                "内置顾问提示词",
                "资料查找模板");
        List<String> contents = List.of(
                PromptLibrary.builtIn().load(PromptRole.PLANNER),
                PromptLibrary.builtIn().load(PromptRole.COUNSELOR),
                PromptLibrary.searchPromptTemplate("408 真题"));
        // 正面锚点：没有这几条，内容为空串时下面「不含禁用词」会全部空转通过
        List<String> positiveAnchors = List.of("学习与生活规划师", "观察到的模式", "搜索关键词");

        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i);
            String source = sources.get(i);
            assertFalse(content.isBlank(), source + " 不得为空（空内容会让下面的禁用词断言空转）");
            assertTrue(content.length() >= 200,
                    source + " 太短（" + content.length() + " 字），不像一份真在用的提示词文本");
            assertTrue(content.contains(positiveAnchors.get(i)),
                    source + " 缺少本来源必备的正面锚点「" + positiveAnchors.get(i) + "」");
            for (String banned : BANNED_WORDS) {
                assertFalse(content.contains(banned),
                        source + " 不得含界面禁用词「" + banned + "」");
            }
            for (String label : DIAGNOSTIC_WORDS) {
                assertFalse(content.contains(label),
                        source + " 不得含诊断性标签「" + label + "」");
            }
            assertFalse(content.contains("心理健康得分"),
                    source + " 不得出现「心理健康得分」这类量化结论");
        }
    }

    // ------------------------------------------------------------------
    // 4. 角色枚举与架构边界
    // ------------------------------------------------------------------

    @Test
    void promptRole_matchesSpecPaths() {
        assertEquals("学习与生活规划师.md", PromptRole.PLANNER.fileName());
        assertEquals("生活与心理顾问.md", PromptRole.COUNSELOR.fileName());
        assertEquals(2, PromptRole.values().length, "只有两个角色（spec §7.2 的表格两行）");
        assertEquals(PromptRole.PLANNER, PromptRole.valueOf("PLANNER"));
        assertEquals(PromptRole.COUNSELOR, PromptRole.valueOf("COUNSELOR"));
        assertTrue(PromptRole.PLANNER.displayName().contains("学习与生活规划师"),
                "角色名要能被界面直接展示");
        assertFalse(PromptRole.PLANNER.displayName().contains("考研"),
                "角色名不得写成考研规划师");
    }

    @Test
    void promptLibrary_livesOutsideTheAiPackage_andAiPackageKeepsNoFileAccessNorStorageDependency()
            throws Exception {
        // 裁定（控制器）：ai 包的字节码里不得出现任何 java/nio/file 引用，而 PromptLibrary 要读盘，
        // 所以它必须住在 ai 包之外。它一旦被搬回 ai 包，这条断言当场变红。
        assertEquals("com.xiaoyiyuan.study.prompt", PromptLibrary.class.getPackageName(),
                "PromptLibrary 必须住在 ai 包之外（ai 包在编译期就拿不到写文件/读盘的能力）");
        assertNotEquals(PromptRole.class.getPackageName(), PromptLibrary.class.getPackageName());

        Path mainClasses = Path.of(PromptLibrary.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        Path aiClasses = mainClasses.resolve("com/xiaoyiyuan/study/ai");
        assertTrue(Files.isDirectory(aiClasses), "找不到 ai 包的 class 目录：" + aiClasses);

        List<Path> classFiles;
        try (Stream<Path> entries = Files.list(aiClasses)) {
            classFiles = entries.filter(e -> e.getFileName().toString().endsWith(".class")).toList();
        }
        Set<String> names = classFiles.stream().map(e -> e.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(classFiles.size() >= 8,
                "ai 包的 class 个数不对（扫不到东西时这条护栏就是空转的）：" + names);
        assertTrue(names.contains("PromptRole.class"), "新加的角色枚举应当在 ai 包里：" + names);

        Set<String> fileAccessOffenders = new LinkedHashSet<>();
        Set<String> storageOffenders = new LinkedHashSet<>();
        int scannedConstants = 0;
        for (Path classFile : classFiles) {
            List<String> constants = constantPoolUtf8(Files.readAllBytes(classFile));
            scannedConstants += constants.size();
            for (String constant : constants) {
                String hit = forbiddenFileReference(constant);
                if (hit != null) {
                    fileAccessOffenders.add(classFile.getFileName() + " → " + hit);
                }
                if (constant.contains("com/xiaoyiyuan/study/storage")) {
                    storageOffenders.add(classFile.getFileName() + " → " + constant);
                }
            }
        }
        assertTrue(scannedConstants > 100,
                "常量池里一条字符串都没扫到（解析器坏了会变成空转的绿灯）：" + scannedConstants);
        assertTrue(fileAccessOffenders.isEmpty(),
                "ai 包的字节码里不得出现读/写文件的引用（spec §3.3 / §7.5）；命中：" + fileAccessOffenders);
        assertTrue(storageOffenders.isEmpty(),
                "ai 包不得依赖 storage 包（spec §7.5：AI 无文件写入权）；命中：" + storageOffenders);

        // 阳性对照：同一个解析器必须能读出 PromptLibrary 真的引用了 storage.TextFileCodec，
        // 否则上面那条「ai 包里没有 storage」可能只是因为解析器什么都没读出来。
        List<String> promptLibraryConstants = constantPoolUtf8(Files.readAllBytes(
                mainClasses.resolve("com/xiaoyiyuan/study/prompt/PromptLibrary.class")));
        assertTrue(promptLibraryConstants.contains("com/xiaoyiyuan/study/storage/TextFileCodec"),
                "阳性对照失败：解析器读不出 PromptLibrary 对 TextFileCodec 的引用，"
                        + "这条护栏就是空转的");
        assertTrue(promptLibraryConstants.stream().anyMatch(c -> c.startsWith("java/nio/file/")),
                "阳性对照失败：PromptLibrary 确实在用 java.nio.file（它读盘）");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 断言「回退到内置」：报告为内置、内容逐字等于内置、且非空。 */
    private static void assertFallsBackToBuiltIn(PromptLibrary lib, PromptRole role, String caseName) {
        String builtIn = PromptLibrary.builtIn().load(role);
        assertTrue(lib.isUsingBuiltIn(role), caseName + "：应当报告为「正在用内置默认」");
        assertEquals(builtIn, lib.load(role), caseName + "：应当逐字回退到内置提示词，而不是空串或半截内容");
        assertFalse(lib.load(role).isBlank(), caseName + "：回退结果不得为空");
    }

    /** 五个模式在给出的提示词里都能指认到，而且每个模式都真的有正文。 */
    private static void assertAllFiveModesReachable(String plannerPrompt, String source) {
        for (String heading : MODE_HEADINGS) {
            // 必须锚在行首：只判 contains("### E 答疑讲解") 会被 "#### E 答疑讲解" 蒙过去
            //（四个 # 里含三个 #），那样「把模式标题改坏」就不会变红。
            assertTrue(startsALine(plannerPrompt, heading),
                    source + " 必须覆盖五种模式之一（且它必须是一个行首标题）：" + heading);
        }
        for (String heading : MODE_HEADINGS) {
            int start = headingLineStart(plannerPrompt, heading) + heading.length();
            int next = plannerPrompt.indexOf("\n### ", start);
            String body = next < 0 ? plannerPrompt.substring(start)
                    : plannerPrompt.substring(start, next);
            assertTrue(body.strip().length() >= MIN_MODE_BODY_LENGTH,
                    source + " 的「" + heading + "」只留了个标题（正文 " + body.strip().length()
                            + " 字，至少要有 " + MIN_MODE_BODY_LENGTH + " 字）");
        }
    }

    /** 给定文本里是否有一行以该标题开头（文件开头也算行首）。 */
    private static boolean startsALine(String text, String heading) {
        return text.startsWith(heading) || text.contains("\n" + heading);
    }

    /** 该标题所在行的起始下标（文件开头算行首）。 */
    private static int headingLineStart(String text, String heading) {
        return text.startsWith(heading) ? 0 : text.indexOf("\n" + heading) + 1;
    }

    private static String preview(String text) {
        String flat = text.replace("\n", "\\n");
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    /** 解析 class 文件的常量池，取出全部 {@code CONSTANT_Utf8} 字符串。 */
    private static List<String> constantPoolUtf8(byte[] classBytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            assertEquals(0xCAFEBABE, in.readInt(), "class 文件的魔数不符");
            in.readUnsignedShort();
            in.readUnsignedShort();
            int poolCount = in.readUnsignedShort();
            List<String> constants = new ArrayList<>();
            for (int index = 1; index < poolCount; index++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> constants.add(in.readUTF());
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        index++;
                    }
                    default -> throw new IOException("未知的常量池 tag " + tag + "（下标 " + index + "）");
                }
            }
            return constants;
        }
    }

    /** 与 {@code AiClientTest} 同口径的「写文件 / 执行进程」判定。 */
    private static String forbiddenFileReference(String constant) {
        for (String prefix : List.of("java/nio/file/", "java/io/File")) {
            if (constant.startsWith(prefix) || constant.contains("L" + prefix)
                    || constant.contains("[" + prefix)) {
                return prefix;
            }
        }
        for (String exact : List.of("java/lang/Runtime", "java/lang/ProcessBuilder",
                "java/lang/Process")) {
            if (constant.equals(exact) || constant.contains("L" + exact + ";")
                    || constant.contains(")" + exact)) {
                return exact;
            }
        }
        return null;
    }
}
