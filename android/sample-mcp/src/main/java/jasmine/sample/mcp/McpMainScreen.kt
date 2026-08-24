package jasmine.sample.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.launch

/** 顶层页面导航状态。 */
private sealed interface Screen {
    data object List : Screen
    data class Edit(val editing: McpServerConfig?) : Screen
    data class Detail(val name: String) : Screen
}

/** 把调用结果里的文本块提取为可展示字符串。 */
private fun CallToolResult.toDisplayText(): String {
    val parts = content.mapNotNull { block ->
        when (block) {
            is TextContent -> block.text
            else -> "[${block::class.simpleName}]"
        }
    }
    return if (parts.isEmpty()) "(无内容)" else parts.joinToString("\n")
}

/** 把 prompt 结果的消息提取为可展示字符串。 */
private fun GetPromptResult.toDisplayText(): String {
    val parts = messages.map { msg ->
        when (val c = msg.content) {
            is TextContent -> "[${msg.role}] ${c.text}"
            else -> "[${msg.role}] [${c::class.simpleName}]"
        }
    }
    return if (parts.isEmpty()) "(无内容)" else parts.joinToString("\n")
}

/** 把资源读取结果的内容提取为可展示字符串。 */
private fun ReadResourceResult.toDisplayText(): String {
    val parts = contents.map { c ->
        when (c) {
            is TextResourceContents -> c.text
            else -> "[${c::class.simpleName}]"
        }
    }
    return if (parts.isEmpty()) "(无内容)" else parts.joinToString("\n")
}

