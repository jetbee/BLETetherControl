package dev.bletether.control

import java.util.UUID

/** BLE protocol shared with mac/bletether_menubar.py. Keep both in sync. */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("7b1e0001-6a4f-4c2e-9d55-0b1e7e7e7e01")
    val COMMAND_UUID: UUID = UUID.fromString("7b1e0002-6a4f-4c2e-9d55-0b1e7e7e7e01") // Write
    val STATUS_UUID: UUID = UUID.fromString("7b1e0003-6a4f-4c2e-9d55-0b1e7e7e7e01")  // Read + Notify
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Commands (first byte written to COMMAND)
    const val CMD_TETHER_OFF: Byte = 0x00
    const val CMD_TETHER_ON: Byte = 0x01
    const val CMD_QUERY: Byte = 0x02

    // Status (single byte on STATUS)
    const val ST_OFF: Byte = 0x00
    const val ST_ON: Byte = 0x01
    const val ST_STARTING: Byte = 0x02
    const val ST_STOPPING: Byte = 0x03
    const val ST_FAILED: Byte = 0xFF.toByte()
}
