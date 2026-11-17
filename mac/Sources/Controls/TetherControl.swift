// Control Center toggle. The extension does no Bluetooth itself: it records the requested
// state and pings the menu bar app, which talks to the phone and reports back.

import AppIntents
import SwiftUI
import WidgetKit

@main
struct BLETetherControls: WidgetBundle {
    var body: some Widget {
        TetherControl()
    }
}

struct TetherControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Shared.controlKind, provider: Provider()) { isOn in
            ControlWidgetToggle("Tethering", isOn: isOn, action: SetTetheringIntent()) { on in
                Label(on ? "On" : "Off", systemImage: on ? "personalhotspot" : "personalhotspot.slash")
            }
        }
        .displayName("Phone Tethering")
        .description("Turn the phone's Wi-Fi hotspot on or off over Bluetooth.")
    }

    struct Provider: ControlValueProvider {
        var previewValue: Bool { false }
        func currentValue() async throws -> Bool { Shared.isOn }
    }
}

struct SetTetheringIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Set Phone Tethering"

    @Parameter(title: "Tethering On")
    var value: Bool

    func perform() async throws -> some IntentResult {
        Shared.requested = value
        Shared.postRequest()
        return .result()
    }
}
