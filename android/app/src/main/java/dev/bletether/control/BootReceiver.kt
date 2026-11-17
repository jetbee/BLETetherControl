package dev.bletether.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BleGattService.canStart(context)) return
        // Only a real boot broadcast may start a foreground service from the background.
        runCatching { context.startForegroundService(Intent(context, BleGattService::class.java)) }
            .onFailure { android.util.Log.w("BootReceiver", "could not start service", it) }
    }
}
