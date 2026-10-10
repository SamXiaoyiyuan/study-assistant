package com.xiaoyiyuan.study.markdown;

/**
 * 尚未识别出语义的块：原样文本进、原样文本出。
 *
 * <p>Task 3 只做切块，不做任何 Markdown 语义识别（表格识别是 Task 4 的事）。
 * 语义块（表格、列表、段落……）只需实现 {@link Block}，在干净状态下返回各自的原文即可，
 * 因此整个模型是「默认无损」的：新块的默认行为就满足 P1。
 *
 * <p><b>public 是硬要求</b>：后续任务在别的包里消费本类型（见 {@link Block} 的说明）。
 */
public final class RawBlock implements Block {

    /**
     * 切块时拿到的原文，且此后<b>永不被改写</b>。
     *
     * <p>这是 P1 的锚点：无论 {@link #currentText} 后来被改成什么，只要没被标记脏，
     * {@link #render()} 都能逐字节还原本段输入。
     */
    private final String originalText;

    /** 编辑后的内容；仅在 {@link #dirty} 为 true 时生效。 */
    private String currentText;

    /** 是否被显式标记为改动过。 */
    private boolean dirty;

    /**
     * @param originalText 本段原文，必须与切块时切出的字节完全一致（含段内换行、
     *                     含归属于本段的空行；<b>不含</b>任何被丢弃的空格）
     */
    public RawBlock(String originalText) {
        this.originalText = originalText;
        // 初值就是原文，避免 dirty 之前出现 null：render() 在两条路径上都拿得到非空字符串
        this.currentText = originalText;
    }

    /** 本块当前的字节内容（不论脏否）。 */
    public String currentText() {
        return currentText;
    }

    /** 写入新内容。注意：这<b>不会</b>自动置脏——脏必须由调用方显式声明。 */
    public void setCurrentText(String currentText) {
        this.currentText = currentText;
    }

    @Override
    public boolean dirty() {
        return dirty;
    }

    @Override
    public void markDirty() {
        this.dirty = true;
    }

    @Override
    public void clearDirty() {
        this.dirty = false;
    }

    /**
     * 脏则返回 {@link #currentText}，不脏则返回 {@link #originalText}。
     *
     * <p>两条路径都<b>直接返回字符串本身</b>：不补末尾换行、不 trim、不规范化换行符。
     * 任何「顺手收拾一下」的修饰都会破坏 P1——空白就是数据。
     */
    @Override
    public String render() {
        return dirty ? currentText : originalText;
    }
}
