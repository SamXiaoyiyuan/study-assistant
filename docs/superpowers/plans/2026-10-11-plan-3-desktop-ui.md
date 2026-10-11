# JavaFX 桌面界面与打包 · 实施计划（计划 3 / 3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把已经交付的 `study-core`（计划 1 数据层 + 计划 2 情绪域与 AI 模块）装进一个**可运行的 JavaFX 25 桌面应用**：8 类页面 + 设置页 + 危机资源面板、明暗两套主题、`DisabledWordTest` 文案合规扫描、`jpackage` 产出免安装目录。

**Architecture:** `study-desktop` 内分三层：`ui/theme`（视觉令牌，纯数据 + 自检）、`ui/service`（把 `study-core` 的能力包成**可注入、可单测**的门面；**所有文件读写的唯一入口**）、`ui/view` + `ui/control`（8 类页面与控制器，Java 代码构建 UI + CSS，**不用 FXML**）。依赖方向严格单向：`study-desktop → study-core`，`study-core` 永远不知道界面的存在（spec §3.3）。界面**不自己拼路径**、**不自己 new FileStore**、**不直接调 `PlanDocument.parse`**，一切经 `ui/service`。

**Tech Stack:** Java 25（`C:\Program Files\Java\jdk-25.0.3`）｜ Maven 3.9.16（IDEA 自带）｜ JavaFX `25.0.4`（`javafx-controls` + `javafx-graphics`）｜ JUnit Jupiter `5.14.4`（**测试仍然只写在 `study-desktop` 的 `ui/service` 与 `ui/theme` 两个包里**，见下）｜ `jpackage` / `jlink` / `jdeps`（JDK 25 自带，**本机已实测存在**：`jpackage --version` → `25.0.3`）

**Spec:** `docs/superpowers/specs/2026-10-10-study-assistant-design.md`

**前置：** 计划 1 已交付（`study-core` 数据层，分支 `plan-1-core`）；计划 2 的 Task 1–2 已提交（HEAD `4262c9a`：情绪文件模型、配置扩展），**Task 3–9（危机检测器、AI 客户端、流式、提示词、确认门、对话历史、情绪分析）尚未交付**。本计划的任务依赖其中若干接口，处置方式见「对计划 2 未定稿接口的处置」一节——**这是本计划最重要的前置约定**。

**视觉规范：** `design-system/study-assistant/MASTER.md`（本机已存在，`## 项目覆盖层 Project Overrides` 一节含完整的明/暗令牌表）。

> **`docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md` 目前不存在**——`MASTER.md` 末尾引用了它，但工作树里没有这个文件。本计划第 1 条裁定处理这件事（裁定 A1）。

## 对计划 2 未定稿接口的处置（凡是引用计划 2 的签名，一律先看这一节）

计划 2 的 Task 3–9 尚未实现。本计划对它们的引用**一律照抄计划 2 章节里已声明的签名**，并在每个 Task 的 `Interfaces` 里逐条标注下列三种标记之一：

| 标记 | 含义 | 实现者该怎么做 |
|---|---|---|
| `[已交付]` | 计划 1/2 已提交，签名**以源码为准** | 直接 `read` 那个源文件核对签名，按它写 |
| `[待计划 2]` | 计划 2 已声明但尚未提交 | 按本计划抄录的签名写；**若计划 2 终稿的签名与此处不同，以计划 2 终稿为准**，并把差异写进报告 |
| `[本计划新建]` | 计划 1/2 都没声明，**由本计划的某个任务创建** | 该任务必须先建它，后续任务才编译得过（每处都注明了是哪个任务） |

**已知的 `[待计划 2]` 接口清单**（全部照抄计划 2 的写法，执行前先确认计划 2 实际交付到第几个任务）：

| 接口 | 计划 2 出处 | 本计划消费它的任务 |
|---|---|---|
| `CrisisDetector` / `CrisisSignal` | 计划 2 Task 3 | Task 10 |
| `AiConfig` / `AiRequest` / `AiMessage` / `AiFailure` / `AiFailureKind` / `AiClient.complete` / `AiClient.endpoint` | 计划 2 Task 4 | Task 8 |
| `AiStreamListener` / `AiClient.stream` | 计划 2 Task 5 | Task 8 |
| `PromptRole` / `PromptLibrary` | 计划 2 Task 6 | Task 8、Task 9 |
| `PendingRequest` / `ConsentGate` / `ConfirmingAiSession` | 计划 2 Task 7 | Task 8 |
| `Conversation` / `ConversationStore` | 计划 2 Task 8 | Task 8 |
| `AnalysisWindow` / `EmotionAnalysis` | 计划 2 Task 9 | Task 7、Task 8 |

**若某任务开工时其 `[待计划 2]` 依赖仍未提交**：该任务**不得**自行发明实现，**停在这里**并在报告里写清「被计划 2 的 Task N 阻塞」，由控制器决定是插队执行计划 2 的那个任务，还是把本任务顺延。自行造一个近似接口会让计划 2 交付后出现两套同名不同签名的类型。

## 关键裁定（spec 没写清的地方，这里给结论；每条都写「错了的代价」）

| # | 裁定 | 为什么 | 错了的代价 |
|---|---|---|---|
| **A1** | **视觉令牌以 `design-system/study-assistant/MASTER.md` 的「项目覆盖层」一节为准。** `docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md` 在本机**不存在**（`MASTER.md` 末尾引用了它）。Task 1 的令牌值**逐字取自 `MASTER.md` §2 的两张表**，不自行发明色值；执行每页之前**先读 `MASTER.md`** | 唯一存在的、经过人工裁决的视觉来源就是 `MASTER.md` 的覆盖层（它明确写了「优先级高于以上全部默认值」，并把 Claymorphism / Lora / Raleway 都否决了）。等一份不存在的文档会让 Task 1 无法开工 | **中**：若日后补上 `javafx-visual-guidelines.md` 且与 `MASTER.md` 数值不同，`tokens-*.css` 要按它改一遍——因为值只写在两个 CSS 文件里，改动点是集中的，代价可控。反过来若 Task 1 自己发明色值，日后两处冲突、且对比度没有经过实测 |
| **A2** | **页面清单按 spec §8.1 的 8 类，拆成 9 个侧栏项 + 1 个非侧栏页**：总览 / 目标 / 课程 / 周检查 / 周记录 / 情绪 / 情绪趋势 / 助手 / 设置（侧栏 9 项）+ 求助资源（`Page.CRISIS`，`inSidebar()` 为 false，但从设置页与危机命中处可达）。`MASTER.md` §6 里的「周计划」与 spec 的「周检查」**取 spec 的措辞**；`MASTER.md` §6 的「情绪记录 / 情绪趋势」两页采用，把 spec 的「情绪」拆成「记录」与「趋势」两页 | spec §8.1 是有约束力的需求文档（8 页），`MASTER.md` 是视觉文档；两者措辞不一致时以 spec 为准。把「情绪」拆成两页是为了让**录入**与**趋势图**各有独立的评审单元（用户任务书也点名了这两页） | **低**：多一个侧栏项而已。若用户觉得项太多，合并「情绪 / 情绪趋势」只需要删一个枚举项与一个文件 |
| **A3** | **提示词目录不做成配置项**：固定 `<studyDir>\提示词`（由 `StudyDirs.promptsDir` 派生）。**不修改 `AppPaths`**（计划 1 的冻结面） | spec §13「study 目录可在设置页修改」只授权了**一个**可改的路径；提示词那一行的路径是从 study 目录派生的。新增一个配置键需要动 `study-core` 的 `AppConfig`（计划 2 的冻结面），而 spec 没要求它 | **低**：用户若单独搬走提示词目录，只能随 study 目录一起搬；搬不了时回退内置默认，且设置页会显示「现在用的是内置默认」，不会静默失效 |
| **A4** | **「隐藏暂缓内容」是显示偏好，不是删除**：`DeferredVisibility.HIDDEN` 只影响 `visibleGoals()` 的返回，**绝不改动文件、绝不丢数据**；切回 `COLLAPSED`/`VISIBLE_DIM` 后内容原样回来 | spec §2 决策 19 把它放在「应用配置」里，§5.2 又说历史完整保留。把它做成删除会直接违反「暂缓不是放弃」这条最核心的设计意图 | **高**：一旦实现成「隐藏即删除」，用户切一次偏好就永久丢掉「考研（暂缓）」那一节——那是本项目最不能出的错。Task 5 的测试里有 `hidden→collapsed` 的往返断言钉住它 |
| **A5** | **每份文件全局只有一个 `DocumentSession`，由 `AppShell` 持有并注入所有页面**；`ui/view` 与 `ui/control` **禁止**直接 `new FileStore(...)` / `TextFileCodec.write(...)` / `Files.write(...)` / `PlanDocument.parse(...)`。**唯一例外**：迁移走 `Migrator.apply`，对话历史走 `ConversationStore` | `FileStore` 的基线是**实例状态**。两个页面各持一份 `DocumentSession` 时，第一个页面保存后第二个页面的基线就过期了——它的下一次保存会被判成「外部改动」（多一次弹窗），或者更糟：如果它中间调过 `reload()`，基线就被拉到别人写的内容上，然后**静默覆盖**。**同一份文件的内存状态只能有一份** | **高**：违反它的表现是「改了的东西偶尔不见了」，而且很难复现。Task 3 有 `twoSessionsOnSameFile_secondSaveIsBlocked` 与 `uiViewLayer_hasNoDirectFileWriteApi` 两条测试守着 |
| **A6** | **`DisabledWordTest` 的扫描范围 = ① `study-desktop/src/main`（全部源码与资源）② `study-core/src/main/resources` ③ `study-core` 的**用户可见异常类与配置类**的字符串常量池（`SectionMissingException` / `NotUtf8Exception` / `TargetExistsException` / `GoalStatus` / `PlanDocument` / `EmotionLog` / `AppConfig`）；**不扫** `study-core/src/main/java` 的其余源码与任何测试目录。**不设任何白名单**，改用**三条反向自证断言**（违规样例逐条命中、合规样例 0 命中、词表与 spec 逐字相等）+ **反规避断言**（禁止字面量里出现 `\u` 转义与零宽字符）。`UiText.forbiddenWords()` 的返回值用**编码点拼接**构造（`"未" + "完成"`），使它自己不会被规则 ① 扫到 | 「白名单 = 这个词只出现在测试自己的词表里」的通常形态是**自证清白**：真违规只要被加进白名单，测试永远绿。反向自证断言让**扫描器与词表本身**也进入被验证范围：漏报会红（②号）、误报会红（①号）、词表被缩小会红（③号）。定这个范围的理由：core 的注释里出现「失败」是**允许且必要的**（它描述程序自身的失败，不是对用户的评判），扫它会逼出一堆无意义改写；但**会显示到界面上的 core 异常消息**必须扫 | **中**：范围定窄了会漏掉真实的界面文案泄漏；定宽了会逼出一堆注释改写。已知的真实命中（开工前实测，**7 个类里至少 2 个会命中**）：`SectionMissingException` 与 `PlanDocument` 的消息、以及 `AppConfig.setRequestTimeout` 的消息都含「失败」，**它们会显示在界面上**——按 A6 由界面侧的 `UiText.neutralize` 在**显示前**中立化并把 core 原文写日志；同时那条 `coreUserVisibleExceptionMessages_...` 测试一旦命中就红，逼实现者把位置写进报告。彻底修法（改 core 的消息）留给计划 4（见「下一份计划」第 2 条） |
| **A7** | **界面只负责「把 `PendingRequest.promptText()` 原样显示」与「如实回传确认结果」**：不摘要、不截断、不润色、不在预览里折叠。发送路径**只有 `AssistantController.send(...)` 一条**，`ConsentGate` 是它唯一的前置 | spec §7.5 要求「未确认不得发起网络请求」，而这条在最上游是 `ConfirmingAiSession` 的职责（计划 2 Task 7 有测试）。界面能做的最有价值的事是**让用户看到的就是真正会发出去的东西**——预览一旦被加工，「我确认过了」这句话就不成立了 | **高**：预览与实发不一致时，用户可能发出一段他从未同意发送的原文（情绪记录是**私密数据**）。Task 8 的 `confirmed_sendsExactlyThePreviewedText` 与 `noSecondSendPathThatBypassesTheGate` 两条测试守着 |
| **A8** | **`120`/`110` 的「号码」列在界面上只读、「文案」列可改**；删除按钮 disabled 并给出中性说明。**界面拦截只是体验，core 的 `AppConfig.setCrisisResources` 才是唯一的安全网** | spec §12 说「可改文案，但不可移除」。而「改号码」在效果上等于**移除那个号码**——如果放开号码编辑，用户能把 `120` 改成 `119`，保底规则就被绕过了，而 core 的校验（只看列表里有没有 `120`）**拦不住**，因为改完的列表里确实没有 `120`，会直接被 core 拒绝（`IllegalArgumentException`）——用户看到的是「改个号码怎么报错了」。设为只读让规则在界面上**可理解**，core 的校验在下面**兜底** | **低**：若用户确实想改 `120` 的号码（极端少见），只能手改 `config.properties`，core 仍会拒绝缺少 `120` 的列表；这份限制是刻意保留的 |
| **A9** | **jpackage 只产出 `app-image`（免安装目录），`.msi` 不做**；产物约 150–250MB，JavaFX 走 `--module-path` + `--add-modules javafx.controls`，启动器必须带 `--enable-native-access=javafx.graphics`，正式产物**不加** `--win-console`（但脚本保留 `-DebugConsole` 开关） | spec §10 把 `.msi` 明确列为「可选、需 WiX Toolset、用户确认后再做」——**没有用户明确的同意就不做**。`--enable-native-access` 是计划 1 实测的必需品（`javafx:run` 里有、jpackage 不会自动带）；JavaFX 不是 JDK 模块，jpackage **不会**从 classpath 上自动补 `--module-path`/`--add-modules` | **中**：漏 `--enable-native-access` → 用户每次启动都看到 WARNING；漏 `--module-path`/`--add-modules` → 产物启动即报「缺 javafx.controls」；加 `--win-console` → 用户每次看到一个黑色控制台窗口。三条都在 Task 12 的脚本里写成了固定参数 |
| **A10** | **不做 `@import url('https://fonts.googleapis.com/...')`**，字体族用系统回退链 `"Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI"`；**不引入 Lora / Raleway** | `MASTER.md` §1 已裁定 Lora / Raleway 无 CJK 字形；且桌面应用要**离线可用**，引远程字体在断网时是失败路径。JavaFX 的 `@import` 对远程 URL 支持不可靠，失败时**只打 warning 不抛异常**，表现是「中文变成方块」而不是报错 | **中**：字体加载是**静默失败**，没看窗口就发现不了。Task 1 的令牌与字体族是硬编码常量，Task 12 的验收清单第 11 项专门人工确认「中文无方块」 |

## Global Constraints

**继承计划 2 的全部约束**（运行环境 / 编码与字节保真 / AI 与安全 / 领域规则 / 路径 / 测试纪律），一字不改地沿用：

- **`mvn` 不在 PATH 上。** 所有 Maven 命令必须用 IDEA 自带的那一份，下文记作 `MVN`：
  `& 'D:\Program Files\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'`
- 工作目录 `E:\Project\Java\Study`；数据目录 `%USERPROFILE%\study`
- 读写固定 **UTF-8（无 BOM）+ `\n`**；P1/P2/P3 三项性质；非法 UTF-8 一律拒绝读取
- **显式保存、写前备份、绝不静默覆盖**（SHA-256 基线比对）
- **逐次确认门**：调 AI 前必须展示**将要发送的完整请求内容**并获确认，未确认**不发出一个字节**
- `ai` 包不依赖 `storage` 包；**AI 输出永不自动落盘**
- 危机关键词本地匹配；**命中时照常提供 AI 分析，不锁定、不阻断**
- **禁止诊断性标签**（`抑郁`/`焦虑症`/`中度`/得分）；一律用「观察到」「可能相关」
- **`120` 与 `110` 不可删除**（可改文案）——**全项目唯一的删除保护**
- 周界周一–周日、锚点 `2026-09-14`；目标四态均为中性
- **界面禁用词**（一个字都不许出现）：`未完成`、`逾期`、`落后`、`断签`、`失败`、`警告`、`已放弃`、`未坚持`
- 测试用脱敏样例；测试不得访问真实网络；断言必须能定位到具体数值
- 写文件若由 PowerShell 产出，必须 `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))`（本 harness 的 `Set-Content -Encoding utf8` 会写 BOM）
- **每次下结论前必须 `clean`**（Maven 只在源码变更时重编译）
- **实现者只 `git add <明确路径>`**；禁止 `git add -A` / `git add .` / `git commit -a`（同一工作树可能有别的 agent 的半成品）
- **评审者禁止执行任何写类 git 命令**（`reset`/`checkout`/`restore`/`stash`/`clean`/`rebase`）；要隔离就拷到 `%TEMP%`

