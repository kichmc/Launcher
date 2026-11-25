package com.kich.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How long layout building waits for the installed-app list on a cold start. */
private const val APP_LIST_TIMEOUT_MS = 3_000L

/**
 * AK's own first layout for a workspace (see [DefaultWorkspaceLayout]) from the live settings (grid size,
 * dock capacity) and the installed apps. Nothing is stored. Shared by new workspaces and the first workspace.
 * Without apps (cold start) the plan is just the clock widget.
 */
suspend fun buildDefaultLayoutPlan(
    context: Context,
    appRepository: AppRepository,
    settingsRepository: SettingsRepository,
    workspaceName: String,
): DefaultWorkspaceLayout.Plan {
    val settings = settingsRepository.settings.first()
    val apps = withTimeoutOrNull(APP_LIST_TIMEOUT_MS) { appRepository.apps.first { it.isNotEmpty() } }.orEmpty()
    val dockApps = if (apps.isEmpty()) emptyList() else {
        val byKey = apps.associateBy { it.key }
        withContext(Dispatchers.IO) { resolveDefaultDockKeys(context, apps, settingsLast = true) }.mapNotNull { byKey[it] }
    }
    // The category each app declares about itself (games, audio, social…); -1 when it declares none.
    val systemCategories = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        apps.associate { a -> a.key to (runCatching { pm.getApplicationInfo(a.packageName, 0).category }.getOrDefault(-1)) }
    }
    return DefaultWorkspaceLayout.plan(
        apps = apps,
        dockApps = dockApps,
        columns = settings.homeColumns,
        rows = settings.homeRows,
        dockColumns = settings.dockColumns,
        smartspaceSpanY = DefaultWorkspaceLayout.SMARTSPACE_SPAN_Y,
        kind = WorkspaceKind.fromName(workspaceName),
        systemCategories = systemCategories,
    )
}

/**
 * Gives the first workspace of a fresh install its home screen, like a new workspace gets, except that the
 * phone's other launcher is asked first ([ExistingLauncherImport]); its layout is used only if it was
 * actually exported and at least one of its apps is installed here. Otherwise the generated layout is used.
 * Writes nothing unless the workspace's home is still empty ([HomeLayoutRepository.initializeDefaultLayout]).
 * Returns (source, note) for [WorkspaceInitStore] (the note says why an import did not happen), or null when
 * nothing was written because the workspace already had items.
 */
suspend fun initializeFirstWorkspaceLayout(
    context: Context,
    appRepository: AppRepository,
    settingsRepository: SettingsRepository,
    homeLayoutRepository: HomeLayoutRepository,
): Pair<String, String>? {
    val settings = settingsRepository.settings.first()
    val apps = appRepository.apps.first { it.isNotEmpty() }

    var note = ""
    val exported = withContext(Dispatchers.IO) { withTimeoutOrNull(IMPORT_TIMEOUT_MS) { ExistingLauncherImport.export(context) } }
    val imported = when (exported) {
        is ExistingLauncherImport.Result.Exported -> {
            val parsed = ExistingLauncherLayout.parse(exported.xml)
            val plan = parsed?.let { ExistingLauncherLayout.plan(it, apps, settings.homeColumns, settings.homeRows, settings.dockColumns) }
            if (plan == null) note = "${exported.launcherPackage}: export unusable or none of its apps are installed"
            plan?.let { it to exported.launcherPackage }
        }
        is ExistingLauncherImport.Result.Unavailable -> { note = exported.reason; null }
        null -> { note = "import timed out"; null }
    }
    if (imported != null) {
        return if (homeLayoutRepository.initializeDefaultLayout(imported.first)) WorkspaceInitStore.IMPORTED to imported.second else null
    }
    val plan = buildDefaultLayoutPlan(context, appRepository, settingsRepository, workspaceName = "")
    return if (homeLayoutRepository.initializeDefaultLayout(plan)) WorkspaceInitStore.GENERATED to "import unavailable: $note" else null
}

private const val IMPORT_TIMEOUT_MS = 4_000L
