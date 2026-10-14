package com.xiaoyiyuan.study.ui.theme;

import java.lang.ref.WeakReference;
import java.util.Map;
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
     * 自检：每个 {@link ThemeTokens} 在<b>两个模式</b>下的解析结果都是合法 {@code #RRGGBB}。
     *
     * <p>后续任务（外壳启动、打包验收）用它把「样式表键集合是否完整」变成一个可断言的布尔值。</p>
     *
     * @return 全部解析成功为 {@code true}
     */
    public boolean verifyAllTokensResolve() {
        return verify(ThemeTokens::values);
    }

    /** 合法令牌值的形态：井号 + 六位十六进制（与 {@code ThemeTokensTest} 逐条断言的格式一致）。 */
    private static final java.util.regex.Pattern TOKEN_VALUE =
            java.util.regex.Pattern.compile("#[0-9A-Fa-f]{6}");

    /**
     * {@link #verifyAllTokensResolve()} 的<b>可注入</b>形式：给定「主题模式 → 令牌表」的取数函数，
     * 逐项检查两个模式下的每个 {@link ThemeTokens}。
     *
     * <p>抽成这个方法是为了让<b>失败分支真的能被测到</b>：评审的 M15 证明旧版实现里
     * 「令牌缺失就返回 false」这条路径没有任何测试（把 {@code catch} 改成 {@code return true}
     * 之后 56 条全绿）。测试现在可以注入三份故意坏掉的取数函数——缺键、值不是 {@code #RRGGBB}、
     * 取数本身抛异常——让三条失败分支各自承重。</p>
     *
     * @param loader 主题模式 → 令牌表（生产路径是 {@link ThemeTokens#values(ThemeMode)}）
     * @return 两个模式下全部令牌都取到合法值为 {@code true}；缺键、值非法、取数失败都为 {@code false}
     */
    static boolean verify(java.util.function.Function<ThemeMode, Map<String, String>> loader) {
        for (ThemeMode mode : ThemeMode.values()) {
            Map<String, String> parsed;
            try {
                parsed = loader.apply(mode);
            } catch (RuntimeException e) {
                // 资源缺失 / 解析失败也算「没能解析」，不把异常漏给调用方
                return false;
            }
            for (ThemeTokens token : ThemeTokens.values()) {
                String value = parsed.get(token.key());
                if (value == null || !TOKEN_VALUE.matcher(value).matches()) {
                    return false;
                }
            }
        }
        return true;
    }
}
