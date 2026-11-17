package dev.bletether.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service hosting the BLE GATT server.
 *
 *  COMMAND (write, encrypted): 0x00 off, 0x01 on, 0x02 query
 *  STATUS  (read + notify, encrypted): see Protocol.ST_*
 *
 * Both characteristics require an encrypted (bonded) link, so only a paired Mac can
 * switch tethering. Do not weaken this: otherwise anyone nearby could enable the hotspot.
 */
class BleGattService : Service() {

    private lateinit var gattServer: BluetoothGattServer
    private lateinit var tether: TetherController
    private lateinit var statusChar: BluetoothGattCharacteristic
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    @Volatile private var lastStatus: Byte = Protocol.ST_OFF

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Callers check canStart() first; this only guards against a bad start.
        if (!canStart(this)) {
            Log.e(TAG, "Bluetooth permissions missing; open the app once to grant them")
            stopSelf()
            return
        }
        startAsForeground()

        tether = TetherController(this) { publishStatus(it) }
        tether.register()
        lastStatus = tether.currentState()

        val btManager = getSystemService(BluetoothManager::class.java)
        gattServer = btManager.openGattServer(this, gattCallback)
        gattServer.addService(buildService())
        startAdvertising(btManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        runCatching { tether.unregister() }
        runCatching { gattServer.close() }
        super.onDestroy()
    }

    // --- GATT -----------------------------------------------------------------------------

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val command = BluetoothGattCharacteristic(
            Protocol.COMMAND_UUID, PROPERTY_WRITE, PERMISSION_WRITE_ENCRYPTED,
        )
        statusChar = BluetoothGattCharacteristic(
            Protocol.STATUS_UUID, PROPERTY_READ or PROPERTY_NOTIFY, PERMISSION_READ_ENCRYPTED,
        )
        val cccd = BluetoothGattDescriptor(
            Protocol.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
        )
        statusChar.addDescriptor(cccd)

        service.addCharacteristic(command)
        service.addCharacteristic(statusChar)
        return service
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) subscribers.remove(device)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            var result = BluetoothGatt.GATT_SUCCESS
            if (characteristic.uuid != Protocol.COMMAND_UUID || value.isEmpty()) {
                result = BluetoothGatt.GATT_FAILURE
            } else {
                when (value[0]) {
                    Protocol.CMD_TETHER_ON -> tether.start()
                    Protocol.CMD_TETHER_OFF -> tether.stop()
                    Protocol.CMD_QUERY -> publishStatus(tether.currentState())
                    else -> result = BluetoothGatt.GATT_FAILURE
                }
            }
            if (responseNeeded) gattServer.sendResponse(device, requestId, result, 0, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            gattServer.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(lastStatus),
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == Protocol.CCCD_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribers.add(device)
                    // Send the current state right after subscribing.
                    notify(device, lastStatus)
                } else {
                    subscribers.remove(device)
                }
            }
            if (responseNeeded) gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun publishStatus(status: Byte) {
        lastStatus = status
        subscribers.forEach { notify(it, status) }
    }

    private fun notify(device: BluetoothDevice, status: Byte) {
        runCatching {
            gattServer.notifyCharacteristicChanged(device, statusChar, false, byteArrayOf(status))
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    // --- Advertising ----------------------------------------------------------------------

    private fun startAdvertising(btManager: BluetoothManager) {
        val advertiser = btManager.adapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising not supported"); return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
            .build()
        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { Log.e(TAG, "advertise failed: $errorCode") }
        })
    }

    // --- Foreground notification ----------------------------------------------------------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "BLE Tether Control", NotificationManager.IMPORTANCE_MIN),
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("BLETetherControl")
            .setContentText("Waiting for BLE commands")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    companion object {
        private const val TAG = "BleGattService"
        private const val CHANNEL = "ble_tether_control"

        private val REQUIRED = listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        )

        /** A normal install (Shizuku mode) needs these granted from MainActivity first. */
        fun canStart(ctx: android.content.Context) = REQUIRED.all {
            ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
}
