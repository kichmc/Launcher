package com.kich.launcher.motivation

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.kich.launcher.R

/**
 * The Motivation widget: a real AppWidget (RemoteViews) that appears in the widget picker. The
 * quote is computed from the time on every system update, so no alarms or services are needed.
 */
class MotivationWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, build(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                MotivationStore(context).refresh()
                updateAll(context)
            }
            ACTION_FAVORITE -> {
                val store = MotivationStore(context)
                store.toggleFavorite(store.current())
                updateAll(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.kich.launcher.motivation.REFRESH"
        const val ACTION_FAVORITE = "com.kich.launcher.motivation.FAVORITE"

        /** Redraws every placed Motivation widget (after a setting or quote changed). */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MotivationWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, build(context))
        }

        private fun build(context: Context): RemoteViews {
            val store = MotivationStore(context)
            val quote = store.current()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val views = RemoteViews(context.packageName, R.layout.widget_motivation)
            views.setTextViewText(R.id.quote_text, quote)
            val isFav = store.isFavorite(quote)
            views.setTextViewText(R.id.favorite_button, if (isFav) "\u2605" else "\u2606")
            // Gold star when favorited, white outline otherwise
            views.setTextColor(R.id.favorite_button, if (isFav) 0xFFFFC371.toInt() else 0xFFFFFFFF.toInt())
            views.setOnClickPendingIntent(
                R.id.refresh_button,
                PendingIntent.getBroadcast(
                    context, 1,
                    Intent(context, MotivationWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    flags,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.favorite_button,
                PendingIntent.getBroadcast(
                    context, 2,
                    Intent(context, MotivationWidgetProvider::class.java).setAction(ACTION_FAVORITE),
                    flags,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.quote_text,
                PendingIntent.getActivity(context, 3, Intent(context, MotivationActivity::class.java), flags),
            )
            return views
        }
    }
}
