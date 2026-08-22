package com.lhzkml.jasmine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lhzkml.jasmine.core.agent.ProviderProtocol

/**
 * One provider's edit page, used for both create and edit. Layout is split
 * into cards: basic info, models (probe + multi-select), parameters.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProviderEditScreen(
    providerId: String?,
    onDone: () -> Unit,
    viewModel: ProviderEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var keyVisible by remember { mutableStateOf(false) }

    LaunchedEdit(providerId, viewModel)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (providerId == null) "新增供应商" else "编辑供应商") },
                    actions = {
                        if (providerId != null) {
                            TextButton(onClick = { viewModel.delete(onDone) }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                EditCard("基本信息") {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::updateName,
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
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
                    OutlinedTextField(
                        value = state.apiAddress,
                        onValueChange = viewModel::updateApiAddress,
                        label = { Text("API 地址") },
                        placeholder = { Text("https://example.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.apiKey,
                        onValueChange = viewModel::updateApiKey,
                        label = { Text("API 密钥（可选）") },
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
                }
            }

            item {
                EditCard("模型") {
                    Button(
                        onClick = viewModel::testAndFetchModels,
                        enabled = !state.testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.testing) "连接中…" else "测试并获取模型")
                    }
                    state.result?.let {
                        Text(it, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (state.available.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = viewModel::selectAllModels) { Text("全选") }
                            TextButton(onClick = viewModel::clearModels) { Text("清除") }
                        }
                        state.available.forEach { modelId ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = modelId in state.models,
                                    onCheckedChange = { viewModel.toggleModel(modelId) },
                                )
                                Text(modelId, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (state.models.isNotEmpty()) {
                        Text("已选模型", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.models.forEach { modelId ->
                                FilterChip(
                                    selected = true,
                                    onClick = { viewModel.toggleModel(modelId) },
                                    label = { Text(modelId) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                EditCard("参数") {
                    OutlinedTextField(
                        value = state.contextLength,
                        onValueChange = viewModel::updateContextLength,
                        label = { Text("上下文长度") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = state.maxOutputTokens,
                        onValueChange = viewModel::updateMaxOutputTokens,
                        label = { Text("最大输出长度（可选）") },
                        supportingText = { Text("留空不发送限制字段，使用供应商最大值") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }

            item {
                Button(onClick = { viewModel.save(onDone) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.saved) "已保存" else "保存")
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** Loads the provider once per providerId (create or edit). */
@Composable
private fun LaunchedEdit(providerId: String?, viewModel: ProviderEditViewModel) {
    androidx.compose.runtime.LaunchedEffect(providerId) { viewModel.load(providerId) }
}

@Composable
private fun EditCard(
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
