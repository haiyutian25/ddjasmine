package jasmine.sample.example.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jasmine.sample.example.jni.NativeLib

/** SO 库示例页：展示 JNI 调用、动态库加载、native 方法。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoLibraryScreen() {
    val nativeLib = remember { NativeLib() }
    var basicResult by remember { mutableStateOf("") }
    var mathResult by remember { mutableStateOf("") }
    var arrayResult by remember { mutableStateOf("") }
    var systemInfo by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var numberA by remember { mutableStateOf("10") }
    var numberB by remember { mutableStateOf("20") }
    var sqrtNumber by remember { mutableStateOf("16.0") }

    Scaffold(topBar = { TopAppBar(title = { Text("SO 库加载", fontWeight = FontWeight.Bold) }) }) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InfoCard(icon = Icons.Default.Info)
            StatusCard()
            DemoCard(
                title = "基础 JNI 调用",
                description = "调用最简单的native方法获取字符串",
                buttonText = "调用 stringFromJNI",
                isLoading = isLoading,
                result = basicResult,
                onButtonClick = {
                    isLoading = true
                    basicResult = try { nativeLib.stringFromJNI() } catch (e: Exception) { "调用失败: ${e.message}" } finally { isLoading = false }
                },
            )
            MathDemoCard(
                numberA = numberA, numberB = numberB, sqrtNumber = sqrtNumber, result = mathResult,
                onNumberAChange = { numberA = it }, onNumberBChange = { numberB = it }, onSqrtNumberChange = { sqrtNumber = it },
                onCalculate = {
                    try {
                        val a = numberA.toIntOrNull() ?: 0
                        val b = numberB.toIntOrNull() ?: 0
                        val sqrt = sqrtNumber.toDoubleOrNull() ?: 0.0
                        val sum = nativeLib.addNumbers(a, b)
                        val sqrtResult = nativeLib.calculateSquareRoot(sqrt)
                        mathResult = "加法: $a + $b = $sum\n平方根: √$sqrt = $sqrtResult"
                    } catch (e: Exception) { mathResult = "计算失败: ${e.message}" }
                },
            )
            DemoCard(
                title = "字符串数组处理", description = "传递字符串数组到native方法进行处理",
                buttonText = "处理字符串数组", isLoading = false, result = arrayResult,
                onButtonClick = {
                    try {
                        val testArray = arrayOf("Hello", "Native", "World", "JNI")
                        arrayResult = nativeLib.processStringArray(testArray)
                    } catch (e: Exception) { arrayResult = "处理失败: ${e.message}" }
                },
            )
            DemoCard(
                title = "系统信息获取", description = "获取native层的编译器和架构信息",
                buttonText = "获取系统信息", isLoading = false, result = systemInfo,
                onButtonClick = {
                    systemInfo = try { nativeLib.getSystemInfo() } catch (e: Exception) { "获取失败: ${e.message}" }
                },
            )
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Native 库信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("展示已加载的SO库基本信息和系统环境", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusCard() {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("库加载状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("已加载", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("nativelib.so 已成功加载，包含5个JNI方法", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MathDemoCard(
    numberA: String, numberB: String, sqrtNumber: String, result: String,
    onNumberAChange: (String) -> Unit, onNumberBChange: (String) -> Unit, onSqrtNumberChange: (String) -> Unit, onCalculate: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("数学计算演示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = numberA, onValueChange = onNumberAChange, label = { Text("数字 A") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(value = numberB, onValueChange = onNumberBChange, label = { Text("数字 B") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = sqrtNumber, onValueChange = onSqrtNumberChange, label = { Text("平方根计算") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onCalculate, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("执行计算")
            }
            if (result.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(result, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DemoCard(
    title: String, description: String, buttonText: String,
    isLoading: Boolean, result: String, onButtonClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onButtonClick, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("调用中...")
                } else {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(buttonText)
                }
            }
            if (result.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    Text(text = if (result.contains("\n")) result else "结果: $result", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
