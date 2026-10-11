# JavaFX 视觉实施指南 —— 学习助手（个人学习 · 情绪陪伴桌面应用）

- **适用范围**：`study-desktop`（JavaFX 25 + Java + CSS，不用 FXML）。本文只定义视觉与交互规范，不改动任何业务契约。
- **上位文档**：`docs/superpowers/specs/2026-10-10-study-assistant-design.md`（§5.2 目标四态、§5.8 中性呈现原则、§8.2 状态色与文案规则、§8.3 技术选择）。
- **设计系统来源**：`design-system/study-assistant/MASTER.md`（含「项目覆盖层」）。检索过程与命中情况见 `design-system/study-assistant/SEARCH-LOG.md`。
- **来源标注约定**：每条建议后标 `[技能库命中: <query>]`（该 query 在 ui-ux-pro-max 本地库中确有条目）或 `[通用默认: 未命中数据库]`。

## 设计旋钮取值与理由（`--variance 4 --motion 3 --density 6`）

| 旋钮 | 取值 | 理由 |
|---|---|---|
| `--variance 4` | Balanced / Modern | 不追求极简到没有结构（1–3 偏向居中极简），也不要 8–10 的 Bento/粗野主义不对称。本应用要承载表格与表单，需要可预测的网格。`[技能库命中: study planner mental wellness calm desktop tool --design-system]` |
| `--motion 3` | Subtle | 情绪场景下动效必须几乎察觉不到。8–10 级的编排式动效（pin/Flip/SplitText）在此完全不可接受。`[技能库命中: study planner mental wellness calm desktop tool --design-system]` |
| `--density 6` | Standard（16–64px 量级刻度） | 需要表格化进度，密度不能是营销页的 1–3（24–96px）；但也不是 8–10 的仪表盘密排（8–32px）——过密会制造压迫感，与「不制造压迫感」硬约束冲突。`[技能库命中: study planner mental wellness calm desktop tool --design-system]` |

**注意**：`--design-system` 的默认风格输出（Claymorphism、`#DC2626` destructive、`#059669` 绿色 accent、Lora/Raleway 字体、Scroll Reveal 动效）**全部被项目覆盖层否决**，理由逐条记录在 `MASTER.md` 的「项目覆盖层 §1」。本文所有数值以覆盖层为准。

---

## 1. 设计令牌表

单位约定：**px 一律指 JavaFX 逻辑像素**（见 §3）。除根字号外，所有尺寸给 `em` 值，以便整体随缩放/字号档位等比变化。对比度为本机实测值（WCAG 2.x 相对亮度公式）。

### 1.1 颜色

#### 明色主题（Light）

| 令牌 | 十六进制 | 用途 | 对比度实测 |
|---|---|---|---|
| `-fx-c-bg` | `#F7F6FA` | 窗口/页面背景 | — |
| `-fx-c-surface` | `#FFFFFF` | 卡片、表格、输入框、对话框底色 | — |
| `-fx-c-surface-muted` | `#EFEEF4` | 次级面：表头、分组底、代码/原文预览底 | — |
| `-fx-c-surface-stripe` | `#F2F1F6` | `TableView` 斑马行（偶数行） | 文字于其上 14.21:1 |
| `-fx-c-surface-hover` | `#EBEAF2` | 行/卡片悬停 | 文字于其上 13.37:1 |
| `-fx-c-surface-selected` | `#E3E1F2` | 行选中（非聚焦态） | 文字于其上 12.41:1 |
| `-fx-c-accent-soft` | `#ECEBF7` | 主色浅填充：选中项底、chip 底 | 主色于其上 5.72:1 |
| `-fx-c-border-subtle` | `#E3E1EA` | 分隔线、卡片描边（装饰性，不承担识别功能） | — |
| `-fx-c-border-control` | `#8F8CA0` | **需要被识别的控件边界**：输入框、下拉框、复选/单选、表格外框 | 白底 3.27:1 / 页底 3.04:1 |
| `-fx-c-text` | `#1E2130` | 主文本、表格正文、标题 | 页底 14.84:1 / 白底 15.96:1 |
| `-fx-c-text-secondary` | `#565A6E` | 次文本：说明、单位、表头、时间戳 | 页底 6.33:1 / 白底 6.81:1 |
| `-fx-c-text-tertiary` | `#63677A` | 元信息：文件路径、字数、辅助说明 | 页底 5.20:1 / 次级面 4.85:1 |
| `-fx-c-text-disabled` | `#8F8CA0` | **仅**用于禁用控件（WCAG 对禁用元素豁免对比度） | 白底 3.27:1 |
| `-fx-c-accent` | `#4A55A2` | 主操作按钮底、链接、选中态文字 | 页底 6.27:1 / 白底 6.75:1 |
| `-fx-c-on-accent` | `#FFFFFF` | 主色底上的文字 | 于 `#4A55A2` 上 6.75:1 |
| `-fx-c-accent-hover` | `#3D4AA8` | 主色悬停/按下 | 7.11:1 |
| `-fx-c-focus` | `#3D4AA8` | 焦点环 | 于页面底 7.11:1（远超 3:1 要求） |
| `-fx-c-notice-bg` | `#EDF1F6` | 中性提示条底（保存成功、需确认、说明） | — |
| `-fx-c-notice-text` | `#2F4A66` | 中性提示文本 | 于提示底 8.08:1 |
| `-fx-c-notice-border` | `#C6D2E0` | 提示条描边 | — |

#### 暗色主题（Dark）

| 令牌 | 十六进制 | 用途 | 对比度实测 |
|---|---|---|---|
| `-fx-c-bg` | `#16171C` | 窗口/页面背景 | — |
| `-fx-c-surface` | `#1E2027` | 卡片、表格、输入框、对话框底色 | — |
| `-fx-c-surface-muted` | `#262932` | 次级面 | — |
| `-fx-c-surface-stripe` | `#23252D` | 斑马行 | 文字于其上 12.61:1 |
| `-fx-c-surface-hover` | `#2A2D36` | 悬停 | 文字于其上 11.34:1 |
| `-fx-c-surface-selected` | `#33364A` | 行选中 | 文字于其上 9.80:1 |
| `-fx-c-accent-soft` | `#2A2D45` | 主色浅填充 | 主色于其上 6.60:1 |
| `-fx-c-border-subtle` | `#2E313B` | 分隔线、卡片描边 | — |
| `-fx-c-border-control` | `#737A8E` | 控件边界 | 面底 3.80:1 / 页底 4.18:1 / 次级面 3.39:1 |
| `-fx-c-text` | `#E8E9EE` | 主文本 | 页底 14.76:1 / 面底 13.42:1 |
| `-fx-c-text-secondary` | `#ADB2C0` | 次文本 | 页底 8.44:1 / 面底 7.67:1 |
| `-fx-c-text-tertiary` | `#8E93A3` | 元信息 | 页底 5.84:1 / 面底 5.31:1 |
| `-fx-c-text-disabled` | `#737A8E` | 禁用控件 | 面底 3.80:1 |
| `-fx-c-accent` | `#A8B2F0` | 主色（**暗色下提亮降饱和，不是反相**；同时用作主按钮底） | 页底 8.77:1 |
| `-fx-c-on-accent` | `#16171C` | 主色底上的文字（亮底配深字） | 8.77:1 |
| `-fx-c-accent-hover` | `#B4BDF5` | 主色悬停 | 9.83:1 |
| `-fx-c-focus` | `#9AA6F5` | 焦点环 | 于页底 7.80:1 |
| `-fx-c-notice-bg` | `#20252C` | 中性提示条底 | — |
| `-fx-c-notice-text` | `#C3D0DE` | 中性提示文本 | 于提示底 9.83:1 |
| `-fx-c-notice-border` | `#39404A` | 提示条描边 | — |

> 暗色主题**不依赖阴影**表达层级（深色背景上阴影不可见）。层级 = 面抬升（bg → surface → surface-muted）+ `border-subtle` 描边。
> `[技能库命中: "dark mode contrast" --domain ux]` → `color-dark-mode`：暗色用降饱和/提亮的同色相变体，且必须单独测对比度。

### 1.2 四态中性色 + 「待回报」

目标四态（进行中 / 暂缓 / 已取消 / 已完成）**全部为中性色，不表示好坏**；条目态的「待回报」刻意**不分配独立色相**，靠虚线形状与文字区分。任何状态都必须「文字 + 形状/图标 + 颜色」三重编码，不得只靠颜色。

`[技能库命中: "neutral status color semantics" --domain ux]` → `color-not-only`：Don't 用红/绿单独表示 error/success。
`[技能库命中: "calm low stimulation color" --domain ux]` → 同上（`color-not-only`, `color-contrast`）。

| 状态 | 明色值 | 暗色值 | 形状/图标 | 对比度（明 / 暗） |
|---|---|---|---|---|
| 进行中 | `#3F6B9E` 冷静蓝 | `#93B4D9` | 实心圆点 + 进度弧线图标 | 5.13:1 / 8.33:1 |
| 已完成 | `#5A6478` 冷板岩 | `#A6AEC2` | 实心圆点 + 勾线（1.6px 描边） | 5.53:1 / 8.06:1 |
| 待回报 | 文字 `#565A6E`，虚线边 `#8F8CA0` | 文字 `#ADB2C0`，虚线边 `#737A8E` | 空心虚线圆环（`dash-array: 2 2`） | 6.33:1 / 8.44:1 |
| 暂缓 | `#6B5F8A` 雾紫灰 | `#B3A6CC` | 空心圆环 + 两条横线（暂停符号） | 5.38:1 / 7.88:1 |
| 已取消 | `#63677A` 中性灰 | `#9AA0B0` | 空心圆环 + 一条斜线 | 5.20:1 / 6.84:1 |

**为什么不用绿/红**：绿色会被读作「完成=好」、红色会被读作「失败/落后」，二者都会把状态判断转成价值判断。「已完成」用冷板岩灰 + 勾线，语义由勾线和文字承载；「暂缓」用低彩度雾紫灰，与「已取消」的中性灰形成可辨但等价的差异——**五个状态的视觉权重刻意接近**，不存在任何一个比其他更"亮眼"。

### 1.3 危机资源面板色（必须不吓人）

危机关键词命中时展示**资源面板**，不是红色警报、不弹窗打断、不追问细节。

| 令牌 | 明色 | 暗色 | 用途 | 对比度（明 / 暗） |
|---|---|---|---|---|
| `-fx-c-crisis-bg` | `#F3EFE7` 暖砂 | `#2A2622` | 面板底 | 与页面底差异 1.07:1 / 1.19:1（刻意极轻，靠强调条定位） |
| `-fx-c-crisis-bar` | `#7A6647` | `#C9A87C` | 左侧 3px 强调条（唯一的视觉强调手段） | 于面板底 4.79:1 / 6.70:1 |
| `-fx-c-crisis-text` | `#4A4238` | `#E6DCCE` | 标题与正文 | 8.61:1 / 11.08:1 |
| `-fx-c-crisis-meta` | `#6B5F4C` | `#C9B79A` | 号码、机构名等元信息 | 5.44:1 / 7.67:1 |
| `-fx-c-crisis-border` | `#D8CDBA` | `#4A4238` | 面板描边 | — |

**注意**：面板底色与页面底色对比只有 1.07:1，因此**不允许**去掉左侧强调条——它是面板唯一的可识别边界（且 4.79:1 ≥ 3:1）。这是刻意的：暖砂色"存在但不喊叫"。

### 1.4 字号、行距、字重、字体族

