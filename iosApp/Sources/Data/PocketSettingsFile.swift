import SwiftUI
import UniformTypeIdentifiers

/// A settings backup in the JSON format shared with Android, for `fileExporter` / `fileImporter`.
/// The text is produced and validated by the shared core (`PocketApi.exportSettingsJson`,
/// `inspectSettingsJson`, `restoreSettingsJson`); this type only moves it in and out of files.
struct PocketSettingsFile: FileDocument {
    static let readableContentTypes: [UTType] = [.json]
    static let maximumBytes = 1_048_576
    static let defaultFilename = "clhs-pocket-settings.json"

    enum ReadError: Error, Equatable {
        case tooLarge, notText, unreadable
    }

    var text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw ReadError.unreadable }
        text = try Self.text(from: data)
    }

    /// Reads a file picked with `fileImporter`, which hands out security-scoped URLs.
    init(contentsOf url: URL) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        // Read at most one byte past the limit: a file provider may not report a size at all.
        guard let handle = try? FileHandle(forReadingFrom: url) else { throw ReadError.unreadable }
        defer { try? handle.close() }
        guard let data = try? handle.read(upToCount: Self.maximumBytes + 1) else { throw ReadError.unreadable }
        guard data.count <= Self.maximumBytes else { throw ReadError.tooLarge }
        text = try Self.text(from: data)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }

    private static func text(from data: Data) throws -> String {
        guard data.count <= maximumBytes else { throw ReadError.tooLarge }
        guard let text = String(data: data, encoding: .utf8) else { throw ReadError.notText }
        return text
    }
}
