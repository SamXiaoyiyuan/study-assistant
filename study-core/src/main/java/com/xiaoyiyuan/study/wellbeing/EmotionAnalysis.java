package com.xiaoyiyuan.study.wellbeing;

import com.xiaoyiyuan.study.ai.AiMessage;
import com.xiaoyiyuan.study.ai.PendingRequest;
import com.xiaoyiyuan.study.ai.PromptRole;
import com.xiaoyiyuan.study.prompt.PromptLibrary;

import java.util.List;
import java.util.Objects;

/**
 * 情绪 AI 分析的组装层（spec §6.4：「时间窗 → 请求 → 三段式输出」）。
 *
 * <h2>它只组装，不发送、不落盘</h2>
 * {@link #build(PromptRole, AnalysisWindow, EmotionLog, PromptLibrary)} 的产物是一份
 * {@link PendingRequest}——「将要发送、但还没有获得确认」的请求。它<b>不发网络请求</b>
 * （那是 {@code ai.ConfirmingAiSession} 与确认门的事），也<b>不写任何文件</b>
 * （spec §6.4：分析结果不写回情绪记录文件，文件只装用户自己的记录）。
 * 本类的公开签名里连一个 {@code Path} 都没有，由 {@code EmotionAnalysisTest} 用反射钉住。
 *
 * <h2>为什么读提示词要读盘的类在 {@code prompt} 包</h2>
 * {@code ai} 包受一条字节码护栏约束（其 class 不得出现任何 {@code java/nio/file/*}），
 * 所以 {@link PromptLibrary} 住在 {@code prompt} 包、依赖方向是 {@code prompt → ai}。
 * 本类住在 {@code wellbeing} 包（它消费 {@link EmotionLog}），于是它可以自由地
 * 「先读提示词、再把纯字符串交给 {@code ai}」。这正是控制器对 Task 9 的硬性要求：
 * <b>{@code PromptLibrary.load(role)} 必须落在 {@code messages[0]}</b>。
 *
 * <h2>发送内容 = 用户记录原文</h2>
 * 用户要先看到自己写的东西才会放心（spec §6.4 第 2 步：完整原文预览）。
 * 所以 {@link #renderEntries(List)} 逐字回放用户的条目（含日期标题、情绪、强度、各可选字段、
 * 以及那些本 App 不认识的 {@code - } 行），<b>不做摘要、不截断、不做任何脱敏</b>。
 *
 * <h2>措辞：约束在提示词，解析层如实呈现</h2>
 * 「不出现诊断性标签、一律用『观察到』『可能相关』」这条约束由<b>提示词</b>承担
 * （内置提示词里逐条写着）。{@link Result#parse} <b>不做「洗白」</b>：模型若违规写出
 * 「抑郁」这类词，解析结果照样带着它，由界面标注「模型没有遵守措辞约束」。
 * 静默替换会同时毁掉两件事：用户看不到模型真的说了什么，以及我们再也发现不了提示词失效。
 */
public final class EmotionAnalysis {

    /**
     * 危机命中时保留的中性资源提示（spec §6.5）。
     *
     * <p>它<b>不是</b>红色警报、不是弹窗、不追问细节、不评判，也不阻断任何事——
     * 命中危机关键词时 AI 分析照常提供（{@link #build} 照常返回请求）。
     *
     * <p>{@code 120} 与 {@code 110} 两条渠道不可删除（spec §12 / global-constraints）
     * 已由 {@code CrisisResource} 的删除保护守着；这里只是把它们再说一遍，
     * 且刻意<b>不</b>写「请拨打热线」这类会挤掉分析的措辞。
     */
    private static final String CRISIS_NOTICE =
            "这段时间的记录里出现了本地关键词表匹配到的表达。这里有一些可以直接打电话的渠道，"
                    + "它们比我更管用：120（急救）、110（报警），以及你能联系到的学校心理健康中心。"
                    + "我不替代专业帮助，也不会因此停下——下面照常是我观察到的东西。";