根字号 `-fx-font-size: 15px`（逻辑像素）。所有子级用 `em`。`[技能库命中: "clean readable ui sans" --domain typography]` 与 `[技能库命中: "chinese friendly ui" --domain typography]`（`Chinese Simplified / Noto Sans SC`）→ 采用其"单一无衬线族 + 字重分层级"的思路，并将主字体换成 Windows 原生中文字体以规避回退风险。`[技能库命中: "font size scaling" --stack javafx]` 无字号条目（仅返回图片懒加载），故字号阶梯为 `[通用默认: 未命中数据库]`（对应 quick-reference §6 `font-scale` 的 12/14/16/18/24/32 思路，按中文可读性微调为 12/13/14/15/16/18/21/26）。

| 令牌 | em | @15px 逻辑 px | 字重 | 用途 |
|---|---|---|---|---|
| `-fx-fs-micro` | `0.80em` | 12 | 400 | 表格内辅助标记、极短元信息（**不作正文**） |
| `-fx-fs-caption` | `0.87em` | 13 | 400 | 表头、时间戳、chip 文字 |
| `-fx-fs-small` | `0.93em` | 14 | 400 | 次要正文、表格单元格 |
| `-fx-fs-body` | `1.00em` | 15 | 400 | 正文、输入框、列表项 |
| `-fx-fs-h3` | `1.07em` | 16 | 600 | 卡片标题、分组标题 |
| `-fx-fs-h2` | `1.20em` | 18 | 600 | 页面内区块标题 |
| `-fx-fs-h1` | `1.40em` | 21 | 600 | 页面标题 |
| `-fx-fs-display` | `1.73em` | 26 | 400 | 仅「总览」顶部一句话摘要 |

**字重只用 400 与 600**。原因：Windows 中文字体族通常只有 Light/Regular/Bold 三个实体，`-fx-font-weight: 500` 会被合成或回退，跨机器渲染不一致。`[技能库命中: "clean readable ui sans" --domain typography]` → `weight-hierarchy`（Bold 标题 600–700 / Regular 正文 400）由此收敛为 600/400。

**行距**：JavaFX **没有 `line-height`**，`-fx-line-spacing` 加的是"行与行之间的额外间距"。

> 目标行高 = 字号 × L。则 `-fx-line-spacing = 字号 × (L − 1)`。
> 正文要 L = 1.5 → `-fx-line-spacing: 0.5em`；标题 L = 1.35 → `0.35em`；周记录等长文阅读区 L = 1.6 → `0.6em`。
> `[技能库命中: "clean readable ui sans" --domain typography]` 与 quick-reference §6 → `line-height` 1.5–1.75。

**字体族**：

```css
/* 主字体：Windows 原生中文字体优先，缺字回退到 Noto Sans SC，最后回退系统 UI 字体 */
-fx-font-family: "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI";
```

- `[通用默认: 未命中数据库]`：技能库的 CJK 条目只给了 Google Fonts 方案（`Noto Sans SC` 需联网加载）。本项目是**离线桌面应用**，不允许运行时联网拉字体，因此主族换成随 Windows 附带的「微软雅黑 UI」。这是对命中结果的**离线化改造**，非库中原文。
- 数字列（进度表、情绪强度统计）：优先 `"Sarasa Mono SC", "Noto Sans Mono CJK SC"`（等宽中文，数字天然表格化），缺失时回退主字体 + **右对齐 + 补位空格**。JavaFX 无 `font-variant-numeric`，无法用 CSS 强制等宽数字。`[技能库命中: "clean readable ui sans" --domain typography]` → quick-reference §6 `number-tabular`，实现手段为 `[通用默认: 未命中数据库]`
- **验证项**：JavaFX CSS 的 `-fx-font-family` 支持逗号分隔族列表；若在本机出现族列表解析异常，退路是在 Java 侧 `Font.font("Microsoft YaHei UI", size)` 后逐节点 `setFont()`。**此点需在实施计划第一步的最小窗口里实测确认**。

### 1.5 间距刻度（Density 6）

`[技能库命中: study planner mental wellness calm desktop tool --design-system]` → Standard 密度覆盖 `--space-*` 表。数值按桌面中文界面微调，全部给 `em` 以便随字号档位一起缩放。

| 令牌 | em | @15px 逻辑 px | 用途 |
|---|---|---|---|
| `-fx-sp-1` | `0.27em` | 4 | 图标与文字间隙、chip 内边距 |
| `-fx-sp-2` | `0.53em` | 8 | 行内元素间隙、相邻可点元素最小间隔 |
| `-fx-sp-3` | `0.80em` | 12 | 表单字段间隙、卡片内边距（小） |
| `-fx-sp-4` | `1.07em` | 16 | **标准卡片内边距**、区块内边距 |
| `-fx-sp-5` | `1.60em` | 24 | 区块之间 |
| `-fx-sp-6` | `2.13em` | 32 | 页面内大分区 |
| `-fx-sp-7` | `3.20em` | 48 | 页面上下留白（大屏时的呼吸区） |

该刻度为 4 的倍数、按 4/8/12/16 递进，符合 `[技能库命中: "empty state guidance" --domain ux]` 同批返回的 Material 4pt/8dp 增量体系（quick-reference §5 `spacing-scale`）。

### 1.6 圆角

| 令牌 | 值 | 用途 |
|---|---|---|
| `-fx-r-control` | `6` | 按钮、输入框、下拉框、chip 之外的控件 |
| `-fx-r-card` | `10` | 卡片、面板、`TableView` 外框 |
| `-fx-r-dialog` | `14` | 对话框、弹出层 |
| `-fx-r-pill` | `999`（或用高度的一半） | 状态 chip、情绪 chip、标签 |

刻意**不用** Claymorphism 的 16–24px 厚圆角：厚圆角 + 双层阴影会读作"玩具/儿童"，与情绪场景的克制气质冲突，且会与表格化数据的方正结构打架。`[技能库命中: "minimal calm neutral" --domain style]` → `minimalism-and-swiss-style`。

### 1.7 阴影

JavaFX 语法为 `-fx-effect: dropshadow(gaussian, <color>, radius, spread, offsetX, offsetY);`

| 令牌 | 明色 | 暗色 |
|---|---|---|
| `-fx-sh-card` | `dropshadow(gaussian, rgba(30,33,48,0.07), 3, 0, 0, 1)` | **不用**（改用 `border-subtle` + `surface` 抬升） |
| `-fx-sh-raised`（悬停） | `dropshadow(gaussian, rgba(30,33,48,0.10), 6, 0, 0, 2)` | `dropshadow(gaussian, rgba(0,0,0,0.45), 6, 0, 0, 2)`（仅用于浮出层） |
| `-fx-sh-dialog` | `dropshadow(gaussian, rgba(30,33,48,0.14), 12, 0, 0, 4)` | `dropshadow(gaussian, rgba(0,0,0,0.55), 14, 0, 0, 5)` |

最多两级，且**阴影不参与状态表达**（状态永远靠文字 + 形状 + 中性色）。

---

## 2. JavaFX CSS 落地方式

`[技能库命中: "css theme dark light" --stack javafx]` → `Layer brand CSS after base theme`、`Keep theme switching centralized`、`Use a base theme as baseline`。

### 2.1 available 机制：looked-up colors

JavaFX CSS **没有 CSS 变量（`var()`）、没有 `@media`、没有自定义属性语法**。它提供的是 **looked-up color（查找色）**：任何以 `-` 开头的属性名都定义一个颜色，在子节点里按名字引用，解析时沿父链向上查找。

这带来两个关键能力：

1. **在 `.root` 上定义令牌**，全部后代可直接用 `-fx-c-text`、`-fx-c-surface` 等名字。
2. **在 `.root` 上重写 Modena 自己的查找色**（`-fx-base`、`-fx-accent`、`-fx-focus-color`、`-fx-control-inner-background`、`-fx-text-background-color`、`-fx-selection-bar` 等），一处改动即可让**所有标准控件**跟随项目配色，而不必逐个控件重写。这是本方案能用不到 300 行 CSS 覆盖 Modena 的原因。

**必须**用 `-fx-c-*` 前缀命名自有颜色令牌，避免与 Modena 内建的 `-fx-background`、`-fx-accent`、`-fx-text-fill`、`-fx-border` 等真实属性名撞名。

### 2.2 文件分层与主题切换

```
study-desktop/src/main/resources/css/
  tokens-light.css   ← :root 令牌 + Modena 重着色（必载）
  tokens-dark.css    ← 同名的 :root 令牌，暗色值（按需载）
  app.css            ← 组件级样式（读令牌，从不写死颜色，必载）
  print-export.css   ← 导出/打印用（可选，强制明色）
```

加载顺序（**app.css 必须在最后**，否则组件样式被基线覆盖）：

```java
scene.getStylesheets().setAll(
    css("/css/tokens-light.css"),
    css("/css/app.css"));
```

主题切换集中在一个 `ThemeService`（**不允许**任何控制器自己 `setStyle`，也不允许在各页面里散落 `if (dark)`）：

```java
public enum ThemeMode { LIGHT, DARK, SYSTEM }

public final class ThemeService {
    private static final String TOKENS_LIGHT = "/css/tokens-light.css";
    private static final String TOKENS_DARK  = "/css/tokens-dark.css";
    private static final String APP_CSS      = "/css/app.css";

    /** 主题只改样式表列表，不动任何节点样式 */
    public void apply(Scene scene, ThemeMode mode) {
        boolean dark = switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> isSystemDark();       // 见 2.5：未验证项
        };
        scene.getStylesheets().setAll(
            css(dark ? TOKENS_DARK : TOKENS_LIGHT),
            css(APP_CSS));
    }
    private static String css(String p) {
        return Objects.requireNonNull(ThemeService.class.getResource(p)).toExternalForm();
    }
}
```

**关于 AtlantaFX**：技能库三次命中都推荐把它当作企业级基线（`Use AtlantaFX as the enterprise theme baseline`、`Layer brand CSS after AtlantaFX`、`Use AtlantaFX controls for common app affordances`）。
**本项目的裁决：不引入 AtlantaFX。** 理由：① 上位文档 §8.3 已确定除 Gson 外不加依赖；② 本项目最关键的约束是「禁用色与禁用词审计」，引入第三方主题会让"某个控件到底取哪个颜色"变得难以静态审计，而自建 300 行层可以逐行检查；③ AtlantaFX 默认主题自带 success/danger 语义色，正是本项目要禁用的东西。
**采纳其架构思想**（base theme → 项目令牌层 → 组件层），只是把 base 换成 Modena。若将来决定引入 AtlantaFX，覆盖层与 `app.css` 无需改动，只需在样式表列表最前面插入其 user-agent 样式表——这也是把令牌层独立成文件的价值。

### 2.3 可直接粘贴的令牌块

**`tokens-light.css`**（明色）：

