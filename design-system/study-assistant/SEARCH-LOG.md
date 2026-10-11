# 检索日志 —— Study Assistant 设计系统与视觉指南

- **日期**：2026-10-11
- **执行者**：UI/UX 设计智能助手（子代理）
- **检索脚本**：`%USERPROFILE%\.dsh\skills\ui-ux-pro-max\scripts\search.py`（技能库 `ui-ux-pro-max`）
- **解释器**：`python`（本机可用，未回退到 `python3` / `py -3`）
- **输出根目录**：`E:\Project\Java\Study`
- **项目根**：`E:\Project\Java\Study`（工作目录与之相同）
- **命名约定**：`-p "Study Assistant"` → 项目 slug `study-assistant`

## 事前检查

| 检查项 | 结果 |
|---|---|
| `search.py --help` 参数确认 | 通过。确认 `--design-system / --persist / -p / --output-dir / --variance / --motion / --density / --domain / --stack / -n / --json / --full` 均存在 |
| `design-system/study-assistant/MASTER.md` 是否已存在 | **不存在**（`design-system/` 目录整体不存在）→ 无需 `--force`，`--persist` 直接生成 |
| 缓存输入：上位设计规格 | 已读 `docs/superpowers/specs/2026-10-10-study-assistant-design.md` 的 §5.2 / §5.8 / §8.2 / §8.3，用于校准四态与禁用词清单 |
| 是否触碰 Java 源码 | **否**。本次任务只新增/修改 Markdown 文档，未读改任何 `.java`，未执行任何 git 命令 |

---

## 1. 设计系统生成（必做）

### 命令

```
python "%USERPROFILE%\.dsh\skills\ui-ux-pro-max\scripts\search.py" "study planner mental wellness calm desktop tool" --design-system --persist -p "Study Assistant" --output-dir "E:\Project\Java\Study" --variance 4 --motion 3 --density 6
```

**结果**：非 0 结果，`✅ Design system persisted to E:\Project\Java\Study\design-system\study-assistant/`，生成 `MASTER.md`。

### 返回的 top 结果标识

| 段 | 返回标识 |
|---|---|
| Design Dials | Variance 4/10 (Balanced / Modern)、Motion 3/10 (Subtle)、Density 6/10 (Standard) |
| Pattern | `Product Demo + Features` |
| Style | `Claymorphism`（Mode Support: Light supported / Dark conditional） |
| Colors | `Study purple + correct green`：Primary `#7C3AED` / Accent `#059669` / Background `#FAF5FF` / Destructive `#DC2626` |
| Typography | `Lora / Raleway`（Mood: calm, wellness, health, relaxing, natural, organic） |
| Key Effects | Inner+outer shadows (subtle, no hard lines), soft press (200ms ease-out), fluffy elements |
| Motion | `Scroll Reveal (Subtle)` — 300-400ms, `power1.out`, GSAP ScrollTrigger |
| Avoid | Inconsistent styling + Poor contrast ratios |
| Category | Flashcard & Study Tool |

### 采纳 / 否决（逐项）

