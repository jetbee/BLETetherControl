package dev.bletether.control

import android.content.Context
import android.util.Log
import java.util.concurrent.Executor

/**
 * Custom ROM: the app lives in /system/priv-app with TETHER_PRIVILEGED allowlisted,
 * so the regular TetheringManager service can be used directly.
 */
class SystemTetherBackend(private val ctx: Context) : TetherBackend {
    override val name = "system"

    override fun start(executor: Executor, onState: (Byte) -> Unit) {
        val tm = ctx.getSystemService("tethering") ?: error("tethering service not available")
        TetheringReflection.startWifi(tm, executor, onState)
    }

    override fun stop(onState: (Byte) -> Unit) {
        val tm = ctx.getSystemService("tethering") ?: error("tethering service not available")
        TetheringReflection.stopWifi(tm)
    }
}

/**
 * TetheringManager is a @SystemApi, so it is reached via reflection. The shapes follow
 * AOSP (API 30+); verify them on a real device — see README "Things to verify".
 */
internal object TetheringReflection {
    private const val TAG = "TetheringReflection"

    val tmCls: Class<*> by lazy { Class.forName("android.net.TetheringManager") }

    fun startWifi(tm: Any, executor: Executor, onState: (Byte) -> Unit) {
        val reqCls = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builderCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val cbCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")

        val builder = builderCls.getConstructor(Int::class.javaPrimitiveType)
            .newInstance(TetherBackend.TETHERING_WIFI)
        val request = builderCls.getMethod("build").invoke(builder)

        val callback = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, m, args ->
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
    }

    fun stopWifi(tm: Any) {
        tmCls.getMethod("stopTethering", Int::class.javaPrimitiveType)
            .invoke(tm, TetherBackend.TETHERING_WIFI)
    }
}
