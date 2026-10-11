# Design System Master File

> **LOGIC:** When building a specific page, first check `design-system/pages/[page-name].md`.
> If that file exists, its rules **override** this Master file.
> If not, strictly follow the rules below.

---

**Project:** Study Assistant
**Generated:** 2026-10-11 19:59:54
**Category:** Flashcard & Study Tool
**Design Dials:** Variance 4/10 (Balanced / Modern) | Motion 3/10 (Subtle) | Density 6/10 (Standard)

---

## Global Rules

### Color Palette

| Role | Hex | CSS Variable |
|------|-----|--------------|
| Primary | `#7C3AED` | `--color-primary` |
| On Primary | `#FFFFFF` | `--color-on-primary` |
| Secondary | `#8B5CF6` | `--color-secondary` |
| On Secondary | `#000000` | `--color-on-secondary` |
| Accent/CTA | `#059669` | `--color-accent` |
| On Accent/CTA | `#000000` | `--color-on-accent` |
| Background | `#FAF5FF` | `--color-background` |
| Foreground | `#0F172A` | `--color-foreground` |
| Card | `#FFFFFF` | `--color-card` |
| Card Foreground | `#0F172A` | `--color-card-foreground` |
| Muted | `#F7F3FD` | `--color-muted` |
| Muted Foreground | `#475569` | `--color-muted-foreground` |
| Border | `#EFE7FC` | `--color-border` |
| Destructive | `#DC2626` | `--color-destructive` |
| On Destructive | `#FFFFFF` | `--color-on-destructive` |
| Ring | `#7C3AED` | `--color-ring` |

**Color Notes:** Study purple + correct green

### Typography

