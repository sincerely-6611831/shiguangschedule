package com.xingheyuzhuan.shiguangschedule.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 手机解锁后刷新所有已添加的小组件。
 * 由 Application 动态注册，仅在应用进程存活时接收广播。
 */
class WidgetUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT || !hasActiveWidgets(context)) {
            return
        }

        WorkManagerHelper.enqueueUnlockRefresh(context)
    }
}
