# Reasonix GUI — JetBrains 全家桶 AI 插件规划文档

> 项目代号：`rx-gui`
> 版本：v0.1 (草案)
> 最后更新：2025-07

---

## 1. 项目概述

### 1.1 定位

**Reasonix GUI（rx-gui）** 是一款面向 JetBrains 全家桶 IDE（IntelliJ IDEA、PyCharm、GoLand、WebStorm、Android Studio 等）的侧边栏 AI 交互插件。

它的本质是对 Reasonix CLI 的 **GUI 封装套壳** —— 不修改 Reasonix 任何底层代码，通过标准协议调用 Reasonix 引擎，在 IDE 内提供图形化的 AI 交互体验。类似于 GitHub Copilot Chat 或 Cursor Chat 的交互模式，但引擎层为 Reasonix。

### 1.2 核心理念

```
┌──────────────────────────────────────────────────────────┐
│                   GUI 层 (本插件)                         │
│      JetBrains Plugin · Kotlin + Swing UI                 │
│          只管交互体验，不碰 AI 逻辑                         │
├──────────────────────────────────────────────────────────┤
│                  协议层                                   │
│     reasonix acp — stdin/stdout JSON-RPC (ACP)           │
├──────────────────────────────────────────────────────────┤
│                  引擎层 (Reasonix CLI)                    │
│     DeepSeek 模型 · 文件系统工具 · MCP 工具 · 代码分析     │
└──────────────────────────────────────────────────────────┘
```

| 层 | 职责 | 不做什么 |
|---|---|---|
| **GUI 层**（本插件） | 侧边栏 ToolWindow、消息渲染、代码选择、设置界面 | 不直接调用 LLM API |
| **协议层** | NDJSON JSON-RPC 2.0 双向通信 | 不解析 AI 逻辑 |
| **引擎层**（Reasonix） | 模型推理、工具执行、上下文管理 | 不感知 IDE UI |

### 1.3 与同类产品对比

| 产品 | 引擎 | 协议 | 定位 |
|---|---|---|---|
| GitHub Copilot | 自研 | 私有 API | 闭源全家桶 |
| Cursor | 自研 | 私有 | 独立 IDE |
| Continue.dev | 多模型 | LLM API | 开源插件 |
| **rx-gui** | **Reasonix** | **ACP (JSON-RPC)** | **开源套壳** |

### 1.4 目标用户

- 已经使用或其他方式接触 **Reasonix CLI** 的开发者
- 习惯在 JetBrains IDE 内完成全流程工作、不想切终端的开发者
- 需要快速选中代码、向 AI 提问（重构、解释、debug）的开发者

---

## 2. 技术栈

### 2.1 开发技术

| 组件 | 选择 | 说明 |
|---|---|---|
| **语言** | Kotlin + Java 17 | JetBrains 插件官方推荐 |
| **构建** | Gradle + `gradle-intellij-plugin` 2.0+ | 官方插件开发工具链 |
| **UI 框架** | Swing + Kotlin UI DSL | JetBrains 插件标准 UI |
| **JSON-RPC** | `kotlinx.serialization` + `okio` | 轻量、协程友好 |
| **协程** | `kotlinx.coroutines` | 管理 ACP 流式通信 |
| **Markdown 渲染** | IDE 内置的 `EditorHtmlRenderer` 或 flexmark | JetBrains 编辑器引擎 |
| **最低 IDE 版本** | IntelliJ Platform 2023.2+ | 覆盖绝大多数用户 |

### 2.2 外部依赖

| 依赖 | 用途 | 必需？ |
|---|---|---|
| **Reasonix CLI** (≥ 0.49.0) | ACP 协议引擎 | 是（运行期） |
| **JetBrains Plugin SDK** | 插件 API | 是（编译期） |
| **Gradle** | 构建 | 是（开发期） |
| JDK 17+ | 编译运行 | 是（开发期） |

