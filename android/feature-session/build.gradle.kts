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

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lhzkml.jasmine.feature.session"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "com.lhzkml.jasmine.core.testing.HiltTestRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(project(":core-data"))
    implementation(project(":core-ui"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(project(":feature-session-navigation"))

    androidTestImplementation(project(":core-testing"))

    // Core Android dependencies
    implementation(libs.androidx.activity.compose)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Markdown rendering for model replies: Markwon (View-based, wrapped in
    // AndroidView — see ui/MarkdownText.kt). prism4j-bundler is a javac
    // annotation processor: compileOnly exposes the @PrismBundle annotation,
    // annotationProcessor runs it over ui/PrismSupport.java to generate the
    // GrammarLocator (Kotlin compiles before generated Java, so the holder
    // and factory stay in Java).
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.image)
    implementation(libs.markwon.html)
    implementation(libs.markwon.inline.parser)
    implementation(libs.markwon.linkify)
    // markwon/prism4j 传递的旧版 annotations-java5 与 Kotlin 传递的
    // annotations 23.0.0 类重复（checkDuplicateClasses 失败），排除旧版。
    implementation(libs.markwon.syntax.highlight) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation(libs.prism4j) {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    compileOnly(libs.prism4j.bundler)
    annotationProcessor(libs.prism4j.bundler)

    // Coil 3：全项目唯一图片加载引擎。Markdown 内嵌图片经自定义 SchemeHandler
    // 桥接（见 ui/MarkdownText.kt）；coil-compose 供聊天图片 UI（AsyncImage）。
    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Navigation
    implementation(libs.androidx.navigation3.runtime)

    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt and instrumented tests.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    // Hilt and Robolectric tests.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
