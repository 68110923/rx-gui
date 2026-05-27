package com.reasonix.gui.acp

import com.google.gson.*
import com.reasonix.gui.util.ReasonixCli
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

// ── Session update types ──

sealed interface SessionUpdate
data class MessageChunk(val content: String) : SessionUpdate
data class ThoughtChunk(val content: String) : SessionUpdate
data class ToolCall(val toolCallId: String, val title: String, val rawInput: String?) : SessionUpdate
data class ToolCallUpdate(val toolCallId: String, val status: String, val content: String?) : SessionUpdate
data class PlanUpdate(val entries: List<PlanEntry>) : SessionUpdate
data class SessionDone(val stopReason: String) : SessionUpdate
data class SessionError(val error: String) : SessionUpdate
data class PlanEntry(val title: String, val status: String, val priority: String?)

// ── Permission request ──

data class PermissionRequest(
    val callbackId: String,
    val sessionId: String,
    val toolCallId: String,
    val toolTitle: String,
    val options: List<String>,
    val rawInput: String? = null
)

// ── Request results ──

data class InitResult(val protocolVersion: Int, val agentInfo: JsonObject?)
data class SessionResult(val sessionId: String)
data class PromptResult(val stopReason: String)

// ── Client callbacks ──

interface AcpClientCallbacks {
    fun onUpdate(sessionId: String, update: SessionUpdate)
    fun onPermission(request: PermissionRequest)
    fun onStatus(connected: Boolean, info: String?)
    fun onStderr(line: String)
}

// ── ACP Client ──

class AcpClient {
    private val NEXT_ID = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement>>()
    private val callbacks = mutableListOf<AcpClientCallbacks>()
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var connected = false
    @Volatile private var cachedCmd: String? = null
    @Volatile private var sessionId: String? = null

    fun addCallback(cb: AcpClientCallbacks) { synchronized(callbacks) { callbacks.add(cb) } }
    fun removeCallback(cb: AcpClientCallbacks) { synchronized(callbacks) { callbacks.remove(cb) } }
    fun isConnected() = connected
    fun getSessionId() = sessionId

    // ── Connection lifecycle ──

    fun connect(projectDir: String) {
        if (connected) return
        Thread({
            try {
                val cmd = ReasonixCli.detect() ?: run { notifyStatus(false, "Reasonix not found"); return@Thread }
                notifyStatus(false, "Connecting...")
                val pb = ProcessBuilder(cmd, "acp", "--dir", projectDir)
                pb.redirectErrorStream(false)
                pb.environment()["PATH"] = ReasonixCli.getShellPath()
                val p = pb.start()
                process = p
                writer = p.outputStream.bufferedWriter()
                Thread { readLoop(p.inputStream) }.apply { isDaemon = true }.start()
                Thread { readStderr(p.errorStream) }.apply { isDaemon = true }.start()
                Thread { waitForExit(p) }.apply { isDaemon = true }.start()
                val init = initialize().get(15, TimeUnit.SECONDS)
                notifyStatus(true, init.agentInfo?.get("name")?.asString ?: "Reasonix")
            } catch (e: Exception) {
                notifyStatus(false, e.message ?: "Connection failed")
                cleanup()
            }
        }, "rx-acp-connect").apply { isDaemon = true }.start()
    }

    fun disconnect() {
        cleanup()
        notifyStatus(false, "Disconnected")
    }

    fun newSession(cwd: String, model: String? = null): CompletableFuture<SessionResult> {
        val future = sendRequest("session/new", buildJson {
            put("cwd", cwd)
            if (model != null) put("model", model)
        })
        return future.thenApply { SessionResult(it.asJsonObject.get("sessionId").asString).also { sessionId = it.sessionId } }
    }

    fun sendPrompt(text: String, context: String? = null, mode: String? = null, model: String? = null, effort: String? = null): CompletableFuture<PromptResult> {
        val sid = sessionId ?: return CompletableFuture.failedFuture(IllegalStateException("No session"))
        val blocks = JsonArray().apply {
            add(buildJson { put("type", "text"); put("text", text) })
            if (context != null) add(buildJson { put("type", "text"); put("text", context) })
        }
        val future = sendRequest("session/prompt", buildJson {
            put("sessionId", sid)
            put("prompt", blocks)
            if (mode != null) put("mode", mode)
            if (model != null) put("model", model)
            if (effort != null) put("effort", effort)
        })
        return future.thenApply { PromptResult(it.asJsonObject.get("stopReason").asString) }
            .orTimeout(120, TimeUnit.SECONDS)
    }

    fun cancelSession() {
        val sid = sessionId ?: return
        sendNotification("session/cancel", buildJson { put("sessionId", sid) })
    }

    fun respondPermission(callbackId: String, option: String) {
        sendNotification("permission/response", buildJson {
            put("callbackId", callbackId)
            put("option", option)
        })
    }

    // ── JSON-RPC I/O ──

    private fun initialize(): CompletableFuture<InitResult> {
        val future = sendRequest("initialize", buildJson {
            put("protocolVersion", 1)
            put("clientInfo", buildJson { put("name", "rx-gui"); put("version", "0.2.0") })
        })
        return future.thenApply {
            val obj = it.asJsonObject
            InitResult(obj.get("protocolVersion").asInt, obj.getAsJsonObject("agentInfo"))
        }
    }

