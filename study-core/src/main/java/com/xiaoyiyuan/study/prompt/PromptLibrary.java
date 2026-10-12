package com.xiaoyiyuan.study.prompt;

import com.xiaoyiyuan.study.ai.PromptRole;
import com.xiaoyiyuan.study.storage.TextFileCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 提示词库：外部文件优先，缺失则回退内置（spec §7.2）。
 *
 * <p><b>为什么这个类不在 {@code ai} 包里</b>（计划 2 Task 6 的授权偏离，控制器裁定）：
 * {@code ai} 包受一条字节码护栏约束——它的 class 里不得出现任何 {@code java/nio/file/*}
 * （spec §7.5：AI 客户端在编译期就拿不到文件读写能力）。而「外部提示词文件优先」这件事必须读盘，
 * 于是两边无法共存。取舍是：{@link PromptRole}（纯值，{@code ai} 包需要它）留在 {@code ai}，
 * 读盘的部分搬到这里，依赖方向为 {@code prompt → ai}。公开签名与计划 2 的声明一致
 * （{@code PromptLibrary(Path)}、{@code builtIn()}、{@code load(PromptRole)}、
 * {@code isUsingBuiltIn(PromptRole)}、{@code searchPromptTemplate(String)}），
 * Task 7/9 可以照计划调用，只是要多一行 import。
 *
 * <p><b>「不崩溃」是本类的第一性质</b>（spec §7.2 的原话是 App 不崩溃）：外部文件不存在、
 * 是空文件、只有空白、不是合法 UTF-8、是目录、被别的程序锁住……任何一种情况都<b>回退内置</b>，
 * 不抛异常、不返回空串。提示词是「内容」而不是「逻辑」，内容坏了不该让 AI 功能整块不可用。
 *
 * <p><b>不做缓存</b>：spec §7.2 要求「文件存在 → 改后立即生效，无需重新编译」。每次调用都重新读一遍，
 * 用户存完文件下一次发送就生效；本类只在界面动作里被调用，这点开销无关紧要。
 *
 * <p><b>不是 final</b>：{@link #load(PromptRole)} 可以被替身覆盖，测试要能造出「提示词是空白」
 * 这类边界输入来钉住 {@code EmotionAnalysis.build} 的运行时自检（那些自检的逻辑在调用方，
 * 不该为了能测它而把生产类改成接口）。
 *
 * <p>本类只读，不写。外部提示词文件的创建/编辑由用户自己（或界面用系统默认程序打开）完成。
 */
public class PromptLibrary {

    /** 内置回退在 classpath 上的目录（spec §7.2：{@code study-core\src\main\resources\prompts\}）。 */
    private static final String BUILT_IN_RESOURCE_DIR = "/prompts/";

    /**
     * 外部提示词目录；{@code null} 表示「只用内置、完全不读盘」。
     *
     * <p>目录允许不存在——它就是我们期望的常态（用户还没建过提示词文件）。
     */
    private final Path promptDir;

    /**
     * @param promptDir 外部提示词目录（通常是 {@code <studyDir>\提示词}），<b>可以不存在</b>；
     *                  传 {@code null} 等价于 {@link #builtIn()}：只用内置，不碰磁盘
     */
    public PromptLibrary(Path promptDir) {
        this.promptDir = promptDir;
    }

    /**
     * 只用内置资源的提示词库：<b>不读盘、不建目录、不受当前工作目录影响</b>。
     *
     * <p>测试与「只想拿默认提示词」的调用方走它（例如设置页要展示内置版本长什么样）。
     */
    public static PromptLibrary builtIn() {
        return new PromptLibrary(null);
    }

    /**
     * 取某个角色的提示词：外部文件优先，缺失/为空/读不动/非法 UTF-8 时回退内置。
     *
     * <p>返回的是<b>原文</b>（不 trim、不改换行符）：提示词是用户自己写的内容，
     * 多余的空行也是他的排版意图。
     *
     * @return 非空提示词；外部不可用时是内置回退
     * @throws IllegalStateException 内置资源本身缺失或读不出来——那说明打包不完整，
     *                               属于程序错误，必须当场响亮地失败，而不是给模型发一份空提示词
     */
    public String load(PromptRole role) {
        String external = readExternalOrNull(role);
        return external != null ? external : builtInContent(role);
    }

    /**
     * 当前这个角色是否在用内置默认（界面据此提示「现在用的是内置默认」，spec §7.2）。
     *
     * <p>判据与 {@link #load} 完全同一份：只要 {@code load} 会回退内置，这里就是 {@code true}，
     * 两者因此不可能互相矛盾。代价是可能多读一次文件（这个方法本来也只被界面调用）。
     */
    public boolean isUsingBuiltIn(PromptRole role) {
        return readExternalOrNull(role) == null;
    }

    /**
     * 生成「搜索关键词候选 + 提问模板」（spec §7.3 的 C 资料查找；半自动搜索）。
     *
     * <p><b>它只生成文本</b>：不联网、不读文件、不写文件——签名里连一个网络/IO 类型都没有，
     * 由 {@code PromptFallbackTest} 用反射钉住。用户拿着这份线索自己去搜，再把结果贴回来，
     * AI 才据以整理成可溯源的清单（spec §7.4 第 7 条：资源可核实）。
     *
     * @param topic 要查的主题（例如「数据结构」）
     * @throws IllegalArgumentException 主题为 {@code null} 或全是空白：没有主题就搜不出东西，
     *                                  与其给出一份带空格的模板，不如当场说清楚
     */
    public static String searchPromptTemplate(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "资料查找需要一个明确的主题（例如「数据结构」或「目标院校的复试线」）；收到：" + topic);
        }
        String t = topic.strip();
        return """
                资料查找（C）：%s

                这一步是半自动的：下面这些线索由 App 生成，**它不会替你联网**。请把它们逐条复制到
                搜索引擎里，再把搜到的结果（页面标题、地址、书名与版本）整段贴回对话，
                由我把结果整理成一份可溯源的资料清单。

                ## 搜索关键词候选（一条太宽就再加一个限定词）
                1. %s 网课 推荐 评价
                2. %s 教材 版本 习题 答案
                3. %s 历年真题 解析
                4. %s 目标院校 分数线 招生人数 官网
                5. %s 学习路线 先修知识 顺序

                ## 提问模板（把这段连同搜到的结果一起贴回来）
                请把下面这些搜到的内容整理成一份资料清单，每一条都写清楚：
                1. 名称：属于网课 / 教材 / 真题集 / 院校信息里的哪一类
                2. 适用阶段：先修、打基础、强化、还是接近考试的阶段
                3. 获取方式：书名 + 版本 + 出版社，或可打开的页面地址
                4. 出处：我是从哪个页面或哪本书看到的，便于我自己核对
                5. 你确实核实过的信息与还没核实的要分开写；不确定的标注「待核实」，不要补成确定的数字。

                主题：%s
                """.formatted(t, t, t, t, t, t, t);
    }

    /**
     * 读外部提示词；「不该用外部内容」的一切情形都返回 {@code null}（＝回退内置）。
     *
     * <p>返回 {@code null} 的四种情形，各自都对应一条测试：
     * <ul>
     *   <li>目录为 {@code null}（{@link #builtIn()}）、目录/文件不存在；</li>
     *   <li>同名路径不是普通文件（目录、设备名……）：{@code isRegularFile} 直接挡掉，
     *       不必等 {@code readAllBytes} 抛异常；</li>
     *   <li>{@link TextFileCodec} 严格解码失败（非法 UTF-8 → {@code NotUtf8Exception}，
     *       它继承 {@code IOException}）或任何读盘失败（被独占锁住、权限不足……）；</li>
     *   <li>内容只有空白：一份空提示词比内置提示词坏得多——模型会失去全部行为约束，
     *       而用户多半只是把文件清空了还没来得及写，这时静默回退是他想要的。</li>
     * </ul>
     */
    private String readExternalOrNull(PromptRole role) {
        if (promptDir == null) {
            return null;
        }
        Path file = promptDir.resolve(role.fileName());
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String content;
        try {
            content = TextFileCodec.read(file).content();
        } catch (IOException e) {
            // 非法 UTF-8 与所有读盘失败一视同仁：视为「这个文件不能用」，回退内置。
            // 不把异常消息暴露到界面上——提示词读不了不该打断用户正在做的事。
            return null;
        } catch (SecurityException e) {
            // 安全策略/ACL 拒绝访问时抛的是 RuntimeException，同样不能让界面炸掉
            return null;
        }
        return content.isBlank() ? null : content;
    }

    /**
     * 读内置资源（{@code classpath:/prompts/<文件名>}）。
     *
     * <p>用 {@code getResourceAsStream} 而不是文件 API：资源在打包后位于 jar 内，
     * 只有 classpath 这一条路在读它时不变。返回的字节按 UTF-8 解码；「内置文件是合法 UTF-8、
     * 纯 LF、无 BOM」由 {@code PromptFallbackTest} 直接读资源字节钉住。
     */
    private static String builtInContent(PromptRole role) {
        String resourcePath = BUILT_IN_RESOURCE_DIR + role.fileName();
        try (InputStream in = PromptLibrary.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("内置提示词资源缺失：" + resourcePath
                        + "（study-core 打包不完整；内置回退是「文件坏了也不崩」的底线，不能在打包层丢掉）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("内置提示词资源读不出来：" + resourcePath, e);
        }
    }
}
