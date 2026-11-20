import Foundation
@preconcurrency import PocketShared

/// `FileStore` in Application Support, outside iCloud and device backups.
///
/// School data (`private/`) uses complete protection: unreadable while the device is locked.
/// Public caches and preferences use protection until first unlock. Every failure is returned to
/// the shared core as a failed `StoreResult`.
final class ProtectedFileStore: NSObject, FileStore, @unchecked Sendable {
    private let root: URL
    private let fileManager = FileManager()
    private let lock = NSLock()

    init(root: URL) {
        self.root = root
    }

    static func applicationSupport() throws -> ProtectedFileStore {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        return ProtectedFileStore(root: base.appendingPathComponent("PocketData", isDirectory: true))
    }

    static func protection(forArea area: String) -> FileProtectionType {
        area == FileStoreCompanion.shared.PRIVATE ? .complete : .completeUntilFirstUserAuthentication
    }

    func read(path: String) -> StoreResult {
        lock.withLock {
            guard let url = resolve(path, file: true) else { return .companion.failure(message: "Invalid path") }
            guard fileManager.fileExists(atPath: url.path) else { return .companion.ok() }
            do {
                let data = try Data(contentsOf: url)
                guard let text = String(data: data, encoding: .utf8) else {
                    return .companion.failure(message: "File is not text")
                }
                let modified = try fileManager.attributesOfItem(atPath: url.path)[.modificationDate] as? Date
                return .companion.value(
                    text: text, modifiedAtEpochMillis: Int64(((modified ?? .distantPast).timeIntervalSince1970 * 1000).rounded())
                )
            } catch {
                return .companion.failure(message: "Read failed")
            }
        }
    }

    func write(path: String, text: String, modifiedAtEpochMillis: Int64) -> StoreResult {
        lock.withLock {
            guard let url = resolve(path, file: true), let area = path.split(separator: "/").first.map(String.init) else {
                return .companion.failure(message: "Invalid path")
            }
            let protection = Self.protection(forArea: area)
            do {
                try prepareDirectories(for: url, protection: protection)
                let option: Data.WritingOptions = protection == .complete
                    ? .completeFileProtection : .completeFileProtectionUntilFirstUserAuthentication
                try Data(text.utf8).write(to: url, options: [.atomic, option])
                try fileManager.setAttributes(
                    [
                        .protectionKey: protection,
                        .modificationDate: Date(timeIntervalSince1970: TimeInterval(modifiedAtEpochMillis) / 1000),
                    ],
                    ofItemAtPath: url.path
                )
                return .companion.ok()
            } catch {
                return .companion.failure(message: "Write failed")
            }
        }
    }

    func delete(path: String) -> StoreResult {
        lock.withLock {
            guard let url = resolve(path, file: true) else { return .companion.failure(message: "Invalid path") }
            return remove(url)
        }
    }

    func deleteTree(directory: String) -> StoreResult {
        lock.withLock {
            guard let url = resolve(directory) else { return .companion.failure(message: "Invalid path") }
            return remove(url)
        }
    }

    private func remove(_ url: URL) -> StoreResult {
        guard fileManager.fileExists(atPath: url.path) else { return .companion.ok() }
        do {
            try fileManager.removeItem(at: url)
            return .companion.ok()
        } catch {
            return .companion.failure(message: "Delete failed")
        }
    }

    private func prepareDirectories(for file: URL, protection: FileProtectionType) throws {
        if !fileManager.fileExists(atPath: root.path) {
            try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
        }
        // Also for a root that already exists: one made before this rule, or restored without it.
        var mutableRoot = root
        if (try? mutableRoot.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup) != true {
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try mutableRoot.setResourceValues(values)
        }
        let directory = file.deletingLastPathComponent()
        if !fileManager.fileExists(atPath: directory.path) {
            try fileManager.createDirectory(
                at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: protection]
            )
        }
    }

    /// Accepts only `area/name[/name…]` made of plain file-name characters, so no path can leave the root.
    /// A file is always inside an area; only `deleteTree` may name the area itself.
    private func resolve(_ path: String, file: Bool = false) -> URL? {
        if file, path.split(separator: "/", omittingEmptySubsequences: false).count < 2 { return nil }
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        let areas = [FileStoreCompanion.shared.PRIVATE, FileStoreCompanion.shared.PUBLIC, FileStoreCompanion.shared.SETTINGS]
        guard let area = parts.first, areas.contains(area), parts.count <= 4 else { return nil }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
        for part in parts {
            guard !part.isEmpty, part.count <= 255, part != ".", part != "..",
                  part.unicodeScalars.allSatisfy(allowed.contains)
            else { return nil }
        }
        return parts.reduce(root) { $0.appendingPathComponent($1) }
    }
}
