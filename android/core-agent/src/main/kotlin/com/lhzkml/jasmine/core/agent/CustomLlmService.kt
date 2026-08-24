package com.lhzkml.jasmine.core.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** API wire protocol used by one user-defined provider connection. */
enum class ProviderProtocol(val endpoint: String) {
    /** OpenAI-compatible `POST /chat/completions`. */
    CHAT_COMPLETIONS("/chat/completions"),

    /** OpenAI-compatible `POST /responses`. */
    RESPONSES("/responses"),
}

/**
 * Fully user-defined provider connection. Jasmine ships no provider address,
 * model, key, context window, or output limit by default.
 */
data class CustomProviderConfig(
    val apiAddress: String,
    val apiKey: String,
    val model: String,
    val protocol: ProviderProtocol,
    val contextLength: Int,
    /** `null` means provider maximum: omit the protocol's output-limit field. */
    val maxOutputTokens: Int?,
) {
    init {
        require(apiAddress.isNotBlank()) { "API 地址不能为空" }
        require(model.isNotBlank()) { "模型不能为空" }
        require(contextLength > 0) { "上下文长度必须大于 0" }
        maxOutputTokens?.let { output ->
            require(output > 0) { "最大输出长度必须大于 0" }
            require(output < contextLength) { "最大输出长度必须小于上下文长度" }
        }
    }
}

/** Provider-side failure with a sanitized message (never echoes credentials). */
class LlmProviderException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** One streamed text piece, either thinking or final content. */
private enum class StreamPieceKind { REASONING, CONTENT }

private data class StreamPiece(val kind: StreamPieceKind, val text: String)

/**
 * Generic OpenAI-compatible provider supporting both Chat Completions and
 * Responses. The configured API address may be either a base address
 * (`https://host/v1`) or the selected protocol's full endpoint; Jasmine
 * normalizes both forms.
 *
 * Before dispatch, history is fitted to `contextLength - maxOutputTokens`
 * using the same fixed density as the Rust meter (4 characters/token). The
 * newest message is always retained; older messages drop from the head.
 *
 * HTTP transport is Ktor (OkHttp engine) so the host shares the same client
 * stack — and OkHttp version — as plugins embedding the MCP Kotlin SDK.
 */
