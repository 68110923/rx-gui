package com.reasonix.gui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.reasonix.gui.toolwindow.RxToolWindowPanel

/**
 * 侧边栏 ToolWindow 工厂。
 * 用户点击侧边栏 Reasonix 图标时，IDE 调用此方法创建面板内容。
 */
class RxToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RxToolWindowPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
