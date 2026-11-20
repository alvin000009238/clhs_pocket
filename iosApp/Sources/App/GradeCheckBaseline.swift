import CryptoKit
import Foundation

/// The exam list seen by the last grade check that completed, kept across launches so a new exam
/// is still noticed after the app was restarted. It is tied to one sign-in: another account, or
/// the same account signed in again, starts without a baseline. Only exam keys are stored.
struct GradeCheckBaseline {
    private static let key = "grades.checkBaseline.v1"
    let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private static func owner(_ authorizationId: String) -> String {
        SHA256.hash(data: Data(authorizationId.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Nil when no check has completed for this sign-in.
    func exams(authorizationId: String) -> [String]? {
        guard let stored = defaults.dictionary(forKey: Self.key),
              stored["owner"] as? String == Self.owner(authorizationId) else { return nil }
        return stored["exams"] as? [String]
    }

    func save(exams: [String], authorizationId: String) {
        defaults.set(["owner": Self.owner(authorizationId), "exams": exams], forKey: Self.key)
    }

    func clear() {
        defaults.removeObject(forKey: Self.key)
    }
}
