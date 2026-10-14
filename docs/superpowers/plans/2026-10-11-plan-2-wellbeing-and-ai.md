# 情绪域与 AI 模块 · 实施计划（计划 2 / 3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已交付的 `study-core` 数据层之上，加上**情绪记录域**（含危机兜底）与 **AI 模块**（OpenAI 兼容客户端、可编辑提示词、逐次确认门、对话历史），全部仍无界面、可单元测试。

**Architecture:** 继续 `study-core` 单模块内的分包：`wellbeing`（情绪记录、危机兜底）、`ai`（客户端、提示词、确认门、对话历史）。`ai` 包**不依赖** `storage` 包——沿用 spec §3.3 的约束，AI 在编译期就拿不到文件写入权。情绪文件沿用 `markdown` 包的块模型与 `storage` 的编码保真读写，因此自动继承 P1。

**Tech Stack:** Java 25 ｜ Maven 3.9.16（IDEA 自带）｜ JUnit Jupiter 5.14.4 ｜ **Gson 2.14.0（本计划新增，spec §3.1 允许）** ｜ JDK 自带 `java.net.http.HttpClient` 与 `com.sun.net.httpserver.HttpServer`（测试用的本地桩服务）

**Spec:** `docs/superpowers/specs/2026-10-10-study-assistant-design.md`

**前置：** 计划 1 已交付（分支 `plan-1-core`，`study-core` 178 测试全绿）。本计划在同一分支的后续提交上进行。

## Global Constraints

以下为 spec 的项目级要求，**每个任务的要求都隐含包含本节**。计划 1 已实测的环境陷阱同样有效，一并沿用。

**运行环境**
- **`mvn` 不在 PATH 上。** 所有 Maven 命令必须用 IDEA 自带的那一份，下文记作 `MVN`：
  `& 'D:\Program Files\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'`
- 工作目录：`E:\Project\Java\Study`；数据目录：`%USERPROFILE%\study`（**严禁写入**，本计划只读它）
- 包根：`com.xiaoyiyuan.study`；Maven groupId：`com.xiaoyiyuan`
- **Maven 只在源码变更时重编译**：怀疑读到陈旧 class 时一律 `clean` 后再跑
- 版本：Gson `2.14.0`（已核实存在）；其余同计划 1

**编码与字节保真（继承计划 1）**
- 读写固定 **UTF-8（无 BOM）+ `\n`**；**不得做换行符转换**
- **P1**：不修改就写回，**字节完全相同**（对任何**合法 UTF-8** 输入成立，含 CRLF / BOM 文件）
- **P2**：只改一个字段，只有该块内容变化
- **非法 UTF-8 一律拒绝读取**（`TextFileCodec` 已实现，`NotUtf8File` 语义沿用 `NotUtf8Exception`）
- **块边界只由零字符空行产生**；仅含空格/制表符的行是**内容**
- **测试必须断言具体数值**；只断言「消息含文件名」这类弱条件抓不住真 bug
- 写文件若由 PowerShell 产出，必须 `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))`（本 harness 的 `Set-Content -Encoding utf8` 会写 BOM）

**AI 与安全（本计划核心）**
- **逐次确认门**：调用 AI 前，界面必须展示**将要发送的完整请求内容**并获得用户确认。**未确认不得发起任何网络请求。**
- **`ai` 包不得依赖 `storage` 包**，也不得持有任何 `Path` 写入口——它在编译期就拿不到文件写入权
- **AI 输出永不自动落盘**：必须经界面展示全文、用户点「写入」后才由 `storage` 落盘
- **危机关键词走本地匹配，不由 AI 判断**；命中时**仍然照常提供 AI 分析，不锁定、不阻断**
- **禁止诊断性标签**：不出现「抑郁」「焦虑症」「中度」「心理健康得分 XX 分」；一律用「**观察到**」「**可能相关**」
- **`120` 与 `110` 两条危机渠道不可删除**（可改文案）；其余渠道用户可增删改
- **情绪数据默认绝不发送**；无用户显式确认，不发出一个字节

**领域规则（继承计划 1）**
- 周界周一–周日；锚点 `2026-09-14`；`DayOfWeek` ISO-8601
- 目标四态（进行中/暂缓/已取消/已完成）均为中性
- 界面禁用词：`未完成`、`逾期`、`落后`、`断签`、`失败`、`警告`、`已放弃`、`未坚持`
- 全字段可编辑，无锁定内容（`120`/`110` 为唯一例外）

**路径（本计划新增）**
- 情绪记录：`%USERPROFILE%\study\生活与情绪记录.md`
- 提示词（可编辑）：`%USERPROFILE%\study\提示词\{学习与生活规划师,生活与心理顾问}.md`
- 提示词（内置回退）：`study-core/src/main/resources/prompts/` 下的同名文件
- 危机关键词表：`study-core/src/main/resources/crisis-keywords.txt`
- 连接配置：`%APPDATA%\xiaoyiyuan-study\config.properties`（计划 1 已建 `AppConfig`）
- 对话历史：`%APPDATA%\xiaoyiyuan-study\conversations\<id>.json`

**测试纪律**
- 测试用**脱敏样例**；**绝不把真实情绪记录或真实档案提交进仓库**
- **不得在测试里访问真实网络**：AI 客户端测试必须打在 JDK 自带的 `com.sun.net.httpserver.HttpServer` 本地桩上
- 断言失败信息要能直接定位到字节偏移、行号或字段名

## Review Focus

Spec 未覆盖、但最可能咬到真实使用者的 5 类输入或条件。**每一条都已在拥有该代码的任务里配了对应测试**：

1. **AI 返回体不是预期的 JSON 形状**（网关返回 HTML 错误页、`choices` 为空数组、`content` 为 null、流式分片被截断）：期望**不崩溃、不静默当成空回复**，而是给出可读的失败分类。→ Task 4、5
2. **流式响应在 UTF-8 多字节字符中间被切断**（SSE 分片边界落在汉字中间）：期望**不解码出乱码**，按字节累积到完整字符再解码。这是流式实现最常见的静默损坏点。→ Task 5
3. **`base_url` 填写形状五花八门**（带尾斜杠、已含 `/v1`、含 `/v1/chat/completions` 整条路径、有前后空格、是 `http://` 本地地址）：期望全部规范化到同一最终 URL，且**不产生 `/v1/v1`**。→ Task 4
4. **危机关键词出现在否定或转述语境**（「我不想自杀」「朋友说他撑不下去了」「昨天看到一篇讲抑郁的文章」）：按 spec §6.5 **不做否定排除，一律命中**，且呈现为**中性面板**（无红色、无诊断词、不阻断 AI）。期望误报代价被压到「多一个中性面板」。→ Task 3
5. **情绪记录文件被手工改坏**（字段行顺序打乱、出现未知字段、日期被改成非法格式、同一天出现两条）：期望**未知字段原样保留、不丢行**，非法日期与重复日期**可读地拒绝**而不是静默丢弃。→ Task 1

