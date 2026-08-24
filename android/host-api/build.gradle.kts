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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 宿主 API 聚合门面（方案 B/C）：
 *
 * 把宿主要暴露给插件的核心库（core-plugin + core-ui）与 Compose 基础依赖通过
 * `api` 传递聚合。插件只需 `implementation(project(":host-api"))` 一个依赖，
 * 即可在编译期直接 import 宿主的 PluginHost / PluginContext / JasmineTheme /
 * 颜色常量 / Compose 组件，运行时经 parent-first 从宿主解析——"直接使用宿主
 * 组件"而非"复用（复制代码）"。
 *
 * 本模块自身的类不会打进插件 DEX（project 依赖不进 resolvedArtifacts，也不进
 * library AAR 的 classes.jar）；其 `api` 暴露的远程依赖（Compose 等）会被
 * PluginPackPlugin 的自动宿主依赖排除 + 默认 "androidx" 前缀排除掉。
 */
plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lhzkml.jasmine.host.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 聚合暴露宿主核心 API（api 传递）。
    api(project(":core-plugin"))
    api(project(":core-ui"))

    // 宿主共享的 Compose 基础依赖经 api 传递，插件无需逐个声明。
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
    api(libs.androidx.activity.compose)
    api(libs.androidx.core.ktx)

    // 宿主共享的网络栈：插件（如 MCP）用到 Ktor client/engine 时经 api 传递
    // 到编译 classpath，运行时 parent-first 从宿主解析，插件无需再逐个声明。
    api(libs.ktor.client.core)
    api(libs.ktor.client.okhttp)
}
