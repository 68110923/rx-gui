package com.reasonix.gui.runner

/**
 * 处理 Reasonix CLI 的安装和版本检查。
 */
class SetupRunner {

    data class CommandResult(val success: Boolean, val output: String)

    fun isInstalled(): Boolean {
        return try {
            val process = ProcessBuilder("reasonix", "version")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun getVersion(): String {
        return try {
            val process = ProcessBuilder("reasonix", "version")
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            out
        } catch (e: Exception) {
            ""
        }
    }

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
}
