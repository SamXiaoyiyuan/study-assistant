package com.xiaoyiyuan.study.ui.service;

import com.xiaoyiyuan.study.markdown.Block;
import com.xiaoyiyuan.study.markdown.MarkdownParser;
import com.xiaoyiyuan.study.plan.PlanDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 主计划文件的界面侧门面：结构化字段走 {@link PlanDocument}，§五 生活准则走<b>原文块路径</b>。
 *
 * <h2>为什么 §五 要另走一条路</h2>
 * {@link PlanDocument} 只建模了五个章节（现状 / 目标 / 课程 / 底线 / 阶段，见
 * {@code PlanSection}），§五 生活准则是<b>未建模</b>章节——它的内容是用户手写的散列项，
 * 被 {@code MarkdownDocument} 当作原样块逐字节保存。给它硬造一个模型，就是「App 改写用户散文」
 * 的起点（{@code PlanSection} 的注释已把这条钉死）。
 *
 * <p>所以本类的 §五 读写只有一条通道：把当前文本交给 {@link MarkdownParser} 切块 →
 * 找到标题正好是 {@code ## 五、生活准则} 的那一块及其后续、直到下一个二级标题为止 →
 * <b>只改其中的目标行</b> → 重新解析回 {@link PlanDocument}。其余每一个字节都在原样块里，
 * 因此「读出来再原样写回去 → render 逐字节等于原文」这条性质天然成立（有测试钉着）。
 *
 * <p>约定：{@link #lifeRules()} 返回<b>去掉 {@code - } 前缀、去掉行尾换行</b>的正文行；
 * 写回时补 {@code - } 前缀，并沿用原行自己的行尾符（LF / CRLF 都不被静默改写）。
 */
public final class PlanService {

    /** §五 的完整标题（「中文序号 + 标题名」双重匹配，与 PlanDocument 的定位口径一致）。 */
    private static final String LIFE_RULES_HEADING = "## 五、生活准则";

    /** 章节（二级标题）前缀：本类用它划定 §五 的结束边界。 */
    private static final String SECTION_PREFIX = "## ";

    /** 生活准则的列表项前缀。 */
    private static final String BULLET_PREFIX = "- ";

    private final DocumentSession session;
    private final Path studyDir;

    /** 当前文档模型；§五 的改动会整体重新解析后替换它。 */
    private PlanDocument document;

    /**
     * @param session  这份计划文件的唯一 session（由 {@code AppShell} 持有并注入）
     * @param studyDir 学习目录（§五 之外的能力，例如后续的资料目录，用它派生）
     * @throws com.xiaoyiyuan.study.plan.SectionMissingException 打开时的文本解析不出五个建模章节
     */
    public PlanService(DocumentSession session, Path studyDir) {
        this.session = Objects.requireNonNull(session, "session");
        this.studyDir = Objects.requireNonNull(studyDir, "studyDir");
        this.document = PlanDocument.parse(session.currentText());
    }

    /** 当前文档模型。 */
    public PlanDocument document() {
        return document;
    }

    /** 学习目录。 */
    public Path studyDir() {
        return studyDir;
    }

    /**
     * 对文档模型做一次结构化改动，并把渲染结果交给 session。
     *
     * <p>只改内存，不碰磁盘；真正落盘由 {@link #save} 决定。
     */
    public void mutate(Consumer<PlanDocument> mutation) {
        Objects.requireNonNull(mutation, "mutation").accept(document);
        session.update(document.render());
    }

    /** 保存：语义与 {@link DocumentSession#save} 完全一致（本类不另设写路径）。 */
    public SaveResult save(ExternalChangePolicy policy) throws IOException {
        return session.save(policy);
    }

    // =====================================================================
    // §五 生活准则（原文块路径）
    // =====================================================================

    /** §五 生活准则的逐行文本（去掉 {@code - } 前缀与行尾换行）；没有这一节时返回空列表。 */
    public List<String> lifeRules() {
        return lifeRulesOf(document.render());
    }

    /** 改第 {@code index} 条（下标按 {@link #lifeRules()} 的顺序），写回时补 {@code - } 前缀。 */
    public void setLifeRule(int index, String text) {
        String value = requireSingleLine(text);
        LifeRulesRegion region = locateLifeRules(document.render(), true);
        List<Integer> bullets = bulletLineIndexes(region.lines());
        int line = bulletIndex(region.lines(), bullets, index);
        region.lines().set(line, BULLET_PREFIX + value + lineEnding(region.lines().get(line)));
        applyLifeRules(region);
    }

    /** 在列表末尾追加一条。 */
    public void addLifeRule(String text) {
        String value = requireSingleLine(text);
        LifeRulesRegion region = locateLifeRules(document.render(), true);
        List<String> lines = region.lines();
        List<Integer> bullets = bulletLineIndexes(lines);
        int afterLine = bullets.isEmpty() ? headingLine(lines) : bullets.get(bullets.size() - 1);
        String ending = lineEnding(lines.get(afterLine));
        if (ending.isEmpty()) {
            // 被插在其后的那一行没有行尾符（文件末行）时先补一个，否则新行会与它挤成一行
            lines.set(afterLine, lines.get(afterLine) + "\n");
            ending = "\n";
        }
        lines.add(afterLine + 1, BULLET_PREFIX + value + ending);
        applyLifeRules(region);
    }

    /** 删除第 {@code index} 条。 */
    public void removeLifeRule(int index) {
        LifeRulesRegion region = locateLifeRules(document.render(), true);
        List<Integer> bullets = bulletLineIndexes(region.lines());
        int line = bulletIndex(region.lines(), bullets, index);
        region.lines().remove(line);
        applyLifeRules(region);
    }

    /** 调整行序（{@code to} 是「移完之后所在的位置」）。 */
    public void moveLifeRule(int from, int to) {
        LifeRulesRegion region = locateLifeRules(document.render(), true);
        List<String> lines = region.lines();
        List<Integer> bullets = bulletLineIndexes(lines);
        // 先校验两端下标再落笔：被拒绝的写入必须一个字节都不改
        bulletIndex(lines, bullets, from);
        bulletIndex(lines, bullets, to);
        List<String> bodies = new ArrayList<>(bullets.size());
        for (int line : bullets) {
            bodies.add(bulletBody(lines.get(line)));
        }
        String moved = bodies.remove(from);
        bodies.add(to, moved);
        for (int i = 0; i < bullets.size(); i++) {
            int line = bullets.get(i);
            lines.set(line, BULLET_PREFIX + bodies.get(i) + lineEnding(lines.get(line)));
        }
        applyLifeRules(region);
    }

    /**
     * 从任意文本里取出 §五 生活准则的逐行正文。
     *
     * <p>包内可见：{@link WeeklySnapshot} 复用同一条通道，免得两处各写一份「怎么找 §五」。
     */
    static List<String> lifeRulesOf(String text) {
        Objects.requireNonNull(text, "text");
        LifeRulesRegion region = locateLifeRules(text, false);
        if (region == null) {
            return List.of();
        }
        List<String> rules = new ArrayList<>();
        for (int line : bulletLineIndexes(region.lines())) {
            rules.add(bulletBody(region.lines().get(line)));
        }
        return List.copyOf(rules);
    }

    // ---- §五 定位与改写：只在这里读 / 写那一节的字节 ----

    /**
     * §五 这一节在全文里的三段切分：{@code prefix}（之前）+ {@code lines}（本节逐行原文）+
     * {@code suffix}（下一个二级标题起，之后全部）。
     *
     * <p>{@code lines} 是<b>可变</b>的：所有 §五 编辑都在这份行列表上做，然后整段拼回。
     */
    private record LifeRulesRegion(String prefix, List<String> lines, String suffix) {
    }

    /**
     * 定位 §五：按块顺序找到第一个「含标题 {@code ## 五、生活准则}」的块，再取到下一个含二级标题的块为止。
     *
     * <p>{@code required=false}（只读路径）时找不到就返回 {@code null}；{@code true}（写路径）时抛错
     * ——<b>定位不到就不写</b>，而不是猜一个位置或凭空造一节。
     */
    private static LifeRulesRegion locateLifeRules(String text, boolean required) {
        List<Block> blocks = MarkdownParser.parse(text).blocks();
        int heading = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (containsHeading(blocks.get(i).render(), LIFE_RULES_HEADING)) {
                heading = i;
                break;
            }
        }
        if (heading < 0) {
            if (!required) {
                return null;
            }
            throw new IllegalStateException("文档里找不到 `" + LIFE_RULES_HEADING + "` 这一节，"
                    + "不猜位置、也不新建：先在计划文件里写上这一节的标题。");
        }
        int end = blocks.size();
        for (int i = heading + 1; i < blocks.size(); i++) {
            if (containsHeading(blocks.get(i).render(), null)) {
                end = i; // 下一个二级标题：§五 到此为止（与 PlanDocument 的章节边界同口径）
                break;
            }
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < heading; i++) {
            prefix.append(blocks.get(i).render());
        }
        StringBuilder region = new StringBuilder();
        for (int i = heading; i < end; i++) {
            region.append(blocks.get(i).render());
        }
        StringBuilder suffix = new StringBuilder();
        for (int i = end; i < blocks.size(); i++) {
            suffix.append(blocks.get(i).render());
        }
        return new LifeRulesRegion(prefix.toString(), splitKeepingLineEndings(region.toString()),
                suffix.toString());
    }

    /** 把改过的 §五 拼回全文，重新解析成 {@link PlanDocument}，再交给 session。 */
    private void applyLifeRules(LifeRulesRegion region) {
        StringBuilder text = new StringBuilder(region.prefix());
        for (String line : region.lines()) {
            text.append(line);
        }
        text.append(region.suffix());
        // 重新解析：只有 §五 的字节变了，五个建模章节与其余原样块原样照抄（render 逐字节还原）
        this.document = PlanDocument.parse(text.toString());
        session.update(document.render());
    }

    /** 该块里是否含标题行；{@code exactHeading == null} 表示「任意二级标题」。 */
    private static boolean containsHeading(String blockText, String exactHeading) {
        for (String line : splitKeepingLineEndings(blockText)) {
            String stripped = line.strip();
            if (exactHeading == null ? stripped.startsWith(SECTION_PREFIX) : stripped.equals(exactHeading)) {
                return true;
            }
        }
        return false;
    }

    /** §五 区域里所有列表项行的下标（按原文顺序）。 */
    private static List<Integer> bulletLineIndexes(List<String> lines) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (withoutLineEnding(lines.get(i)).startsWith(BULLET_PREFIX)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    /** 第 {@code index} 条列表项在其区域里的行下标；越界就抛。 */
    private static int bulletIndex(List<String> lines, List<Integer> bullets, int index) {
        if (index < 0 || index >= bullets.size()) {
            throw new IndexOutOfBoundsException("生活准则下标越界：" + index + "（共 " + bullets.size() + " 条）");
        }
        return bullets.get(index);
    }

    /** §五 标题行在区域行列表里的下标。 */
    private static int headingLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (withoutLineEnding(lines.get(i)).strip().equals(LIFE_RULES_HEADING)) {
                return i;
            }
        }
        throw new IllegalStateException("内部错误：定位到了 §五 的块，却找不到它的标题行");
    }

    /** 列表项的正文：去掉 {@code - } 前缀与行尾换行。 */
    private static String bulletBody(String line) {
        return withoutLineEnding(line).substring(BULLET_PREFIX.length());
    }

    /** 一条生活准则只能是一行；含换行会写坏整节，拒绝而不是转义。 */
    private static String requireSingleLine(String text) {
        Objects.requireNonNull(text, "text");
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("生活准则的一条只能是一行（不能含换行符）：" + text);
        }
        return text;
    }

    /** 只去掉行尾的 {@code '\n'} / {@code '\r'}，行内空白一格不动。 */
    private static String withoutLineEnding(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end);
    }

    /** 行尾符本身（{@code "\n"} / {@code "\r\n"} / {@code ""}）。 */
    private static String lineEnding(String line) {
        return line.substring(withoutLineEnding(line).length());
    }

    /** 按 {@code '\n'} 切成「保留行尾符」的行；不 trim、不规范化换行。 */
    private static List<String> splitKeepingLineEndings(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }
}
