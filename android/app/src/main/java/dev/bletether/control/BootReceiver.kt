package dev.bletether.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BleGattService.canStart(context)) return
        context.startForegroundService(Intent(context, BleGattService::class.java))
    }
}
