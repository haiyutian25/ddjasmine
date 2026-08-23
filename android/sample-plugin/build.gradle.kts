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
 * Sample plugin built as a library module: jasmine.plugin-pack turns the
 * release AAR into a signed plugin APK (aapt2 with a partitioned
 * package-id, d8, signer), and jasmine.plugin-dev injects that APK into the
 * host's assets/plugins at build time.
 */
plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("jasmine.plugin-pack")
}

android {
    namespace = "jasmine.sample.hello"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = false
        buildConfig = false
        shaders = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Packaging: plugin package-id 0x81 (host keeps 0x7f; slot 1).
pluginPack {
    packageIdSlot.set(1)
}

dependencies {
    compileOnly(project(":core-plugin"))
    compileOnly(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)

    ksp(project(":core-plugin-ksp"))
}