---

## 3. 架构设计

### 3.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                      JetBrains IDE (JVM)                      │
│                                                              │
│   ┌──────────────────────────────────────────────┐           │
│   │              rx-gui Plugin                    │           │
│   │                                              │           │
│   │  ┌─────────────┐   ┌──────────────────────┐  │           │
│   │  │  Settings    │   │   Tool Window         │  │           │
│   │  │  (Config)   │   │  ┌─────────────────┐  │  │           │
│   │  └──────┬──────┘   │  │ Top Info Bar    │  │  │           │
│   │         │          │  │ • 项目名称        │  │  │           │
│   │         │ reads    │  │ • 连接状态指示灯   │  │  │           │
│   │         │          │  │ • 模型名称        │  │  │           │
│   │         ▼          │  └─────────────────┘  │  │           │
│   │  ┌─────────────┐   │  ┌─────────────────┐  │  │           │
│   │  │AcpProcess   │   │  │ Message List    │  │  │           │
│   │  │Manager      │◄──┼──┤ (滚动对话)       │  │  │           │
│   │  │             │   │  │ • AI 回复        │  │  │           │
│   │  │ • 启动/停止  │   │  │ • 用户消息       │  │  │           │
│   │  │ • 崩溃重连   │   │  │ • Markdown 渲染  │  │  │           │
│   │  └──────┬──────┘   │  │ • 代码高亮       │  │  │           │
│   │         │          │  └─────────────────┘  │  │           │
│   │         ▼          │  ┌─────────────────┐  │  │           │
│   │  ┌─────────────┐   │  │ Input Area      │  │  │           │
│   │  │ AcpClient   │   │  │ • 文本输入       │  │  │           │
│   │  │ (JSON-RPC)  │◄──┼──┤ • 已选代码上下文  │  │  │           │
│   │  └──────┬──────┘   │  │ • 发送按钮       │  │  │           │
│   │         │          │  └─────────────────┘  │  │           │
│   │         │          └──────────────────────┘  │           │
│   └─────────┼────────────────────────────────────┘           │
│             │                                                │
└─────────────┼────────────────────────────────────────────────┘
              │ stdin/stdout · NDJSON JSON-RPC
              ▼
   ┌──────────────────────┐
   │    reasonix acp       │  ← 子进程
   │    --dir /path        │
   │    --model flash|pro  │
   └──────────────────────┘
```

### 3.2 ACP 通信协议

`reasonix acp` 是集成核心。它通过 stdin/stdout 传输 NDJSON 格式的 JSON-RPC 2.0 消息。

**基础消息流：**

```
→  {"jsonrpc":"2.0","id":1,"method":"chat/completion",
     "params":{"messages":[{"role":"user","content":"解释这段代码"}]}}
←  {"jsonrpc":"2.0","id":1,"result":{"content":"这段代码是..."}}
```

**流式输出（SSE 风格）：**

```
→  {"jsonrpc":"2.0","id":1,"method":"chat/completion",
     "params":{"messages":[...],"stream":true}}
←  {"jsonrpc":"2.0","id":1,"result":{"delta":{"content":"这段"}}}
←  {"jsonrpc":"2.0","id":1,"result":{"delta":{"content":"代码"}}}
←  {"jsonrpc":"2.0","id":1,"result":{"finish":"stop"}}
```

**工具调用：**

```
←  {"jsonrpc":"2.0","id":1,"result":{"tool_calls":[
      {"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":\"src/main.kt\"}"}}
   ]}}
→  {"jsonrpc":"2.0","id":2,"method":"tool/result",
     "params":{"tool_call_id":"call_1","content":"文件内容..."}}