**计划 3 新增约束（界面层）**

1. **不可写死像素尺寸。** 所有可见尺寸/间距/颜色来自令牌或 `em` 单位；**禁止** `setMinWidth(900)` 这类锁死、**禁止** `min == max` 的窗口尺寸、**禁止** Canvas 固定像素绘制、**禁止**位图图标。理由：本机显示器是 **约 200% DPI 缩放**（物理 3200×2000 / 逻辑 1600×1000，计划 1 已实测）；写死 px 的布局在 200% 下必然破版。
2. **状态不得只靠颜色传达**（`color-not-only`）。四态与「待回报」必须同时有**文字标签**，并在需要处叠加**形状**（实线 / 虚线边框）。
3. **不存在任何 red / orange / 高饱和绿的语义色**；**不存在** `-fx-c-danger|error|warning|success` 令牌；**禁止** `Alert.AlertType.ERROR` 与 `Alert.AlertType.WARNING`。
4. **界面文案全部收在常量类里**（`ui/text/UiText.java`），不在视图代码里散写中文字面量。这是 `DisabledWordTest` 能真正守住文案的前提，也是唯一让「扫描范围」可界定的做法。
5. **`read()` 先于 `save()`**（计划 1 Task 8 与计划 2「下一份计划」两处遗留约束）：打开页面/文档一律 `DocumentSession.open(...)`（内部先 `FileStore.read()` 并建立基线）；**任何 `save()` 之前必须已成功 `read()`**，且检测到外部改动时**一个字节都不写**，交给用户选「重新载入 / 覆盖」。**界面代码禁止直接调 `new FileStore(...)` / `TextFileCodec.write(...)` / `Files.write(...)`**——唯一入口是 `ui/service/DocumentSession`。
6. **主类永远是 `Launcher`**（`com.xiaoyiyuan.study.ui.Launcher`），`App` 只是 `Application` 子类。这是计划 1 实测的硬约束：classpath 方式下 JavaFX 拒绝启动继承 `Application` 的 main 类。
7. **应用标准输出不可用 `javafx:run` 取证**（计划 1 实测：插件用 Apache Commons Exec、无 stream pump）。要看应用自身输出、要验证 native-access 警告是否消失，**必须直接用 JVM 启动**，命令行用插件构建的同一条（同样的 `--module-path`、classpath、主类）。
8. **界面代码不得绕开 `ui/service` 直接用 `PlanDocument.parse(readFile(...))`**：那是「两份内存状态」，第二个保存的人会静默覆盖第一个（见 Task 3 的裁定 A5）。

## Review Focus

Spec 未覆盖、但最可能咬到真实使用者的 5 类输入或条件。**每一条都已配了对应测试或明确的验收观察值**：

1. **界面文案里混进禁用词，而它来自 `study-core` 的异常消息**（`SectionMissingException`、`GoalStatus.fromLabel`、`AppConfig.setRequestTimeout` 的消息里**确实**含「失败」，实测存在）。期望：**扫描范围要罩到 core 的资源与异常消息**，而不是只扫 `study-desktop`——只扫界面源码的测试是自证清白的。→ Task 11 + 裁定 A6
2. **200% DPI 缩放下布局破版**（写死 px、`min==max`、固定宽表格、图表硬编码画布）。期望：令牌 + `em` 单位 + 只在「窗口最小尺寸」上限死；并且**每次生成的验证方式必须是人工看**，因为 JavaFX 的 CSS 解析失败**只打一行 warning、不抛异常**。→ Task 1、Task 2、Task 12
3. **`read()` 先于 `save()` 的落点**：同一份文件被两个页面各持一份内存副本、或第一次保存前没有 `read()`、或两个窗口都开着同一个文件。期望：**每份文件全局只有一个 `DocumentSession` 实例**，`save()` 前比对 SHA-256 基线，不一致就返回 `EXTERNAL_CHANGE_DETECTED` 并**不写一个字节**。→ Task 3（有测试）
4. **情绪数据发送前的三个维度**：只发用户选定时间窗内的记录、原文预览必须与真正发出的内容**完全一致**、未确认前没有任何网络请求。期望：由 `EmotionAnalysis.build` + `ConsentGate` 在 core 侧守住（计划 2），界面侧只负责**把 `PendingRequest.promptText()` 原样显示**（不截断、不摘要、不润色），并把确认结果如实回传。→ Task 8 + 裁定 A7
5. **图表与情绪数据的「不评判」**：情绪趋势图**不得**只靠颜色、不得出现「完成率」「打卡天数」「记录完整度」「连续记录 N 天」，**记录缺失不得暗示任何负面**（spec §6.2）。期望：图表每张都有文字摘要与可见数据表；缺记录的日期在图上就是「没有点」，旁边文字写「未回报」，**没有红/橙**，没有「中断」类措辞。→ Task 7

---

### Task 1: 视觉令牌与主题

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/theme/ThemeMode.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/theme/ThemeTokens.java`
- Create: `study-desktop/src/main/resources/css/tokens-light.css`
- Create: `study-desktop/src/main/resources/css/tokens-dark.css`
- Create: `study-desktop/src/main/resources/css/app.css`
- Modify: `study-desktop/pom.xml`（把 `javafx-controls` 同时声明为 `test` 作用域可见的依赖：JavaFX 依赖默认是 `compile`，测试期本来就在 classpath 上，此步只是**显式**加一条 `javafx-graphics` 依赖，避免 `javafx.scene.Node`、`javafx.scene.Parent` 在 `ui/theme` 的签名里解析不到）
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/theme/ThemeTokensTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `enum ThemeMode { LIGHT, DARK }`，含 `ThemeMode other()`（切换用）与 `String label()`
- Produces `[本计划新建]`: `enum ThemeTokens`
  - `String key()` → `-fx-c-bg` 这类 looked-up color 名（**不带首尾空白**）
  - `static List<String> cssFiles(ThemeMode)` → `["/css/tokens-light.css", "/css/app.css"]`（暗色时第一项换成 `tokens-dark.css`）
  - `static String valueOf(ThemeTokens, ThemeMode)` → 从对应 css 里解析出的十六进制值（形如 `#F7F6FA`）
- Produces `[本计划新建]`: `final class ThemeManager`
  - `ThemeManager(ThemeMode initial)`
  - `ThemeMode current()` / `void setMode(ThemeMode)` / `boolean reduceMotion()` / `void setReduceMotion(boolean)`
  - `void attach(Scene scene)` —— 用 `Scene.getStylesheets().setAll(...)` **整体替换**样式表（不用 `add`，否则明暗切换会叠加两份）
  - `boolean verifyAllTokensResolve()` —— 每个 `ThemeTokens` 在**两个模式**下都能解析到合法十六进制值
- Consumes: 无（`ui/theme` 只依赖 JavaFX 与 `design-system/study-assistant/MASTER.md` 的令牌表）

**规则（全部必须实现）**
- **令牌值只在两个 CSS 文件里定义，Java 代码里一个硬编码十六进制颜色都不许有。** `ThemeTokensTest` 有一条断言扫描 `src/main/java` 下**没有** `#RRGGBB` 字面量。
- **字体族显式指定且只用无衬线中文族**：`"Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans SC", "Segoe UI"`。**不得**引入 Lora / Raleway（`MASTER.md` §1 已裁定：二者无 CJK 字形，中文会回退甚至出方块——这正是计划 1 的已知风险 3）。
- **不用 `@import url(...)` 引 Google Fonts**：桌面应用要离线可用；字体族就是系统字体回退链。
- 基础字号 `15px` 逻辑像素；**层级只用字重 + 字号 + 间距**表达（`MASTER.md` §3）；不引入第二种中文字体。
- **明暗切换 = 降饱和 + 提亮同色相**，不是反相（`MASTER.md` §2 的暗色表）。暗色主题**不用阴影**，用「面抬升 + 描边」表达层级。
- 圆角：控件 `6` / 卡片 `10` / 对话框 `14` / 状态 chip 全圆。
- 动效只有状态过渡（悬停/按下/展开/焦点）150–200ms 缓出；**无**滚动揭示、无视差、无自动播放；同一视图最多 1–2 处。JavaFX 无 CSS 媒体查询，`prefers-reduced-motion` 的等价物是 **`ThemeManager.reduceMotion()`** 开关（设置页绑它）。
- 四种状态色与「待回报」：`-fx-c-state-active` / `-fx-c-state-done` / `-fx-c-state-paused` / `-fx-c-state-cancelled`，`-fx-c-state-await` 是 `#565A6E` + **虚线边** `#8F8CA0`（无独立色相，靠形状区分）。
- 危机面板三色：`-fx-c-crisis-bg: #F3EFE7`（暖砂）/ `-fx-c-crisis-bar: #7A6647` / `-fx-c-crisis-text: #4A4238`。**不是红色、不是警告色。**
- **CSS 里不许写 `px` 之外的长度单位混用**：字号用 `px`（JavaFX 的 `-fx-font-size` 只认 `px`/`em`），间距用 `em`。

- [ ] **Step 1: 写失败的测试 `ThemeTokensTest`**

至少覆盖这些断言（方法名即断言意图，值必须逐字，`MASTER.md` §2 的令牌值为准）：

```java
// everyToken_resolvesInBothModes
for (ThemeMode mode : ThemeMode.values())
    for (ThemeTokens token : ThemeTokens.values())
        assertTrue(ThemeTokens.valueOf(token, mode).matches("#[0-9A-Fa-f]{6}"),
                token.key() + " 在 " + mode + " 下必须解析到 #RRGGBB，实际：" + ThemeTokens.valueOf(token, mode));

// lightAndDark_defineTheSameTokenSet
assertEquals(ThemeTokens.values().length, countKeysIn("/css/tokens-light.css"));
assertEquals(ThemeTokens.values().length, countKeysIn("/css/tokens-dark.css"));

// specPinnedValues_areExact
assertEquals("#F3EFE7", ThemeTokens.valueOf(ThemeTokens.CRISIS_BG, ThemeMode.LIGHT));   // 暖砂，不是红
assertEquals("#16171C", ThemeTokens.valueOf(ThemeTokens.BG, ThemeMode.DARK));
assertEquals("6.27:1 的 #4A55A2", "#4A55A2 的对比度说明：见 MASTER.md §2");              // 占位注意：见下

// noHardcodedHexColorInJavaSources
// 扫描 src/main/java 下全部 .java，断言不含 "#" + 6 位十六进制（正则 "#[0-9A-Fa-f]{6}"）
// 唯一的例外机制：注释里写 U+ 形式而不是字面色值

// noForbiddenSemanticTokens
// 两份 tokens css 合起来不含 danger|error|warning|success 这些令牌名（大小写不敏感）

// noPixelLockedSizesInCss
// app.css 里不出现 "min-width: 9" 这类把控件锁死在 900px 以上的写法（正则扫 min-width 数值 > 320）

// cssFiles_differOnlyInTokenFile
assertEquals("/css/tokens-light.css", ThemeTokens.cssFiles(ThemeMode.LIGHT).get(0));
assertEquals("/css/tokens-dark.css",  ThemeTokens.cssFiles(ThemeMode.DARK).get(0));
assertEquals(ThemeTokens.cssFiles(ThemeMode.LIGHT).subList(1, 2), ThemeTokens.cssFiles(ThemeMode.DARK).subList(1, 2));

// reduceMotion_defaultsToFalse_andToggles
```

> **写测试时的一条纪律**：上面 `specPinnedValues_areExact` 里的第二行是**故意写出来的反例占位**，实现时必须换成对**具体数值**的断言（例如 `assertEquals("#4A55A2", ThemeTokens.valueOf(ThemeTokens.ACCENT, ThemeMode.LIGHT))`）。**不许**留下任何形如「说明见文档」的占位断言——本项目判定覆盖的唯一标准是「两种错误实现下都会红」，占位断言两种都不会红。

- [ ] **Step 2: 先跑现有全量，确认基线是绿的**

Run: `MVN -pl study-core clean test`
Expected: 与开工时记录的数字一致（计划 2 Task 2 结束时的基数是 **218 run / 0 fail / 0 error / 4 skipped**；若计划 2 又推进了任务，以当时的实际值为准并写进报告）。**这一步不通过就不要往下做。**

- [ ] **Step 3: 跑新测试，确认红**

Run: `MVN -pl study-desktop clean test -Dtest=ThemeTokensTest`
Expected: 编译失败，`cannot find symbol: class ThemeTokens`（或 `ThemeMode`）。

- [ ] **Step 4: 实现**

要点（签名与断言已定，这里是留下的选择）：
- 两个 tokens css 写成同构的两份，键完全一样、只有值不同：明色用 `MASTER.md` §2 第一张表，暗色用第二张表（第二张是两列排版，读的时候注意它仍是 24 个键）。
- `app.css` **只用** `-fx-c-*` looked-up color 与 `em` 单位，不含任何字面色值。
- `ThemeTokens.valueOf` 的实现方式自选（在类初始化时把两个 css 用 `getResourceAsStream` 读进来、用正则 `(-fx-c-[a-z-]+)\s*:\s*(#[0-9A-Fa-f]{6})` 抽出映射）。**不许**用 `Scene` 渲染后再 `getComputedStyle`——那需要启动 JavaFX 运行时，测试会变成一个「起不起得来图形栈」的抽奖。
- `ThemeManager.attach` 用 `scene.getStylesheets().setAll(...)`：`add` 会在每次切换时叠加一份旧令牌，第二份以更晚的顺序生效——表现是「切了没反应」，很难查。
- 字体族写成常量（`-fx-font-family` 值），在 `app.css` 的 `.root` 上设一次，其余选择器继承。

- [ ] **Step 5: 跑新测试，确认绿**

Run: `MVN -pl study-desktop clean test -Dtest=ThemeTokensTest`
Expected: 全绿。

- [ ] **Step 6: 全量回归 + 桌面模块编译**

Run: `MVN -pl study-core clean test` → 与 Step 2 的数字一致（0 fail / 0 error）
Run: `MVN -q -DskipTests package` → BUILD SUCCESS
（**`mvn test` 根本不编译 `study-desktop/src/main`**，所以「界面代码能编译」这件事必须由 `package` 显式验证。`-DskipTests` 是安全的：它跳过测试执行但仍编译测试源码；**不要**用 `-Dmaven.test.skip=true`，那会连测试编译一起跳过、把这唯一的编译检查也绕过去。）

- [ ] **Step 7: Commit**

```bash
git add study-desktop/pom.xml study-desktop/src/main/java/com/xiaoyiyuan/study/ui/theme \
        study-desktop/src/main/resources/css study-desktop/src/test/java/com/xiaoyiyuan/study/ui/theme
git commit -F <utf8-消息文件>     # 主题：feat(ui): 视觉令牌与明暗主题（无硬编码色值，200% 缩放安全）
```

---

### Task 2: 应用外壳与导航

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/AppShell.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/Navigation.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/Page.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/PlaceholderPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/StatusBar.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/StartupCheck.java`
- Modify: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/App.java`（把冒烟窗口换成 `AppShell`；**`Launcher` 不动**）
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/StartupCheckTest.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/NavigationTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `enum Page { OVERVIEW("总览"), GOALS("目标"), COURSES("课程"), WEEK_CHECK("周检查"), WEEK_LOG("周记录"), EMOTION("情绪"), EMOTION_TREND("情绪趋势"), ASSISTANT("助手"), SETTINGS("设置"), CRISIS("求助资源") }`，含 `String label()` 与 `boolean inSidebar()`
- Produces `[本计划新建]`: `final class Navigation`
  - `Navigation(Page initial)`
  - `Page current()` / `void go(Page)` / `void addListener(Consumer<Page>)`
  - `List<Page> sidebarItems()` —— **侧栏顺序即 spec §8.1 的页面顺序**（总览 → 目标 → 课程 → 周检查 → 周记录 → 情绪 → 情绪趋势 → 助手 → 设置）
- Produces `[本计划新建]`: `record StartupCheck.Result(boolean ready, List<String> notices, Path planFile, Path legacyFile, Path studyDir)`
- Produces `[本计划新建]`: `final class StartupCheck`
  - `static Result inspect(Path studyDir)`
  - `static boolean shouldOfferMigration(Path studyDir)` —— 计划文件**不存在**且 `408-进度.md` **存在**时为真（spec §4.6 的触发条件）
  - `static boolean isWritable(Path studyDir)` —— 目录不存在时看**最近的已存在祖先**是否可写，不要把「目录还没建」报成不可写