---

### Task 1: 情绪记录的文件模型

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/wellbeing/EmotionEntry.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/wellbeing/EmotionLog.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/wellbeing/EmotionFileTest.java`

**Interfaces:**
- Produces: `public record EmotionEntry(LocalDate date, String mood, Integer intensity, String sleep, String exercise, String event, String note, List<String> unknownLines)`
- Produces: `public final class EmotionLog`
  - `static EmotionLog parse(String markdown)`
  - `static EmotionLog empty()` → 头部长这样、无条目
  - `String render()`
  - `List<EmotionEntry> entries()` → **最新在前**（文件顺序）
  - `void addEntry(EmotionEntry)` → 插入到**第一个 `## ` 之前**
  - `void replaceEntry(int index, EmotionEntry)` / `void removeEntry(int index)`
  - `boolean hasEntryOn(LocalDate)`
- Consumes: `MarkdownParser`、`MarkdownDocument`、`RawBlock`（计划 1，同包外但已 `public`）

**文件格式（逐字，新建文件时必须与此一致）**

```markdown
# 生活与情绪记录

> 本文件由「学习助手」App 维护，只存放在本机。
> 记录默认不会被发送给任何 AI；每次分析前 App 都会先展示将要发送的原文。
> 最新记录在最上方。

---

## 2026-10-10（周六）

- 情绪：疲惫 ｜ 强度：3
- 睡眠：7 小时
- 运动：无
- 生活事件：下午电路分析实验拖到六点
- 备注：实验报告还没写，有点烦
```

**规则（全部必须实现）**
- **头部引用块是原样块**：未经编辑时逐字节回写
- 每个日期条目是**结构块**，字段顺序固定：`情绪`（含 `｜ 强度：N` 同行）、`睡眠`、`运动`、`生活事件`、`备注`
- **字段为空则该行不输出**（`备注` 为空 → 没有 `- 备注：` 行）
- 日期标题格式 `## yyyy-MM-dd（周X）`，`周X` 用 `周六` 这类中文
- 分隔符用**全角 `｜`**（U+FF5C），与主计划文件一致
- 新条目插入到**第一个 `## ` 之前**（即最新在最上方）
- **未知行不丢**：条目内出现的、不属于上述五个字段的 `- ` 行，收进该条目的 `unknownLines` 并在回写时**原样输出**
- 非法日期标题 → 抛 `IllegalArgumentException`（含原文）
- 同一天出现两个条目 → `hasEntryOn` 为真；`addEntry` 在同日已存在时抛 `IllegalStateException`（含日期），**不覆盖、不重复插入**

- [ ] **Step 1: 写失败的测试 `EmotionFileTest`**

至少覆盖这些断言（方法名即断言意图，值必须逐字）：

```java
// roundTrip_untouchedFileIsByteIdentical
assertEquals(SAMPLE, EmotionLog.parse(SAMPLE).render());   // SAMPLE 含上面那段完整格式

// newLog_hasHeaderAndNoEntries
assertTrue(EmotionLog.empty().render().startsWith("# 生活与情绪记录"));
assertEquals(0, EmotionLog.empty().entries().size());

// addEntry_insertsBeforeFirstEntry_newestFirst
EmotionLog log = EmotionLog.parse(SAMPLE);
log.addEntry(new EmotionEntry(LocalDate.of(2026,10,11), "平静", 2, null, null, null, null, List.of()));
assertEquals(LocalDate.of(2026,10,11), log.entries().get(0).date());
assertTrue(log.render().indexOf("2026-10-11") < log.render().indexOf("2026-10-10"));

// emptyFields_areOmitted
// 只填 date/mood/intensity 时：render 里含 "- 情绪："，不含 "- 睡眠："、"- 运动："、"- 生活事件："、"- 备注："

// unknownLines_arePreservedVerbatim
// 条目内含 "- 天气：晴" 时：entries().get(0).unknownLines() 含它，且 render() 仍逐字含 "- 天气：晴"

// invalidDateHeading_throwsWithOriginalText
IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> EmotionLog.parse("## 2026-13-45（周X）\n\n- 情绪：平静 ｜ 强度：2\n"));
assertTrue(ex.getMessage().contains("2026-13-45"));

// addEntry_sameDateTwice_isRejected
IllegalStateException ex2 = assertThrows(IllegalStateException.class, () -> {
    EmotionLog l = EmotionLog.parse(SAMPLE);            // SAMPLE 已含 2026-10-10
    l.addEntry(new EmotionEntry(LocalDate.of(2026,10,10), "开心", 5, null,null,null,null, List.of()));
});
assertTrue(ex2.getMessage().contains("2026-10-10"));

// setStrengthOutOfRange_isRejected   —— 强度只允许 1..5
assertThrows(IllegalArgumentException.class,
        () -> new EmotionEntry(LocalDate.of(2026,10,10), "平静", 9, null,null,null,null, List.of()));

// unknownFieldLines_doNotBreakTheFollowingEntry
// 条目 A 含未知行、其后还有条目 B：B 仍被正确解析（日期与字段都对）

// fullWidthSeparator_isUsed
assertTrue(EmotionLog.parse(SAMPLE).render().contains("｜"));   // U+FF5C
```

- [ ] **Step 2: 运行，确认失败**

Run: `MVN -pl study-core test -Dtest=EmotionFileTest`
Expected: 编译失败，`cannot find symbol: class EmotionLog`。

- [ ] **Step 3: 实现，并让该文件的**未修改往返**继承 P1**