| 返回项 | 决定 | 理由 |
|---|---|---|
| Design Dials 4 / 3 / 6 | **采纳** | 与硬约束一致：低方差、克制动效、中等密度（表格化进度需要，但不能密排到制造压迫感）。已在指南 §「设计旋钮取值与理由」写明 |
| Density 6 的 `--space-*` 表（16–64px） | **部分采纳并改造** | 采纳其"标准密度、4 的倍数递增"结构；数值按桌面中文界面重排为 4/8/12/16/24/32/48，并全部改用 `em`（DPI 安全，见指南 §1.5、§3.3） |
| Pattern `Product Demo + Features` | **否决** | 桌面单用户工具没有落地页、没有视频 hero、没有转化漏斗 |
| Style `Claymorphism` | **否决** | 库自身标注 Best For 为 "Educational apps, children's apps, ... fun-focused, casual games"；软 3D / chunky / 玩具感 / 16–24px 厚圆角 / 双层阴影，与「情绪场景 + 低刺激 + 不评判」冲突，且会与表格化数据的方正结构打架 |
| Primary `#7C3AED` | **否决** | 色度偏高、刺激偏强；改用低饱和靛蓝 `#4A55A2`（明）/ `#A8B2F0`（暗） |
| Accent `#059669`（绿） | **否决（双重理由）** | ① 绿色会被读作 success（"完成=好"），违反四态中性；② 实测于明色底仅 **3.50:1**，本身就不达 4.5:1 |
| `Destructive #DC2626`（红） | **否决** | 直接违反「禁用高饱和红橙、禁用警告色」。实测于明色底 **4.49:1**，也不达标 |
| Typography `Lora / Raleway` | **否决** | 两款字体**均无 CJK 字形**，中文界面会回退甚至出现方块（上位文档 §8.3 列为已知风险） |
| Motion `Scroll Reveal` / GSAP ScrollTrigger | **否决** | 桌面 JavaFX 应用无滚动驱动揭示范式；且 ScrollTrigger 是 Web 库。改为 150–200ms 状态过渡 |
| Avoid `Poor contrast ratios` | **采纳** | 落地为「新增颜色令牌必须先在对比度计算里跑一遍」的审计纪律 |
| Pre-Delivery Checklist（8 条） | **部分采纳** | 采纳对比度、焦点可见、reduced-motion 三条；`cursor:pointer`、375–1440px 断点、`prefers-reduced-motion` 媒体查询在 JavaFX 中不存在，改为等价实现（见指南 §6.5、§3） |

**结论**：`--design-system` 的**框架**（旋钮 + 密度刻度 + 检查清单）可用，但其**具体取值**面向 Web 营销/通用教育产品，与本项目三条硬约束（低刺激、四态中性、无诊断语义）系统性冲突。因此在其生成的 `MASTER.md` 之后追加了「项目覆盖层」，逐项裁决并给出合规令牌。**未使用 `--force`**（生成时 MASTER.md 尚不存在，不存在覆盖他人决策的风险）。

---

## 2. 补充检索（按主意图逐条）

所有命令均为 `python "%USERPROFILE%\.dsh\skills\ui-ux-pro-max\scripts\search.py" <query> <selector> <value>`。

### 2.1 `--domain ux`

| # | 命令（query 部分） | 返回 | 是否采纳 |
|---|---|---|---|
| 1 | `"calm low stimulation color" --domain ux` | 2 条：`Color Only`（Do: Use icons/text in addition to color；Don't: Red/green only for error/success；Severity High）、`Color Contrast`（4.5:1；Severity High） | **采纳**。落为「四态/条目态必须文字+形状+颜色三重编码」与所有文本 ≥4.5:1 的硬指标 |
| 2 | `"neutral status color semantics" --domain ux` | 3 条：`Color Only`（同上）、`Submit Feedback`、`Color Contrast` | **采纳** `Color Only`（正是本项目的核心约束）；`Submit Feedback` 部分采纳（保存后给中性反馈） |
| 3 | `"empty state guidance" --domain ux` | 3 条：`Empty States`（Do: helpful message and action；Code Good 示例 `No items yet. Create one!`）、`Touch Target Size`（iOS 44pt / Android 48dp）、`Active State` | **采纳并改造**。采纳"必须给有帮助的信息 + 一个动作"；**不采纳**示例文案的鼓励式感叹号语气，改为中性陈述（如"本周还没有安排。可以从『周计划』开始……"）。`Touch Target Size` 采纳为 44 逻辑像素最小热区；`Active State` 采纳为设置页二级导航高亮 |
| 4 | `"keyboard focus visibility" --domain ux` | 3 条：`Focus States`（High）、`Focus Not Obscured (Enhanced)`（AAA）、`Focus Not Obscured (Minimum)`（AA） | **全盘采纳**。落为统一的 2 逻辑像素焦点环 + 「底栏/页头不浮在内容上」的布局规则 |

### 2.2 `--domain color`

