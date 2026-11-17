// BLETether — menu bar app + Settings window. Also serves the Control Center toggle
// (Sources/Controls), which asks this app to do the Bluetooth work.

import ServiceManagement
import SwiftUI

@main
struct BLETetherApp: App {
    @NSApplicationDelegateAdaptor private var appDelegate: AppDelegate

    init() {
        ControlRequests.observe()
    }

    var body: some Scene {
        MenuBarExtra {
            MenuContent(ble: appDelegate.ble, openSettings: appDelegate.showSettings)
        } label: {
            MenuBarIcon(ble: appDelegate.ble)
        }
    }
}

/// Owns the BLE manager and the Settings window, so Settings can also be opened when the
/// app is launched again from Finder (it has no Dock icon or main window).
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let ble = BLEManager()
    private var settingsWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        if ble.selectedID == nil { showSettings() }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        showSettings()
        return false
    }

    func showSettings() {
        if settingsWindow == nil {
            let window = NSWindow(contentViewController: NSHostingController(rootView: SettingsView(ble: ble)))
            window.title = "BLETether Settings"
            window.styleMask = [.titled, .closable]
            window.isReleasedWhenClosed = false
            window.center()
            settingsWindow = window
        }
        NSApp.activate()
        settingsWindow?.makeKeyAndOrderFront(nil)
    }
}

struct MenuBarIcon: View {
    @ObservedObject var ble: BLEManager

    var body: some View {
        Image(systemName: ble.isOn ? "personalhotspot" : "personalhotspot.slash")
    }
}

struct MenuContent: View {
    @ObservedObject var ble: BLEManager
    let openSettings: () -> Void

    var body: some View {
        Text("Phone: \(ble.selectedName.isEmpty ? ble.connection : "\(ble.selectedName) — \(ble.connection)")")
        Text("Tethering: \(ble.statusText)")
        Divider()
        Button("Tethering ON") { ble.send(Proto.cmdOn) }
        Button("Tethering OFF") { ble.send(Proto.cmdOff) }
        Divider()
        Button("Settings…") { openSettings() }
        .keyboardShortcut(",")
        Button("Quit") { NSApp.terminate(nil) }
            .keyboardShortcut("q")
    }
}

struct SettingsView: View {
    @ObservedObject var ble: BLEManager
    @State private var ssid = Hotspot.ssid
    @State private var password = Hotspot.password
    @State private var iface = Hotspot.interface
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled

    var body: some View {
        Form {
            Section("Phone") {
                LabeledContent("Selected", value: ble.selectedName.isEmpty ? "None (use first found)" : ble.selectedName)
                LabeledContent("Status", value: ble.connection)
                List(ble.found, selection: Binding(
                    get: { ble.selectedID },
                    set: { id in
                        guard let id, let dev = ble.found.first(where: { $0.id == id }) else { return }
                        ble.selectedName = dev.name
                        ble.selectedID = id
                        ble.connect()
                    })) { dev in
                    HStack {
                        Text(dev.name)
                        Spacer()
                        Text("\(dev.rssi) dBm").foregroundStyle(.secondary)
                    }
                    .tag(dev.id)
                }
                .frame(minHeight: 100)
                HStack {
                    Button(ble.isScanning ? "Scanning…" : "Scan for Phones") { ble.scanForDevices() }
                        .disabled(ble.isScanning)
                    Button("Forget Phone") {
                        ble.selectedName = ""
                        ble.selectedID = nil
                    }
                    .disabled(ble.selectedID == nil)
                }
            }

            Section("Hotspot to join when tethering is ON") {
                TextField("SSID", text: $ssid)
                    .onChange(of: ssid) { Hotspot.ssid = ssid }
                SecureField("Password", text: $password)
                    .onChange(of: password) { Hotspot.password = password }
                TextField("Wi-Fi interface", text: $iface)
                    .onChange(of: iface) { Hotspot.interface = iface }
            }

            Section {
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) {
                        do {
                            if launchAtLogin { try SMAppService.mainApp.register() }
                            else { try SMAppService.mainApp.unregister() }
                        } catch {
                            launchAtLogin = SMAppService.mainApp.status == .enabled
                        }
                    }
            } footer: {
                Text("Keep this app running for the Control Center toggle to work.")
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 440)
        .padding(.vertical)
    }
}

/// Receives requests from the Control Center toggle (see Shared.postRequest).
enum ControlRequests {
    static func observe() {
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(), nil,
            { _, _, _, _, _ in
                DispatchQueue.main.async {
                    guard let on = Shared.requested else { return }
                    Shared.requested = nil
                    NotificationCenter.default.post(name: .tetherRequest, object: on)
                }
            },
            Shared.requestNotification as CFString, nil, .deliverImmediately)
    }
}

extension Notification.Name {
    static let tetherRequest = Notification.Name("dev.bletether.mac.tetherRequest")
}
