package com.lhzkml.jasmine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lhzkml.jasmine.core.agent.ProviderProtocol

/** User-defined provider connection. Jasmine supplies no provider defaults. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    viewModel: ProviderSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(title = { Text("自定义供应商") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.loading) {
                item { CircularProgressIndicator() }
                return@LazyColumn
            }

            item {
                SettingCard(title = "协议") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.protocol == ProviderProtocol.CHAT_COMPLETIONS,
                            onClick = { viewModel.selectProtocol(ProviderProtocol.CHAT_COMPLETIONS) },
                            label = { Text("Chat Completions") },
                        )
                        FilterChip(
                            selected = state.protocol == ProviderProtocol.RESPONSES,
                            onClick = { viewModel.selectProtocol(ProviderProtocol.RESPONSES) },
                            label = { Text("Responses") },
                        )
                    }
                    Text(
                        when (state.protocol) {
                            ProviderProtocol.CHAT_COMPLETIONS -> "请求端点：/chat/completions"
                            ProviderProtocol.RESPONSES -> "请求端点：/responses"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            item {
                SettingCard(title = "API 地址") {
                    OutlinedTextField(
                        value = state.apiAddress,
                        onValueChange = viewModel::updateApiAddress,
                        placeholder = { Text("https://example.com/v1") },
                        supportingText = { Text("可填写基础地址，也可填写所选协议的完整端点") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            item {
                SettingCard(title = "API 密钥") {
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = viewModel::updateApiKey,
                        placeholder = { Text("本地或免认证接口可以留空") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { keyVisible = !keyVisible }) {
                                Text(if (keyVisible) "隐藏" else "显示")
                            }
                        },
                    )
                    Text(
                        "密钥使用 Android Keystore AES-GCM 加密存储",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            item {
                SettingCard(title = "模型") {
                    OutlinedTextField(
                        value = state.model,
                        onValueChange = viewModel::selectModel,
                        placeholder = { Text("模型 ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (state.models.isNotEmpty()) {
                        Text("供应商模型", style = MaterialTheme.typography.labelMedium)
                        state.models.forEach { modelId ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectModel(modelId) }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(modelId)
                                if (modelId == state.model) {
                                    Text("已选", color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingCard(title = "Token 限制") {
                    OutlinedTextField(
                        value = state.contextLength,
                        onValueChange = viewModel::updateContextLength,
                        label = { Text("上下文长度") },
                        supportingText = { Text("输入历史与最大输出的总窗口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = state.maxOutputTokens,
                        onValueChange = viewModel::updateMaxOutputTokens,
                        label = { Text("最大输出长度（可选）") },
                        supportingText = {
                            Text(
                                if (state.maxOutputTokens.isBlank()) "留空时不发送限制字段，使用供应商最大值"
                                else if (state.protocol == ProviderProtocol.CHAT_COMPLETIONS) "作为 max_tokens 发送"
                                else "作为 max_output_tokens 发送"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = viewModel::testAndFetchModels,
                        enabled = !state.testing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.testing) "连接中…" else "测试并获取模型")
                    }
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.saved) "已保存" else "保存")
                    }
                }
                state.result?.let {
                    Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
