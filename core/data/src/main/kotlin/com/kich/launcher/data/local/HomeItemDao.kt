package com.kich.launcher.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeItemDao {
    @Query("SELECT activeProfileId FROM workspace_state WHERE id = 1 LIMIT 1")
    suspend fun activeProfileId(): String?

    @Query("INSERT OR REPLACE INTO workspace_state(id, activeProfileId) VALUES (1, :profileId)")
    suspend fun setActiveProfileId(profileId: String)

    @Query("DELETE FROM home_items WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Query("SELECT COUNT(*) FROM home_items WHERE profileId = :profileId")
    suspend fun profileRowCount(profileId: String): Int

    /** Every row (home items, folders, and folder children) in the active AK workspace. */
    @Query("SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') ORDER BY containerId ASC, page ASC, cellY ASC, cellX ASC")
    fun observeAll(): Flow<List<HomeItemEntity>>

    @Query("SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId ORDER BY page ASC, cellY ASC, cellX ASC")
    suspend fun getContainerOrdered(containerId: Long): List<HomeItemEntity>

    @Query("SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId")
    suspend fun getContainer(containerId: Long): List<HomeItemEntity>

    /** Every row in both containers of the active AK workspace, for the ghost-row sweep. */
    @Query("SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1')")
    suspend fun getAll(): List<HomeItemEntity>

    @Query("SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id LIMIT 1")
    suspend fun getById(id: Long): HomeItemEntity?

    @Query(
        "SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND packageName = :pkg " +
            "AND className = :cls AND userSerial = :user LIMIT 1",
    )
    suspend fun getByKey(containerId: Long, pkg: String, cls: String, user: Long): HomeItemEntity?

    @Query(
        "SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND page = :page " +
            "AND cellX = :cellX AND cellY = :cellY LIMIT 1",
    )
    suspend fun getAt(containerId: Long, page: Int, cellX: Int, cellY: Int): HomeItemEntity?

    @Insert
    suspend fun insert(item: HomeItemEntity): Long

    @Query("DELETE FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND packageName = :pkg " +
            "AND className = :cls AND userSerial = :user",
    )
    suspend fun deleteByKey(containerId: Long, pkg: String, cls: String, user: Long)

    @Query("DELETE FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1')")
    suspend fun clear()

    @Query(
        "SELECT COUNT(*) FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND packageName = :pkg " +
            "AND className = :cls AND userSerial = :user",
    )
    suspend fun count(containerId: Long, pkg: String, cls: String, user: Long): Int

    @Query("SELECT COUNT(*) FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId")
    suspend fun childCount(containerId: Long): Int

    @Query(
        "UPDATE home_items SET containerId = :containerId, page = :page, cellX = :cellX, cellY = :cellY " +
            "WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id",
    )
    suspend fun moveById(id: Long, containerId: Long, page: Int, cellX: Int, cellY: Int)

    /** Shifts the page of every row in [containerId] at or after [from] by [offset]. */
    @Query("UPDATE home_items SET page = page + :offset WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND page >= :from")
    suspend fun offsetPages(containerId: Long, from: Int, offset: Int)

    @Query("SELECT COUNT(*) FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND page = :page")
    suspend fun countOnPage(containerId: Long, page: Int): Int

    /** Ids of the folder rows on [page] of [containerId]; their children live under those ids. */
    @Query("SELECT id FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND page = :page AND folderName IS NOT NULL")
    suspend fun folderIdsOnPage(containerId: Long, page: Int): List<Long>

    @Query("DELETE FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId IN (:containerIds)")
    suspend fun deleteByContainers(containerIds: List<Long>)

    @Query("DELETE FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND containerId = :containerId AND page = :page")
    suspend fun deleteOnPage(containerId: Long, page: Int)

    /** Points an app row at another launcher activity of the same package (see the ghost sweep). */
    @Query("UPDATE home_items SET className = :className WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id")
    suspend fun updateClassName(id: Long, className: String)

    @Query("UPDATE home_items SET folderName = :name WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id")
    suspend fun renameFolder(id: Long, name: String)

    @Query("UPDATE home_items SET spanX = :spanX, spanY = :spanY WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id")
    suspend fun updateSpans(id: Long, spanX: Int, spanY: Int)

    /** Binds a restored (placeholder) widget row to its freshly allocated device-local [appWidgetId]. */
    @Query("UPDATE home_items SET appWidgetId = :appWidgetId WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id")
    suspend fun updateWidgetId(id: Long, appWidgetId: Int)

    /** Unbinds a widget row (stale id from another device's restore) back into a placeholder. */
    @Query("UPDATE home_items SET appWidgetId = NULL WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND id = :id")
    suspend fun clearWidgetId(id: Long)

    /** The device-local ids of every currently-bound widget, to reconcile against the AppWidgetHost. */
    @Query("SELECT appWidgetId FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1') AND appWidgetId IS NOT NULL")
    suspend fun boundWidgetIds(): List<Int>

    /** Widget IDs owned by every AK workspace; inactive profiles must not lose their host IDs. */
    @Query("SELECT appWidgetId FROM home_items WHERE appWidgetId IS NOT NULL")
    suspend fun allWorkspaceBoundWidgetIds(): List<Int>

    @Query("SELECT * FROM home_items WHERE appWidgetId IS NOT NULL")
    suspend fun allWorkspaceWidgetRows(): List<HomeItemEntity>

    @Query("UPDATE home_items SET appWidgetId = NULL WHERE id = :id")
    suspend fun clearWidgetIdInAnyWorkspace(id: Long)

    @Query("SELECT * FROM home_items WHERE profileId = COALESCE((SELECT activeProfileId FROM workspace_state WHERE id = 1), 'user1')")
    suspend fun getAllOnce(): List<HomeItemEntity>

    /** Atomically replaces only the active workspace layout (including its widgets). */
    @Transaction
    suspend fun replaceLayout(items: List<HomeItemEntity>) {
        clear()
        val owner = activeProfileId() ?: "user1"
        for (item in items) insert(item.copy(profileId = owner))
    }

    /** Copies a layout into a different workspace with fresh row IDs and remapped folder links. */
    @Transaction
    suspend fun replaceLayoutWithFreshIds(items: List<HomeItemEntity>) {
        clear()
        val owner = activeProfileId() ?: "user1"
        val rootRows = items.filter { it.containerId == HomeItemEntity.HOME || it.containerId == HomeItemEntity.DOCK }
        val idMap = mutableMapOf<Long, Long>()
        for (row in rootRows) {
            val newId = insert(row.copy(id = 0L, profileId = owner))
            idMap[row.id] = newId
        }
        for (row in items.filter { it.containerId != HomeItemEntity.HOME && it.containerId != HomeItemEntity.DOCK }) {
            val newContainer = idMap[row.containerId] ?: continue
            insert(row.copy(id = 0L, containerId = newContainer, profileId = owner))
        }
    }
}
