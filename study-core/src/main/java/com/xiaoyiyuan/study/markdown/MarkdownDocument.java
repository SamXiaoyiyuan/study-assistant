package com.xiaoyiyuan.study.markdown;

import java.util.List;

/**
 * 一篇 Markdown 文档的块序列。
 *
 * <p>本类只做一件事：把各块的 {@link Block#render()} <b>顺序拼接</b>。它刻意<b>不做任何收尾</b>——
 * 不加末尾换行、不修剪空白、不规范化换行符。因为 render() 的结果会被直接写回用户文件，
 * 任何一处「顺手收拾」都会让 P1 失效。
 *
 * <p>构造时复制一份块列表：文档一旦建成，块序列就是确定的（块的<b>内容</b>仍可编辑），
 * 这样 render() 的拼接顺序不会因为外部改动了传入的 List 而被悄悄改变。
 *
 * <p><b>public 是硬要求</b>：后续任务在别的包里消费本类型（见 {@link Block} 的说明）。
 */
public final class MarkdownDocument {

    private final List<Block> blocks;

    /**
     * @param blocks 块的顺序即文档的顺序；内部会复制一份，外部再改动传入列表不影响本文档
     */
    public MarkdownDocument(List<Block> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    /** 按文档顺序返回块；列表不可变，块本身可编辑。 */
    public List<Block> blocks() {
        return blocks;
    }

    /** 逐块拼接，不做任何收尾修饰——render() 的结果可能被直接写回用户文件。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Block block : blocks) {
            sb.append(block.render());
        }
        return sb.toString();
    }
}
