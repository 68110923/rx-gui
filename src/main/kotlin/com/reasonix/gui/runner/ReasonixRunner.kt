package com.reasonix.gui.runner

import java.io.File

/**
 * 调用 Reasonix CLI。
 */
class ReasonixRunner {

    companion object {
        private const val TIMEOUT_SEC = 180L
    }

    /** 返回 Reasonix 可执行路径，找不到则 null */
    private fun findReasonix(): String? {
        // shell 登录环境
        for (shell in listOf("/bin/zsh", "/bin/bash")) {
            try {
                val p = ProcessBuilder(shell, "-l", "-c", "which reasonix")
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }

        // 直接调用
        try {
            val p = ProcessBuilder("reasonix", "version").redirectErrorStream(true).start()
            if (p.waitFor() == 0) return "reasonix"
        } catch (_: Exception) {}

        // 已知路径
        for (path in listOf(
            "/opt/homebrew/bin/reasonix",
            "/usr/local/bin/reasonix",
            System.getProperty("user.home") + "/.npm-global/bin/reasonix"
        )) {
            if (File(path).exists()) return path
        }

        return null
    }

    fun run(prompt: String, onResult: (String) -> Unit) {
        Thread {
            try {
                val exe = findReasonix()
                if (exe == null) {
                    onResult("❌ Reasonix CLI not found.\\n\\nPlease install it from the **Settings → Core** tab.")
                    return@Thread
                }
                val result = execute(exe, prompt)
                onResult(result)
            } catch (e: Exception) {
                onResult("❌ ${e.message}")
            }
        }.start()
    }

    private fun execute(exe: String, prompt: String): String {
        val pb = if (exe.startsWith("/")) {
            ProcessBuilder(exe, "run", prompt)
        } else {
            ProcessBuilder("/bin/zsh", "-l", "-c", "reasonix run '$prompt'")
        }
        pb.redirectErrorStream(true)

        val process = pb.start()
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000

        try {
            var line = reader.readLine()
            while (line != null) {
                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly()
                    return "❌ Timeout after ${TIMEOUT_SEC}s"
                }
                output.appendLine(line)
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }

        process.waitFor()
        val result = output.toString().trim()
        return result.ifEmpty { "(empty)" }
    }
}
