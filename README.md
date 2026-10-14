# 学习助手（Study Assistant）

一个 Windows 桌面应用：把「学习与生活计划」做成**本地优先**的进度管家，并内置一个**完整的 AI 助手**。

它不做第二套数据——界面与 AI 助手**读写同一份 Markdown 文件**（`学习与生活计划.md`），
这份文件同时对你的编辑器、AI、以及这个 App 可见。因此整个存储层是围绕一件事设计的：
**在任何情况下都不静默毁掉你的数据。**

> **项目状态：开发中（约完成计划的 1/4 接口层 + 1/4 界面层）。**
> 基础层与情绪/AI 层已完成并通过全量测试；JavaFX 界面还在做。**非常欢迎接手后续任务**——
> 见下方「当前进度」与「怎么贡献」。

---

## 功能范围

| 已可用（代码层已交付、有测试） | 界面待做 |
|---|---|
| 计划文件的**无损读写**（UTF-8 无 BOM + LF 字节保真、局部编辑、外部改动防护、写前备份） | 9 个侧栏页面 + 1 个求助资源覆盖层 |
| 周界与周记录（周一–周日、锚点周、追加新周） | 打包与人工验收 |
| 情绪记录（独立文件、字段可空、原样行不丢） | |
| 危机关键词**本地**匹配 + 中性资源面板数据 | |
| OpenAI 兼容客户端（非流式 + SSE 流式、失败分类、**逐次确认门**） | |
| 对话历史、提示词「外部文件 + 内置回退」 | |
| 一次性迁移（旧档案 → 新计划文件，备份 → 映射 → diff 确认 → 才写） | |

**明确不做**（不是「还没做」）：云同步、每日打卡/提醒/推送、AI 自动改文件、
function calling / agent 循环 / 本地模型推理、情绪数据加密、用 AI 判断是否触发危机、
统计完成率与打卡天数（这会制造压迫感）。

## 技术栈

- **Java 25**（`maven.compiler.release=25`）
- **JavaFX 25.0.4**（界面；不用 FXML，纯 Java + CSS，无 `module-info.java`）
- **Maven 多模块**：`study-core`（纯 Java）+ `study-desktop`（JavaFX）
- **JUnit Jupiter 5.14.4**；唯一运行时第三方依赖是 **Gson 2.14.0**（HTTP 用 JDK 自带的 `java.net.http.HttpClient`）

## 快速开始

```bash
git clone <this-repo>
cd Study

# 全量测试
mvn -B clean test                     # study-core 480 通过 / study-desktop 147 通过

# 只跑一个测试类
mvn -B -pl study-core clean test -Dtest=AppConfigTest

# 编译界面模块（test 阶段不会编译 study-desktop/src/main，务必额外跑这条）
mvn -B -q -DskipTests package

# 启动窗口（需要先把 core 装进本地仓库）
mvn -q -DskipTests install
mvn -pl study-desktop javafx:run
```

> **`clean` 不是可选项。** Maven 只在源码变更时重编译，读到陈旧 `.class` 会得出错误结论。
>
> 有两组**默认跳过**的只读验收测试，它们会去读你本机的真实档案：
> `-Dstudy.realFile=...`、`-Dstudy.previewOut=...`、`-Dstudy.previewDropLine=...`、
> `-Dstudy.previewAllowedAbsentFile=...`。不传这些属性时它们自动 skip，全量跑仍应是 0 error。

## 项目结构

```
study-core/            纯 Java 逻辑层（不认识界面）
  markdown/            块模型（原样块 / 结构表）+ 无损解析渲染   ← 字节保真的地基
  plan/                目标 / 课程 / 底线 / 阶段 / 周界 / 周记录
  storage/             FileStore（显式保存 + 备份 + SHA-256 基线）、TextFileCodec（严格 UTF-8）
  wellbeing/           情绪记录、危机本地匹配、情绪 AI 分析
  ai/                  OpenAI 兼容客户端 + 逐次确认门（**不得依赖 storage**）
  prompt/ conversation/ migrate/ config/
study-desktop/         JavaFX 界面层
  ui/theme/            设计令牌 + 明暗主题 + 字体族回退链
  ui/service/          界面侧唯一文件读写入口（DocumentSession）
  ui/view/ control/ text/   页面 / 控制器 / 界面文案常量
docs/superpowers/      设计规格与实施计划（**动手前必读**）
```

**依赖方向是承重的，不是代码风格**：`study-desktop → study-core` 单向；
`ai` 包**不得依赖 `storage`**——所以 AI 客户端在编译期就拿不到写文件的能力。
「AI 不改文件」不靠提示词叮嘱，靠依赖方向保证。

## 动手前必读

