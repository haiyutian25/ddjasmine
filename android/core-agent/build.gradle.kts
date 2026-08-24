import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-kernel"))
    // coroutines.core 已由 core-kernel 以 api 暴露，此处不再重复暴露。
    // 统一 HTTP 客户端为 Ktor（okhttp engine 底层仍用 OkHttp 5.3.2，
    // 与插件侧 MCP SDK 的 Ktor 版本一致，避免宿主/插件的 OkHttp 版本冲突）。
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
