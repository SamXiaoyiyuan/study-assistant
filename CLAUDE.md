# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> 本文件描述的是**这个仓库当前的真实形态**。项目文档、账本、裁定全部是中文，请用中文继续。
> 这是一个**规格驱动开发（SDD）**项目：代码只是产物之一，**过程资产（spec / plans / briefs / 账本）与代码同等重要**。

## 一、这个项目是什么

Windows 桌面应用（Java 25 + JavaFX 25），把原先跑在对话里的「私人学习助手」产品化：本地进度管家为主 +
完整 AI 助手。用户是学生本人，**主语是「现阶段的学习与生活」，考研是「暂缓」的可选长线目标**（不得劝进或劝退）。

核心设计约束（决定了大半架构）：**App 与 AI 助手共用同一份 Markdown 数据**——直接读写
`学习与生活计划.md`，不产生第二套并行数据；这个 Markdown 由**三方共同编辑**（本应用、AI、用户自己的编辑器），
所以整条存储链是围绕「不可能静默毁掉用户数据」设计的。

## 二、常用命令

**`mvn` 不在 PATH 上。** 必须用 IDEA 自带的那一份，下文记作 `MVN`：

```bash
MVN='/d/Program Files/IntelliJ IDEA 2026.2.2/plugins/maven-plugin/lib/maven3/bin/mvn.cmd'
cd "E:/Project/Java/Study"

# 全量回归（唯一可信的「绿」。**下结论前必须带 clean**——Maven 只在源码变更时重编译，
# 否则会读到陈旧 class 并得出错误结论，本项目已经踩过）
"$MVN" -B clean test                      # core 480 / desktop 118 / 0 fail / 0 error / 4 skipped

# 单模块 / 单测试类 / 单方法
"$MVN" -B -pl study-core clean test
"$MVN" -B -pl study-core clean test -Dtest=AppConfigTest
"$MVN" -B -pl study-core clean test -Dtest=AppConfigTest#crisis_cannotDrop120Or110

# 触碰 study-desktop 时必须额外跑一次（界面代码的编译错误 test 阶段发现不了）
"$MVN" -B -q -DskipTests package          # 不要用 -Dmaven.test.skip=true（连测试编译也跳过）

# 跑窗口（本机验证用；javafx:run 的前缀目标不能与 -am 同用）
"$MVN" -q -DskipTests install             # 干净仓库上先装 core
"$MVN" -pl study-desktop javafx:run
```

**opt-in 的只读验收测试**（默认为 skipped，**它们会碰用户的真实档案，只读**）：
`study.realFile`、`study.previewOut`、`study.previewDropLine`、`study.previewAllowedAbsentFile`。
只给一半属性也必须 0 error（各用例先用 `assumeTrue` 钉住自己依赖的属性）：

```bash
"$MVN" -B -pl study-core clean test \
  -Dtest=RealFileRoundTripTest -Dstudy.realFile='%USERPROFILE%\study\408-进度.md'
```

## 三、架构

```
study-assistant (pom, 父 POM：唯一的版本来源 javafx.version / junit.version)
├── study-core     纯 Java。唯一运行时第三方依赖 = Gson 2.14.0（唯一被批准的引入）
└── study-desktop  JavaFX 25.0.4。依赖 study-core；core **永远不知道界面的存在**
```

依赖方向是**单向且承重的**：不是代码风格，而是安全性质的实现手段——
**`ai` 包不得依赖 `storage` 包**（`jdeps` 验收：`ai` 出边 0 条），所以 AI 客户端**在编译期就拿不到写文件的能力**。
「AI 不改文件」不靠提示词叮嘱，靠依赖方向。

**study-core 包职责**