```

**关键 ACP 参数：**

| 参数 | 值 | 说明 |
|---|---|---|
| `--dir` | 项目根路径 | 文件系统操作的根目录 |
| `--model` | `deepseek-v4-flash` / `deepseek-v4-pro` | 模型选择 |
| `--budget` | USD 预算上限 | 可选 |
| `--preset` | auto / flash / pro | 预设模型包 |
| `--transcript` | 日志路径 | 可选，调试用 |

### 3.3 IDEA 侧边栏 ToolWindow

使用 IntelliJ Platform 标准的 `ToolWindowFactory` 扩展点：

```xml
<toolWindow id="Reasonix"
            anchor="right"
            factoryClass="com.reasonix.gui.toolwindow.RxToolWindowFactory"
            icon="/icons/pluginIcon.svg"/>
```

面板布局（从上到下）：

1. **InfoBar** — 项目名 / 连接状态（绿点/红点）/ 模型名
2. **MessageList** — 可滚动的对话消息列表
3. **CodeContextBanner** — 当前选中的代码片段（可选）
4. **InputArea** — 文本输入框 + 发送按钮

### 3.4 代码选择上下文机制

```
编辑器选中代码 → CodeSelectionHandler 监听 SelectionEvent
    ↓
提取选中文本 + 文件名 + 行号范围
    ↓
格式化上下文横幅显示在输入框上方
    ↓
用户发送消息时自动拼接：
  ┌─ src/com/example/Foo.kt (L10-L25) ─┐
  │ fun bar() {                         │
  │   return list.map { it.toString() } │
  │ }                                   │
  └─────────────────────────────────────┘
  [用户消息] "请解释这段代码"
```

实现方式：

- 注册 `EditorFactory.EVENT_TOPIC` 监听选区变化
- 使用 `Editor.getSelectionModel()` 获取选中文本
- 通过 `FileDocumentManager` 获取文件路径
- 选中文本作为 system/user message 的上下文前缀

### 3.5 子进程生命周期管理

```
状态机:
  ┌──────────┐   start()   ┌──────────┐   acp 就绪   ┌──────────┐
  │ STOPPED  │ ──────────► │ STARTING │ ───────────► │ RUNNING  │
  └──────────┘             └──────────┘               └──────────┘
       ▲                        │                          │
       │                   start() 失败                    │ 崩溃
       │                        ▼                          │
       │                   ┌──────────┐                    │
       └───────────────────│ STOPPED  │◄───────────────────┘
                           └──────────┘

重连策略:
  - 崩溃后自动重连，最多 3 次
  - 指数退避：1s → 2s → 4s
  - 超过上限后显示 "连接失败" 红色状态
  - 用户可手动点击重连

关闭行为:
  - 插件 dispose → Process.destroy()（强制杀子进程）
  - 先发 SIGTERM，等 3s 后 SIGKILL
