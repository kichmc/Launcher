package com.kich.launcher.motivation

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.kich.launcher.R

/**
 * The Motivation widget: a real AppWidget (RemoteViews) that appears in the widget picker. The
 * quote is computed from the time on every system update, so no alarms or services are needed.
 *
 * It is responsive: each placed widget is drawn from its own size (a one-row compact layout or the
 * normal one, with the text size fitted to the space) and redrawn when the user resizes it. Colours,
 * corner shape, font, size and opacity come from the settings screen.
 */
class MotivationWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, build(context, manager.getAppWidgetOptions(it))) }
    }

    /** The user resized the widget: redraw it for the new size. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        manager.updateAppWidget(appWidgetId, build(context, newOptions))
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

        /** Below this height (dp) the one-row layout is used. */
        private const val COMPACT_BELOW_DP = 100

        private val FILL = intArrayOf(
            R.drawable.widget_motivation_fill_sharp,
            R.drawable.widget_motivation_fill_soft,
            R.drawable.widget_motivation_fill_round,
        )
        private val GLOW = intArrayOf(
            R.drawable.widget_motivation_glow_sharp,
            R.drawable.widget_motivation_glow_soft,
            R.drawable.widget_motivation_glow_round,
        )

        /** Redraws every placed Motivation widget (after a setting or quote changed). */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MotivationWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, build(context, manager.getAppWidgetOptions(it))) }
        }

        private fun build(context: Context, options: Bundle?): RemoteViews {
            val store = MotivationStore(context)
            val quote = store.current()
            val style = MotivationStyle.resolve(context, store)
            val isFav = store.isFavorite(quote)

            // Size the launcher reports for this widget: portrait uses min width / max height,
            // landscape the other way round. 0 or missing means "unknown", so use the default size.
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthDp = (options?.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0)
                .takeIf { it > 0 } ?: 250
            val heightDp = (options?.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0)
                .takeIf { it > 0 } ?: 110

            val compact = heightDp < COMPACT_BELOW_DP
            val showRefresh = !compact || widthDp >= 200

            // The user's size slider scales text, padding and buttons together. Buttons can only be
            // resized on Android 12+ (setViewLayoutWidth); before that they keep their base size.
            val scale = store.sizeScale / 100f
            val baseChip = if (compact) 34f else 40f
            val chipDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                minOf(baseChip * scale, heightDp * (if (compact) 0.7f else 0.35f)).coerceAtLeast(24f)
            } else {
                baseChip
            }
            val padStart = (if (compact) 16f else 20f) * scale
            val padTop = (if (compact) 8f else 14f) * scale
            val padEnd = (if (compact) 8f else 12f) * scale
            val padBottom = (if (compact) 8f else 10f) * scale

            // Room left for the quote after padding, header and buttons (all in dp).
            val textWidth: Float
            val textHeight: Float
            if (compact) {
                textWidth = widthDp - padStart - padEnd - 6f - chipDp - (if (showRefresh) chipDp + 6f else 0f)
                textHeight = heightDp - padTop - padBottom
            } else {
                textWidth = widthDp - padStart - padEnd - 8f
                textHeight = heightDp - padTop - padBottom - (if (style.showLabel) 18f else 0f) - chipDp
            }
            val maxSp = (if (compact) minOf(style.maxSp, 18f) else style.maxSp) * scale
            val fit = MotivationStyle.fitText(quote, textWidth, textHeight, maxSp, style.font)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val views = RemoteViews(
                context.packageName,
                if (compact) R.layout.widget_motivation_compact else R.layout.widget_motivation,
            )

            // Background: a solid card plus a soft glow, both tinted to the theme and faded to the opacity.
            val alpha = style.opacity * 255 / 100
            views.setImageViewResource(R.id.bg_fill, FILL[style.corner])
            views.setInt(R.id.bg_fill, "setColorFilter", style.fill)
            views.setInt(R.id.bg_fill, "setImageAlpha", alpha)
            views.setImageViewResource(R.id.bg_glow, GLOW[style.corner])
            views.setInt(R.id.bg_glow, "setColorFilter", style.glow)
            views.setInt(R.id.bg_glow, "setImageAlpha", alpha)

            // Quote: show only the chosen font, sized to fit.
            val openSettings = PendingIntent.getActivity(context, 3, Intent(context, MotivationActivity::class.java), flags)
            intArrayOf(R.id.quote_text, R.id.quote_sans, R.id.quote_mono).forEachIndexed { index, id ->
                val chosen = index == style.font
                views.setViewVisibility(id, if (chosen) View.VISIBLE else View.GONE)
                if (chosen) {
                    views.setTextViewText(id, quote)
                    views.setTextColor(id, style.text)
                    views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, fit.sp)
                    views.setInt(id, "setMaxLines", fit.maxLines)
                }
                views.setOnClickPendingIntent(id, openSettings)
            }

            // Spacing and buttons follow the size slider.
            val density = context.resources.displayMetrics.density
            views.setViewPadding(
                R.id.content,
                (padStart * density).toInt(), (padTop * density).toInt(),
                (padEnd * density).toInt(), (padBottom * density).toInt(),
            )
            intArrayOf(R.id.favorite_button, R.id.refresh_button).forEach { id ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    views.setViewLayoutWidth(id, chipDp, TypedValue.COMPLEX_UNIT_DIP)
                    views.setViewLayoutHeight(id, chipDp, TypedValue.COMPLEX_UNIT_DIP)
                }
                views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, chipDp * 0.5f)
            }

            // Buttons
            val chip = if (style.lightText) R.drawable.widget_motivation_chip_light else R.drawable.widget_motivation_chip_dark
            views.setInt(R.id.favorite_button, "setBackgroundResource", chip)
            views.setTextViewText(R.id.favorite_button, if (isFav) "\u2605" else "\u2606")
            views.setTextColor(R.id.favorite_button, if (isFav) style.accent else style.text)
            views.setInt(R.id.refresh_button, "setBackgroundResource", chip)
            views.setTextColor(R.id.refresh_button, style.text)
            views.setViewVisibility(R.id.refresh_button, if (showRefresh) View.VISIBLE else View.GONE)
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

            // Header exists only in the normal layout.
            if (!compact) {
                views.setViewVisibility(R.id.header_row, if (style.showLabel) View.VISIBLE else View.GONE)
                views.setInt(R.id.accent_bar, "setColorFilter", style.accent)
                views.setTextColor(R.id.header_label, style.accent)
            }
            return views
        }
    }
}
