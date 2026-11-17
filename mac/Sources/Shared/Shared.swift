// Shared between the menu bar app and the Controls (Control Center) extension.

import Foundation

enum Shared {
    /// Team-prefixed app group: works on macOS without a provisioning profile entry.
    static let appGroup = "B4NY2F3726.dev.bletether"
    static let controlKind = "dev.bletether.mac.tethering"

    /// Darwin notification the extension posts after writing `requested`.
    static let requestNotification = "dev.bletether.mac.request"

    static var defaults: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    /// Last tethering state reported by the phone (true = ON).
    static var isOn: Bool {
        get { defaults.bool(forKey: "isOn") }
        set { defaults.set(newValue, forKey: "isOn") }
    }

    /// State the user asked for from the control; nil when nothing is pending.
    static var requested: Bool? {
        get { defaults.object(forKey: "requested") as? Bool }
        set {
            if let newValue { defaults.set(newValue, forKey: "requested") }
            else { defaults.removeObject(forKey: "requested") }
        }
    }

    static func postRequest() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(requestNotification as CFString), nil, nil, true)
    }
}
