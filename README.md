# BLETetherControl

Turn an Android phone's Wi-Fi hotspot on and off from a Mac over Bluetooth LE.

- `android/` — An Android app (Kotlin) that runs a BLE GATT server and starts/stops Wi-Fi tethering.
  - Custom ROM (e.g. LineageOS): installed as a priv-app in the system partition.
  - Stock ROM: tethering is switched through [Shizuku](https://shizuku.rikka.app/).
- `mac/` — A small menu bar app (Swift) with a Settings window and a Control Center toggle. It talks to the phone over BLE and joins the hotspot once it is up.

## BLE protocol

| Characteristic | UUID | Properties |
|---|---|---|
| Service | `7b1e0001-6a4f-4c2e-9d55-0b1e7e7e7e01` | |
| COMMAND | `7b1e0002-6a4f-4c2e-9d55-0b1e7e7e7e01` | Write (encrypted) — `0x00` off, `0x01` on, `0x02` query |
| STATUS | `7b1e0003-6a4f-4c2e-9d55-0b1e7e7e7e01` | Read + Notify (encrypted) — `0x00` off, `0x01` on, `0x02` starting, `0x03` stopping, `0xFF` failed |

Both characteristics require an encrypted (bonded) link, so only a paired Mac can switch tethering.

## Android

Build the APK (JDK 17, Android SDK 36):

```sh
cd android
./gradlew assembleDebug
```

The app picks a backend on each command: priv-app if `TETHER_PRIVILEGED` is granted, otherwise Shizuku.

### Custom ROM (system app)

1. Install the APK as a priv-app (e.g. `/system/priv-app/BLETetherControl/`).
2. Install `android/privapp/privapp-permissions-bletethercontrol.xml` to `/system/etc/permissions/` (or `/product/etc/permissions/`).
3. Reboot, open the app once and grant the Bluetooth / notification permissions. The service starts on boot.

### Stock ROM (Shizuku)

1. Install and start [Shizuku](https://shizuku.rikka.app/) (wireless debugging or root).
2. Install the APK normally.
3. Open the app: grant Bluetooth / notification permissions, grant the Shizuku permission, then **Start BLE service**.

Shizuku started via wireless debugging does not survive a reboot; start it again, then open the app.

## Mac

Needs macOS 26 (for the Control Center toggle), Xcode 26 and [XcodeGen](https://github.com/yonaskolb/XcodeGen).
Set your own `DEVELOPMENT_TEAM` and app group prefix in `mac/project.yml` and `mac/Sources/Shared/Shared.swift`, then:

```sh
cd mac
./build.sh            # -> mac/build/BLETether.app
```

Copy `BLETether.app` to `/Applications` and open it. Then:

- **Settings…** (menu bar): scan for and choose the phone, set the hotspot SSID / password (stored in the keychain) to join when tethering turns ON, and enable launch at login.
- **Control Center**: Edit Controls → add **Phone Tethering**. The toggle asks the menu bar app to do the Bluetooth work, so keep the app running.

macOS shows a pairing prompt on first connection.

## Things to verify

- `TetheringManager` is a `@SystemApi` reached via reflection. The class and method shapes follow AOSP (API 30+); check them on your Android / LineageOS version on a real device.
- Shizuku mode builds `TetheringManager` through its hidden `(Context, Supplier<IBinder>)` constructor around a Shizuku-wrapped binder, and reports `com.android.shell` as the caller package. Check that the shell user may start tethering on your ROM.

## License

[Apache License 2.0](LICENSE)
