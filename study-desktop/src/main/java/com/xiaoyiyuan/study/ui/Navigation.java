package com.xiaoyiyuan.study.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.xiaoyiyuan.study.ui.view.Page;

/**
 * 导航状态：<b>纯逻辑</b>，不碰 JavaFX，因此「切页」这件事本身可以被单测。
 *
 * <p>为什么单独一个类：把页面切换写在每个按钮的事件处理器里，等于把「当前是第几页」
 * 复制成 N 份状态——总有一天会有一处忘记更新（现象是「点了没反应」或「两页同时高亮」）。
 * 这里只有一个 {@link #current}，内容区由 {@code AppShell} 订阅 {@link #addListener}
 * 完成更换，视图代码里不再出现第二套判断。</p>
 */
public final class Navigation {

    private final List<Page> sidebarItems;
    private final List<Consumer<Page>> listeners = new ArrayList<>();

    private Page current;

    /**
     * @param initial 初始页（通常 {@link Page#OVERVIEW}）
     */
    public Navigation(Page initial) {
        this.current = Objects.requireNonNull(initial, "initial");
        this.sidebarItems = java.util.Arrays.stream(Page.values())
                .filter(Page::inSidebar)
                .toList();
    }

    /** 当前页。 */
    public Page current() {
        return current;
    }

    /**
     * 切到某一页；同一页重复切<b>不通知</b>（重复通知会让内容区重建、抢焦点、丢掉正在编辑的内容）。
     *
     * @param page 目标页
     */
    public void go(Page page) {
        Page target = Objects.requireNonNull(page, "page");
        if (target == current) {
            return;
        }
        this.current = target;
        notifyListeners(target);
    }

    /**
     * 注册监听者：切页时按注册顺序各收到一次通知。
     *
     * <p>用 {@code List.copyOf} 式快照遍历：监听者在通知过程中又注册新监听者时，
     * 新监听者不会收到本轮通知，也不会抛 {@code ConcurrentModificationException}。</p>
     *
     * @param listener 监听者
     */
    public void addListener(Consumer<Page> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** 侧栏内容与顺序（spec §8.1 的页面顺序 + 设置页收尾；求助资源不在侧栏里）。 */
    public List<Page> sidebarItems() {
        return sidebarItems;
    }

    private void notifyListeners(Page page) {
        for (Consumer<Page> listener : List.copyOf(listeners)) {
            listener.accept(page);
        }
    }
}
