import SwiftUI
import UIKit

struct PocketCard<Content: View>: View {
    var fill: Color = PocketColor.surfaceLow
    var padding: CGFloat = PocketMetrics.cardPadding
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: .rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))
    }
}

struct PocketSectionHeader<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        // Side by side when both fit on one line, otherwise the trailing item drops below the title.
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline) {
                heading.lineLimit(1)
                Spacer(minLength: 12)
                trailing
            }
            VStack(alignment: .leading, spacing: 4) {
                heading
                trailing
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
    }

    private var heading: some View {
        Text(title)
            .font(PocketFont.sectionTitle)
            .foregroundStyle(PocketColor.onSurface)
            .accessibilityAddTraits(.isHeader)
    }
}

extension PocketSectionHeader where Trailing == EmptyView {
    init(title: String) {
        self.init(title: title) { EmptyView() }
    }
}

struct PocketMoreLink<Value: Hashable>: View {
    var label = "查看全部"
    let value: Value

    var body: some View {
        NavigationLink(value: value) {
            HStack(spacing: 2) {
                Text(label)
                Image(systemName: "chevron.right").imageScale(.small)
            }
            .font(.subheadline.weight(.medium))
            .foregroundStyle(PocketColor.primary)
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
    }
}

struct PocketChip: View {
    let text: String
    var systemImage: String?
    var foreground: Color = PocketColor.onSecondaryContainer
    var background: Color = PocketColor.secondaryContainer

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage {
                Image(systemName: systemImage).imageScale(.small).accessibilityHidden(true)
            }
            Text(text)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .layoutPriority(1)
        .font(.caption.weight(.semibold))
        .foregroundStyle(foreground)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(background, in: .capsule)
    }
}

/// Rounded tonal icon tile shared by rows on every root screen.
struct PocketIconTile: View {
    let systemImage: String
    var foreground: Color = PocketColor.onPrimaryContainer
    var background: Color = PocketColor.primaryContainer
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(foreground)
            .frame(width: size, height: size)
            .background(background, in: .rect(cornerRadius: size * 0.32, style: .continuous))
            .accessibilityHidden(true)
    }
}

struct PocketRowDivider: View {
    var body: some View {
        Rectangle()
            .fill(PocketColor.outline.opacity(0.45))
            .frame(height: 1 / UIScreen.main.scale)
    }
}

extension View {
    /// Liquid Glass for floating controls on iOS 26+, system material before that.
    @ViewBuilder
    func pocketGlass<S: Shape>(in shape: S, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            background(.regularMaterial, in: shape)
                .overlay(shape.stroke(PocketColor.outline.opacity(0.35), lineWidth: 0.5))
                .shadow(color: .black.opacity(0.08), radius: 12, y: 4)
        }
    }

    /// Standard scrolling page chrome: Pocket background and a readable width on iPad.
    func pocketPage() -> some View {
        frame(maxWidth: PocketMetrics.regularContentWidth)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, PocketMetrics.screenPadding)
            .padding(.top, 8)
            .padding(.bottom, 32)
    }
}

/// One column on compact widths, two balanced columns on regular widths.
struct PocketColumns<Leading: View, Trailing: View>: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    var spacing: CGFloat = PocketMetrics.sectionSpacing
    @ViewBuilder var leading: Leading
    @ViewBuilder var trailing: Trailing

    var body: some View {
        if sizeClass == .regular {
            HStack(alignment: .top, spacing: 20) {
                VStack(alignment: .leading, spacing: spacing) { leading }
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                VStack(alignment: .leading, spacing: spacing) { trailing }
                    .frame(maxWidth: .infinity, alignment: .topLeading)
            }
        } else {
            VStack(alignment: .leading, spacing: spacing) {
                leading
                trailing
            }
        }
    }
}

struct PocketBrandMark: View {
    var body: some View {
        HStack(spacing: 8) {
            Image("PocketLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 28, height: 28)
            Text("Pocket")
                .font(PocketFont.brand)
                .foregroundStyle(PocketColor.onSurface)
        }
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("壢中 Pocket")
    }
}

/// Placeholder for content that real data has not delivered yet: a spinner while loading,
/// otherwise the reason and a retry.
struct PocketPendingView: View {
    let isLoading: Bool
    let title: String
    var message: String?
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            if isLoading {
                ProgressView()
                    .controlSize(.large)
                Text("載入中…")
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            } else {
                Image(systemName: "icloud.slash")
                    .font(.largeTitle)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .accessibilityHidden(true)
                Text(title)
                    .font(PocketFont.cardTitle)
                    .foregroundStyle(PocketColor.onSurface)
                if let message {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                }
                if let retry {
                    Button("重試", action: retry)
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 4)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .padding(.horizontal, 24)
        .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))
        // Keep the retry button its own element instead of merging it into the message.
        .accessibilityElement(children: .contain)
    }
}

/// Hides scrolled content behind the navigation bar: solid page colour under the bar, fading
/// out just below it, so titles and floating bar buttons never sit on legible text. The system
/// scroll-edge effect alone leaves text readable on iOS 26 and is absent with the iOS 18 fallback.
private struct PocketScrollEdge: ViewModifier {
    private static let fade: CGFloat = 14
    /// Tallest collapsed bar; keeps the cover off the content while a large title is expanded.
    private static let collapsedBar: CGFloat = 54

    private static var statusBarHeight: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first?.keyWindow?.safeAreaInsets.top ?? 0
    }

    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            GeometryReader { proxy in
                let inset = proxy.safeAreaInsets.top
                let bar = min(inset, Self.statusBarHeight + Self.collapsedBar)
                let height = bar + Self.fade
                PocketColor.background
                    .mask(
                        LinearGradient(
                            stops: [
                                .init(color: .black, location: 0),
                                // Without a bar (e.g. a sheet) the stop would be negative.
                                .init(color: .black.opacity(0.96), location: max(0, bar - 6) / height),
                                .init(color: .clear, location: 1),
                            ],
                            startPoint: .top, endPoint: .bottom
                        )
                    )
                    .frame(height: height)
                    .offset(y: -inset)
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }
}

extension View {
    func pocketScrollEdge() -> some View { modifier(PocketScrollEdge()) }
}
