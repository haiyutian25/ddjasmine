package jasmine.sample.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP server 配置，对应 `servers.json` 的 `mcpServers` 中的一个条目。
 *
 * 支持两种 transport（与官方规范一致）：
 *  - HTTP/Streamable：`url`（远程 server）
 *  - STDIO：`command` + `args` + `env`（本地子进程）
 */
data class McpServerConfig(
    val name: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    /** OAuth/认证 access token；连接时作为 `Authorization: Bearer` 注入。 */
    val accessToken: String? = null,
    val enabled: Boolean = true,
) {
    val isHttp: Boolean get() = !url.isNullOrBlank()
    val isStdio: Boolean get() = !command.isNullOrBlank()

    /** 配置是否完整可连接（http 需 url，stdio 需 command）。 */
    val isValid: Boolean get() = isHttp || isStdio

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        url?.takeIf { it.isNotBlank() }?.let { put("url", it) }
        command?.takeIf { it.isNotBlank() }?.let { put("command", it) }
        if (args.isNotEmpty()) put("args", JSONArray(args))
        if (env.isNotEmpty()) put("env", JSONObject(env))
        if (headers.isNotEmpty()) put("headers", JSONObject(headers))
        accessToken?.takeIf { it.isNotBlank() }?.let { put("accessToken", it) }
    }

    companion object {
        fun fromJson(name: String, json: JSONObject): McpServerConfig {
            val args = json.optJSONArray("args")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            val env = json.optJSONObject("env")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } ?: emptyMap()
            val headers = json.optJSONObject("headers")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.getString(it) }
            } ?: emptyMap()
            return McpServerConfig(
                name = name,
                url = json.optString("url").takeIf { it.isNotEmpty() },
                command = json.optString("command").takeIf { it.isNotEmpty() },
                args = args,
                env = env,
                headers = headers,
                accessToken = json.optString("accessToken").takeIf { it.isNotEmpty() },
                enabled = json.optBoolean("enabled", true),
            )
        }
    }
}
