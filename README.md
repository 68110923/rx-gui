# Reasonix GUI — JetBrains 全家桶 AI 插件

一个轻量级的 JetBrains 全家桶插件，在 IDE 侧边栏中与 Reasonix AI 对话。

## 功能

- 📌 **侧边栏对话** — 在 IDE 右侧面板中与 Reasonix 聊天
- 📎 **选中代码提问** — 在编辑器中选中代码，自动附带到问题中
- 📝 **Markdown 渲染** — AI 回复支持代码高亮、粗体、链接等
- 🔌 **即插即用** — 只要你的电脑上装了 Reasonix CLI，打开插件就能用

## 前置条件

1. **JDK 17+** — 用于编译和运行插件
2. **IntelliJ IDEA**（或 PyCharm 等任意 JetBrains IDE）— 用来调试和安装插件
3. **Reasonix CLI** — `reasonix` 命令在 PATH 中可用

```bash
# 确认 Reasonix 已安装
reasonix version
# 应输出: reasonix 0.49.0 或更高
```

## 快速开始

### 1. 在 IDE 中打开项目

用 IntelliJ IDEA / PyCharm 打开这个目录，IDE 会自动识别为 Gradle 项目。

### 2. 运行调试

```bash
./gradlew runIde
```

这会启动一个新的 IDE 实例，里面已经装好了这个插件。侧边栏会出现 "Reasonix" 图标。

### 3. 打包插件

```bash
./gradlew buildPlugin
```

打包后的插件在 `build/distributions/rx-gui-0.1.0.zip`。

### 4. 安装到你的 IDE

- **方式 A**: `File → Settings → Plugins → ⚙ Install Plugin from Disk` → 选择上面的 ZIP
- **方式 B**: 直接复制到 IDE 的 plugins 目录

## 项目结构

```
rx-gui/
├── build.gradle.kts              # Gradle 构建配置
├── settings.gradle.kts
├── gradle.properties
├── src/main/
│   ├── kotlin/com/reasonix/gui/
│   │   ├── toolwindow/
│   │   │   ├── RxToolWindowFactory.kt   # 侧边栏工厂（注册入口）
│   │   │   └── RxToolWindowPanel.kt     # 聊天面板 UI
│   │   └── runner/
│   │       └── ReasonixRunner.kt        # 调用 reasonix CLI
│   └── resources/
│       ├── META-INF/plugin.xml          # 插件描述符
│       └── icons/
│           ├── pluginIcon.svg
│           └── toolWindowIcon.svg
└── PLAN.md                       # 项目规划文档
```

## 工作原理

```
┌─ IDE ─────────────────────────────────────┐
│                                            │
│  你的代码 (编辑器)         侧边栏面板         │
│  ┌──────────────┐     ┌──────────────────┐ │
│  │              │     │ 状态: ● 已连接    │ │
│  │ def foo():   │     │                  │ │
│  │   ...        │────►│ [对话消息列表]     │ │
│  │              │选中  │ • 用户问题        │ │
│  │              │代码  │ • AI 回复         │ │
│  └──────────────┘     │                  │ │
│                       │ [输入框] [发送]   │ │
│                       └────────┬─────────┘ │
│                                │            │
└────────────────────────────────┼────────────┘
                                 │
                    Process("reasonix run 问题+代码")
                                 │
                                 ▼
                        ┌────────────────┐
                        │  Reasonix CLI  │
                        │  (你的电脑上)   │
                        └────────────────┘
```

## 实现细节

- **UI**: Kotlin Swing，JEditorPane 渲染 HTML 格式消息
- **异步**: 对话请求在后台线程执行，不阻塞 UI
- **Markdown**: 自实现轻量转换器，支持代码块、粗体、斜体、链接、标题
- **兼容性**: 只依赖 `com.intellij.modules.platform`，在所有 JetBrains IDE 可用

## 常见问题

### Q: 为什么不用 JCEF（内嵌浏览器）？
A: 不是所有 IDE/版本都支持 JCEF，纯 Swing 兼容性最好，代码也更少。

### Q: 选中代码没反应？
A: 插件每 0.5 秒检查一次编辑器选区。选中代码后，底部会出现橙色横幅提示已捕获。

### Q: 回复很慢？
A: Reasonix 处理需要时间，默认超时 120 秒。状态栏会显示 "Thinking..." 提示正在处理。

### Q: 能切换模型吗？
A: 当前版本默认使用 `reasonix run`（flash 模式）。后续版本会加入模型选择。
