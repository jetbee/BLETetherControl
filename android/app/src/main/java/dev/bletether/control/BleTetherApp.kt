package dev.bletether.control

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class BleTetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // A normal (non-system) install is subject to hidden API checks, which block the
        // reflection on TetheringManager / WifiManager. Not needed for priv-app, harmless there.
        HiddenApiBypass.addHiddenApiExemptions("L")
    }
}
