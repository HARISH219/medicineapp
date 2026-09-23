package com.tbmedtrack.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.tbmedtrack.app.MainActivity
import com.tbmedtrack.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cute companion widget. Read-only: tapping opens the app; it never records medication.
 * Renders the real medication status from the local database via [WidgetStateProvider].
 */
class MedTrackWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        renderAll(context, mgr, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        renderAll(context, mgr, intArrayOf(appWidgetId))
    }

    private fun renderAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = WidgetStateProvider.compute(context)
                for (id in ids) {
                    val options = mgr.getAppWidgetOptions(id)
                    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    // Use the taller 2x2 layout when the host gives us enough height.
                    val layout = if (minHeight >= 110) R.layout.widget_medtrack_2x2
                    else R.layout.widget_medtrack_2x1
                    val views = buildViews(context, layout, state)
                    mgr.updateAppWidget(id, views)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, layout: Int, state: WidgetState): RemoteViews {
        val views = RemoteViews(context.packageName, layout)
        views.setImageViewResource(R.id.widget_mascot, state.mascotRes)
        views.setTextViewText(R.id.widget_status, state.statusText)
        views.setContentDescription(R.id.widget_root, state.contentDescription)

        val dotIds = intArrayOf(R.id.dot1, R.id.dot2, R.id.dot3, R.id.dot4)
        for (i in dotIds.indices) {
            if (i < state.dotRes.size) {
                views.setViewVisibility(dotIds[i], View.VISIBLE)
                views.setImageViewResource(dotIds[i], state.dotRes[i])
            } else {
                views.setViewVisibility(dotIds[i], View.GONE)
            }
        }

        // Tap opens the app. It NEVER records medication.
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        return views
    }

    companion object {
        /** Refresh every placed MedTrack widget. Call after status changes or sync. */
        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MedTrackWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(context, MedTrackWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
