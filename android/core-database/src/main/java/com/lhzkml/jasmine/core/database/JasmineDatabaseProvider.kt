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

import android.content.Context
import androidx.room.Room

/**
 * `JasmineDatabase` 的统一单例访问点：Hilt、框架（core-plugin）、插件都经此获取
 * 同一个 Room 实例，避免各自 `databaseBuilder` 建出多份（同文件多连接）。
 */
object JasmineDatabaseProvider {

    @Volatile
    private var instance: JasmineDatabase? = null

    fun get(context: Context): JasmineDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JasmineDatabase::class.java,
                "Plugin",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
}
