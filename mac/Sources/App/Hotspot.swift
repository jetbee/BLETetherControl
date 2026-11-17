// Joins the phone's hotspot once tethering is ON. SSID / interface live in UserDefaults,
// the password in the login keychain.

import Foundation
import Security

enum Hotspot {
    static var ssid: String {
        get { UserDefaults.standard.string(forKey: "ssid") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "ssid") }
    }

    static var interface: String {
        get { UserDefaults.standard.string(forKey: "wifiInterface") ?? "en0" }
        set { UserDefaults.standard.set(newValue, forKey: "wifiInterface") }
    }

    static var password: String {
        get { Keychain.read(account: "hotspot") ?? "" }
        set { Keychain.write(newValue, account: "hotspot") }
    }

    static func joinIfConfigured() {
        let ssid = ssid, iface = interface, pass = password
        guard !ssid.isEmpty else { return }
        DispatchQueue.global().async {
            run(["-setairportpower", iface, "on"])
            run(["-setairportnetwork", iface, ssid, pass])
        }
    }

    private static func run(_ args: [String]) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        p.arguments = args
        try? p.run()
        p.waitUntilExit()
    }
}

enum Keychain {
    private static let service = "dev.bletether.mac"

    static func read(account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func write(_ value: String, account: String) {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(q as CFDictionary)
        guard !value.isEmpty else { return }
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        SecItemAdd(add as CFDictionary, nil)
    }
}