- Produces `[本计划新建]`: `final class AppShell`：`AppShell(Stage, AppConfig, Navigation)` + `Parent build()`
- Consumes `[已交付]`: `AppPaths.planFile(Path)`、`AppPaths.legacyFile(Path)`、`AppPaths.defaultStudyDir()`、`AppConfig`（`studyDir()` / `deferredVisibility()` / `moodOptions()`）

**规则（全部必须实现）**
- **单窗口 + 左侧导航**（spec §8.1）。窗口 `Scene` 初值 `1100×720`，**最小尺寸 900×600**；**不设最大尺寸**、**不锁死宽高相等**（200% 缩放安全，见 Global Constraints 第 1 条）。
- **导航与内容区解耦**：`Navigation` 是纯逻辑（可单测），`AppShell` 只订阅它并换内容区。**不许**把页面切换写在按钮的事件处理器里各写一遍。
- 状态栏显示三件事：当前 `studyDir`、当前工作模式（离线可用 / 已配置 AI）、以及**最后保存时间**（由 Task 3 的 `DocumentSession` 提供；Task 2 先留接口、显示「尚未保存」）。
- **主题切换**：一个可见的切换控件（明 / 暗），调 `ThemeManager.setMode`。
- **启动引导（首次运行检查）**：
  - 计划文件存在 → 正常进入「总览」；
  - 计划文件不存在、旧档案存在 → 进入「总览」并显示一条**中性提示条**：「旧档案还在原来的位置。可以把它迁移成新的计划文件。」+ 一个「打开迁移向导」按钮（迁移向导本体在 Task 9 的「设置」页；Task 2 只需把按钮指过去）。
  - 两个都不存在 → 显示中性提示「还没有计划文件。可以在设置里选一个目录，或让助手帮你建一份。」**不报错、不阻断、不用红色、不用 `Alert.ERROR`**。
  - **启动路径上任何一个检查失败都不许让 App 起不来**：`AppConfig.load` 已是「坏了也回默认值」；`StartupCheck` 同理——读不动就返回 `ready=false` + 一条中性 notice，界面照常起来。
- **禁用词**：外壳里出现的中文一律从 Task 2 起就走 `UiText`（Task 11 会扫描；现在先建立这个习惯，避免 Task 11 时返工）。

- [ ] **Step 1: 写失败的测试 `NavigationTest` 与 `StartupCheckTest`**

```java
// NavigationTest
// sidebarItems_followsSpecOrder
assertEquals(List.of(Page.OVERVIEW, Page.GOALS, Page.COURSES, Page.WEEK_CHECK, Page.WEEK_LOG,
                     Page.EMOTION, Page.EMOTION_TREND, Page.ASSISTANT, Page.SETTINGS),
             new Navigation(Page.OVERVIEW).sidebarItems());
// go_notifiesListenersExactlyOnce
// go_samePageTwice_doesNotNotifyTwice
// crisisPage_isReachableButNotInSidebar   —— 危机面板是覆盖层（Task 10），侧栏没有它

// StartupCheckTest（用 @TempDir，绝不碰真实目录）
// bothFilesMissing_notReady_andOffersNothingToMigrate
// onlyLegacyPresent_offersMigration
// onlyPlanPresent_readyWithoutMigration
// unwritableDir_isReportedAsNotice_notException      —— 指向一个不存在且父目录不可写的路径
// notices_neverContainForbiddenWords                  —— 对 Result.notices() 逐个断言不含禁用词
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=NavigationTest,StartupCheckTest`，Expected: 编译失败（`cannot find symbol: class StartupCheck`）
- [ ] **Step 4: 实现**（要点：`StartupCheck` **只读**，绝不创建目录、绝不写文件；`isWritable` 往最近的已存在祖先上探；`AppShell` 用 `BorderPane`：`left` = 导航，`center` = 内容，`bottom` = 状态栏，`top` = 提示条）
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=NavigationTest,StartupCheckTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test`（数字不变）+ `MVN -q -DskipTests package`（BUILD SUCCESS）
- [ ] **Step 7: 人工看一眼窗口**（本任务只做「起得来、能切页、中文无方块」）

Run（**顺序不能省**）：`MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run`
Expected: 标题为应用的窗口出现，中文正常，9 个侧栏项可点，切换时内容区换标题，状态栏显示 studyDir。
**取证纪律（计划 1 实测的三条）**：① 不要用全屏截图再声称窗口在里面——本机 200% 缩放，`GetWindowRect` 是**逻辑**坐标而截图位图是**物理**像素，按逻辑坐标裁剪会偏 2 倍；正确做法是先按窗口标题找到句柄、取**物理**坐标后 `CopyFromScreen`。② `PrintWindow(PW_RENDERFULLCONTENT)` **拿不到** JavaFX 场景内容（只有原生标题栏），不要用它。③ **人工确认是最终权威**，截图为辅。

- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui study-desktop/src/test/java/com/xiaoyiyuan/study/ui
git commit -F <utf8-消息文件>     # 主题：feat(ui): 应用外壳与 9 项导航 + 启动引导（坏配置也能起来）
```

---

### Task 3: 界面侧数据访问层（`ui/service`，`read()` 先于 `save()` 的落点）

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/ExternalChangePolicy.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/DocumentSession.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/PlanService.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/EmotionService.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/WeeklySnapshot.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/StudyDirs.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/service/DocumentSessionTest.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/service/WeeklySnapshotTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `enum ExternalChangePolicy { ASK_USER, RELOAD, FORCE_OVERWRITE }`
- Produces `[本计划新建]`: `enum SaveResult { SAVED, BLOCKED_EXTERNAL_CHANGE, UNCHANGED }`
- Produces `[本计划新建]`: `final class DocumentSession`
  - `static DocumentSession open(Path file, BackupRotation rotation) throws IOException` —— **唯一构造入口**，内部先 `FileStore.read()` 建基线
  - `Path file()` / `String originalText()` / `String currentText()` / `boolean isDirty()` / `String baselineHash()`
  - `void update(String newText)`
  - `SaveResult save(ExternalChangePolicy policy) throws IOException`
  - `String reload() throws IOException` —— 丢弃内存编辑、重新 `read()`、返回新的文本
- Produces `[本计划新建]`: `final class WeeklySnapshot`
  - `record BaselineView(String goal, String item, String amount, String plan, String status, boolean goalActive)`
  - `static List<BaselineView> baselines(PlanDocument)` —— **只含「进行中」目标的条目**（spec §5.4）；暂缓/已取消目标的行返回时 `goalActive=false` 且**不进本周**
  - `static List<Course> coursesThisWeek(PlanDocument)` —— §三 全部行
  - `static List<String> lifeRules(PlanDocument)` —— §五 生活准则的逐行文本
- Produces `[本计划新建]`: `final class PlanService`
  - `PlanService(DocumentSession session, Path studyDir)` / `PlanDocument document()` / `void mutate(Consumer<PlanDocument>)` / `SaveResult save(ExternalChangePolicy)`
  - `void setLifeRule(int index, String text)` / `void addLifeRule(String text)` / `void removeLifeRule(int index)` / `void moveLifeRule(int from, int to)`
- Produces `[本计划新建]`: `final class EmotionService`
  - `EmotionService(DocumentSession session)` / `EmotionLog log()` / `void mutate(Consumer<EmotionLog>)` / `SaveResult save(ExternalChangePolicy)`
- Produces `[本计划新建]`: `final class StudyDirs`
  - `static Path studyDir(AppConfig)`、`static Path planFile(AppConfig)`、`static Path legacyFile(AppConfig)`
  - `static Path emotionFile(AppConfig)` → `<studyDir>\生活与情绪记录.md`
  - `static Path promptsDir(AppConfig)` → `<studyDir>\提示词`
  - `static Path materialsDir(AppConfig)` → `<studyDir>\资料`
  - `static Path backupDir(AppConfig)`、`static Path archiveDir(AppConfig)`
  - `static BackupRotation backupRotation(AppConfig)` → 每文件保留 10 份（spec §4.5 的份数）
- Consumes `[已交付]`: `FileStore`（`read` / `save(String,boolean)` / `reload` / `contentHash` / `changedExternally`）、`SaveOutcome`、`BackupRotation`、`AppPaths`、`PlanDocument`、`PlanSection`、`Goal`/`GoalStatus`/`Course`/`Baseline`/`Phase`/`ProfileItem`、`EmotionLog`/`EmotionEntry`、`AppConfig`
- **`[本计划新建]` 的路径派生**：`AppPaths` 目前**没有**情绪文件、提示词目录、资料目录这三条派生（计划 1 只做了计划文件、旧档案、备份、归档）。本任务**不修改 `AppPaths`**（它是计划 1 的冻结面），而是在 `StudyDirs` 里派生：`studyDir.resolve("生活与情绪记录.md")`、`studyDir.resolve("提示词")`、`studyDir.resolve("资料")`。**裁定：提示词目录不做成配置项**，只由 `studyDir` 派生——理由与代价见裁定 A3。

**规则（全部必须实现，这是本任务的核心）**
- **`read()` 先于 `save()` 的落点就是 `DocumentSession.open` 与 `save`：**
  1. `open` 是**唯一**构造入口，构造器 `private`。构造器里什么都不做；`open` 里先 `rotation` 或 `new FileStore(file, rotation)` 再 `store.read()`。**`FileStore` 的构造器刻意不读盘，且它自己的文档写明「已有内容的文件若一次 `read()` 都没做就直接 `save`，会返回 `EXTERNAL_CHANGE_DETECTED`」**——那是失败关闭，不是漏报，我们正好靠它兜底。
  2. `save` 的顺序：**先比对 `store.changedExternally()`**。为真且 `policy == ASK_USER` → **不写一个字节**、返回 `BLOCKED_EXTERNAL_CHANGE`。`RELOAD` → `store.reload()` 后返回 `BLOCKED_EXTERNAL_CHANGE`（**不写**，把「重新载入」的语义交回界面）。`FORCE_OVERWRITE` → 调 `store.save(currentText, true)`。
  3. 内容与 `originalText` 相同 → 返回 `UNCHANGED` 并**根本不调 `store.save`**。这一条不是优化，是**正确性**：`FileStore.save` 会先备份再写，一次「什么都没改的保存」会在 `.backup\` 里堆一份无意义快照，把真正需要的那十份挤掉（每文件只保留 10 份）。
  4. `save` 成功后把 `originalText` 更新为写下去的那份文本。
- **每份文件全局只有一个 `DocumentSession`**（由 `AppShell` 持有并注入各页面）。**禁止**任何页面自己 `DocumentSession.open(planFile)`。两个页面各持一份内存副本时，第二个保存的人会静默覆盖第一个（Review Focus 3）。
- **界面代码禁止直接调** `new FileStore(...)`、`TextFileCodec.write(...)`、`Files.write(...)`、`PlanDocument.parse(...)`。Task 3 之后，`ui/view` 与 `ui/control` 里出现这些调用一律算返工。**唯一的例外是 Task 9 的 `ConversationStore` 与迁移（它们不经过 `DocumentSession`）**：迁移走 `Migrator.plan` / `Migrator.apply`（见 Task 9），对话历史走 `ConversationStore`。
- `EmotionLog` **没有暴露 `dirty()`**，而且对**被编辑过**的条目重新生成时会把半角 `|` 规范化为全角 `｜`（计划 2 Task 1 的已知取舍）。因此「有没有改动」只能由 `DocumentSession` 用「渲染结果 vs `originalText`」判定——这正是第 3 条的 `UNCHANGED` 分支。**界面层不许自己维护第二套脏标记。**
- **`reload()` 与 `open()` 都要能吃 `NotUtf8Exception`**：非法 UTF-8 一律**不写任何内容**，界面显示中性提示「该文件不是合法 UTF-8，已停止操作，没有写入任何内容」+ 文件路径。**不做任何替换、不猜编码。**
- **`.backup\` 与 `.archive\` 由写入方创建**（计划 1 的教训）：`DocumentSession` 不建目录，`FileStore`/`BackupRotation` 在真正需要时建。
- 本任务的 `DocumentSession` 允许**同步**读写（文件都是几十 KB，Windows 本地盘）。**不要**为了「不卡界面」提前引入线程池或 `javafx.concurrent.Task`——`save` 的语义是「用户点了保存，我要立刻知道成没成」，异步化会把「外部改动确认」这个交互做成两段式，收益为零、复杂度翻倍。

- [ ] **Step 1: 写失败的测试 `DocumentSessionTest`**（这是本计划最重要的一组测试）

```java
// save_beforeAnyRead_isBlocked_notSilentOverwrite        ← 计划 1 FileStore 的失败关闭
Path f = dir.resolve("p.md"); Files.writeString(f, "已有内容\n");
DocumentSession s = DocumentSession.open(f, rotation);
Files.writeString(f, "别人改过\n");                         // 模拟 AI 助手/编辑器改动
assertEquals(SaveResult.BLOCKED_EXTERNAL_CHANGE, s.save(ExternalChangePolicy.ASK_USER));
assertEquals("别人改过\n", Files.readString(f), "检测到外部改动时一个字节都不许写");

// save_afterRead_writesBackExactly
// open → update("新内容\n") → save(ASK_USER) == SAVED，磁盘逐字等于 "新内容\n"

// save_unchangedContent_isUNCHANGED_andDoesNotBackup
// open → save(ASK_USER) == UNCHANGED；且 .backup 目录根本不存在（没有产生任何快照）

// everySave_leavesABackupWithSpecName
// 两次真实保存后：.backup 下有 2 个文件，名字匹配 "^p\.md\.\d{8}-\d{6}\.bak$"

// forceOverwrite_isTheOnlyWayToWriteOverAnExternalChange
// 外部改动后 save(FORCE_OVERWRITE) == SAVED，磁盘等于我们的内容，且备份数 +1

// reload_discardsMemoryEditAndRebaselines
// open → update("我的编辑") → 外部改动 → reload() 返回外部那份文本；再 save() == UNCHANGED

// invalidUtf8_isReportedAndNothingIsWritten(@TempDir)
// 用 GBK 字节写一个文件 → assertThrows(NotUtf8Exception.class, () -> DocumentSession.open(f))
// 并且断言该文件字节与写下去时完全相同

// twoSessionsOnSameFile_secondSaveIsBlocked
// 这一条把「一份文件只能有一个 session」的必要性钉在测试里：
// a = open(f); b = open(f); a.update("A"); a.save(ASK_USER) == SAVED;
// b.update("B"); assertEquals(BLOCKED_EXTERNAL_CHANGE, b.save(ASK_USER));   // b 的基线过期了

// planService_editLifeRule_keepsEveryOtherByteIdentical      ← P2 在界面侧的落地
// 读一份含 §五 bullet 的样例计划 → setLifeRule(0, "新的第一行") → 断言：
//   ① render() 里含 "新的第一行"；② 除该行外，其余每一行仍逐字存在（逐行比对原文本）
// lifeRules_addRemoveMove_areOrderStable

// emotionService_addEntry_writesThroughAndKeepsHeaderByteIdentical
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=DocumentSessionTest,WeeklySnapshotTest`
- [ ] **Step 4: 实现**（要点：`DocumentSession` 的 `save` 把「比对 → 决策 → 调 `FileStore`」写成**唯一一条**分支链，不许有第二条写路径；`WeeklySnapshot.baselines` 按 `goals()` 里 `status() == ACTIVE` 的名字过滤 §四 的行，**名字比对忽略两侧空白**（用户手写表格常留空格，`Goal.name()` 与 `Baseline.goal()` 都不是 strip 过的））
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=DocumentSessionTest,WeeklySnapshotTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 形态断言（把「唯一写入口」变成可执行的约束）**

在 `DocumentSessionTest` 里加一条**反射**断言（**不要**在测试里读源码文件——那依赖工作目录，脆弱；计划 2 已就同类问题裁定过用反射）：

```java
// uiViewLayer_hasNoDirectFileWriteApi
// 扫描 com.xiaoyiyuan.study.ui.view 与 com.xiaoyiyuan.study.ui.control 下的类：
//   断言其中没有任何类引用了 java.nio.file.Files / TextFileCodec / FileStore
// 实现方式自选（反射扫类名即可，类列表由测试显式写死并 assertTrue 非空——
//   空列表会让 loop 形同虚设，这正是 Task 1 那条占位断言的同类错误）
```

- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/service
git commit -F <utf8-消息文件>     # 主题：feat(ui): 界面侧数据访问层（read 先于 save，外部改动不静默覆盖）
```