    /** 三段的标题（逐字，全局唯一定义）；解析与界面渲染都从这里取。 */
    private static final String HEADING_OBSERVATIONS = "观察到的模式";
    private static final String HEADING_FACTORS = "可能的影响因素";
    private static final String HEADING_SUGGESTIONS = "小建议";

    /** 发给模型的固定指令：三段式与措辞约束在提示词里，这里只说「按提示词来」。 */
    private static final String TASK_INSTRUCTION =
            "请按系统提示词里的三段式与措辞要求分析这段时间的记录：\n"
                    + "1. " + HEADING_OBSERVATIONS + "\n"
                    + "2. " + HEADING_FACTORS + "\n"
                    + "3. " + HEADING_SUGGESTIONS + "（2~3 条，小到今天就能做）";

    private EmotionAnalysis() {
    }

    /**
     * 组装「将要发送的完整请求」：{: system 提示词, user 记录原文 + 指令}，交给确认门。
     *
     * <p><b>不发送任何网络请求</b>（spec §6.4 第 3 步：用户点「发送并分析」之前不发生任何网络
     * 请求），也<b>不写任何文件</b>。返回的 {@link PendingRequest#promptText()} 就是要逐字展示给
     * 用户的那一份。
     *
     * <p>用内置提示词（不读盘）。需要外部提示词（{@code <studyDir>\提示词\*.md} 优先）时用
     * {@link #build(PromptRole, AnalysisWindow, EmotionLog, PromptLibrary)}；
     * 两处的组装逻辑只有一份，所以「预览里有没有提示词」不会两处各答一次。
     *
     * @param role   角色（界面用 {@link PromptRole#COUNSELOR}）
     * @param window 用户选定的时间窗
     * @param log    已解析的情绪记录
     * @throws IllegalArgumentException 任一参数为 {@code null}
     */
    public static PendingRequest build(PromptRole role, AnalysisWindow window, EmotionLog log) {
        return build(role, window, log, PromptLibrary.builtIn());
    }

    /**
     * 组装「将要发送的完整请求」，提示词由调用方提供的提示词库给出（外部文件优先、内置回退）。
     *
     * <p>装配顺序是刻意的，且只有这一处：
     * <ol>
     *   <li>{@code messages[0]} = {@code library.load(role)} —— <b>提示词必须在第一格</b>，
     *       OpenAI 兼容协议里 {@code system} 消息承担全部行为约束（三段式、措辞约束）；
     *       丢掉它就等于让模型自由发挥，而用户看到的预览里还会是一堆看起来很正常的记录；</li>
     *   <li>{@code messages[1]} = 时间窗 + 条数 + <b>用户记录原文</b> + 指令（只装窗口内的条目）；</li>
     *   <li>{@code promptText} = {@link PendingRequest#joinContents(List)}
     *       —— 与确认门自己算的那一份<b>同一个出口</b>，所以「预览 == 实发」不是靠两次实现对齐，
     *       而是靠只有一次实现；</li>
     *   <li>自检：{@code promptText} 必须含提示词的第一句。这一条在错误实现下会当场抛错，
     *       而不是静默发出一份没有约束的请求。</li>
     * </ol>
     *
     * @param role    角色；提示词按它取（{@code PLANNER} 与 {@code COUNSELOR} 的内容不同）
     * @param window  用户选定的时间窗；窗口外的条目<b>一个字节都不会进入请求</b>
     * @param log     已解析的情绪记录
     * @param library 提示词库（外部目录优先、缺失回退内置）
     * @throws IllegalArgumentException 任一参数为 {@code null}，或组装结果里没有提示词
     */
    public static PendingRequest build(PromptRole role, AnalysisWindow window, EmotionLog log,
                                       PromptLibrary library) {
        Objects.requireNonNull(role, "role（要按哪个角色取提示词）");
        Objects.requireNonNull(window, "window（只发用户选定的那一窗）");
        Objects.requireNonNull(log, "log（分析的材料是用户的情绪记录）");
        Objects.requireNonNull(library, "library（提示词库；只用内置时传 PromptLibrary.builtIn()）");

        List<EmotionEntry> selected = window.select(log);
        String prompt = library.load(role);

        List<AiMessage> messages = List.of(
                new AiMessage("system", prompt),
                new AiMessage("user", userMessage(window, selected)));

        PendingRequest pending = PendingRequest.of(role, messages, PendingRequest.joinContents(messages));

        // 自检（controller 对 Task 9 的硬性要求）：预览里必须真的有提示词。
        // PendingRequest 的契约只保证「预览 == 实发」——上面若漏了 messages[0]，
        // 预览与实发会「一致地」丢掉提示词，Task 7 的全部断言照样绿。
        String anchor = promptAnchor(prompt);
        if (anchor.isEmpty() || !pending.promptText().contains(anchor)) {
            throw new IllegalStateException(
                    "组装出来的待发送文本里没有提示词（" + role.displayName() + "）："
                            + "预览 == 实发这条契约管不了「提示词有没有被放进去」，所以在这里当场拦下。"
                            + "提示词锚点 = `" + anchor + "`。");
        }
        return pending;
    }

