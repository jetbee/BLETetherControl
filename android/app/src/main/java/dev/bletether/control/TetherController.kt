package dev.bletether.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * Starts/stops Wi-Fi tethering and reports state changes.
 *
 * The actual switching goes through a [TetherBackend]: priv-app on custom ROMs, Shizuku
 * otherwise. The backend is chosen per command (see [TetherBackend.select]).
 */
class TetherController(
    private val ctx: Context,
    private val onState: (Byte) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }

    private val apStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // WifiManager.WIFI_AP_STATE_*: 10 disabling, 11 disabled, 12 enabling, 13 enabled, 14 failed
            val st = when (i.getIntExtra("wifi_state", 11)) {
                10 -> Protocol.ST_STOPPING
                11 -> Protocol.ST_OFF
                12 -> Protocol.ST_STARTING
                13 -> Protocol.ST_ON
                else -> Protocol.ST_FAILED
            }
            onState(st)
        }
    }

    fun register() {
        ctx.registerReceiver(
            apStateReceiver,
            IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister() {
        runCatching { ctx.unregisterReceiver(apStateReceiver) }
    }

    /** Current state, read via the @SystemApi WifiManager.isWifiApEnabled(). */
    fun currentState(): Byte = runCatching {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val enabled = WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wm) as Boolean
        if (enabled) Protocol.ST_ON else Protocol.ST_OFF
    }.getOrElse {
        Log.w(TAG, "isWifiApEnabled failed", it)
        Protocol.ST_FAILED
    }

    fun start() = withBackend("start") {
        it.start(executor, onState)
        onState(Protocol.ST_STARTING)
    }

    fun stop() = withBackend("stop") {
        it.stop(onState)
        onState(Protocol.ST_STOPPING)
    }

    private fun withBackend(op: String, block: (TetherBackend) -> Unit) {
        val (backend, reason) = TetherBackend.select(ctx)
        if (backend == null) {
            Log.e(TAG, "$op(): no backend: $reason")
            onState(Protocol.ST_FAILED)
            return
        }
        runCatching { block(backend) }.onFailure {
            Log.e(TAG, "$op() via ${backend.name} failed", it)
            onState(Protocol.ST_FAILED)
        }
    }

    private companion object {
        const val TAG = "TetherController"
    }
}
