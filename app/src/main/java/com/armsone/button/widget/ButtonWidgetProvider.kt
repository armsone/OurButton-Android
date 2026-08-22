package com.armsone.button.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.armsone.button.MainActivity
import com.armsone.button.R

private fun widgetIntent(context: Context, action: String, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_WIDGET_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

private fun bindActions(context: Context, views: RemoteViews, child: Boolean) {
    views.setOnClickPendingIntent(R.id.widget_quiet, widgetIntent(context, "quiet", if (child) 11 else 1))
    views.setOnClickPendingIntent(R.id.widget_ding, widgetIntent(context, "ding", if (child) 12 else 2))
    views.setOnClickPendingIntent(R.id.widget_open, widgetIntent(context, "open", if (child) 14 else 4))
    views.setOnClickPendingIntent(R.id.widget_voice, widgetIntent(context, "voice", if (child) 13 else 3))
}

class ParentButtonWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_parent)
            bindActions(context, views, child = false)
            manager.updateAppWidget(id, views)
        }
    }
}

class ChildButtonWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_child)
            bindActions(context, views, child = true)
            manager.updateAppWidget(id, views)
        }
    }
}
