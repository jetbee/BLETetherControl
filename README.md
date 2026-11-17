# BLETetherControl

Turn an Android phone's Wi-Fi hotspot on and off from a Mac over Bluetooth LE.

- `android/` — A LineageOS priv-app (Kotlin) that runs a BLE GATT server and starts/stops Wi-Fi tethering.
- `mac/` — A Python menu bar app that talks to the phone over BLE and joins the hotspot once it is up.

## BLE protocol

| Characteristic | UUID | Properties |
|---|---|---|
| Service | `7b1e0001-6a4f-4c2e-9d55-0b1e7e7e7e01` | |
| COMMAND | `7b1e0002-6a4f-4c2e-9d55-0b1e7e7e7e01` | Write (encrypted) — `0x00` off, `0x01` on, `0x02` query |
| STATUS | `7b1e0003-6a4f-4c2e-9d55-0b1e7e7e7e01` | Read + Notify (encrypted) — `0x00` off, `0x01` on, `0x02` starting, `0x03` stopping, `0xFF` failed |

Both characteristics require an encrypted (bonded) link, so only a paired Mac can switch tethering.

## Android

Requires a ROM where you can install system apps (e.g. LineageOS).

1. Build the APK in `android/` (Android Gradle Plugin 8.7, minSdk 33).
2. Install it as a priv-app (e.g. `/system/priv-app/BLETetherControl/`).
3. Install `android/privapp/privapp-permissions-bletethercontrol.xml` to `/system/etc/permissions/` (or `/product/etc/permissions/`).
4. Reboot. The service starts on boot and advertises the service UUID.

## Mac

```sh
cd mac
pip install -r requirements.txt
python3 bletether_menubar.py
```

Optional config in `~/.bletether.json` (keep it private, `chmod 600`):

```json
{ "ssid": "your-hotspot-ssid", "password": "your-hotspot-password", "wifi_interface": "en0" }
```

macOS shows a pairing prompt on first connection.

## Things to verify

- `TetheringManager` is a `@SystemApi` reached via reflection. The class and method shapes follow AOSP (API 30+); check them on your Android / LineageOS version on a real device.

## License

[Apache License 2.0](LICENSE)