| # | 命令 | 返回 | 是否采纳 |
|---|---|---|---|
| 5 | `"calm wellness muted" --domain color` | 3 条：`Beauty/Spa/Wellness Service`（`#EC4899` 粉）、`Healthcare App`（`#0891B2` 青 + `#059669` 绿，"Calm cyan + health green"）、`Meditation & Mindfulness`（`#7C3AED` 薰衣草 + `#059669` 绿） | **部分采纳**。三套**全部**带 `Destructive: #DC2626` → 该字段整体拒绝。采纳的是"低刺激底色 + 中性 muted（`#F1EEF5`/`#E8F1F6`/`#F7F3FD`）+ `Muted Foreground #475569` + 单一 accent"的**结构**，并参考其三套的亮/暗对比层级自行派生合规令牌 |
| 6 | `"soft neutral dashboard" --domain color`（按任务要求作为第 5 条的替代/补充试跑） | 3 条：`Parenting & Baby Tracker`（粉 + `#0284C7`，仍带 `#DC2626`）、`Beauty/Spa/Wellness Service`（同 #5 结果 1）、`Financial Dashboard`（深色 `#020617` + `Accent #22C55E`，"Dark bg + green positive indicators"） | **不采纳**。前两套仍是粉色系 + 红色 destructive（不适配中性四态）；第三套是深色金融仪表盘，明确用绿色表示"正向指标"，与本项目「禁用绿=成功」直接冲突。**如实记录：本条检索命中但跑题** |

### 2.3 `--domain typography`

| # | 命令 | 返回 | 是否采纳 |
|---|---|---|---|
| 7 | `"chinese friendly ui" --domain typography` | 3 条：`Soft Rounded`（Varela Round / Nunito Sans）、**`Chinese Simplified`（Noto Sans SC / Noto Sans SC，Sans+Sans，"chinese, simplified, modern, professional, multilingual, readable"）**、`Chinese Traditional`（Noto Serif TC / Noto Sans TC） | **采纳方向，离线化改造**。命中条目给的是 Google Fonts URL（需联网），而本项目是离线桌面应用 → 主字体改为 Windows 原生「Microsoft YaHei UI」，`Noto Sans SC` 降为回退族。`Soft Rounded` 因无 CJK 且偏"软圆"风格（同 Claymorphism 问题）不采纳 |
| 8 | `"clean readable ui sans" --domain typography` | 3 条：`Flat Design Mobile (System Bold)`（Inter / Inter，Notes 含 "Headlines fontWeight 800... Thick weights carry all hierarchy"）、`Corporate Trust`（Lexend / Source Sans 3，"Excellent accessibility"）、`Vietnamese Friendly`（Be Vietnam Pro / Noto Sans） | **采纳方法论，不采纳字形**。采纳"单一无衬线族 + 用字重分层级"（并收敛为 400/600，因为中文只常见 Light/Regular/Bold 三实体）；三套字形均无 CJK，不采用。Inter 的 800 字重过于强烈，与低刺激冲突 |

### 2.4 `--domain chart`

| # | 命令 | 返回 | 是否采纳 |
|---|---|---|---|
| 9 | `"mood trend line chart" --domain chart` | 3 条：**`Trend Over Time` → Line Chart**（Secondary: Area/Smooth Area；When NOT: <4 数据点用 stat card、>6 序列是噪音；Color Guidance `Primary: #0080FF`，Fill 20% opacity；A11y Notes "Use solid, dashed, and dotted line styles plus direct series labels; **never distinguish series by hue alone**"；A11y Fallback "Visible data table plus concise trend summary. Keyboard: focus reveals hover values; +/- buttons zoom; Reset restores the full range"；Library: Chart.js/Recharts/ApexCharts）、`Streaming Area Chart`（实时监控，不适用）、`Time-Series Forecast`（带置信带，不适用） | **采纳**：折线图类型、线型区分序列、`+/-` 缩放 + 重置、可见数据表 + 文字摘要、键盘可达数据点。**不采纳**：`#0080FF`（改用中性冷静蓝 `#3F6B9E`）、库推荐的三个图表库（均为 Web 库，JavaFX 用 `javafx.scene.chart.LineChart`）、"Fill 20% opacity" 的面积填充（本项目不填充，避免把情绪量感视觉化） |

### 2.5 `--stack javafx`