| 文档 | 作用 |
|---|---|
| `docs/superpowers/specs/2026-10-10-study-assistant-design.md` | **唯一有约束力的需求规格** |
| `docs/superpowers/specs/2026-10-11-javafx-visual-guidelines.md` | JavaFX 视觉规范（设计令牌 / 禁用色 / DPI） |
| `docs/superpowers/plans/2026-10-11-plan-3-desktop-ui.md` | **当前正在执行的计划**（12 个任务，逐任务有 Files/Interfaces/规则/Step） |
| `docs/superpowers/plans/2026-10-10-plan-1-foundation.md`、`2026-10-11-plan-2-wellbeing-and-ai.md` | 已交付的两个计划（想看懂既有代码时读） |
| `CLAUDE.md` | 仓库当前形态、命令、铁律的速查 |

### 不可违反的约束（摘要，完整版见 `CLAUDE.md`）

- **字节保真**：读写固定 UTF-8 无 BOM + `\n`，**绝不转换换行符**；非法 UTF-8 **拒绝读取**
  （绝不用 U+FFFD 替换）；不改动就写回 → **字节完全相同**；只改一个字段 → **只有该块变化**。
- **写入安全**：显式保存、写前备份（保留最近 10 份）、**绝不静默覆盖**（SHA-256 基线比对）。
- **AI 安全**：调用 AI 前必须展示**将要发送的完整原文**并获确认，未确认**不发出一个字节**；
  AI 输出**永不自动落盘**；危机关键词走**本地**匹配，命中时**照常提供 AI 分析、不锁定不阻断**。
- **文案**：界面禁用词一个字都不许出现（`未完成`/`逾期`/`落后`/`断签`/`失败`/`警告`/`已放弃`/`未坚持`）；
  禁用诊断性标签（`抑郁`/`焦虑症`/`中度`/得分）；无数据写「待回报」「未回报」；
  **所有界面中文文案收在 `ui/text/UiText`**，视图里不许散写。
- **DPI**：不可写死像素尺寸（本项目的目标机器是 200% 缩放）；状态不得只靠颜色传达；
  不存在 red/orange/高饱和绿的语义色。

## 当前进度

| 计划 | 状态 |
|---|---|
| 计划 1 · 基础层（10 任务） | ✅ 完成，终审通过 |
| 计划 2 · 情绪域与 AI（9 任务） | ✅ 完成，含终审与修复波 |
| 计划 3 · JavaFX 界面（12 任务） | 🔄 **Task 1–3 完成**（视觉令牌与主题 / 应用外壳与导航 / 界面侧数据访问层）；**Task 4–12 待做** |

**最需要帮助的地方 = 计划 3 的 Task 4–12**：

| # | 任务 |
|---|---|
| 4 | 进度总览页 + 周检查页 + 周记录页 + `UiText` 文案层 |
| 5 | 目标与阶段页（四态中性 chip、全字段可编辑） |
| 6 | 课程页（§一「本学期课程」单源约定） |
| 7 | 情绪记录页 + 情绪趋势页（趋势图必须同时有文字与数据表） |
| 8 | AI 对话页（逐次确认门 + 流式 + 写入前必须点确认） |
| 9 | 设置页（AI 连接 / 路径 / 显示偏好 / 危机渠道 / 迁移向导） |
| 10 | 危机资源面板（中性呈现、不阻断 AI） |
| 11 | 界面禁用词合规测试 `DisabledWordTest` |
| 12 | 打包（`jpackage` 免安装目录）+ 人工验收清单 |

每个任务的完整规格（要建哪些文件、公开接口签名、必须实现的规则、逐步 Step）都在
`docs/superpowers/plans/2026-10-11-plan-3-desktop-ui.md` 里，**照着做即可，不需要重新设计**。

## 怎么贡献

1. **挑一个任务**（建议按 Task 4 → 12 顺序，有依赖）；
2. 读该任务的计划章节 + `CLAUDE.md` 的约束；
3. **先写失败的测试**，跑，确认它**红**；
4. 实现到绿，跑全量回归（`mvn -B clean test`），**测试总数只增不减**；
5. 提交（**只 `git add <明确路径>`**，不要 `git add -A`）；
6. 如果你想让改动更可信：**用变异测试证明护栏承重**——把护栏反转或删掉，
   **必须有至少一个测试变红**。只「跑绿了」不算覆盖。

**本项目的判定标准**：断言必须能定位到**具体数值 / 字段名 / 行号 / 字节偏移**；
「消息里含文件名」这种弱断言抓不住真 bug。测试一律用**脱敏样例**，
**绝不提交真实档案或真实情绪记录**，**测试里不得访问真实网络**。

## 许可证

[MIT](LICENSE)