    /**
     * 一次分析的完整产物：待发送请求 + 危机判定 + 中性资源提示。
     *
     * <p>为什么把危机判定跟请求放在一起返回，而不是让计划 3 的界面自己再扫一遍：判定要在
     * 「本次真正会发送的那份原文」上做（只判窗口内的条目），而那份原文只有本类的
     * {@link #renderEntries(List)} 一种算法。让界面各算一次，就会出现「面板判定的文本」
     * 与「实际发送的文本」悄悄分岔——分岔之后漏报是静默的。
     *
     * <p><b>三段式结果不在这里</b>：它来自模型的回复，而回复要等用户确认并请求返回之后才有
     * （{@link Result#parse} 就是给那一刻用的）。本记录描述的是「发送<b>之前</b>」的状态。
     *
     * <p><b>{@code request} 与 {@code crisisNotice} 是并列关系，不是替代关系</b>：
     * 命中危机关键词时分析照常产出（spec §6.5 的关键决定：不锁定、不阻断）。
     *
     * @param request        将要发送、等待用户确认的请求（<b>还没有发出去</b>）
     * @param crisisTriggered 本地关键词表是否命中（判定只在本地做，spec §6.5）
     * @param crisisNotice   命中时的中性资源提示（含 120 / 110）；未命中时为空串
     */
    public record Outcome(PendingRequest request, boolean crisisTriggered, String crisisNotice) {

        /** 紧凑构造器：界面会直接解引用它们，所以一个都不许是 {@code null}。 */
        public Outcome {
            Objects.requireNonNull(request, "request（没有待发送请求就没有确认这一步）");
            crisisNotice = crisisNotice == null ? "" : crisisNotice;
        }
    }

    /**
     * 组装一份「等待确认的请求」并带上本地危机判定，供计划 3 的界面一次拿全。
     *
     * <p>危机判定只在<b>本地</b>做（spec §6.5：不由 AI 判断），判定的材料是本次要发送的
     * 用户记录原文；<b>命中不改变请求本身</b>——不丢记录、不替换成热线话术、不抛异常，
     * 只是把「命中了」这个事实与一份中性提示带出来。
     *
     * <p>这里刻意<b>没有</b>「命中就返回别的请求」的分支：那是这条约束最常见的破坏方式，
     * 而它写出来之后在预览里看起来完全正常。
     *
     * @param role     角色
     * @param window   用户选定的时间窗
     * @param log      已解析的情绪记录
     * @param detector 危机检测器；{@code null} 等价于「不判定」，此时 {@code crisisTriggered}
     *                 恒为 false（不猜、不阻断）
     */
    public static Outcome buildWithCrisis(PromptRole role, AnalysisWindow window, EmotionLog log,
                                         CrisisDetector detector) {
        PendingRequest request = build(role, window, log);
        boolean triggered = detector != null
                && detector.detect(renderEntries(window.select(log))).triggered();
        return new Outcome(request, triggered, triggered ? CRISIS_NOTICE : "");
    }

