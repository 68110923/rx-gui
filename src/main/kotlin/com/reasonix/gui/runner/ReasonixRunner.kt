package com.reasonix.gui.runner

import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

class ReasonixRunner {

    companion object {
        private const val TIMEOUT_SEC = 120L
    }

    /** 快速检测 reasonix 是否可用 */
    private fun detect(): String? {
        // 1) 直接调
        if (tryRun("reasonix", "version")) return "reasonix"
        // 2) 已知路径
        for (path in listOf("/opt/homebrew/bin/reasonix", "/usr/local/bin/reasonix", home(".npm-global/bin/reasonix"))) {
            if (File(path).exists()) return path
        }
        // 3) shell 登录环境
        for (shell in listOf("/bin/zsh", "/bin/bash")) {
            try {
                val p = ProcessBuilder(shell, "-l", "-c", "which reasonix 2>/dev/null").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(3, TimeUnit.SECONDS)
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }
        return null
    }

    private fun tryRun(vararg cmd: String): Boolean {
        return try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS) == true
        } catch (_: Exception) { false }
    }

    private fun home(sub: String) = System.getProperty("user.home") + "/" + sub

    fun run(prompt: String, onResult: (String) -> Unit) {
        Thread {
            val result = try {
                val reasonix = detect()
                if (reasonix == null) "❌ Reasonix CLI not found."
                else execute(reasonix, prompt)
            } catch (e: Exception) {
                "❌ ${e.message}"
            }
            onResult(result)
        }.start()
    }

    private fun execute(reasonix: String, prompt: String): String {
        val pb = ProcessBuilder(reasonix, "run")
        pb.redirectErrorStream(true)
        val proc = pb.start()

        // 通过 stdin 送入 prompt，避免 shell 转义问题
        OutputStreamWriter(proc.outputStream).use { writer ->
            writer.write(prompt)
            writer.flush()
        }
        proc.outputStream.close()

        val output = StringBuilder()
        val reader = proc.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000
        try {
            var line = reader.readLine()
            while (line != null) {
                if (System.currentTimeMillis() > deadline) {
                    proc.destroyForcibly()
                    return "❌ Timeout after ${TIMEOUT_SEC}s"
                }
                output.appendLine(line)
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }
        proc.waitFor(5, TimeUnit.SECONDS)
        return output.toString().trim().ifEmpty { "(empty)" }
    }
}
