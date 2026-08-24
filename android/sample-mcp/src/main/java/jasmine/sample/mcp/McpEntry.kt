package jasmine.sample.mcp

import androidx.compose.runtime.Composable
import com.lhzkml.jasmine.core.plugin.PluginContext
import com.lhzkml.jasmine.core.plugin.PluginEntry
import com.lhzkml.jasmine.core.plugin.PluginMenuEntry
import com.lhzkml.jasmine.core.plugin.ServiceKey
import com.lhzkml.jasmine.core.plugin.ServiceTable
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.io.File

/**
 * 跨插件发布的 MCP 服务：宿主或其它插件可通过 [KEY] 消费 MCP 能力。
 */
interface McpService {
    suspend fun listServers(): List<McpServerConfig>
    suspend fun saveServer(config: McpServerConfig)
    suspend fun deleteServer(name: String)
    suspend fun setServerEnabled(name: String, enabled: Boolean)
    suspend fun connect(name: String)
    suspend fun disconnect(name: String)
    suspend fun listTools(name: String): List<Tool>
    suspend fun callTool(name: String, tool: String, arguments: Map<String, Any?>): CallToolResult
    suspend fun ping(name: String)
    suspend fun listPrompts(name: String): List<Prompt>
    suspend fun getPrompt(name: String, prompt: String, arguments: Map<String, String>): GetPromptResult
    suspend fun listResources(name: String): List<Resource>
    suspend fun readResource(name: String, uri: String): ReadResourceResult
    suspend fun setLoggingLevel(name: String, level: LoggingLevel)
    fun isConnected(name: String): Boolean

    companion object {
        val KEY = ServiceKey<McpService>("jasmine.sample.mcp.McpService")
    }
}

/**
 * MCP 插件入口（`jasmine.plugin.entryClass` 指向的类）。
 */
class McpEntry : PluginEntry {

    private lateinit var repository: McpRepository
    private lateinit var clientManager: McpClientManager

    private val service = object : McpService {
        override suspend fun listServers(): List<McpServerConfig> = repository.load()
        override suspend fun saveServer(config: McpServerConfig) = repository.upsert(config)
        override suspend fun deleteServer(name: String) = repository.remove(name)
        override suspend fun setServerEnabled(name: String, enabled: Boolean) =
            repository.setEnabled(name, enabled)

        override suspend fun connect(name: String) {
            val config = repository.find(name)
                ?: throw IllegalArgumentException("server 不存在：$name")
            clientManager.connect(config)
        }

        override suspend fun disconnect(name: String) = clientManager.disconnect(name)
        override suspend fun listTools(name: String): List<Tool> = clientManager.listTools(name)
        override suspend fun callTool(name: String, tool: String, arguments: Map<String, Any?>) =
            clientManager.callTool(name, tool, arguments)

        override suspend fun ping(name: String) = clientManager.ping(name)
        override suspend fun listPrompts(name: String) = clientManager.listPrompts(name)
        override suspend fun getPrompt(name: String, prompt: String, arguments: Map<String, String>) =
            clientManager.getPrompt(name, prompt, arguments)

        override suspend fun listResources(name: String) = clientManager.listResources(name)
        override suspend fun readResource(name: String, uri: String) = clientManager.readResource(name, uri)
        override suspend fun setLoggingLevel(name: String, level: LoggingLevel) =
            clientManager.setLoggingLevel(name, level)

        override fun isConnected(name: String): Boolean = clientManager.isConnected(name)
    }

    override val services: ServiceTable = mapOf(McpService.KEY to service)

    override val menuEntry: PluginMenuEntry = PluginMenuEntry(
        title = "MCP 客户端",
        subtitle = "Model Context Protocol 服务器配置、连接与工具调用",
    )

    override fun onLoad(context: PluginContext) {
        repository = McpRepository(File(context.pluginDir, "servers.json"))
        clientManager = McpClientManager()
    }

    override fun onUnload() {
        clientManager.disconnectAll()
    }

    @Composable
    override fun MainScreen() {
        McpMainScreen(repository = repository, clientManager = clientManager)
    }
}