```css
/* ============================================================
   Study Assistant — Light Tokens
   顺序：① Modena 重着色  ② 项目颜色令牌  ③ 字号/间距/圆角/阴影令牌
   规则：本文件不写任何具体组件的选择器（.button/.table-view 等）
   ============================================================ */

.root {
    /* ---------- ① 重着色 Modena 派生基色（让所有标准控件跟随） ---------- */
    -fx-base:                     #FFFFFF;   /* Modena 由它派生控件底色 */
    -fx-background:               #F7F6FA;
    -fx-control-inner-background: #FFFFFF;
    -fx-text-background-color:    #1E2130;   /* Modena 的默认文本色查找点 */
    -fx-accent:                   #4A55A2;
    -fx-focus-color:              #3D4AA8;
    -fx-faint-focus-color:        #3D4AA822; /* 8 位十六进制，Modena 同款写法 */
    -fx-selection-bar:            #E3E1F2;
    -fx-selection-bar-non-focused:#EBEAF2;

    /* ---------- ② 项目颜色令牌 ---------- */
    -fx-c-bg:              #F7F6FA;
    -fx-c-surface:         #FFFFFF;
    -fx-c-surface-muted:   #EFEEF4;
    -fx-c-surface-stripe:  #F2F1F6;
    -fx-c-surface-hover:   #EBEAF2;
    -fx-c-surface-selected:#E3E1F2;
    -fx-c-accent-soft:     #ECEBF7;

    -fx-c-border-subtle:   #E3E1EA;
    -fx-c-border-control:  #8F8CA0;

    -fx-c-text:            #1E2130;
    -fx-c-text-secondary:  #565A6E;
    -fx-c-text-tertiary:   #63677A;
    -fx-c-text-disabled:   #8F8CA0;

    -fx-c-accent:          #4A55A2;
    -fx-c-accent-hover:    #3D4AA8;
    -fx-c-on-accent:       #FFFFFF;
    -fx-c-focus:           #3D4AA8;

    -fx-c-notice-bg:       #EDF1F6;
    -fx-c-notice-border:   #C6D2E0;
    -fx-c-notice-text:     #2F4A66;

    /* 四态中性色 + 待回报（全部中性，无红/绿语义） */
    -fx-c-state-active:    #3F6B9E;  /* 进行中 */
    -fx-c-state-done:      #5A6478;  /* 已完成 */
    -fx-c-state-paused:    #6B5F8A;  /* 暂缓   */
    -fx-c-state-cancelled: #63677A;  /* 已取消 */
    -fx-c-state-await:     #565A6E;  /* 待回报：文字 */
    -fx-c-state-await-line:#8F8CA0;  /* 待回报：虚线环 */

    /* 危机资源面板（暖砂中性，非警报色） */
    -fx-c-crisis-bg:       #F3EFE7;
    -fx-c-crisis-border:   #D8CDBA;
    -fx-c-crisis-bar:      #7A6647;
    -fx-c-crisis-text:     #4A4238;
    -fx-c-crisis-meta:     #6B5F4C;

    /* ---------- ③ 非颜色令牌 ---------- */
    /* 字体族与根字号：唯一允许出现 px 字体大小的地方（逻辑像素） */
    -fx-font-family: "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI";
    -fx-font-size: 15px;

    -fx-fs-micro:   0.80em;
    -fx-fs-caption: 0.87em;
    -fx-fs-small:   0.93em;
    -fx-fs-body:    1.00em;
    -fx-fs-h3:      1.07em;
    -fx-fs-h2:      1.20em;
    -fx-fs-h1:      1.40em;
    -fx-fs-display: 1.73em;

    -fx-ls-body:  0.5em;   /* 行距 = 1.5 × 字号 */
    -fx-ls-head:  0.35em;  /* 行距 = 1.35 × 字号 */
    -fx-ls-read:  0.6em;   /* 行距 = 1.6 × 字号，长文阅读 */

    -fx-sp-1: 0.27em;  -fx-sp-2: 0.53em;  -fx-sp-3: 0.80em;  -fx-sp-4: 1.07em;
    -fx-sp-5: 1.60em;  -fx-sp-6: 2.13em;  -fx-sp-7: 3.20em;

    -fx-r-control: 6;  -fx-r-card: 10;  -fx-r-dialog: 14;  -fx-r-pill: 999;

    -fx-sh-card:   dropshadow(gaussian, rgba(30,33,48,0.07), 3, 0, 0, 1);
    -fx-sh-raised: dropshadow(gaussian, rgba(30,33,48,0.10), 6, 0, 0, 2);
    -fx-sh-dialog: dropshadow(gaussian, rgba(30,33,48,0.14), 12, 0, 0, 4);

    /* 图表：覆盖 Modena 的 CHART_COLOR_1..8，避免出现红/橙序列 */
    CHART_COLOR_1: #3F6B9E;
    CHART_COLOR_2: #5A6478;
    CHART_COLOR_3: #6B5F8A;
    CHART_COLOR_4: #4A55A2;
    CHART_COLOR_5: #63677A;
    CHART_COLOR_6: #3D4AA8;
    CHART_COLOR_7: #8F8CA0;
    CHART_COLOR_8: #565A6E;
}
```

**`tokens-dark.css`**（暗色）——**结构完全相同，只换值**。这样 `app.css` 一行都不用改：

```css
.root {
    -fx-base:                     #1E2027;
    -fx-background:               #16171C;
    -fx-control-inner-background: #1E2027;
    -fx-text-background-color:    #E8E9EE;
    -fx-accent:                   #A8B2F0;
    -fx-focus-color:              #9AA6F5;
    -fx-faint-focus-color:        #9AA6F522;
    -fx-selection-bar:            #33364A;
    -fx-selection-bar-non-focused:#2A2D36;

    -fx-c-bg:              #16171C;
    -fx-c-surface:         #1E2027;
    -fx-c-surface-muted:   #262932;
    -fx-c-surface-stripe:  #23252D;
    -fx-c-surface-hover:   #2A2D36;
    -fx-c-surface-selected:#33364A;
    -fx-c-accent-soft:     #2A2D45;

    -fx-c-border-subtle:   #2E313B;
    -fx-c-border-control:  #737A8E;

    -fx-c-text:            #E8E9EE;
    -fx-c-text-secondary:  #ADB2C0;
    -fx-c-text-tertiary:   #8E93A3;
    -fx-c-text-disabled:   #737A8E;

    -fx-c-accent:          #A8B2F0;
    -fx-c-accent-hover:    #B4BDF5;
    -fx-c-on-accent:       #16171C;
    -fx-c-focus:           #9AA6F5;

    -fx-c-notice-bg:       #20252C;
    -fx-c-notice-border:   #39404A;
    -fx-c-notice-text:     #C3D0DE;

    -fx-c-state-active:    #93B4D9;
    -fx-c-state-done:      #A6AEC2;
    -fx-c-state-paused:    #B3A6CC;
    -fx-c-state-cancelled: #9AA0B0;
    -fx-c-state-await:     #ADB2C0;
    -fx-c-state-await-line:#737A8E;

    -fx-c-crisis-bg:       #2A2622;
    -fx-c-crisis-border:   #4A4238;
    -fx-c-crisis-bar:      #C9A87C;
    -fx-c-crisis-text:     #E6DCCE;
    -fx-c-crisis-meta:     #C9B79A;

    /* 暗色下取消卡片阴影：层级靠面抬升 + 描边 */
    -fx-sh-card: none;
    -fx-sh-raised: dropshadow(gaussian, rgba(0,0,0,0.45), 6, 0, 0, 2);
    -fx-sh-dialog: dropshadow(gaussian, rgba(0,0,0,0.55), 14, 0, 0, 5);

    CHART_COLOR_1: #93B4D9;
    CHART_COLOR_2: #A6AEC2;
    CHART_COLOR_3: #B3A6CC;
    CHART_COLOR_4: #A8B2F0;
    CHART_COLOR_5: #9AA0B0;
    CHART_COLOR_6: #B4BDF5;
    CHART_COLOR_7: #737A8E;
    CHART_COLOR_8: #ADB2C0;
}
```

> 字号、间距、圆角令牌在两个文件里**重复一份相同值**是刻意的：JavaFX 不能跨文件合并 `.root` 规则块，而只加载一个 tokens 文件才能保证切换原子性。若嫌重复，可把非颜色令牌单独放 `tokens-base.css` 并在两个主题下都先加载——**但那样主题切换时要换两张表，容易漏**，本项目选择重复。

### 2.4 组件级骨架（`app.css`，覆盖 Modena）

