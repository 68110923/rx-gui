# RX GUI — 分阶段开发计划

## Phase 0: 核心通信 ✅ (已完成)

- [x] 基础 Gradle 构建 (`./gradlew buildPlugin`)
- [x] 侧边栏 ToolWindow (Chat + Settings 双页)
- [x] `reasonix run` 调用（stdin 传参 + 超时）
- [x] Reasonix 安装检测 + 一键安装
- [x] Markdown 渲染（代码高亮、粗体、标题）
- [x] 选中代码附带到问题
- [x] JBCefBrowser 聊天 UI（圆角气泡、动画、时间戳）

**问题：**
- ⚠️ JBCefBrowser 在某些 IDE 版本可能空白 — 待加 JEditorPane 回退方案
- ⚠️ Reasonix 启动后首次对话可能慢 — 待加 loading 状态细化

---

## Phase 1: 稳定性加固 🔧 (当前)

- [ ] 编译产出自检：`./gradlew buildPlugin` 成功后校验 ZIP 完整性
- [ ] JBCefBrowser 不可用时自动降级到纯 Java 渲染
- [ ] 消息发送队列：防止用户快速连发导致状态混乱
- [ ] 错误分类展示：网络错误 / 超时 / Reasonix 内部错误 / 无安装
- [ ] 日志输出到 IDE 事件日志 (`com.intellij.notification`)
- [ ] 测试：在 PyCharm / IDEA 2024.2+ 上完成一次完整对话

---

## Phase 2: 体验优化 ✨

- [ ] **流式输出** — `reasonix run` 支持 streaming，逐字显示 AI 回复
- [ ] **停止生成按钮** — 发送后 Send → Stop，可中断长回复
- [ ] **消息操作** — 复制、重新生成、删除单条消息
- [ ] **主题适配** — 跟随 IDE 亮/暗色主题自动切换
- [ ] **字体同步** — 聊天气泡字体跟随 IDE 编辑器字体
- [ ] **快捷键** — `Cmd+Shift+R` 打开 RX GUI 面板 / 聚焦输入框
- [ ] **国际化** — 中英文界面切换

---

## Phase 3: 功能扩展 🚀

- [ ] **对话历史** — 保存/恢复历史会话
- [ ] **多会话** — 新建/切换多个对话
- [ ] **文件上下文** — `@file` 引用项目文件
- [ ] **Diff 预览** — AI 建议的代码改动右侧对比
- [ ] **Agent 模式** — 自动执行多步骤任务（读文件 → 修改 → 运行测试）
- [ ] **MCP 支持** — 插件内配置 MCP server

---

## Phase 4: 发布准备 📦

- [ ] 插件签名
- [ ] JetBrains Marketplace 上架
- [ ] 自动更新支持
- [ ] 使用统计（匿名）
- [ ] 用户反馈入口

---

## 当前文件结构

```
src/main/
├── kotlin/com/reasonix/gui/
│   ├── runner/
│   │   ├── ReasonixRunner.kt   ← stdin 调用 reasonix
│   │   └── SetupRunner.kt      ← 安装/检测
│   └── toolwindow/
│       ├── RxToolWindowFactory.kt  ← 侧边栏注册
│       └── RxToolWindowPanel.kt    ← 主 UI (Chat + Settings)
└── resources/
    ├── META-INF/plugin.xml     ← 插件描述
    └── icons/
        ├── pluginIcon.svg      ← 40x40
        └── toolWindowIcon.svg  ← 13x13
```
