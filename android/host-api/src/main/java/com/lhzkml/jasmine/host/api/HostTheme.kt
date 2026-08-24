/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lhzkml.jasmine.host.api

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.lhzkml.jasmine.core.ui.JasmineTheme

/**
 * 插件复用宿主主题的推荐入口（方案 C：资源/主题直用）。
 *
 * 经 host-api 的 `api(project(":core-ui"))` 传递，插件在编译期直接 import
 * 宿主的 [JasmineTheme] 与颜色常量；运行时经 parent-first 从宿主解析，无需
 * 复制任何主题代码——这就是"直接使用宿主组件"而非"复用（复制）"。
 */
@Composable
fun HostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    JasmineTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}
