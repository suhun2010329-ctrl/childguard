package com.jel.childguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅 후 감시 서비스 재시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.isEnabled(context)) {
            MonitorService.start(context)
        }
    }
}
