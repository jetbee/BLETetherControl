package dev.bletether.control

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * Setup screen: grants runtime permissions, asks Shizuku for permission (stock ROM),
 * and starts the BLE service. Not needed day to day — the service also starts on boot.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val binderListener = Shizuku.OnBinderReceivedListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply { setPadding(0, 0, 0, 32) }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(statusView)
            addView(button("Grant Bluetooth / notification permissions") {
                requestPermissions(RUNTIME_PERMISSIONS, REQ_RUNTIME)
            })
            addView(button("Grant Shizuku permission") {
                if (ShizukuTetherBackend.isRunning()) Shizuku.requestPermission(ShizukuTetherBackend.REQUEST_CODE)
                refresh()
            })
            addView(button("Start BLE service") {
                if (BleGattService.canStart(this@MainActivity)) {
                    startForegroundService(Intent(this@MainActivity, BleGattService::class.java))
                }
                refresh()
            })
        }
        setContentView(layout)

        Shizuku.addRequestPermissionResultListener(shizukuListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun refresh() = runOnUiThread {
        val mode = when {
            TetherBackend.hasSystemPermission(this) -> "system (priv-app)"
            ShizukuTetherBackend.isGranted() -> "Shizuku"
            ShizukuTetherBackend.isRunning() -> "Shizuku running, permission not granted"
            else -> "none — install as priv-app, or start Shizuku"
        }
        val runtime = if (hasRuntimePermissions()) "granted" else "missing"
        statusView.text = "Tethering backend: $mode\nBluetooth / notification permissions: $runtime"
    }

    private fun hasRuntimePermissions() = RUNTIME_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private companion object {
        const val REQ_RUNTIME = 1
        val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