```css
/* ---------- 焦点：所有可交互控件统一可见焦点环 ---------- */
/* 技巧：平时给透明边框并让它向内外各扩 2（负 insets），只在 :focused 时上色，
   这样焦点出现/消失不会改变布局，不会引起跳动。 */
.button, .toggle-button, .text-field, .text-area, .combo-box, .choice-box,
.check-box, .radio-button, .table-view, .list-view, .scroll-pane, .spinner {
    -fx-border-color: transparent;
    -fx-border-width: 2;
    -fx-border-insets: -2;
}
.button:focused, .toggle-button:focused, .text-field:focused, .text-area:focused,
.combo-box:focused, .choice-box:focused, .check-box:focused, .radio-button:focused,
.table-view:focused, .list-view:focused, .spinner:focused {
    -fx-border-color: -fx-c-focus;
}
/* 2 逻辑像素 + 7.11:1 对比度，满足 focus-appearance（面积 + 3:1） */

/* ---------- 面 / 卡片 ---------- */
.card {
    -fx-background-color: -fx-c-surface;
    -fx-background-radius: -fx-r-card;
    -fx-border-color: -fx-c-border-subtle;
    -fx-border-radius: -fx-r-card;
    -fx-border-width: 1;
    -fx-padding: -fx-sp-4;
    -fx-effect: -fx-sh-card;
}

/* ---------- 文本层级 ---------- */
.page-title  { -fx-font-size: -fx-fs-h1;    -fx-font-weight: 600; -fx-text-fill: -fx-c-text; }
.section-title{ -fx-font-size: -fx-fs-h2;   -fx-font-weight: 600; -fx-text-fill: -fx-c-text; }
.card-title  { -fx-font-size: -fx-fs-h3;    -fx-font-weight: 600; -fx-text-fill: -fx-c-text; }
.body        { -fx-font-size: -fx-fs-body;  -fx-text-fill: -fx-c-text;            -fx-line-spacing: -fx-ls-body; }
.secondary   { -fx-font-size: -fx-fs-small; -fx-text-fill: -fx-c-text-secondary;  -fx-line-spacing: -fx-ls-body; }
.meta        { -fx-font-size: -fx-fs-caption;-fx-text-fill: -fx-c-text-tertiary; }
.reading     { -fx-font-size: -fx-fs-body;  -fx-text-fill: -fx-c-text;            -fx-line-spacing: -fx-ls-read; }
/* Labeled 默认就是 wrap 可开的；Java 侧务必 setWrapText(true)（见 §3） */

/* ---------- 按钮 ---------- */
.button {
    -fx-font-size: -fx-fs-body;
    -fx-padding: -fx-sp-3 -fx-sp-5;
    -fx-min-height: 2.9em;          /* ≈44 逻辑像素，触控/高缩放下的安全高度 */
    -fx-background-radius: -fx-r-control;
    -fx-background-color: -fx-c-surface;
    -fx-text-fill: -fx-c-text;
    -fx-border-color: -fx-c-border-control;
    -fx-border-radius: -fx-r-control;
    -fx-border-width: 1;
    -fx-border-insets: 0;
}
.button:hover   { -fx-background-color: -fx-c-surface-hover; }
.button:pressed { -fx-background-color: -fx-c-surface-selected; }
.button:disabled{ -fx-opacity: 0.45; -fx-text-fill: -fx-c-text-disabled; }
.button.primary {
    -fx-background-color: -fx-c-accent;
    -fx-text-fill: -fx-c-on-accent;
    -fx-border-color: transparent;
}
.button.primary:hover   { -fx-background-color: -fx-c-accent-hover; }
.button.primary:pressed { -fx-background-color: -fx-c-accent-hover; }
/* 主按钮的焦点环需要在深色底上也看得见：改用内描边 + 提亮 */
.button.primary:focused {
    -fx-border-color: -fx-c-focus;
    -fx-border-insets: -2;
    -fx-border-width: 2;
}
/* 次级/文字按钮 */
.button.quiet {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-text-fill: -fx-c-accent;
}
.button.quiet:hover { -fx-background-color: -fx-c-accent-soft; }

/* ---------- 输入控件 ---------- */
.text-field, .text-area, .combo-box, .choice-box, .spinner {
    -fx-background-color: -fx-c-surface;
    -fx-background-radius: -fx-r-control;
    -fx-border-color: -fx-c-border-control;   /* 3.27:1，可识别 */
    -fx-border-radius: -fx-r-control;
    -fx-border-width: 1;
    -fx-text-fill: -fx-c-text;
    -fx-prompt-text-fill: -fx-c-text-tertiary;
    -fx-font-size: -fx-fs-body;
    -fx-padding: -fx-sp-2 -fx-sp-3;
}
.text-field { -fx-min-height: 2.4em; }
.text-area  { -fx-min-height: 6em; -fx-pref-row-count: 6; -fx-wrap-text: true; }
.text-area .content { -fx-background-color: -fx-c-surface; }
.text-field:focused, .text-area:focused { -fx-border-color: -fx-c-focus; }

/* 复选框/单选框：选中态用主色而非 Modena 的蓝 */
.check-box:selected .mark { -fx-background-color: -fx-c-on-accent; }
.check-box:selected .box  { -fx-background-color: -fx-c-accent; -fx-border-color: -fx-c-accent; }
.radio-button:selected .dot { -fx-background-color: -fx-c-accent; }

/* ---------- 中性提示条（替代 Alert.WARNING / ERROR） ---------- */
.notice {
    -fx-background-color: -fx-c-notice-bg;
    -fx-background-radius: -fx-r-control;
    -fx-border-color: -fx-c-notice-border;
    -fx-border-radius: -fx-r-control;
    -fx-border-width: 1;
    -fx-padding: -fx-sp-3 -fx-sp-4;
}
.notice .label { -fx-text-fill: -fx-c-notice-text; -fx-line-spacing: -fx-ls-body; }

/* ---------- 危机资源面板（暖砂，唯一强调是左侧色条） ---------- */
.crisis-panel {
    -fx-background-color: -fx-c-crisis-bg;
    -fx-background-radius: -fx-r-card;
    -fx-border-color: -fx-c-crisis-border;
    -fx-border-radius: -fx-r-card;
    -fx-border-width: 1;
    -fx-padding: -fx-sp-4 -fx-sp-4 -fx-sp-4 -fx-sp-5;
    /* 左侧 3 逻辑像素强调条：用 background-insets 叠一层实现 */
    -fx-background-insets: 0, 0 0 0 0;
}
.crisis-panel .crisis-title { -fx-text-fill: -fx-c-crisis-text; -fx-font-weight: 600;
                              -fx-font-size: -fx-fs-h3; }
.crisis-panel .crisis-body  { -fx-text-fill: -fx-c-crisis-text; -fx-line-spacing: -fx-ls-read; }
.crisis-panel .crisis-meta  { -fx-text-fill: -fx-c-crisis-meta; -fx-font-size: -fx-fs-small; }
/* 实践建议：色条用一个独立 Region（宽 3、高 MAX、背景 -fx-c-crisis-bar）放进 HBox 最左，
   比在 CSS 里堆多层 background-insets 更直观、更好调。 */

/* ---------- 状态 chip（文字 + 形状 + 中性色，三重编码） ---------- */
.chip {
    -fx-background-radius: -fx-r-pill;
    -fx-padding: 0.2em 0.6em;
    -fx-font-size: -fx-fs-caption;
    -fx-background-color: -fx-c-surface-muted;
    -fx-text-fill: -fx-c-text-secondary;
}
.chip.state-active    { -fx-text-fill: -fx-c-state-active;    -fx-background-color: derive(-fx-c-state-active, 92%); }
.chip.state-done      { -fx-text-fill: -fx-c-state-done;      -fx-background-color: derive(-fx-c-state-done, 92%); }
.chip.state-paused    { -fx-text-fill: -fx-c-state-paused;    -fx-background-color: derive(-fx-c-state-paused, 92%); }
.chip.state-cancelled { -fx-text-fill: -fx-c-state-cancelled; -fx-background-color: derive(-fx-c-state-cancelled, 92%); }
/* 待回报：无独立色相，靠虚线边框 */
.chip.state-await {
    -fx-text-fill: -fx-c-state-await;
    -fx-background-color: transparent;
    -fx-border-color: -fx-c-state-await-line;
    -fx-border-radius: -fx-r-pill;
    -fx-border-width: 1;
    -fx-border-style: segments(2, 2);
}
/* 注意：derive(color, 92%) 在暗色主题下会偏亮、与表面色接近，
   因此 chip 的底色在暗色下建议改为显式令牌而不是 derive。见下方警告。 */

/* ---------- 表格 ---------- */
.table-view {
    -fx-background-color: -fx-c-surface;
    -fx-background-radius: -fx-r-card;
    -fx-border-color: -fx-c-border-subtle;
    -fx-border-radius: -fx-r-card;
    -fx-border-width: 1;
    -fx-padding: 0;
    -fx-table-cell-border-color: transparent;
    -fx-selection-bar: -fx-c-surface-selected;
}
.table-view .column-header-background { -fx-background-color: -fx-c-surface-muted; }
.table-view .column-header,
.table-view .filler {
    -fx-background-color: transparent;
    -fx-border-color: transparent transparent -fx-c-border-subtle transparent;
    -fx-size: 2.6em;
}
.table-view .column-header .label {
    -fx-text-fill: -fx-c-text-secondary;
    -fx-font-size: -fx-fs-caption;
    -fx-font-weight: 600;
    -fx-alignment: center-left;    /* 数字列在 Java 侧加 .numeric 改为 center-right */
}
.table-row-cell {
    -fx-background-color: -fx-c-surface;
    -fx-border-color: transparent transparent -fx-c-border-subtle transparent;
    -fx-table-cell-border-color: transparent;
    -fx-cell-size: 2.6em;          /* 逻辑像素单位随 em 缩放 */
    -fx-text-background-color: -fx-c-text;
}
.table-row-cell:odd     { -fx-background-color: -fx-c-surface-stripe; }
.table-row-cell:hover   { -fx-background-color: -fx-c-surface-hover; }
.table-row-cell:selected{ -fx-background-color: -fx-c-surface-selected;
                          -fx-text-background-color: -fx-c-text; }
.table-row-cell:selected .table-cell { -fx-text-fill: -fx-c-text; }
.table-view .table-cell { -fx-padding: 0.4em 0.7em; -fx-text-fill: -fx-c-text;
                          -fx-font-size: -fx-fs-small; -fx-alignment: center-left; }
.table-view .table-cell.numeric { -fx-alignment: center-right; }
.table-view .placeholder .label { -fx-text-fill: -fx-c-text-secondary;
                                  -fx-font-size: -fx-fs-small; -fx-wrap-text: true; }
.table-view .scroll-bar:horizontal .increment-button,
.table-view .scroll-bar:horizontal .decrement-button { -fx-opacity: 0; -fx-padding: 0; }

/* ---------- 列表 ---------- */
.list-view { -fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; }
.list-cell { -fx-background-color: transparent; -fx-padding: -fx-sp-2 -fx-sp-3;
             -fx-text-fill: -fx-c-text; -fx-font-size: -fx-fs-body;
             -fx-cell-size: 2.6em; }
.list-cell:hover    { -fx-background-color: -fx-c-surface-hover; }
.list-cell:selected { -fx-background-color: -fx-c-surface-selected; -fx-text-fill: -fx-c-text; }

/* ---------- 滚动容器 ---------- */
.scroll-pane, .scroll-pane > .viewport { -fx-background-color: transparent; }
.scroll-pane { -fx-border-color: transparent; -fx-padding: 0; }
.scroll-bar:vertical .thumb, .scroll-bar:horizontal .thumb {
    -fx-background-color: -fx-c-border-control;
    -fx-background-radius: -fx-r-pill;
}
.scroll-bar:vertical { -fx-pref-width: 0.7em; }   /* 细滚动条（逻辑单位） */
.scroll-bar:horizontal { -fx-pref-height: 0.7em; }
.scroll-bar .increment-arrow, .scroll-bar .decrement-arrow { -fx-shape: " "; -fx-padding: 0; }

/* ---------- 对话框 ---------- */
.dialog-pane {
    -fx-background-color: -fx-c-surface;
    -fx-padding: -fx-sp-4;
}
.dialog-pane > .header-panel { -fx-background-color: -fx-c-surface-muted; }
.dialog-pane > .header-panel .label { -fx-text-fill: -fx-c-text; -fx-font-size: -fx-fs-h3;
                                      -fx-font-weight: 600; }
.dialog-pane .button-bar .button { -fx-min-width: 6em; }
.dialog-pane .content.label { -fx-text-fill: -fx-c-text; -fx-line-spacing: -fx-ls-body; }

/* ---------- 图表 ---------- */
.chart { -fx-padding: 0; -fx-background-color: transparent; }
.chart-plot-background { -fx-background-color: -fx-c-surface; }
.chart-vertical-grid-lines { -fx-stroke: -fx-c-border-subtle; -fx-stroke-dash-array: 2 4; }
.chart-horizontal-grid-lines { -fx-stroke: -fx-c-border-subtle; }
.chart-alternative-row-fill, .chart-alternative-column-fill { -fx-fill: transparent; -fx-stroke: transparent; }
.chart .axis { -fx-tick-label-fill: -fx-c-text-secondary; -fx-tick-label-font-size: -fx-fs-caption;
               -fx-border-color: -fx-c-border-subtle; }
.chart .axis .axis-label { -fx-text-fill: -fx-c-text-secondary; -fx-font-size: -fx-fs-caption; }
.chart .chart-series-line { -fx-stroke-width: 2; }
.default-color0.chart-series-line { -fx-stroke: -fx-c-state-active; }
.default-color0.chart-line-symbol {
    -fx-background-color: -fx-c-surface, -fx-c-state-active;
    -fx-background-radius: 999, 999;
    -fx-background-insets: 0, 0.28em;
    -fx-padding: 0.36em;   /* 点标记尺寸：随字号缩放，不写死 px */
}
.chart .chart-legend { -fx-background-color: transparent; }
.chart .chart-legend-item { -fx-text-fill: -fx-c-text-secondary; }
.chart .chart-line-symbol:filled { -fx-effect: null; }

/* ---------- 进度表达：刻意不用 ProgressBar ---------- */
/* 禁用 -fx-progress-color 的高亮表达；周进度一律用文字 "已回报 3 / 6" + 中性点阵。 */
```

> **`derive()` 的坑（重要）**：`derive(color, 92%)` 是"更亮 92%"，明色主题下会得到很浅的底（正确），但**暗色主题下会得到接近白色**，chip 会变成刺眼白块。因此暗色主题下**不要用 `derive()` 造浅底**，改为在两个 tokens 文件里各定义一份显式 chip 底色令牌，例如 `-fx-c-state-active-soft`。上表把 chip 写成 `derive()` 是为了演示明色思路；落地时按令牌处理。

### 2.5 明暗切换的边界与未验证项

- **已确定可行的机制**：`.root` 上的令牌重定义 + 切换样式表列表。looked-up color 沿父链解析，因此根节点一改，全部后代同步（`[技能库命中: "css theme dark light" --stack javafx]` → `Keep theme switching centralized`）。
- **未验证项（必须实测）**：JavaFX 22+ 引入了 `Platform.getPreferences()`（含 `ColorScheme`）。若本机 JavaFX 25 上该 API 可读系统深浅色偏好，则 `ThemeMode.SYSTEM` 可以直接跟随 Windows；**否则**退化为在设置页提供纯手动的「明 / 暗」两档，并加一句说明（不要臆造能自动跟随）。
- **必须避免的做法**：在控制器里逐个 `node.setStyle("-fx-background-color: #...")`（技能库明确列为 Don't），以及直接修改 Modena 源样式。
- **切换时的可访问性**：主题切换后焦点环颜色随之变化，需目测确认 `:focused` 控件在两种主题下都清晰（暗色下 `#9AA6F5` 于 `#1E2027` 上 7.80:1）。

