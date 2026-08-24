package jasmine.sample.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * MCP 连接管理器：基于官方 `kotlin-sdk-client`，按 server 名维护长连接。
 *
 * - HTTP/Streamable 远程：`StreamableHttpClientTransport`（Ktor + SSE）
 * - STDIO 本地：`StdioClientTransport`（`ProcessBuilder` spawn 子进程）
 *
 * 所有协议方法均为 suspend，调用方需在协程作用域内使用。
 */
class McpClientManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableMapOf<String, Client>()
    private val processes = mutableMapOf<String, Process>()

    /** 连接（或重连）指定 server，返回连接成功与否。 */
    suspend fun connect(config: McpServerConfig) {
        withContext(Dispatchers.IO) {
            disconnectInternal(config.name)

            val client = Client(
                clientInfo = Implementation(name = "jasmine-mcp-plugin", version = "1.0.0"),
                options = ClientOptions(),
            )
            val requestHeaders: HttpRequestBuilder.() -> Unit = {
                config.headers.forEach { (key, value) -> header(key, value) }
                config.accessToken?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }

            val transport = when (config.transportType) {
                TransportType.STREAMABLE_HTTP -> {
                    val http = HttpClient(OkHttp) { install(SSE) }
                    StreamableHttpClientTransport(
                        client = http,
                        url = requireNotNull(config.url) { "缺少 url" },
                        requestBuilder = requestHeaders,
                    )
                }

                TransportType.SSE -> {
                    val http = HttpClient(OkHttp) { install(SSE) }
                    SseClientTransport(
                        client = http,
                        urlString = requireNotNull(config.url) { "缺少 url" },
                        requestBuilder = requestHeaders,
                    )
                }

                TransportType.STDIO -> {
                    val process = ProcessBuilder(buildList {
                        add(requireNotNull(config.command) { "缺少 command" })
                        addAll(config.args)
                    })
                        .apply { environment().putAll(config.env) }
                        .start()
                    processes[config.name] = process
                    StdioClientTransport(
                        input = process.inputStream.asSource().buffered(),
                        output = process.outputStream.asSink().buffered(),
                        error = process.errorStream.asSource().buffered(),
                    )
                }
            }
            client.connect(transport)
            clients[config.name] = client
        }
    }

    /** 列出已连接 server 的工具。 */
    suspend fun listTools(name: String): List<Tool> = withContext(Dispatchers.IO) {
        val client = clients[name] ?: throw IllegalStateException("server 未连接：$name")
        client.listTools().tools
    }

    /** 调用工具，返回 SDK 结果对象（UI 层负责提取文本）。 */
    suspend fun callTool(name: String, toolName: String, arguments: Map<String, Any?>): CallToolResult =
        withContext(Dispatchers.IO) {
            clients[name]?.callTool(toolName, arguments)
                ?: throw IllegalStateException("server 未连接：$name")
        }

    /** 连通性检查（initialize 握手）。 */
    suspend fun ping(name: String) {
        withContext(Dispatchers.IO) { clients[name]?.ping() }
    }

    /** 列出 prompts。 */
    suspend fun listPrompts(name: String): List<Prompt> = withContext(Dispatchers.IO) {
        val client = clients[name] ?: throw IllegalStateException("server 未连接：$name")
        client.listPrompts().prompts
    }

    /** 获取指定 prompt 的内容。 */
    suspend fun getPrompt(name: String, promptName: String, arguments: Map<String, String>): GetPromptResult =
        withContext(Dispatchers.IO) {
            clients[name]?.getPrompt(
                GetPromptRequest(GetPromptRequestParams(name = promptName, arguments = arguments)),
            ) ?: throw IllegalStateException("server 未连接：$name")
        }

    /** 列出 resources。 */
    suspend fun listResources(name: String): List<Resource> = withContext(Dispatchers.IO) {
        val client = clients[name] ?: throw IllegalStateException("server 未连接：$name")
        client.listResources().resources
    }

    /** 读取指定 URI 的资源内容。 */
    suspend fun readResource(name: String, uri: String): ReadResourceResult =
        withContext(Dispatchers.IO) {
            clients[name]?.readResource(ReadResourceRequest(ReadResourceRequestParams(uri = uri)))
                ?: throw IllegalStateException("server 未连接：$name")
        }

    /** 设置 server 的日志级别。 */
    suspend fun setLoggingLevel(name: String, level: LoggingLevel) {
        withContext(Dispatchers.IO) { clients[name]?.setLoggingLevel(level) }
    }

    fun isConnected(name: String): Boolean = clients.containsKey(name)

    /** 断开指定 server。 */
    suspend fun disconnect(name: String) {
        withContext(Dispatchers.IO) { disconnectInternal(name) }
    }

    /** 断开全部连接（插件卸载时调用）。 */
    fun disconnectAll() {
        clients.keys.toList().forEach { name ->
            runCatching {
                val client = clients.remove(name)
                val process = processes.remove(name)
                if (client != null) scope.launch { client.close() }
                process?.destroy()
            }
        }
    }

    private suspend fun disconnectInternal(name: String) {
        val client = clients.remove(name)
        val process = processes.remove(name)
        if (client != null) client.close()
        process?.destroy()
    }
}