/** 插件主界面：宿主打开菜单入口后渲染。 */
@Composable
fun McpMainScreen(repository: McpRepository, clientManager: McpClientManager) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var servers by remember { mutableStateOf(repository.load()) }
    var connected by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    val refresh: () -> Unit = { servers = repository.load() }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try {
                block()
            } catch (t: Throwable) {
                error = t.message ?: t::class.simpleName
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("确定") } },
            title = { Text("错误") },
            text = { Text(message) },
        )
    }

    when (val s = screen) {
        is Screen.List -> ServerListScreen(
            servers = servers,
            connected = connected,
            onAdd = { screen = Screen.Edit(null) },
            onEdit = { screen = Screen.Edit(it) },
            onOpen = { screen = Screen.Detail(it.name) },
            onDelete = { name ->
                run {
                    repository.remove(name)
                    clientManager.disconnect(name)
                    refresh()
                    connected = connected - name
                }
            },
            onToggleEnabled = { name, enabled ->
                repository.setEnabled(name, enabled)
                refresh()
                if (!enabled) {
                    run { clientManager.disconnect(name) }
                    connected = connected - name
                }
            },
            onImport = { json -> repository.importJson(json).also { refresh() } },
            onExport = { repository.exportJson() },
        )

        is Screen.Edit -> ServerEditScreen(
            initial = s.editing,
            onSave = { config ->
                repository.upsert(config)
                refresh()
                screen = Screen.List
                // 保存后若启用且配置有效，自动连接，让用户立即看到连接状态与工具
                if (config.enabled && config.isValid) {
                    run {
                        clientManager.connect(config)
                        connected = connected + config.name
                    }
                }
            },
            onCancel = { screen = Screen.List },
        )

        is Screen.Detail -> ServerDetailScreen(
            name = s.name,
            config = repository.find(s.name),
            connected = s.name in connected,
            clientManager = clientManager,
            onConnect = {
                clientManager.connect(repository.find(s.name)!!)
                connected = connected + s.name
            },
            onDisconnect = {
                clientManager.disconnect(s.name)
                connected = connected - s.name
            },
            onPing = { clientManager.ping(s.name) },
            onBack = { screen = Screen.List },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerListScreen(
    servers: List<McpServerConfig>,
    connected: Set<String>,
    onAdd: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onOpen: (McpServerConfig) -> Unit,
    onDelete: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onImport: (String) -> Int,
    onExport: () -> String,
) {
    var importOpen by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var exportText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 服务器") },
                actions = {
                    TextButton(onClick = {
                        exportText = onExport()
                        exportOpen = true
                    }) { Text("导出") }
                    TextButton(onClick = { importOpen = true }) { Text("导入") }
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = "添加")
                    }
                },
            )
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有配置任何 MCP 服务器", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAdd) { Text("添加服务器") }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(servers, key = { it.name }) { server ->
                    ServerCard(
                        server = server,
                        connected = server.name in connected,
                        onOpen = { onOpen(server) },
                        onEdit = { onEdit(server) },
                        onDelete = { onDelete(server.name) },
                        onToggleEnabled = { onToggleEnabled(server.name, it) },
                    )
                }
            }
        }
    }

    if (importOpen) {
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("导入配置") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("粘贴 Claude Desktop 兼容 JSON") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    onImport(importText)
                    importOpen = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { importOpen = false }) { Text("取消") }
            },
        )
    }

    if (exportOpen) {
        AlertDialog(
            onDismissRequest = { exportOpen = false },
            title = { Text("导出配置") },
            text = {
                OutlinedTextField(
                    value = exportText,
                    onValueChange = {},
                    label = { Text("当前配置 JSON") },
                    minLines = 5,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { exportOpen = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ServerCard(
    server: McpServerConfig,
    connected: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (server.transportType) {
                            TransportType.STREAMABLE_HTTP -> "Streamable HTTP"
                            TransportType.SSE -> "SSE"
                            TransportType.STDIO -> "STDIO"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (connected) {
                        Text(
                            "已连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (server.transportType) {
                        TransportType.STDIO ->
                            listOfNotNull(server.command, server.args.joinToString(" ").ifBlank { null })
                                .joinToString(" ")

                        else -> server.url.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = server.enabled, onCheckedChange = onToggleEnabled)
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditScreen(
    initial: McpServerConfig?,
    onSave: (McpServerConfig) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var transportType by remember {
        mutableStateOf(initial?.transportType ?: TransportType.STREAMABLE_HTTP)
    }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var args by remember { mutableStateOf(initial?.args?.joinToString(" ") ?: "") }
    var env by remember {
        mutableStateOf(initial?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "")
    }
    var headersText by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: "")
    }
    var accessTokenText by remember { mutableStateOf(initial?.accessToken ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (initial == null) "添加服务器" else "编辑服务器") })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = transportType == TransportType.STREAMABLE_HTTP,
                    onClick = { transportType = TransportType.STREAMABLE_HTTP },
                    label = { Text("Streamable HTTP") },
                )
                FilterChip(
                    selected = transportType == TransportType.SSE,
                    onClick = { transportType = TransportType.SSE },
                    label = { Text("SSE") },
                )
                FilterChip(
                    selected = transportType == TransportType.STDIO,
                    onClick = { transportType = TransportType.STDIO },
                    label = { Text("STDIO") },
                )
            }

            if (transportType != TransportType.STDIO) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL（如 https://host/mcp）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text("请求头（每行 Key: Value）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = accessTokenText,
                    onValueChange = { accessTokenText = it },
                    label = { Text("Access Token（可选，Bearer 认证）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令（如 node / npx）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text("参数（空格分隔）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    label = { Text("环境变量（每行 KEY=VALUE）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = {
                        onSave(
                            McpServerConfig(
                                name = name.trim(),
                                url = if (transportType != TransportType.STDIO) url.trim().ifBlank { null } else null,
                                command = if (transportType == TransportType.STDIO) command.trim().ifBlank { null } else null,
                                args = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                                env = env.lines().mapNotNull { line ->
                                    val i = line.indexOf('=')
                                    if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
                                }.toMap(),
                                headers = headersText.lines().mapNotNull { line ->
                                    val i = line.indexOf(':')
                                    if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
                                }.toMap(),
                                accessToken = accessTokenText.trim().ifBlank { null },
                                enabled = enabled,
                                transportType = transportType,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerDetailScreen(
    name: String,
    config: McpServerConfig?,
    connected: Boolean,
    clientManager: McpClientManager,
    onConnect: suspend () -> Unit,
    onDisconnect: suspend () -> Unit,
    onPing: suspend () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var tools by remember { mutableStateOf<List<Tool>>(emptyList()) }
    var prompts by remember { mutableStateOf<List<Prompt>>(emptyList()) }
    var resources by remember { mutableStateOf<List<Resource>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var callTarget by remember { mutableStateOf<Tool?>(null) }
    var callArgs by remember { mutableStateOf("{}") }
    var callResult by remember { mutableStateOf<String?>(null) }
    var promptTarget by remember { mutableStateOf<Prompt?>(null) }
    var promptContent by remember { mutableStateOf<String?>(null) }
    var resourceTarget by remember { mutableStateOf<Resource?>(null) }
    var resourceContent by remember { mutableStateOf<String?>(null) }
    var toolsError by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            localError = null
            try {
                block()
            } catch (t: Throwable) {
                localError = t.message ?: t::class.simpleName
            } finally {
                loading = false
            }
        }
    }

    // 连接后一次性加载三类内容；list* 各自容错（server 可能不支持某类）。
    fun connectAndLoad() = scope.launch {
        loading = true
        localError = null
        try {
            onConnect()
        } catch (t: Throwable) {
            localError = "连接失败：${t.message}"
            loading = false
            return@launch
        }
        toolsError = null
        tools = try {
            clientManager.listTools(name)
        } catch (t: Throwable) {
            toolsError = "工具加载失败：${t.message}"
            emptyList()
        }
        prompts = try { clientManager.listPrompts(name) } catch (_: Throwable) { emptyList() }
        resources = try { clientManager.listResources(name) } catch (_: Throwable) { emptyList() }
        loading = false
    }

    fun refreshAll() = scope.launch {
        loading = true
        toolsError = null
        tools = try {
            clientManager.listTools(name)
        } catch (t: Throwable) {
            toolsError = "工具加载失败：${t.message}"
            emptyList()
        }
        prompts = try { clientManager.listPrompts(name) } catch (_: Throwable) { emptyList() }
        resources = try { clientManager.listResources(name) } catch (_: Throwable) { emptyList() }
        loading = false
    }

    localError?.let { message ->
        AlertDialog(
            onDismissRequest = { localError = null },
            confirmButton = { TextButton(onClick = { localError = null }) { Text("确定") } },
            title = { Text("错误") },
            text = { Text(message) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (config == null) {
                Text("配置不存在")
                return@Column
            }
            Text(
                when (config.transportType) {
                    TransportType.STREAMABLE_HTTP -> "Streamable HTTP：${config.url}"
                    TransportType.SSE -> "SSE：${config.url}"
                    TransportType.STDIO -> "STDIO：${config.command} ${config.args.joinToString(" ")}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "连接状态：${if (connected) "已连接" else "未连接"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!connected) {
                    Button(onClick = { connectAndLoad() }, enabled = !loading) { Text("连接") }
                } else {
                    OutlinedButton(
                        onClick = {
                            run {
                                onDisconnect()
                                tools = emptyList()
                                prompts = emptyList()
                                resources = emptyList()
                            }
                        },
                        enabled = !loading,
                    ) { Text("断开") }
                    OutlinedButton(onClick = { run { onPing() } }, enabled = !loading) { Text("Ping") }
                    OutlinedButton(onClick = { refreshAll() }, enabled = !loading) { Text("刷新") }
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("工具") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Prompts") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Resources") })
            }

            Spacer(Modifier.height(12.dp))

            when (tab) {
                0 -> ToolList(tools = tools, connected = connected, toolsError = toolsError, onCall = { tool ->
                    callTarget = tool
                    callResult = null
                    callArgs = "{}"
                })

                1 -> PromptList(prompts = prompts, connected = connected, onView = { prompt ->
                    promptTarget = prompt
                    promptContent = null
                })

                else -> ResourceList(resources = resources, connected = connected, onRead = { resource ->
                    resourceTarget = resource
                    resourceContent = null
                })
            }
        }
    }

    // 调用工具对话框
    callTarget?.let { tool ->
        AlertDialog(
            onDismissRequest = { callTarget = null },
            title = { Text("调用 ${tool.name}") },
            text = {
                Column {
                    tool.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = callArgs,
                        onValueChange = { callArgs = it },
                        label = { Text("参数（JSON）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    callResult?.let { result ->
                        Spacer(Modifier.height(8.dp))
                        Text("结果：", style = MaterialTheme.typography.titleSmall)
                        Text(result, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val argsMap = try {
                            org.json.JSONObject(callArgs).let { obj ->
                                obj.keys().asSequence().associateWith { obj.get(it) }
                            }
                        } catch (_: Exception) {
                            emptyMap()
                        }
                        callResult = try {
                            clientManager.callTool(name, tool.name, argsMap).toDisplayText()
                        } catch (t: Throwable) {
                            "调用失败：${t.message}"
                        }
                    }
                }) { Text("执行") }
            },
            dismissButton = {
                TextButton(onClick = { callTarget = null }) { Text("关闭") }
            },
        )
    }

    // 查看 prompt 对话框
    promptTarget?.let { prompt ->
        AlertDialog(
            onDismissRequest = { promptTarget = null },
            title = { Text("Prompt：${prompt.name}") },
            text = {
                Column {
                    prompt.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    promptContent?.let { content ->
                        Text(content, style = MaterialTheme.typography.bodySmall)
                    } ?: Text("点击下方「获取」加载内容", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        promptContent = try {
                            clientManager.getPrompt(name, prompt.name, emptyMap()).toDisplayText()
                        } catch (t: Throwable) {
                            "获取失败：${t.message}"
                        }
                    }
                }) { Text("获取") }
            },
            dismissButton = {
                TextButton(onClick = { promptTarget = null }) { Text("关闭") }
            },
        )
    }

    // 读取 resource 对话框
    resourceTarget?.let { resource ->
        AlertDialog(
            onDismissRequest = { resourceTarget = null },
            title = { Text("Resource：${resource.name}") },
            text = {
                Column {
                    Text(resource.uri, style = MaterialTheme.typography.bodySmall)
                    resource.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    resourceContent?.let { content ->
                        Text(content, style = MaterialTheme.typography.bodySmall)
                    } ?: Text("点击下方「读取」加载内容", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        resourceContent = try {
                            clientManager.readResource(name, resource.uri).toDisplayText()
                        } catch (t: Throwable) {
                            "读取失败：${t.message}"
                        }
                    }
                }) { Text("读取") }
            },
            dismissButton = {
                TextButton(onClick = { resourceTarget = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ToolList(tools: List<Tool>, connected: Boolean, toolsError: String?, onCall: (Tool) -> Unit) {
    if (toolsError != null) {
        Text(toolsError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        return
    }
    if (tools.isEmpty()) {
        Text(if (connected) "暂无工具" else "连接后列出工具", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("工具（${tools.size}）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tools, key = { it.name }) { tool ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tool.name, style = MaterialTheme.typography.titleSmall)
                        tool.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Button(onClick = { onCall(tool) }) { Text("调用") }
                }
            }
        }
    }
}

@Composable
private fun PromptList(prompts: List<Prompt>, connected: Boolean, onView: (Prompt) -> Unit) {
    if (prompts.isEmpty()) {
        Text(if (connected) "暂无 Prompts" else "连接后列出 Prompts", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("Prompts（${prompts.size}）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(prompts, key = { it.name }) { prompt ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(prompt.name, style = MaterialTheme.typography.titleSmall)
                        prompt.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Button(onClick = { onView(prompt) }) { Text("查看") }
                }
            }
        }
    }
}

@Composable
private fun ResourceList(resources: List<Resource>, connected: Boolean, onRead: (Resource) -> Unit) {
    if (resources.isEmpty()) {
        Text(if (connected) "暂无 Resources" else "连接后列出 Resources", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text("Resources（${resources.size}）", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(resources, key = { it.uri }) { resource ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(resource.name, style = MaterialTheme.typography.titleSmall)
                        Text(resource.uri, style = MaterialTheme.typography.bodySmall)
                        resource.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    Button(onClick = { onRead(resource) }) { Text("读取") }
                }
            }
        }
    }
}