| 包 | 职责 |
|---|---|
| `markdown` | 块模型（`RawBlock` 原样块 / `TableBlock` 结构表）+ 无损解析与渲染。**整个存储保真的地基** |
| `plan` | 主计划域：目标/课程/底线/阶段/周界/周记录（`PlanDocument` 是对 §一–§六 的唯一读写入口） |
| `storage` | `FileStore`（显式保存 + 备份 + SHA-256 基线）、`TextFileCodec`（严格 UTF-8 编解码）、`BackupRotation` |
| `wellbeing` | 情绪记录（`EmotionLog`）、危机兜底（`CrisisDetector` 本地关键词匹配）、AI 情绪分析（`EmotionAnalysis`） |
| `ai` | OpenAI 兼容客户端（非流式 + SSE 流式）、失败分类、**逐次确认门**（`ConsentGate` / `ConfirmingAiSession`） |
| `conversation` | 对话历史（Gson，落在 `%APPDATA%\xiaoyiyuan-study\conversations\`） |
| `prompt` | 提示词「外部文件 + 内置回退」（因不得依赖 storage 而落在独立包） |
| `migrate` | 一次性迁移 `408-进度.md` → `学习与生活计划.md`（备份 → 映射 → diff 确认 → 才写） |
| `config` | `AppConfig`（键=值文本，坏了也回退默认值）、`AppPaths`（路径派生） |

**study-desktop 包职责**：`ui`（`AppShell` 外壳 / `Navigation` / `StatusBar` / `StartupCheck`）、
`ui/theme`（令牌 + 明暗主题 + 字体族解析）、`ui/view`（页面）、`ui/text`（`UiText`：**全部界面文案的唯一出处**）、
`ui/control`（`StatusModel`）；计划 3 还会引入 `ui/service`（`DocumentSession` 等）。

**页面**：spec §8.1 是 8 页，实现拆成 `Page` 枚举的 10 项（多了「情绪趋势」与「求助资源」覆盖层——
`CRISIS` 的 `inSidebar=false`，它从设置页与危机命中处可达，不出现在侧栏）。侧栏顺序 = 枚举声明顺序，
**只有一个定义处**。

**数据文件**：主计划 `<studyDir>\学习与生活计划.md`（§一 现状 / §二 目标 / §三 课程 / §四 每周底线 /
§五 生活准则 / §六 阶段排期 / §七 本周 / §八 考研(暂缓) / §九 周记录 / §十 待确认）；情绪记录是**独立文件**
`生活与情绪记录.md`（将来分享计划文件时不会连带暴露情绪记录）；旧档案 `408-进度.md` **保留不动**。

## 四、铁律（违反任何一条都算返工）

### 编码与字节保真
- 读写固定 **UTF-8 无 BOM + `\n`**；**绝不做换行符转换**（`.gitattributes` 已把仓库锁成 LF）
- **严禁 `new String(bytes, UTF_8)` 这类有损解码**。非法 UTF-8 一律严格解码抛 `NotUtf8Exception`，
  **绝不用 U+FFFD 替换**——这是最危险的数据破坏路径
- **P1**：不修改就写回，**字节完全相同**（对任何合法 UTF-8 输入都成立，含 CRLF / BOM 文件）
- **P2**：只改一个字段，**只有该块内容变化**
- **P3**：迁移后原文件所有原样块文本行逐行存在于新文件
- **块边界只由「零字符空行」产生**；只含空格 / 制表符的行是**内容**，不是分隔符
- 结构表**不要按列宽补空格**（否则读不回自己写的文件）

### 写入安全
- **显式保存，绝不自动保存**；**绝不静默覆盖**：保存前比对 SHA-256 基线，不一致就**一个字节都不写**，
  把「重新载入 / 覆盖」交给用户选
- 写前备份到 `<studyDir>\.backup\`，`<原名>.<yyyyMMdd-HHmmss>.bak`，每文件保留最近 10 份
- **内容与打开时相同就不写**（`UNCHANGED`）——一次「什么都没改的保存」会挤掉真正需要的那十份快照

### AI 与安全
- **逐次确认门**：调 AI 前界面必须展示**将要发送的完整请求内容**（不摘要、不截断、不润色）并获得确认；
  **未确认不得发出一个字节**
- **AI 输出永不自动落盘**：必须经界面展示全文、用户点「写入」后才由 `storage` 落盘
- **危机关键词走本地匹配，不由 AI 判断**；命中时**照常提供 AI 分析，不锁定、不阻断**
  （「一旦形成『我说了真话 App 就锁死』的体验，下次就会开始写假的」）
- **情绪数据默认绝不发送**
- **`120` 与 `110` 两条危机渠道不可删除**（文案可改）——**全项目唯一的删除保护**，API 层与文件层双重守住

### 领域与文案规则
- 周界周一–周日；锚点 `2026-09-14`；`DayOfWeek` 用 ISO-8601
- 目标四态（进行中 / 暂缓 / 已取消 / 已完成）**皆为中性表述**；「暂缓」不是删除也不是放弃
- **禁止诊断性标签**：不出现「抑郁」「焦虑症」「中度」「心理健康得分 XX 分」；一律用「**观察到**」「**可能相关**」
- 无数据文案固定为「**待回报**」/「**未回报**」
- **界面禁用词（一个字都不许出现在界面上）**：`未完成`、`逾期`、`落后`、`断签`、`失败`、`警告`、`已放弃`、`未坚持`
- **全字段可编辑，无锁定内容**（`120`/`110` 是唯一例外）
- **界面文案全部收在 `ui/text/UiText`**；`ui/view` 与 `ui/control` 里不许散写中文字面量

### JavaFX / DPI
- **不可写死像素尺寸**：可见尺寸/间距/颜色一律来自令牌或 `em`；禁止 `min == max` 的窗口、禁止位图图标
  （本机显示器约 **200% DPI 缩放**，写死 px 必然破版）
- **状态不得只靠颜色传达**：四态与「待回报」必须同时有文字标签
- **不存在任何 red / orange / 高饱和绿的语义色**；禁止 `Alert.AlertType.ERROR` / `WARNING`
- 字体族显式指定且只用无衬线中文族；**不用 `@import url(...)`** 引远程字体（离线可用）
- **CSS 解析失败在 JavaFX 里只打一行 warning、不抛异常**，表现是「样式没生效」。
  **每次改 CSS 都必须人工看一眼窗口**，不能只看测试绿
- **能用查找值 / 不能用查找值，要按属性分开看**（这条曾被写错过，2026-10-14 由独立复审实测纠正）：
  - **会被静默忽略**：`-fx-padding`、`-fx-background-radius`、`-fx-border-radius`、
    `-fx-background-insets`、`-fx-border-insets`、`-fx-border-width` —— JavaFX 25 运行时抛
    `ClassCastException` 并**丢弃该声明**，而 `CssParser` **不报错**，表现是「内边距/圆角全是 0」。
    实测：`app.css` 曾有 **31 处**这类声明（修复后 0），渲染期 `ClassCastException` 观测由 **32 条降到 0**
    （数字与分布见 `.superpowers/sdd/2026-10-11-plan-3-desktop-ui/task-1-fix2-review.md`）
  - **查找值合法且生效**：`-fx-spacing`、`-fx-min|max|pref-width|height`（Region 的转换器收 Number，
    `-fx-min-height: -fx-sp-6` 实测 `minHeight=32.0`，CCE=0）。**本项目仍统一要求写字面值**——
    这是**约定**（可审计、可逐值对齐令牌），**不是**因为它们会被忽略。**别再写成「同样会被忽略」。**
  - 两条护栏断言（`ColorAuditTest.appCss_lengthProperties_neverReferenceLookupValues` 等）守着这条约定

### 界面数据访问
- **`read()` 先于 `save()`**：打开文档一律走单一入口（`DocumentSession.open`），界面代码**禁止**直接
  `new FileStore(...)` / `TextFileCodec.write(...)` / `Files.write(...)` / `PlanDocument.parse(...)`
  （例外：迁移走 `Migrator.apply`，对话历史走 `ConversationStore`）
- **每份文件全局只有一个会话对象**，由 `AppShell` 持有并注入；**禁止**页面自己 open
- `migrate` 的 `Migrator` 是唯一例外入口；界面**不写 `setStyle`**

## 五、测试与验证纪律

- **判定「护栏是否真的被守住」的唯一标准是变异测试**：把护栏反转或删掉，**必须有至少一个已提交测试变红**。
  只「跑绿了」不算覆盖。**虚假的覆盖声明不可接受**——若某分支反转后仍全绿，**如实说「这半没被守住」**，
  不要粉饰
- 断言必须能定位到**具体数值 / 字段名 / 行号 / 字节偏移**；「消息里含文件名」这种弱断言抓不住真 bug。
  **恒真断言（`assertTrue(true)`、`assertEquals(x,x)`、空断言）零容忍**
- **测试一律用脱敏样例**；**绝不把真实档案或真实情绪记录提交进仓库**
- **测试里不得访问真实网络**：AI 测试打在 JDK 自带的 `com.sun.net.httpserver.HttpServer` 本地桩上；
  界面测试用注入的假 `AiClient` / 假 `ConsentGate`
- **避免绝对时间断言**（负载下会 flake）。要用**因果判据**（如 `CountDownLatch` / 事件标志），
  本项目已把两处「全绿不可信」的根源这样修掉
- 测试写在 `study-core`，以及 `study-desktop` 的 `ui/service`、`ui/theme`、`ui/control`、`ui/text`
  四个包（spec §8.3 的边界在此**显式放宽**）。`ui/view` / `ui/chart` 里的视图不写单元测试，
  由 `package` + 人工验收把关

**真实档案基线（任何任务前后都必须核对，必须完全不变）**：
`%USERPROFILE%\study\408-进度.md`，13377 字节 / 177 行 / LF / 无 BOM，
SHA-256 `9922A25BA8CFB277C2272F73FBA062D82E744F926C7EF8C4D78A0D3EBAA54FF8`。
**`%USERPROFILE%\study` 是用户的真实数据，全程只读**——除用户在界面里显式点了保存/迁移/开始记录。

## 六、工作流程（SDD，别跳过）

**开工前按顺序读**：① spec `docs/superpowers/specs/2026-10-10-study-assistant-design.md`（**唯一有约束力的需求文档**）
② 正在执行的计划 `docs/superpowers/plans/2026-10-11-plan-N-*.md` ③ **账本**
`.superpowers/sdd/<计划名>/progress.md`（最新内容在最下面，里面的 `Ruling:` 行是历史裁定，**必须遵守**）
④ 每任务简报 `.superpowers/sdd/<计划名>/task-N-brief.md` ⑤ `global-constraints.md`。

**每个任务的固定流程**：
1. 读 brief → 2. **先写失败的测试**，跑，确认**红**（记录红的证据；编译失败也算，但要写清）
→ 3. 实现到绿 → 4. **全量回归**（带 `clean`），总数只增不减 → 5. **提交**（`git add <明确路径>`）
→ 6. **独立评审**（由**不是实现者**的角色对照 spec + brief 逐条核查，并**自己用变异验证**至少一处安全关键护栏）
→ 7. 修复轮（最多 5 轮）→ 限定复审 → 8. **往账本追加**本轮结果。

**账本每轮必须包含**：实际命令与**观察到的输出（贴关键行，不要转述）**；
`Ruling: <裁定了什么> — <为什么> — <错了的代价>`（**这是唯一能让用户看到判断依据的通道，不许省略**）；
变异验证转录（哪些变红 / 哪半没被守住就如实说）。

**协作纪律（同一工作树可能有别的 agent 在写）**：
- 实现者**只 `git add <明确路径>`**；**禁止** `git add -A` / `git add .` / `git commit -a`
- **评审者禁止任何写类 git 命令**（`reset` / `checkout` / `restore` / `stash` / `clean` / `rebase`）。
  要隔离环境就**把文件拷到 `%TEMP%`**，并给 Maven 加 `-f`，**绝不在真实仓库里跑变异**
- 多行 commit message 用 `git commit -F <utf8 文件>`，不要堆 `-m`
- **控制器不自己改实现代码**；机制问题交给实跑证据，不要凭印象裁定

## 七、环境陷阱（用代价换来的，别再踩一次）

- **`mvn` 不在 PATH**；`JAVA_HOME` 未设置，靠 PATH 上的 JDK 25（`java -version` → 25.0.3）
- **PowerShell 会写 BOM**：`Set-Content -Encoding utf8` / `Out-File -Encoding utf8` 都加 BOM，**破坏 P1**。
  唯一可靠写法：`[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`，
  内容用 `\n`。`>` 重定向写的是 UTF-16LE + BOM + CRLF
- **Java 源码里 `\uXXXX` 出现在注释 / javadoc 中是编译错误**；写码点请用 `U+FF5C` 这种形式
  （`DisabledWordTest` 另有一条反规避断言禁止字面量里出现 `\u` 转义与零宽字符）
- **`javafx:run` 不会把子 JVM 的 stdout/stderr 带进 Maven 输出流**；要证明 JVM 层面行为
  （native-access 警告等）**必须直接用 JVM 启动**。日志里只有插件 banner **不算证据**
- **`PrintWindow(PW_RENDERFULLCONTENT)` 拿不到 JavaFX 场景内容**；`GetWindowRect` 是**逻辑**坐标、
  截图是**物理**像素（差约 2 倍）。**人工确认窗口才是最终权威**
- **`.superpowers/` 被 git 忽略**（内含 `.gitignore` 只有一行 `*`）但**磁盘上真实存在**，
  是这个项目最重要的过程资产。**不要用 `git clean -xdf`**，也不要因为 `git status` 看不见它就以为它不存在
- **`String.contains` 是子串判断，`List.contains` 是 `equals` 判断**，二者不等价；
  返回给调用方的集合元素要去掉行尾换行符
- **JDK / API 细节必须核实存在性，不能凭印象**（本项目已两次因此返工）

## 八、当前状态（2026-10-14）

| 计划 | 状态 |
|---|---|
| 计划 1 · 基础层（10 任务） | ✅ 完成，终审通过 |
| 计划 2 · 情绪域与 AI（9 任务） | ✅ 完成（账本判 COMPLETE），含「测试确定性批」 |
| 计划 3 · JavaFX 界面（12 任务） | 🔄 Task 1 已关闭（3 轮）；**Task 2 已提交 `d1d56d5` 且已评审（PASS-with-notes）**；Task 3–12 待做 |

- 分支 `plan-1-core`（`main` 是其祖先，**不要 merge / 不要 push / 不要删分支**）
- 基线：**core 480 / 0 fail / 0 error / 4 skipped，desktop 118 / 0 / 0 / 0**。改动不允许让这个数字退化
- 已评审待处理（详见账本；都属小改，不是回归）：
  - Task 2 的 3 条 Minor：`StartupCheck.isWritable` 的**不可写分支在本机被 `assumeFalse` 永久跳过**（未承重）；
    外壳层没有「暗色真的在场景里生效」的读数；`Result.ready()` 在**生产代码里零调用方**（死数据）
  - `task-1-fix2-review.md` 的 **1 MEDIUM + 4 LOW**：`LENGTH_CLASS_PROPERTIES` **过宽**（把 `-fx-spacing` /
    `-fx-min|max|pref-*` 也当成「会被忽略」→ M03 是**假红**，理由已在本文件纠正）；护栏**报的行号偏移 −14**
    （`CssTokens.parse` 剥注释时塌掉了换行）；「CCE 33→0」自证日志只有 **32** 条；报告 §2.1 小结与表不符

**红线**：不要 merge / push / 删分支 / 删 `.superpowers` / 写真实档案 / 提交真实情绪数据 /
顺手改遗留 minor / 重新设计 spec。