---

### Task 4: 进度总览页 + 周计划页 + 周记录页

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/OverviewPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/WeekCheckPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/WeekLogPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/OverviewController.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/WeekLogController.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/text/UiText.java`（**本计划所有界面文案的唯一去处**；Task 4 起被全体视图共享；Task 11 的错误消息中立化包装也放同包）
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/WeekLogControllerTest.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/text/UiTextTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `final class UiText`
  - 全部界面文案的 `public static final String` 常量（按页面分组、加 `// ── 总览 ──` 这类注释）
  - `static String goalStateChip(GoalStatus)` → `进行中` / `暂缓` / `已取消` / `已完成`（**逐字来自 `GoalStatus.label()`**，不许另写一套）
  - `static String entryStatusChip(String raw)` → 原样返回 `待回报` / `进行中` / `已完成`；**认不出来的原样显示原文**，绝不映射成带评判的措辞
  - `static String emptyData()` → `未回报`
  - `static String neutralize(String coreMessage)` —— 把 core 异常消息里可能出现的禁用词替换为中性表述，并**在替换确实发生时**附一句「（详细信息见日志）」。**这个方法必须先把 core 原文写进日志**，因为替换本身是有损的
  - `static List<String> forbiddenWords()` / `static List<String> diagnosticLabels()` —— **只在测试里用**（Task 11 的扫描器与自检都读它）
- Produces `[本计划新建]`: `WeekLogController`
  - `WeekLogController(PlanService)`
  - `String render()` —— 文档全文
  - `List<WeekRecord> weeks()` —— `WeekRecordAppender.parseWeeks(render())`
  - `String appendWeekPreview(int weekNumber)` —— 只算不写（纯字符串进出）
  - `void appendWeek(int weekNumber)` —— 会把新文本灌进 session（**但仍要用户点保存才落盘**）
- Produces `[本计划新建]`: `OverviewPage` / `WeekCheckPage` / `WeekLogPage`：`Page` 的实现，构造 `(PlanService, AppConfig, Consumer<Page> navigate)`
- Consumes `[本计划新建]`: `PlanService`、`WeeklySnapshot`、`DocumentSession`（Task 3）、`UiText`（本任务）
- Consumes `[已交付]`: `WeekRecordAppender.parseWeeks(String)` / `hasWeek(String,int)` / `appendWeek(String,int)`、`WeekRecord(int, LocalDate, LocalDate, String, List<String>)`、`WeekCalendar.weekNumber(LocalDate)` / `mondayOf` / `sundayOf` / `weekRangeLabel(int)`、`PlanDocument.{profile,goals,courses,baselines,phases}()` 及其增删改、`GoalStatus.label()`

**规则（全部必须实现）**
- **总览页只列「进行中」的目标**（spec §8.1）：① 学校课程（各课本周任务）② 每周底线卡 ③ 生活准则。**「暂缓」目标绝不出现**——包括它的底线行（`WeeklySnapshot.baselines` 已过滤）。理由：总览上出现一个暂缓目标，用户读到的是「你少了什么」。
- **周检查页三块录入**（spec §8.1）：课程完成情况 / 底线达成 / 有没有被挤掉 → 生成复盘（可调 AI，也可手写）。AI 入口在 Task 8 接上；本任务只做**手写**路径与三块表单。
  - **空数据 = 「未回报」**，不等于「未完成」（spec §5.8 第 3 条）。
  - 三块表单里的「完成情况」下拉项只能是 `待回报` / `进行中` / `已完成`；**不得**出现 `未完成`。
- **周记录页**（spec §5.7、§8.1）：时间线视图，每周一条；`### 周标题` 与其下 bullet 用**文本框直接编辑**（那些行含 LaTeX 与散文，按原样块处理，spec §4.4）。**追加新一周**：
  - 调 `WeekRecordAppender.hasWeek(render(), n)` 先判重；已有 → **中性提示**「第 N 周已经有记录了，改为编辑它」，**不覆盖、不重复插入**（`appendWeek` 自己也会抛 `IllegalStateException`，界面要接住并显示这句）。
  - `appendWeek` 抛 `IllegalStateException` 说「找不到周记录章节标题」时，如实显示：**这条消息里没有禁用词，可以原样展示**（已核）。
  - 追加之后**不自动保存**：spec §4.5「显式保存，不自动保存」。界面要把新周块显示出来、把保存按钮点亮。
- **周标题必须由 `WeekCalendar.weekRangeLabel` 生成**，界面里**不许**自己拼日期区间（计划 1 的教训：两处各拼一次迟早对不上）。
- **表格与 Markdown 表格的对应**（spec §4.2）：所有表格类内容（§一/§二/§三/§四/§六）用类型化表单编辑；所有原样块（§五/§七/§九/§十/文末注释）用文本框编辑原文。**不许**给原样块做结构化解析。
- **`UiText` 的纪律**：从本任务起，`ui/view` 与 `ui/control` 里**不许**再出现中文界面字面量。这是 Task 11 的 `DisabledWordTest` 能界定的唯一办法（Global Constraints 第 4 条）。
- 页面上任何一处「没有数据」都写 `未回报`；任何一处「未完成态」都写 `待回报`。**这三种措辞（`待回报`/`未回报`/`进行中`）是全局仅有的中性状态词。**

- [ ] **Step 1: 写失败的测试 `WeekLogControllerTest` 与 `UiTextTest`**

```java
// WeekLogControllerTest（纯逻辑，不碰 JavaFX，全部用 @TempDir 的样例计划文件）
// appendWeekPreview_insertsBeforeNextSectionHeading_andDoesNotTouchPrefix
// appendWeekPreview_doesNotChangeTheSessionText          —— 「预览是纯函数」这条性质
// appendWeek_existingWeek_isRejectedWithNeutralMessage
//   断言消息含 "第 4 周"，且不含任何禁用词（对 forbiddenWords() 逐个断言）
// appendWeek_missingWeeklySection_throwsAndSessionIsUnchanged
// weeks_parsesAllExistingWeeks_inFileOrder
// weekHeading_comesFromWeekCalendar
//   assertEquals("### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）",
//                WeekRecordAppender.parseWeeks(controller.appendWeekPreview(4)).get(0).heading());

// UiTextTest
// everyConstantValue_containsNoForbiddenWordOrDiagnosticLabel
//   反射枚举 UiText 的全部 public static String 常量（含 goalStateChip/entryStatusChip/emptyData 的返回值）
// goalStateChip_coversAllFourStates_andMatchesGoalStatusLabel
// entryStatusChip_passesThroughUnknownTextVerbatim
// neutralize_removesEveryForbiddenWord_andLogsOriginal
//   断言 neutralize("章节「目标清单」定位失败：…") 不含 "失败"
//   断言 core 原文整句出现在日志里（用 java.util.logging 的 Handler 捕获）
// forbiddenWords_isExactlyTheSpecList
//   assertEquals(List.of("未完成","逾期","落后","断签","失败","警告","已放弃","未坚持"), forbiddenWords());
// diagnosticLabels_isExactlyTheSpecList
//   assertEquals(List.of("抑郁","焦虑症","中度"), diagnosticLabels());
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=WeekLogControllerTest,UiTextTest`
- [ ] **Step 4: 实现**
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=WeekLogControllerTest,UiTextTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 人工验收** → `MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run`
  Expected: 总览页能看到课程/底线/生活准则三块，**看不到任何「考研」字样**（它是暂缓）；周记录页有 4 条历史（样例档案的周数），点「追加第 5 周」出现新周块且**保存按钮变亮**；不改动任何文件就退出，磁盘上的计划文件字节不变（用 `Get-FileHash` 前后比对）。
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/OverviewPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/WeekCheckPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/WeekLogPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/OverviewController.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/WeekLogController.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/text \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/WeekLogControllerTest.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/text
git commit -F <utf8-消息文件>     # 主题：feat(ui): 总览 / 周检查 / 周记录三页 + 界面文案常量层
```

---

### Task 5: 目标与阶段页

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/GoalsPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/GoalsController.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/GoalsControllerTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `GoalsController`
  - `GoalsController(PlanService, AppConfig)`
  - `List<Goal> visibleGoals()` —— 按 `AppConfig.deferredVisibility()` 过滤；`COLLAPSED` 时**只返回进行中/已完成**，暂缓与已取消进入 `deferredGoals()`
  - `List<Goal> deferredGoals()` / `List<Goal> endedGoals()`
  - `void setStatus(int index, GoalStatus)` / `void move(int from, int to)` / `void add(Goal)` / `void remove(int)`
  - `List<Phase> phases()` / `void addPhase(Phase)` / `void setPhase(int, Phase)` / `void removePhase(int)` / `void movePhase(int, int)`
  - `String phasesEmptyNotice()` —— `阶段排期为空` 时返回「当前无进行中的阶段排期」（spec §5.5 的**固定提示句**，**不得**显示为空白或错误）
  - `String stateChipClass(GoalStatus)` —— 返回 CSS 类名（`state-active` / `state-paused` / `state-cancelled` / `state-done`），**颜色与形状都由 CSS 给**，控制器只管选类
- Consumes `[已交付]`: `PlanDocument.goals()` / `setGoal` / `addGoal` / `removeGoal` / `moveGoal`、`Goal(String,GoalStatus,String,String,String)`、`GoalStatus`、`PlanDocument.phases()` / `setPhase` / `addPhase` / `removePhase` / `movePhase`、`Phase(String,String,String,String)`、`AppConfig.deferredVisibility()` / `setDeferredVisibility`、`DeferredVisibility`
- Consumes `[本计划新建]`: `PlanService`、`DocumentSession`（Task 3）、`UiText`（Task 4）

**规则（全部必须实现）**
- **四态中性显示**（spec §5.2、§5.8、`MASTER.md` §5）：`进行中` = `-fx-c-state-active` 实线 chip；`暂缓` = `-fx-c-state-paused`；`已取消` = `-fx-c-state-cancelled`；`已完成` = `-fx-c-state-done`。**chip 里永远带文字**，颜色只是辅助（`color-not-only`）。**`待回报` 用虚线边**、无独立色相。
- **暂缓 / 已取消操作没有任何二次确认恐吓文案**（spec §8.1「暂缓与取消均为中性操作」）。切换状态的确认对话框（如果有）只用中性措辞，**不许**出现「确定要放弃吗」这类句子——`已放弃` 是禁用词。
- **一键可从「暂缓」恢复为「进行中」**（spec §5.2）。
- **显示方式可配置**（spec §5.1、`DeferredVisibility`）：默认 `COLLAPSED`；`VISIBLE_DIM` 时暂缓内容**在列表里可见但弱化**（降透明度 + 灰字，**仍然带 `暂缓` 文字 chip**）；`HIDDEN` 时**完全不出现**。**裁定：`HIDDEN` 是「显示偏好」而不是「删除」**——切换它绝不改动文件、绝不丢数据（裁定见 A4）。
- **全字段可编辑**（spec §5.1）：目标的名称/状态/优先级/时间窗/说明、阶段的阶段/时间窗/主攻/验收标准**全部可改**；目标与阶段都支持**增 / 删 / 改 / 调序**（`PlanDocument` 已提供 `moveGoal` / `movePhase`，界面要有上移/下移按钮——spec §4.4 要求阶段排期保留行序）。
- **写入必须经 `PlanDocument` 的写入口，不许自己拼表格文本**：`PlanDocument` 的 `requireCellText` 闸门会**拒绝**含裸 `|` 或换行的值（消息含字段名与原文）。界面要在输入框上接住这个 `IllegalArgumentException`，把消息显示出来，**并且不关掉用户正在编辑的表单**（用户改一个字就能继续）。
- 优先级：`暂缓`/`已取消` 的目标**不参与排序**（spec §5.2）。界面排序时把它们排到末尾，但仍保留在列表里（除非 `HIDDEN`）。
- **`显示偏好` 与 `情绪选项` 存在应用配置里**，点「应用」才 `AppConfig.save()`；配置保存失败（磁盘满、权限）**必须让用户看见**，不许当作已保存。

- [ ] **Step 1: 写失败的测试 `GoalsControllerTest`**

```java
// visibleGoals_collapsedDefault_hidesDeferredAndCancelled
// visibleGoals_visibleDim_keepsThemWithPausedChip
// visibleGoals_hidden_removesThemEntirely
// setStatus_deferredToActive_isAnOrdinaryOperation_withNoExtraPrompt
//   断言操作只触发一次 session.update(...)，且没有第二个布尔参数/确认回调
// moveGoal_reordersRows_andKeepsEveryOtherLineIdentical   ← P2
// addAndRemoveGoal_roundTripThroughRenderAndReparse
//   新增 → render → PlanDocument.parse(render()) → 目标数 +1、字段逐字相同
// cellTextWithPipe_isRejected_andDocumentIsUnchanged
//   assertThrows(IllegalArgumentException.class, () -> c.setGoalName(0, "a | b"))
//   断言 render() 与调用前逐字节相同（被拒绝的写入一个字节都不许动）
// phasesEmpty_producesTheFixedNoticeSentence
//   assertEquals("当前无进行中的阶段排期", c.phasesEmptyNotice());
// phases_reorder_isOrderStable
// stateChipClass_mapsAllFourStates
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=GoalsControllerTest`
- [ ] **Step 4: 实现**（要点：`visibleGoals()` 的过滤只看 `AppConfig.deferredVisibility()`，**不改文件**；阶段排期的空态**必须**输出那句固定提示，不许留空 `TableView`）
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=GoalsControllerTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 人工验收** → `MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run`
  Expected: 目标页三条目标（两进行中、一暂缓）；把 `考研（408）` 从暂缓切回进行中、再切回暂缓，两次操作都**没有**任何恐吓文案；切到 `HIDDEN` 后它从列表消失、**再切回 `COLLAPSED` 它还在**（数据没丢）；阶段页显示「当前无进行中的阶段排期」。
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/GoalsPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/GoalsController.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/GoalsControllerTest.java
git commit -F <utf8-消息文件>     # 主题：feat(ui): 目标与阶段页（四态中性、全字段可编辑、行序可调）
```

---

### Task 6: 课程页与周检查页的课程部分（单源约定）

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/CoursesPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/CoursesController.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/CourseRoster.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/service/CourseRosterTest.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/CoursesControllerTest.java`
- Modify: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/WeekCheckPage.java`（接上课程部分）

**Interfaces:**
- Produces `[本计划新建]`: `final class CourseRoster`
  - `static List<String> parse(String profileCoursesCell)` —— 以 `、` 分隔、去空项、**去重保序**、每项 `strip()`
  - `static String join(List<String> names)` —— 用 `、` 拼回
  - `static String rosterItemKey(PlanDocument doc)` —— 找 §一 里「本学期课程」那一行的**行下标**；找不到抛 `SectionMissingException`（**不猜键名、不新建行**）
- Produces `[本计划新建]`: `CoursesController`
  - `CoursesController(PlanService)`
  - `List<String> roster()` —— 来自 §一 的课程清单（**唯一来源**）
  - `List<Course> table()` —— §三 的行
  - `SyncPreview syncPreview()` —— `record SyncPreview(List<String> toAdd, List<String> toRemove, List<String> toKeep)`
  - `void applySync()` —— 只在 §三 上增行/删行；**不动 §一**
  - `void setRosterItem(int index, String name)` —— 改 §一 里「本学期课程」的整行文本（`PlanDocument.setProfileItem`）
  - `void addCourse(Course)` / `void setCourse(int, Course)` / `void removeCourse(int)`
  - `String termLabel()` —— 读 §一 的「年级 / 学期」行，用于页头显示
- Consumes `[已交付]`: `PlanDocument.profile()` / `setProfileItem(int,String)`、`PlanDocument.courses()` / `addCourse` / `setCourse` / `removeCourse`、`Course(String,String,String,String)`、`ProfileItem(String,String)`、`SectionMissingException`
- Consumes `[本计划新建]`: `PlanService`（Task 3）、`UiText`（Task 4）

**规则（全部必须实现）**
- **单源约定（spec §5.3，硬要求）**：§一 的「本学期课程」行是课程清单的**唯一来源**；§三 的表格行**由该清单生成**（新增/删除课程**只在 §一 操作**，§三 随动）。
  - `CoursesController` **不提供**从 §三 直接新增课程名的入口。§三 的编辑限于**「本周任务」「截止」「状态」三列**。
  - §一 与 §三 不一致时**不自动同步**：页面上显示一条中性提示 + 「按课程清单更新课程表」按钮，按 `syncPreview()` 展示**将要增加/删除的课程名**（逐条列出），用户确认后才 `applySync()`。**不自动改**用户的表。
