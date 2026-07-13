import SwiftUI

enum AppAppearance: String, CaseIterable, Identifiable {
    static let storageKey = "skillforge.appearance"

    case system
    case light
    case dark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    static func resolve(storedValue: String?) -> AppAppearance {
        storedValue.flatMap(AppAppearance.init(rawValue:)) ?? .system
    }
}
