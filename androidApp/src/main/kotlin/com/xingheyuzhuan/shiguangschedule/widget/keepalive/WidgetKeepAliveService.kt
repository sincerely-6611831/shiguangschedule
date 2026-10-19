package com.xingheyuzhuan.shiguangschedule.widget.keepalive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xingheyuzhuan.shiguangschedule.R

/** 由用户主动开启，以常驻通知维持 Application 中的解锁监听。 */
class WidgetKeepAliveService : Service() {
    private var overlay: View? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !isEnabled(this)) {
            preferences(this).edit().putBoolean(ENABLED, false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.widget_keepalive_channel), NotificationManager.IMPORTANCE_LOW
        ))
        val settings = PendingIntent.getActivity(this, 0,
            Intent(this, WidgetKeepAliveActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, WidgetKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.widget_keepalive_title))
            .setContentText(getString(R.string.widget_keepalive_notification))
            .setContentIntent(settings)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.widget_keepalive_stop), stop)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true
        updateOverlay()
        return START_STICKY
    }

    private fun updateOverlay() {
        if (!isOverlayEnabled(this) || !Settings.canDrawOverlays(this)) {
            removeOverlay()
            return
        }
        if (overlay != null) return
        val view = View(this)
        val params = WindowManager.LayoutParams(1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            getSystemService(WindowManager::class.java).addView(view, params)
            overlay = view
            isOverlayAttached = true
        } catch (e: RuntimeException) {
            Log.w("WidgetKeepAlive", "无法添加透明保活悬浮窗", e)
        }
    }

    private fun removeOverlay() {
        overlay?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeView(view) }
                .onFailure { Log.w("WidgetKeepAlive", "移除保活悬浮窗失败", it) }
        }
        overlay = null
        isOverlayAttached = false
    }

    override fun onDestroy() {
        removeOverlay()
        isRunning = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "widget_unlock_keepalive"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_STOP = "com.xingheyuzhuan.shiguangschedule.STOP_WIDGET_KEEPALIVE"
        private const val ENABLED = "foreground_enabled"
        private const val OVERLAY_ENABLED = "overlay_enabled"
        var isRunning by mutableStateOf(false)
            private set
        var isOverlayAttached by mutableStateOf(false)
            private set

        internal fun preferences(context: Context) =
            context.getSharedPreferences("widget_keepalive", Context.MODE_PRIVATE)

        fun isEnabled(context: Context) = preferences(context).getBoolean(ENABLED, false)

        fun isOverlayEnabled(context: Context) = preferences(context).getBoolean(OVERLAY_ENABLED, false)

        fun setOverlayEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(OVERLAY_ENABLED, enabled).apply()
            if (isEnabled(context)) {
                ContextCompat.startForegroundService(context, Intent(context, WidgetKeepAliveService::class.java))
            }
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(ENABLED, enabled).apply()
            if (enabled) {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, WidgetKeepAliveService::class.java))
                } catch (e: RuntimeException) {
                    preferences(context).edit().putBoolean(ENABLED, false).apply()
                    throw e
                }
            } else {
                context.stopService(Intent(context, WidgetKeepAliveService::class.java))
            }
        }

        fun startIfEnabled(context: Context) {
            if (!isEnabled(context) || isRunning) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WidgetKeepAliveService::class.java))
            } catch (e: RuntimeException) {
                // 某些系统禁止开机启动前台服务；保留用户选择，待下次打开应用重试。
                Log.w("WidgetKeepAlive", "系统暂未允许启动小组件保活服务", e)
            }
        }
    }
}

class WidgetKeepAliveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WidgetKeepAliveService.startIfEnabled(context)
        }
    }
}
