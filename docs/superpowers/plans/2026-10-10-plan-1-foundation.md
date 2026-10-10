# 骨架、无损数据层与计划域 · 实施计划（计划 1 / 3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建起 Maven 双模块工程，并交付一个**字节级无损**读写主计划文件、且能安全完成一次性迁移的 `study-core`。

**Architecture:** `study-core` 内分两层。`markdown` 包只懂「Markdown 块」（原样块 / 表格块 + 脏标记），完全不知道业务；`plan`、`storage`、`migrate`、`config` 包在其上做业务映射与文件安全。依赖方向单向，`core` 不依赖任何界面代码。

**Tech Stack:** Java 25 ｜ Maven 3.9.16（IntelliJ IDEA 自带）｜ JUnit Jupiter 5.14.4 ｜ JavaFX 25.0.4（仅 Task 1 环境冒烟用）

**Spec:** `docs/superpowers/specs/2026-10-10-study-assistant-design.md`

## Global Constraints

以下为 spec 的项目级要求，**每个任务的要求都隐含包含本节**。

**运行环境**
- **`mvn` 不在 PATH 上。** 所有 Maven 命令必须用 IDEA 自带的那一份，下文把这一整串记作 `MVN`：
  `& 'D:\Program Files\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'`
- 工作目录：`E:\Project\Java\Study`
- 数据目录：`%USERPROFILE%\study`
- 源码包根：`com.xiaoyiyuan.study`；Maven groupId：`com.xiaoyiyuan`

**编码与字节保真（最重要）**
- 读写固定 **UTF-8（无 BOM）+ `\n`**；**不得使用平台默认编码**。
- **不得做换行符转换**：读取保真优先于规范化。
- **P1**：不修改就写回，**字节完全相同**（对任何**合法 UTF-8** 输入都成立，含 CRLF / BOM 文件）。
- **非法 UTF-8 一律拒绝读取**：严格解码（`CharsetDecoder` + `CodingErrorAction.REPORT`），抛领域异常，**绝不返回 U+FFFD 替换过的字符串**（否则写回即毁坏用户文件；GBK 文件在中文 Windows 上常见）。
- **P2**：只改一个字段，**只有该块内容变化**，其余块字节不变。
- **P3**：迁移后，原文件所有**原样块文本行**逐行存在于新文件。
- 新建文件固定 UTF-8 无 BOM + LF。
- 检出到 CRLF / BOM 时：**读取不崩溃、不静默转换**，给中性提示 + 显式「规范化为 LF（无 BOM）」操作。