| # | 命令 | 返回 | 是否采纳 |
|---|---|---|---|
| 10 | `"layout responsive dpi scaling" --stack javafx` | 3 条：**`Set sensible min pref and max sizes`**（Do: `Region.USE_COMPUTED_SIZE`；Don't: lock every control to fixed width/height；Code Bad: `button.setMinSize(96,32); button.setMaxSize(96,32);`）、**`Prefer constraints over fixed coordinates`**（Severity **High**；Do: `GridPane.setHgrow(..., ALWAYS)`、`column.setPercentWidth(50)`；Don't: `field.setPrefWidth(328); field.setLayoutX(120)`）、`Bind progress to background tasks` | **全盘采纳**前两条，直接构成指南 §3.2 禁止清单的第 1–2 项。第三条采纳为「AI 发送中」的进度反馈做法 |
| 11 | `"css theme dark light" --stack javafx` | 3 条：`Keep theme switching centralized`（Do: ThemeService sets user-agent stylesheet and app CSS variants；Don't: 各控制器自己决定主题）、`Layer brand CSS after AtlantaFX`（Do: 在基础主题之后再 add app.css）、`Use AtlantaFX as the enterprise theme baseline` | **采纳**前两条 → 落为 `ThemeService`（指南 §2.2）与「`app.css` 必须最后加载」。**不采纳第三条（引入 AtlantaFX）**：上位文档 §8.3 已定"除 Gson 外不加依赖"；且第三方主题自带 success/danger 语义色，会让"禁用色审计"无法逐行完成。仅采纳其分层架构思想 |
| 12 | `"table view styling" --stack javafx` | 3 条：`Use AtlantaFX controls for common app affordances`、`Use view models for complex screens`、`Fail fast when loading FXML` | **部分跑题**：本条**没有返回任何表格样式条目**。`Use view models` 与项目"Java 代码 + CSS，面向 JavaFX properties"的取向一致，可作参考；`Fail fast when loading FXML` 因项目明确规定**不用 FXML** 而不适用。表格的 CSS 具体写法 → **未命中数据库** |

### 2.6 补充检索（为补齐命中缺口而追加）

| # | 命令 | 返回 | 是否采纳 |
|---|---|---|---|
| 13 | `"dark mode contrast" --domain ux` | `Contrast Readability`（High）、`Color Contrast`（High）、`Focus Appearance`（WCAG 2.2 AAA：≥2 CSS px 周长、3:1 状态对比） | **全盘采纳**。`Focus Appearance` 直接决定了焦点环规格（2 逻辑像素 + 7.11:1） |
| 14 | `"reduced motion preference" --domain ux` | `Reduced Motion`（High）、`Excessive Motion`（High，"Animate 1-2 key elements per view maximum"）、`Motion Sensitivity`（High，视差/滚动劫持致眩） | **全盘采纳**。落为「同一视图最多 1–2 处动效」+ 设置页「减少动效」开关（JavaFX 无媒体查询，属等价实现） |
| 15 | `"minimal calm neutral" --domain style` | **1 条**：`minimalism-and-swiss-style`（Minimalism & Swiss Style；Keywords: Clean, simple, spacious, functional, white space, high contrast, geometric, sans-serif, grid-based；Light ✓ / Dark ✓；Performance cost:low；Accessibility **risk:low**；Effects: Subtle hover 200-250ms；Design System Variables: `--shadow: none`, `--accent-color: single primary only`） | **全盘采纳**为替代风格（替换被否决的 Claymorphism）。其"单一 accent 色"与"阴影可无"正合本项目的禁用色与低刺激要求 |
| 16 | `"tableview column cell" --stack javafx`（对 #12 的**一次重试**，换更窄的说法） | 3 条：`Prefer constraints over fixed coordinates`（High）、**`Configure TableView cell value factories with properties`**（Don't: `new SimpleStringProperty(obj.toString())`）、**`Use cell factories for custom rendering`**（Do: override `updateItem` and handle empty state；Don't: 把 Node 放进 model 对象） | **采纳**后两条（指南 §7.1 第 3–4 项）。重试有效：从"无表格条目"变为"两条可直接落地的 TableView 规则"。**表格 CSS 具体属性写法仍未命中**，标注为 `[通用默认: 未命中数据库]` |
| 17 | `"accessibility focus keyboard" --stack javafx` | `Keep keyboard focus visible`（High；Code Good: `.button:focused { -fx-border-color: -brand-focus; -fx-border-width: 2; }`；Code Bad: `.button:focused { -fx-background-insets: 0; }`）、`Use mnemonics for menu and form workflows`、`Test theme-critical flows with TestFX` | **采纳**前两条，其中 Code Good 的写法被直接用于令牌块骨架（指南 §2.4 / §6.2）。`TestFX` 与上位文档"界面层不写单元测试"冲突 → 不采纳 |
| 18 | `"font size scaling" --stack javafx` | **1 条，且跑题**：`Use background loading for large images`（Images 类，与字号/缩放无关） | **不采纳**。如实记录：本条**未命中**，DPI/字号细则无库内依据 |