要点（签名与断言已定，这里是留下的选择）：
- 复用 `MarkdownParser` 把文件切成块；**头部引用块与 `---` 当原样块处理**，未脏时逐字节回写。
- 每个 `## ` 标题 + 其后的 `- ` 行构成一个条目块。**只有整块都是可识别的字段行或未知 `- ` 行时**才当结构块；出现别的形状（例如夹了段落）则**整块降级为原样块**——宁可不解析，不能改坏。
- 回写：未脏块输出原文；脏条目按固定字段顺序重排并输出（这就是 spec §4.3 已接受的「脏块会重排」代价，只影响该条目内部）。
- `unknownLines` 回写在**已知字段之后**，保持它们在原文中的相对顺序。
- 写文件不走 `ai` 包；本任务只产出**文本**，不碰文件系统。

- [ ] **Step 4: 运行，确认通过**

Run: `MVN -pl study-core clean test -Dtest=EmotionFileTest`
Expected: 全绿。

- [ ] **Step 5: 对真实档案跑一次 P1（确认没有回归）**

Run: `MVN -pl study-core clean test -Dtest=RealFileRoundTripTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"`
Expected: `bytesIn=13377 bytesOut=13377 identical=true`。

- [ ] **Step 6: Commit**

```bash
git add study-core/src
git commit -F <utf8-消息文件>     # 主题：feat(core): 情绪记录的文件模型（未知行不丢，重复日期拒绝）
```

---

### Task 2: 配置扩展（AI 三字段 + 危机渠道，含 120/110 删除保护）

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/wellbeing/CrisisResource.java`
- Modify: `study-core/src/main/java/com/xiaoyiyuan/study/config/AppConfig.java`
- Modify: `study-core/src/test/java/com/xiaoyiyuan/study/config/AppConfigTest.java`

**【顺序说明】** `CrisisResource` 由**本任务**创建（它本质是个配置值类型），Task 3 只创建检测器与资源文件。前置任务先建类型，后续任务才能编译。

**Interfaces:**
- Produces（在既有 `AppConfig` 上新增，全部 `public`）：
  - `String baseUrl()` / `void setBaseUrl(String)`
  - `String apiKey()` / `void setApiKey(String)`
  - `String model()` / `void setModel(String)`
  - `Duration requestTimeout()` / `void setRequestTimeout(Duration)`
  - `String schoolCounseling()` / `void setSchoolCounseling(String)`
  - `List<CrisisResource> crisisResources()` / `void setCrisisResources(List<CrisisResource>)`
  - `record CrisisResource(String label, String number)`（放 `wellbeing` 包，见 Task 3；本任务先按此签名依赖它）
- Consumes: 计划 1 的 `AppConfig`（手写 properties、UTF-8 无 BOM + LF、未知值回退默认）

**规则**
- `crisisResources()` **默认**为 spec 附录 A 的五条内置渠道（`12356`、`010-82951332`、`800-810-1117`、`120`、`110`），顺序固定
- **`setCrisisResources` 必须拒绝删掉 `120` 或 `110`**：若传入列表缺少任一，抛 `IllegalArgumentException`（消息点明缺哪个）；其它渠道可自由增删改
- 存储：`crisisResources` 用 `标签=号码` 逐条存，键前缀 `crisis.N.label` / `crisis.N.number`（**不要用逗号拼接**，号码里没有逗号但标签可能有）
- `baseUrl` / `apiKey` / `model` 默认空串；`requestTimeout` 默认 60 秒
- **沿用计划 1 的教训**：值**按字面读写**，不套用 `Properties` 转义（`base_url` 里可能没有反斜杠，但规则要一致）

- [ ] **Step 1: 写失败的测试（追加到 `AppConfigTest`）**

```java
// crisisDefaults_areTheFiveBuiltins
assertEquals(List.of("12356","010-82951332","800-810-1117","120","110"),
        cfg.crisisResources().stream().map(CrisisResource::number).toList());

// crisis_cannotDrop120Or110
assertThrows(IllegalArgumentException.class, () -> cfg.setCrisisResources(List.of(
        new CrisisResource("全国统一心理援助热线","12356"))));
// 消息必须点明缺哪个
IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> cfg.setCrisisResources(List.of()));
assertTrue(ex.getMessage().contains("120") && ex.getMessage().contains("110"));

// crisis_canBeExtendedAndRelabelled
cfg.setCrisisResources(List.of(new CrisisResource("我改的名字","120"), new CrisisResource("报警","110"),
        new CrisisResource("学校心理中心","1234-5678")));
assertEquals(3, AppConfig.load(f).crisisResources().size());   // 往返

// aiFields_defaultEmpty_andRoundTrip
assertEquals("", cfg.baseUrl());
cfg.setBaseUrl("https://api.deepseek.com"); cfg.setModel("deepseek-chat"); cfg.save();
assertEquals("https://api.deepseek.com", AppConfig.load(f).baseUrl());

// schoolCounseling_blankByDefault_andRoundTrips

// requestTimeout_defaultsTo60Seconds

// writtenFile_staysUtf8NoBomLf_withReadableChinese   —— 复用计划 1 既有断言风格
```

- [ ] **Step 2: 运行，确认失败** → `MVN -pl study-core clean test -Dtest=AppConfigTest`
- [ ] **Step 3: 实现**（要点：新增字段走既有的单点 `render()`/`readValues()` 路径；`setCrisisResources` 的校验是**唯一**的删除保护，要写清注释说明为什么）
- [ ] **Step 4: 运行，确认通过** → `MVN -pl study-core clean test -Dtest=AppConfigTest`
- [ ] **Step 5: 全量回归** → `MVN -pl study-core clean test`
- [ ] **Step 6: Commit**

---

### Task 3: 危机兜底（本地关键词匹配 + 中性资源面板数据）

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/wellbeing/CrisisSignal.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/wellbeing/CrisisDetector.java`
- Create: `study-core/src/main/resources/crisis-keywords.txt`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/wellbeing/CrisisKeywordTest.java`

**Interfaces:**
- Consumes: `public record CrisisResource(String label, String number)`（**Task 2 已创建**）
- Produces: `public record CrisisSignal(boolean triggered, List<String> matchedKeywords)`
- Produces: `public final class CrisisDetector`
  - `static CrisisDetector load(Path keywordFile)` —— 从外部文件加载，**文件不存在则回退到内置资源**
  - `static CrisisDetector builtIn()`
  - `CrisisSignal detect(String text)` —— 大小写不敏感
- Consumes: `AppConfig.crisisResources()`（Task 2）

**规则（严格按 spec §6.5）**
- **本地关键词匹配，不依赖 AI**
- 关键词表放**资源文件**，可更新而不重新编译；文件每行一个词，`#` 开头为注释，空行忽略
- **不做否定语境排除**：「我不想自杀」**同样触发**。理由写进代码注释：误报代价只是一个**中性面板**，漏报代价可能是命；而且「我不想自杀」本身往往也是求助信号
- `detect` **只做判断与给出命中词**，**不产出任何用户可见文案**——文案由界面渲染，且必须中性（无红色、无「警告」、不追问、不评判）
- 命中时**不阻断 AI**：本类**不得**提供任何「阻止继续」的 API

