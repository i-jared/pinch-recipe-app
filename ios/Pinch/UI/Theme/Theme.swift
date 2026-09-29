import SwiftUI
import UIKit

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: alpha
        )
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(uiColor: UIColor(hex: hex, alpha: opacity))
    }

    static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

/// Pinch's palette: vintage jadeite kitchenware. Enamel-white canvas, jade for actions,
/// deep pine ink, cherry only for favorites and timers, butter for notes.
enum Theme {
    static let canvas = Color.adaptive(light: 0xF2F6F1, dark: 0x0E1A17)
    static let surface = Color.adaptive(light: 0xFFFFFF, dark: 0x16251F)
    static let sunken = Color.adaptive(light: 0xE3ECE5, dark: 0x1F322B)
    static let ink = Color.adaptive(light: 0x17332C, dark: 0xE8F1EB)
    static let inkSoft = Color.adaptive(light: 0x587068, dark: 0x9FB5AB)
    static let jade = Color(hex: 0x74C69D)
    static let jadeDeep = Color.adaptive(light: 0x1F7A57, dark: 0x8FD6B0)
    static let onJade = Color(hex: 0x10291F)
    static let cherry = Color.adaptive(light: 0xE0393E, dark: 0xFF6B6B)
    static let butter = Color(hex: 0xFFD966)
    static let note = Color.adaptive(light: 0xFFF3C4, dark: 0x3A3420)
    static let noteInk = Color.adaptive(light: 0x5A4A12, dark: 0xF4E7B5)
    static let line = Color.adaptive(light: 0xD5E1D8, dark: 0x27392F)
}

extension Font {
    /// SF Rounded at a Dynamic Type style, so everything scales with the reader's text size.
    static func rounded(_ style: Font.TextStyle, _ weight: Font.Weight = .regular) -> Font {
        .system(style, design: .rounded, weight: weight)
    }
}

extension CoverPalette {
    var base: Color {
        switch self {
        case .tomato: Color(hex: 0xF26B4F)
        case .jade: Color(hex: 0x74C69D)
        case .butter: Color(hex: 0xF6C945)
        case .blueberry: Color(hex: 0x6D87DB)
        case .plum: Color(hex: 0xA976C6)
        case .saffron: Color(hex: 0xF59B3A)
        case .beet: Color(hex: 0xD6577F)
        case .sky: Color(hex: 0x6DBFE3)
        case .sage: Color(hex: 0x9DB88A)
        case .cocoa: Color(hex: 0xB07B5C)
        }
    }

    /// A darker shade for text and accents on light backgrounds.
    var deep: Color {
        switch self {
        case .tomato: .adaptive(light: 0xC23F27, dark: 0xFF8E77)
        case .jade: .adaptive(light: 0x1F7A57, dark: 0x8FD6B0)
        case .butter: .adaptive(light: 0x946B00, dark: 0xF6D366)
        case .blueberry: .adaptive(light: 0x3B55AB, dark: 0x9BB0F0)
        case .plum: .adaptive(light: 0x74439F, dark: 0xC9A0E2)
        case .saffron: .adaptive(light: 0xA85A0C, dark: 0xF8B56A)
        case .beet: .adaptive(light: 0xA32C55, dark: 0xF08BAA)
        case .sky: .adaptive(light: 0x1F7299, dark: 0x93D3EE)
        case .sage: .adaptive(light: 0x55733F, dark: 0xB9D0A8)
        case .cocoa: .adaptive(light: 0x7A4B30, dark: 0xD7A889)
        }
    }

    /// A pale wash of the color for large backgrounds.
    var tint: Color {
        switch self {
        case .tomato: .adaptive(light: 0xFDE4DD, dark: 0x3A221D)
        case .jade: .adaptive(light: 0xDDF3E7, dark: 0x1C3529)
        case .butter: .adaptive(light: 0xFDF3D1, dark: 0x3A3218)
        case .blueberry: .adaptive(light: 0xE2E8FA, dark: 0x1F2742)
        case .plum: .adaptive(light: 0xEFE3F6, dark: 0x2F2240)
        case .saffron: .adaptive(light: 0xFDEAD3, dark: 0x3A2A17)
        case .beet: .adaptive(light: 0xF9DEE7, dark: 0x3A1C27)
        case .sky: .adaptive(light: 0xDEF1FA, dark: 0x183341)
        case .sage: .adaptive(light: 0xE9F0E2, dark: 0x26301F)
        case .cocoa: .adaptive(light: 0xF2E5DC, dark: 0x33251D)
        }
    }

    /// Text color that reads on `base`.
    var onBase: Color {
        switch self {
        case .tomato, .blueberry, .plum, .beet, .cocoa: .white
        case .jade, .butter, .saffron, .sky, .sage: Color(hex: 0x17332C)
        }
    }

    var displayName: String { rawValue.capitalized }
}

extension CoverPattern {
    var displayName: String { rawValue.capitalized }
}
