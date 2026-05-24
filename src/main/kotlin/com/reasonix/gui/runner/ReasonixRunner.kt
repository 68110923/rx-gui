package com.reasonix.gui.runner

/**
 * 调用 Reasonix CLI 的封装。
 *
 * 使用方式:
 *   val runner = ReasonixRunner()
 *   runner.run("解释这段代码", onResult = { result -> ... })
 *
 * 内部执行: reasonix run <prompt>
 * 超时时间: 120 秒
 */
class ReasonixRunner {

    companion object {
        /** Reasonix CLI 命令名 (假定在 PATH 中) */
        private const val COMMAND = "reasonix"

        /** 超时秒数 */
        private const val TIMEOUT_SECONDS = 120L

        /** 可自定义的 reasonix 路径 (为空则使用 PATH 中的) */
        var customPath: String? = null
    }

    // ── 公共 API ──

    /**
     * 在后台线程中调用 reasonix run，通过回调返回结果。
     *
     * @param prompt  要发送的完整提示词
     * @param onResult  结果回调 (在 EDT 调用时需要自行包裹 SwingUtilities.invokeLater)
     */
    fun run(prompt: String, onResult: (String) -> Unit) {
        Thread {
            try {
                val result = execute(prompt)
                onResult(result)
            } catch (e: Exception) {
                val errorMsg = buildString {
                    appendLine("**Error:** ${e.message ?: "Unknown error"}")
                    appendLine()
                    appendLine("> Please check:")
                    appendLine("> - Is Reasonix installed? Run `reasonix version`")
                    appendLine("> - Is `reasonix` in your PATH?")
                }
                onResult(errorMsg)
            }
        }.start()
    }

    // ── 内部执行 ──

    @Throws(Exception::class)
    private fun execute(prompt: String): String {
        val cmd = customPath ?: COMMAND

        val processBuilder = ProcessBuilder(cmd, "run", prompt)
            .redirectErrorStream(true) // stderr 合并到 stdout

        // 设置工作目录为当前项目根目录（方便 reasonix 操作文件）
        // processBuilder.directory(java.io.File(System.getProperty("user.dir")))

        val process = processBuilder.start()

        // 读取输出，最多等待 TIMEOUT_SECONDS 秒
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()

        val startTime = System.currentTimeMillis()
        val timeoutMillis = TIMEOUT_SECONDS * 1000

        try {
            var line = reader.readLine()
            while (line != null) {
                // 超时检查
                if (System.currentTimeMillis() - startTime > timeoutMillis) {
                    process.destroyForcibly()
                    return "**Timeout:** Reasonix did not respond within ${TIMEOUT_SECONDS}s."
                }
                output.appendLine(line)
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }

        val exitCode = process.waitFor()

        val result = output.toString().trim()

        if (result.isEmpty()) {
            return if (exitCode != 0) {
                "**Reasonix exited with code $exitCode** (no output)\n\nMake sure `reasonix` is installed and configured. Run `reasonix setup` to configure."
            } else {
                "(empty response)"
            }
        }

        return result
    }
}