- **Heading Font:** Lora
- **Body Font:** Raleway
- **Mood:** calm, wellness, health, relaxing, natural, organic
- **Google Fonts:** [Lora + Raleway](https://fonts.googleapis.com/css2?family=Lora:wght@400;500;600;700&family=Raleway:wght@300;400;500;600;700&display=swap)

**CSS Import:**
```css
@import url('https://fonts.googleapis.com/css2?family=Lora:wght@400;500;600;700&family=Raleway:wght@300;400;500;600;700&display=swap');
```

### Spacing Variables

*Density: 6/10 — Standard*

| Token | Value | Usage |
|-------|-------|-------|
| `--space-xs` | `4px` / `0.25rem` | Tight gaps |
| `--space-sm` | `8px` / `0.5rem` | Icon gaps, inline spacing |
| `--space-md` | `16px` / `1rem` | Standard padding |
| `--space-lg` | `24px` / `1.5rem` | Section padding |
| `--space-xl` | `32px` / `2rem` | Large gaps |
| `--space-2xl` | `48px` / `3rem` | Section margins |
| `--space-3xl` | `64px` / `4rem` | Hero padding |

### Shadow Depths

| Level | Value | Usage |
|-------|-------|-------|
| `--shadow-sm` | `0 1px 2px rgba(0,0,0,0.05)` | Subtle lift |
| `--shadow-md` | `0 4px 6px rgba(0,0,0,0.1)` | Cards, buttons |
| `--shadow-lg` | `0 10px 15px rgba(0,0,0,0.1)` | Modals, dropdowns |
| `--shadow-xl` | `0 20px 25px rgba(0,0,0,0.15)` | Hero images, featured cards |

---

## Component Specs

### Buttons

```css
/* Primary Button */
.btn-primary {
  background: #059669;
  color: #000000;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}

.btn-primary:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

/* Secondary Button */
.btn-secondary {
  background: transparent;
  color: #7C3AED;
  border: 2px solid #7C3AED;
  padding: 12px 24px;
  border-radius: 8px;
  font-weight: 600;
  transition: all 200ms ease;
  cursor: pointer;
}
```

### Cards

```css
.card {
  background: #FAF5FF;
  border-radius: 12px;
  padding: 24px;
  box-shadow: var(--shadow-md);
  transition: all 200ms ease;
  cursor: pointer;
}

.card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}
```

### Inputs

```css
.input {
  padding: 12px 16px;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  font-size: 16px;
  transition: border-color 200ms ease;
}

.input:focus {
  border-color: #7C3AED;
  outline: none;
  box-shadow: 0 0 0 3px #7C3AED20;
}
```

### Modals

```css
.modal-overlay {
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
}

.modal {
  background: white;
  border-radius: 16px;
  padding: 32px;
  box-shadow: var(--shadow-xl);
  max-width: 500px;
  width: 90%;
}
```

---

## Style Guidelines

**Style:** Claymorphism

**Keywords:** Soft 3D, chunky, playful, toy-like, bubbly, thick borders (3-4px), double shadows, rounded (16-24px)

**Best For:** Educational apps, children's apps, SaaS platforms, creative tools, fun-focused, onboarding, casual games

**Key Effects:** Inner+outer shadows (subtle, no hard lines), soft press (200ms ease-out), fluffy elements, smooth transitions

### Page Pattern

**Pattern Name:** Product Demo + Features

- **Conversion Strategy:** Use an interactive demo only when it explains value better than static media. Provide captions, transcript, visible play/pause controls, and a non-video fallback; do not autoplay under reduced motion. Pause media when offscreen or hidden and keep the final product state available as static content.
- **CTA Placement:** Video center + CTA right/bottom
- **Section Order:** Hero > Product video/mockup (center) > Feature breakdown per section > Comparison (optional) > CTA

---

## Motion

**Scroll Reveal** (Subtle) — Trigger: scroll (viewport enter) | Duration: 300-400ms | Easing: `power1.out`

```js
gsap.from(el, { opacity: 0, y: 12, duration: 0.35, ease: 'power1.out', scrollTrigger: { trigger: el, start: 'top 90%', toggleActions: 'play none none reverse' } });
```

**Framework notes:** Requires the ScrollTrigger plugin registered once via gsap.registerPlugin(ScrollTrigger); Use matchMedia('(prefers-reduced-motion: reduce)') to skip non-essential motion and render the final state immediately

- ✅ Keep the y offset small (8-16px) so it reads as a fade, not a slide
- ❌ Don't reveal below-the-fold content needed for SEO/crawlers as invisible-by-default without a no-JS fallback
- ⚡ toggleActions 'play none none reverse' avoids re-triggering on every scroll direction change

---

## Anti-Patterns (Do NOT Use)

- ❌ Inconsistent styling
- ❌ Poor contrast ratios

### Additional Forbidden Patterns

- ❌ **Emojis as icons** — Use SVG icons (Heroicons, Lucide, Simple Icons)
- ❌ **Missing cursor:pointer** — All clickable elements must have cursor:pointer
- ❌ **Layout-shifting hovers** — Avoid scale transforms that shift layout
- ❌ **Low contrast text** — Maintain 4.5:1 minimum contrast ratio
- ❌ **Instant state changes** — Always use transitions (150-300ms)
- ❌ **Invisible focus states** — Focus states must be visible for a11y

---

## Pre-Delivery Checklist

Before delivering any UI code, verify:

- [ ] No emojis used as icons (use SVG instead)
- [ ] All icons from consistent icon set (Heroicons/Lucide)
- [ ] `cursor-pointer` on all clickable elements
- [ ] Hover states with smooth transitions (150-300ms)
- [ ] Light mode: text contrast 4.5:1 minimum
- [ ] Focus states visible for keyboard navigation
- [ ] `prefers-reduced-motion` respected
- [ ] Responsive: 375px, 768px, 1024px, 1440px
- [ ] No content hidden behind fixed navbars
- [ ] No horizontal scroll on mobile

---
---

# 项目覆盖层 Project Overrides（优先级高于以上全部默认值）

> 本层由 `/ui-ux-pro-max` 检索结果的**人工裁决**生成（生成日期 2026-10-11）。
> 上方默认输出面向 Web 营销/通用产品；本项目是**单人使用的 JavaFX 25 桌面应用**，且带**情绪与心理内容**，
> 存在三条硬约束（低刺激、四态中性、无诊断/评判语义），因此以下令牌与风格**覆盖**上方同名条目。
> 完整中文实施细节见 `docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md`，检索过程见 `SEARCH-LOG.md`。

## 1. 被否决的上方默认值及理由

| 上方默认值 | 裁决 | 理由 |
|---|---|---|
| `--color-destructive: #DC2626` 及一切红色/橙色 | **整体移除** | 违反「禁用高饱和红橙 / 禁用警告色」。项目内**不存在** destructive / error / warning / success 语义色 |
| `--color-accent: #059669` 绿色作为 CTA / 状态 | **移除** | 绿色会被读作「完成=好」，违反四态中性要求 |
| `--color-primary: #7C3AED` 高饱和紫 | **替换** | 色度偏高、刺激偏强；改用低饱和靛蓝 `#4A55A2`（明） |
| Style = **Claymorphism**（软 3D、chunky、玩具感、双层阴影） | **替换为 Minimalism & Swiss Style** | Claymorphism 被技能库自身标注为「Educational apps, children's apps, fun-focused」；玩具感与情绪/心理场景的「不评判、低刺激」气质冲突，双层阴影+3-4px 粗边提高视觉噪音 |
| Typography = **Lora / Raleway** | **替换** | 两款字体**均无 CJK 字形**，中文界面会回退到默认字体甚至出现方块（本项目已知风险） |
| Pattern = Product Demo + Features（Hero/视频/CTA） | **不适用** | 桌面单用户工具无落地页、无转化漏斗；见下方「页面结构」 |
| Motion = Scroll Reveal / ScrollTrigger | **不适用** | 桌面应用无滚动驱动揭示范式；改用 150–200ms 的状态过渡微动效 |
| `prefers-reduced-motion` 媒体查询 | **替换实现** | JavaFX 无 CSS 媒体查询；改用应用内「减少动效」开关 + 读取 Windows 动画设置 |
| cursor:pointer / 375–1440px 断点 / max-w-6xl | **不适用** | JavaFX 无 cursor 属性、无 CSS 断点、无 max-width；改用窗口最小尺寸 + 约束式布局 |

## 2. 采纳的调色板（替代上方 Color Palette 段）

明色主题（对比度已逐对实测，均 ≥ 4.5:1 文字 / ≥ 3:1 控件边界）：

| Role | Hex | 用途 |
|---|---|---|
| `-fx-c-bg` | `#F7F6FA` | 页面背景 |
| `-fx-c-surface` | `#FFFFFF` | 卡片 / 表格 / 输入框底 |
| `-fx-c-surface-muted` | `#EFEEF4` | 次级面（表头、分组底） |
| `-fx-c-border-subtle` | `#E3E1EA` | 分隔线、卡片描边（装饰性） |
| `-fx-c-border-control` | `#8F8CA0` | 输入框/下拉框边界（3.27:1） |
| `-fx-c-text` | `#1E2130` | 主文本（14.84:1） |
| `-fx-c-text-secondary` | `#565A6E` | 次文本（6.33:1） |
| `-fx-c-text-tertiary` | `#63677A` | 元信息（5.20:1） |
| `-fx-c-accent` | `#4A55A2` | 主操作、链接、选中（6.27:1；白字于其上 6.75:1） |
| `-fx-c-accent-hover` | `#3D4AA8` | 悬停/按下 |
| `-fx-c-focus` | `#3D4AA8` | 焦点环（7.11:1） |
| `-fx-c-notice-bg` | `#EDF1F6` | 中性提示条底（非警告） |
| `-fx-c-notice-text` | `#2F4A66` | 中性提示文本（8.08:1） |
| `-fx-c-state-active` | `#3F6B9E` | 目标状态「进行中」（5.13:1） |
| `-fx-c-state-done` | `#5A6478` | 目标状态「已完成」（5.53:1） |
| `-fx-c-state-paused` | `#6B5F8A` | 目标状态「暂缓」（5.38:1） |
| `-fx-c-state-cancelled` | `#63677A` | 目标状态「已取消」（5.20:1） |
| `-fx-c-state-await` | `#565A6E` + 虚线边 `#8F8CA0` | 条目状态「待回报」（无独立色相，靠虚线形状区分） |
| `-fx-c-crisis-bg` | `#F3EFE7` | 危机资源面板底（暖砂，非红） |
| `-fx-c-crisis-bar` | `#7A6647` | 危机面板左侧强调条（4.79:1） |
| `-fx-c-crisis-text` | `#4A4238` | 危机面板标题与正文（8.61:1） |

暗色主题：

| Role | Hex | Role | Hex |
|---|---|---|---|
| `-fx-c-bg` | `#16171C` | `-fx-c-accent` | `#A8B2F0`（8.77:1） |
| `-fx-c-surface` | `#1E2027` | `-fx-c-accent-hover` | `#B4BDF5` |
| `-fx-c-surface-muted` | `#262932` | `-fx-c-focus` | `#9AA6F5`（7.80:1） |
| `-fx-c-border-subtle` | `#2E313B` | `-fx-c-state-active` | `#93B4D9` |
| `-fx-c-border-control` | `#737A8E`（3.80:1） | `-fx-c-state-done` | `#A6AEC2` |
| `-fx-c-text` | `#E8E9EE`（14.76:1） | `-fx-c-state-paused` | `#B3A6CC` |
| `-fx-c-text-secondary` | `#ADB2C0`（8.44:1） | `-fx-c-state-cancelled` | `#9AA0B0` |
| `-fx-c-text-tertiary` | `#8E93A3`（5.84:1） | `-fx-c-crisis-bg` | `#2A2622` |
| `-fx-c-notice-bg` | `#20252C` | `-fx-c-crisis-bar` | `#C9A87C`（6.70:1） |
| `-fx-c-notice-text` | `#C3D0DE`（9.83:1） | `-fx-c-crisis-text` | `#E6DCCE`（11.08:1） |

**明暗切换原则**：暗色不是明色的反相，而是**降饱和 + 提亮同色相**（`color-dark-mode`）。深色下阴影几乎不可见 → 暗色主题用「面抬升 + 描边」表达层级，不靠阴影。

## 3. 采纳的字体搭配（替代 Lora / Raleway）

- 界面与正文：`"Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI"`（无衬线，中文优先）
- 层级只用**字重 + 字号 + 间距**表达（`weight-hierarchy`），**不引入第二种中文字体**以规避 JavaFX 中文回退/方块问题
- 数字列：优先本机 `"Sarasa Mono SC"` / `"Noto Sans Mono CJK SC"`，缺失时用右对齐 + `String.format` 补位（JavaFX 无 `font-variant-numeric`）
- 基础字号 `15px` 逻辑像素，行距用 `-fx-line-spacing: 0.5em`（JavaFX 无 `line-height`）
- 技能库命中的 `Chinese Simplified (Noto Sans SC)` 方向被采纳为**回退族**；`Lora / Raleway` 因无 CJK 字形被否决

## 4. 采纳的风格与动效

- **Style：Minimalism & Swiss Style**（技能库 `minimalism-and-swiss-style`，risk:low，明暗双支持，200–250ms 微妙悬停）
- 圆角：控件 `6` / 卡片 `10` / 对话框 `14` / 状态 chip `全圆`（**不用** Claymorphism 的 16–24px 厚圆角与双层阴影）
- 阴影最多两级且极淡；暗色主题不用阴影
- 动效：仅状态过渡（悬停/按下/展开/焦点）150–200ms，缓出；**无**滚动揭示、无视差、无自动播放；同一视图最多 1–2 处动效（`excessive-motion`）；提供「减少动效」开关

## 5. 覆盖后的反模式清单（追加于上方清单之后）

- ❌ 任何红色 / 橙色 / 高饱和绿，任何 `-fx-c-danger|error|warning|success` 令牌
- ❌ `Alert.AlertType.ERROR` / `WARNING`（渲染红色图标与告警语义）
- ❌ 用百分比进度条表示计划完成度（隐含「落后」语义）
- ❌ 连续打卡天数、「0%」「0 天」式差评汇总
- ❌ 诊断性词汇与评分（「抑郁」「焦虑症」「中度」「得分 XX 分」）
- ❌ 界面文案中出现：`未完成`、`逾期`、`落后`、`断签`、`失败`、`警告`、`已放弃`、`未坚持`
- ❌ 用颜色单独表达状态（必须同时有文字 + 形状/图标，`color-not-only`）
- ❌ 硬编码像素尺寸、锁定 `min==max`、位图图标、Canvas 固定像素绘制（200% 缩放下必然破版）
- ❌ Emoji 当图标（JavaFX 在 Windows 下 emoji 渲染不一致）

## 6. 页面结构（替代 Product Demo + Features）

侧边导航 + 页头 + 内容区 + 状态条的桌面工具壳，共 8 类页面：进度总览 / 周计划 / 周记录 / 目标与阶段 / 情绪记录 / 情绪趋势 / AI 对话 / 设置（详见实施指南 §5）。

## 7. 项目专属交付前检查清单（与上方清单并用）

- [ ] 界面文案扫描通过 8 个禁用词 + 无诊断性词汇
- [ ] 四态（进行中/暂缓/已取消/已完成）在明暗两套下均可达且为中性色
- [ ] 危机资源面板为暖砂中性面板，非红色警报、非弹窗
- [ ] 100% / 150% / 200% 三档缩放下无截断、无重叠、无横向滚动
- [ ] 仅用键盘可完成：切换页面、编辑表格单元格、发送消息、开关主题
- [ ] 焦点环在所有可交互控件上可见且未被底栏/浮层遮挡
- [ ] 每张图表都有对应的文字摘要与可见数据表
- [ ] `prefers-reduced-motion` 的等价物：设置页「减少动效」开关生效
