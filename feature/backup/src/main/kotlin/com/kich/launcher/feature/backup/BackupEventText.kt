package com.kich.launcher.feature.backup

import android.content.res.Resources

/** The text [event] shows: on the backup screen's snackbar and in the "Open with" import's dialog. */
internal fun backupEventMessage(res: Resources, event: BackupEvent): String = when (event) {
    is BackupEvent.Exported -> res.getString(R.string.backup_exported)
    is BackupEvent.Restored -> {
        val base = if (event.skipped > 0) {
            res.getQuantityString(R.plurals.backup_restored_skipped, event.skipped, event.skipped)
        } else {
            res.getString(R.string.backup_restored)
        }
        // Widgets are never "restored": Android widget ids belong to this device, so each one is a placeholder.
        if (event.widgetsNeedSetup > 0) {
            base + ". " + res.getQuantityString(R.plurals.backup_widgets_need_setup, event.widgetsNeedSetup, event.widgetsNeedSetup)
        } else base
    }
    BackupEvent.InvalidFile -> res.getString(R.string.backup_import_invalid)
    BackupEvent.TooLarge -> res.getString(R.string.backup_error_too_large)
    BackupEvent.ReadBlocked -> res.getString(R.string.backup_import_read_blocked)
    BackupEvent.ReadTimedOut -> res.getString(R.string.backup_import_timeout)
    is BackupEvent.Failed -> {
        val failed = res.getString(R.string.backup_failed)
        if (event.message.isBlank()) failed else "$failed: ${event.message}"
    }
}
