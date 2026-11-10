package com.kich.launcher.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persisted selector for the Room home-layout partition currently exposed to launcher queries. */
@Entity(tableName = "workspace_state")
data class WorkspaceStateEntity(
    @PrimaryKey val id: Int = 1,
    val activeProfileId: String = "user1",
)