---

## 3. DPI / 缩放安全规则（本机 Windows 200% 缩放）

`[技能库命中: "layout responsive dpi scaling" --stack javafx]` →
`Set sensible min pref and max sizes`（Don't: Lock every control to fixed width and height）、
`Prefer constraints over fixed coordinates`（Don't: Hard-code pixel positions and sizes, severity **High**）。
`[技能库命中: "tableview column cell" --stack javafx]` → `Configure TableView cell value factories with properties`、`Use cell factories for custom rendering`。
`[技能库命中: "font size scaling" --stack javafx]` → 无字号相关条目（只返回图片后台加载），故本节 DPI 细则为 `[通用默认: 未命中数据库]` + JavaFX 平台机制说明。

### 3.1 先理解机制：px 是逻辑像素

JavaFX 在 Windows 上通过 `glass.win.uiScale` 自动应用 DPI 缩放。`Screen.getPrimary().getOutputScaleX()` 在 200% 缩放下返回 `2.0`。**CSS 里写的 px 是逻辑像素**，渲染时被自动放大 2 倍成物理像素。

所以真正的危险**不是"用了 px"**，而是下面三类：

1. **把逻辑像素当成物理尺寸做算术**（例如"这行字 20px 宽，窗口 800px 能放 40 个字"）。逻辑像素与物理像素不是 1:1 时，这种估算全错。
2. **锁死容器尺寸**，导致文字放大后溢出/截断。
3. **在代码里手动除以/乘以 `getOutputScaleX()`** —— 会与 JavaFX 自身的缩放叠加，界面变得极小或极大。

### 3.2 禁止清单（写进 code review）

| # | 禁止写法 | 为什么在 200% 下会坏 | 替代 |
|---|---|---|---|
| 1 | `node.setPrefWidth(328)` / `setLayoutX(120)` / `setLayoutY(...)` | 绝对定位不随内容与字号变化；中文放大后必然重叠 | `Region.USE_COMPUTED_SIZE` + 容器约束 |
| 2 | `button.setMinSize(96, 32); button.setMaxSize(96, 32);`（min==max） | 彻底锁死按钮，中文标签在两字变四字或字号放大时被裁 | 只给 `setMinHeight`；宽度交给内容 |
| 3 | `-fx-pref-width: 120px; -fx-pref-height: 30px;` 写在会显示中文的控件上 | 同上 | `-fx-min-height: 2.9em;` + `-fx-padding` |
| 4 | `Math.round(node.getWidth() / 3.0)` 手算列宽 | 依赖运行时物理宽，缩放变化后错位 | `ColumnConstraints.setPercentWidth(33)` |
| 5 | 位图图标 (`ImageView` + PNG) 当图标 | 200% 下模糊；100% 下过小 | `SVGPath`（矢量，`-fx-stroke` 用令牌色） |
| 6 | 在 `Canvas` 上按固定像素画表格/折线 | 缩放后线宽与坐标全错 | 优先用 `TableView` / `LineChart`；若必须 Canvas，则监听 `widthProperty`/`heightProperty` 重绘 |
| 7 | 用一个 `HBox` 横排「标签 + 值」且不设 wrap | 200% 下值被挤出可视区 | 标签与值改为上下 `VBox`，或值 `setWrapText(true)` + `HBox.setHgrow(值, ALWAYS)` |
| 8 | `stage.setWidth(1280); stage.setHeight(800);` 作为**硬性**要求 | 本身是逻辑像素、可用，但不能作为后续布局的假设 | 给初始尺寸 + `setMinWidth(900) / setMinHeight(620)`（逻辑像素），之后全部用约束 |
| 9 | 细边框写 `-fx-border-width: 0.5px` | 某些缩放比例下渲染为 0 而消失 | 保持 `1`，用更浅的颜色降低视觉重量 |
| 10 | 依赖 `-fx-fixed-cell-size` 或把 `-fx-cell-size` 写死成小值 | 行内中文换行后被裁 | `-fx-cell-size: 2.6em`，行内容允许 wrap |

### 3.3 必须遵循的做法

**① 根字号 + em 单位：全局一处可调**

```css
.root { -fx-font-size: 15px; }     /* 逻辑像素；200% 下渲染为 30 物理像素 */
.card { -fx-padding: -fx-sp-4; }   /* = 1.07em，随字号一起放大 */
```

字号档位（设置页「字号」）只改 `.root` 的 `-fx-font-size`（15 / 17 / 19），**其余全部自动跟随**——这正是所有令牌用 em 的原因。

**② 用约束式布局而不是尺寸**

```java
// 表单：两列，左列固定比例、右列吃掉剩余空间
ColumnConstraints c0 = new ColumnConstraints();
c0.setPercentWidth(30);
ColumnConstraints c1 = new ColumnConstraints();
c1.setHgrow(Priority.ALWAYS);
c1.setMinWidth(Region.USE_COMPUTED_SIZE);
grid.getColumnConstraints().setAll(c0, c1);

// 内容区纵向撑满
VBox.setVgrow(weekList, Priority.ALWAYS);

// 输入框横向撑满（不要给固定宽）
nameField.setMaxWidth(Double.MAX_VALUE);
```

**③ 中文文本一律允许换行**

```java
label.setWrapText(true);
label.setMaxWidth(Double.MAX_VALUE);   // 让 wrap 有意义
```

长文阅读区（周记录正文、AI 回复、危机资源说明）额外限宽，避免 200% 下变成超长单行：

```css
.reading { -fx-max-width: 46em; }   /* ≈ 34 个汉字/行，接近 line-length 65–75 字符的建议 */
```

`[技能库命中: "clean readable ui sans" --domain typography]` → quick-reference §6 `line-length`（65–75 字符）与 §5 `line-length-control`；中文按 1 汉字 ≈ 2 字符换算，取 34 汉字/行。具体 `46em` 数值为 `[通用默认: 未命中数据库]`。

**④ 200% 缩放下的具体注意点**

- **行高与图标**：字号放大后，图标必须跟着放大。`SVGPath` 用 `-fx-scale-x/-fx-scale-y` 绑到 `em` 换算值，或直接把 path 定义在 20 单位视窗内并设 `-fx-scale-x: 1em` 之类比例（Java 侧按根字号计算更稳）。
- **`TableView` 列宽**：给 `TableColumn` 设 `setPrefWidth` 时可接受，但**必须同时**设 `setMinWidth` 一个较小值（如 60），否则窄窗口下出现横向滚动；更好的做法是让名称列 `setMaxWidth(Double.MAX_VALUE)` 吸收剩余宽度。
- **对话框**：不设固定宽。`dialogPane.setPrefWidth(Region.USE_COMPUTED_SIZE)`，内容区 `-fx-max-width: 46em`，超长文本用 `ScrollPane`（`setFitToWidth(true)`）。
- **避免滚动嵌套**：`[技能库命中: "empty state guidance" --domain ux]` 同批返回的 §5 `scroll-behavior` 提示"避免嵌套滚动"；JavaFX 里表现为 `ScrollPane` 套 `TableView` 会导致滚轮被内层吃掉。规则：一个页面只允许**一个**纵向 `ScrollPane`，表格本身不放进 `ScrollPane`，而是 `VBox.setVgrow(table, ALWAYS)`。
- **屏幕阅读器字号**（Windows 辅助功能里的「文本大小」）会与 DPI 缩放叠加。**所有容器必须容忍一行文字变两行**：涉及文字的地方不用 `HBox` 硬排，改用 `FlowPane`（`setHgap/setVgap`）或 `VBox`。
- **回归验证**：改完界面后在 Windows 显示设置里以 **100% / 150% / 200%** 三档各截一次图，检查清单：无截断、无重叠、无横向滚动条、焦点环完整可见、表格行不裁字。

**⑤ 布局骨架的通用壳**

```
BorderPane (root)
├── left   : 侧边导航 VBox（宽 13.5em；窄窗时收为 4.5em 图标栏）
├── top    : 页头 HBox（页标题 + 周序号 + 主题切换 + 设置入口；setHgrow(spacer, ALWAYS)）
├── center : ScrollPane(fitToWidth=true) > VBox(spacing = -fx-sp-5)  ← 唯一纵向滚动容器
└── bottom : 状态条 HBox（文件路径 + 保存状态；**不放进度百分比**）
```

`focus-not-obscured` 的实现要点：底栏是 `BorderPane.bottom`（占据自己的空间），**不要**用 `StackPane` 把底栏浮在内容上——浮层会遮住滚到最底部的焦点控件。`[技能库命中: "keyboard focus visibility" --domain ux]` → `focus-not-obscured`、`focus-states`。

---

## 4. 禁用色与禁用词清单

### 4.1 禁用色相（状态语义层）

| 禁用对象 | 判定规则 | 为什么 |
|---|---|---|
| 红色 | 色相 345°–20° 且饱和度 > 12% | 读作 error / 危险 / 警告 |
| 橙色 | 色相 20°–45° 且饱和度 > 12% | 读作 warning / 提醒 |
| 高饱和绿 | 色相 90°–160° 且饱和度 > 20% | 读作 success（"完成=好"） |
| 任何"高饱和"色（HSV 的 S > 45%）作为大面积填充 | — | 违反低刺激要求 |
| 语义令牌名 | `-fx-c-danger` / `-fx-c-error` / `-fx-c-warning` / `-fx-c-success` / `-fx-c-critical` | 令牌名会诱导后续开发者填入红/绿 |

**具体被否决的库默认值**（已记录在 MASTER 覆盖层）：
`#DC2626`（destructive 红）、`#DC2626` 于明色底 4.49:1 且语义错、`#059669`（accent 绿，于明色底仅 3.50:1，既不达标又语义错）、`#EF4444` / `#22C55E`（暗色红/绿）。
`[技能库命中: "calm wellness muted" --domain color]` 返回的三套调色板**都带 `Destructive: #DC2626`**——这部分**明确不采纳**；采纳的只是它们的"低刺激底色 + 中性 muted + 单一强调色"结构。

**深一层的理由**：本应用里"没做"和"做得少"是常态。任何红色/绿色都会把"今天没记录"变成一次隐性指责。四态与条目态的视觉权重刻意做到接近，正是为了让"暂缓考研"在视觉上和"本周在推进课程"一样正当中性。

**唯一允许的强调色**：`-fx-c-accent`（低饱和靛蓝，明 `#4A55A2` / 暗 `#A8B2F0`），且**只用于**主操作按钮、链接、选中态、焦点环——不用于任何状态语义。

### 4.2 JavaFX 里最容易"漏进红色"的地方

| 位置 | 问题 | 处理 |
|---|---|---|
| `Alert` / `AlertType.ERROR` / `WARNING` | Modena 给 ERROR/WARNING 配红色调图标与默认按钮 | **一律使用 `AlertType.NONE`** 或自建 `Dialog`，配 `-fx-c-notice-*` 提示条样式 |
| `TextInputControl` 的非法输入 | 无内建红星，但开发者常自己加 | 校验信息用 `.notice`，文案写"这里需要一个日期，例如 2026-10-11"（说明+补救路径），不写"输入无效" |
| `ProgressBar` | `-fx-progress-color` 常被配成红/绿；语义上"百分比"本身就是落后暗示 | **本应用不使用百分比进度条表达计划完成度**；用「已回报 3 / 6」文字 + 中性点阵 |
| `TableView` 的 `:selected` | Modena 默认选中蓝偏亮 | 已用 `-fx-c-surface-selected` 覆盖 |
| 自定义 CSS 里的 `red` / `crimson` / `orange` 关键字 | 手滑 | 构建期扫描（见 4.4） |
| 图表默认 `CHART_COLOR_1`（Modena 是 `#f3622d` 橙红） | 默认序列色就是红橙系 | 两个 tokens 文件里都已覆盖 `CHART_COLOR_1..8` |

