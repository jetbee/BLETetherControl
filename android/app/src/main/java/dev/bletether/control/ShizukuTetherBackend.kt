package dev.bletether.control

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.IBinder
import java.util.concurrent.Executor
import java.util.function.Supplier
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Stock ROM: talk to the tethering service through Shizuku, so the binder calls run as
 * the shell user (uid 2000), which holds TETHER_PRIVILEGED.
 *
 * A private TetheringManager is built around a Shizuku-wrapped binder. The tethering
 * service checks that the caller package belongs to the calling uid, so the context
 * reports "com.android.shell" as its op package.
 *
 * Uses the hidden constructor TetheringManager(Context, Supplier<IBinder>); hidden API
 * checks are lifted in [BleTetherApp]. Verify on a real device — see README.
 */
class ShizukuTetherBackend(ctx: Context) : TetherBackend {
    override val name = "shizuku"

    private val shellContext = object : ContextWrapper(ctx.applicationContext) {
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
    }

    private val manager: Any by lazy {
        val binder = SystemServiceHelper.getSystemService("tethering")
            ?: error("tethering service not available")
        val wrapped: IBinder = ShizukuBinderWrapper(binder)
        TetheringReflection.tmCls
            .getConstructor(Context::class.java, Supplier::class.java)
            .newInstance(shellContext, Supplier<IBinder> { wrapped })
    }

    override fun start(executor: Executor, onState: (Byte) -> Unit) {
        TetheringReflection.startWifi(manager, executor, onState)
    }

    override fun stop(onState: (Byte) -> Unit) {
        TetheringReflection.stopWifi(manager)
    }

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val REQUEST_CODE = 1001

        fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun isGranted(): Boolean = isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }
}
