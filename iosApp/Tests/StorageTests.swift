import Security
import XCTest
@testable import CLHSPocket
@preconcurrency import PocketShared

final class KeychainStoreTests: XCTestCase {
    private let service = "com.clhs.pocket.ios.tests.\(UUID().uuidString)"

    override func tearDown() {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
    }

    func testStoresReplacesAndDeletesValues() {
        let store = KeychainStore(service: service)
        XCTAssertTrue(store.read(key: "k").succeeded)
        XCTAssertNil(store.read(key: "k").text)
        XCTAssertTrue(store.write(key: "k", value: "first").succeeded)
        XCTAssertTrue(store.write(key: "k", value: "第二").succeeded)
        XCTAssertEqual(store.read(key: "k").text, "第二")
        XCTAssertNil(KeychainStore(service: service + ".other").read(key: "k").text)
        XCTAssertTrue(store.delete(key: "k").succeeded)
        XCTAssertTrue(store.delete(key: "k").succeeded, "deleting a missing item is not a failure")
        XCTAssertNil(store.read(key: "k").text)
    }

    func testItemsAreDeviceOnlyUnlockedOnlyAndNotSynchronised() throws {
        XCTAssertTrue(KeychainStore(service: service).write(key: "k", value: "v").succeeded)
        var item: CFTypeRef?
        let status = SecItemCopyMatching([
            kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: "k",
            kSecAttrSynchronizable: kSecAttrSynchronizableAny, kSecReturnAttributes: true,
        ] as CFDictionary, &item)
        XCTAssertEqual(status, errSecSuccess)
        let attributes = try XCTUnwrap(item as? [String: Any])
        XCTAssertEqual(attributes[kSecAttrAccessible as String] as? String, kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        XCTAssertEqual((attributes[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue ?? false, false)
    }

    func testKeychainErrorsAreReturnedAsFailuresNeverAsEmptyOrSuccess() {
        var operations = KeychainOperations()
        operations.copyMatching = { _, _ in errSecInteractionNotAllowed }
        operations.update = { _, _ in errSecInteractionNotAllowed }
        operations.delete = { _ in errSecInteractionNotAllowed }
        let locked = KeychainStore(service: service, operations: operations)
        XCTAssertFalse(locked.read(key: "k").succeeded)
        XCTAssertFalse(locked.write(key: "k", value: "v").succeeded)
        XCTAssertFalse(locked.delete(key: "k").succeeded)

        operations = KeychainOperations()
        operations.add = { _, _ in errSecDuplicateItem }
        XCTAssertFalse(KeychainStore(service: service, operations: operations).write(key: "new", value: "v").succeeded)
    }
}

final class ProtectedFileStoreTests: XCTestCase {
    private var root: URL!
    private var store: ProtectedFileStore!

    override func setUp() {
        root = temporaryDirectory()
        store = ProtectedFileStore(root: root)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
    }

    func testRoundTripsTextAndTheGivenModificationTime() {
        XCTAssertTrue(store.read(path: "private/grades/a.json").succeeded)
        XCTAssertNil(store.read(path: "private/grades/a.json").text)
        XCTAssertTrue(store.write(path: "private/grades/a.json", text: "成績", modifiedAtEpochMillis: 1_700_000_000_123).succeeded)
        let read = store.read(path: "private/grades/a.json")
        XCTAssertEqual(read.text, "成績")
        XCTAssertEqual(read.modifiedAtEpochMillis, 1_700_000_000_123)
        XCTAssertTrue(store.write(path: "private/grades/a.json", text: "new", modifiedAtEpochMillis: 5).succeeded)
        XCTAssertEqual(store.read(path: "private/grades/a.json").text, "new")
    }

    func testDeleteTreeRemovesOnlyThatAreaAndMissingTargetsSucceed() {
        for path in ["private/a.json", "private/schedule/b.json", "public/c.json", "settings/d.json"] {
            XCTAssertTrue(store.write(path: path, text: "x", modifiedAtEpochMillis: 0).succeeded)
        }
        XCTAssertTrue(store.deleteTree(directory: "private").succeeded)
        XCTAssertTrue(store.deleteTree(directory: "private").succeeded)
        XCTAssertNil(store.read(path: "private/a.json").text)
        XCTAssertNil(store.read(path: "private/schedule/b.json").text)
        XCTAssertEqual(store.read(path: "public/c.json").text, "x")
        XCTAssertTrue(store.delete(path: "settings/d.json").succeeded)
        XCTAssertTrue(store.delete(path: "settings/d.json").succeeded)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), ["public", "settings"])
    }

    func testPathsCannotLeaveTheStoreOrUseUnknownAreas() {
        for path in ["../x", "private/../../x", "/etc/hosts", "other/x.json", "private//x", "private/a b", "", "private/./x"] {
            XCTAssertFalse(store.write(path: path, text: "x", modifiedAtEpochMillis: 0).succeeded, path)
            XCTAssertFalse(store.read(path: path).succeeded, path)
            XCTAssertFalse(store.delete(path: path).succeeded, path)
            XCTAssertFalse(store.deleteTree(directory: path).succeeded, path)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.deletingLastPathComponent().appendingPathComponent("x").path))
    }

    func testSchoolDataGetsCompleteProtectionAndTheStoreIsExcludedFromBackup() throws {
        XCTAssertEqual(ProtectedFileStore.protection(forArea: "private"), .complete)
        XCTAssertEqual(ProtectedFileStore.protection(forArea: "public"), .completeUntilFirstUserAuthentication)
        XCTAssertEqual(ProtectedFileStore.protection(forArea: "settings"), .completeUntilFirstUserAuthentication)
        XCTAssertTrue(store.write(path: "private/a.json", text: "x", modifiedAtEpochMillis: 0).succeeded)
        XCTAssertEqual(try root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testFileSystemErrorsAreReturnedAsFailures() throws {
        // A regular file where the area directory should be makes every write below it fail.
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: root.appendingPathComponent("private"))
        XCTAssertFalse(store.write(path: "private/grades/a.json", text: "x", modifiedAtEpochMillis: 0).succeeded)
    }
}