---

## 3. 0 结果 / 跑题记录与处理

| 检索 | 现象 | 处理 | 最终依据 |
|---|---|---|---|
| `"font size scaling" --stack javafx` | 返回 1 条但**完全跑题**（图片后台加载） | 按 skill 规则**只重试一次**的额度在此前已用于 `"tableview column cell"`；该条本身是补充检索，不再耗尽重试额度反复试 | 如实标注 **`[通用默认: 未命中数据库]`**。DPI 细则改由 JavaFX 平台机制（`glass.win.uiScale`、`Screen.getOutputScaleX()`、逻辑像素语义）+ quick-reference §5 布局响应式条目推导，并在指南 §3 明确写出「字号/DPI 部分库内无条目」 |
| `"table view styling" --stack javafx` | 返回 3 条但**均非样式内容**（AtlantaFX 控件、view model、FXML 加载） | **重试一次**，换更窄说法 `"tableview column cell" --stack javafx` → 命中 2 条可落地规则 | 命中部分按库采纳；**表格 CSS 属性写法（`-fx-cell-size`、`:odd`、`:selected`、column-header 等）标注 `[通用默认: 未命中数据库]`**，来源为 JavaFX CSS 参考（Modena 选择器结构） |
| `"soft neutral dashboard" --domain color` | 返回 3 条但两套是粉色系（含红色 destructive）、一套是深色金融仪表盘（绿=正向指标） | 该条是任务书里预置的"若第一条不相关再试一次"的替代 query，已按其设计意图执行 | 如实记录**跑题**，不采纳任何一套。最终调色板由 `"calm wellness muted"` 的**结构**派生 + 逐对对比度实测确定 |
| `"calm wellness muted" --domain color` 的三套调色板 | 三套**全部**含 `Destructive: #DC2626` | 不整体采纳；只采纳其低刺激底色/中性 muted/单 accent 的结构 | 令牌值由本机实测确定（`docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md` §1.1） |

**未发生**：任何"0 结果但当作有结果"的呈现。所有 `[通用默认: 未命中数据库]` 标注处均已核对，确认技能库中确无对应条目。

---

## 4. 参考文件读取记录（只读需要的部分）

| 文件 | 读取范围 | 用途 |
|---|---|---|
| `references/quick-reference.md` | §1 Accessibility（L7–34）、§2 Touch & Interaction（L35–54）、§4 Style Selection（L77–92）、§5 Layout & Responsive（L93–113）、§6 Typography & Color（L114–133）、§7 Animation（L134–161）、§10 Charts & Data（L225–256） | 对应任务要求的优先级 1/2/4/5/6/7 与图表规则。**未整份复制进产物**，只在指南中按条目名（如 `color-not-only`、`focus-not-obscured`、`number-tabular`）引用 |
| `docs/superpowers/specs/2026-10-10-study-assistant-design.md` | §5.1–§5.8（L300–429）、§8.2–§8.3（L604–623）、§9–§11（L626–679） | 校准四态定义、禁用词清单、禁用色要求、技术约束（不用 FXML、不加依赖、中文字体显式指定） |

---

## 5. 产出文件

| 文件 | 来源 |
|---|---|
| `design-system/study-assistant/MASTER.md` | `--design-system --persist` 生成，之后**追加**「项目覆盖层 Project Overrides」（未使用 `--force`，未删除任何已生成内容） |
| `design-system/study-assistant/SEARCH-LOG.md` | 本文件 |
| `docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md` | 本次撰写的中文 JavaFX 实施指南 |

**未触碰**：`study-core/`、`study-desktop/`、`pom.xml` 及任何 `.java` 文件；未执行任何 git 操作。
