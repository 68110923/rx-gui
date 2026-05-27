# RX GUI — JetBrains Reasonix AI 插件

在 JetBrains IDE 侧边栏中直接与 Reasonix AI 对话，支持流式响应、Markdown 渲染、代码 Diff 审阅、费用/余额实时显示。

## 功能

- **流式 AI 对话** — 实时 Markdown 渲染 + 代码语法高亮
- **审查模式** — 文件编辑交互式 Diff（接受 / 拒绝）
- **模式切换** — review / auto / yolo 一键切换
- **预设 & 推理深度** — auto / flash / pro 预设 + low~max 推理深度
- **斜杠命令** — `/model`、`/mode`、`/preset`、`/effort`、`/clear`、`/help`
- **费用 & 余额** — 消费金额 + 余额实时显示
- **上下文 & 缓存** — 上下文用量 + 缓存命中率
- **选中代码提问** — 编辑器中选中代码自动附带到问题中
- **跨平台** — macOS / Windows / Linux

## 前置条件

1. JDK 21+（编译用，项目自带 `jdk21/`）
2. JetBrains IDE（IntelliJ IDEA / PyCharm 等）
3. Reasonix CLI (`npm install -g reasonix`)
4. DeepSeek API Key（`reasonix setup`）

```bash
reasonix version  # 确认安装
```

## 快速开始

```bash
# 调试运行
./gradlew runIde

# 打包插件
./gradlew buildPlugin
# → build/distributions/rx-gui-0.2.0.zip

# 安装: IDE → Settings → Plugins → ⚙ → Install Plugin from Disk → 选择 zip
```

## 项目结构

```
rx-gui/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── src/main/
│   ├── kotlin/com/reasonix/gui/
│   │   ├── acp/AcpClient.kt              # ACP 协议客户端 (JSON-RPC)
│   │   ├── ui/ChatPanel.kt               # 聊天面板 (JBCefBrowser)
│   │   ├── diff/InteractiveDiffManager.kt # 交互式 Diff 审阅
│   │   └── util/
│   │       ├── ReasonixCli.kt            # CLI 调用 & 配置
│   │       └── ReasonixApiClient.kt      # REST API 客户端
│   └── resources/
│       ├── META-INF/plugin.xml
│       ├── chat.html                     # 聊天前端 UI
│       └── icons/                        # 插件图标
└── build/distributions/                  # 打包输出
```

## 技术实现

- **前端**: Chromium (JBCefBrowser) 渲染，自定义 Markdown 解析器（行级状态机）
- **后端**: Kotlin，ACP 协议 (stdin/stdout NDJSON JSON-RPC 2.0)
- **数据获取**: REST API (`/api/overview`) 获取费用/余额，`usage.jsonl` 作为后备
- **兼容性**: 依赖 `com.intellij.modules.platform`，全 JetBrains IDE 可用