```

---

## 4. 功能规划

### 4.1 MVP（v0.1）—— 核心功能

| 编号 | 功能 | 优先级 | 说明 |
|---|---|---|---|
| F-01 | 侧边栏 ToolWindow | P0 | 在 IDE 右侧/左侧显示插件面板 |
| F-02 | 基本信息展示 | P0 | 项目路径、Reasonix 版本、模型名 |
| F-03 | 连接状态指示灯 | P0 | 绿点（已连接）/ 红点（断开） |
| F-04 | 对话输入框 + 发送 | P0 | 底部 InputArea |
| F-05 | 流式文字回复展示 | P0 | 逐 block 显示 AI 回复 |
| F-06 | 代码选中上下文 | P0 | 自动附加选中代码到消息 |
| F-07 | Markdown 渲染 | P0 | 代码块高亮、链接、列表 |
| F-08 | 对话历史（当前会话） | P0 | 多轮消息列表，可滚动 |
| F-09 | ACP 子进程管理 | P0 | 启动/停止/重连 |
| F-10 | 深色/浅色主题适配 | P0 | 跟随 IDE LaF |

### 4.2 V2 增强

| 编号 | 功能 | 说明 |
|---|---|---|
| F-11 | 重新生成 | 最后一条 AI 回复可重新生成 |
| F-12 | 复制消息 | 每条消息有复制按钮 |
| F-13 | 文件差异预览 | Reasonix 编辑文件后，IDE 内 VCS diff |
| F-14 | 代码引用跳转 | 回复中的文件名/行号可点击跳转 |
| F-15 | 插入到编辑器 | 代码块有 "插入" 按钮 |
| F-16 | 设置页面 | 模型选择、Reasonix 路径、预算控制 |
| F-17 | MCP 服务器管理 | 添加/删除 MCP 服务器 |
| F-18 | 会话持久化 | 保存/恢复历史会话 |
| F-19 | 选中代码快速操作 | 右键菜单 "用 Reasonix 解释" |
| F-20 | 多语言 i18n | 中文/英文 界面 |

### 4.3 有意识排除的功能

| 排除项 | 原因 |
|---|---|
| 直接 LLM API 调用 | 由 Reasonix 引擎统一管理，不做重复实现 |
| 模型训练/微调 | 超出范围 |
| 独立的代码补全（TabNine 式） | 这是 Copilot 的领域，插件仅做对话交互 |
| 非 JetBrains IDE 支持 | 聚焦 JetBrains 全家桶 |

---

## 5. 项目文件结构

```
rx-gui/
├── build.gradle.kts                    # Gradle 构建配置
├── settings.gradle.kts                 # 项目设置
├── gradle.properties                   # 属性（IDE 版本等）
├── gradle/                             # Gradle Wrapper
│   └── wrapper/
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── com/reasonix/gui/
│       │       ├── RxGuiPlugin.kt              # 插件入口
│       │       ├── RxGuiBundle.kt              # i18n 资源
│       │       ├── toolwindow/
│       │       │   ├── RxToolWindowFactory.kt  # ToolWindow 工厂
│       │       │   └── RxToolWindowPanel.kt    # 主面板
│       │       ├── ui/
│       │       │   ├── InfoBar.kt              # 顶部信息栏
│       │       │   ├── MessageListPanel.kt     # 消息列表
│       │       │   ├── MessageCellRenderer.kt  # 单条消息渲染
│       │       │   ├── InputPanel.kt           # 输入区
│       │       │   ├── CodeContextBanner.kt    # 代码选择横幅
│       │       │   └── MarkdownRenderer.kt     # Markdown → HTML
│       │       ├── acp/
│       │       │   ├── AcpProtocol.kt          # JSON-RPC 消息定义
│       │       │   ├── AcpClient.kt            # ACP 通信客户端
│       │       │   ├── AcpProcessManager.kt    # 子进程生命周期
│       │       │   └── AcpStreamParser.kt      # NDJSON 流解析
│       │       ├── listener/
│       │       │   └── CodeSelectionListener.kt # 编辑器选区监听
│       │       ├── settings/
│       │       │   ├── RxGuiSettings.kt        # 配置持久化
│       │       │   └── RxGuiSettingsPage.kt    # 设置界面
│       │       └── util/
│       │           ├── FileOpener.kt           # 文件跳转
│       │           └── Icons.kt                # 图标资源
│       ├── resources/
│       │   ├── META-INF/
│       │   │   └── plugin.xml                  # 插件描述符
│       │   └── icons/
│       │       ├── pluginIcon.svg              # 插件图标 (13x13)
│       │       ├── pluginIcon@2x.svg           # 插件图标 (26x26)
│       │       ├── toolWindowIcon.svg          # 侧边栏图标
│       │       ├── send.svg                    # 发送按钮
│       │       └── reconnect.svg               # 重连按钮
│       └── messages/
│           └── RxGuiBundle.properties          # 界面字符串
├── test/
│   └── kotlin/
│       └── com/reasonix/gui/
│           ├── acp/
│           │   ├── AcpProtocolTest.kt          # 协议序列化测试
│           │   ├── AcpClientTest.kt            # 客户端测试
│           │   └── AcpStreamParserTest.kt      # 流解析测试
│           └── ui/
│               └── MarkdownRendererTest.kt     # Markdown 渲染测试
└── README.md
```

---

## 6. `plugin.xml` 核心扩展点

```xml
<idea-plugin>
    <id>com.reasonix.gui</id>
    <name>Reasonix GUI</name>
    <vendor>Reasonix</vendor>
    <description>JetBrains IDE 中与 Reasonix AI 对话</description>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- 侧边栏 ToolWindow -->
        <toolWindow
            id="Reasonix"
            anchor="right"
            factoryClass="com.reasonix.gui.toolwindow.RxToolWindowFactory"
            icon="/icons/toolWindowIcon.svg"/>

        <!-- 设置页面 -->
        <applicationConfigurable
            id="com.reasonix.gui.settings"
            displayName="Reasonix GUI"
            instance="com.reasonix.gui.settings.RxGuiSettingsPage"/>
    </extensions>

    <applicationListeners>
        <!-- IDE 关闭时清理子进程 -->
        <listener
            class="com.reasonix.gui.RxGuiPlugin"
            topic="com.intellij.ide.AppLifecycleListener"/>
    </applicationListeners>
