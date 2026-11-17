#!/usr/bin/env python3
"""Menu bar controller for BLETetherControl (Android side: ../android).

Config: ~/.bletether.json
  {
    "ssid": "Pixel-hotspot",        # hotspot SSID to join once tethering is ON
    "password": "xxxxxxxx",
    "wifi_interface": "en0"          # optional, default en0
  }
Keep that file private (chmod 600).
"""
import asyncio
import json
import subprocess
import threading
from pathlib import Path

import rumps
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "7b1e0001-6a4f-4c2e-9d55-0b1e7e7e7e01"
COMMAND_UUID = "7b1e0002-6a4f-4c2e-9d55-0b1e7e7e7e01"
STATUS_UUID = "7b1e0003-6a4f-4c2e-9d55-0b1e7e7e7e01"

CMD_OFF, CMD_ON, CMD_QUERY = b"\x00", b"\x01", b"\x02"
STATUS_NAMES = {0x00: "OFF", 0x01: "ON", 0x02: "starting…", 0x03: "stopping…", 0xFF: "error"}

CONFIG_PATH = Path.home() / ".bletether.json"


def load_config() -> dict:
    try:
        return json.loads(CONFIG_PATH.read_text())
    except Exception:
        return {}


class BleWorker:
    """Owns the asyncio loop + BLE connection in a background thread."""

    def __init__(self, on_status, on_conn):
        self.on_status = on_status
        self.on_conn = on_conn
        self.loop = asyncio.new_event_loop()
        self.client: BleakClient | None = None
        threading.Thread(target=self.loop.run_forever, daemon=True).start()

    def submit(self, coro):
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    async def connect(self):
        if self.client and self.client.is_connected:
            return
        self.on_conn("scanning…")
        device = await BleakScanner.find_device_by_filter(
            lambda d, ad: SERVICE_UUID in (ad.service_uuids or []), timeout=10
        )
        if device is None:
            self.on_conn("phone not found")
            raise RuntimeError("phone not found")
        client = BleakClient(device, disconnected_callback=lambda _c: self.on_conn("disconnected"))
        await client.connect()  # macOS shows the pairing prompt on first encrypted access
        await client.start_notify(STATUS_UUID, lambda _h, data: self.on_status(data[0]))
        self.client = client
        self.on_conn("connected")
        await client.write_gatt_char(COMMAND_UUID, CMD_QUERY, response=True)

    async def send(self, cmd: bytes):
        await self.connect()
        await self.client.write_gatt_char(COMMAND_UUID, cmd, response=True)

    async def disconnect(self):
        if self.client and self.client.is_connected:
            await self.client.disconnect()


class App(rumps.App):
    def __init__(self):
        super().__init__("📵", quit_button="Quit")
        self.conn_item = rumps.MenuItem("Phone: disconnected")
        self.status_item = rumps.MenuItem("Tethering: unknown")
        self.on_item = rumps.MenuItem("Tethering ON", callback=self.tether_on)
        self.off_item = rumps.MenuItem("Tethering OFF", callback=self.tether_off)
        self.menu = [self.conn_item, self.status_item, None, self.on_item, self.off_item]
        self.worker = BleWorker(self.set_status, self.set_conn)
        self._joined = False

    # -- UI callbacks (called from the BLE thread; rumps tolerates simple attribute sets) --
    def set_conn(self, text: str):
        self.conn_item.title = f"Phone: {text}"
        if text != "connected":
            self.title = "📵"

    def set_status(self, code: int):
        name = STATUS_NAMES.get(code, f"0x{code:02x}")
        self.status_item.title = f"Tethering: {name}"
        if code == 0x01:
            self.title = "📶"
            if not self._joined:
                self._joined = True
                threading.Thread(target=self.join_hotspot, daemon=True).start()
        else:
            self.title = "📵" if code in (0x00, 0xFF) else self.title
            if code == 0x00:
                self._joined = False

    def join_hotspot(self):
        cfg = load_config()
        if not cfg.get("ssid"):
            return
        iface = cfg.get("wifi_interface", "en0")
        subprocess.run(["networksetup", "-setairportpower", iface, "on"], check=False)
        subprocess.run(
            ["networksetup", "-setairportnetwork", iface, cfg["ssid"], cfg.get("password", "")],
            check=False,
        )

    # -- menu actions -------------------------------------------------------------------
    def tether_on(self, _):
        self._send(CMD_ON)

    def tether_off(self, _):
        self._send(CMD_OFF)

    def _send(self, cmd: bytes):
        fut = self.worker.submit(self.worker.send(cmd))

        def done(f):
            if f.exception():
                rumps.notification("BLETetherControl", "Failed", str(f.exception()))

        fut.add_done_callback(done)


if __name__ == "__main__":
    App().run()
