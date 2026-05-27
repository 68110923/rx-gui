package com.reasonix.gui.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class ReasonixApiClient(private val baseUrl: String, private val token: String) {

    fun getOverview(): JsonObject? { return get("/api/overview") }

    fun getBalance(): String? {
        val ov = getOverview() ?: return null
        val stats = ov.getAsJsonObject("stats") ?: return null
        val balanceArr = stats.getAsJsonArray("balance")
        if (balanceArr != null && balanceArr.size() > 0) {
            val total = balanceArr[0].asJsonObject.get("total_balance")?.asString ?: return null
            if (total.isNotEmpty()) return total
        }
        val cb = ov.getAsJsonObject("cockpit")?.getAsJsonObject("balance")
        return cb?.get("total")?.asString
    }

    fun getCost(): Double? {
        return getOverview()?.getAsJsonObject("stats")?.get("totalCostUsd")?.asDouble
    }

    fun getCachePct(): Double? {
        return getOverview()?.getAsJsonObject("stats")?.get("cacheHitRatio")?.asDouble
    }

    private fun get(path: String): JsonObject? {
        return try {
            val conn = open("$baseUrl$path?token=$token", "GET")
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) { null }
    }

    private fun open(urlStr: String, method: String): HttpURLConnection {
        val conn = URI.create(urlStr).toURL().openConnection() as HttpURLConnection
        conn.setRequestProperty("X-Reasonix-Token", token)
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.requestMethod = method
        return conn
    }

    companion object {
        private val PORTS = intArrayOf(49321, 49322, 49323)
        private val URL_RE = Regex("""http://127\.0\.0\.1:(\d+)/\?token=([a-f0-9]+)""")

        fun startAndDetect(projectDir: String): ReasonixApiClient? {
            val cmd = ReasonixCli.detect() ?: return null
            for (port in PORTS) {
                tryStart(cmd, projectDir, port)?.let { return it }
            }
            return null
        }

        private fun tryStart(cmd: String, projectDir: String, port: Int): ReasonixApiClient? {
            return try {
                val pb = ProcessBuilder(cmd, "code", "--dashboard-port", port.toString(), projectDir)
                pb.redirectErrorStream(true)
                // Redirect stdin from empty to prevent TUI from blocking
                pb.redirectInput(ProcessBuilder.Redirect.PIPE)
                val process = pb.start()
                // Close stdin immediately so TUI doesn't wait for input
                process.outputStream.close()

                // Collect stdout in a background thread
                val output = AtomicReference("")
                val reader = process.inputStream.bufferedReader()
                val readThread = Thread({
                    try {
                        val sb = StringBuilder()
                        val buf = CharArray(4096)
                        var n: Int
                        while (reader.read(buf).also { n = it } != -1) {
                            sb.append(buf, 0, n)
                            // Stop reading once we have the URL line
                            if (sb.contains("token=")) break
                        }
                        output.set(sb.toString())
                    } catch (_: Exception) {}
                }, "rx-api-read")
                readThread.isDaemon = true
                readThread.start()

                // Wait for the URL to appear (up to 20s)
                val deadline = System.currentTimeMillis() + 20000
                var token: String? = null
                while (System.currentTimeMillis() < deadline && token == null) {
                    val text = output.get()
                    val m = URL_RE.find(text)
                    if (m != null) token = m.groupValues[2]
                    else Thread.sleep(400)
                }

                if (token != null) {
                    readThread.interrupt()
                    return ReasonixApiClient("http://127.0.0.1:$port", token)
                }
                process.destroyForcibly()
                null
            } catch (_: Exception) { null }
        }
    }
}