- [ ] **Step 1: 写失败的测试 `CrisisKeywordTest`**

```java
// builtinKeywords_triggerOnObviousSignals
assertTrue(CrisisDetector.builtIn().detect("我不想活了").triggered());
assertTrue(CrisisDetector.builtIn().detect("我想自杀").triggered());

// negationIsNotExcluded_deliberatelyTriggers
CrisisSignal s = CrisisDetector.builtIn().detect("我不想自杀，只是太累了");
assertTrue(s.triggered(), "按 spec §6.5 不做否定排除：宁误报不漏报");
assertFalse(s.matchedKeywords().isEmpty());

// thirdPartyMention_alsoTriggers
assertTrue(CrisisDetector.builtIn().detect("朋友说他撑不下去了").triggered());

// ordinaryText_doesNotTrigger
assertFalse(CrisisDetector.builtIn().detect("今天做了两小时数学，还行").triggered());

// keywordFileFromDisk_isUsed_andOverridesBuiltin
// 写一个只含「香蕉」的文件 → detect("我想吃香蕉") 触发、detect("我想自杀") 不触发

// missingKeywordFile_fallsBackToBuiltin_andDoesNotThrow

// commentsAndBlankLines_areIgnored
// 文件里 "# 注释" 与空行不成为关键词："# 注释" 文本不触发

// matchingIsCaseInsensitive_forAsciiKeywords
// 文件含 "help" 时，"HELP me" 触发

// detect_neverReturnsNull_andHandlesBlankInput
assertFalse(CrisisDetector.builtIn().detect("").triggered());
assertFalse(CrisisDetector.builtIn().detect("   ").triggered());
```

- [ ] **Step 2–4: 标准的 RED → 实现 → GREEN**（`MVN -pl study-core clean test -Dtest=CrisisKeywordTest`）
- [ ] **Step 5: 全量回归** → `MVN -pl study-core clean test`
- [ ] **Step 6: Commit**

---

### Task 4: OpenAI 兼容客户端（非流式、URL 规范化、失败分类）

**Files:**
- Modify: `study-core/pom.xml`（**新增 Gson 2.14.0 依赖**，spec §3.1 允许）
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/AiConfig.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/AiRequest.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/AiFailure.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/AiClient.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/ai/AiClientTest.java`

**Interfaces:**
- Produces: `public record AiConfig(String baseUrl, String apiKey, String model, Duration timeout)`
- Produces: `public record AiRequest(String model, List<AiMessage> messages, boolean stream)`；`public record AiMessage(String role, String content)`
- Produces: `public enum AiFailureKind { NETWORK, TIMEOUT, UNAUTHORIZED, RATE_LIMITED, SERVER_ERROR, MALFORMED_RESPONSE }`
- Produces: `public final class AiFailure extends Exception`，含 `AiFailureKind kind()` 与用户可读中文消息
- Produces: `public final class AiClient`
  - `AiClient(AiConfig config)` ／ `AiClient(AiConfig, HttpClient)`（测试注入）
  - `String complete(AiRequest request) throws AiFailure, InterruptedException` —— **非流式**
  - `static URI endpoint(String baseUrl)` —— **纯函数**，规范化到最终 URL
- Consumes: 无（**不得**依赖 `storage`）

**URL 规范化规则（Review Focus 3，必须有测试）**
- 去除首尾空白，去除**全部**尾部 `/`
- 若结果已以 `/v1` 结尾 → 追加 `/chat/completions`
- 若结果已以 `/v1/chat/completions` 结尾 → **原样返回**（避免拼成 `.../completions/v1/chat/completions`）
- 否则 → 追加 `/v1/chat/completions`
- 例：`https://api.deepseek.com` → `https://api.deepseek.com/v1/chat/completions`；`https://x.com/v1` → `https://x.com/v1/chat/completions`；`https://x.com/v1/chat/completions` → 不变；`http://127.0.0.1:8080` → `http://127.0.0.1:8080/v1/chat/completions`

**测试必须打在本地桩上**（**不得访问真实网络**）：用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起 `127.0.0.1:0`（随机端口），按用例返回不同状态码与响应体。

- [ ] **Step 1: 写失败的测试 `AiClientTest`**

```java
// endpoint_normalizesAllDocumentedShapes
assertEquals(URI.create("https://api.deepseek.com/v1/chat/completions"), AiClient.endpoint("https://api.deepseek.com"));
assertEquals(URI.create("https://api.deepseek.com/v1/chat/completions"), AiClient.endpoint("  https://api.deepseek.com/  "));
assertEquals(URI.create("https://x.com/v1/chat/completions"), AiClient.endpoint("https://x.com/v1"));
assertEquals(URI.create("https://x.com/v1/chat/completions"), AiClient.endpoint("https://x.com/v1/chat/completions"));
assertEquals(URI.create("http://127.0.0.1:8080/v1/chat/completions"), AiClient.endpoint("http://127.0.0.1:8080"));
// 反例：绝不能出现 /v1/v1
assertFalse(AiClient.endpoint("https://x.com/v1").toString().contains("/v1/v1"));

// complete_returnsAssistantContent_onWellFormedResponse
// 桩返回标准 chat.completion JSON → complete 返回 "你好"

// complete_sendsBearerTokenAndModel
// 桩断言 Authorization: Bearer <key> 与请求体里的 model

// complete_classifies_401_asUNAUTHORIZED
// 桩返回 401 → AiFailure with kind == UNAUTHORIZED

// complete_classifies_429_asRATE_LIMITED
// complete_classifies_5xx_asSERVER_ERROR

// complete_htmlErrorPage_isMALFORMED_RESPONSE_notCrash
// 桩返回 200 + "<html>Bad Gateway</html>" → MALFORMED_RESPONSE（不得抛裸 JSON 异常）

// complete_emptyChoices_isMALFORMED_RESPONSE
// 桩返回 200 + {"choices":[]} → MALFORMED_RESPONSE

// complete_nullContent_isMALFORMED_RESPONSE
// 桩返回 200 + {"choices":[{"message":{"content":null}}]} → MALFORMED_RESPONSE

// complete_connectionRefused_isNETWORK
// 指向一个关闭的端口 → NETWORK

// complete_timeout_isTIMEOUT
// 桩 sleep 长于 timeout → TIMEOUT

// complete_doesNotWriteFiles
// 断言的形态：AiClient 的构造签名里没有任何 Path、且包内无 java.nio.file 引用（用反射或源码扫描断言）
```

