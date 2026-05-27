package com.reasonix.gui.util

import com.google.gson.*
import java.io.File
import java.util.concurrent.TimeUnit

object ReasonixCli {

    private const val TIMEOUT = 15L
    private val IS_WIN = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** Locate the reasonix binary. */
    fun detect(): String? {
        // Cross-platform search paths
        val paths = mutableListOf(
            // macOS Homebrew
            "/opt/homebrew/bin/reasonix",
            "/usr/local/bin/reasonix",
            // Linux / general
            "/usr/bin/reasonix",
            "/usr/local/bin/reasonix",
            // npm global
            home(".npm-global/bin/reasonix"),
            home(".local/bin/reasonix"),
            home("node_modules/.bin/reasonix")
        )
        if (IS_WIN) {
            paths.add(0, expandEnv("%APPDATA%\\npm\\reasonix.cmd"))
            paths.add(1, expandEnv("%LOCALAPPDATA%\\npm\\reasonix.cmd"))
            paths.add(2, home("AppData\\npm\\reasonix.cmd"))
            paths.add(3, home("AppData\\Roaming\\npm\\reasonix.cmd"))
        }
        for (p in paths) if (File(p).exists()) return p

        // Fallback: which/where command
        val whichCmd = if (IS_WIN) listOf("cmd", "/c", "where reasonix 2>nul") else listOf("/bin/sh", "-c", "which reasonix 2>/dev/null")
        return try {
            val p = ProcessBuilder(whichCmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(3, TimeUnit.SECONDS)
            if (out.isNotEmpty()) out.lines().first().trim() else null
        } catch (_: Exception) { null }
    }

    /** Read the Reasonix config file. */
    fun readConfig(): JsonObject {
        val f = configFile()
        if (!f.exists()) return JsonObject()
        return try { JsonParser.parseString(f.readText()).asJsonObject } catch (_: Exception) { JsonObject() }
    }

    /** Write a value to the Reasonix config file. */
    fun writeConfig(key: String, value: String) {
        val obj = readConfig()
        obj.addProperty(key, value)
        configFile().writeText(GsonBuilder().setPrettyPrinting().create().toJson(obj))
    }

    /** Get the current editMode from Reasonix config. */
    fun getEditMode(): String = readConfig().get("editMode")?.asString ?: "auto"

    /** Get the current preset. */
    fun getPreset(): String = readConfig().get("preset")?.asString ?: "pro"

    /** Resolve the shell PATH for subprocesses (cross-platform). */
    fun getShellPath(): String {
        if (!IS_WIN) {
            // Use zsh on macOS (loads .zprofile where brew shellenv adds paths)
            val shell = if (File("/bin/zsh").exists()) "/bin/zsh" else "/bin/sh"
            return try {
                val p = ProcessBuilder(shell, "-l", "-c", "echo \$PATH")
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(3, TimeUnit.SECONDS)
                if (out.isNotEmpty()) out else System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
            } catch (_: Exception) {
                System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin"
            }
        }
        val envPath = System.getenv("PATH") ?: System.getenv("Path")
        if (envPath != null && envPath.isNotBlank()) return envPath
        return "C:\\Windows\\system32;C:\\Windows;C:\\Windows\\System32\\Wbem"
    }

    /** Run reasonix with args, return stdout. Destroys process on timeout. */
    fun run(vararg args: String): Result<String> {
        val cmd = detect() ?: return Result.failure(Exception("Reasonix not found"))
        val pb = ProcessBuilder(listOf(cmd) + args.toList())
        pb.environment()["PATH"] = getShellPath()
        return try {
            val p = pb.start()
            val finished = p.waitFor(TIMEOUT, TimeUnit.SECONDS)
            if (finished) {
                val stdout = p.inputStream.bufferedReader().readText().trim()
                val stderr = p.errorStream.bufferedReader().readText().trim()
                if (p.exitValue() == 0) Result.success(stdout)
                else Result.failure(Exception(if (stderr.isNotBlank()) stderr else stdout))
            } else {
                p.destroyForcibly()
                Result.failure(Exception("Process timed out after ${TIMEOUT}s"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Run reasonix and return both stdout and exit code. Destroys process on timeout. */
    fun runWithExit(vararg args: String): Pair<Int, String> {
        val cmd = detect() ?: return Pair(-1, "Reasonix not found")
        val pb = ProcessBuilder(listOf(cmd) + args.toList())
        pb.environment()["PATH"] = getShellPath()
        return try {
            val p = pb.start()
            val stdout = p.inputStream.bufferedReader().readText().trim()
            val stderr = p.errorStream.bufferedReader().readText().trim()
            val finished = p.waitFor(TIMEOUT, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                Pair(-1, "Process timed out after ${TIMEOUT}s")
            } else {
                Pair(p.exitValue(), (stdout + "\n" + stderr).trim())
            }
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    private fun configFile(): File = File(home(".reasonix/config.json"))

    private fun home(sub: String): String {
        val userHome = System.getProperty("user.home") ?: System.getenv("HOME") ?: System.getenv("USERPROFILE") ?: "."
        return "$userHome${File.separator}$sub"
    }

    private fun expandEnv(s: String): String {
        var r = s
        r = r.replace("%APPDATA%", System.getenv("APPDATA") ?: "")
        r = r.replace("%LOCALAPPDATA%", System.getenv("LOCALAPPDATA") ?: "")
        return r
    }
}
