package dev.voltguard.battery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import dev.voltguard.battery.R
import dev.voltguard.battery.util.QuickSettingsExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoltGuardAllOffWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(context, VoltGuardAllOffWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_ALL_OFF
            }
            val pi = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget_all_off)
            views.setOnClickPendingIntent(R.id.widget_all_off_root, pi)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_ALL_OFF) {
            CoroutineScope(Dispatchers.Default).launch {
                QuickSettingsExecutor.cutBackgroundAppNetwork(context)
                val res = QuickSettingsExecutor.executeAllOff(context)
                val done = res.count { it.state == dev.voltguard.battery.engine.ItemExecState.DONE }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "VoltGuard: $done toggles OFF (Hotspot/Rotation untouched)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_ALL_OFF = "dev.voltguard.battery.ACTION_WIDGET_ALL_OFF"
    }
}
