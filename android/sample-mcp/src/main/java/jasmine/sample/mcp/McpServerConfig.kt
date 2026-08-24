package jasmine.sample.mcp

import com.lhzkml.jasmine.core.database.McpServerEntity
import org.json.JSONArray
import org.json.JSONObject

/** MCP server 的传输类型。 */
enum class TransportType {
    /** 新版 Streamable HTTP（单 endpoint，支持 SSE 流式响应与 JSON 响应）。 */
    STREAMABLE_HTTP,

    /** 旧版 HTTP + SSE（SSE 收消息 + 独立 POST endpoint 发消息）。 */
    SSE,

    /** 本地标准输入输出子进程。 */
    STDIO,
}

/**
 * MCP server 配置，对应 `servers.json` 的 `mcpServers` 中的一个条目。
 *
 * 传输类型由 [transportType] 决定：
 *  - [TransportType.STREAMABLE_HTTP]：`url`（远程）
 *  - [TransportType.SSE]：`url`（远程）
 *  - [TransportType.STDIO]：`command` + `args` + `env`（本地子进程）
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
    val transportType: TransportType = TransportType.STREAMABLE_HTTP,
) {
    val isStdio: Boolean get() = transportType == TransportType.STDIO
    val isSse: Boolean get() = transportType == TransportType.SSE
    val isStreamableHttp: Boolean get() = transportType == TransportType.STREAMABLE_HTTP

    /** 配置是否完整可连接（stdio 需 command，http/sse 需 url）。 */
    val isValid: Boolean get() = when (transportType) {
        TransportType.STDIO -> !command.isNullOrBlank()
        else -> !url.isNullOrBlank()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("transportType", transportType.name)
        url?.takeIf { it.isNotBlank() }?.let { put("url", it) }
        command?.takeIf { it.isNotBlank() }?.let { put("command", it) }
        if (args.isNotEmpty()) put("args", JSONArray(args))
        if (env.isNotEmpty()) put("env", JSONObject(env))
        if (headers.isNotEmpty()) put("headers", JSONObject(headers))
        accessToken?.takeIf { it.isNotBlank() }?.let { put("accessToken", it) }
    }

    /** 转 Room 实体（List/Map 字段序列化为 JSON 字符串）。 */
    fun toEntity(): McpServerEntity = McpServerEntity(
        name = name,
        url = url,
        command = command,
        argsJson = args.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() },
        envJson = env.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() },
        headersJson = headers.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toString() },
        accessToken = accessToken,
        enabled = enabled,
        transportType = transportType.name,
    )

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
            val transportType = when {
                json.has("transportType") ->
                    runCatching { TransportType.valueOf(json.getString("transportType")) }
                        .getOrDefault(TransportType.STREAMABLE_HTTP)

                // 向后兼容：旧配置没有 transportType，按字段推断
                json.optString("command").isNotEmpty() -> TransportType.STDIO
                else -> TransportType.STREAMABLE_HTTP
            }
            return McpServerConfig(
                name = name,
                url = json.optString("url").takeIf { it.isNotEmpty() },
                command = json.optString("command").takeIf { it.isNotEmpty() },
                args = args,
                env = env,
                headers = headers,
                accessToken = json.optString("accessToken").takeIf { it.isNotEmpty() },
                enabled = json.optBoolean("enabled", true),
                transportType = transportType,
            )
        }

        /** 从 Room 实体还原（JSON 字符串反序列化为 List/Map）。 */
        fun fromEntity(entity: McpServerEntity): McpServerConfig {
            val args = entity.argsJson?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    (0 until arr.length()).map { arr.getString(it) }
                }.getOrDefault(emptyList())
            } ?: emptyList()
            val env = entity.envJson?.let { json ->
                runCatching {
                    val obj = JSONObject(json)
                    obj.keys().asSequence().associateWith { obj.getString(it) }
                }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val headers = entity.headersJson?.let { json ->
                runCatching {
                    val obj = JSONObject(json)
                    obj.keys().asSequence().associateWith { obj.getString(it) }
                }.getOrDefault(emptyMap())
            } ?: emptyMap()
            return McpServerConfig(
                name = entity.name,
                url = entity.url,
                command = entity.command,
                args = args,
                env = env,
                headers = headers,
                accessToken = entity.accessToken,
                enabled = entity.enabled,
                transportType = runCatching { TransportType.valueOf(entity.transportType) }
                    .getOrDefault(TransportType.STREAMABLE_HTTP),
            )
        }
    }
}