- **「暂缓」目标的课程**（未来若有）不进总览；本页只列全部课程行（它是课程清单的镜像）。
- 课程状态四选：`待填` / `进行中` / `已完成` / `待回报`（spec §5.3）。**没有「未完成」这个选项。**
- **`截止` 只作展示，绝不触发提醒**（spec §5.3）。页面里**不许**有任何定时器、通知、托盘图标、声音。过期日期**不做红标、不做排序**（乱序显示即可，或按文件行序）。
- **明确不做**（spec §5.3）：作业提醒推送 / 逾期红标 / 完成率统计 / 优先级自动排序算法。
- **学期切换**：改 §一 的「当前学期」文本即可（`setProfileItem`）；页头显示它。**历史课程不保留**（周记录里已留痕）。
- **`CourseRoster.rosterItemKey` 找不到「本学期课程」行时**：抛 `SectionMissingException` 并让界面显示「§一 里找不到『本学期课程』这一行，无法读取课程清单」。**不许**自动建行——那是改用户的文件。
- §一 的**行键不可改**（`PlanDocument` 只提供 `setProfileItem(index, value)`，只改「内容」列，没有增删行的口子）。因此课程清单的增删**只能在那一行的文本里加/减课程名**。这是 `PlanDocument` 当前公开面的真实边界（见「与计划 1/2 接口核对的结果」的缺口 #2）。

- [ ] **Step 1: 写失败的测试**

```java
// CourseRosterTest
// parse_splitsOnIdeographicComma_andDropsBlanks
// parse_deduplicatesButKeepsFirstOccurrenceOrder
//   assertEquals(List.of("数据库","Java","线性代数"), CourseRoster.parse("数据库、Java、、线性代数、数据库"));
// join_isInverseOfParse
// rosterItemKey_findsTheCoursesRow
// rosterItemKey_missingRow_throwsSectionMissingException
//   assertThrows(SectionMissingException.class, ...)，且文档一个字节都没变

// CoursesControllerTest
// applySync_addsOnlyMissingCourses_andNeverTouchesProfile
//   sync 前记录 §一 那一行的原文；sync 后断言它逐字未变
// applySync_removesCoursesThatLeftTheRoster
//   —— 删课程时 §三 的行随之消失；断言 §四/§六/§九 的字节都没动
// syncPreview_describesTheChange_withoutApplying
// setCourseTask_doesNotChangeTheCourseName
//   —— §三 的「课程」列不可从 §三 改：assertThrows(UnsupportedOperationException.class, …)
//   （或：接口里根本不提供这个方法——选一种并写进报告；测试要能区分二者）
// dueDate_isDisplayOnly_noTimersAreRegistered
//   断言控制器里没有 javafx.animation.Timeline 实例（反射扫字段），也没有 Notification/托盘引用
// addCourse_isNotExposedOnTheCoursesPage
//   用反射断言 CoursesController 没有接受课程名的新增入口（只有 applySync）
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=CourseRosterTest,CoursesControllerTest`
- [ ] **Step 4: 实现**
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=CourseRosterTest,CoursesControllerTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 人工验收** → `MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run`
  Expected: 课程页显示 6 门课（迁移给的是这 6 门）；把清单里的「物理学」删掉并确认同步 → 课程表少一行；页面上**没有**任何日期红标、没有「逾期」字样。
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/CoursesPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/CoursesController.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/service/CourseRoster.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/WeekCheckPage.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/service/CourseRosterTest.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/CoursesControllerTest.java
git commit -F <utf8-消息文件>     # 主题：feat(ui): 课程页与课程清单单源同步（§一 为唯一来源）
```

---

### Task 7: 情绪记录页与情绪趋势页

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/EmotionPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/EmotionTrendPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/EmotionController.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/EmotionTrendController.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/chart/MoodTrendChart.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/EmotionControllerTest.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/EmotionTrendControllerTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `EmotionController`
  - `EmotionController(EmotionService, AppConfig)`
  - `List<EmotionEntry> entries()` —— 最新在前（文件顺序）
  - `List<String> moodOptions()` —— `AppConfig.moodOptions()`（**可增删**，spec §5.1）
  - `void addEntry(EmotionEntry)` / `void replaceEntry(int, EmotionEntry)` / `void removeEntry(int)`
  - `String newEntryHeading()` —— 当天日期标题，由 `EmotionEntry.headingLabel(LocalDate.now())` 生成
  - `String analysisWindowLabel(AnalysisWindow)` —— 「近 7 天（2026-10-04 – 2026-10-10）」这类**中性**文案
- Produces `[本计划新建]`: `EmotionTrendController`
  - `record DayPoint(LocalDate date, String mood, Integer intensity, boolean reported)`
  - `List<DayPoint> points(AnalysisWindow window)` —— 区间内**每一天**一个点；该日没有记录时 `reported=false`、`mood=null`
  - `String textSummary(AnalysisWindow)` —— 给图表配的**纯文字摘要**（必须存在，Review Focus 5）
  - `String reportedCount(AnalysisWindow)` —— 「这个区间里记录了 N 天」。**不得**出现「N/M」这种比例写法，也**不得**出现 `%`（隐含完成率）
- Produces `[本计划新建]`: `final class MoodTrendChart`
  - `MoodTrendChart()` / `Node node()` / `void setPoints(List<DayPoint>)`
  - 每一点的标签由 `EmotionTrendController` 提供；**颜色只作辅助**，每一点旁有文字
- Consumes `[已交付]`: `EmotionLog.parse/empty/entries/addEntry/replaceEntry/removeEntry/hasEntryOn`、`EmotionEntry(date,mood,intensity,sleep,exercise,event,note,unknownLines)`、`EmotionEntry.headingLabel(LocalDate)`、`EmotionEntry.isBlankText(String)`、`AppConfig.moodOptions()` / `setMoodOptions`
- Consumes `[待计划 2]`: `AnalysisWindow(LocalDate from, LocalDate to)`、`AnalysisWindow.lastDays(LocalDate,int)`、`AnalysisWindow.select(EmotionLog)`、`EmotionAnalysis.build(PromptRole, AnalysisWindow, EmotionLog)`、`EmotionAnalysis.Result`（**以计划 2 终稿为准**）
- Consumes `[本计划新建]`: `EmotionService`（Task 3）、`UiText`（Task 4）

**规则（全部必须实现）**
- **文件由 App 在首次进入情绪页时创建**（spec §6.1）：`EmotionLog.empty().render()` → `DocumentSession` 写到 `<studyDir>\生活与情绪记录.md`。**创建动作必须由用户可见的按钮触发**（「开始记录第一天的情绪」），不许在切页时静默建文件——静默新建一个文件是「在用户背后动他的盘」。
- **点选录入（spec §6.2）**：情绪**点选**（`AppConfig.moodOptions()`，可增删）；强度 1–5 **由用户自己打分**（可以是「不打分」，即 `null`）；睡眠 / 运动 / 生活事件 / 备注可选。
  - **明确不做**：不做「连续记录 X 天」streak；不做「你今天还没记录」提醒；不统计「记录完整度」。**断记不补、不提示、不追责。**
- **字段为空则该行不输出**（spec §6.1）：用 `EmotionEntry.isBlankText(value)` 判定（它把 `null` 与**长度为零**当空，**不把修剪过的空白当空**——用户写一个空格也是字）。界面里**不要把空串和 null 混为一谈**：`null` = 文件里没有这一行，`""` = 有这一行但没内容。
- **同一天已有记录**：`addEntry` 抛 `IllegalStateException`（消息含日期），界面显示它并**切到编辑已有那一条**——**不覆盖、不追加第二条**。
- **未知行原样保留**（计划 2 Task 1）：编辑某一条时，`unknownLines` 要原样带回去（界面上显示为**只读的额外行列表**，或者原样出现在文本框里）。**绝不因为「不认识」就丢掉。**
- **整块降级的条目**（计划 2：形状不认识时整块降级、`entries()` 里没有它）**必须仍然可见**：趋势页与记录页都要显示「另有 N 段内容本 App 无法结构化，已在原文件中原样保留」并给出**跳转到原始文本编辑**的入口。**不许**让用户以为记录不见了。
- **趋势图必须同时有文字与数据表**（Review Focus 5、`MASTER.md` §7）：
  - 图上的点**只画有记录的日期**；没有记录的日期就是**没有点**，旁边文字写 `未回报`。
  - **不得**用颜色单独传达状态：每个点旁边有情绪词文字，强度用**点的半径或竖直位置**表示（不是只靠色）。
  - **不得**出现红色/橙色、**不得**出现 `%`、`得分`、`完成率`、`打卡`、`断签`、`中断` 这类词。
  - `textSummary` 是**必出**的：形如「这个区间里记录了 5 天。出现较多的情绪是：疲惫、平静。」
- **情绪选项列表可增删**（spec §5.1）：设置页/情绪页都能改；改完 `AppConfig.save()`。**删除一个正在被历史记录引用的情绪词，不影响历史记录**（历史是纯文本，本 App 不「修正」它）。
- **AI 分析入口**（spec §6.4 的流程）在本任务**只做「时间窗选择 + 原文预览的容器」**，真正的发送在 Task 8。本任务的 `EmotionController.analysisWindowLabel` 供 Task 8 使用。
- **不做情绪数据加密**（spec §1.2 已明确）。**不做**导出/分享情绪的快捷入口（分离的理由见 spec §6.1）。

- [ ] **Step 1: 写失败的测试**

```java
// EmotionControllerTest
// addEntry_sameDateTwice_isRejectedWithTheCoreMessage_andNothingChanged
// createFile_onlyWhenUserAsks            —— 构造控制器不建文件；assertFalse(Files.exists(emotionFile))
// createFile_writesTheExactHeaderFromEmotionLogEmpty
//   assertEquals(EmotionLog.empty().render(), Files.readString(emotionFile));
// emptyFieldOmitting_matchesEmotionEntry_isBlankText
//   —— 只填 date/mood 时 render 里没有 "- 睡眠："、"- 备注：" 等行
// unknownLines_areCarriedBackOnEdit
//   —— 取一条含 "- 天气：晴" 的条目，只改备注，断言 render 里 "- 天气：晴" 仍在
// moodOptions_addAndRemove_doesNotTouchExistingEntries
//   —— 删掉一个情绪词后，历史里那一条的文本逐字不变

// EmotionTrendControllerTest
// points_includeEveryDayInWindow_reportedFlagIsExact
//   lastDays(2026-10-10, 7) → 7 个点；只有 10-09 与 10-10 有记录 → 那两天 reported=true
// textSummary_neverContainsPercentOrRatio
//   assertFalse(summary.contains("%")); assertFalse(summary.matches(".*\\d+\\s*/\\s*\\d+.*"));
// reportedCount_isAWholeNumberSentence
//   assertEquals("这个区间里记录了 2 天", c.reportedCount(window));
// textSummary_andPoints_neverContainForbiddenWordsOrDiagnosticLabels
// degradedBlocks_areAnnounced_notHidden
//   —— 构造一份含「形状不认识」条目的文件，断言 notice 文本里含段数 N
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=EmotionControllerTest,EmotionTrendControllerTest`
- [ ] **Step 4: 实现**（要点：`MoodTrendChart` 用 `javafx.scene.chart.LineChart` 或 `ScatterChart` + `NumberAxis`/`CategoryAxis`；**轴与内容随容器拉伸**，不写死画布尺寸；点的文字标签用 `Tooltip` **加**一个旁边的**可见**文字列，别只做 tooltip）
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=EmotionControllerTest,EmotionTrendControllerTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 人工验收** → `MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run`
  Expected: 情绪页首访时**没有**自动建文件；点「开始记录」才出现文件（用资源管理器确认路径与文件名逐字为 `生活与情绪记录.md`）；记录一条后趋势页出现一个点 + 一句文字摘要；**页面里没有任何红色**。
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/EmotionPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/EmotionTrendPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/EmotionController.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/EmotionTrendController.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/chart \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/EmotionControllerTest.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/EmotionTrendControllerTest.java
git commit -F <utf8-消息文件>     # 主题：feat(ui): 情绪记录页与趋势页（低摩擦录入、不评判、图表不靠颜色）
```

---

### Task 8: AI 对话页（逐次确认门 + 流式 + 写入前必须点确认）

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/AssistantPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/AssistantController.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/SwingConsentGate.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/StreamingTextSink.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/AssistantControllerTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `final class AssistantController`
  - `AssistantController(AiClient, ConsentGate, ConversationStore, PromptLibrary, UiText)`（**全部注入**，测试里给假实现）
  - `record Preview(String fullText, int characterCount)` —— 界面就显示这两项
  - `Preview preview(String userInput)` —— **只构造、不发送**
  - `void send(String userInput)` —— 内部走 `ConfirmingAiSession.complete/stream`；**未确认时绝无网络请求**
  - `void appendDelta(String)` / `String receivedText()` / `boolean isTruncated()`
  - `void writeToPlan(String text)` / `void writeToMaterials(String text)` —— **只有用户点了「写入」才会被调用**；两者都经 `PlanService`（前者另存为一份 `.md` 到 `materialsDir`）
  - `Conversation currentConversation()` / `void exportToMaterials() throws IOException`
- Produces `[本计划新建]`: `final class SwingConsentGate implements ConsentGate`
  - 构造 `SwingConsentGate(Function<PendingRequest, Boolean> display)` —— 真正的弹窗注入进来，测试里给 `req -> false` / `req -> true`
  - `boolean confirm(PendingRequest)`
- Produces `[本计划新建]`: `final class StreamingTextSink implements AiStreamListener`
  - `void onDelta(String)` / `void onComplete(String)`；**把增量转交给 `Consumer<String>`**，界面在 FX 线程上消费
- Consumes `[待计划 2]`: `AiClient(AiConfig)`、`String AiClient.complete(AiRequest)`、`void AiClient.stream(AiRequest, AiStreamListener)`、`AiConfig(String,String,String,Duration)`、`AiRequest(String,List<AiMessage>,boolean)`、`AiMessage(String,String)`、`AiFailure`/`AiFailureKind`、`AiStreamListener{onDelta,onComplete}`、`PromptRole`/`PromptLibrary(Path)`/`load(PromptRole)`/`isUsingBuiltIn(PromptRole)`、`PendingRequest(String,String,List<AiMessage>)`、`ConsentGate{confirm(PendingRequest)}`、`ConfirmingAiSession(AiClient,ConsentGate)`/`complete`/`stream`/`lastSentPreview()`、`Conversation`/`ConversationStore(Path)`/`save`/`load`/`list`/`delete`/`toMarkdown`、`AnalysisWindow`/`EmotionAnalysis.build`/`EmotionAnalysis.Result`（**全部以计划 2 终稿为准**）
- Consumes `[本计划新建]`: `PlanService`、`StudyDirs`（Task 3）、`UiText`（Task 4）
- Consumes `[已交付]`: `AppConfig.baseUrl/apiKey/model/requestTimeout`、`AppPaths.conversationsDir()`

**规则（全部必须实现，这是全计划安全要求最高的一页）**
- **逐次确认门（spec §7.5，硬要求）——界面的三个动作顺序不许颠倒：**
  1. 用户输入后，先只做 `preview()`，把 `PendingRequest.promptText()` **完整原文**显示在一个**可滚动、可选中、可复制**的文本区里。**不是摘要、不是截断、不是折叠**。旁边显示字数。
  2. 只有用户点了「发送并分析」，才调 `send(...)`；`ConsentGate.confirm` 在这里被调用。
  3. **确认发生在网络请求之前**：`ConfirmingAiSession` 负责这条（计划 2 Task 7 有测试守住「未确认时桩上 hits == 0」）。界面**不得**提供任何绕过 `ConsentGate` 的发送路径——**不许**有第二个「直接发送」按钮。
- **AI 输出永不自动落盘**（spec §7.5）：回复只在界面上流式显示。**必须**用户点「写入」才由界面调用 `PlanService` / `StudyDirs.materialsDir()` 落盘。落盘前显示**写入目标路径**。
- **流式**：`onDelta` 的增量**必须在 FX 线程**上追加（`Platform.runLater`）。**中途断流**：已收到的部分**保留**，界面标记为「**回复不完整**」（spec §7.6 的固定措辞，**不是**「未完成」）。
- **失败处理**（spec §7.6）：网络失败 / 超时 / 401 / 429 → **中性提示 + 重试按钮**，并且**不丢失用户已输入的内容**（输入框里的字一个字都不许清）。
  - `AiFailure` 的消息是中文、中性的（计划 2 Task 4 已要求）。界面显示它之前仍要过 `UiText.neutralize(...)` 兜底一次。
  - **不做任何准确率承诺**（spec §11 风险 5）。
