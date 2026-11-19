import SwiftUI

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
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(PocketFont.sectionTitle)
                .foregroundStyle(PocketColor.onSurface)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 12)
            trailing
        }
        .padding(.horizontal, 4)
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
                Image(systemName: systemImage).imageScale(.small)
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