### 4.3 禁用词：作用方式与检查点

**8 个禁用词**（一个字都不许出现在任何面向用户的位置）：

```
未完成  逾期  落后  断签  失败  警告  已放弃  未坚持
```

**它们可能藏身的 9 个位置**（按容易漏的程度排序）：

1. **表头与列名**——最容易漏，"逾期"常常在这里变成列标题。检查点：所有 `TableColumn` 的标题字符串。
2. **AI 生成内容**——规划文本、情绪分析文本、周复盘文本。模型完全可能写出"已逾期""未坚持"。检查点：**AI 返回文本在渲染前必须先过同一个扫描器**，命中则替换为中性同义表述（并记录一条日志，便于以后改提示词）。
3. **空数据/空状态文案**——必须是「待回报」或「未回报」，不能是「未完成」。`[技能库命中: "empty state guidance" --domain ux]` → `empty-states`：给有帮助的信息 + 一个动作，不要空白屏；同时注意库里的示例文案 `No items yet. Create one!` 是**通用鼓励语气**，本项目要改成中性陈述（"本周还没有安排。可以从『周计划』开始，或让助手给一个建议。"）——即采纳其"必须给引导"，不采纳其"感叹号式鼓励"。
4. **`Alert` / `Dialog` 的标题与按钮**——例如 "Warning"、"Failed"。
5. **Tooltip 与 `accessibleText` / `accessibleHelp`**——屏幕阅读器专用文本**也是界面文案**，同样在扫描范围内。
6. **窗口标题、任务栏标题、菜单项**。
7. **周序号相关的派生文案**——例如"连续记录 0 天"。
8. **通知/状态条的瞬时文案**——"保存失败"必须改成"没有保存成功，文件可能被其他程序占用。可以重试，或先另存一份。"
9. **导出文件与打印样式里的表头**。

**落地手段**（把规则变成机器可查的）：

- **单一文案来源**：所有界面文案集中到 `ui-text.properties`（或一个 `UiText` 常量类），**不允许**在业务代码里内联中文字面量。这样扫描面从"全仓库"缩到一个文件 + Java 字面量兜底。
- **状态文案单一映射**：`StatusText` 枚举负责四态与条目态的唯一文案，各页面只能取枚举，不得自造。
- **构建期测试**（对应上位文档测试策略第 13 项 `DisabledWordTest`）：扫描范围 =
  `src/main/resources/**/*.properties`、`**/*.css`、`**/*.md`（内置提示词）、
  Java 源码中的**中文字符串字面量**（正则 `"[^"]*[\u4e00-\u9fa5][^"]*"`）、
  以及**测试用的内置提示词快照**。
  额外两条断言：① 不出现诊断性词表（`抑郁`、`焦虑症`、`中度`、`得分`）；② 不出现 `-fx-c-danger|error|warning|success`。
- **扫描范围必须排除文档目录（重要，否则测试自我击败）**：本文件与 `design-system/**` 为了**规定**禁用词而**必须写出这 8 个词**。若 `DisabledWordTest` 扫到 `docs/**` 或 `design-system/**`，它会永远红灯。扫描白名单应限定为：
  `study-desktop/src/main/resources/**`（properties / css / 内置提示词）、
  `study-desktop/src/main/java/**` 的中文字符串字面量、
  `study-core/src/main/resources/**`（生成文案模板）。
  并显式断言排除 `docs/**`、`design-system/**`、`*.md`（除内置提示词文件，它们放在 `resources/prompts/` 下而非仓库文档目录）。
- **运行时守卫**（开发构建）：`UiText.guard(String)` 在渲染前断言；命中时抛出带定位信息的异常（只在 `-Dstudy.dev=true` 时启用）。
- **AI 文本入口守卫**：`AiTextSanitizer.neutralize(String)` 在 AI 文本进入 UI 前调用，命中禁用词时替换为中性表述并记录。这条是**关键词测试之外**的第二道网，因为 AI 输出不可预测。

**中性替代词表**（建议直接写进 `UiText`）：

| 禁用词 | 中性替代 |
|---|---|
| 未完成 | 待回报 / 还没回报 |
| 逾期 | （删除该概念；日期只作展示，见上位文档 §5.3） |
| 落后 | （删除；不做进度对比） |
| 断签 | （删除；不做连续记录统计） |
| 失败 | 没有保存成功 / 这次没有完成 |
| 警告 | 提示 / 需要你确认 |
| 已放弃 | 已取消 |
| 未坚持 | 暂缓 / 这一段时间没有安排 |

---

## 5. 八类页面的布局骨架建议

通用：每页 = 页头（标题 + 一句中性摘要）+ 内容 `VBox`（`-fx-sp-5` 间距）+ 可选底部操作条。**每页只有一个主操作按钮**（`primary-action`，`[技能库命中: "minimal calm neutral" --domain style]` 同批规则）。

### 5.1 进度总览

```
VBox (spacing -fx-sp-5)
├── [卡] 本周
│     └── HBox: 「第 4 周」+「10-05 ~ 10-11」(meta) + spacer + 主题/刷新
│         VBox: 底线条目 ×3–6
│           每行 = HBox[ 目标chip(小) | 项(body) | 量(secondary) | 状态chip(待回报/进行中/已完成) ]
├── [区] 目标（横排卡片，FlowPane，卡片 min-width 16em）
│     └── 卡 = VBox[ 名称(h3) | 状态chip(四态中性) | 时间窗(meta) | 一句话(secondary, wrap) ]
│         暂缓目标默认收进可展开的「暂缓中」分组（折叠标题用中性措辞）
└── [区] 本周该做什么
      └── TableView（课程 | 本周任务 | 截止(展示) | 状态）
```

- 空态：「本周还没安排。可以从『周计划』开始，或让助手给一个建议。」
- **不做**：完成率、百分比、与上周的对比箭头、任何红色的日期。

### 5.2 周计划

```
VBox
├── [条] 周切换： [← 第 4 周]  10-05 ~ 10-11  [第 5 周 →]     （无"进度差"提示）
├── SplitPane (默认 62 / 38，可拖动)
│   ├── 左：GridPane 表单（目标 | 项 | 量 | 怎么安排 | 状态）
│   │     目标 = ComboBox（只列进行中目标 + 一个"不挂目标"选项）
│   │     量 = TextField（自由文本，不解析单位）
│   │     状态 = ChoiceBox（待回报 / 进行中 / 已完成）
│   │     行末 = 删除（quiet 按钮，二次确认用中性措辞）
│   └── 右：VBox「如果要减，先减这些」
│         ListView（自由文本，行内可编辑）+ 每行 上移 / 下移
└── [底] HBox: [保存] (primary)  [追加新一周] (quiet)   spacer   保存状态(meta)
```

- 字段全部允许中文换行；`量` 列宽给 `PercentWidth`，不写死。
- 保存成功用 `.notice` 中性条，**不用**红色/绿色闪烁。

### 5.3 周记录

```
VBox
├── [条] HBox: 「共 6 周记录」(meta)  spacer  [追加新一周] (primary)
└── 时间线 VBox（每张卡 = 一周）
     卡 = VBox
       ├── HBox: 第 N 周（MM-DD ~ MM-DD）(h3)  +  spacer  + 「展开/收起」
       └── TextArea（就地编辑，wrap，rows≥6，-fx-line-spacing: -fx-ls-read）
```

- 时间线左侧用一条 1px `-fx-c-border-subtle` 竖线 + 空心圆点做视觉串联；**不做**"哪周缺了"的标记。
- 外部修改检测命中时：**不静默覆盖**，顶部插入一条 `.notice`：文件在本应用之外被改过 + [查看差异] [以磁盘为准] [保留我的修改] 三个中性按钮。

### 5.4 目标与阶段

```
VBox
├── [区] 目标
│     ├── 工具条： [新增目标] (primary)  spacer  「显示：进行中 / 全部」(ChoiceBox)
│     └── TableView（名称 | 状态 | 优先级 | 时间窗 | 说明）
│         状态列 = 自定义 cell（chip，四态中性色 + 形状）
│         优先级列 = .numeric 右对齐
├── [区] 阶段排期
│     └── TableView（阶段 | 时间窗 | 主攻 | 验收标准）+ 行操作 上移 / 下移
└── [底] 状态条： 「当前无进行中的阶段排期」——中性提示，不显示为空白或错误
```

- 状态编辑：点状态 chip → `ChoiceBox` 弹出四态；切到「暂缓」时**不弹确认警告**，只在状态条显示一句中性说明（"已暂缓。切换到该目标所在页面时可以随时恢复为进行中。"）。
- 暂缓目标的阶段行显示为「暂缓中」，`-fx-c-state-paused`，**不进入本周**。
- 上位文档 §5.5 要求"无进行中阶段时输出提示句，不显示为空白"——对应 `empty-states` 的正面做法。

### 5.5 情绪记录

```
VBox
├── [卡] 今天
│     ├── GridPane
│     │    情绪： FlowPane 的 chip 多选（词表来自设置，默认：平静/疲惫/焦虑/低落/烦躁/轻松/专注/孤单）
│     │          选中态 = -fx-c-accent-soft 底 + -fx-c-accent 边（不是红黄绿）
│     │    强度： 5 个中性圆点（单选；空心→实心，用 -fx-c-accent，不用红黄绿渐层）
│     │    睡眠： TextField（数字，后缀「小时」用 meta 文本，不写进输入框）
│     │    备注： TextArea（wrap，rows 3）
│     └── [保存本次记录] (primary)
├── [区] 最近 14 天
│     └── 列表/表格（日期 | 情绪 chips | 强度点阵 | 睡眠 | 备注摘要）
└── [区] 需要的时候（危机资源面板入口，常态是安静的 quiet 按钮，不是常驻红条）
```

- **强度绝不用红黄绿渐层**：用同色实心圆点数量表达（1–5），旁边配文字「强度 3」（`number-tabular` 思路）。
- 保存反馈："已记录。"（中性，无感叹号）。
- 危机词命中时：在**当前页内**插入 `.crisis-panel`（暖砂底 + 左色条 + 热线列表），不弹窗、不遮罩、不追问。

### 5.6 情绪趋势

```
VBox
├── [条] 区间选择： 最近 14 天 / 30 天 / 90 天 (ToggleGroup)   spacer   [导出为文本]
├── [卡] 图表区
│     ├── LineChart（见 §7.2）
│     └── VBox 文字摘要（必填，不是可选）
│           「最近 30 天记录 18 次；出现最多：疲惫 7 次、平静 5 次、焦虑 4 次。」
│           「睡眠记录 12 次，多数在 6–7 小时。」
├── [卡] 数据表（日期 | 情绪 | 强度 | 睡眠）—— TableView，可排序
└── [区] AI 情绪分析入口
      └── [开始分析] (primary) → 先弹「将要发送的原文」预览（.notice 底 + 只读 TextArea）
```

- 空数据：显示空态卡「还没有记录。从『情绪记录』记一次，这里就会出现趋势。」——**不显示空坐标轴**（`empty-data-state`：Don't 显示空图框）。
- **禁止**：把 30 天情绪聚合成一个"总分"、任何 `得分 XX` 形式、任何诊断性标签（`抑郁`/`焦虑症`/`中度`）。

### 5.7 AI 对话