- **AI 只出文本，人来拍板**（spec §1.2）：**不做** function calling / 工具调用 / 自动 agent 循环 / 本地模型推理。页面上**不许**有任何「自动应用建议」的按钮。
- **对话历史**存 `%APPDATA%\xiaoyiyuan-study\conversations\<id>.json`（`AppPaths.conversationsDir()`）：**不进入 `study\` 目录**。`id` 用 `UUID.randomUUID().toString()`（`ConversationStore` **不生成 id**）。点「导出到资料」→ `toMarkdown` → 存 `<studyDir>\资料\<标题>-<时间戳>.md`。
- **情绪分析入口的原文预览**：时间窗选 `近 7 天 / 近 30 天 / 自定义区间`（spec §6.4）→ `EmotionAnalysis.build(PromptRole.COUNSELOR, window, log)` → 显示「将发送 N 条记录」+ **完整原文预览** → 用户点「发送并分析」。
  - **区间外的记录绝不出现在 `promptText` 里**（计划 2 Task 9 有测试守住）。界面**不许**偷偷扩大窗口。
  - 分析结果**不写回情绪记录文件**；用户点「保存到资料」时才写 `<studyDir>\资料\心理-观察记录.md`（spec §6.4）。
- **主打的「现阶段规划」**（spec §7.3）：输入 = 本学期课程 + 可用时间 + 生活准则 +（可选）近期情绪概况 → 输出 = 课程优先级 / 每周节奏 / 风险科目 / **可舍弃项**。这些输入项**全部来自计划文件**（§一、§三、§五），界面**不另存一份**。
- **五种模式入口**（A 建档 / B 滚动 / C 资料查找 / D 决策咨询 / E 答疑讲解，spec §7.3）：UI 上就是**提示词选择 + 预填的输入模板**，**不是**五套不同的发送逻辑。**C 资料查找是半自动的**（spec §2 决策 8）：App 生成搜索关键词与提问模板（`PromptLibrary.searchPromptTemplate(topic)`，计划 2 Task 6），用户贴回搜索结果，AI 据此整理。**界面里不许有任何网络搜索调用。**
- **提示词用外部文件优先**（spec §7.2）：页面顶部显示当前正在用的是**外部文件**还是**内置默认**（`PromptLibrary.isUsingBuiltIn(PromptRole)`）；用内置时显示一句中性提示与「打开提示词目录」的入口（目录可能不存在——**不自动创建**，见 Task 9）。

- [ ] **Step 1: 写失败的测试 `AssistantControllerTest`**

```java
// preview_returnsTheFullPromptText_notTruncated
//   5000 字输入 → preview().fullText().length() == 完整长度
// notConfirmed_sendsNothing_andKeepsUserInput        ← 最重要的一条
//   gate = SwingConsentGate(req -> false)
//   assertThrows(...) 或 assertEquals(未发送, ...)
//   桩上 hits == 0；断言 userInput 变量仍逐字在手（控制器不提供「清空输入」的隐式行为）
// confirmed_sendsExactlyThePreviewedText
//   gate 记录收到的 promptText；桩记录实际请求体；两者逐字相等
// streamDelta_isForwardedInOrder_andCompleteFiresOnce
// streamTruncated_keepsPartial_andMarksIncomplete
//   assertTrue(controller.isTruncated()); assertEquals("收到的那部分", controller.receivedText());
//   assertFalse(controller.receivedText().contains("未完成"));   // 固定措辞是「回复不完整」
// aiOutput_isNotWrittenUntilExplicitWrite
//   发送成功、拿到回复后：断言 studyDir 下**没有任何新文件**；
//   调用 writeToMaterials(text) 之后才出现 资料/ 下的那个文件
// failure_keepsUserInput_andOffersRetry
// emotionsWindow_previewContainsEverySelectedEntry_andNothingOutside
// writeToPlan_showsTargetPathBeforeWriting
// noSecondSendPathThatBypassesTheGate
//   用反射断言 AssistantController 的公开方法里，只有 send(...) 一条会构造 AiRequest
//   （实现方式：把 AiClient 换成桩，调用每一个公开方法，只有 send 能让桩收到请求）
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=AssistantControllerTest`
  **注意**：本任务依赖计划 2 的 Task 4–9。若它们尚未交付，本步会红在「找不到 `AiClient`」——那是**预期的阻塞**，不是失败。此时**停下来**，在报告里写「被计划 2 Task 4–9 阻塞」，并在账本里追加一条 Ruling，由控制器决定顺序。
- [ ] **Step 4: 实现**
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=AssistantControllerTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 人工验收（AI 部分可离线验证的都要验）**
  - 把 `base_url` 填成一个**本机不存在的端口**（如 `http://127.0.0.1:9`），点发送 → 中性提示 + 重试按钮，**输入框里的字还在**。
  - 用**本地桩**（或填一个真实端点，由用户自行决定）验证流式逐字出现；中途拔网 → 标记「回复不完整」且已收到的部分保留。
  - **确认门**：在确认对话框上点「取消」→ 用 `netstat -ano | findstr <host>` 或桩的请求计数确认**没有任何请求发出**。
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/AssistantPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/AssistantController.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/SwingConsentGate.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/StreamingTextSink.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/AssistantControllerTest.java
git commit -F <utf8-消息文件>     # 主题：feat(ui): AI 对话页（逐次确认门、流式、输出须点写入才落盘）
```

---

### Task 9: 设置页（AI 连接 / 路径 / 显示偏好 / 危机渠道 / 迁移向导 / 提示词）

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/SettingsPage.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/SettingsController.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/MigrationWizardController.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/SettingsControllerTest.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/MigrationWizardControllerTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `SettingsController`
  - `SettingsController(AppConfig, PlanService, Path configFile)`
  - 读：`baseUrl()` / `apiKey()` / `model()` / `requestTimeout()` / `schoolCounseling()` / `crisisResources()` / `studyDir()` / `deferredVisibility()` / `moodOptions()` / `promptDir()`
  - 写：`void setBaseUrl(String)` / `setApiKey(String)` / `setModel(String)` / `setRequestTimeoutSec(long)` / `setSchoolCounseling(String)` / `setCrisisResources(List<CrisisResource>)` / `setStudyDir(Path)` / `setDeferredVisibility(DeferredVisibility)` / `setMoodOptions(List<String>)`
  - `SaveResult save() throws IOException` —— 唯一的写盘入口
  - `record SaveResult(boolean saved, String message)`；`message` **必须是中性措辞**
  - `String apiKeyPlaintextWarning()` —— **固定文案**：明确告诉用户 Key 是**明文**存储在本机（spec §7.1）。**不许**用「已加密」这类暗示。
  - `String promptStatus(PromptRole)` —— 「外部文件」/「内置默认」+ 实际路径
  - `void openPromptDirInExplorer()` —— 目录不存在时**不创建**，显示中性提示
- Produces `[本计划新建]`: `MigrationWizardController`
  - `MigrationWizardController(Migrator, Path studyDir)`
  - `record Preview(String targetText, String sourceText, List<String> notes)` —— **只展示**
  - `Preview preview() throws IOException` —— 调 `Migrator.plan(legacyFile)`；**不写任何东西**
  - `MigrationResult apply() throws IOException` —— 调 `Migrator.apply(plan, target)`；只有用户点「确认迁移」才会走到
  - `boolean shouldOffer()` —— `AppPaths.planFile` 不存在且 `AppPaths.legacyFile` 存在
- Consumes `[已交付]`: `AppConfig`（全部字段 + `save()`）、`AppPaths`（`planFile`/`legacyFile`/`backupDir`/`archiveDir`/`configFile`/`defaultStudyDir`/`conversationsDir`）、`Migrator(Path)`/`plan(Path)`/`apply(MigrationPlan,Path)`、`MigrationPlan(Path,String,String,List<String>)`、`MigrationResult(Path,Path,Path)`、`TargetExistsException`、`CrisisResource(String,String)`、`PromptRole`/`PromptLibrary`（**`[待计划 2]`**）
- Consumes `[本计划新建]`: `PlanService`、`StudyDirs`（Task 3）、`UiText`（Task 4）

**规则（全部必须实现）**
- **AI 连接三字段 + 超时**（spec §7.1）：`base_url` / `api_key` / `model` / 超时。`base_url` 输入框要有**实时规范化预览**（显示最终请求地址）。**规范化由 `ai` 包负责**（`AiClient.endpoint`，计划 2 Task 4）：界面**只调用它**，**不自己拼 URL**（否则会出现 `/v1/v1`，或两处规范化规则分叉）。
  - `setRequestTimeoutSec` 要接住 `AppConfig.setRequestTimeout` 的 `IllegalArgumentException`（非正数 / 含亚秒）并显示消息。
  - **「测试连接」按钮**（spec §7.1）：发最小请求。**它也必须过确认门**——它是网络请求。**未确认不得发出。**
  - **Key 明文告知**：固定文案，不许弱化。
- **study 目录与文件路径**（spec §8.1）：可改 `studyDir`；页面显示**由它派生**的四个绝对路径（计划文件 / 情绪记录 / 备份 / 归档）。
  - **改目录之后不自动搬迁任何数据**（spec 没说搬迁，不许发明）。改完只写配置。
  - **`studyDir` 指向不存在或不可写的目录时**：中性提示，**仍允许写进配置**（用户可能想先填后建目录），但要在设置页顶部显示一句「这个目录现在不可写」。
- **导入 / 导出**（spec §8.1 设置页的职责、spec §2 决策 17「单文件导入/导出」）：**本计划不实现导出格式的编排逻辑**，只做两件**已能确定**的事 —— ① 「导出计划文件副本到…」：用 `TextFileCodec.read` + `write` 把 `学习与生活计划.md` 复制到一个用户选的路径（**逐字节相同**）；② 「从备份恢复」：列出 `.backup\` 下该文件的备份（按时间倒序），用户选一份 → **先备份当前文件、再写**，并且**必须弹确认**。**禁止**在这里引入任何新格式（zip / json / 自定义包）。→ 归入「覆盖说明」
- **备份管理**（spec §8.1）：列出 `<studyDir>\.backup\` 的文件（名 / 大小 / 时间），提供「打开备份目录」。**不提供「一键全部删除」**（那是把用户唯一的退路做成一个误点）。
- **提示词状态 + 编辑入口**（spec §7.2）：显示两个角色的当前来源（外部文件 / 内置默认）与路径；提供「用系统默认程序打开提示词目录」；**目录/文件不存在时不创建、不报错**，显示中性提示「还没有外部提示词文件，现在用的是内置默认」。
  - **提示词目录不做成配置项**（裁定 A3）：固定为 `<studyDir>\提示词`。
- **显示偏好**（spec §5.1）：暂缓/已取消目标的显示方式（可见灰显 / 折叠 / 隐藏，默认折叠）、情绪选项列表（增删改）。
- **迁移向导**（spec §4.6，入口在设置页）：
  1. `preview()` → 并排展示**完整新旧文本**（左 = 旧档案原文，右 = 迁移后目标文本）+ `notes` 逐条列出 + 明确写一句「**此时尚未写入任何文件**」。
  2. 用户点「确认迁移」→ `apply()`。
  3. `apply()` 抛 `TargetExistsException` → 显示**中性**提示：「目标文件已经存在，迁移不会覆盖它。可以先把它改名或归档，或者改用导入。」（**决不静默覆盖**；`Migrator` 已经把这条检查排在第一步，且此时磁盘上什么都没多出来）。
  4. `apply()` 抛 `IOException`（源文件被改过 / 归档冲突）→ 逐字显示消息（**已核实这些消息里没有禁用词**），并提示重新预览。
  5. 成功后显示三个路径（新文件 / 备份 / 不可变归档），并明确说「原档案 `408-进度.md` 一个字节都没动，仍在原位置」。
  - **迁移是唯一允许在 `DocumentSession` 之外写文件的路径**（它走 `Migrator.apply`）。写完之后必须**重新 `open` 计划文件的 `DocumentSession`**，否则内存里的旧文档会与磁盘不一致。
- **`120` / `110` 的界面表现**（spec §12 的保底规则）：
  - 渠道表格里，`120` 与 `110` 两行的**删除按钮是禁用（disabled）状态**，鼠标悬停/点击给出中性说明：「急救与报警是保底渠道，号码不能被移除。可以改显示文案。」
  - **号码列本身也可以改**（spec 允许改文案；号码是身份，所以**改号码等于删掉旧号码**）。**裁定**：为了不出现「改着改着 120 就没了」，`120`/`110` 的**号码列设为只读**，**文案列可改**。理由与代价见裁定 A8。
  - **界面必须在提交前拦一道**：任何会移除 `120`/`110` 的编辑，都要在界面上给出可理解的说明，而不是把 `AppConfig.setCrisisResources` 的 `IllegalArgumentException` 直接甩给用户。**但 core 的校验仍然是唯一的安全网**——界面拦截是体验，不是保证。
  - 其余三条内置渠道（`12356`、`010-82951332`、`800-810-1117`）可自由**增删改**。
  - **`62715275` 不得写入**（spec §12：已核实失效）。**默认列表里没有它**——设置页也不提供「恢复默认」之外的任何预设。
  - 学校心理健康中心：一个自由文本字段，**留空时危机面板不显示该行**。

- [ ] **Step 1: 写失败的测试**

```java
// SettingsControllerTest
// save_isTheOnlyWritePath_andRoundTripsThroughAppConfig
//   setBaseUrl → save() → AppConfig.load(configFile).baseUrl() 逐字相等
// save_failure_isReported_notSwallowed
//   把 configFile 指向一个不可写的位置（一个已存在的目录同名）→ save() 返回 saved=false 且有消息
// crisis_removing120_isBlockedByTheInterface_andByTheCore
//   ① 界面层：requestRemoveCrisis("120") 返回 false 且给出说明文案（不含禁用词）
//   ② core 层：构造一个缺 120 的列表并断言 AppConfig.setCrisisResources 抛 IllegalArgumentException
// crisis_120And110_numbersAreReadOnly_labelsAreEditable
//   assertTrue(c.isNumberReadOnly("120")); assertFalse(c.isLabelReadOnly("120"));
// crisis_defaultIsTheFiveBuiltins_inOrder_andContainsNo62715275
// apiKeyWarning_saysPlaintext_andNeverSaysEncrypted
//   assertTrue(w.contains("明文")); assertFalse(w.contains("加密"));
// changingStudyDir_doesNotMoveOrCreateAnythingOnDisk
//   改目录后断言：旧目录文件数不变、新目录不存在
// promptStatus_reportsBuiltIn_whenDirectoryMissing_andDoesNotCreateIt
// testConnection_goesThroughTheConsentGate
//   桩上 hits == 0（未确认）→ 确认后才 1

// MigrationWizardControllerTest（全程 @TempDir，绝不碰真实 study 目录）
// preview_writesNothing
//   preview() 前后：目标文件不存在、.backup 不存在、.archive 不存在、源文件 SHA-256 不变
// apply_refusesWhenTargetExists_andNothingIsCreated
//   assertThrows(TargetExistsException.class, ...) 且 .backup 与 .archive 都不存在
// apply_leavesTheSourceFileByteIdentical
//   assertEquals(sha256Before, sha256After(source))
// apply_createsBackupAndImmutableArchive_andReportsThem
// apply_reopenSessionAfterMigration_seesTheNewTarget
//   —— 迁移后重新 open 计划文件，document() 非空、goals() 有 3 行
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=SettingsControllerTest,MigrationWizardControllerTest`
- [ ] **Step 4: 实现**
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=SettingsControllerTest,MigrationWizardControllerTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 人工验收（迁移必须在**脱敏样例副本**上验，不许碰真实档案）**
  - 把 `study-core/src/test/resources/fixtures/legacy-408-progress.md` 复制到一个临时目录，把 `studyDir` 指过去 → 设置页出现迁移入口 → 预览能看到完整新旧文本 → 取消 → 确认磁盘上什么都没变 → 再确认迁移 → 检查三个路径都出现、源文件 SHA-256 不变。
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/SettingsPage.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/SettingsController.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/MigrationWizardController.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/SettingsControllerTest.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/MigrationWizardControllerTest.java
git commit -F <utf8-消息文件>     # 主题：feat(ui): 设置页（AI/路径/偏好/危机渠道 120·110 保护/迁移向导）
```

---

### Task 10: 危机资源面板与危机命中呈现

