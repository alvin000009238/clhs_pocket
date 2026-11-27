import SwiftUI

/// Full-screen lock shown instead of app content while the app lock is engaged.
struct LockScreenView: View {
    let lock: AppLockController

    var body: some View {
        ZStack {
            PocketColor.background.ignoresSafeArea()
            VStack(spacing: 0) {
                Spacer(minLength: 24)
                VStack(spacing: 20) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 34, weight: .semibold))
                        .foregroundStyle(PocketColor.onPrimaryContainer)
                        .frame(width: 88, height: 88)
                        .background(PocketColor.primaryContainer, in: Circle())
                        .accessibilityHidden(true)
                    Text("壢中 Pocket 已鎖定")
                        .font(PocketFont.pageTitle)
                        .foregroundStyle(PocketColor.onSurface)
                        .multilineTextAlignment(.center)
                    if let message = lock.message {
                        Label(message, systemImage: "exclamationmark.circle.fill")
                            .font(.callout)
                            .foregroundStyle(PocketColor.negative)
                            .multilineTextAlignment(.leading)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(PocketColor.surfaceLow, in: RoundedRectangle(cornerRadius: PocketMetrics.innerRadius))
                            .accessibilityIdentifier("lock-message")
                    }
                }
                Spacer(minLength: 24)
                Button {
                    Task { await lock.unlock() }
                } label: {
                    HStack(spacing: 10) {
                        if lock.isAuthenticating {
                            ProgressView().tint(PocketColor.onPrimary)
                        } else {
                            Image(systemName: lock.method.systemImage)
                        }
                        Text("使用 \(lock.method.title) 解鎖")
                    }
                    .font(.headline)
                    .foregroundStyle(PocketColor.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(PocketColor.primary, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(lock.isAuthenticating)
                .accessibilityIdentifier("lock-unlock")
            }
            .frame(maxWidth: 420)
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
        }
        .accessibilityAddTraits(.isModal)
        .sensoryFeedback(.error, trigger: lock.message) { _, message in message != nil }
    }
}