- [ ] **Step 2: 运行，确认失败**（预期编译失败：`cannot find symbol: class AiClient`）
- [ ] **Step 3: 实现**

要点：
- 用 `java.net.http.HttpClient`（JDK 自带，**不引第三方 HTTP 库**）；请求头 `Authorization: Bearer <apiKey>`、`Content-Type: application/json`
- 用 Gson 解析响应；**任何解析异常都归类为 `MALFORMED_RESPONSE`**，绝不外泄原始 JSON 异常
- 超时用 client 的 `connectTimeout` + 请求级 `timeout`
- **`ai` 包内不得出现 `java.nio.file` / `Files` / `Path` 的写入用法**——这是 spec §3.3 的架构约束，用一条测试守住
- 失败消息一律中文、中性、可执行（例：「密钥被拒绝（401），请检查 API Key」），**不得**出现禁用词

- [ ] **Step 4: 运行，确认通过** → `MVN -pl study-core clean test -Dtest=AiClientTest`
- [ ] **Step 5: 全量回归 + 确认真实档案未被触碰（SHA-256 不变）**
- [ ] **Step 6: Commit**

---

### Task 5: 流式（SSE）响应

**Files:**
- Modify: `study-core/src/main/java/com/xiaoyiyuan/study/ai/AiClient.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/AiStreamListener.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/ai/AiStreamTest.java`

**Interfaces:**
- Produces: `public interface AiStreamListener { void onDelta(String text); void onComplete(String fullText); }`
- Produces: `void AiClient.stream(AiRequest request, AiStreamListener listener) throws AiFailure, InterruptedException`

**规则**
- 解析 `text/event-stream`：`data: {...}` 行；`data: [DONE]` 结束；忽略空行与 `:` 注释行
- 每个分片的 `choices[0].delta.content` 追加为增量
- **字节级累积再解码**（Review Focus 2）：**不得**对每个分片单独 `new String(bytes, UTF_8)`——SSE 分片边界可能落在 UTF-8 多字节字符中间。必须把收到的字节累积到一个缓冲，**只在能解出完整字符时才解码**（用 `CharsetDecoder` 的增量模式，或累积字节后按完整字符边界切分）。
- 分片缺失 `delta` 或 `content` 为 null → **跳过该分片**，不算失败
- 流中断 → 已收到的部分**保留**（`onDelta` 已发出的不回滚），并抛 `AiFailure`（kind 按原因分类）；界面据此标记「**回复不完整**」（spec §7.6 的措辞，**不是**「未完成」）
- `onComplete` 只在正常结束时调用一次

- [ ] **Step 1: 写失败的测试 `AiStreamTest`**

```java
// stream_concatenatesDeltasInOrder
// 桩依次发 data:{"choices":[{"delta":{"content":"你"}}]} / "好" / data:[DONE]
// → listener 收到 onDelta("你")、onDelta("好")、onComplete("你好")

// stream_multibyteSplitAcrossChunks_doesNotProduceMojibake
// 「好」的 UTF-8 是 E5 A5 BD；桩把 E5 A5 与 BD 分两个 TCP 分片发出
// → onComplete 必须等于 "好"，且不得出现 U+FFFD
assertFalse(full.contains("\uFFFD"));

// stream_ignoresNullDeltaAndCommentLines
// 桩发 ": ping" 与 {"choices":[{"delta":{}}]} → 不崩溃、不产生空 delta 之外的输出

// stream_[DONE]_terminates_andOnCompleteFiresOnce
// 用计数器断言 onComplete 恰好调用 1 次

// stream_truncated_keepsPartial_andReportsFailure
// 桩发两个分片后直接断连 → onDelta 收到两个；抛 AiFailure；已收内容不被回滚（另一变量记录）

// stream_httpError_throwsAiFailure_beforeAnyDelta
// 桩返回 401 → 抛 AiFailure(UNAUTHORIZED)，onDelta 一次都没被调用

// stream_doesNotBufferWholeResponseInMemory
// 形态断言：listener 在流未结束时已收到增量（桩在 [DONE] 前 sleep 300ms，
// 在 sleep 期间断言 listener 已被调用过）
```

- [ ] **Step 2–4: RED → 实现 → GREEN**（`MVN -pl study-core clean test -Dtest=AiStreamTest`）
- [ ] **Step 5: 全量回归** → `MVN -pl study-core clean test`
- [ ] **Step 6: Commit**

---

