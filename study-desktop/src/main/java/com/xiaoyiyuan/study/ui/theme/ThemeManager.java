package com.xiaoyiyuan.study.ui.theme;

import java.lang.ref.WeakReference;
import java.util.Objects;

import javafx.scene.Scene;

/**
 * 主题的<b>唯一</b>管理者：主题切换只改样式表列表，绝不在控制器里逐节点 {@code setStyle}，
 * 也不在各页面里散落 {@code if (dark)}。
 *
 * <p>加载顺序固定为 {@code tokens-*.css} → {@code app.css}（后者必须最后）。</p>
 *
 * <p>{@link #attach(Scene)} 用 {@code Scene.getStylesheets().setAll(...)} <b>整体替换</b>——
 * 用 {@code add} 会在每次切换时叠加一份旧令牌，后加载的那份生效，表现是「切了没反应」，
 * 是这个项目里最难查的一类问题。</p>
 */
public final class ThemeManager {

    private ThemeMode current;
    private boolean reduceMotion;
    private WeakReference<Scene> attached = new WeakReference<>(null);

    /** @param initial 初始主题（本任务只有明 / 暗两档，理由见 {@link ThemeMode}） */
    public ThemeManager(ThemeMode initial) {
        this.current = Objects.requireNonNull(initial, "initial");
        this.reduceMotion = false;
    }

    /** 当前主题。 */
    public ThemeMode current() {
        return current;
    }

    /**
     * 切换主题：更新状态；若已经 {@link #attach(Scene)} 过，同时把样式表整体换成新主题的那两张。
     */
    public void setMode(ThemeMode mode) {
        this.current = Objects.requireNonNull(mode, "mode");
        applyToAttached();
    }

    /** 在明 / 暗之间切换。 */
    public void toggle() {
        setMode(current.other());
    }

    /** 「减少动效」开关的当前值（默认 false；设置页负责把它与系统动画设置对齐）。 */
    public boolean reduceMotion() {
        return reduceMotion;
    }

    /**
     * 设置「减少动效」。
     *
     * <p>JavaFX 没有 {@code prefers-reduced-motion} 媒体查询，这里是它的等价物：
     * 为真时所有 {@code Transition} 应直接跳到终态（<b>不是</b>把时长设为 0 后仍播放）。</p>
     */
    public void setReduceMotion(boolean reduceMotion) {
        this.reduceMotion = reduceMotion;
    }

    /**
     * 把主题挂到场景上：<b>整体替换</b>样式表列表（不是 {@code add}）。
     *
     * @param scene 目标场景
     */
    public void attach(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        this.attached = new WeakReference<>(scene);
        applyTo(scene);
    }

    /** 取消挂载（窗口关闭时调用，避免持有已销毁的 Scene）。 */
    public void detach() {
        this.attached = new WeakReference<>(null);
    }

    /**
     * 直接给某个场景应用当前主题（不改变已挂载的目标）。
     * 供后续任务在弹窗场景上套用同一套令牌。
     */
    public void applyTo(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        // setAll，而不是 add：add 会叠加，第二份令牌以更晚的顺序生效
        scene.getStylesheets().setAll(ThemeTokens.cssFiles(current).stream()
                .map(path -> {
                    var url = ThemeTokens.class.getResource(path);
                    if (url == null) {
                        throw new IllegalStateException("classpath 上找不到样式表 " + path);
                    }
                    return url.toExternalForm();
                })
                .toList());
    }

    private void applyToAttached() {
        Scene scene = attached.get();
        if (scene != null) {
            applyTo(scene);
        }
    }

    /**
     * 自检：每个 {@link ThemeTokens} 在<b>两个模式</b>下都能解析到合法 {@code #RRGGBB}。
     *
     * <p>后续任务（外壳启动、打包验收）用它把「样式表键集合是否完整」变成一个可断言的布尔值。</p>
     *
     * @return 全部解析成功为 {@code true}
     */
    public boolean verifyAllTokensResolve() {
        for (ThemeMode mode : ThemeMode.values()) {
            for (ThemeTokens token : ThemeTokens.values()) {
                String value;
                try {
                    value = ThemeTokens.valueOf(token, mode);
                } catch (RuntimeException e) {
                    return false;
                }
                if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) {
                    return false;
                }
            }
        }
        return true;
    }
}