```
BorderPane
├── left   : 会话列表 ListView（宽 14em，可折叠）  [新建对话] (quiet)
├── center : VBox
│     ├── ScrollPane(fitToWidth) > VBox（消息流）
│     │     用户消息：右对齐气泡，-fx-c-accent-soft 底，圆角 -fx-r-card
│     │     助手消息：左对齐气泡，-fx-c-surface 底 + -fx-c-border-subtle 描边
│     │     助手长文本：-fx-max-width: 46em，-fx-line-spacing: -fx-ls-read
│     │     （不用红/绿区分角色；角色靠对齐 + 底色区分）
│     └── [卡] 输入区
│           TextArea（wrap，rows 3，Enter 发送 / Shift+Enter 换行）
│           HBox: [发送] (primary，发送中禁用 + 转圈)  spacer  字数(meta)
└── bottom : 状态条：模型名 / 连接状态 —— **用中性文字，不用红点**
```

- **情绪分析前的原文确认面板**（上位文档 §6.1 硬要求）：`.notice` 样式的可折叠卡，标题「将要发送给 AI 的原文」，内容只读 `TextArea`，按钮 [发送] / [取消]。
- 连接失败：`AI 暂时没有回应。可以重试，或先检查设置里的配置。` + [重试]——**不用**红色、不用「失败」。
- 危机词命中：消息流末尾插入 `.crisis-panel`，输入区**保持可用**（不阻断表达），面板不改写用户的话、不评判。

### 5.8 设置

```
HBox
├── left  : 二级导航 ListView（宽 13em）
│            AI 配置 / 文件与路径 / 导入与导出 / 备份管理 / 提示词状态 /
│            迁移向导 / 显示偏好 / 危机资源号码
└── center: ScrollPane(fitToWidth) > VBox（当前分区）
     ├── AI 配置： 服务地址(TextField) / 密钥(PasswordField + [显示] 切换) / 模型(ComboBox)
     │            [测试连接] (quiet) → 结果显示在 .notice 里（"连接正常。" / "没有连上……"）
     ├── 文件与路径： 只读 TextField + [选择文件]；显示编码与行尾(meta)
     ├── 导入与导出： [导出备份] [从备份恢复]——恢复走二次确认 + 中性措辞
     ├── 备份管理： TableView（时间 | 大小 | 操作）+ 保留上限说明；超限自动删最旧
     ├── 提示词状态： 每个提示词一行（名称 | 内置/自定义 | [查看] [恢复内置]）
     ├── 迁移向导： [开始迁移] (primary) → 前置说明 + diff 预览 + 备份确认（三步，每步可返回）
     ├── 显示偏好：
     │    主题： 明 / 暗 / 跟随系统 (ToggleGroup)
     │    减少动效： CheckBox（默认读取系统动画设置）
     │    字号： 15 / 17 / 19 (ToggleGroup) → 只改 .root 的 -fx-font-size
     │    暂缓与已取消目标： 折叠 / 灰显可见 / 隐藏 (ChoiceBox)
     │    情绪选项列表： 可增删改的 ListView
     └── 危机资源号码： TableView（渠道 | 号码 | 来源）+ [新增] [删除] [恢复默认]
```

- **危险操作**（删除备份、以磁盘为准覆盖、清空情绪记录）统一模式：二次确认 + 说明后果 + 说明可撤销性（`confirmation-dialogs`、`undo-support`）。**不引入红色按钮**；用「主按钮给安全选项、quiet 按钮给危险选项」的排布差别 + 明确的文字来区分。
- `[技能库命中: "empty state guidance" --domain ux]` → `Active State`：当前分区必须高亮（用 `-fx-c-accent-soft` + 左侧 2px `-fx-c-accent` 竖条）。

---

## 6. 可访问性清单

### 6.1 对比度

| 要求 | 阈值 | 本项目落实 |
|---|---|---|
| 正文文本 vs 底色 | ≥ 4.5:1 | 所有文本令牌已实测：明色最低 5.20:1，暗色最低 5.31:1 |
| 大号文本（≥18.66px 常规 / ≥24px） | ≥ 3:1 | 全部达 4.5:1 以上，超额 |
| 控件可识别边界 | ≥ 3:1 | `-fx-c-border-control`：明 3.27:1 / 暗 3.80:1 |
| 焦点指示 | ≥ 3:1 且面积 ≥ 2px 周长 | `-fx-c-focus`：明 7.11:1 / 暗 7.80:1，2 逻辑像素全周长描边 |
| 图表数据线 vs 绘图区 | ≥ 3:1 | `-fx-c-state-active` 于 `-fx-c-surface`：明 5.51:1 / 暗 8.33:1 |
| 装饰性分隔线 | 无要求 | `-fx-c-border-subtle` 明 1.29:1 / 暗 1.25:1（刻意极轻） |

来源：`[技能库命中: "calm low stimulation color" --domain ux]`、`[技能库命中: "neutral status color semantics" --domain ux]`、`[技能库命中: "dark mode contrast" --domain ux]` → 均返回 `color-contrast`（4.5:1 / 3:1）；`[技能库命中: "clean readable ui sans" --domain typography]` → `color-accessible-pairs`。

**审计纪律**：新增任何颜色令牌，必须先在对比度计算里跑一遍并通过阈值，才能写进 tokens 文件。不允许"看起来还行"就上线。

### 6.2 键盘与焦点

- **可见焦点环**：所有可交互控件统一 `:focused { -fx-border-color: -fx-c-focus; -fx-border-width: 2; -fx-border-insets: -2; }`——透明边框常驻保证**焦点出现不引起布局跳动**。
  `[技能库命中: "keyboard focus visibility" --domain ux]` → `focus-states`（不要移除 outline 而不给替代）；`[技能库命中: "accessibility focus keyboard" --stack javafx]` → `Keep keyboard focus visible`（Don't: `-fx-background-insets: 0` 之类抹掉焦点）。
- **焦点不被遮挡**（`focus-not-obscured`）：底栏/页头用 `BorderPane` 的正常区域，**不浮在内容上**；不使用常驻 `StackPane` 覆盖层；对话框内的滚动容器不能把焦点控件滚到按钮栏后面。
- **Tab 顺序 = 视觉顺序**：JavaFX 默认按节点添加顺序遍历。若某处顺序错，用 `node.setFocusTraversable`、`requestFocus` 或 `Parent.getChildren()` 顺序调整，**不要**用 `-fx-` 属性去改。
- **快捷键与助记符**：常用动作给助记符（`saveButton.setMnemonicParsing(true); saveButton.setText("_保存");`）。`[技能库命中: "accessibility focus keyboard" --stack javafx]` → `Use mnemonics for menu and form workflows`。
- **表格键盘操作**：`TableView` 默认可方向键移动；确认编辑单元格用 Enter、取消用 Esc。自定义 cell 必须保持可聚焦（不要 `setFocusTraversable(false)`）。
- **拖拽必须有键盘替代**（`dragging-alternative`）：阶段排序/砍单顺序用「上移 / 下移」按钮，不依赖拖拽；若保留拖拽，按钮必须同时存在。

### 6.3 目标尺寸与间距

| 元素 | 最小尺寸 | 依据 |
|---|---|---|
| 主操作按钮 | 高度 `2.9em`（≈44 逻辑像素） | `[技能库命中: "empty state guidance" --domain ux]` 同批 `touch-target-size`（44pt / 48dp） |
| 图标按钮 | 44×44 逻辑像素（可视图形可小于 44，热区必须 44） | 同上；`no-precision-required` |
| 表格行 / 列表项 | 高度 `2.6em`（≈39 逻辑像素） | 桌面指针环境的折中；若在触屏笔记本使用，提到 `2.9em` |
| 相邻可点元素间距 | ≥ `-fx-sp-2`（8 逻辑像素） | `touch-spacing`（8px/8dp） |
| chip / 开关 | 高度 ≥ `1.9em`，间距 ≥ `-fx-sp-1` | `chip-collection-reflow`：集合先换行再缩小标签 |

`[技能库命中: "empty state guidance" --domain ux]` → 同时返回 `touch-target-size` 与 `no-precision-required`；另有 `web-target-size`（WCAG 2.2 AA，24×24 CSS px）作为下限，本项目的 39/44 逻辑像素均远超。

### 6.4 屏幕阅读器可见文本

- 每个图标按钮：`button.setAccessibleText("删除这一条")`（`aria-labels` 的 JavaFX 等价物）。
- 每张表格：`table.setAccessibleText("本周课程任务，4 行")`；状态列 cell 的 `accessibleText` 必须是完整短语（"状态：待回报"），不能只读出一个色名。
- 每张图表：`chart.setAccessibleText(文字摘要)`，且页面上**必须**另有可见的文字摘要与数据表（`data-table`、`screen-reader-summary`）。
- `accessibleHelp` 用于补充说明（如危险操作的后果）。
- **所有 `accessibleText` / `accessibleHelp` 也纳入禁用词扫描**——它们虽然不显示在屏幕上，但会被读出来，同样是"界面文案"。
- 标题层级：Java 侧用样式类而非 `h1/h2` 标签，因此在 `accessibleRole` 与页头结构上保持一致顺序（页面标题 → 区块标题 → 卡片标题），不要跳级。
- 动态更新（保存成功、AI 开始回复）用状态条文字而非抢焦点（`toast-accessibility`：不要抢焦点）。JavaFX 侧做法：更新一个 `.notice` 或状态条 Label 的文本，并把焦点留在用户当前控件上。

### 6.5 动效与减少动效

- 只有状态过渡（悬停/按下/展开/焦点）使用动效：150–200ms，缓出；离开比进入快（`exit-faster-than-enter`：≈60–70%）。
- 同一视图最多 1–2 处动效（`excessive-motion`：Animate 1-2 key elements per view maximum）。
- **不做**：滚动揭示、视差、连续脉冲、自动轮播、宽度/高度动画（`transform-performance`：只动 opacity/transform）。
- 实现：JavaFX 无 `prefers-reduced-motion` 等价物。设置页提供「减少动效」开关，默认值尝试读取 Windows 的动画设置（`HKCU\Control Panel\Desktop\WindowMetrics\MinAnimate`）；开关为真时所有 `Transition` 直接跳到终态（**不是**把时长设为 0 后仍播放）。
  `[技能库命中: "reduced motion preference" --domain ux]` → `reduced-motion`、`excessive-motion`、`motion-sensitivity`（三条 Severity 均为 High）。
- 危机面板与 AI 回复**永不动画进出**——避免在敏感时刻制造额外刺激。

### 6.6 其他

- **图标**：统一用 `SVGPath`（Lucide/Feather 风格，线性，stroke-width 1.6，24 单位视窗）。**不用 emoji**（`no-emoji-icons`：`[技能库命中: "minimal calm neutral" --domain style]` 同批规则；且 JavaFX 在 Windows 上 emoji 渲染为彩色位图，与线性图标风格冲突、缩放后模糊）。
- **禁用态**：`-fx-opacity: 0.45` + `setDisable(true)`，并且不要用禁用态隐藏"未回报"这类重要信息（禁用 ≠ 无数据）。`disabled-states` 要求"降低不透明度 + 光标变化 + 语义属性"，JavaFX 侧对应 `opacity` + `setDisable`（JavaFX 无 cursor 语义，故省略光标项）。
- **截断**：优先换行（`setWrapText(true)`）；必须截断时用省略号 + `Tooltip` 提供全文（`truncation-strategy`）。
- **表单校验**：失焦时校验（不是每次按键），提示放在相关字段下方并说明原因与补救方式（`inline-validation`、`error-clarity`、`error-placement`）。文案示例："这里需要一个日期，例如 2026-10-11。"（不是"输入无效"）。
- **长表单自动保存草稿**（`form-autosave`）：周记录编辑区每 5 秒或失焦时写草稿，防误关丢失。
- **重复启动的写入安全**：外部修改检测（上位文档测试 7）在 UI 上表现为`.notice` 而非红条。

---

## 7. 表格与图表建议

### 7.1 Markdown 表格 → `TableView` 的样式注意点