</idea-plugin>
```

---

## 7. 开发路线图

### 阶段一：脚手架 & 协议验证（第 1 周）

| 步骤 | 任务 | 产出验证 |
|---|---|---|
| 1.1 | 初始化 Gradle 项目 | `./gradlew build` 通过 |
| 1.2 | 配置 `plugin.xml` 注册 ToolWindow | IDE 启动后侧边栏出现空白面板 |
| 1.3 | 编写 AcpProtocol.kt（消息模型 + 序列化） | 单元测试通过 |
| 1.4 | 编写 AcpClient.kt（基础请求-响应） | 能发消息并收到回复 |
| 1.5 | 编写 AcpProcessManager.kt | 子进程启动/停止正常 |

**里程碑 M1：** `reasonix acp` 子进程能在 IDE 插件中启动、收发消息。

### 阶段二：核心 UI 搭建（第 2 周）

| 步骤 | 任务 | 产出验证 |
|---|---|---|
| 2.1 | InfoBar — 状态/模型/项目名 | 显示正确信息 |
| 2.2 | InputPanel — 输入框 + 发送按钮 | 打字、点击发送触发 ACP 请求 |
| 2.3 | MessageListPanel — 滚动消息列表 | 多条消息正确显示 |
| 2.4 | MessageCellRenderer — 消息气泡 | 用户/AI 消息区分显示 |
| 2.5 | MarkdownRenderer — 代码高亮 | 代码块有颜色 |
| 2.6 | 流式响应渲染 | 逐块显示回复内容 |

**里程碑 M2：** 完整的对话交互流程：输入 → ACP 调用 → 流式展示回复。

### 阶段三：代码选择 & 上下文（第 3 周）

| 步骤 | 任务 | 产出验证 |
|---|---|---|
| 3.1 | CodeSelectionListener — 选区监听 | 选中代码时触发事件 |
| 3.2 | CodeContextBanner — 选择横幅 | 显示选中文件+行号 |
| 3.3 | 上下文拼接到发送消息 | 消息体包含代码上下文 |
| 3.4 | FileOpener — 路径点击跳转 | 点击文件名跳转到编辑器 |

**里程碑 M3：** 选中代码 → 问 AI → 得到带文件引用的回复。

### 阶段四：设置 & 体验打磨（第 4 周）

| 步骤 | 任务 | 产出验证 |
|---|---|---|
| 4.1 | RxGuiSettings — 配置持久化 | 修改后重启保持 |
| 4.2 | RxGuiSettingsPage — 设置 UI | 可在设置中修改模型/路径 |
| 4.3 | 主题适配 | 深色/浅色 UI 正常 |
| 4.4 | 进程异常处理 | 崩溃重连、状态提示 |
| 4.5 | 重连按钮 + 手动操作 | 断开后可手动恢复 |
| 4.6 | 首次启动引导 | 检测 reasonix 是否安装 |

**里程碑 M4：** 可日常使用的 MVP 版本。

---

## 8. 关键设计决策记录

| 编号 | 决策 | 选项 | 选择理由 |
|---|---|---|---|
| ADR-01 | 协议选择 | MCP vs ACP | ACP 是 Reasonix 原生协议，支持工具调用和流式输出 |
| ADR-02 | UI 框架 | Swing vs JCEF vs Compose | Swing 是 JetBrains Plugin SDK 标准，兼容性最好 |
| ADR-03 | Markdown 渲染 | IDE 内置渲染器 vs flexmark | 先用 IDE 内置 `EditorHtmlRenderer`，不足时换 flexmark |
| ADR-04 | 子进程管理 | JDK ProcessBuilder 单独管理 | 无需额外依赖，完全可控 |
| ADR-05 | 通信序列化 | kotlinx.serialization JSON | 协程原生支持，编译期类型安全 |
| ADR-06 | 最低 IDE 版本 | 2023.2 | 覆盖 ~90% 用户，API 稳定 |

---

## 9. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|
| `reasonix acp` 协议变更 | 中 | 高 | 关注 CHANGELOG，写协议版本协商 |
| ACP 子进程内存泄漏 | 低 | 中 | 设置 `--budget` 限制，定期重启进程 |
| JetBrains API 跨版本兼容 | 中 | 中 | 使用 `@ApiStatus` 注解隔离，CI 多版本测试 |
| Reasonix 未安装 | 高 | 高 | 首次启动引导检测，提供安装命令 |
| Windows 进程管理差异 | 中 | 中 | 三平台（Win/Mac/Linux）测试 CI |
| 大型项目中文件操作延迟 | 低 | 中 | 设置文件排除规则，加载状态提示 |

---

## 10. 测试策略

| 层级 | 覆盖范围 | 工具 |
|---|---|---|
| **单元测试** | ACP 协议序列化/反序列化、流解析、Markdown 渲染 | JUnit 5 + kotlin.test |
| **集成测试** | ACP 客户端连接、子进程启停 | Testcontainers (模拟进程) |
| **UI 测试** | ToolWindow 面板渲染 | IntelliJ UI Test Framework |
| **手动测试** | 全功能流程、JetBrains 多版本兼容 | IDE 本地运行 + CI |

---

## 11. 发布 & 分发

| 渠道 | 方式 |
|---|---|
| **JetBrains Marketplace** | 正式发布渠道 |
| **GitHub Releases** | 预发布版 + 插件 ZIP |
| **本地安装** | `File → Settings → Plugins → Install from Disk` |

版本号规范：`v0.1.0`（语义化版本，遵循 JetBrains 插件市场规范）

---

## 12. 附录

### 12.1 参考资源

- [IntelliJ Platform Plugin SDK 文档](https://plugins.jetbrains.com/docs/intellij/)
- [JetBrains Plugin 示例：`intellij-sdk-code-samples`](https://github.com/JetBrains/intellij-sdk-code-samples)
- [Reasonix CLI 文档](https://reasonix.com)（如需）
- [JSON-RPC 2.0 规范](https://www.jsonrpc.org/specification)

### 12.2 术语表

| 术语 | 解释 |
|---|---|
| **ACP** | Agent Client Protocol — Reasonix 的 stdin/stdout JSON-RPC 协议 |
| **MCP** | Model Context Protocol — 模型工具调用标准协议 |
| **ToolWindow** | JetBrains IDE 侧边栏/底部面板 |
| **NDJSON** | Newline Delimited JSON — 每行一个 JSON 对象 |
| **JetBrains Marketplace** | JetBrains 插件官方市场 |
