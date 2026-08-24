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
import kotlinx.coroutines.flow.Flow

/**
 * MCP server 配置的持久化实体。List/Map 字段（args/env/headers）由插件侧
 * 序列化为 JSON 字符串存放；持久化统一走宿主的 Room（core-database）。
 */
@Entity(tableName = "mcp_server")
data class McpServerEntity(
    @PrimaryKey val name: String,
    val url: String?,
    val command: String?,
    val argsJson: String?,
    val envJson: String?,
    val headersJson: String?,
    val accessToken: String?,
    val enabled: Boolean,
    val transportType: String,
)

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_server")
    fun getAll(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_server WHERE name = :name")
    suspend fun findByName(name: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: McpServerEntity)

    @Query("DELETE FROM mcp_server WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("UPDATE mcp_server SET enabled = :enabled WHERE name = :name")
    suspend fun setEnabled(name: String, enabled: Boolean)
}
