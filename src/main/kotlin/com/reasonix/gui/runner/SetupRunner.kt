package com.reasonix.gui.runner

import java.io.File

/**
 * Reasonix CLI 安装检测与管理。
 */
class SetupRunner {

    data class CommandResult(val success: Boolean, val output: String)

    /** 多级检测 Reasonix 是否已安装 */
    fun isInstalled(): Boolean = detect() != null

    /** 获取版本号 */
    fun getVersion(): String = detect() ?: "(not found)"

    /** 查找 Reasonix 的完整路径 */
    private fun detect(): String? {
        // 1) Shell 登录环境 (加载 .zshrc / .zprofile, 保证 PATH 正确)
        for (shell in listOf("/bin/zsh", "/bin/bash")) {
            try {
                val p = ProcessBuilder(shell, "-l", "-c", "reasonix version")
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }

        // 2) 直接调用 (如果 IDE 进程 PATH 已包含)
        try {
            val p = ProcessBuilder("reasonix", "version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotEmpty()) return out
        } catch (_: Exception) {}

        // 3) 检查常见绝对路径
        val known = listOf(
            "/opt/homebrew/bin/reasonix",
            "/usr/local/bin/reasonix",
            System.getProperty("user.home") + "/.npm-global/bin/reasonix"
        )
        for (path in known) {
            try {
                val f = File(path)
                if (f.exists()) {
                    val p = ProcessBuilder(path, "version").redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    if (p.waitFor() == 0) return "$path\n$out"
                }
            } catch (_: Exception) {}
        }

        // 4) npx reasonix
        try {
            val p = ProcessBuilder("npx", "reasonix", "version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotEmpty()) return "(via npx)\n$out"
        } catch (_: Exception) {}

        return null
    }

    /** npm install -g reasonix */
    fun install(onLine: (String) -> Unit): CommandResult {
        return try {
            val process = ProcessBuilder("npm", "install", "-g", "reasonix")
                .redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                output.appendLine(line)
                onLine(line)
                line = reader.readLine()
            }
            reader.close()
            val exitCode = process.waitFor()
            CommandResult(exitCode == 0, output.toString().trim())
        } catch (e: Exception) {
            val msg = "Error: ${e.message}"
            onLine(msg)
            CommandResult(false, msg)
        }
    }
}
