package com.xiaoyiyuan.study.ui.view;

import java.util.List;

import com.xiaoyiyuan.study.ui.StartupCheck;
import com.xiaoyiyuan.study.ui.text.UiText;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 首启引导的<b>中性提示条</b>：替代 {@code Alert.ERROR} / {@code Alert.WARNING}。
 *
 * <p>「中性」在这里是可以被断言的几条性质：只用 {@code .notice} 样式（提示色，不是红/橙），
 * 不弹窗、不阻断，文字里不出现 8 个界面禁用词，也不出现诊断性标签。
 * 提示条挂在 {@code BorderPane.top}——页头之外的独立区域，不遮内容。</p>
 *
 * <p>旧档案存在时多一个「{@link UiText#OPEN_MIGRATION}」按钮：迁移向导本体是 Task 9 的
 * 「设置」页内容，所以 Task 2 只把用户送过去（{@code openSettings} 回调）。</p>
 */
public final class StartupNoticeBar {

    private final HBox box = new HBox();

    /**
     * @param notices       要显示的中性提示（逐条，不为空）
     * @param openSettings  点击「打开迁移向导」时要执行的动作（通常是切到设置页）
     */
    public StartupNoticeBar(List<String> notices, Runnable openSettings) {
        box.getStyleClass().add("notice");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setSpacing(12.0);

        Label text = new Label(String.join(" ", notices));
        text.getStyleClass().add("body");
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        box.getChildren().add(text);

        if (notices.contains(StartupCheck.NOTICE_LEGACY_PRESENT)) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.SOMETIMES);

            Button openMigration = new Button(UiText.OPEN_MIGRATION);
            openMigration.getStyleClass().add("quiet");
            openMigration.setOnAction(event -> openSettings.run());

            box.getChildren().addAll(spacer, openMigration);
        }
        box.setAccessibleText(String.join(" ", notices));
    }

    /** 提示条节点（带 {@code notice} 样式类）。 */
    public HBox node() {
        return box;
    }
}
