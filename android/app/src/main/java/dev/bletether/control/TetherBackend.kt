package dev.bletether.control

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.Executor

/**
 * How tethering is switched. Picked per command, so Shizuku starting after boot is fine.
 *
 *  - [SystemTetherBackend]:  custom ROM, app installed as priv-app with TETHER_PRIVILEGED.
 *  - [ShizukuTetherBackend]: stock ROM, calls go through Shizuku as the shell user.
 */
interface TetherBackend {
    val name: String
    fun start(executor: Executor, onState: (Byte) -> Unit)
    fun stop(onState: (Byte) -> Unit)

    companion object {
        const val TETHERING_WIFI = 0

        fun hasSystemPermission(ctx: Context): Boolean =
            ctx.checkSelfPermission("android.permission.TETHER_PRIVILEGED") ==
                PackageManager.PERMISSION_GRANTED

        /** Returns the usable backend, or null with a reason in [Selection.reason]. */
        fun select(ctx: Context): Selection = when {
            hasSystemPermission(ctx) -> Selection(SystemTetherBackend(ctx), null)
            !ShizukuTetherBackend.isRunning() -> Selection(null, "Shizuku is not running")
            !ShizukuTetherBackend.isGranted() -> Selection(null, "Shizuku permission not granted")
            else -> Selection(ShizukuTetherBackend(ctx), null)
        }
    }

    data class Selection(val backend: TetherBackend?, val reason: String?)
}