### Task 6: 提示词（外部文件 + 内置回退）

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/PromptRole.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/prompt/PromptLibrary.java`  ← **实际落在 `prompt` 包**（交付时裁定：它要读外部提示词文件，而 `ai` 包被字节码护栏禁止出现 `java/nio/file/*`；`PromptRole` 是纯值，留在 `ai`）
- Create: `study-core/src/main/resources/prompts/学习与生活规划师.md`
- Create: `study-core/src/main/resources/prompts/生活与心理顾问.md`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/ai/PromptFallbackTest.java`

**Interfaces:**
- Produces: `public enum PromptRole { PLANNER("学习与生活规划师"), COUNSELOR("生活与心理顾问") }`，含 `String fileName()`
- Produces: `public final class PromptLibrary`
  - `PromptLibrary(Path promptDir)` —— 外部提示词目录（可不存在）
  - `static PromptLibrary builtIn()` —— **只用内置资源，不读盘**（测试与回退默认走它）
  - `String load(PromptRole role)` —— 外部文件优先，缺失则内置回退
  - `boolean isUsingBuiltIn(PromptRole role)`
- Consumes: `TextFileCodec`（读外部提示词；**严格 UTF-8**，非法则视为缺失并回退内置，同时 `isUsingBuiltIn` 为真）

**规则**
- **文件不存在 → 用内置默认值，不抛异常**（spec §7.2）
- **文件存在 → 优先使用文件内容**，改后立即生效，无需重新编译
- **五种模式的落点**（spec §7.3）：A 建档 / B 滚动 / D 决策咨询 / E 答疑讲解**都是提示词内容**，必须写进内置的角色提示词里（各自说明何时用、产出什么形状）。**C 资料查找**额外需要一个可复用的产出——App 要先生成「搜索关键词 + 提问模板」交给用户去搜（半自动搜索，spec §2 决策 4），因此本任务还要产出：
  - `public static String searchPromptTemplate(String topic)` —— 返回一段中文文本，含若干搜索关键词候选与一个提问模板；**不含任何网络调用**（它只生成文本）
  - 测试：`searchPromptTemplate_containsTopicAndKeywords_andNoNetwork`（断言含 topic、含至少 3 个关键词候选行、且方法签名无网络/IO 相关类型）
- 内置 `学习与生活规划师.md` 必须**逐字包含** spec §7.4 的 10 条硬约束（不评价过去／指出信息缺口／禁止空洞口号／强度匹配／可检验／不制造压迫感／资源可核实／文件单源／砍单强制／**长期目标中立**）
- 内置 `生活与心理顾问.md` 必须**逐字包含** spec §6.5 的措辞约束（禁止诊断性标签；用「观察到」「可能相关」；不计算心理状态分数）
- **不得**把角色定位写成「**考研**规划师」——现阶段的定位是「学习与生活规划师」（spec §7.2）

- [ ] **Step 1: 写失败的测试 `PromptFallbackTest`**

```java
// missingDirectory_usesBuiltIn_andDoesNotThrow
assertTrue(lib.isUsingBuiltIn(PromptRole.PLANNER));
assertTrue(lib.load(PromptRole.PLANNER).contains("不制造压迫感"));

// existingFile_overridesBuiltIn
// 临时目录写一个只含「只回一个字」的 学习与生活规划师.md
assertEquals("只回一个字", lib.load(PromptRole.PLANNER).strip());
assertFalse(lib.isUsingBuiltIn(PromptRole.PLANNER));

// builtInPlanner_containsAllTenHardConstraints
for (String s : List.of("不评价过去","信息不足","空洞口号","可支配时间","可检验","不制造压迫感",
                        "可核实","单源","砍单","长期目标中立"))
    assertTrue(PromptLibrary.builtIn().load(PromptRole.PLANNER).contains(s), s);

// builtInCounselor_forbidsDiagnosticLabels_andRequiresHedgedWording
String c = PromptLibrary.builtIn().load(PromptRole.COUNSELOR);
assertTrue(c.contains("观察到") && c.contains("可能相关"));
assertTrue(c.contains("不替代专业"));

// builtIn_isNotFramedAsExamOnlyPlanner
assertFalse(PromptLibrary.builtIn().load(PromptRole.PLANNER).contains("考研规划师"));

// invalidUtf8PromptFile_fallsBackToBuiltIn
// 写一个 GBK 字节的文件 → load 返回内置内容，isUsingBuiltIn 为真，不抛异常

// roleFileName_matchesSpecPaths
assertEquals("学习与生活规划师.md", PromptRole.PLANNER.fileName());
assertEquals("生活与心理顾问.md", PromptRole.COUNSELOR.fileName());
```

- [ ] **Step 2–4: RED → 实现 → GREEN**（`MVN -pl study-core clean test -Dtest=PromptFallbackTest`）
- [ ] **Step 5: 全量回归 + 确认真实档案与真实提示词目录未被触碰**
- [ ] **Step 6: Commit**

---

### Task 7: 逐次确认门（Spec §7.5 的架构约束）

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/PendingRequest.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/ai/ConsentGate.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/ai/ConsentGateTest.java`

**Interfaces:**
- Produces: `public record PendingRequest(String role, String promptText, List<AiMessage> messages)`
- Produces: `public interface ConsentGate { boolean confirm(PendingRequest request); }` —— **界面实现**；`true` 才允许发送
- Produces: `public final class ConfirmingAiSession`
  - `ConfirmingAiSession(AiClient client, ConsentGate gate)`
  - `String complete(PromptRole role, List<AiMessage> messages) throws AiFailure, InterruptedException`
  - `void stream(PromptRole role, List<AiMessage> messages, AiStreamListener listener) throws ...`
  - `String lastSentPreview()` —— 记录**上次真正发出的完整请求文本**，供界面回显与审计

**规则（这是本计划最重要的一条约束）**
- **未获确认，绝不发起任何网络请求。** `gate.confirm(...)` 返回 `false` 时：**不构造请求、不调用 `AiClient`、不产生任何副作用**，直接返回/抛出一个明确的「用户未确认」结果（用 `IllegalStateException` 或专门的返回值，二者择一并在报告里说明）
- `PendingRequest.promptText()` 必须是**将要发送的完整文本**（提示词 + 全部消息），界面拿它做预览——**不是摘要、不是截断**
- 获得确认后，`lastSentPreview()` 等于当时那份 `promptText`
- `ConfirmingAiSession` **不得**暴露任何绕过 `ConsentGate` 的方法

- [ ] **Step 1: 写失败的测试 `ConsentGateTest`**

```java
// notConfirmed_sendsNothingToTheClient
// 注意：AiClient 是 final 类，**不能**用子类/装饰器做 spy。
// 正确做法：把 AiClient 指向本地桩服务器，在桩上统计收到的请求数。
AtomicInteger hits = new AtomicInteger();   // 由桩服务器的 handler 自增
ConfirmingAiSession s = new ConfirmingAiSession(clientPointingAtStub, req -> false);
assertThrows(...);
assertEquals(0, hits.get(), "未确认时绝不能发起请求");

// notConfirmed_hasNoSideEffectsOnLastSentPreview
assertEquals("", s.lastSentPreview());

// confirmed_sendsExactlyThePreviewedText
// gate 记录收到的 promptText；桩记录实际收到的请求体
// 断言 gate 看到的 promptText 与实际发送内容一致（完整、未截断）

// previewIsTheFullText_notTruncated
// 构造 5000 字的消息 → promptText().length() 等于完整长度

// confirmed_recordsLastSentPreview

// stream_alsoRequiresConfirmation
// gate 返回 false 时 stream 一个 delta 都收不到、桩上 hits 仍为 0

// gateIsConsultedOncePerSend
// 计数器断言 confirm 恰好被调用 1 次（用 gate 侧的计数，不是请求侧）
```

- [ ] **Step 2–4: RED → 实现 → GREEN**（`MVN -pl study-core clean test -Dtest=ConsentGateTest`）
- [ ] **Step 5: 形态断言（守住架构约束）**：`ai` 包内**不得**出现任何文件写入 API。用一条测试扫描 `ai` 包的源码或类引用（例：断言 `com.xiaoyiyuan.study.ai` 下没有类的字段/方法签名带 `java.nio.file.Path`）。这条测试是 spec §3.3 的可执行化。
- [ ] **Step 6: 全量回归 + Commit**

---

### Task 8: 对话历史（Gson）

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/conversation/Conversation.java`  ← **实际落在 `conversation` 包**（交付时裁定：Store 要读写 `Path`，而 `ai` 包被字节码护栏禁止出现 `java/nio/file/*`；依赖方向 `conversation → ai` 单向）
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/conversation/ConversationStore.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/conversation/ConversationStoreTest.java`

**Interfaces:**
- Produces: `public record Conversation(String id, String title, Instant startedAt, List<AiMessage> messages)`
- Produces: `public final class ConversationStore`
  - `ConversationStore(Path conversationsDir)` —— **只记住路径，不创建目录**（创建发生在 `save`，见下）
  - `void save(Conversation) throws IOException`
  - `Optional<Conversation> load(String id) throws IOException`
  - `List<Conversation> list() throws IOException` —— 按 `startedAt` **最新在前**
  - `void delete(String id) throws IOException`
  - `String toMarkdown(Conversation)` —— 供「导出到资料」使用

**规则**
- 存 `<conversationsDir>/<id>.json`，UTF-8 无 BOM + LF
- **构造器不创建目录**（只记住路径）；**`save` 在写前创建父目录**。这样 `list()` 对「目录尚不存在」的语义才有意义，也符合计划 1 的教训（由**写入方**负责建父目录）。
- `id` 由调用方给（界面用 UUID）；本类**不**生成 id，也不做路径拼接校验以外的事
- **`id` 必须校验为安全文件名**（不得含 `/`、`\`、`..`），否则抛 `IllegalArgumentException`——防目录穿越
- `load` 遇到**损坏 JSON** → 抛 `IOException`（含文件路径），**不得**返回空对象或静默忽略
- `list()` 遇到单个损坏文件 → **跳过该文件并在结果里不带它**，但不让整体失败（一个坏文件不该让历史全丢）；同时提供 `List<String> lastSkippedIds()` 供界面提示
- 目录不存在时 `list()` 返回空列表（不抛）
- `toMarkdown` 输出：标题、时间、每条消息按 `## 用户` / `## 助手` 分段——**沿用主计划文件的风格**

- [ ] **Step 1: 写失败的测试 `ConversationStoreTest`**

```java
// saveThenLoad_roundTripsExactly   —— 含中文与换行的消息
// save_createsMissingDirectory     —— 两层缺失的子目录（计划 1 的同款教训）
// save_writesUtf8NoBomLf
// load_corruptJson_throwsWithPath
// list_isNewestFirst
// list_skipsCorruptFile_withoutLosingOthers_andReportsIt
// list_missingDirectory_isEmpty_notThrowing
// delete_removesFile_missingIsNoOp
// idWithPathSeparator_isRejected
assertThrows(IllegalArgumentException.class, () -> store.save(new Conversation("../evil", "t", Instant.now(), List.of())));
// toMarkdown_hasExpectedSections
```

- [ ] **Step 2–4: RED → 实现 → GREEN**（`MVN -pl study-core clean test -Dtest=ConversationStoreTest`）
- [ ] **Step 5: 全量回归 + 确认真实档案 SHA-256 不变**
- [ ] **Step 6: Commit**

---

### Task 9: 情绪 AI 分析（时间窗 → 请求 → 三段式输出）

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/wellbeing/AnalysisWindow.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/wellbeing/EmotionAnalysis.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/wellbeing/EmotionAnalysisTest.java`

**Interfaces:**
- Produces: `public record AnalysisWindow(LocalDate from, LocalDate to)`，含 `static AnalysisWindow lastDays(LocalDate today, int days)` 与 `List<EmotionEntry> select(EmotionLog)`
- Produces: `public final class EmotionAnalysis`
  - `static PendingRequest build(PromptRole role, AnalysisWindow window, EmotionLog log)` —— 组装**将要发送的完整文本**，交给确认门
  - `static String renderEntries(List<EmotionEntry> entries)` —— 把选中的条目渲染为发送文本（**原文形态**，便于用户核对）
  - `record Result(String observations, String factors, String suggestions)`；`static Result parse(String aiText)`
- Consumes: Task 1 的 `EmotionLog`/`EmotionEntry`、Task 6 的 `PromptLibrary`、Task 7 的 `PendingRequest`

**规则**
- **发送内容 = 用户记录原文**，不是摘要（用户要先看到自己写的东西才会放心）
- `Result.parse` 从模型输出里取出三段：**观察到的模式 / 可能的影响因素 / 2~3 条可执行的小建议**。解析**必须容错**：段落缺失 → 该段为空串，其余照收；不抛异常
- **分析结果不写回情绪记录文件**（spec §6.4）——`EmotionAnalysis` **不得**出现任何 `Path` 写入
- 提示词里必须带上 spec §6.5 的措辞约束；`parse` 之前**不得**对模型输出做「洗白」（例如把「抑郁」替换掉）——**措辞约束属于提示词职责，解析层如实呈现**，但要在 `Result` 的文档里写明：界面渲染时若出现诊断性词汇属**模型违规**，应由界面标注而非静默改写
- `AnalysisWindow.lastDays(today, 7)` = `today.minusDays(6)` 到 `today`（含两端共 7 天）

- [ ] **Step 1: 写失败的测试 `EmotionAnalysisTest`**

```java
// lastDays7_isInclusiveOfBothEnds
assertEquals(new AnalysisWindow(LocalDate.of(2026,10,4), LocalDate.of(2026,10,10)),
             AnalysisWindow.lastDays(LocalDate.of(2026,10,10), 7));

// select_filtersByDateRange_andKeepsNewestFirst

// renderEntries_usesTheOriginalText_notASummary
// 条目含备注与未知行时，渲染文本里逐字含备注与未知行

// build_producesPendingRequestWhosePromptTextContainsEverySelectedEntry
// 断言 promptText 里含每条选中记录的日期与备注

// build_promptTextExcludesOutOfRangeEntries
// 区间外的记录**不得**出现在 promptText 里（隐私：只发用户选定的窗口）

// parse_extractsThreeSections
// 输入 "观察到的模式：X\n可能的影响因素：Y\n小建议：Z" → observations=X, factors=Y, suggestions=Z

// parse_missingSection_yieldsEmptyString_notThrow
// 只给第一段 → factors 与 suggestions 为空串，不抛异常

// parse_emptyInput_returnsAllEmpty

// analysisClass_hasNoFileWriteApi
// 形态断言：EmotionAnalysis 的公开签名里没有 Path，且包内无 Files.write 引用
```

- [ ] **Step 2–4: RED → 实现 → GREEN**（`MVN -pl study-core clean test -Dtest=EmotionAnalysisTest`）
- [ ] **Step 5: 全量回归 + 真实档案与真实情绪文件均未被触碰（若 `生活与情绪记录.md` 已存在，报其 SHA-256 前后一致；不存在则报告「未创建」）**
- [ ] **Step 6: Commit**

---

## 覆盖说明（spec 中本计划**不含核心代码**的条目）

明确列出，避免误以为被静默丢弃：

| spec 条目 | 为什么本计划不做 | 归谁 |
|---|---|---|
| §6.3 生活准则 | 它位于主计划文件的 **§五**，是**原样块**（逐行文本编辑），**不需要任何核心代码** | 计划 3 的界面用文本框直接编辑 |
| §6.2 的「点选录入」界面 | 数据模型在 Task 1；点选控件是界面 | 计划 3 |
| §6.5 的中性面板呈现 | 判定与命中词在 Task 3；**文案与渲染是界面**（且必须中性、不阻断 AI） | 计划 3 |
| §7.7 v1 不做 | function calling、工具调用、agent 循环、本地推理、AI 自主读写文件——**本计划一律不实现**，且 Task 7 的形态断言从架构上守住「AI 无文件写入权」 | 不实现 |
| §9 测试 #13 `DisabledWordTest` | 它扫描的是**界面文案** | 计划 3 |
| §8 的 8 个页面、CSS、jpackage | 全是界面与打包 | 计划 3 |

## 计划 2 的全部 opt-in 属性

下列属性**默认都不存在**。缺省时相关用例必须 `skipped`（不是 error）：一条真实档案守卫若在
`Path.of(null)` 上抛 NPE，整轮 `clean test` 就会变成 BUILD FAILURE，而整轮红会掩盖真正失败的那条用例。
`MVN` 会把 `-D` 原样转给 surefire，所以这些值从命令行直达测试里的 `System.getProperty(...)`。

| 属性 | 作用 | 唯一的使用者 | 缺省时 |
|---|---|---|---|
| `study.realFile` | 只读地用真实档案跑一轮：`RealFileRoundTripTest`（逐字节往返）、`EncodingTest.realFile_hasNoBomAndNoCrlf`（无 BOM / 纯 LF / 13377 字节）、`RealMigrationPreviewTest` 的两条用例（迁移预览与「散文一行都不许缺席」断言） | 3 个测试类 | 整类 / 单条用例 `skipped`（类级 `@EnabledIfSystemProperty` + 用例内 `assumeTrue` 双保险） |
| `study.previewOut` | 把迁移结果写到哪里，供人工逐节通读（spec §4.6 第 3 步「展示完整 diff」） | `RealMigrationPreviewTest.writePreviewForHumanReview` | 该条 `skipped`（预览是给人看的产物，不是回归网） |
| `study.previewAllowedAbsentFile` | **负向验证钩子**：指向一份「允许缺席」清单文件（一行一条）。给定时，只有清单里的行算允许缺席 | `RealMigrationPreviewTest.noProseLineFromTheRealArchiveIsAbsentFromTheTarget` | 走内置的三类判据（`---` / `#` 标题 / `|` 表格行），断言行为完全不受影响 |
| `study.previewDropLine` | **负向验证钩子**：给出一条**真实存在**的源行原文，断言用的目标文本会先删掉该行，于是断言必须红 | 同上 | 不删任何行；正常断言 |

两个负向钩子只影响断言、不碰档案也不碰迁移：它们的用途是证明「散文缺席」那条断言**真的会亮红**，
而不是一条空转的绿灯。`study.previewAllowedAbsentFile` 用**文件**而不是逗号分隔的 `-D` 值，
因为真实档案的行里有中文、空格与全角符号，从命令行传一串行文本会被 shell 拆散（实测 exit 255）。

## 计划 2 验收标准

全部满足才算完成：

- [ ] `MVN -pl study-core clean test` 全绿，新增至少：`EmotionFileTest`、`CrisisKeywordTest`、`AiClientTest`、`AiStreamTest`、`PromptFallbackTest`、`ConsentGateTest`、`ConversationStoreTest`、`EmotionAnalysisTest`，以及 `AppConfigTest` 的扩展
- [ ] `MVN -pl study-core clean test -Dtest=RealFileRoundTripTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"` 仍为 **13377 字节进、13377 字节出、identical=true**
- [ ] `%USERPROFILE%\study\408-进度.md` 的 SHA-256 与计划 1 结束时一致（`9922A25BA8CFB277C2272F73FBA062D82E744F926C7EF8C4D78A0D3EBAA54FF8`）
- [ ] 测试**未访问任何真实网络**（AI 相关测试全部打在本地桩上）；可用「断网跑测试仍全绿」验证
- [ ] `%USERPROFILE%\study\生活与情绪记录.md` **要么不存在、要么内容与测试前逐字节相同**（本计划不主动创建它）
- [ ] `ai` 包中**没有**任何文件写入 API（由 Task 7 Step 5 的形态断言守住）
- [ ] `crisis-keywords.txt` 与两个内置提示词都在 `study-core/src/main/resources/` 下，且提示词可用外部文件覆盖
- [ ] `git status` 干净；`.superpowers/` 与测试产物未入库；**未提交任何真实情绪记录或真实档案**

## 下一份计划

**计划 3 —— JavaFX 界面与打包**：8 个页面（总览 / 目标 / 课程 / 周检查 / 周记录 / 情绪 / 助手 / 设置）、CSS 与中文字体、`DisabledWordTest` 文案扫描（spec §9 测试 #13）、迁移向导（展示 diff → 确认）、`jpackage` 产出免安装目录。届时 UI 必须调用 `read()` 后才允许 `save()`（计划 1 的 Task 8 遗留约束），并在比较状态文本前 `strip()`（Task 6 遗留约束）。
