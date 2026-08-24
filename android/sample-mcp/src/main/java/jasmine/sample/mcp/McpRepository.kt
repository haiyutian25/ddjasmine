package jasmine.sample.mcp

import com.lhzkml.jasmine.core.database.McpServerDao
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * MCP server 配置仓库：持久化统一走宿主的 Room（core-database 的 McpServerDao），
 * 不再用 `servers.json` 文件。仍保留 `importJson`/`exportJson` 以兼容 Claude
 * Desktop 的 JSON 格式导入导出。
 */
class McpRepository(private val dao: McpServerDao) {

    suspend fun load(): List<McpServerConfig> =
        dao.getAll().first().map { McpServerConfig.fromEntity(it) }

    suspend fun upsert(config: McpServerConfig) {
        dao.upsert(config.toEntity())
    }

    suspend fun remove(name: String) {
        dao.deleteByName(name)
    }

    suspend fun setEnabled(name: String, enabled: Boolean) {
        dao.setEnabled(name, enabled)
    }

    suspend fun find(name: String): McpServerConfig? =
        dao.findByName(name)?.let { McpServerConfig.fromEntity(it) }

    /** 从 Claude Desktop 兼容的 JSON 文本批量导入 server（同名覆盖），返回导入数量。 */
    suspend fun importJson(jsonText: String): Int {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return 0
        }
        val servers = root.optJSONObject("mcpServers") ?: return 0
        var count = 0
        servers.keys().forEach { name ->
            servers.optJSONObject(name)?.let { json ->
                dao.upsert(McpServerConfig.fromJson(name, json).toEntity())
                count++
            }
        }
        return count
    }

    /** 导出当前配置为 Claude Desktop 兼容的 JSON 文本。 */
    suspend fun exportJson(): String {
        val root = JSONObject()
        val mcpServers = JSONObject()
        load().forEach { mcpServers.put(it.name, it.toJson()) }
        root.put("mcpServers", mcpServers)
        return root.toString(2)
    }
}
