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

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The markdown renderer's android AARs lag behind on the mirror
        // (metadata lists the version, files 404), so this group resolves
        // straight from Maven Central.
        maven("https://repo1.maven.org/maven2") {
            name = "MavenCentralOfficial"
            content { includeGroup("com.mikepenz") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "Jasmine"

include(":app")
include(":core-agent")
include(":core-data")
include(":core-database")
include(":core-kernel")
include(":core-kernel-ksp")
include(":core-plugin")
include(":core-plugin-ksp")
include(":core-testing")
include(":core-ui")
include(":feature-plugin-navigation")
include(":feature-session-navigation")
include(":feature-session")
include(":feature-plugin")
include(":sample-plugin")
include(":sample-guide")
include(":sample-example")
include(":test-app")