**Files:**
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/CrisisPanel.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/CrisisController.java`
- Test: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/CrisisControllerTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `CrisisController`
  - `CrisisController(AppConfig)`
  - `record ResourceLine(String label, String number, boolean isSchool)` —— `label` 为空时**不显示该行**（`schoolCounseling` 留空即如此）
  - `List<ResourceLine> resources()`
  - `String headline()` —— 固定文案，方向：「这里有一些可以直接打电话的渠道，它们比我更管用。」
  - `boolean shouldShow(CrisisSignal signal)` —— `signal.triggered()`
  - **`void dismiss()`** —— 面板可以关掉，关掉之后**不影响任何东西**（不锁界面、不阻断 AI）
- Produces `[本计划新建]`: `final class CrisisPanel`
  - `CrisisPanel(CrisisController)` / `Node node()` / `void showFor(CrisisSignal)` / `void hide()`
- Consumes `[待计划 2]`: `CrisisDetector.builtIn()` / `CrisisDetector.load(Path)` / `CrisisSignal(boolean, List<String>)` / `signal.triggered()` / `signal.matchedKeywords()`（**以计划 2 终稿为准**）
- Consumes `[已交付]`: `AppConfig.crisisResources()` / `schoolCounseling()`、`CrisisResource(String,String)`
- Consumes `[本计划新建]`: `UiText`（Task 4）

**规则（全部必须实现，这是 spec §6.5 的界面落地）**
- **中性呈现**（spec §6.5）：
  - **不是红色警报**：面板底色是 `-fx-c-crisis-bg`（暖砂 `#F3EFE7`），左侧强调条 `-fx-c-crisis-bar`（`#7A6647`）。**没有任何红/橙**。
  - **不是弹窗打断**：面板是**页面内的一块区域**（或非模态的可关闭浮层），**不用** `Alert`、**不用**模态 `Stage`、**不抢焦点**、**不发声**。
  - **不追问细节、不质问、不评判**。面板里**不出现**用户的原文（不做「你刚才写了 X」这种回指）。
- **面板列出全部渠道**（spec §6.5）：`AppConfig.crisisResources()` 的五条内置（用户可增删改）+ 学校心理健康中心（**留空时不显示该行**）。号码渲染成**可直接看、可直接拨**的形式（就是一个大的可选中文本，不做 tel: 链接——桌面端没有意义，反而引入浏览器依赖）。
- **不阻断 AI（spec §6.5「关键决定」）**：
  - 命中时面板**出现**，但**界面不许锁定**：`CrisisController` **不得**提供任何「阻止继续」的 API（没有 `boolean mustAcknowledgeFirst()`、没有 `void blockUntilDismissed()`）。
  - **AI 分析照常提供**：命中之后，AI 发送按钮**仍然可用**，`AssistantController.send` 不检查 `CrisisSignal`。
  - 面板可以有「知道了」按钮，它只做 `hide()`；**没有勾选框、没有强制阅读计时**。
- **`matchedKeywords` 不在界面上显示**（计划 2 已裁定 `detect` 只给判断与命中词、不出文案）。命中词**只写日志**，用于日后调试。界面上出现命中词等于把「App 在扫描你写的话」摆到用户面前。
- **面板本身必须能从侧栏独立打开**（`Page.CRISIS`，`inSidebar()` 为 false 但导航可达）：用户想在没事的时候先看看有哪些渠道，必须能看。**不要求先命中。**
- 面板里**不得**出现任何诊断性词汇与评分（spec §6.5 措辞约束）。**不得**出现 `警告`（禁用词）。
- **误报的代价被刻意压低**（spec §6.5）：面板出现时**不写任何文件、不改任何状态、不产生通知**。

- [ ] **Step 1: 写失败的测试 `CrisisControllerTest`**

```java
// resources_areTheConfiguredOnes_inOrder
// schoolCounseling_blank_hidesThatLine
//   assertEquals(5, c.resources().size())；填上学咨询后 == 6
// headline_andEveryResourceLine_containNoForbiddenWordAndNoDiagnosticLabel
//   —— 对 headline() 与每一条 ResourceLine 的 label/number 逐个断言
// shouldShow_isExactlySignalTriggered
// controllerHasNoBlockingApi
//   反射断言 CrisisController 的公开方法里没有名字含 block|prevent|must|require|force 的（大小写不敏感）
// dismiss_onlyHides_theDetectorAndEverythingElseStillWorks
//   —— dismiss 之后：resources() 仍然可读、AI 发送仍然可用（用一个标志记录）
// matchedKeywords_areNeverRendered
//   —— 对 resources()/headline() 的全部字符串断言不含命中词（例如 "自杀"）
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=CrisisControllerTest`
  **注意**：本任务依赖计划 2 的 Task 3（`CrisisDetector`）。若未交付，本步红在「找不到 `CrisisDetector`」，是**预期的阻塞**。替代方案（必须写进报告）：先把 `CrisisController` 的 `shouldShow` 接成一个 `Predicate<Boolean>` 形态，待 `CrisisDetector` 到位后再改签名——**但只有在控制器同意后才这么做**，自行改接口会在计划 2 交付后分叉。
- [ ] **Step 4: 实现**
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=CrisisControllerTest`
- [ ] **Step 6: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 7: 人工验收** → `MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run`
  Expected: 侧栏（或设置页里的入口）能打开求助资源页，看到 5 条渠道 + 学校心理中心（若已填）；**整页没有一处红色**；把「我知道了」点掉后，AI 页面的发送按钮仍然可用。
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/main/java/com/xiaoyiyuan/study/ui/view/CrisisPanel.java \
        study-desktop/src/main/java/com/xiaoyiyuan/study/ui/control/CrisisController.java \
        study-desktop/src/test/java/com/xiaoyiyuan/study/ui/control/CrisisControllerTest.java
git commit -F <utf8-消息文件>     # 主题：feat(ui): 中性危机资源面板（不阻断、不锁定、不追问）
```

---

### Task 11: 界面禁用词与合规测试（`DisabledWordTest`）

**Files:**
- Create: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/text/DisabledWordTest.java`
- Create: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/text/VisibleTextScanner.java`
- Create: `study-desktop/src/test/resources/fixtures/ui-text-samples/`（**故意合规**的样例文案）
- Create: `study-desktop/src/test/resources/fixtures/ui-violations/`（**故意违规**的样例：至少 8 个文件，每个含一个禁用词或一个诊断词）
- Create: `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/text/VisibleTextScannerSelfTest.java`

**Interfaces:**
- Produces `[本计划新建]`: `final class VisibleTextScanner`
  - `VisibleTextScanner(Path root)` —— **只收一个根目录**，不读工作目录之外的任何东西
  - `List<Hit> scan()` —— `record Hit(Path file, int line, String word)`；`file` 是**相对 root 的路径**，保证失败消息在不同机器上一样
  - `List<Hit> scanClasses(List<Class<?>> classes, List<String> words)` —— 扫**已加载类**的常量池里出现的词（用于 `study-core` 的异常消息）
  - 只扫扩展名在 `.java/.css/.properties/.fxml/.md/.txt/.json` 里的文件；**不扫** `target/`、`build/`、`.git/`
- Produces `[本计划新建]`: `DisabledWordTest`（`@Test` 方法清单见 Step 1）

**扫描范围（明确裁定，见裁定 A6）**

| # | 扫什么 | 用什么词表 | 为什么 |
|---|---|---|---|
| 1 | `study-desktop/src/main`（全部源码与资源，含 `.css` `.properties` `.fxml` `.md` `.json`） | 8 个禁用词 + 3 个诊断词 | 界面文案与样式的唯一来源 |
| 2 | `study-core/src/main/resources`（内置提示词、`crisis-keywords.txt`） | 8 个禁用词 + 3 个诊断词 | 危机关键词表必然含 `自杀` 之类，**它不含 8 个禁用词也不含 3 个诊断词**；内置提示词是「会显示给用户看的内容」 |
| 3 | `study-core` 的**用户可见异常类**与配置类：`SectionMissingException` / `NotUtf8Exception` / `TargetExistsException` / `GoalStatus` / `PlanDocument` / `EmotionLog` / `AppConfig`，扫**已加载类的字符串常量池** | 8 个禁用词 + 3 个诊断词 | 这些消息**会原样出现在界面上**，而它们属于 core、扫不到就等于放行（实测 `SectionMissingException` 与 `AppConfig.setRequestTimeout` 的消息含「失败」） |
| 4 | **不扫** `study-core/src/main/java` 的其余源码 | —— | core 的注释与内部日志里出现「失败」是**允许且必要的**（它们描述程序自身的失败，不是对用户的评判）；把它们一起扫会逼出一堆无意义的改写 |
| 5 | **不扫** `study-desktop/src/test` 与 `study-core/src/test` | —— | 测试里的违规样例与禁用词表**必须**含这些词，否则测试无法自证有效 |

**白名单机制（不许自证清白）——裁定 A6 的落地**

- **不设任何按文件/按词的白名单。** 白名单的通常形态是「这个词只出现在测试自己的禁用词表里」，而那正好是自证清白：任何一个真违规只要被加进白名单，测试永远绿。
- **代替白名单的是三条反向自证断言**，它们让「扫描器与词表本身」也进入被验证的范围：
  1. `selfTest_catchesEveryViolation`：对 `fixtures/ui-violations/` 下的 8+ 个样例文件扫一遍，**断言每一个被扫到的词都正好是期望的那一个**（文件到词的映射显式写死在测试里）。任何一条漏报或误报都红。
  2. `selfTest_reportsZeroOnCompliantSamples`：对 `fixtures/ui-text-samples/`（故意合规）扫一遍，断言**0 命中**。这一条挡住「扫描器把所有东西都报一遍」的假绿。
  3. `wordList_isExactlyTheSpecList`：`assertEquals(List.of("未完成","逾期","落后","断签","失败","警告","已放弃","未坚持"), UiText.forbiddenWords())` 与三个诊断词的同类断言。**词表是硬编码在测试里的字面量**，必须与 spec §8.2、§6.5 **逐字**一致；**不许**从被扫描的文件里推导词表（那就是自证清白）。
- **测试自己的禁用词表放在哪里**：放在 `study-desktop/src/test/java/com/xiaoyiyuan/study/ui/text/DisabledWordTest.java` 与 `UiText`（main 侧，见下）里。后者**会被第 1 条扫描规则扫到**——因此 `UiText.forbiddenWords()` 的返回**不能**是字面量，必须用**编码点拼接**构造：例如 `"未" + "完成"`（这一类拼接在 main 侧源码里看不到完整词），并且**另加一条断言**禁止更狡猾的规避手法。
- **反规避断言**（`noEvasionTricks`）：扫 `study-desktop/src/main` 时，除了词表匹配，还要断言源码里**不出现** `\u` 形式的转义（正则 `\\u[0-9a-fA-F]{4}`）与零宽字符（`\u200B` `\u200C` `\u200D` `\uFEFF`）——这两者是唯一能让一个禁用词「肉眼可见但扫描不到」的写法。`\u` 的禁令与项目既有纪律一致（Java 源码里 `\uXXXX` 出现在注释/javadoc 里本身就是编译错误，计划 1 已踩过）。
- **失败消息必须可定位**：`Hit(file, line, word)` 渲染成 `study-desktop/src/main/java/.../XxxPage.java:42 出现禁用词「失败」`。**不许**只报「发现 N 处违规」。

- [ ] **Step 1: 写 `DisabledWordTest` 与 `VisibleTextScannerSelfTest`**

```java
// DisabledWordTest
// desktopMain_containsNoForbiddenWordOrDiagnosticLabel
//   new VisibleTextScanner(Path.of("src/main")).scan() 为空
//   （测试的工作目录是 study-desktop —— 用 Path.of("src/main") 相对的就是本模块；
//     失败消息里必须带相对路径，见接口）
// coreResources_containNoForbiddenWordOrDiagnosticLabel
//   new VisibleTextScanner(Path.of("../study-core/src/main/resources")).scan() 为空
// coreUserVisibleExceptionMessages_containNoForbiddenWordOrDiagnosticLabel
//   VisibleTextScanner().scanClasses(List.of(SectionMissingException.class, NotUtf8Exception.class,
//       TargetExistsException.class, GoalStatus.class, PlanDocument.class, EmotionLog.class,
//       AppConfig.class), words)
//   为空；类列表必须显式写死并 assertTrue(classes.size() >= 7)（空列表会让这条形同虚设）
//   —— AppConfig.class 必须在列表里：它的超时消息会被设置页原样显示给用户
// wordList_isExactlyTheSpecList            （见上）
// noEvasionTricks                          （见上）
// everyHitIsReportedWithFileAndLine        （在违规样例上断言 Hit 的渲染形态）
// scan_reportsZeroOnTheCompliantFixtureDirectory      ①
// scan_catchesEveryViolationInTheFixtureDirectory     ②
//   Map<String,String> expected = Map.of("violation-01.java","未完成", ... "violation-11.md","中度");
//   对每个文件的命中词集合做 assertEquals
```

- [ ] **Step 2: 先跑现有全量，确认基线是绿的** → `MVN -pl study-core clean test`
- [ ] **Step 3: 跑新测试，确认红** → `MVN -pl study-desktop clean test -Dtest=DisabledWordTest,VisibleTextScannerSelfTest`
- [ ] **Step 4: 实现扫描器与样例，并**逐条清掉真实命中****
  - 真实命中**必须逐条处理**：改文案、或（当它确实是 core 异常消息时）由 Task 4 的 `UiText.neutralize(...)` 在**显示前**中立化，并把 core 原文写日志。**裁定 A6 允许中立化作为界面侧的兜底，但同时要求**：`coreUserVisibleExceptionMessages_...` 那条测试**一旦命中就红**，逼实现者把命中位置写进报告、由控制器决定是改 core 消息（要动计划 1/2 的冻结面）还是维持中立化。
  - 已知的真实命中（开工前实测，**不是猜测**）：
    | 位置 | 词 | 是否会被 `DisabledWordTest` 扫到 |
    |---|---|---|
    | `SectionMissingException` 的消息（`"章节「X」定位失败："`） | `失败` | **会**（第 3 条规则），且**会显示在界面上** |
    | `PlanDocument` 的 `SectionMissingException` 调用点消息 | `失败` | **会**（`PlanDocument.class` 在类列表里） |
    | `AppConfig.setRequestTimeout` 的消息（`"零或负超时会让每次请求直接失败…"`） | `失败` | **会**（只要把 `AppConfig.class` 放进类列表）。**裁定：放进去**——这条消息会被 Task 9 的 `setRequestTimeoutSec` 的 `catch (IllegalArgumentException)` 显示给用户。因此 `coreUserVisibleExceptionMessages_...` 的类列表**必须含 `AppConfig.class`**，而它会命中；处理方式 = 由 `UiText.neutralize` 在显示前中立化（A6），并把「core 消息该改」记进「下一份计划」第 2 条 |
    | `GoalStatus` 的 javadoc / `GoalStateTest` | `失败`/`落后`/`已放弃` | javadoc 不在常量池里，**扫不到**；`GoalStateTest` 在 test 目录，**不扫** |
- [ ] **Step 5: 跑新测试，确认绿** → `MVN -pl study-desktop clean test -Dtest=DisabledWordTest,VisibleTextScannerSelfTest`
- [ ] **Step 6: 变异验证（这一步不许省）**

把 `UiText` 里任意一个常量临时改成含 `失败`，跑 `DisabledWordTest`：**必须红**，且失败消息里出现文件名与行号。把改动还原。**在报告里贴出这次变异的命令与输出。** 「跑绿了」不算覆盖。

- [ ] **Step 7: 全量回归 + 桌面模块编译** → `MVN -pl study-core clean test` + `MVN -q -DskipTests package`
- [ ] **Step 8: Commit**

```bash
git add study-desktop/src/test/java/com/xiaoyiyuan/study/ui/text \
        study-desktop/src/test/resources/fixtures/ui-violations \
        study-desktop/src/test/resources/fixtures/ui-text-samples
git commit -F <utf8-消息文件>     # 主题：test(ui): 禁用词与诊断性标签扫描（含反向自证与反规避断言）
```

---

### Task 12: 打包与运行（`jpackage`）

**Files:**
- Create: `study-desktop/packaging/build-app-image.ps1`
- Create: `study-desktop/packaging/app-icon.ico`（**先占位后替换**：见 Step 5）
- Create: `docs/superpowers/plans/2026-10-11-plan-3-验收清单.md`
- Test: 无单元测试（打包是手工验收任务；验证方式是「产物真的能跑」）

**Interfaces:**
- Produces `[本计划新建]`: `study-desktop/packaging/build-app-image.ps1`，参数 `-JdkHome`（默认 `C:\Program Files\Java\jdk-25.0.3`）、`-OutDir`（默认 `study-desktop\target\dist`）
- Consumes `[已交付]`: `study-desktop/pom.xml` 的 `javafx-maven-plugin` 配置（`mainClass = com.xiaoyiyuan.study.ui.Launcher`、`--enable-native-access=javafx.graphics`）、父 POM 的 `javafx.version = 25.0.4`

