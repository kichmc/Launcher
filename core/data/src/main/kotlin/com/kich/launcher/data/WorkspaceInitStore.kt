package com.kich.launcher.data

import android.content.Context

/**
 * Where a workspace's first layout came from, kept in the same SharedPreferences file as the workspace
 * list (`ak_workspace_profiles`, keys next to `layout_partitioned_<id>`). The layout itself (order, pages,
 * dock, folders) is the normal Room rows of that workspace; this only records that it was initialised and how.
 * A workspace with no record existed before this was kept (its layout is the user's, never regenerated).
 */
object WorkspaceInitStore {
    const val PREFS = "ak_workspace_profiles"
    const val IMPORTED = "imported"
    const val GENERATED = "generated"

    fun sourceKey(id: String) = "layout_init_$id"
    fun noteKey(id: String) = "layout_init_note_$id"

    fun activeId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("active", "user1") ?: "user1"

    fun record(context: Context, id: String, source: String, note: String = "") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(sourceKey(id), source).putString(noteKey(id), note.take(300)).apply()
    }

    /** [IMPORTED], [GENERATED], or null for a workspace that has no record. */
    fun source(context: Context, id: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sourceKey(id), null)
}
