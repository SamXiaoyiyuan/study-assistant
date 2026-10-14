package com.xiaoyiyuan.study.ui.view;

import com.xiaoyiyuan.study.ui.text.UiText;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 页面占位视图：页面<b>本体</b>的内容在 Task 4–10。
 *
 * <p>Task 2 只要求「导航、状态栏、主题切换、首启引导真的做出来」，所以 8 类页面先各有一个
 * 占位视图。它必须满足两件事，否则外壳的断言就失去意义：</p>
 * <ol>
 *   <li>带上 {@code placeholder-page} 样式类 + {@code Page} 的 {@code id}——
 *       测试据此证明「切页后旧页已从场景图里移除，只剩当前页一个」；</li>
 *   <li>文字走 {@link UiText}（从 Task 2 起建立这个习惯，Task 11 的禁用词扫描才不会返工）。</li>
 * </ol>
 */
public final class PlaceholderPage {

    private final Page page;
    private final VBox root = new VBox();

    /**
     * @param page 这个占位视图代表的页面
     */
    public PlaceholderPage(Page page) {
        this.page = page;

        root.getStyleClass().add("placeholder-page");
        root.setId(page.name());
        root.setSpacing(8.0);
        root.setAlignment(Pos.TOP_LEFT);

        Label title = new Label(page.label());
        title.getStyleClass().add("page-title");
        title.setWrapText(true);
        title.setMaxWidth(Double.MAX_VALUE);

        Label hint = new Label(UiText.PLACEHOLDER_HINT);
        hint.getStyleClass().add("secondary");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);

        root.getChildren().addAll(title, hint);
        root.setAccessibleText(UiText.PLACEHOLDER_LABEL_PREFIX + page.label());
    }

    /** 这个占位视图代表的页面。 */
    public Page page() {
        return page;
    }

    /** 占位视图节点（带 {@code placeholder-page} 样式类）。 */
    public VBox root() {
        return root;
    }
}
