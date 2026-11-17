// Talks to the phone (android/ BleGattService) over BLE.

import CoreBluetooth
import Foundation
import WidgetKit

// Keep in sync with android/.../Protocol.kt
enum Proto {
    static let service = CBUUID(string: "7b1e0001-6a4f-4c2e-9d55-0b1e7e7e7e01")
    static let command = CBUUID(string: "7b1e0002-6a4f-4c2e-9d55-0b1e7e7e7e01")  // Write
    static let status = CBUUID(string: "7b1e0003-6a4f-4c2e-9d55-0b1e7e7e7e01")   // Read + Notify

    static let cmdOff: UInt8 = 0x00, cmdOn: UInt8 = 0x01, cmdQuery: UInt8 = 0x02
    static let stOff: UInt8 = 0x00, stOn: UInt8 = 0x01, stFailed: UInt8 = 0xFF
    static let names: [UInt8: String] = [0x00: "OFF", 0x01: "ON", 0x02: "starting…", 0x03: "stopping…", 0xFF: "error"]
}

struct FoundDevice: Identifiable, Hashable {
    let id: UUID
    var name: String
    var rssi: Int
}

@MainActor
final class BLEManager: NSObject, ObservableObject {
    @Published private(set) var connection = "disconnected"
    @Published private(set) var status: UInt8?
    @Published private(set) var found: [FoundDevice] = []
    @Published private(set) var isScanning = false

    /// The phone chosen in Settings. Without one, the first phone found is used.
    @Published var selectedID: UUID? {
        didSet {
            UserDefaults.standard.set(selectedID?.uuidString, forKey: "deviceID")
            if oldValue != selectedID { disconnect() }
        }
    }
    @Published var selectedName: String {
        didSet { UserDefaults.standard.set(selectedName, forKey: "deviceName") }
    }

    var statusText: String { status.map { Proto.names[$0] ?? String(format: "0x%02x", $0) } ?? "unknown" }
    var isOn: Bool { status == Proto.stOn }

    private var central: CBCentralManager!
    private var phone: CBPeripheral?
    private var commandChar: CBCharacteristic?
    private var pending: UInt8?
    private var scanPurpose: ScanPurpose = .none
    private enum ScanPurpose { case none, connect, list }

    override init() {
        selectedID = UserDefaults.standard.string(forKey: "deviceID").flatMap(UUID.init(uuidString:))
        selectedName = UserDefaults.standard.string(forKey: "deviceName") ?? ""
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        NotificationCenter.default.addObserver(forName: .tetherRequest, object: nil, queue: .main) { [weak self] note in
            guard let on = note.object as? Bool else { return }
            MainActor.assumeIsolated { self?.send(on ? Proto.cmdOn : Proto.cmdOff) }
        }
    }

    // MARK: Commands

    func send(_ cmd: UInt8) {
        if let phone, let commandChar, phone.state == .connected {
            phone.writeValue(Data([cmd]), for: commandChar, type: .withResponse)
        } else {
            pending = cmd
            connect()
        }
    }

    func connect() {
        guard central.state == .poweredOn, phone == nil else { return }
        if let id = selectedID, let known = central.retrievePeripherals(withIdentifiers: [id]).first {
            attach(known)
            return
        }
        startScan(.connect)
    }

    func disconnect() {
        if let phone { central.cancelPeripheralConnection(phone) }
        reset("disconnected")
    }

    /// Lists nearby phones for the Settings picker.
    func scanForDevices() {
        found = []
        startScan(.list)
    }

    // MARK: Internals

    private func startScan(_ purpose: ScanPurpose) {
        guard central.state == .poweredOn else { return }
        scanPurpose = purpose
        isScanning = true
        if purpose == .connect { connection = "scanning…" }
        central.scanForPeripherals(withServices: [Proto.service])
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            guard let self, self.isScanning else { return }
            self.stopScan()
            if purpose == .connect, self.phone == nil { self.connection = "phone not found" }
        }
    }

    private func stopScan() {
        central.stopScan()
        isScanning = false
        scanPurpose = .none
    }

    private func attach(_ peripheral: CBPeripheral) {
        phone = peripheral
        peripheral.delegate = self
        connection = "connecting…"
        central.connect(peripheral)
    }

    private func reset(_ text: String) {
        phone = nil
        commandChar = nil
        connection = text
    }

    private func setStatus(_ code: UInt8) {
        status = code
        if code == Proto.stOn || code == Proto.stOff {
            Shared.isOn = code == Proto.stOn
            ControlCenter.shared.reloadControls(ofKind: Shared.controlKind)
        }
        if code == Proto.stOn { Hotspot.joinIfConfigured() }
    }
}

extension BLEManager: CBCentralManagerDelegate, CBPeripheralDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn: connect()
            case .unauthorized: connection = "Bluetooth not allowed (System Settings › Privacy)"
            default: connection = "Bluetooth off"
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi: NSNumber) {
        let name = peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "Unnamed phone"
        MainActor.assumeIsolated {
            switch scanPurpose {
            case .list:
                let dev = FoundDevice(id: peripheral.identifier, name: name, rssi: rssi.intValue)
                if let i = found.firstIndex(where: { $0.id == dev.id }) { found[i] = dev } else { found.append(dev) }
            case .connect:
                if selectedID == nil || selectedID == peripheral.identifier {
                    stopScan()
                    attach(peripheral)
                }
            case .none:
                break
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([Proto.service])
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { reset("connect failed") }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { reset("disconnected") }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == Proto.service }) else {
            MainActor.assumeIsolated { reset("service not found") }
            return
        }
        peripheral.discoverCharacteristics([Proto.command, Proto.status], for: service)
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            for c in service.characteristics ?? [] {
                if c.uuid == Proto.command { commandChar = c }
                // Encrypted characteristic: macOS shows the pairing prompt on first access.
                if c.uuid == Proto.status { peripheral.setNotifyValue(true, for: c) }
            }
            connection = "connected"
            if selectedID == nil {
                selectedID = peripheral.identifier
                selectedName = peripheral.name ?? "Phone"
            }
            let cmd = pending ?? Proto.cmdQuery
            pending = nil
            if let commandChar { peripheral.writeValue(Data([cmd]), for: commandChar, type: .withResponse) }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == Proto.status, let byte = c.value?.first else { return }
        MainActor.assumeIsolated { setStatus(byte) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        guard error != nil else { return }
        MainActor.assumeIsolated { setStatus(Proto.stFailed) }
    }
}
