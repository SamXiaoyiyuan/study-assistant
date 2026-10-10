package com.xiaoyiyuan.study.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public final class App extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("环境冒烟通过 · 中文渲染正常");
        // 显式指定字体族：JavaFX 中文渲染不指定可能出方块（spec §8.3）
        label.setStyle("-fx-font-family: 'Microsoft YaHei'; -fx-font-size: 20px;");
        stage.setTitle("环境冒烟");
        stage.setScene(new Scene(new StackPane(label), 420, 200));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
