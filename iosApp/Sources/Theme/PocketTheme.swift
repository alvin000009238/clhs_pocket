import SwiftUI
import UIKit

/// Pocket colour roles, mirroring `ScoreTheme.kt` on Android.
enum PocketColor {
    static let background = dynamic(0xF8F9FF, 0x111418)
    static let surfaceLow = dynamic(0xF2F3FA, 0x191C20)
    static let surface = dynamic(0xECEEF4, 0x1D2024)
    static let surfaceHigh = dynamic(0xE6E8EE, 0x272A2F)
    static let onSurface = dynamic(0x191C20, 0xE1E2E8)
    static let onSurfaceVariant = dynamic(0x43474E, 0xC3C7CF)
    static let outline = dynamic(0xC3C7CF, 0x43474E)

    static let primary = dynamic(0x36618E, 0xA0CAFD)
    static let onPrimary = dynamic(0xFFFFFF, 0x003258)
    static let primaryContainer = dynamic(0xD1E4FF, 0x194975)
    static let onPrimaryContainer = dynamic(0x194975, 0xD1E4FF)
    static let secondaryContainer = dynamic(0xD7E3F8, 0x3C4858)
    static let onSecondaryContainer = dynamic(0x3C4858, 0xD7E3F8)
    static let tertiary = dynamic(0x6B5778, 0xD6BEE4)
    static let tertiaryContainer = dynamic(0xF2DAFF, 0x523F5F)
    static let onTertiaryContainer = dynamic(0x523F5F, 0xF2DAFF)

    static let positive = dynamic(0x126738, 0x81C995)
    static let negative = dynamic(0xBA1A1A, 0xFFB4AB)
    static let warning = dynamic(0x946C00, 0xFDE293)

    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }

    /// Subject colours arrive from the shared core as ARGB pairs.
    static func subject(light: Int64, dark: Int64) -> Color {
        dynamic(UInt32(truncatingIfNeeded: light) & 0xFFFFFF, UInt32(truncatingIfNeeded: dark) & 0xFFFFFF)
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: 1
        )
    }
}

/// Huninn carries page names, section titles and key figures, as on Android.
/// Body copy stays on the system font for legibility and Dynamic Type.
enum PocketFont {
    static let displayFamily = "Huninn-Regular"
    static let brandFamily = "Outfit-Bold"

    static func display(_ size: CGFloat, relativeTo style: Font.TextStyle = .title) -> Font {
        .custom(displayFamily, size: size, relativeTo: style)
    }

    static let hero = display(40, relativeTo: .largeTitle)
    static let figure = display(34, relativeTo: .largeTitle)
    static let pageTitle = display(28, relativeTo: .title)
    static let sectionTitle = display(20, relativeTo: .title3)
    static let cardTitle = display(18, relativeTo: .headline)
    static let brand = Font.custom(brandFamily, size: 22, relativeTo: .title2)

    @MainActor
    static func installNavigationFonts() {
        guard let large = UIFont(name: displayFamily, size: 32),
              let inline = UIFont(name: displayFamily, size: 18) else { return }
        let bar = UINavigationBar.appearance()
        bar.largeTitleTextAttributes = [.font: UIFontMetrics(forTextStyle: .largeTitle).scaledFont(for: large)]
        bar.titleTextAttributes = [.font: UIFontMetrics(forTextStyle: .headline).scaledFont(for: inline)]
    }
}

enum PocketMetrics {
    static let screenPadding: CGFloat = 16
    static let cardPadding: CGFloat = 20
    static let cardSpacing: CGFloat = 12
    static let sectionSpacing: CGFloat = 24
    static let cardRadius: CGFloat = 28
    static let innerRadius: CGFloat = 16
    static let regularContentWidth: CGFloat = 1080
}
