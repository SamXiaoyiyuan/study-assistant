package com.xiaoyiyuan.study.ui;

/** JavaFX 在 classpath 方式下要求启动类不继承 Application，故独立一个 Launcher。 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
