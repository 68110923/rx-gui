package com.reasonix.gui.runner

/**
 * 处理 Reasonix CLI 的安装和配置。
 * 所有操作在后台线程中执行，通过回调返回结果。
 */
class SetupRunner {

    data class CommandResult(val success: Boolean, val output: String)

    // ── 公共 API ──

    /** 检查 Reasonix 是否已安装 */
    fun isInstalled(): Boolean {
        return try {
            val process = ProcessBuilder("reasonix", "version")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /** 检查 API Key 是否已配置 */
    fun hasApiKey(): Boolean {
        return try {
            val process = ProcessBuilder("reasonix", "config", "get", "reasonix.apiKey")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.isNotEmpty() && output != "null" && output.length > 5
        } catch (e: Exception) {
            false
        }
    }

    /** 安装 Reasonix (npm install -g reasonix) */
    fun install(onLine: (String) -> Unit): CommandResult {
        return try {
            val process = ProcessBuilder("npm", "install", "-g", "reasonix")
                .redirectErrorStream(true)
                .start()

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

    /** 设置 API Key */
    fun setApiKey(key: String): CommandResult {
        return try {
            val process = ProcessBuilder("reasonix", "config", "set", "reasonix.apiKey", key)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            CommandResult(exitCode == 0, output)
        } catch (e: Exception) {
            CommandResult(false, "Error: ${e.message}")
        }
    }
}
