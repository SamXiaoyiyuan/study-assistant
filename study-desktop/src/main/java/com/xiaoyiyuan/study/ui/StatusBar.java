package com.xiaoyiyuan.study.ui;

import com.xiaoyiyuan.study.ui.control.StatusModel;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 底部状态条：显示 {@code studyDir} / 工作模式 / 最后保存时间（规则一节的三件事）。
 *
 * <p>放在 {@code BorderPane.bottom}——它占自己的空间，<b>不是</b>浮在内容上的 {@code StackPane}
 * 覆盖层（指南 §3.3 的 {@code focus-not-obscured}：浮层会遮住滚到最底部的焦点控件）。</p>
 *
 * <p>文本来自 {@link StatusModel}（纯逻辑、有单测），这里只负责把它显示出来。</p>
 */
public final class StatusBar {

    private final HBox box = new HBox();
    private final Label text = new Label();
    private final StringProperty line = new SimpleStringProperty();

    /**
     * @param model 状态栏的三个事实
     */
    public StatusBar(StatusModel model) {
        box.getStyleClass().add("status-bar");
        box.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        text.textProperty().bind(line);
        box.getChildren().addAll(text, spacer);

        line.set(model.statusLine());
    }

    /** 状态栏文本（与 {@link StatusModel#statusLine()} 同步）。 */
    public StringProperty textProperty() {
        return line;
    }

    /** 状态栏节点。 */
    public HBox node() {
        return box;
    }
}