    /**
     * 把选中的条目渲染成<b>发送文本</b>：用户记录原文，逐字，最新在前。
     *
     * <p>用 {@link EmotionEntry#toBlockText(String)} 而不是自己拼字段：解析与生成共用一处格式，
     * 才不会出现「App 显示的和文件里写的不一样」。这里唯一加的是条目之间的空行。
     *
     * <p><b>不做任何加工</b>：不摘要、不截断、不替换词汇、不因为命中危机词而改写
     * （spec §6.5：命中不阻断，分析照常）。
     *
     * @param entries 选中的条目（通常是 {@link AnalysisWindow#select(EmotionLog)} 的结果）；
     *                {@code null} 视为空列表
     * @return 待发送文本；没有条目时是空串
     */
    public static String renderEntries(List<EmotionEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (EmotionEntry entry : entries) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(entry.toBlockText(""));
        }
        return text.toString();
    }

    /**
     * 中性的危机资源提示（spec §6.5），供界面在资源面板里显示。
     *
     * <p>它含 {@code 120} 与 {@code 110}（这两条渠道不可删除），不含任何界面禁用词与诊断性标签，
     * 也不打断任何流程——是否显示它由界面决定，而不是由「是否继续分析」决定。
     */
    public static String crisisNotice() {
        return CRISIS_NOTICE;
    }

    /**
     * 用户消息：时间窗 + 条数 + 记录原文 + 指令。
     *
     * <p>「将发送 N 条记录」是 spec §6.4 第 2 步明写要显示的；两个端点也写出来，
     * 用户才能核对「发的是哪一段」。窗口里没有记录时如实写 0 条，不假装成别的东西。
     */
    private static String userMessage(AnalysisWindow window, List<EmotionEntry> selected) {
        return "时间窗：" + window.label() + "\n"
                + "将发送 " + selected.size() + " 条记录\n\n"
                + "# 我的记录（原文）\n\n"
                + renderEntries(selected) + "\n\n"
                + "# 我要的分析\n\n"
                + TASK_INSTRUCTION;
    }

    /**
     * 自检用的一句话锚点：提示词的<b>第一行非空内容</b>必须出现在待发送文本里。
     *
     * <p>这是 controller 对 Task 9 的硬性要求：{@link PendingRequest} 的契约只保证
     * 「预览 == 实发」，它<b>管不了</b>提示词有没有被放进去。一个只把记录拼进 user 消息、
     * 丢掉 system 提示词的实现能通过其它所有断言，却会让模型失去全部行为约束
     * （三段式、措辞约束）。这条自检是那个唯一的守卫。
     *
     * <p>锚点从 {@link PromptLibrary#load} 的<b>返回值</b>现算，不再写死字面量：
     * 写死的话，用户把外部提示词换掉（那是被支持的用法）就会让所有分析报错；
     * 而现算的锚点在任何一份提示词上都成立，同时仍然能抓住「提示词根本没被放进去」。
     */
    private static String promptAnchor(String prompt) {
        return firstNonBlankLine(prompt);
    }

    /** 第一行非空内容（用于自检时给出可读的提示词锚点）。 */
    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    // =====================================================================
    // 三段式输出
    // =====================================================================

    /**
     * 一次分析的<b>三段式</b>结果（spec §6.4 第 4 步）：观察到的模式 / 可能的影响因素 /
     * 2~3 条可执行的小建议。
     *
     * <h2>解析必须容错，永不抛异常</h2>
     * 模型漏掉一段、把标题写错、返回一大段没有标题的散文……都只会让那一段变成<b>空串</b>，
     * 其余照收。让「解析失败」把已经拿到的回复整块丢掉，对用户毫无价值。
     *
     * <h2>解析层不洗白模型输出（重要）</h2>
     * spec §6.5 的措辞约束（不出现疾病名、不给严重程度分级、不量化评分）是<b>提示词的职责</b>。
     * 本类<b>不</b>做替换、过滤或改写：模型若违规写出「抑郁」这类词，它照样出现在结果里。
     * 界面渲染时若看到诊断性词汇，那是<b>模型违规</b>，应当<b>标注</b>（例如「模型没有遵守措辞约束」）
     * 而不是静默改掉——静默改写会让用户看到一个从未被说出的版本，也让提示词失效永远无人发现。
     *
     * <h2>危机提示与三段是并列的，不是替代</h2>
     * {@link #crisisTriggered()} / {@link #crisisNotice()} 只表示「本地关键词表命中了」，
     * 它们<b>不</b>改变三段的内容，也<b>不</b>阻止任何事：命中时分析照常展示（spec §6.5）。
     * 界面拿它去渲染中性的资源面板即可。
     *
     * @param observations   第一段；缺失时为空串
     * @param factors        第二段；缺失时为空串
     * @param suggestions    第三段；缺失时为空串
     * @param crisisTriggered 本地关键词表是否命中
     * @param crisisNotice   命中时的中性资源提示（含 120 / 110）；未命中时为空串
     */
    public record Result(String observations, String factors, String suggestions,
                         boolean crisisTriggered, String crisisNotice) {

        /** 紧凑构造器：三段与提示都不许是 {@code null}（界面直接拼接它们）。 */
        public Result {
            observations = observations == null ? "" : observations;
            factors = factors == null ? "" : factors;
            suggestions = suggestions == null ? "" : suggestions;
            crisisNotice = crisisNotice == null ? "" : crisisNotice;
        }

        /**
         * 从模型输出里取出三段：{@code observations} / {@code factors} / {@code suggestions}。
         *
         * <p>识别规则（宽进严出：宁可多认一段，也不许把整块内容丢掉）：
         * <ul>
         *   <li>标题可以出现在行首，前面允许 {@code #}、{@code *}、{@code -}、数字与空白
         *       （模型常写成 Markdown 小标题或带编号的列表）；</li>
         *   <li>标题与正文之间的冒号可以是全角 {@code ：} 或半角 {@code :}，也可以没有冒号
         *       （正文另起一行）；</li>
         *   <li>标题之后、下一个标题之前的一切都归那一段，<b>段内换行原样保留</b>；</li>
         *   <li>第一个标题之前的内容（通常是一句开场白）忽略；没有标题时三段都是空串。</li>
         * </ul>
         *
         * <p>标题必须<b>逐字</b>匹配（例如「观察模式的补充说明」不算标题）：
         * 近似匹配会把正文里顺口提到的词当成标题，把后面的内容整块挪到错误的段里。
         *
         * <p>不做危机判定（危机由本地 {@link CrisisDetector} 判定，spec §6.5：不依赖 AI 判断），
         * 所以这里 {@code crisisTriggered} 恒为 {@code false}、{@code crisisNotice} 恒为空串。
         * 需要带上危机提示时用 {@link #parse(String, boolean)}。
         *
         * @param aiText 模型输出；{@code null} 或空白 → 三段都是空串
         * @return 三段结果，永不 {@code null}、永不抛异常
         */
        public static Result parse(String aiText) {
            return parse(aiText, false);
        }

        /**
         * 从模型输出里取出三段，并带上「这次记录命中了本地危机关键词」这一事实。
         *
         * @param aiText          模型输出；{@code null} 或空白 → 三段都是空串
         * @param crisisTriggered 本地 {@link CrisisDetector} 是否命中（由调用方判定）
         */
        public static Result parse(String aiText, boolean crisisTriggered) {
            String text = aiText == null ? "" : aiText;
            String[] sections = splitSections(text);
            return new Result(sections[0], sections[1], sections[2],
                    crisisTriggered, crisisTriggered ? CRISIS_NOTICE : "");
        }

        /** 三段是否都是空的（界面据此显示「这次回复里没有可展示的内容」）。 */
        public boolean hasNoSections() {
            return observations.isEmpty() && factors.isEmpty() && suggestions.isEmpty();
        }
    }

    /**
     * 按标题把模型输出切成三段。
     *
     * <p>标题<b>同行</b>的正文（{@code 观察到的模式：甲}）与<b>下一行</b>的正文
     * （{@code ## 观察到的模式\n甲}）都要收进来——这两种写法模型都会用，漏掉前者会让整段
     * 静默消失（这一版第一轮就踩到了）。
     *
     * @return 长度恒为 3 的数组：{@code [observations, factors, suggestions]}
     */
    private static String[] splitSections(String text) {
        String[] sections = {"", "", ""};
        if (text.isEmpty()) {
            return sections;
        }
        int current = -1;
        StringBuilder buffer = new StringBuilder();
        for (String rawLine : text.split("\n", -1)) {
            Heading heading = headingAt(rawLine);
            if (heading != null) {
                if (current >= 0) {
                    sections[current] = buffer.toString().strip();
                }
                current = heading.index();
                buffer.setLength(0);
                if (!heading.rest().isEmpty()) {
                    buffer.append(heading.rest()).append('\n');
                }
                continue;
            }
            if (current >= 0) {
                buffer.append(rawLine).append('\n');
            }
            // current < 0：第一个标题之前的开场白，忽略
        }
        if (current >= 0) {
            sections[current] = buffer.toString().strip();
        }
        return sections;
    }

    /** 一个标题的识别结果：段号 + 标题之后的剩余正文（可能是空串）。 */
    private record Heading(int index, String rest) {
    }

    /**
     * 这一行是不是某一段的标题；是就返回段号与剩余正文，否则返回 {@code null}。
     *
     * <p>先把行首的 Markdown 装饰（{@code #}、{@code *}、{@code -}、{@code >}、空白）与行首编号
     * （{@code 1.} / {@code 1、} / {@code （1）} / {@code 一、}）剥掉，再逐字比对标题。
     * 标题后面允许没有冒号（正文另起一行），也允许是全角或半角冒号。
     *
     * <p>{@code **可能的影响因素**：乙} 这种「整段标题被加粗、冒号在 {@code **} 之后」的写法
     * 要认出来：只看行尾 {@code $} 的加粗剥离对它是无效的（{@code **} 后面还跟着冒号与正文），
     * 所以还要剥掉紧贴冒号之前的装饰符——模型这么写很常见，漏掉就等于整段丢失。
     */
    private static Heading headingAt(String line) {
        String rest = stripLeadingDecorations(line);
        for (int i = 0; i < SECTION_HEADINGS.length; i++) {
            String heading = SECTION_HEADINGS[i];
            if (rest.startsWith(heading)) {
                String after = stripLeadingDecorations(rest.substring(heading.length())).stripLeading();
                if (after.isEmpty()) {
                    return new Heading(i, "");
                }
                if (after.charAt(0) == '：' || after.charAt(0) == ':') {
                    return new Heading(i, after.substring(1).strip());
                }
            }
        }
        return null;
    }

    /** 三段标题，顺序即段号。 */
    private static final String[] SECTION_HEADINGS = {
            HEADING_OBSERVATIONS, HEADING_FACTORS, HEADING_SUGGESTIONS};

    /**
     * 剥掉行首/行尾的 Markdown 装饰与行首编号，例如 {@code ## 1. 观察到的模式} → {@code 观察到的模式}。
     *
     * <p>它会被调两次（整行一次、标题之后一次），所以必须对已经剥过的输入是幂等的：
     * {@code 可能的影响因素：乙} 再进一次不会掉字。
     */
    private static String stripLeadingDecorations(String line) {
        return line.strip()
                .replaceFirst("^[#*\\->•]+\\s*", "")
                .replaceFirst("^[0-9０-９]{1,3}\\s*[.、)）]\\s*", "")
                .replaceFirst("^[（(]\\s*[0-9０-９一二三四五六七八九十]{1,3}\\s*[)）]\\s*", "")
                .replaceFirst("^[一二三四五六七八九十]\\s*[.、)）]\\s*", "")
                .stripTrailing()
                .replaceFirst("\\*+$", "")
                // 行尾是「装饰符 + 冒号」时（例如 **可能的影响因素**：乙），装饰符在冒号之前
                .replaceFirst("[#*\\->•]+(?=\\s*[：:])", "")
                .strip();
    }
}
