package com.smriti.app.live

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.smriti.app.R
import com.smriti.app.ui.MainActivity

/**
 * Home-screen widget with three buttons: Ask by voice, Phone camera, Smart glasses.
 * Each one opens Smriti straight into the matching Live mode.
 */
class SmritiWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_smriti)
            views.setOnClickPendingIntent(R.id.w_ask, open(context, "voice", 1))
            views.setOnClickPendingIntent(R.id.w_phone, open(context, "phone", 2))
            views.setOnClickPendingIntent(R.id.w_glasses, open(context, "glasses", 3))
            views.setOnClickPendingIntent(R.id.w_cctv, open(context, "cctv", 4))
            manager.updateAppWidget(id, views)
        }
    }

    private fun open(context: Context, what: String, code: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(LiveService.EXTRA_LIVE, what)
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
