package com.reasonix.gui.runner

import java.io.File

class ReasonixRunner {

    companion object {
        private const val TIMEOUT_SEC = 180L
    }

    private fun findExe(name: String, args: String = "--version"): String? {
        // login shell
        for (shell in listOf("/bin/zsh", "/bin/bash")) {
            try {
                val p = ProcessBuilder(shell, "-l", "-c", "which $name")
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }
        // interactive shell (loads .zshrc for nvm/fnm)
        for (shell in listOf("/bin/zsh", "/bin/bash")) {
            try {
                val p = ProcessBuilder(shell, "-i", "-c", "which $name")
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out.isNotEmpty()) return out
            } catch (_: Exception) {}
        }
        // direct
        try {
            val p = ProcessBuilder(name, args).redirectErrorStream(true).start()
            if (p.waitFor() == 0) return name
        } catch (_: Exception) {}
        // known paths
        for (dir in listOf("/opt/homebrew/bin", "/usr/local/bin", System.getProperty("user.home") + "/.npm-global/bin")) {
            val f = File(dir, name); if (f.exists()) return f.absolutePath
        }
        return null
    }

    fun run(prompt: String, onResult: (String) -> Unit) {
        Thread {
            try {
                // 先检查 node
                if (findExe("node") == null) {
                    onResult("❌ **Node.js not found.**\n\nPlease install Node.js first: https://nodejs.org")
                    return@Thread
                }
                val exe = findExe("reasonix")
                if (exe == null) {
                    onResult("❌ **Reasonix CLI not found.**\n\nInstall: Settings → Core → Install Reasonix")
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
        // 用交互 shell 执行，保证 nvm/fnm 加载了 node
        val safePrompt = prompt.replace("'", "'\\''")
        val pb = ProcessBuilder("/bin/zsh", "-i", "-c", "reasonix run '$safePrompt'")
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
        return output.toString().trim().ifEmpty { "(empty)" }
    }
}
