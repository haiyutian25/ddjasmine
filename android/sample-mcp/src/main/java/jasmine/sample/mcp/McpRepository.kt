package jasmine.sample.mcp

import org.json.JSONObject
import java.io.File

/**
 * `servers.json` 持久化仓库，存于插件私有目录。
 *
 * 文件格式与 Claude Desktop 兼容：
 * ```
 * { "mcpServers": { "<name>": { "url": "...", "enabled": true } } }
 * ```
 * 采用临时文件 + 原子重命名，避免写一半损坏。
 */
class McpRepository(private val configFile: File) {

    fun load(): List<McpServerConfig> {
        if (!configFile.exists()) return emptyList()
        val root = try {
            JSONObject(configFile.readText())
        } catch (_: Exception) {
            return emptyList()
        }
        val servers = root.optJSONObject("mcpServers") ?: return emptyList()
        return servers.keys().asSequence().mapNotNull { name ->
            servers.optJSONObject(name)?.let { McpServerConfig.fromJson(name, it) }
        }.toList()
    }

    fun save(servers: List<McpServerConfig>) {
        val root = JSONObject()
        val mcpServers = JSONObject()
        servers.forEach { mcpServers.put(it.name, it.toJson()) }
        root.put("mcpServers", mcpServers)

        configFile.parentFile?.mkdirs()
        val tmp = File(configFile.parentFile, configFile.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (configFile.exists()) configFile.delete()
        if (!tmp.renameTo(configFile)) {
            tmp.copyTo(configFile, overwrite = true)
            tmp.delete()
        }
    }

    fun upsert(config: McpServerConfig) {
        val list = load().filterNot { it.name == config.name }.toMutableList()
        list.add(config)
        save(list)
    }

    fun remove(name: String) {
        save(load().filterNot { it.name == name })
    }

    fun setEnabled(name: String, enabled: Boolean) {
        save(load().map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    fun find(name: String): McpServerConfig? = load().find { it.name == name }

    /** 从 Claude Desktop 兼容的 JSON 文本批量导入 server（同名覆盖），返回导入数量。 */
    fun importJson(jsonText: String): Int {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return 0
        }
        val servers = root.optJSONObject("mcpServers") ?: return 0
        var count = 0
        val current = load().toMutableList()
        servers.keys().forEach { name ->
            servers.optJSONObject(name)?.let { json ->
                val config = McpServerConfig.fromJson(name, json)
                current.removeAll { it.name == name }
                current.add(config)
                count++
            }
        }
        save(current)
        return count
    }

    /** 导出当前配置为 Claude Desktop 兼容的 JSON 文本。 */
    fun exportJson(): String {
        val root = JSONObject()
        val mcpServers = JSONObject()
        load().forEach { mcpServers.put(it.name, it.toJson()) }
        root.put("mcpServers", mcpServers)
        return root.toString(2)
    }
}
