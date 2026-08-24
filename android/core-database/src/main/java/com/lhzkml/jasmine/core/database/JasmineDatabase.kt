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

package com.lhzkml.jasmine.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Plugin::class, PluginGrant::class, McpServerEntity::class], version = 3)
abstract class JasmineDatabase : RoomDatabase() {
    abstract fun pluginDao(): PluginDao
    abstract fun pluginGrantDao(): PluginGrantDao
    abstract fun mcpServerDao(): McpServerDao
}