**规则（全部必须实现；每一条都有「计划 1 实测」的代价）**
- **`--main-class` 用 `Launcher`，不是 `App`**（计划 1 实测：classpath 方式下 JavaFX 拒绝启动继承 `Application` 的 main 类）。
- **`--win-console` 只在调试构建里加**（它让产物带一个控制台窗口）。**正式产物不加**，但脚本要提供 `-DebugConsole` 开关，因为「看得到应用自身输出」是计划 1 反复需要的取证手段。
- **`--java-options --enable-native-access=javafx.graphics` 必须加**：`javafx:run` 的配置里有它（计划 1 靠它消除 JDK 25 的原生访问 WARNING），**jpackage 生成的启动器不会自动带上它**。漏了它，用户第一次运行会在控制台看到 WARNING（`-DebugConsole` 时可见；不带控制台时写进 stderr）。
- **JavaFX 必须走 `--module-path` + `--add-modules javafx.controls`**：JavaFX 不是 JDK 模块，jpackage **不会**从 classpath 上的 jar 自动补 `--module-path`/`--add-modules`。漏了它会得到「运行时缺 `javafx.controls`」。
- **`jlink` 精简要包含 `java.prefs`**：JavaFX 依赖它（`MASTER.md` 体系里我们不用 Preferences，但 JavaFX 图形栈会碰它）。`jlink` 会按 `requires` 自动传递依赖，脚本仍要显式列出并在产物上核对模块数。
- **产物体积提前告知**（spec §10）：约 **150–250MB**（含完整运行时）。这是 JavaFX 桌面应用的正常体积，**不是异常**，验收文档里要写清楚。
- **只做 `app-image`（免安装目录），`.msi` 不做**（spec §10：第二步可选，需额外安装 WiX Toolset，**用户确认后再做**）。**不要**在没有用户明确同意的情况下尝试 `.msi`。
- **脚本必须能重复运行**：`OutDir` 先清空再生成（只清自己那一层，**绝不 `Remove-Item -Recurse` 到一个变量拼出来的路径**——先 `Resolve-Path` 并断言它在 `study-desktop\target` 之下）。
- **产物不许包含用户数据**（spec §13：源码在 `E:\`，数据在 `D:\`）：`input` 目录只放我们要放的 jar，`.backup` / `.archive` / `生活与情绪记录.md` / `conversations` **一个都不许进去**。脚本里加一条前置断言：构建后检查产物目录里没有 `*.md`（除 license）、没有 `*.bak`、没有 `*.json`。

- [ ] **Step 1: 写打包脚本 `build-app-image.ps1`**

脚本按下面的顺序做（**顺序即依赖**，每一步都先断言前置产物存在）：
1. 前置：`MVN -q -DskipTests install`（**必须 `install`**，因为下一步要解析依赖）
2. 构建同一程序集：`MVN -pl study-desktop -am -q -DskipTests package`
3. 用 `maven-dependency-plugin:copy-dependencies` 把 `study-desktop` 的运行期依赖拷到 `target/app/lib/app`，用 `dependency:copy` 把 `study-desktop-1.0.0-SNAPSHOT.jar` 拷到 `target/app/lib/study-desktop.jar`（**去掉版本号**：这样 `--main-jar` 不用跟着版本改；`Class-Path` 由 jpackage 从 `input` 目录里的 jar 自动生成，JavaFX jar 也在同一目录，双保险）
4. `jdeps --print-module-deps --ignore-missing-deps target/app/lib/study-desktop.jar` 得到模块集，**并上** `java.prefs`；把结果记进日志（让后人看得见 jlink 到底带了什么）
5. `jlink` 生成 `target/runtime`（`--strip-debug --no-header-files --no-man-pages --compress=zip-6`）
6. `jpackage`：

```powershell
$jp = Join-Path $JdkHome 'bin\jpackage.exe'
& $jp --type app-image `
  --name '学习助手' `
  --app-version '1.0.0' `
  --vendor 'xiaoyiyuan' `
  --description '学习与生活计划桌面端' `
  --input 'target/app/lib' `
  --main-jar 'study-desktop.jar' `
  --main-class 'com.xiaoyiyuan.study.ui.Launcher' `
  --module-path 'target/app/lib' `
  --add-modules 'javafx.controls' `
  --runtime-image 'target/runtime' `
  --java-options '--enable-native-access=javafx.graphics' `
  --icon 'packaging/app-icon.ico' `
  --dest 'target/dist'
if ($DebugConsole) { 追加 '--win-console' }
```

7. 校验产物（**产物能跑才算成功**）：
```powershell
$exe = 'target/dist/学习助手/学习助手.exe'
if (-not (Test-Path $exe)) { throw "产物不存在：$exe" }
$size = (Get-ChildItem 'target/dist/学习助手' -Recurse | Measure-Object Length -Sum).Sum
Write-Output ("app-image 体积：{0:N1} MB" -f ($size / 1MB))
```

**退路（写进脚本注释，实现者按需启用）**：若 `--module-path` 路径出现任何解析问题（JavaFX jar 在 `lib/app` 里与 `study-desktop.jar` 同目录，理论上没问题），退到**最保守的形态**——去掉 `--runtime-image`（让 jpackage 自己 jlink 一份完整运行时）、去掉 `--module-path`/`--add-modules`，只保留 `--input` + `--main-jar` + `--main-class`。代价是体积变大；**先保证能跑，再谈体积**。

- [ ] **Step 2: 跑一次，生成产物**

Run: `pwsh -NoProfile -File study-desktop\packaging\build-app-image.ps1`
Expected: `target/dist/学习助手/` 出现，`学习助手.exe` 在根，`app/` 下有 jar，`runtime/` 下有精简运行时。记录体积与 `runtime` 的模块数。

- [ ] **Step 3: 双击运行产物，确认它真的能跑**

Run: 直接启动 `target/dist/学习助手/学习助手.exe`（**不要**用 `javafx:run` 代替——那是另一条命令行）
Expected: 窗口出现、中文正常、8 类页面都能打开。**若是 `-DebugConsole` 构建**，额外确认控制台里**没有** JDK 25 原生访问 WARNING。

- [ ] **Step 4: 在 200% DPI 的 Windows 上做验收**

把**当前显示器**确认在 200% 缩放（`设置 > 系统 > 显示 > 缩放`；本机即为 200%），逐条走下面的清单，**每条都要人工看到**。结果写进 `docs/superpowers/plans/2026-10-11-plan-3-验收清单.md`（勾选 + 观察值 + 若失败的现象）。

| # | 验收项 | 观察值 |
|---|---|---|
| 1 | 8 类页面都能打开 | 总览 / 目标 / 课程 / 周检查 / 周记录 / 情绪 / 情绪趋势 / 助手，逐个点开都有内容且**不空白、不报错** |
| 2 | 设置页与求助资源页可达 | 侧栏能到；危机面板不要求先命中 |
| 3 | 主题可切 | 明 ↔ 暗切换后**文字仍然清晰**，**没有整块变黑/变白**，状态 chip 的色都换了 |
| 4 | 200% 缩放下无截断、无重叠、无横向滚动 | 逐个窗口从 900×600 拖到 1600×1000；表格列不叠字、按钮不被切掉、状态栏不被挤出窗口 |
| 5 | AI 确认门生效 | 点发送 → 先出现**完整原文预览**（可滚动到最底）→ 点「取消」→ **没有任何请求**（用 `netstat` 或桩计数确认） |
| 6 | AI 输出须点「写入」才落盘 | 收到回复后，`studyDir` 下**没有新文件**；点「写入」后才出现，且路径在点之前已显示 |
| 7 | 危机面板可见且中性 | 在情绪备注里写一句会被命中的话 → 面板出现；**整屏没有红色**；面板出现后**AI 发送按钮仍可点** |
| 8 | 不出现禁用词 | 逐页肉眼扫一遍；并跑 `MVN -pl study-desktop clean test -Dtest=DisabledWordTest` 确认为绿 |
| 9 | 键盘可完成主要操作 | 只用键盘：切页面、编辑表格单元格、发送消息、开关主题；**焦点环在所有可交互控件上可见**且未被底栏遮挡 |
| 10 | 减少动效开关生效 | 打开后悬停/展开没有过渡动画 |
| 11 | 中文无方块 | 8 页 + 危机面板 + 设置页，**没有任何 tofu 方块** |
| 12 | 真实档案未被触碰 | `Get-FileHash '%USERPROFILE%\study\408-进度.md' -Algorithm SHA256` == `9922A25BA58...`（与开工前一致），且 `生活与情绪记录.md` 只在用户主动点「开始记录」后才存在 |

- [ ] **Step 5: 图标**

`design-system/study-assistant/` 下**目前没有 `.ico`**。处理方式（二选一，写进报告）：
- ① 先用一个**临时生成的纯色 `.ico`**（用系统自带工具或 `System.Drawing` 生成 256/48/32/16 四档），保证 `jpackage --icon` 有输入；在验收清单里记一条「图标为临时占位，待正式设计」。
- ② 若本步无法产出合法 `.ico`，**去掉 `--icon`**（jpackage 用默认图标），并在验收清单里记明。**不要**为了这个卡住整个打包。

- [ ] **Step 6: 写验收清单文档** `docs/superpowers/plans/2026-10-11-plan-3-验收清单.md`

内容 = Step 4 的表格 + 实际观察值 + 产物路径与体积 + 复现命令。**不要**把它写成一份「全部通过」的空话；任何未通过的项要写「未通过 + 现象 + 已知原因」。

- [ ] **Step 7: Commit**

```bash
git add study-desktop/packaging docs/superpowers/plans/2026-10-11-plan-3-验收清单.md
git commit -F <utf8-消息文件>     # 主题：build(desktop): jpackage 免安装产物与 200% 缩放验收清单
```

---

## 覆盖说明（spec 中本计划仍**不含**的条目）

明确列出，避免误以为被静默丢弃：

| spec 条目 | 为什么本计划不做 | 归谁 |
|---|---|---|
| §10 第二步 `.msi` 安装包 | spec 明确「需额外安装 WiX Toolset，**用户确认后再做**」 | **等用户明确要求**；不在任何计划的默认范围内 |
| §2 决策 17 的**导入/导出格式**编排 | spec 只说「单文件导入/导出」，没有定义格式。本计划只做「导出计划文件副本（逐字节相同）」与「从备份恢复」两件已能确定的事 | 计划 4（若需要）；**本计划不发明新格式** |
| §6.2 的「情绪选项可增删」的**批量编辑界面** | 数据层在 `AppConfig`（计划 2 已交付），本计划只做逐条增删 | 若嫌麻烦，用户可直接手改 `config.properties` |
| §5.1「全字段可编辑」中的 **§一 现状项的增删行** | `PlanDocument` **只提供** `setProfileItem(index, value)`（只改「内容」列，没有增行/删行/改键名的口子）。界面能做的是改**内容**，课程清单的增删只能在那一行的文本里做 | **计划 4** 需给 `PlanDocument` 补 `addProfileItem` / `removeProfileItem`（见缺口 #2） |
| 情绪数据的加密 | spec §1.2 明确「做不了假加密，不做」 | 不做 |
| 用 AI 判断是否触发危机资源 / 否定语境排除 | spec §1.2、§6.5 明确不做 | 不做 |
| 真题 PDF 阅读器 | spec §1.2 明确不做（已有 PDF 阅读器） | 不做 |
| 云同步 / 多设备同步 | spec §1.2 明确不做 | 不做 |
| 每日打卡 / 提醒 / 通知 / 截止推送 | spec §1.2、§5.3 明确不做 | 不做 |
| AI function calling / 工具调用 / agent 循环 / 本地推理 | spec §7.7 明确不做 | 不做 |
| spec §9 的 1–12 号测试 | 全部在 `study-core`，计划 1/2 已交付（#13 `DisabledWordTest` 除外） | 已完成 |

## 计划 3 验收标准

全部满足才算完成。**每一条都要有可贴出来的命令与观察值**，不许写「已人工确认」：

- [ ] `MVN -pl study-core clean test` 全绿：`Failures: 0, Errors: 0`，且总数**不低于**开工时的基线（计划 2 Task 2 结束时为 218；以开工时实测为准）
- [ ] `MVN -pl study-desktop clean test` 全绿，且至少新增：`ThemeTokensTest`、`NavigationTest`、`StartupCheckTest`、`DocumentSessionTest`、`WeeklySnapshotTest`、`WeekLogControllerTest`、`UiTextTest`、`GoalsControllerTest`、`CourseRosterTest`、`CoursesControllerTest`、`EmotionControllerTest`、`EmotionTrendControllerTest`、`AssistantControllerTest`、`SettingsControllerTest`、`MigrationWizardControllerTest`、`CrisisControllerTest`、`DisabledWordTest`、`VisibleTextScannerSelfTest`
- [ ] `MVN -q -DskipTests package` BUILD SUCCESS（**这一条是「界面代码真的能编译」的证明**；`mvn test` 不编译 `study-desktop/src/main`）
- [ ] `MVN -pl study-desktop clean test -Dtest=DisabledWordTest` 全绿；并且**变异验证**做过：往 `UiText` 的一个常量里塞进「失败」，该测试**变红**且失败消息带文件名与行号（把命令与输出贴进报告）
- [ ] `MVN -pl study-core clean test -Dtest=RealFileRoundTripTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"` 仍是 **13377 字节进、13377 字节出、identical=true**
- [ ] `%USERPROFILE%\study\408-进度.md` 的 SHA-256 == `9922A25BA8CFB277C2272F73FBA062D82E744F926C7EF8C4D78A0D3EBAA54FF8`（前后一致；计划 1/2 的既有基线）
- [ ] `%USERPROFILE%\study\学习与生活计划.md` 与 `生活与情绪记录.md`：**要么不存在、要么逐字节等于本计划开工时的状态**（本计划不主动写它们；只有人工验收时用户点了保存/开始记录才允许变化，且变化必须记明）
- [ ] `MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run` → 窗口出现、中文无方块、9 个侧栏项可点（**日志里的 banner 不算证据**，见 Global Constraints 第 7 条）
- [ ] `pwsh -NoProfile -File study-desktop\packaging\build-app-image.ps1` 成功，`study-desktop\target\dist\学习助手\学习助手.exe` 存在，且**直接双击它能打开窗口**
- [ ] 产物体积落在 **150–250MB** 区间（`Get-ChildItem -Recurse | Measure-Object Length -Sum`），并记录实际数值
- [ ] 产物里**没有**任何用户数据：`Get-ChildItem target/dist/学习助手 -Recurse -Include *.bak,*.archive,生活与情绪记录.md,*.json` 为空
- [ ] `docs/superpowers/plans/2026-10-11-plan-3-验收清单.md` 存在，12 项逐条有观察值；未通过项写明现象
- [ ] `git status --porcelain` 只列出本计划**明确创建/修改**的路径；**未提交**任何真实档案、真实情绪记录、`target/`、`.superpowers/`
- [ ] 本计划的每一个 `[待计划 2]` 依赖，在开工时它的计划 2 任务**已提交**；若有任一未提交，报告中必须写明被阻塞的任务编号

## 下一份计划

**计划 4 —— 界面收口与计划域补口**（只在下列**确实需要**时才做；每一条都来自本计划实测出的边界，不是设想）：

1. **`PlanDocument` 补「§一 现状项」的增 / 删 / 改键名**：`addProfileItem(ProfileItem)` / `removeProfileItem(int)` / `moveProfileItem(int,int)`。当前只有 `setProfileItem(int,String)`，所以 §一 的行键不可改、行不可增删（缺口 #2）。做的时候要连同 `PlanDocumentTest` 与 P2 断言一起加。
2. **`study-core` 里会显示给用户的异常消息去掉禁用词**：`SectionMissingException`（含 `"定位失败："`）、`PlanDocument` 的若干 `SectionMissingException` 消息、`AppConfig.setRequestTimeout` 的消息。这不是界面层能干净解决的事——界面侧的 `UiText.neutralize` 只是兜底（裁定 A6）。做的时候要评估对计划 1/2 已冻结测试的影响（`SectionMissingException` 的消息形态可能已被断言）。
3. **导出/导入格式**：只在用户明确要「换机带走」时才定义。
4. **`.msi` 安装包**：只在用户明确要 Windows 安装程序时才做（需 WiX Toolset）。
5. **正式图标**：本轮是临时占位。
