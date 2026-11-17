package dev.bletether.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Starts/stops Wi-Fi tethering and reports state changes.
 *
 * TetheringManager is a @SystemApi, so it is reached via reflection. This works for
 * apps installed as priv-app with TETHER_PRIVILEGED allowlisted. The exact class and
 * method shapes below follow AOSP (API 30+); verify them on Android 16 / LineageOS on
 * a real device — see README "Things to verify".
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

    fun start() {
        runCatching {
            val tm = ctx.getSystemService("tethering")
                ?: error("tethering service not available")
            val tmCls = Class.forName("android.net.TetheringManager")
            val reqCls = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val builderCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")

            val builder = builderCls.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            val request = builderCls.getMethod("build").invoke(builder)

            val callback = Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, args ->
                when (m.name) {
                    "onTetheringFailed" -> {
                        Log.w(TAG, "startTethering failed: ${args?.firstOrNull()}")
                        onState(Protocol.ST_FAILED)
                    }
                    "onTetheringStarted" -> Log.i(TAG, "startTethering ok")
                }
                null
            }

            tmCls.getMethod("startTethering", reqCls, Executor::class.java, cbCls)
                .invoke(tm, request, executor, callback)
            onState(Protocol.ST_STARTING)
        }.onFailure {
            Log.e(TAG, "start() failed", it)
            onState(Protocol.ST_FAILED)
        }
    }

    fun stop() {
        runCatching {
            val tm = ctx.getSystemService("tethering") ?: error("tethering service not available")
            Class.forName("android.net.TetheringManager")
                .getMethod("stopTethering", Int::class.javaPrimitiveType)
                .invoke(tm, TETHERING_WIFI)
            onState(Protocol.ST_STOPPING)
        }.onFailure {
            Log.e(TAG, "stop() failed", it)
            onState(Protocol.ST_FAILED)
        }
    }

    private companion object {
        const val TAG = "TetherController"
        const val TETHERING_WIFI = 0
    }
}
