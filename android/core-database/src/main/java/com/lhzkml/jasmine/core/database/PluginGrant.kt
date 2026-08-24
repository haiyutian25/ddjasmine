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

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 运行时授权账本条目：记录某插件对某 permissionKey 的授权态（Ask 授权）。
 * 取代此前的 plugin_grants.json 手动文件，统一走宿主的 Room 持久化。
 */
@Entity(tableName = "plugin_grant")
data class PluginGrant(
    val pluginId: String,
    val permissionKey: String,
    val granted: Boolean,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}

@Dao
interface PluginGrantDao {
    @Query("SELECT * FROM plugin_grant WHERE granted = 1")
    fun grantedEntries(): List<PluginGrant>

    @Query("DELETE FROM plugin_grant WHERE pluginId = :pluginId")
    suspend fun deleteByPlugin(pluginId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(grant: PluginGrant)
}