**写入安全**
- **显式保存，不自动保存。**
- 保存前备份至 `.backup\`，命名 `<原文件名>.<yyyyMMdd-HHmmss>.bak`，每文件**保留最近 10 份**，超出删最旧。
- **绝不静默覆盖**：保存前比对内容哈希，不一致必须让用户选择「重新载入」或「覆盖」。
- 迁移**不改动、不删除**原文件；**未确认前不写入任何文件**。

**领域规则**
- 周界 **周一–周日**；第 1 周锚点 = `2026-09-14`–`2026-09-20`；`dayOfWeek` 用 **ISO-8601（周一=1 … 周日=7）**。
- 目标四态：**进行中 / 暂缓 / 已取消 / 已完成**，**四者皆为中性状态**。
- 界面禁用词：`未完成`、`逾期`、`落后`、`断签`、`失败`、`警告`、`已放弃`、`未坚持`。
- 无数据文案固定为「**待回报**」或「**未回报**」。
- **全字段可编辑**，无锁定内容。
- 表格**保留行序**。

**路径**
- 主计划文件：`%USERPROFILE%\study\学习与生活计划.md`
- 迁移来源（保留不动）：`%USERPROFILE%\study\408-进度.md`
- 不可变归档：`%USERPROFILE%\study\.archive\408-进度.md`
- 备份目录：`%USERPROFILE%\study\.backup\`

**版本（已核实存在）**
- JavaFX `25.0.4` ｜ javafx-maven-plugin `0.0.8` ｜ JUnit Jupiter `5.14.4`
- maven-compiler-plugin `3.16.0` ｜ maven-surefire-plugin `3.6.0`
- `maven.compiler.release` = `25`；`project.build.sourceEncoding` = `UTF-8`

**已实测的环境陷阱（Task 1 用代价换来的，后续任务必读）**

- **本 harness 的 PowerShell 写文件会写出 BOM（实测结论，不绑定版本号）**：`Set-Content -Encoding utf8NoBOM` 的参数**被直接拒绝**（该 `FileSystemCmdletProviderEncoding` 枚举只有 `UTF8` 等旧值），而 `Out-File -Encoding utf8` 与 `Set-Content -Encoding utf8` **都会写出 BOM**。要产出本项目要求的「UTF-8 无 BOM + LF」，唯一可靠路径是：
  `[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`，并确保 `$text` 里是 `\n` 而非 `\r\n`。默认的 `>` 重定向与 `Out-File` 写的是 **UTF-16LE + BOM + CRLF**，既违反本约束，文本工具也读不了。
- **`mvn javafx:run` 不会把子 JVM 的 stdout/stderr 带进 Maven 的输出流**（Apache Commons Exec，无 stream pump；已由两方独立核实：插件 `JavaFXRunMojo` 无 `redirectOutput`/`pumpStreamHandler`/`inheritIO`，且 `JavaFXBaseMojo.executeCommandLine` 把子进程输出直接写文件）。因此**无法用 `mvn javafx:run` 的日志证明 JVM 层面的行为**（例如 native-access flag 的效果）；要看应用自身的标准输出/错误，必须**直接用 JVM 启动**——用插件构建的同一条命令行（同样的 `--module-path`、classpath、主类）。
- **`javafx:run` 的前缀目标不能与 `-am` 同用**（见 Task 1 Step 6 的实测结论）。
- **本机显示器约 1.88–2.0 倍 DPI 缩放。** `GetWindowRect` 返回**逻辑**坐标，而截图位图是**物理**像素——两者不是同一坐标空间，按逻辑坐标裁剪会偏移约 2 倍。另外 `PrintWindow(PW_RENDERFULLCONTENT)` **拿不到** JavaFX 场景内容（只拿到原生标题栏）。UI 相关取证必须在同一个坐标空间内完成。
- **`CharsetDecoder` 在 `CodingErrorAction.REPORT` 下，出错时 `ByteBuffer.position()` 已经指向非法序列的**起点**。** 不要写 `position() - result.length()` 之类的减法：`CharsetDecoder.decode` 在 REPORT 分支于「推进过坏字节」之前就 `return cr`，那步推进只发生在 IGNORE/REPLACE。实测：GBK 字节位于文件开头时正确偏移是 `in.position()` = 0，减法得到 **-1**。（结论来自 JDK 25 源码 `UTF_8.Decoder.malformedForLength` → `updatePositions(src, sp, …)`，以及 22 例对照独立 RFC-3629 扫描器的实测。）
- **给实现者的 JDK / API 细节必须核实存在性，不能凭印象。** 本项目已两次因此返工：控制器曾要求调用 `MalformedInputException.getInputOffset()`（**JDK 中不存在**，该异常只有 `getInputLength()`），也曾给出上面那条错误的偏移公式。
- **测试必须断言具体数值，弱条件抓不住这类错误。** 上面那个偏移 bug 是靠断言消息含 `偏移 0` 才被发现的；只断言「消息包含文件名」会放它过去。

**测试纪律**
- 测试用**脱敏样例**，**绝不把真实档案提交进仓库**。
- `RoundTripTest` 与 `MigrationFidelityTest` 必须常绿。它们跑不过就不允许改动界面代码——它们守的是用户的数据。
- 断言失败信息要能直接定位到字节偏移或行号。

## Review Focus

Spec 未覆盖、但最可能咬到真实使用者的 5 类输入或条件。**每一条都已在拥有该代码的任务里配了对应测试**：

1. **文件含 CRLF 或 BOM**（用户用记事本改过）：spec 只实测了纯 LF / 无 BOM。期望：读取不崩溃、往返仍字节一致、**不静默转换**，并给中性提示 + 显式规范化操作。→ Task 2
2. **文件末尾没有换行符**：编辑器常见行为。期望：往返字节一致，**不擅自补换行**。→ Task 3
3. **表格行单元格数不一致、含转义 `\|`、或单元格为空**：解析易错列。期望：不崩溃、不丢行；**错列时降级为原样块**，而不是破坏内容。→ Task 4
4. **章节标题缺失或重复**（用户手删 / 手抄了一节）：章节定位失败。期望：抛出可读的领域异常，**绝不写坏文件**。→ Task 6
5. **迁移目标文件已存在且非空**：spec 只定义了「不存在时才迁移」。期望：**不覆盖**，明确报错并提示改用导入或先归档。→ Task 9

---

### Task 1: Maven 双模块骨架 + JavaFX 环境冒烟

**这是风险 1 的验证任务**（spec §11：JavaFX 25 + JDK 25 在本机从未实际运行过）。它不通过，后续所有界面相关决策都要重做，因此必须第一个做。

**Files:**
- Create: `pom.xml`
- Create: `study-core/pom.xml`
- Create: `study-desktop/pom.xml`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/Launcher.java`
- Create: `study-desktop/src/main/java/com/xiaoyiyuan/study/ui/App.java`

**Interfaces:**
- Produces: Maven 坐标 `com.xiaoyiyuan:study-assistant:1.0.0-SNAPSHOT`（pom）、`com.xiaoyiyuan:study-core:1.0.0-SNAPSHOT`（jar）、`com.xiaoyiyuan:study-desktop:1.0.0-SNAPSHOT`（jar）
- Produces: `com.xiaoyiyuan.study.ui.Launcher.main(String[])` —— 桌面端唯一入口

- [ ] **Step 1: 写父 POM `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.xiaoyiyuan</groupId>
  <artifactId>study-assistant</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>study-core</module>
    <module>study-desktop</module>
  </modules>

  <properties>
    <maven.compiler.release>25</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <javafx.version>25.0.4</javafx.version>
    <junit.version>5.14.4</junit.version>
  </properties>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.16.0</version>
        </plugin>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.6.0</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 2: 写 `study-core/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.xiaoyiyuan</groupId>
    <artifactId>study-assistant</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>study-core</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: 写 `study-desktop/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.xiaoyiyuan</groupId>
    <artifactId>study-assistant</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>study-desktop</artifactId>

  <dependencies>
    <dependency>
      <groupId>com.xiaoyiyuan</groupId>
      <artifactId>study-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-controls</artifactId>
      <version>${javafx.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-maven-plugin</artifactId>
        <version>0.0.8</version>
        <configuration>
          <mainClass>com.xiaoyiyuan.study.ui.Launcher</mainClass>
          <options>
            <option>--enable-native-access=javafx.graphics</option>
          </options>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: 写 `Launcher.java` 与 `App.java`**

`Launcher` 存在的唯一理由：非模块化（classpath）方式下，让 `main` 所在类**不继承** `Application`，否则 JavaFX 会拒绝启动。这是 spec §8.3「不写 `module-info.java`」的直接后果，不是多余的一层。

```java
package com.xiaoyiyuan.study.ui;

/** JavaFX 在 classpath 方式下要求启动类不继承 Application，故独立一个 Launcher。 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
```

```java
package com.xiaoyiyuan.study.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public final class App extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("环境冒烟通过 · 中文渲染正常");
        // 显式指定字体族：JavaFX 中文渲染不指定可能出方块（spec §8.3）
        label.setStyle("-fx-font-family: 'Microsoft YaHei'; -fx-font-size: 20px;");
        stage.setTitle("环境冒烟");
        stage.setScene(new Scene(new StackPane(label), 420, 200));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

- [ ] **Step 5: 编译，验证依赖能解析**

Run: `MVN -q -DskipTests package`
Expected: BUILD SUCCESS，无 "Could not resolve dependencies"。

若报 JavaFX `25.0.4` 无法解析，按此顺序退：`25.0.3` → `25.0.2` → `25.0.1`。**不要**升到 26+（26 及以上要求 JDK 26+，本机是 JDK 25）。

- [ ] **Step 6: 运行，确认窗口出现并渲染中文**

Run: `MVN -q -DskipTests install` 然后 `MVN -pl study-desktop javafx:run`

**顺序不能省，也不能改用 `-am`。两条都已实测：**
- 干净仓库上直接 `MVN -pl study-desktop javafx:run` **会失败**：`-pl` 使 reactor 只含 `study-desktop`，`study-core` 解析不到；`package` 不把 `study-core` 装进本地仓库，**只有 `install` 会**。
- 改成 `-am` **同样会失败**：`javafx:run` 是前缀形式的插件目标，Maven 会在 reactor 的**每个**模块上解析 `javafx` 前缀，而 `study-core` 没有声明该插件，实测报 `No plugin found for prefix 'javafx' in the current project` + `BUILD FAILURE`。
- 因此正确做法是：**先 `install` 把 `study-core` 装进本地仓库，再单独对 `study-desktop` 跑 `javafx:run`。**（`-am` 只适用于 `package` 这类构建目标，不适用于前缀插件目标。）

Expected:
- 弹出标题为「环境冒烟」的窗口，客户区 420×200，显示中文「环境冒烟通过 · 中文渲染正常」，**无方块、无乱码**。
- run 日志中**没有** JDK 25 原生访问 WARNING（由 `--enable-native-access=javafx.graphics` 消除）。
- **日志必须用 UTF-8 落盘**（PowerShell 里显式写 `-Encoding utf8`，否则默认 UTF-16LE+BOM+CRLF，既违反本项目的 LF 约定，文本工具也读不了）。
- **日志必须包含应用自身的输出。** 只截到 `[INFO] --- javafx:0.0.8:run (default-cli) @ study-desktop ---` 这一行 banner **不算证据**——那时 JVM 还没启动，日志里根本没有应用输出，任何"警告已消失"的结论都是空的。

**怎么取证（别用错方法）**：
- **不要**截全屏然后声称窗口在里面。本机显示器为 **200% 缩放**（物理 3200×2000 / 逻辑 1600×1000），而 JavaFX 按**物理像素**定位窗口；裁剪区域若按逻辑坐标算，就会偏移 2 倍——要么截到别的窗口，要么截到空白。这正是 Task 1 第一次取证失败的真实原因。
- 可靠做法：先按 `MainWindowTitle` 找到窗口与句柄，再 `GetWindowRect` 取**物理**坐标后 `CopyFromScreen`。
- `PrintWindow(PW_RENDERFULLCONTENT)` **拿不到** JavaFX 场景内容（只能拿到原生标题栏），不要用它。
- **人工确认是最终权威**：截图为辅，人眼看窗口为主。

这一步同时验证两件事：JavaFX 能在 JDK 25 上跑起来（风险 1），中文渲染正常（风险 3）。**任何一项不通过就在这里停下来解决，不要往下写业务代码。**

- [ ] **Step 7: Commit**

```bash
git add pom.xml study-core/pom.xml study-desktop/pom.xml study-desktop/src
git commit -m "build: Maven 双模块骨架 + JavaFX 25.0.4 环境冒烟通过"
```

---

### Task 2: 文本编码保真读写

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/storage/TextFileCodec.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/storage/EncodingTest.java`

**Interfaces:**
- Produces: `public record TextFile(String content, boolean hasBom, boolean hasCrlf)`
- Produces: `public final class TextFileCodec`，含 `public static TextFile read(Path path) throws IOException`、`public static void write(Path path, String content) throws IOException`、`public static String normalizeToLf(String content)`
- Produces: `public class NotUtf8Exception extends IOException`，消息含文件路径与首个非法字节偏移
- Consumes: 无

**`public` 不是可选项。** Task 9 的 `Migrator` 在 `com.xiaoyiyuan.study.migrate` 包，与 `storage` **不是同一个包**，却要消费 `TextFileCodec`；包私有会让 Task 9 直接编译不过。声明即 `public`。

**严格解码也是硬要求**（见 Global Constraints 的 P1 条）：`read` 必须用 `CharsetDecoder` 的 `REPORT` 模式，非法字节序列抛 `NotUtf8Exception`，不得静默替换成 U+FFFD。对应测试 `invalidUtf8_isRejected_notSilentlyReplaced`。

- [ ] **Step 1: 写失败的测试 `EncodingTest`**

```java
package com.xiaoyiyuan.study.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EncodingTest {

    @Test
    void read_preservesCrlfFile_byteIdenticalRoundTrip(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("crlf.md");
        byte[] original = "# 标题\r\n\r\n正文\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f, original);

        TextFile tf = TextFileCodec.read(f);
        assertTrue(tf.hasCrlf(), "应识别出 CRLF");
        assertFalse(tf.hasBom(), "不应误报 BOM");
        assertEquals("# 标题\r\n\r\n正文\r\n", tf.content(), "读取必须原样保留 CRLF，不转换");

        TextFileCodec.write(f, tf.content());
        assertArrayEquals(original, Files.readAllBytes(f), "P1：不修改就写回，字节必须完全相同");
    }

    @Test
    void read_preservesBomFile_byteIdenticalRoundTrip(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("bom.md");
        byte[] original = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '#', ' ', 'a', '\n'};
        Files.write(f, original);

        TextFile tf = TextFileCodec.read(f);
        assertTrue(tf.hasBom(), "应识别出 UTF-8 BOM");

        TextFileCodec.write(f, tf.content());
        assertArrayEquals(original, Files.readAllBytes(f), "P1：BOM 也必须保真");
    }

    @Test
    void write_newFile_isUtf8NoBomWithLf(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("new.md");
        TextFileCodec.write(f, "# 新文件\n第二行\n");

        byte[] bytes = Files.readAllBytes(f);
        assertEquals('#', bytes[0], "新文件第一个字节必须是 #，不能是 BOM");
        assertFalse(TextFileCodec.read(f).hasCrlf(), "新文件必须是 LF");
    }

    @Test
    void normalizeToLf_stripsBomAndCrlf(@TempDir Path dir) throws IOException {
        String normalized = TextFileCodec.normalizeToLf("\uFEFF# 标题\r\n正文\r\n");
        assertEquals("# 标题\n正文\n", normalized);
    }
}
```

**只用 JUnit 5 自带断言**（`org.junit.jupiter.api.Assertions`）。本计划不引入 AssertJ：spec §3.1 限定 `study-core` 的依赖只有 JUnit 和（计划 2 的）Gson。

- [ ] **Step 2: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=EncodingTest`
Expected: 编译失败，`cannot find symbol: class TextFileCodec`。

- [ ] **Step 3: 实现 `TextFileCodec`**

要点（签名与测试已定，这里是留下的选择）：
- 读取用 `Files.readAllBytes` + `new String(bytes, StandardCharsets.UTF_8)`，**不要**用 `Files.readString`（它会吞掉 BOM 信息，我们就无法识别和保真）。
- `hasBom` = 前三字节为 `EF BB BF`；`hasCrlf` = 内容含 `"\r\n"`。
- `write` 用 `Files.write(path, content.getBytes(StandardCharsets.UTF_8))`。**直接写字符串本身，不做任何替换**——CRLF 保真就是这样实现的。
- `normalizeToLf` = 去掉开头的 `\uFEFF`，再把 `"\r\n"` 全部替换为 `"\n"`。**这个方法只由用户显式点击时调用**，绝不在读取或保存路径上自动调用。

- [ ] **Step 4: 运行测试，确认通过**

Run: `MVN -pl study-core test -Dtest=EncodingTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 5: 对真实文件验证编码识别**

在 `EncodingTest` 里加一个只在显式给路径时才跑的方法（与 Task 3 的 `RealFileRoundTripTest` 同一机制，避免在仓库里放真实档案）：

```java
@Test
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "study.realFile", matches = ".+")
void realFile_hasNoBomAndNoCrlf() throws IOException {
    TextFile tf = TextFileCodec.read(Path.of(System.getProperty("study.realFile")));
    assertFalse(tf.hasBom(), "真实档案应为无 BOM");
    assertFalse(tf.hasCrlf(), "真实档案应为纯 LF");
    assertEquals(13377, tf.content().getBytes(StandardCharsets.UTF_8).length,
            "应为 13377 字节（spec §4.1 实测值）");
}
```

Run: `MVN -pl study-core test -Dtest=EncodingTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"`
Expected: `Tests run: 5, Failures: 0` —— 与 spec §4.1 实测值一致。

- [ ] **Step 6: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 文本编码保真读写（CRLF/BOM 不静默转换）"
```

---

### Task 3: 文档切块 + P1 无损往返

本任务**不识别表格**——此时表格行也是原样文本，P1 天然成立。表格识别放到 Task 4，这样每一步都有独立的测试周期。

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/markdown/Block.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/markdown/RawBlock.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/markdown/MarkdownDocument.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/markdown/MarkdownParser.java`
- Create: `study-core/src/test/resources/fixtures/sample-plan.md`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/markdown/RoundTripTest.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/markdown/RealFileRoundTripTest.java`

**Interfaces:**
- Produces: `interface Block { boolean dirty(); void markDirty(); void clearDirty(); String render(); }`
- Produces: `final class RawBlock implements Block`，构造 `RawBlock(String originalText)`；方法 `String currentText()` / `void setCurrentText(String)`
- Produces: `final class MarkdownDocument`；方法 `List<Block> blocks()`、`String render()`
- Produces: `static MarkdownDocument MarkdownParser.parse(String text)`
- Consumes: 无

- [ ] **Step 1: 造脱敏样例 `sample-plan.md`**

放在 `study-core/src/test/resources/fixtures/sample-plan.md`。**从真实档案手工脱敏改写**，必须包含以下全部特征，否则 P1 测不出问题：

- 头部 `# ` 标题 + `> ` 引用块（含全角 `｜` 分隔符）
- 一个 2 列对齐表格、一个 4 列表格（含 `|---|` 分隔行）
- 有序列表 `1. `、无序列表 `- `
- LaTeX：`$1^\infty$`、`\sum_{k=1}^{n}\frac{n}{n^2+k}`
- 加粗 `**待回报**`、行内代码 `` `study/资料/` ``
- 一段 ```` ``` ```` 围栏代码块
- `---` 分隔线
- 末尾 HTML 注释 `<!-- ... -->`
- **文件以换行符结尾**

**不得包含任何真实个人信息**（真名、院校、成绩、情绪记录）。

- [ ] **Step 2: 写失败的测试 `RoundTripTest`**

```java
package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RoundTripTest {

    private static String fixture() throws IOException {
        try (InputStream in = RoundTripTest.class.getResourceAsStream("/fixtures/sample-plan.md")) {
            assertNotNull(in, "测试样例必须存在");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parseThenRender_isByteIdentical() throws IOException {
        String original = fixture();
        MarkdownDocument doc = MarkdownParser.parse(original);
        assertEquals(original, doc.render(), "P1：不修改就写回，必须逐字符相同");
    }

    @Test
    void parseThenRender_fileWithoutTrailingNewline_isByteIdentical() {
        String original = "# 标题\n\n最后一行没有换行";
        MarkdownDocument doc = MarkdownParser.parse(original);
        assertEquals(original, doc.render(), "不许擅自补末尾换行");
    }

    @Test
    void parseThenRender_emptyAndWhitespaceOnlyInput() {
        assertEquals("", MarkdownParser.parse("").render());
        assertEquals("\n\n", MarkdownParser.parse("\n\n").render());
    }

    @Test
    void parse_keepsEveryLineInSomeBlock() throws IOException {
        String original = fixture();
        MarkdownDocument doc = MarkdownParser.parse(original);
        String joined = doc.blocks().stream().map(Block::render).reduce("", String::concat);
        assertEquals(original, joined, "所有块拼起来必须还原原文");
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=RoundTripTest`
Expected: 编译失败，`cannot find symbol: class MarkdownParser`。

- [ ] **Step 4: 实现 `Block` / `RawBlock` / `MarkdownDocument` / `MarkdownParser`**

要点（签名与测试已定，这里是留下的选择）：

- **切块策略**：按「空行分隔的连续非空行」分组，每组的原文（含组内换行、**不含**分隔空行）放进一个 `RawBlock`；**分隔空行本身也各自成为一个块**（或作为前一块的尾部）——具体做法必须保证 Step 2 的 `parse_keepsEveryLineInSomeBlock` 通过。最省事且不易错的做法：**把空行附加到它前面的那一块末尾**，第一块之前若有空行则单独成块。
- `RawBlock.render()`：`dirty()` 为 false 时返回 `originalText`（**逐字节**），为 true 时返回 `currentText`。
- `MarkdownDocument.render()`：把所有块的 `render()` 拼起来，不做任何收尾修饰（不加末尾换行、不修剪空白）。
- **不要**用 `String.trim()`、`String.strip()`、`split("\\s+")` 之类会吞掉空白的操作。空白就是数据。

- [ ] **Step 5: 运行测试，确认通过**

Run: `MVN -pl study-core test -Dtest=RoundTripTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: 写真实文件的可选测试 `RealFileRoundTripTest`**

设计意图：仓库里不能放真实档案，但**真实性必须被验证过**。所以让它只在显式提供路径时才跑。

```java
package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "study.realFile", matches = ".+")
class RealFileRoundTripTest {

    @Test
    void realProgressFile_isByteIdentical() throws IOException {
        Path p = Path.of(System.getProperty("study.realFile"));
        byte[] original = Files.readAllBytes(p);

        String text = new String(original, java.nio.charset.StandardCharsets.UTF_8);
        MarkdownDocument doc = MarkdownParser.parse(text);
        byte[] rendered = doc.render().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertArrayEquals(original, rendered,
                "真实档案 P1 失败：原 " + original.length + " 字节，渲染后 " + rendered.length + " 字节");
    }
}
```

- [ ] **Step 7: 对真实文件跑一次**

Run: `MVN -pl study-core test -Dtest=RealFileRoundTripTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"`
Expected: `Tests run: 1, Failures: 0` —— 13377 字节进、13377 字节出。

**这一步是整个计划最重要的一次验证。** 它证明「无损往返」在对真实数据上成立，而不只是对样例成立。如果失败，先把失败差异定位清楚再继续。

- [ ] **Step 8: Commit**

```bash
git add study-core/src
git commit -m "feat(core): Markdown 块模型与 P1 无损往返（含真实文件可选验证）"
```

---

### Task 4: 表格识别 + 脏标记 + P2 局部隔离

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/markdown/TableBlock.java`
- Modify: `study-core/src/main/java/com/xiaoyiyuan/study/markdown/MarkdownParser.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/markdown/PartialEditTest.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/markdown/TableParseTest.java`

**Interfaces:**
- Produces: `final class TableBlock implements Block`
  - `List<String> header()`
  - `List<List<String>> rows()`
  - `void setCell(int rowIndex, int colIndex, String value)`
  - `void addRow(List<String> cells)` / `void removeRow(int rowIndex)` / `void moveRow(int from, int to)`
- Consumes: `Block`、`MarkdownDocument`、`MarkdownParser`（Task 3）

- [ ] **Step 1: 写失败的测试 `TableParseTest`**

```java
package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TableParseTest {

    @Test
    void parsesSimpleTable_intoTableBlock() {
        String md = "| 项 | 内容 |\n|---|---|\n| 年级 | 大二上 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        assertEquals(java.util.List.of("项", "内容"), t.header());
        assertEquals(java.util.List.of(java.util.List.of("年级", "大二上")), t.rows());
    }

    @Test
    void noTrailingNewline_roundTripsExactly() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |";
        assertEquals(md, MarkdownParser.parse(md).render());
    }

    @Test
    void malformedRow_degradesToRawBlock_andNeverLosesText() {
        // 3 列表头，数据行只有 2 个单元格 —— 不崩溃、不丢行、降级为原样文本
        String md = "| a | b | c |\n|---|---|---|\n| 1 | 2 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        assertEquals(md, doc.render(), "错列表格必须原样保留");
        assertTrue(doc.blocks().stream().noneMatch(TableBlock.class::isInstance),
                "错列表格应降级为原样块，而不是被解析成结构块");
    }

    @Test
    void escapedPipe_isNotAColumnSeparator() {
        String md = "| 项 | 内容 |\n|---|---|\n| 公式 | a \\| b |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        assertEquals(1, t.rows().size());
        assertEquals("a \\| b", t.rows().get(0).get(1), "转义竖线属于单元格内容");
    }

    @Test
    void emptyCell_isPreserved() {
        String md = "| a | b |\n|---|---|\n|  | 2 |\n";
        MarkdownDocument doc = MarkdownParser.parse(md);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        assertEquals("", t.rows().get(0).get(0));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=TableParseTest`
Expected: 编译失败，`cannot find symbol: class TableBlock`。

- [ ] **Step 3: 写失败的测试 `PartialEditTest`（P2）**

```java
package com.xiaoyiyuan.study.markdown;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PartialEditTest {

    private static final String MD = """
            # 标题

            手写的一段散文，**不要动我**。

            | 项 | 内容 |
            |---|---|
            | 年级 | 大二上 |
            | 目标 | 未定 |

            另一端散文，含 $1^\\infty$ 公式。
            """;

    @Test
    void editOneCell_leavesEveryOtherBlockByteIdentical() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        TableBlock t = (TableBlock) doc.blocks().stream()
                .filter(TableBlock.class::isInstance).findFirst().orElseThrow();
        t.setCell(0, 1, "大二下");

        String out = doc.render();
        assertTrue(out.contains("大二下"), "改动必须生效");
        assertTrue(out.contains("手写的一段散文，**不要动我**。"), "前一段散文必须逐字保留");
        assertTrue(out.contains("另一端散文，含 $1^\\infty$ 公式。"), "后一段散文必须逐字保留");
        assertTrue(out.contains("# 标题"), "标题必须逐字保留");
    }

    @Test
    void untouchedTable_isRenderedFromOriginalBytes() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        assertEquals(MD, doc.render(), "没有改动时整篇必须字节相同（P1 在含表格时同样成立）");
    }

    @Test
    void dirtyFlag_togglesAsExpected() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        Block first = doc.blocks().get(0);
        assertFalse(first.dirty());
        first.markDirty();
        assertTrue(first.dirty());
        first.clearDirty();
        assertFalse(first.dirty());
    }

    @Test
    void rawBlock_editedTextIsUsed() {
        MarkdownDocument doc = MarkdownParser.parse(MD);
        RawBlock raw = doc.blocks().stream()
                .filter(RawBlock.class::isInstance).map(RawBlock.class::cast)
                .filter(b -> b.render().contains("手写的一段散文")).findFirst().orElseThrow();
        raw.setCurrentText(raw.render().replace("不要动我", "已经改了"));
        String out = doc.render();
        assertTrue(out.contains("已经改了"));
        assertTrue(out.contains("另一端散文，含 $1^\\infty$ 公式。"), "未改动的块不受影响");
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=PartialEditTest`
Expected: 失败，`TableParseTest` 同类原因或断言不通过。

- [ ] **Step 5: 实现 `TableBlock` 与 `MarkdownParser` 的表格识别**

要点：

- **识别条件（三个都要满足才当表格）**：① 连续 ≥3 行；② 每行 `strip()` 后以 `|` 开头并以 `|` 结尾；③ 第二行是分隔行（去掉 `|`、`-`、`:`、空格后为空）。
- **列数校验**：分隔行的列数必须与表头一致，且**每个数据行的列数都必须一致**。任一不符 → **整块降级为 `RawBlock`**（Review Focus 3）。
- **切分单元格**：先扫描 `\|`（转义竖线）并跳过，再按 `|` 切；去掉首尾空 `|` 产生的空元素；单元格内容**去掉前后各一个空格**（Markdown 表格惯例），其余空白原样保留。
- **未脏的 `TableBlock.render()`**：返回构造时保存的 `originalText`，**不重新生成**。这是 P1 在含表格时仍成立的关键。
- **脏的 `TableBlock.render()`**：重新生成，列宽按该列最长单元格对齐（中文按**显示宽度**算，全角算 2）。分隔行统一用 `---`。**这会改变对齐空格，是 spec §4.3 已明确接受的代价。**
- `moveRow(from, to)` 支持阶段排期的行序调整（spec §4.4「保留行序」）。

- [ ] **Step 6: 运行全部 core 测试，确认通过**

Run: `MVN -pl study-core test`
Expected: `RoundTripTest`、`TableParseTest`、`PartialEditTest`、`EncodingTest` 全部通过。

- [ ] **Step 7: 再对真实文件跑一次 P1**

Run: `MVN -pl study-core test -Dtest=RealFileRoundTripTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"`
Expected: PASS。真实档案里有 4 张表格，这一步确认表格识别没有破坏 P1。

- [ ] **Step 8: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 表格块识别 + 脏标记 + P2 局部隔离（错列降级为原样块）"
```

---

### Task 5: 周界与周序号

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/WeekCalendar.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/plan/WeekBoundaryTest.java`

**Interfaces:**
- Produces: `static LocalDate WeekCalendar.ANCHOR_MONDAY` = `2026-09-14`
- Produces: `static LocalDate WeekCalendar.mondayOf(LocalDate d)`
- Produces: `static LocalDate sundayOf(LocalDate d)`
- Produces: `static int WeekCalendar.weekNumber(LocalDate d)`
- Produces: `static LocalDate WeekCalendar.weekStart(int weekNumber)`
- Produces: `static String WeekCalendar.weekRangeLabel(int weekNumber)` → 形如 `2026-10-05 – 2026-10-11`
- Consumes: 无

- [ ] **Step 1: 写失败的测试 `WeekBoundaryTest`**

```java
package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class WeekBoundaryTest {

    @Test
    void anchor_isAMonday() {
        assertEquals(java.time.DayOfWeek.MONDAY, WeekCalendar.ANCHOR_MONDAY.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 14), WeekCalendar.ANCHOR_MONDAY);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-14, 1", // 周一
            "2026-09-20, 1", // 周日
            "2026-09-21, 2",
            "2026-09-27, 2",
            "2026-09-28, 3",
            "2026-10-04, 3",
            "2026-10-05, 4",
            "2026-10-10, 4", // 周六
            "2026-10-11, 4"  // 周日
    })
    void weekNumber_matchesArchiveLabels(String date, int expected) {
        assertEquals(expected, WeekCalendar.weekNumber(LocalDate.parse(date)));
    }

    @Test
    void mondayAndSunday_areCorrect() {
        assertEquals(LocalDate.of(2026, 10, 5), WeekCalendar.mondayOf(LocalDate.of(2026, 10, 10)));
        assertEquals(LocalDate.of(2026, 10, 11), WeekCalendar.sundayOf(LocalDate.of(2026, 10, 10)));
    }

    @Test
    void crossesMonthAndYearBoundaries() {
        // 跨月
        assertEquals(LocalDate.of(2026, 9, 28), WeekCalendar.mondayOf(LocalDate.of(2026, 10, 1)));
        // 跨年（2027-01-01 是周五，其所在周的周一是 2026-12-28）
        assertEquals(LocalDate.of(2026, 12, 28), WeekCalendar.mondayOf(LocalDate.of(2027, 1, 1)));
        // 闰年 2028-02-29 是周二，周一为 2028-02-28
        assertEquals(LocalDate.of(2028, 2, 28), WeekCalendar.mondayOf(LocalDate.of(2028, 2, 29)));
    }

    @Test
    void weekStart_isInverseOfWeekNumber() {
        for (int w = 1; w <= 60; w++) {
            LocalDate start = WeekCalendar.weekStart(w);
            assertEquals(w, WeekCalendar.weekNumber(start), "周 " + w + " 起止必须自洽");
            assertEquals(java.time.DayOfWeek.MONDAY, start.getDayOfWeek());
        }
    }

    @Test
    void weekRangeLabel_isHumanReadable() {
        assertEquals("2026-10-05 – 2026-10-11", WeekCalendar.weekRangeLabel(4));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=WeekBoundaryTest`
Expected: 编译失败，`cannot find symbol: class WeekCalendar`。

- [ ] **Step 3: 实现 `WeekCalendar`**

要点：严格照 spec §5.6 的公式。

```
该日所在周的周一 = 该日 − ((dayOfWeek(该日) + 6) mod 7)     // ISO-8601，周一=1 … 周日=7
周序号 = floor((该日所在周的周一 − 2026-09-14) / 7 天) + 1
```

用 `ChronoUnit.DAYS.between(ANCHOR_MONDAY, monday)` 取天数差后整除 7。用 `Math.floorDiv` 而非 `/`，这样锚点之前的日期也不会算错符号。

`weekRangeLabel` 中间用**短破折号 `–`（U+2013）**两边各一个空格，与档案现有写法一致。

- [ ] **Step 4: 运行测试，确认通过**

Run: `MVN -pl study-core test -Dtest=WeekBoundaryTest`
Expected: `Tests run: 5`（含参数化 9 个用例）全部通过。

- [ ] **Step 5: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 周界与周序号计算（锚点 2026-09-14，ISO-8601）"
```

---

### Task 6: 计划文件结构映射

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/GoalStatus.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/Goal.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/Course.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/Baseline.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/Phase.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/ProfileItem.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/PlanSection.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/SectionMissingException.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/PlanDocument.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/plan/PlanDocumentTest.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/plan/GoalStateTest.java`

**Interfaces:**
- Produces: `enum GoalStatus { ACTIVE("进行中"), DEFERRED("暂缓"), CANCELLED("已取消"), DONE("已完成") }`，含 `String label()` 与 `static GoalStatus fromLabel(String)`
- Produces: `record Goal(String name, GoalStatus status, String priority, String window, String note)`
- Produces: `record Course(String name, String weeklyTask, String due, String status)`
- Produces: `record Baseline(String goal, String item, String amount, String plan, String status)`
- Produces: `record Phase(String phase, String window, String focus, String acceptance)`
- Produces: `record ProfileItem(String key, String value)`
- Produces: `enum PlanSection { PROFILE("我的现状"), GOALS("目标清单"), COURSES("课程（本学期）"), BASELINES("每周底线"), PHASES("阶段排期") }`，含 `String label()`（供异常消息直接引用人的说法，而不是枚举名）
- Produces: `final class SectionMissingException extends RuntimeException`，含 `PlanSection section()`
- Produces: `final class PlanDocument`
  - `static PlanDocument parse(String markdown)`
  - `String render()`
  - `List<ProfileItem> profile()` / `void setProfileItem(int index, String value)`
  - `List<Goal> goals()` / `void setGoal(int i, Goal g)` / `void addGoal(Goal g)` / `void removeGoal(int i)` / `void moveGoal(int from, int to)`
  - `List<Course> courses()` / `void setCourse(int i, Course c)` / `void addCourse(Course c)` / `void removeCourse(int i)`
  - `List<Baseline> baselines()` / 同名增删改
  - `List<Phase> phases()` / 同名增删改 **+ `void movePhase(int from, int to)`**
- Consumes: `MarkdownDocument`、`MarkdownParser`、`Block`、`TableBlock`、`RawBlock`（Task 3、4）

- [ ] **Step 1: 写失败的测试 `PlanDocumentTest`**

覆盖受控样例的解析、改一格只动一格、以及**章节缺失 / 重复**（Review Focus 4）。

```java
package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanDocumentTest {

    private static final String MD = """
            # 学习与生活计划 · 测试

            > 当前学期：大二上

            ---

            ## 一、我的现状

            | 项目 | 内容 |
            |---|---|
            | 年级 / 学期 | 大二上 |
            | 本学期课程 | 数据库、Java |

            ---

            ## 二、目标清单

            | 目标 | 状态 | 优先级 | 时间窗 | 说明 |
            |---|---|---|---|---|
            | 本学期学校课程 | 进行中 | 1 | 2026.9–2027.1 | 唯一必须完成的事 |
            | 考研（408） | 暂缓 | — | 大三再定 | 可能取消 |

            ---

            ## 三、课程（本学期）

            | 课程 | 本周任务 | 截止 | 状态 |
            |---|---|---|---|
            | 数据库 | 第 4 章作业 |  | 待回报 |

            ---

            ## 四、每周底线

            | 目标 | 项 | 量 | 怎么安排 | 状态 |
            |---|---|---|---|---|
            | 学校课程 | 作业按时交 | 每周随课 | 当周内完成 | 待回报 |

            ---

            ## 六、阶段排期

            | 阶段 | 时间窗 | 主攻 | 验收标准 |
            |---|---|---|---|
            | A | 2026.9–2027.1 | 学校 6 门课 | 全部及格 |
            | C | 2027.7–2027.8 | 数学基础一轮 | 基础题 ≥70% |
            """;

    @Test
    void parsesProfileGoalsCoursesPhases() {
        PlanDocument doc = PlanDocument.parse(MD);
        assertEquals(2, doc.profile().size());
        assertEquals("大二上", doc.profile().get(0).value());

        assertEquals(2, doc.goals().size());
        assertEquals(GoalStatus.ACTIVE, doc.goals().get(0).status());
        assertEquals(GoalStatus.DEFERRED, doc.goals().get(1).status());
        assertEquals("考研（408）", doc.goals().get(1).name());

        assertEquals(1, doc.courses().size());
        assertEquals("数据库", doc.courses().get(0).name());

        assertEquals(1, doc.baselines().size());
        assertEquals("作业按时交", doc.baselines().get(0).item());
        assertEquals("学校课程", doc.baselines().get(0).goal());

        assertEquals(2, doc.phases().size());
        assertEquals("A", doc.phases().get(0).phase());
    }

    @Test
    void changingOneCell_changesOnlyThatTable() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.setGoal(0, new Goal("本学期学校课程", GoalStatus.ACTIVE, "1", "2026.9–2027.1", "唯一必须完成的事"));

        String out = doc.render();
        assertTrue(out.contains("## 一、我的现状"), "其它章节必须仍在");
        assertTrue(out.contains("| 数据库 | 第 4 章作业 |  | 待回报 |"), "课程表必须逐字未变");
    }

    @Test
    void missingSection_throwsReadableException() {
        String broken = MD.replace("## 二、目标清单", "## 二、目标");
        SectionMissingException ex = assertThrows(SectionMissingException.class,
                () -> PlanDocument.parse(broken));
        assertEquals(PlanSection.GOALS, ex.section());
        assertTrue(ex.getMessage().contains("目标清单"), "异常信息必须能直接读懂");
    }

    @Test
    void duplicateSection_throwsReadableException() {
        String broken = MD + "\n\n## 二、目标清单\n\n| 目标 | 状态 | 优先级 | 时间窗 | 说明 |\n|---|---|---|---|---|\n";
        assertThrows(SectionMissingException.class, () -> PlanDocument.parse(broken));
    }

    @Test
    void phasesOrder_isPreservedAndMovable() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.movePhase(0, 1);
        assertEquals("C", doc.phases().get(0).phase());
        assertEquals("A", doc.phases().get(1).phase());
    }

    @Test
    void goalAddRemove_works() {
        PlanDocument doc = PlanDocument.parse(MD);
        doc.addGoal(new Goal("四级", GoalStatus.ACTIVE, "3", "2027.6", ""));
        assertEquals(3, doc.goals().size());
        doc.removeGoal(0);
        assertEquals(2, doc.goals().size());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=PlanDocumentTest`
Expected: 编译失败，`cannot find symbol: class PlanDocument`。

- [ ] **Step 3: 实现值对象与 `GoalStatus`**

`GoalStatus` 的四态由 spec §5.2 固定。`fromLabel` 遇到未知文本时**抛 `IllegalArgumentException` 并带上原文**——静默降级会让「暂缓」被误读成别的状态，那是数据损坏。

- [ ] **Step 4: 实现 `PlanDocument` 与章节定位**

要点：

- **章节定位**：按顺序扫描块，遇到形如 `## 二、目标清单` 的标题行就记录「当前章节」。**用标题里的中文序号 + 标题名双重匹配**（例如目标清单认 `## 二、目标清单`），只认序号或只认名字都会在用户改标题时误判。
- **每个章节必须恰好出现一次**。0 次或 ≥2 次都抛 `SectionMissingException`（Review Focus 4）。**这是保护用户数据的硬要求：定位不到就不能改，更不能写。**
- **标题之外的散文、列表、引用块、HTML 注释一律不动**——它们不进 `PlanDocument` 的模型，由 `MarkdownDocument` 原样保留。`PlanDocument.render()` 委托给内部持有的 `MarkdownDocument.render()`。
- **单源约定**（spec §5.3）：`courses()` 的行由 §一 的「本学期课程」行生成。本任务先实现「§三 表格存在就解析它」；单源生成逻辑放到计划 3 的界面层，因为那需要用户交互才能决定何时同步。
- 表格外的章节名（大标题、其他节）不参与模型。

- [ ] **Step 5: 写失败的测试 `GoalStateTest`**

```java
package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class GoalStateTest {

    @ParameterizedTest
    @EnumSource(GoalStatus.class)
    void labelRoundTrips(GoalStatus s) {
        assertEquals(s, GoalStatus.fromLabel(s.label()));
    }

    @Test
    void labelsMatchSpec() {
        assertEquals("进行中", GoalStatus.ACTIVE.label());
        assertEquals("暂缓", GoalStatus.DEFERRED.label());
        assertEquals("已取消", GoalStatus.CANCELLED.label());
        assertEquals("已完成", GoalStatus.DONE.label());
    }

    @Test
    void unknownLabel_throwsWithOriginalText() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GoalStatus.fromLabel("暂停中"));
        assertTrue(ex.getMessage().contains("暂停中"), "异常必须带原文，方便定位");
    }

    @Test
    void deferredAndCancelled_areNotFailures() {
        // 四态一律中性：没有任何状态被建模成「失败」
        for (GoalStatus s : GoalStatus.values()) {
            assertFalse(s.name().toUpperCase().contains("FAIL"));
            assertFalse(s.label().contains("失败"));
            assertFalse(s.label().contains("放弃"));
        }
    }
}
```

- [ ] **Step 6: 运行全部 core 测试**

Run: `MVN -pl study-core test`
Expected: 全部通过。

- [ ] **Step 7: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 计划文件结构映射（现状/目标/课程/底线/阶段）+ 章节定位失败保护"
```

---

### Task 7: 周记录追加

spec §5.7 要求「**追加为主，绝不覆盖历史周记录**」。本任务把它做成不需要界面的纯文本操作，因此可以完整测试。

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/WeekRecord.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/plan/WeekRecordAppender.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/plan/WeekRecordAppendTest.java`

**Interfaces:**
- Produces: `record WeekRecord(int weekNumber, LocalDate start, LocalDate end, String heading, List<String> bullets)`
- Produces: `final class WeekRecordAppender`
  - `static List<WeekRecord> parseWeeks(String markdown)`
  - `static boolean hasWeek(String markdown, int weekNumber)`
  - `static String appendWeek(String markdown, int weekNumber)` —— 返回新文本，**不修改输入**
- Consumes: `WeekCalendar`（Task 5）、`MarkdownParser`（Task 3、4）

**周标题格式**（必须与档案现存写法逐字一致，注意是全角 `｜`、短破折号 `–`）：

```
### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）
```

**新一周的模板四行**（与档案 §六 各周一致）：

```
- 本周计划：
- 实际完成：
- 卡住的点：
- 下周行动：
```

- [ ] **Step 1: 写失败的测试 `WeekRecordAppendTest`**

```java
package com.xiaoyiyuan.study.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeekRecordAppendTest {

    private static final String MD = """
            # 学习与生活计划 · 测试

            ## 九、周记录（每周追加，不覆盖）

            ### 2026-09-14 – 2026-09-20（第 1 周 ｜ 周一–周日）

            - 本周计划：单词 20 分钟
            - 实际完成：单词 6 天

            ### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）

            - 实际完成：进行中

            ---

            ## 十、待确认

            1. 目标院校

            <!-- 周界固定为周一–周日 -->
            """;

    @Test
    void parseWeeks_readsExistingWeeks() {
        List<WeekRecord> weeks = WeekRecordAppender.parseWeeks(MD);
        assertEquals(2, weeks.size());
        assertEquals(1, weeks.get(0).weekNumber());
        assertEquals(java.time.LocalDate.of(2026, 9, 14), weeks.get(0).start());
        assertEquals(java.time.LocalDate.of(2026, 9, 20), weeks.get(0).end());
        assertTrue(weeks.get(1).bullets().contains("- 实际完成：进行中"));
    }

    @Test
    void appendWeek_addsHeadingAndTemplateBullets() {
        String out = WeekRecordAppender.appendWeek(MD, 4);

        assertTrue(out.contains("### 2026-10-05 – 2026-10-11（第 4 周 ｜ 周一–周日）"),
                "标题必须与档案格式逐字一致");
        assertTrue(out.contains("- 本周计划："));
        assertTrue(out.contains("- 实际完成："));
        assertTrue(out.contains("- 卡住的点："));
        assertTrue(out.contains("- 下周行动："));
        assertTrue(WeekRecordAppender.hasWeek(out, 4));
    }

    @Test
    void appendWeek_neverTouchesExistingWeeks_orLaterSections() {
        String out = WeekRecordAppender.appendWeek(MD, 4);

        assertTrue(out.contains("- 实际完成：单词 6 天"), "第 1 周必须逐字保留");
        assertTrue(out.contains("### 2026-09-21 – 2026-09-27（第 2 周 ｜ 周一–周日）"), "第 2 周必须在");
        assertEquals(MD.indexOf("## 十、待确认") < out.indexOf("## 十、待确认"), true);
    }

    @Test
    void appendWeek_insertsBeforeNextSection_notAtEndOfFile() {
        String out = WeekRecordAppender.appendWeek(MD, 4);

        int newWeek = out.indexOf("第 4 周");
        int section10 = out.indexOf("## 十、待确认");
        int htmlComment = out.indexOf("<!-- 周界固定为周一–周日 -->");

        assertTrue(newWeek > 0, "新周必须被插入");
        assertTrue(newWeek < section10, "新周必须插在「## 十」之前");
        assertTrue(section10 < htmlComment, "文末 HTML 注释必须仍在最后");
    }

    @Test
    void appendWeek_leavesEverythingBeforeItByteIdentical() {
        String out = WeekRecordAppender.appendWeek(MD, 4);
        String prefixOfOriginal = MD.substring(0, MD.indexOf("## 十、待确认"));
        assertEquals(prefixOfOriginal, out.substring(0, out.indexOf("### 2026-10-05")),
                "插入点之前的全部内容必须字节相同");
    }

    @Test
    void appendWeek_twiceForSameWeek_isRejected() {
        String once = WeekRecordAppender.appendWeek(MD, 4);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> WeekRecordAppender.appendWeek(once, 4));
        assertTrue(ex.getMessage().contains("4"), "异常必须说明是第几周，便于界面改为「编辑既有」");
    }

    @Test
    void appendWeek_whenNoNextSection_appendsAtEnd() {
        String only = "# 计划\n\n## 九、周记录（每周追加，不覆盖）\n\n";
        String out = WeekRecordAppender.appendWeek(only, 3);
        assertTrue(out.contains("第 3 周"));
        assertTrue(out.endsWith("\n"), "结尾应有换行");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=WeekRecordAppendTest`
Expected: 编译失败，`cannot find symbol: class WeekRecordAppender`。

- [ ] **Step 3: 实现 `WeekRecord` 与 `WeekRecordAppender`**

要点：

- **插入点**：§九 章节标题之后、**下一个 `## ` 标题之前**；若其后没有 `## ` 标题，则插到文末。
- **`appendWeek` 只做字符串插入**，不经过 `PlanDocument` 重排——这样插入点之外的内容天然字节不变（测试 `appendWeek_leavesEverythingBeforeItByteIdentical` 就是在守这一点）。
- **已存在同周 → 抛 `IllegalStateException`**，消息里带周序号。**不允许静默重复追加**，也不允许覆盖既有周。界面拿到这个异常后改为提示「这一周已有记录，是否编辑」。
- `parseWeeks` 的标题匹配正则要能容忍全角/半角括号差异但不放宽到误匹配：`^###\s*(\d{4}-\d{2}-\d{2})\s*[–-]\s*(\d{4}-\d{2}-\d{2})（第\s*(\d+)\s*周.*）$`。
- `bullets` 收集该标题之后到下一个 `### ` 或 `## ` 或文末之间的所有 `- ` 行（**保留原样行文本，含 `- ` 前缀**）。

- [ ] **Step 4: 运行测试，确认通过**

Run: `MVN -pl study-core test -Dtest=WeekRecordAppendTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 周记录追加（只插不改，重复周拒绝而非覆盖）"
```

---

### Task 8: 存储服务（备份轮转 + 外部修改检测）

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/storage/BackupRotation.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/storage/FileStore.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/storage/SaveOutcome.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/storage/BackupRotationTest.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/storage/ExternalModificationTest.java`

**Interfaces:**
- Produces: `enum SaveOutcome { SAVED, EXTERNAL_CHANGE_DETECTED }`
- Produces: `final class BackupRotation`
  - `BackupRotation(Path backupDir, int keep)`
  - `Path backup(Path source) throws IOException` → 返回备份文件路径
  - `List<Path> list(Path source)` → 按时间**从新到旧**
- Produces: `final class FileStore`
  - `FileStore(Path file, BackupRotation rotation)`
  - `String read() throws IOException`
  - `SaveOutcome save(String content, boolean force) throws IOException`
  - `boolean changedExternally() throws IOException`
  - `String contentHash() throws IOException`
  - `void reload() throws IOException` → 重新记录基线哈希
- Consumes: `TextFileCodec`（Task 2）

- [ ] **Step 1: 写失败的测试 `BackupRotationTest`**

```java
package com.xiaoyiyuan.study.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupRotationTest {

    @Test
    void backupNameFollowsSpec(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("学习与生活计划.md");
        Files.writeString(src, "内容");
        Path backupDir = dir.resolve(".backup");

        Path made = new BackupRotation(backupDir, 10).backup(src);

        assertTrue(made.getFileName().toString().startsWith("学习与生活计划.md."), "前缀必须是原文件名");
        assertTrue(made.getFileName().toString().endsWith(".bak"), "后缀必须是 .bak");
        assertTrue(made.getFileName().toString().matches(
                "学习与生活计划\\.md\\.\\d{8}-\\d{6}\\.bak"), "实际：" + made.getFileName());
        assertEquals("内容", Files.readString(made), "备份内容必须与源一致");
    }

    @Test
    void keepsOnlyTenNewest(@TempDir Path dir) throws IOException, InterruptedException {
        Path src = dir.resolve("f.md");
        Files.writeString(src, "0");
        BackupRotation rot = new BackupRotation(dir.resolve(".backup"), 10);

        for (int i = 1; i <= 13; i++) {
            Files.writeString(src, String.valueOf(i));
            rot.backup(src);
            Thread.sleep(1100); // 文件名时间戳精度是秒，必须跨秒才能区分
        }

        List<Path> kept = rot.list(src);
        assertEquals(10, kept.size(), "必须只保留最近 10 份");
        assertEquals("13", Files.readString(kept.get(0)), "最新的必须排第一");
    }

    @Test
    void backupOfMissingFile_throws(@TempDir Path dir) {
        assertThrows(IOException.class,
                () -> new BackupRotation(dir.resolve(".backup"), 10).backup(dir.resolve("不存在.md")));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=BackupRotationTest`
Expected: 编译失败，`cannot find symbol: class BackupRotation`。

- [ ] **Step 3: 实现 `BackupRotation`**

要点：
- 文件名时间戳用 `DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")` + `LocalDateTime.now()`。
- **同一秒内重复备份会撞名**，用 `Files.createDirectories` + `StandardCopyOption.REPLACE_EXISTING` 并接受覆盖；测试用 `Thread.sleep(1100)` 跨秒。不要为此引入 `AtomicInteger` 后缀——那会破坏 spec 规定的命名格式。
- `list` 只挑「以 `<原文件名>.` 开头且以 `.bak` 结尾」的文件，避免把别的文件的备份算进来。
- 轮转：`list` 从第 11 个开始删除。

- [ ] **Step 4: 写失败的测试 `ExternalModificationTest`**

```java
package com.xiaoyiyuan.study.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExternalModificationTest {

    private static FileStore storeAt(Path dir) throws IOException {
        Path f = dir.resolve("plan.md");
        if (!Files.exists(f)) {
            Files.writeString(f, "# 原始\n");
        }
        return new FileStore(f, new BackupRotation(dir.resolve(".backup"), 10));
    }

    @Test
    void saveWithNoExternalChange_succeedsAndBacksUp(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read(); // 记录基线

        assertEquals(SaveOutcome.SAVED, store.save("# 改过了\n", false));
        assertEquals("# 改过了\n", Files.readString(dir.resolve("plan.md")));
        assertEquals(1, new BackupRotation(dir.resolve(".backup"), 10).list(dir.resolve("plan.md")).size(),
                "保存前必须已备份");
    }

    @Test
    void externalChange_isDetected_andNotSilentlyOverwritten(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read();

        Files.writeString(dir.resolve("plan.md"), "# 被别人改了\n"); // 模拟 AI 助手改动

        assertTrue(store.changedExternally());
        assertEquals(SaveOutcome.EXTERNAL_CHANGE_DETECTED, store.save("# App 想写的内容\n", false));
        assertEquals("# 被别人改了\n", Files.readString(dir.resolve("plan.md")),
                "检测到外部修改时，绝不能写下去");
    }

    @Test
    void forceOverwrites_despiteExternalChange(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read();
        Files.writeString(dir.resolve("plan.md"), "# 被别人改了\n");

        assertEquals(SaveOutcome.SAVED, store.save("# 用户确认覆盖\n", true));
        assertEquals("# 用户确认覆盖\n", Files.readString(dir.resolve("plan.md")));
    }

    @Test
    void reload_acceptsExternalContentAsNewBaseline(@TempDir Path dir) throws IOException {
        FileStore store = storeAt(dir);
        store.read();
        Files.writeString(dir.resolve("plan.md"), "# 外部内容\n");

        store.reload();

        assertFalse(store.changedExternally(), "重新载入后不应再报外部修改");
        assertEquals(SaveOutcome.SAVED, store.save("# 继续工作\n", false));
    }

    @Test
    void contentHash_isStableForSameBytes(@TempDir Path dir) throws IOException {
        FileStore a = storeAt(dir);
        String h1 = a.contentHash();
        String h2 = storeAt(dir).contentHash();
        assertEquals(h1, h2, "同内容必须同哈希");
    }
}
```

- [ ] **Step 5: 实现 `FileStore`**

要点：
- 基线哈希在**每次成功 `read()` / `reload()` / `save()` 后**更新。
- `contentHash` 用 **SHA-256 对原始字节**计算，返回十六进制字符串。不要对 `String` 求哈希——那会受字符集影响，与"字节相同"的承诺不一致。
- `save` 顺序**必须是**：读当前字节 → 算哈希 → 若与基线不一致且 `force == false`，**直接返回 `EXTERNAL_CHANGE_DETECTED`，一个字节都不写** → 否则先 `rotation.backup(file)` → 再 `TextFileCodec.write` → 更新基线。
- 备份失败必须让 `save` 抛 `IOException`，**不允许"备份失败但照样写"**。
- 文件不存在时：`read()` 返回空串，基线哈希取空字节的哈希（这样"新建文件"路径也能走通）。

- [ ] **Step 6: 运行全部 core 测试**

Run: `MVN -pl study-core test`
Expected: 全部通过。

- [ ] **Step 7: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 存储服务（备份轮转 10 份 + 外部修改检测 + 绝不静默覆盖）"
```

---

### Task 9: 一次性迁移

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/migrate/MigrationPlan.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/migrate/MigrationResult.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/migrate/Migrator.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/migrate/TargetExistsException.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/migrate/MigrationFidelityTest.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/migrate/MigrationSafetyTest.java`
- Test: `study-core/src/test/resources/fixtures/legacy-408-progress.md`

**Interfaces:**
- Produces: `record MigrationPlan(String sourceText, String targetText, List<String> sectionNotes)` —— 纯数据，**不触碰文件系统**
- Produces: `final class Migrator`
  - `Migrator(Path studyDir)`
  - `MigrationPlan plan(Path legacyFile) throws IOException` —— **只读**，可反复调用
  - `MigrationResult apply(MigrationPlan plan, Path targetFile) throws IOException` —— 唯一写文件的方法
- Produces: `record MigrationResult(Path target, Path backup, Path archive)`
- Produces: `final class TargetExistsException extends IOException`
- Consumes: `MarkdownParser`、`BackupRotation`、`TextFileCodec`。**不使用 `PlanDocument`**：目标文件里 §八/§九/§十 都是原样块，而 `PlanDocument` 只建模结构块，承载不了全部内容；因此迁移用**字符串装配**，不绕模型。

- [ ] **Step 1: 造迁移用样例 `legacy-408-progress.md`**

放在 `study-core/src/test/resources/fixtures/legacy-408-progress.md`。**必须忠实复刻真实档案的旧结构**（7 节 + 文末 HTML 注释），但内容全部脱敏。必须包含真实档案里这些难缠的东西：

- `## 一、我的档案` … `## 七、待确认` 全部 7 节
- §二 的 4 行底线表 + 砍单顺序有序列表
- §三 的阶段 A–E 6 行表
- §五 的 ①②③ 三块混合结构
- §六 里**至少 3 周**周记录，含 LaTeX（`$1^\infty$`、`\sum_{k=1}^{n}\frac{n}{n^2+k}`）
- 文末 3 行 HTML 注释

- [ ] **Step 2: 写失败的测试 `MigrationFidelityTest`（P3）**

```java
package com.xiaoyiyuan.study.migrate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationFidelityTest {

    private static String legacy() throws IOException {
        try (InputStream in = MigrationFidelityTest.class
                .getResourceAsStream("/fixtures/legacy-408-progress.md")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 抽出所有「原样块」文本行：非空、且不是表格行、且不是标题行。 */
    private static List<String> preservedLines(String md) {
        List<String> out = new ArrayList<>();
        for (String line : md.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("|")) continue;
            if (t.startsWith("#")) continue;
            if (t.equals("---")) continue;
            out.add(t);
        }
        return out;
    }

    @Test
    void everyPreservedLineSurvives(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = legacy();
        MigrationPlan plan = new Migrator(dir).plan(writeFixture(dir, source));

        List<String> missing = new ArrayList<>();
        String target = plan.targetText();
        for (String line : preservedLines(source)) {
            if (!target.contains(line)) {
                missing.add(line);
            }
        }
        assertTrue(missing.isEmpty(),
                "P3 失败：有 " + missing.size() + " 行原文没进新文件：\n" + String.join("\n", missing));
    }

    @Test
    void weekRecordsAreCarriedOverVerbatim(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        String source = legacy();
        MigrationPlan plan = new Migrator(dir).plan(writeFixture(dir, source));

        // 周记录里的 LaTeX 一个字符都不能变
        assertTrue(plan.targetText().contains("$1^\\infty$"), "LaTeX 必须原样保留");
        assertTrue(plan.targetText().contains("\\sum_{k=1}^{n}\\frac{n}{n^2+k}"), "复杂公式必须原样保留");
    }

    private static Path writeFixture(Path dir, String content) throws IOException {
        Path p = dir.resolve("408-进度.md");
        java.nio.file.Files.writeString(p, content);
        return p;
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=MigrationFidelityTest`
Expected: 编译失败，`cannot find symbol: class Migrator`。

- [ ] **Step 4: 写失败的测试 `MigrationSafetyTest`（含 Review Focus 5）**

```java
package com.xiaoyiyuan.study.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MigrationSafetyTest {

    private static final String LEGACY = """
            # 408 考研备考进度 · 测试

            > 建档：2026-09-12

            ---

            ## 一、我的档案

            | 项目 | 内容 |
            |---|---|
            | 年级 | 大二上 |

            ---

            ## 二、优先级与每周底线

            | 项 | 底线 | 怎么安排 | 状态 |
            |---|---|---|---|
            | 单词 | 7 天里 ≥5 天 | 晨起或睡前 | 待回报 |

            ---

            ## 六、周记录（每周追加，不覆盖）

            ### 2026-09-14 – 2026-09-20（第 1 周）

            - 实际完成：单词 6 天

            <!-- 填写方式：周日晚说一句 -->
            """;

    private static Path legacyFile(Path dir) throws IOException {
        Path p = dir.resolve("408-进度.md");
        Files.writeString(p, LEGACY, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void plan_doesNotTouchAnyFile(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        byte[] before = Files.readAllBytes(legacy);

        new Migrator(dir).plan(legacy); // 只读

        assertArrayEquals(before, Files.readAllBytes(legacy), "plan() 不得改动源文件");
        assertFalse(Files.exists(dir.resolve("学习与生活计划.md")), "plan() 不得写出目标文件");
        assertFalse(Files.exists(dir.resolve(".backup")), "plan() 不得产生备份");
    }

    @Test
    void apply_createsBackupAndArchive_leavesSourceUntouched(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        byte[] before = Files.readAllBytes(legacy);
        Migrator m = new Migrator(dir);

        MigrationResult r = m.apply(m.plan(legacy), dir.resolve("学习与生活计划.md"));

        assertArrayEquals(before, Files.readAllBytes(legacy), "原文件必须一个字节都不变");
        assertTrue(Files.exists(r.target()), "目标文件必须已创建");
        assertTrue(Files.exists(r.backup()), "必须已备份");
        assertTrue(Files.exists(r.archive()), "必须已归档");
        assertArrayEquals(before, Files.readAllBytes(r.archive()), "归档必须与原文逐字节相同");
        assertTrue(r.archive().toString().contains(".archive"), "归档目录必须是 .archive");
    }

    @Test
    void apply_refusesWhenTargetExistsAndIsNotEmpty(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Files.writeString(target, "# 已经有一份了\n");
        Migrator m = new Migrator(dir);

        assertThrows(TargetExistsException.class, () -> m.apply(m.plan(legacy), target));
        assertEquals("# 已经有一份了\n", Files.readString(target), "已存在的目标文件绝不能被覆盖");
    }

    @Test
    void apply_allowsMissingOrEmptyTarget(@TempDir Path dir) throws IOException {
        Path legacy = legacyFile(dir);
        Path target = dir.resolve("学习与生活计划.md");
        Files.writeString(target, "");
        Migrator m = new Migrator(dir);

        MigrationResult r = m.apply(m.plan(legacy), target);
        assertTrue(Files.readString(r.target()).contains("学习与生活计划"));
    }
}
```

- [ ] **Step 5: 实现 `Migrator` 与映射**

要点：

- **`plan()` 绝不碰文件系统**：只 `TextFileCodec.read` 源文件、在内存里算目标文本。
- **`apply()` 的固定顺序**：① 目标存在且非空 → 抛 `TargetExistsException`（Review Focus 5，**先检查再动手**）② 备份源文件到 `.backup\` ③ 归档源文件到 `.archive\408-进度.md` ④ 写目标文件 ⑤ 返回三个路径。**全程不修改、不删除、不重命名源文件。**
- **映射**严格照 spec §4.6 的表。其中必须照做的：
  - §六 周记录**整段原样搬运**到新文件 §九，一个字符都不改（P3 的核心）。
  - §一 中考研相关字段（目标、弱项自评）搬到 §八「考研（暂缓）」。
  - §二 底线表加「目标」列。
  - §三 阶段排期整表搬到 §八。
  - 新文件 §三 课程表用 6 门课初始化（数据库、Java、线性代数、物理学、电路分析基础、马克思主义基本原理）；**若源文件的 §一 有「大二在学」行，以其内容为准**。
  - §二 目标清单初始化三行：本学期学校课程（进行中/1）、生活与身心（进行中/2）、考研（408）（**暂缓**/—）。
- **多级标题也要认**：源文件的 `### 2026-09-14 …` 是三级标题，别把它当章节边界。
- 源文件缺某节时，**按 spec §4.6 尽力映射并在 `sectionNotes` 里记一条**，不要抛异常——因为旧档案本来就可能被用户改过。

- [ ] **Step 6: 运行全部 core 测试**

Run: `MVN -pl study-core test`
Expected: 全部通过。

- [ ] **Step 7: 对真实档案做一次迁移演练（纯只读，不动真数据）**

新增一个只在显式给路径时才跑、且**把预览写到指定位置供人工通读**的测试。这样不需要任何事后要删的临时代码：

```java
package com.xiaoyiyuan.study.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "study.realFile", matches = ".+")
class RealMigrationPreviewTest {

    @Test
    void writePreviewForHumanReview(@TempDir Path dir) throws IOException {
        Path legacy = Path.of(System.getProperty("study.realFile"));
        MigrationPlan plan = new Migrator(dir).plan(legacy);   // 只读

        Path out = Path.of(System.getProperty("study.previewOut"));
        Files.writeString(out, plan.targetText(), StandardCharsets.UTF_8);

        assertTrue(Files.size(out) > 0, "预览文件不能是空的");
    }
}
```

Run: `MVN -pl study-core test -Dtest=RealMigrationPreviewTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md" "-Dstudy.previewOut=%TEMP%\migrated-preview.md"`

然后**人工通读 `%TEMP%\migrated-preview.md`**，逐条确认：

| 要确认的 | 期望 |
|---|---|
| 6 周周记录 | 全部在 §九，LaTeX（`$1^\infty$`、`\sum_{k=1}^{n}\frac{n}{n^2+k}`）一个字符没变 |
| 阶段 A–E | 整表在 §八「考研（暂缓）」 |
| 目标清单 | 三行：本学期学校课程（进行中/1）、生活与身心（进行中/2）、考研（408）（**暂缓**/—） |
| 课程表 | 6 门：数据库、Java、线性代数、物理学、电路分析基础、马克思主义基本原理 |
| 文末 HTML 注释 | 3 行仍在最后 |
| 弱项自评、不碰清单 | 在 §八，不在正文 |

**并且必须确认 `%USERPROFILE%\study\` 下没有产生任何新文件**——`plan()` 是纯只读的，多出文件就说明实现写错了。

通读通过后，删掉预览文件即可；`RealMigrationPreviewTest` **保留在仓库里**（它是可复用的验证工具，不是临时代码）。

- [ ] **Step 8: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 一次性迁移（plan 只读 / apply 先备份归档 / P3 保真 / 拒绝覆盖已有目标）"
```

---

### Task 10: 配置与路径

**Files:**
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/config/AppPaths.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/config/DeferredVisibility.java`
- Create: `study-core/src/main/java/com/xiaoyiyuan/study/config/AppConfig.java`
- Test: `study-core/src/test/java/com/xiaoyiyuan/study/config/AppConfigTest.java`

**Interfaces:**
- Produces: `enum DeferredVisibility { VISIBLE_DIM, COLLAPSED, HIDDEN }`，默认 `COLLAPSED`
- Produces: `final class AppPaths`
  - `static Path appDataDir()` → `%APPDATA%\xiaoyiyuan-study`
  - `static Path configFile()` → `appDataDir()\config.properties`
  - `static Path conversationsDir()`
  - `static Path defaultStudyDir()` → `%USERPROFILE%\study`
  - `static Path planFile(Path studyDir)` → `studyDir\学习与生活计划.md`
  - `static Path legacyFile(Path studyDir)` → `studyDir\408-进度.md`
  - `static Path backupDir(Path studyDir)` / `static Path archiveDir(Path studyDir)`
- Produces: `final class AppConfig`
  - `static AppConfig load(Path file)`
  - `Path studyDir()` / `void setStudyDir(Path)`
  - `DeferredVisibility deferredVisibility()` / `void setDeferredVisibility(DeferredVisibility)`
  - `List<String> moodOptions()` / `void setMoodOptions(List<String>)`
  - `void save() throws IOException`
- Consumes: 无

- [ ] **Step 1: 写失败的测试 `AppConfigTest`**

```java
package com.xiaoyiyuan.study.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void defaults_whenFileMissing(@TempDir Path dir) {
        AppConfig c = AppConfig.load(dir.resolve("不存在.properties"));
        assertEquals(Path.of("%USERPROFILE%\\study"), c.studyDir());
        assertEquals(DeferredVisibility.COLLAPSED, c.deferredVisibility(), "默认折叠");
        assertEquals(List.of("平静", "有劲", "疲惫", "烦躁", "低落", "焦虑", "开心", "麻木"),
                c.moodOptions());
    }

    @Test
    void roundTripsThroughFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setStudyDir(Path.of("E:\\别的目录"));
        c.setDeferredVisibility(DeferredVisibility.HIDDEN);
        c.setMoodOptions(List.of("还行", "很累"));
        c.save();

        AppConfig again = AppConfig.load(f);
        assertEquals(Path.of("E:\\别的目录"), again.studyDir());
        assertEquals(DeferredVisibility.HIDDEN, again.deferredVisibility());
        assertEquals(List.of("还行", "很累"), again.moodOptions());
    }

    @Test
    void fileIsUtf8_noBom_lfOnly(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        AppConfig c = AppConfig.load(f);
        c.setMoodOptions(List.of("疲惫"));
        c.save();

        byte[] bytes = Files.readAllBytes(f);
        assertNotEquals((byte) 0xEF, bytes[0], "不得写 BOM");
        assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("\r\n"),
                "不得写 CRLF");
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("疲惫"),
                "中文必须可读，不能被转义成 \\uXXXX");
    }

    @Test
    void unknownVisibilityValue_fallsBackToDefault(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("config.properties");
        Files.writeString(f, "deferredVisibility=乱写的\n");
        assertEquals(DeferredVisibility.COLLAPSED, AppConfig.load(f).deferredVisibility());
    }

    @Test
    void appPaths_areUnderStudyDir(@TempDir Path dir) {
        assertEquals(dir.resolve("学习与生活计划.md"), AppPaths.planFile(dir));
        assertEquals(dir.resolve("408-进度.md"), AppPaths.legacyFile(dir));
        assertEquals(dir.resolve(".backup"), AppPaths.backupDir(dir));
        assertEquals(dir.resolve(".archive"), AppPaths.archiveDir(dir));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `MVN -pl study-core test -Dtest=AppConfigTest`
Expected: 编译失败，`cannot find symbol: class AppConfig`。

- [ ] **Step 3: 实现 `AppPaths`、`DeferredVisibility`、`AppConfig`**

要点：
- 用 `java.util.Properties`，但**不要**用它的 `store()` —— 那个方法会写 `#注释` 与时间戳，且默认 `ISO-8859-1` 转义中文。**手写读写**：`key=value` 每行一条，`Files.writeString(..., UTF_8)` 并确保用 `\n`。这样中文保持可读（测试里有断言）。
- 情绪选项用逗号分隔存一行。
- `studyDir` 存字符串，读时 `Path.of(...)`。
- 未知枚举值**回退默认值**，不抛异常——配置文件被手改坏不该让 App 起不来。

- [ ] **Step 4: 运行全部 core 测试**

Run: `MVN -pl study-core test`
Expected: 全部通过。这是计划 1 的验收点。

- [ ] **Step 5: Commit**

```bash
git add study-core/src
git commit -m "feat(core): 配置与路径（study 目录 / 暂缓目标显示方式 / 情绪选项）"
```

---

## 计划 1 验收标准

全部满足才算完成：

- [ ] `MVN -pl study-core test` 全绿，包含 `RoundTripTest`、`PartialEditTest`、`TableParseTest`、`EncodingTest`、`WeekBoundaryTest`、`PlanDocumentTest`、`GoalStateTest`、`WeekRecordAppendTest`、`BackupRotationTest`、`ExternalModificationTest`、`MigrationFidelityTest`、`MigrationSafetyTest`、`AppConfigTest`
- [ ] `MVN -pl study-core test -Dtest=RealFileRoundTripTest "-Dstudy.realFile=%USERPROFILE%\study\408-进度.md"` 通过：**13377 字节进、13377 字节出**
- [ ] `MVN -q -DskipTests install` 后 `MVN -pl study-desktop javafx:run` 弹出中文窗口，无方块（顺序不可省，且**不能用 `-am`**，见 Task 1 Step 6）
- [ ] 迁移演练产出的预览文件已人工通读，6 周历史与 LaTeX 完好
- [ ] `%USERPROFILE%\study\` 下的真实文件**一个字节都没被改动**（迁移演练只用了临时目录）
- [ ] `git status` 干净，无预览文件残留、无真实档案入库

## 下一份计划

**计划 2 —— 情绪域与 AI 模块**：情绪记录文件模型、危机关键词兜底、AI 配置（含 `120`/`110` 删除保护）、OpenAI 兼容客户端（流式）、提示词加载与回退、逐次确认门、对话历史。

**计划 3 —— JavaFX 界面与打包**：8 个页面、CSS 与中文字体、禁用词扫描测试、`jpackage` 产出免安装目录。