    private fun sendRequest(method: String, params: JsonObject): CompletableFuture<JsonElement> {
        val w = writer ?: return CompletableFuture.failedFuture(IllegalStateException("Not connected"))
        val id = NEXT_ID.getAndIncrement()
        val future = CompletableFuture<JsonElement>()
        pending[id] = future
        val msg = buildJson { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        try { synchronized(w) { w.write(MSG_GSON.toJson(msg)); w.newLine(); w.flush() } }
        catch (e: Exception) { pending.remove(id); future.completeExceptionally(e) }
        return future
    }

    private fun sendNotification(method: String, params: JsonObject) {
        val w = writer ?: return
        val msg = buildJson { put("jsonrpc", "2.0"); put("method", method); put("params", params) }
        try { synchronized(w) { w.write(MSG_GSON.toJson(msg)); w.newLine(); w.flush() } }
        catch (_: Exception) {}
    }

    // ── Stdin/Stdout readers ──

    private fun readLoop(stream: InputStream) {
        try {
            stream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try { handleMessage(JsonParser.parseString(line).asJsonObject) }
                catch (e: Exception) { System.err.println("[rx-gui] parse error: ${e.message}") }
            }
        } catch (_: Exception) {}
    }

    private fun readStderr(stream: InputStream) {
        try { stream.bufferedReader().forEachLine { synchronized(callbacks) { callbacks.forEach { cb -> cb.onStderr(it) } } } }
        catch (_: Exception) {}
    }

    private fun waitForExit(p: Process) {
        try {
            p.waitFor()
            connected = false
            notifyStatus(false, "Process exited (${p.exitValue()})")
        } catch (_: Exception) {}
    }

    // ── Message dispatch ──

    private fun handleMessage(msg: JsonObject) {
        when {
            msg.has("id") && msg.has("result") -> pending.remove(msg.get("id").asInt)?.complete(msg.get("result"))
            msg.has("id") && msg.has("error") -> pending.remove(msg.get("id").asInt)
                ?.completeExceptionally(RuntimeException(msg.getAsJsonObject("error").get("message").asString))
            msg.has("method") -> handleNotification(msg)
        }
    }

    private fun handleNotification(msg: JsonObject) {
        when (msg.get("method").asString) {
            "session/update" -> {
                val params = msg.getAsJsonObject("params")
                val sid = params.get("sessionId")?.asString ?: return
                val update = parseSessionUpdate(params.getAsJsonObject("update")) ?: return
                synchronized(callbacks) { callbacks.forEach { it.onUpdate(sid, update) } }
            }
            "permission/request" -> {
                val params = msg.getAsJsonObject("params")
                val options = mutableListOf<String>()
                params.getAsJsonArray("options")?.forEach { options.add(it.asString) }
                val toolCallObj = params.getAsJsonObject("toolCall")
                val req = PermissionRequest(
                    callbackId = params.get("callbackId").asString,
                    sessionId = params.get("sessionId").asString,
                    toolCallId = toolCallObj.get("toolCallId").asString,
                    toolTitle = toolCallObj.get("title").asString,
                    options = options,
                    rawInput = toolCallObj.get("rawInput")?.toString()
                )
                synchronized(callbacks) { callbacks.forEach { it.onPermission(req) } }
            }
        }
    }

    private fun parseSessionUpdate(obj: JsonObject): SessionUpdate? = try {
        when (obj.get("sessionUpdate").asString) {
            "agent_message_chunk" -> {
                val content = obj.getAsJsonObject("content")
                MessageChunk(content?.get("text")?.asString ?: content?.toString() ?: "")
            }
            "agent_thought_chunk" -> {
                val content = obj.getAsJsonObject("content")
                ThoughtChunk(content?.get("text")?.asString ?: content?.toString() ?: "")
            }
            "tool_call" -> ToolCall(
                obj.get("toolCallId").asString,
                obj.get("title").asString,
                obj.get("rawInput")?.asString
            )
            "tool_call_update" -> ToolCallUpdate(
                obj.get("toolCallId").asString,
                obj.get("status").asString,
                extractToolOutput(obj)
            )
            "plan" -> {
                val entries = obj.getAsJsonArray("entries")?.map {
                    val e = it.asJsonObject
                    PlanEntry(e.get("title").asString, e.get("status").asString, e.get("priority")?.asString)
                } ?: emptyList()
                PlanUpdate(entries)
            }
            "done" -> SessionDone(obj.get("stopReason").asString)
            "error" -> SessionError(obj.get("error").asString)
            else -> null
        }
    } catch (e: Exception) { null }

    private fun extractToolOutput(obj: JsonObject): String? {
        val contentArr = obj.getAsJsonArray("content") ?: return null
        for (elem in contentArr) {
            val contentObj = elem.asJsonObject.getAsJsonObject("content") ?: continue
            if (contentObj.get("type")?.asString == "text") {
                return contentObj.get("text")?.asString
            }
        }
        return null
    }

    // ── Cleanup ──

    private fun cleanup() {
        connected = false
        sessionId = null
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        process?.destroyForcibly()
        process = null
        val err = RuntimeException("Connection closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    // ── Helpers ──

    private fun notifyStatus(connected: Boolean, info: String?) {
        this.connected = connected
        synchronized(callbacks) { callbacks.forEach { it.onStatus(connected, info) } }
    }

    private fun buildJson(block: JsonObject.() -> Unit): JsonObject = JsonObject().apply(block)

    // Helper to add pairs to JsonObject (since Gson's add() requires JsonElement)
    private fun JsonObject.put(key: String, value: String) { addProperty(key, value) }
    private fun JsonObject.put(key: String, value: Int) { addProperty(key, value) }
    private fun JsonObject.put(key: String, value: Boolean) { addProperty(key, value) }
    private fun JsonObject.put(key: String, value: JsonElement) { add(key, value) }

    companion object {
        private val MSG_GSON = Gson()
    }
}