class CustomLlmService(
    private val config: CustomProviderConfig,
    client: HttpClient = defaultClient(),
    /** Receives every raw line the provider sends, verbatim (diagnostics). */
    private val rawSink: (String) -> Unit = {},
) : LlmService {

    private val client = client
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        val body = requestBody(request.messages, stream = false)
        val payload = client.post(protocolUrl()) {
            applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.bodyAsText()
        rawSink(payload)
        val root = parseObject(payload, "供应商响应无法解析")
        val content = when (config.protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> parseChatContent(root)
            ProviderProtocol.RESPONSES -> parseResponsesContent(root)
        }
        if (content.isEmpty()) throw LlmProviderException("供应商响应中没有文本输出")
        LlmResponse(content)
    }

    override suspend fun stream(
        request: LlmRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val body = requestBody(request.messages, stream = true)
        client.preparePost(protocolUrl()) {
            applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val code = response.status.value
                val errorBody = sanitize(response.bodyAsText().take(300))
                throw LlmProviderException("供应商 HTTP $code：$errorBody")
            }
            val channel = response.bodyAsChannel()
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var eventName = ""
            var sawChunk = false
            while (true) {
                val line = channel.readUTF8Line() ?: break
                rawSink(line)
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                if (trimmed.startsWith("event:")) {
                    eventName = trimmed.removePrefix("event:").trim()
                    continue
                }
                // OpenAI-compatible streaming comes in two dialects: SSE with
                // a `data:` prefix, and JSONL where each raw line is a chunk.
                // Both are streaming; a provider that returns neither failed
                // to honor stream:true.
                val payload = if (trimmed.startsWith("data:")) {
                    trimmed.removePrefix("data:").trim()
                } else {
                    trimmed
                }
                if (payload == "[DONE]") break
                if (!payload.startsWith("{") && !payload.startsWith("[")) continue
                sawChunk = true
                val piece = parseStreamPiece(payload, eventName)
                eventName = ""
                when (piece?.kind) {
                    StreamPieceKind.REASONING -> if (piece.text.isNotEmpty()) {
                        reasoning.append(piece.text)
                        onReasoning(piece.text)
                    }
                    StreamPieceKind.CONTENT -> if (piece.text.isNotEmpty()) {
                        content.append(piece.text)
                        onDelta(piece.text)
                    }
                    null -> Unit
                }
            }
            // Streaming-only: a provider that ignores stream:true is a
            // misconfiguration and fails loud rather than degrading.
            if (!sawChunk) throw LlmProviderException("供应商未返回流式响应（stream:true 被忽略）")
            val finalContent = content.toString()
            if (finalContent.isEmpty()) throw LlmProviderException("供应商流式响应中没有文本输出")
            LlmResponse(finalContent)
        }
    }

    private fun requestBody(messages: List<LlmMessage>, stream: Boolean): String {
        val fitted = fitMessages(messages)
        val body = when (config.protocol) {
            ProviderProtocol.CHAT_COMPLETIONS -> chatRequest(fitted, stream)
            ProviderProtocol.RESPONSES -> responsesRequest(fitted, stream)
        }
        return body.toString()
    }

    /**
     * Extracts one text piece from an SSE data payload, classifying it as
     * reasoning (thinking) or final content:
     *  - Chat Completions: `choices[0].delta.content` / `delta.reasoning_content`
     *  - Responses: `output_text.delta` (content) and
     *    `response.reasoning_summary_text.delta` (reasoning) events whose
     *    `delta` is a string
     */
    private fun parseStreamPiece(data: String, eventName: String): StreamPiece? {
        val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return null
        if (element !is JsonObject) return null

        // Responses-style reasoning summary events.
        if (eventName.contains("reasoning", ignoreCase = true)) {
            element["delta"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { return StreamPiece(StreamPieceKind.REASONING, it) }
        }

        // Chat-style reasoning_content delta.
        element["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { return StreamPiece(StreamPieceKind.REASONING, it) }

        // Final content: top-level delta string (Responses) or delta.content (Chat).
        element["delta"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { return StreamPiece(StreamPieceKind.CONTENT, it) }
        element["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { return StreamPiece(StreamPieceKind.CONTENT, it) }
        return null
    }

    /** Lists model ids from the conventional `GET /models` endpoint. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val payload = client.get(modelsUrl()) {
            applyAuth(this)
        }.bodyAsText()
        val root = parseObject(payload, "模型列表无法解析")
        root["data"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        }
    }

    private fun chatRequest(messages: List<LlmMessage>, stream: Boolean): JsonObject = buildJsonObject {
        put("model", config.model)
        put("messages", messageArray(messages))
        config.maxOutputTokens?.let { put("max_tokens", it) }
        put("stream", stream)
    }

    private fun responsesRequest(messages: List<LlmMessage>, stream: Boolean): JsonObject = buildJsonObject {
        put("model", config.model)
        put("input", messageArray(messages))
        config.maxOutputTokens?.let { put("max_output_tokens", it) }
        put("stream", stream)
    }

    private fun messageArray(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        messages.forEach { message ->
            add(buildJsonObject {
                put("role", message.role)
                put("content", message.content)
            })
        }
    }

    private fun parseChatContent(root: JsonObject): String =
        root["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            .orEmpty()

    private fun parseResponsesContent(root: JsonObject): String {
        root["output_text"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) return it }
        return root["output"]?.jsonArray.orEmpty()
            .flatMap { output -> output.jsonObject["content"]?.jsonArray.orEmpty() }
            .filter { content -> content.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
            .mapNotNull { content -> content.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
    }

    private fun fitMessages(messages: List<LlmMessage>): List<LlmMessage> {
        if (messages.isEmpty()) return messages
        val budget = (config.contextLength - (config.maxOutputTokens ?: 0)).coerceAtLeast(1)
        var used = 0
        val retained = ArrayDeque<LlmMessage>()
        for (message in messages.asReversed()) {
            val cost = message.content.length.divCeil(4) + 8
            if (retained.isNotEmpty() && used + cost > budget) break
            retained.addFirst(message)
            used += cost
        }
        return retained.toList()
    }

    private fun protocolUrl(): String {
        val root = apiRoot()
        return root + config.protocol.endpoint
    }

    private fun modelsUrl(): String = apiRoot() + "/models"

    private fun apiRoot(): String {
        var root = config.apiAddress.trim().trimEnd('/')
        ProviderProtocol.entries.forEach { protocol ->
            if (root.endsWith(protocol.endpoint)) root = root.removeSuffix(protocol.endpoint)
        }
        return root
    }

    private fun applyAuth(builder: HttpRequestBuilder) {
        if (config.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${config.apiKey}")
    }

    private fun parseObject(payload: String, message: String): JsonObject = try {
        json.parseToJsonElement(payload).jsonObject
    } catch (t: Exception) {
        throw LlmProviderException("$message：${sanitize(t.message)}", t)
    }

    private fun sanitize(message: String?): String {
        var value = message ?: "未知错误"
        if (config.apiKey.isNotBlank()) value = value.replace(config.apiKey, "***")
        return value.replace(Regex("sk-[A-Za-z0-9_-]+"), "sk-***")
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}

private fun Int.divCeil(divisor: Int): Int = (this + divisor - 1) / divisor