上位文档的计划文件里含多张 Markdown 表格（目标、课程、每周底线、阶段排期、周记录）。它们映射到 `TableView` 后有几个必须处理的地方：

1. **列宽不要写死**。用 `setPrefWidth` 给一个初值 + `setMinWidth` 小值；**名称/说明类列设 `setMaxWidth(Double.MAX_VALUE)` 吸收剩余宽度**。这样 200% 缩放 + 中文变长时不会横向滚动。`[技能库命中: "layout responsive dpi scaling" --stack javafx]` → `Prefer constraints over fixed coordinates`（severity High）。
2. **行高用 `-fx-cell-size: 2.6em`**（不要 `-fx-fixed-cell-size` 写死像素）。值为 `em` → 随根字号缩放。
3. **单元格内容用 cell factory，不在 model 里放 Node**。`[技能库命中: "tableview column cell" --stack javafx]` → `Use cell factories for custom rendering`（Don't: `row.setBadge(new Label(...))`）；`updateItem` 里必须处理 `empty` 为 `true` 的情况（否则滚动时出现"幽灵状态"）：
   ```java
   statusCol.setCellFactory(c -> new TableCell<Row, GoalState>() {
       @Override protected void updateItem(GoalState s, boolean empty) {
           super.updateItem(s, empty);
           setText(empty || s == null ? null : UiText.of(s));  // 文案只来自枚举
           setGraphic(empty || s == null ? null : statusDot(s)); // 中性色 + 形状
       }
   });
   ```
4. **`setCellValueFactory` 返回可观察属性**，不要 `new SimpleStringProperty(obj.toString())`（库明确列为 Code Bad，因为它不随模型更新）。
5. **状态列的三重编码**：文字（`UiText.of(state)`）+ 形状（实心/空心/虚线圆点）+ 中性色。**绝不允许**只靠颜色。
6. **数字列**：给样式类 `.numeric`，`-fx-alignment: center-right`，并用 `String.format` 对齐位数；有 `Sarasa Mono SC` 时优先其等宽数字。
7. **日期列**：`截止` 列**仅作展示**，不加红字、不加"过期"标记、不排序成"催办"视图（上位文档 §5.3「明确不做逾期红标」）。
8. **空表**：设置 `table.setPlaceholder(中性提示 Label)`——不要留一片空白（`empty-states`）。文案："本周还没有条目。可以在上面添加，或让助手给一个建议。"
9. **可访问性**：`table.setAccessibleText("...")`；列支持点击排序（`sortable-table`），并保持排序状态在键盘可达。
10. **行斑马 + 悬停 + 选中**三态都已用中性令牌覆盖（见 §2.4）。选中的文本色必须显式设 `-fx-text-fill`，否则 Modena 在白底浅选中色上可能出现白字白底。
11. **一个页面只一个纵向滚动容器**：`TableView` 不放进 `ScrollPane`，用 `VBox.setVgrow(table, ALWAYS)`。
12. **Markdown 往返无损**（上位文档性质 P1/P2）与 UI 无关，但**提醒**：`TableView` 的显示值可能与文件里的原始文本不同（如 `暂缓` vs 原文 `暂缓 `），编辑保存时必须保留原样块，不要用 UI 值整表重写。这是个容易踩的实现陷阱，UI 侧的做法是"单元格编辑只改它自己那一格的原始片段"。

### 7.2 情绪趋势图

**图表类型**：折线图（趋势随时间）。`[技能库命中: "mood trend line chart" --domain chart]` → `Trend Over Time` → **Line Chart**（Secondary: Area Chart, Smooth Area）；When NOT to Use：少于 4 个数据点用 stat card（改用数字摘要）；超过 6 条序列视觉噪音。

| 项 | 决定 | 来源 |
|---|---|---|
| 图表控件 | `javafx.scene.chart.LineChart`（无第三方图表依赖） | `[通用默认: 未命中数据库]`（库推荐的是 Chart.js/Recharts/ApexCharts，均为 Web 库，不适用于 JavaFX） |
| 折线 | 单序列，`-fx-stroke: -fx-c-state-active`（明 `#3F6B9E` / 暗 `#93B4D9`），`-fx-stroke-width: 2` | `[通用默认: 未命中数据库]`（库的原话是 "Primary: #0080FF"，是 Web 蓝，不用） |
| 数据点 | 显示（`-fx-create-symbols="true"`），点尺寸 `0.36em`，随字号缩放 | `[技能库命中: "mood trend line chart" --domain chart]` → "Use solid, dashed, and dotted line styles plus direct series labels" |
| Y 轴 | **有序的离散情绪/强度档位**（如 强度 1–5 的文字刻度），**不是"情绪得分"** | 本项目硬约束：禁止 `得分 XX 分` |
| 网格线 | `-fx-c-border-subtle`（低对比，不抢数据） | `[技能库命中: "mood trend line chart" --domain chart]` 同批 §10 `gridline-subtle` |
| 图例 | 单序列时不显示图例，改用**图下方一句话说明**；多序列时图例必须可见且靠近图 | `legend-visible` |
| 直接标注 | 数据点 ≤ 8 时，在点旁直接标文字（如"疲惫"），减少视线往返 | `direct-labeling` |
| 交互 | 每个数据点装 `Tooltip`（日期 + 情绪 + 强度 + 睡眠）；键盘可达：给图表容器加 `Tab` 可聚焦，方向键在点间移动并显示同一 Tooltip | `tooltip-on-interact`、`tooltip-keyboard`、`focusable-elements` |
| 缩放 | `+` / `−` / `重置` 三个按钮（不要依赖滚轮缩放，避免与页面滚动冲突） | `[技能库命中: "mood trend line chart" --domain chart]` → A11y Fallback: "`+/-` buttons zoom; Reset restores the full range" |
| 减少动效 | `chart.setAnimated(false)`（当「减少动效」开启时），数据立即呈现 | `animation-optional`、`reduced-motion` |
| 空数据 | 显示空态卡，**不显示空坐标轴** | `empty-data-state` |
| 数据量 | 记录数很少（单人手工记录），无需降采样；超过 90 天时按周聚合 | `large-dataset`、`time-scale-clarity`（时间粒度必须标注并可切换） |

**不靠颜色单独传达信息**（本节的核心）：

1. **文字摘要必填**。图下方固定一段文字：「最近 30 天记录 18 次；出现最多：疲惫 7 次、平静 5 次、焦虑 4 次。」这段文字**不是装饰**，它保证色觉障碍用户与屏幕阅读器用户拿到同样的信息（`screen-reader-summary`、`color-not-only`）。
2. **可见数据表**。图下方永远有一张同数据的 `TableView`（日期 | 情绪 | 强度 | 睡眠）。`[技能库命中: "mood trend line chart" --domain chart]` → A11y Fallback: "Visible data table plus concise trend summary"。
3. **点形状 + 直接标签**。单序列时靠"点 + 点旁文字"；若将来加第二条序列（例如睡眠时长），**必须**同时用实线/虚线区分，不能只换颜色（`[技能库命中: "mood trend line chart" --domain chart]` → "never distinguish series by hue alone"）。
4. **坐标轴与网格**：`[技能库命中: "mood trend line chart" --domain chart]` 的 `axis-labels`：轴要标单位；Y 轴刻度用中文档位词而不是数字分数。
5. **不要把"情绪越差"映射到"线越低 + 红色"**。折线颜色恒定为 `-fx-c-state-active`（冷静蓝），**不随数据值变色**——否则等于给情绪打分并判好坏。这一条是本项目对库中通用建议的**收紧**，来源 `[通用默认: 未命中数据库]`（库未覆盖心理场景的伦理约束）。
6. **`CHART_COLOR_1..8` 必须覆盖**（已在 tokens 文件里），否则 Modena 默认第一序列色是橙红 `#f3622d`。

---

## 8. 来源标注索引

| 类别 | 命中的 query | 采纳情况 |
|---|---|---|
| 设计系统 | `study planner mental wellness calm desktop tool --design-system`（旋钮方差4/动效3/密度6） | 采纳旋钮与密度刻度；**否决** Claymorphism 风格、红/绿语义色、Lora/Raleway 字体、Scroll Reveal 动效、落地页 Pattern |
| 风格 | `minimal calm neutral --domain style` → `minimalism-and-swiss-style` | 全盘采纳为替代风格 |
| 颜色 | `calm wellness muted --domain color` → 三套调色板 | 采纳"低刺激底色 + 中性 muted + 单强调色"结构；**否决**三套里的 `Destructive: #DC2626` |
| 颜色 | `soft neutral dashboard --domain color` | 未采纳（三套里两套仍带红色 destructive，一套是深色金融仪表盘，绿=正向，语义冲突） |
| 字体 | `chinese friendly ui --domain typography` → `Chinese Simplified / Noto Sans SC` | 采纳方向，**离线化改造**为微软雅黑 UI + Noto Sans SC 回退 |
| 字体 | `clean readable ui sans --domain typography` → `Flat Design Mobile (Inter)`、`Corporate Trust (Lexend/Source Sans 3)` | 采纳"单一族 + 字重分层级"与 `weight-hierarchy`；字形本身无 CJK 故不采用 |
| 图表 | `mood trend line chart --domain chart` → `Trend Over Time / Line Chart` | 采纳图表类型、线型区分、`+/-` 缩放、可见数据表 + 文字摘要 |
| UX | `calm low stimulation color`、`neutral status color semantics` → `color-not-only`、`color-contrast` | 全盘采纳 |
| UX | `empty state guidance` → `empty-states`、`touch-target-size`、`active-state` | 采纳；示例文案的"鼓励语气"改为中性陈述 |
| UX | `keyboard focus visibility` → `focus-states`、`focus-not-obscured (min/enhanced)` | 全盘采纳 |
| UX | `dark mode contrast` → `contrast-readability`、`color-contrast`、`focus-appearance` | 全盘采纳 |
| UX | `reduced motion preference` → `reduced-motion`、`excessive-motion`、`motion-sensitivity` | 全盘采纳，落地为应用内开关 |
| JavaFX | `layout responsive dpi scaling` → `Set sensible min pref and max sizes`、`Prefer constraints over fixed coordinates`（High）、`Bind progress to background tasks` | 采纳前两条；第三条（Task 进度绑定）属实现细节，见 §5.7 发送中状态 |
| JavaFX | `css theme dark light` → `Keep theme switching centralized`、`Layer brand CSS after base theme`、`Use AtlantaFX as baseline` | 采纳前两条；**不引入 AtlantaFX**（理由见 §2.2） |
| JavaFX | `table view styling` → 未返回表格样式条目（返回 AtlantaFX 控件、view model、FXML 加载） | 部分未命中：表格 CSS 具体写法为 `[通用默认: 未命中数据库]` |
| JavaFX | `tableview column cell` → `Configure TableView cell value factories with properties`、`Use cell factories for custom rendering` | 全盘采纳 |
| JavaFX | `accessibility focus keyboard` → `Keep keyboard focus visible`、`Use mnemonics`、`Test theme-critical flows with TestFX` | 采纳前两条；TestFX 与上位文档"界面层不写单元测试"冲突，不采纳 |
| JavaFX | `font size scaling --stack javafx` → 仅返回图片后台加载 | **未命中**：DPI/字号细则全部为 `[通用默认: 未命中数据库]` + JavaFX 平台机制说明 |

**未命中的部分（如实说明）**：JavaFX 的表格/图表具体 CSS 写法、DPI 字号细则、中文数字等宽方案、`em` 令牌体系——本地技能库**没有对应条目**，这些内容是依据 JavaFX 平台机制与通用可访问性规则（quick-reference §1/§2/§5/§6/§7/§8/§10）给出的通用默认建议，不是数据库命中结果。
