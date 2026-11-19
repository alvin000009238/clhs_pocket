import Foundation
@preconcurrency import PocketShared

/// A failure from the shared core, classified for presentation. `message` is zh-TW and safe to show.
struct PocketError: Error, Equatable {
    enum Kind: Equatable {
        case authenticationExpired, transient, school, publicData, storage, cleanupIncomplete, cancelled, invalidInput, unknown
    }

    let kind: Kind
    let message: String
    /// For `.cleanupIncomplete`: what logout or deletion could not remove (`SessionCleanupFailure` names).
    let cleanupFailures: Set<String>

    init(kind: Kind, message: String, cleanupFailures: Set<String> = []) {
        self.kind = kind
        self.message = message
        self.cleanupFailures = cleanupFailures
    }

    init(_ error: Error) {
        if let error = error as? PocketError {
            self = error
            return
        }
        if error is CancellationError {
            self.init(kind: .cancelled, message: "已取消")
            return
        }
        guard let throwable = (error as NSError).userInfo["KotlinException"] as? KotlinThrowable else {
            self.init(kind: .unknown, message: "發生未預期的錯誤")
            return
        }
        let failure = PocketFailure.companion.from(error: throwable)
        let kind: Kind = switch failure.kind {
        case .authenticationExpired: .authenticationExpired
        case .transient: .transient
        case .school: .school
        case .publicData: .publicData
        case .storage: .storage
        case .cleanupIncomplete: .cleanupIncomplete
        case .cancelled: .cancelled
        case .invalidInput: .invalidInput
        default: .unknown
        }
        self.init(kind: kind, message: failure.message, cleanupFailures: Set(failure.cleanupFailures.map(\.name)))
    }
}

/// The iOS data layer: the shared core wired to URLSession, the Keychain and protected files.
///
/// Screens call `PocketApi` through `call`, which cancels the Kotlin work when the Swift task is
/// cancelled and turns every failure into a `PocketError`.
@MainActor
final class PocketBackend {
    let services: PocketServices
    let api: PocketApi

    init(transport: HttpTransport, secureStore: SecureStore, fileStore: FileStore, webDataCleaner: WebDataCleaner) {
        services = PocketServices(
            transport: transport, secureStore: secureStore, fileStore: fileStore, webDataCleaner: webDataCleaner
        )
        api = PocketApi(services: services)
    }

    static func live() throws -> PocketBackend {
        PocketBackend(
            transport: URLSessionTransport(),
            secureStore: KeychainStore(),
            fileStore: try ProtectedFileStore.applicationSupport(),
            webDataCleaner: SchoolWebDataCleaner()
        )
    }

    func call<T>(_ operation: (PocketApi, CancelToken) async throws -> T) async throws(PocketError) -> T {
        let token = CancelToken()
        do {
            return try await withTaskCancellationHandler {
                try await operation(api, token)
            } onCancel: {
                token.cancel()
            }
        } catch {
            throw PocketError(error)
        }
    }

    // Account lifecycle. Each of these throws `.cleanupIncomplete` when data was left behind;
    // the session is unusable either way and the call should be offered again.

    func restoreSession() async throws(PocketError) -> AuthenticatedSession? {
        try await call { try await $0.restoreSession(token: $1) }
    }

    /// Verifies the cookies captured by `SchoolLoginWebView` and stores the session in the Keychain.
    func login(studentNo: String, cookies: [String: String]) async throws(PocketError) -> AuthenticatedSession {
        try await call { try await $0.loginWithCookies(studentNo: studentNo, cookies: cookies, token: $1) }
    }

    func logout() async throws(PocketError) {
        try await call { try await $0.logout(token: $1) }
    }

    /// Switching accounts is a complete logout followed by a new login; nothing is carried over.
    func switchAccount(studentNo: String, cookies: [String: String]) async throws(PocketError) -> AuthenticatedSession {
        try await logout()
        return try await login(studentNo: studentNo, cookies: cookies)
    }

    func deleteAllData() async throws(PocketError) {
        try await call { try await $0.deleteAllData(token: $1) }
    }
}
