package com.ultimaterecovery.pro.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ultimaterecovery.pro.service.RecycleBinMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, RecycleBinMonitorService::class.java)
            serviceIntent.action = "com.ultimaterecovery.pro.ACTION_MONITOR_RECYCLE_BIN"
            context.startForegroundService(serviceIntent)
        }
    }
}
