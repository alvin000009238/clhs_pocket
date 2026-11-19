import Foundation
@preconcurrency import PocketShared
import Security

/// The Keychain calls `KeychainStore` makes, replaceable in tests to simulate Keychain failures.
struct KeychainOperations: Sendable {
    var copyMatching: @Sendable (CFDictionary, UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus = SecItemCopyMatching
    var add: @Sendable (CFDictionary, UnsafeMutablePointer<CFTypeRef?>?) -> OSStatus = SecItemAdd
    var update: @Sendable (CFDictionary, CFDictionary) -> OSStatus = SecItemUpdate
    var delete: @Sendable (CFDictionary) -> OSStatus = SecItemDelete
}

/// `SecureStore` on the iOS Keychain for the school session and the weather API key.
///
/// Items are generic passwords readable only while the device is unlocked, bound to this device
/// (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`): they are not synchronised through iCloud
/// Keychain and are not restored onto another device. Values and keys are never logged.
final class KeychainStore: NSObject, SecureStore, @unchecked Sendable {
    static let defaultService = "com.clhs.pocket.ios.secure"

    private let service: String
    private let operations: KeychainOperations

    init(service: String = KeychainStore.defaultService, operations: KeychainOperations = KeychainOperations()) {
        self.service = service
        self.operations = operations
    }

    private func query(_ key: String) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
            kSecAttrSynchronizable: false,
            kSecUseDataProtectionKeychain: true,
        ]
    }

    func read(key: String) -> StoreResult {
        var query = query(key)
        query[kSecReturnData] = true
        query[kSecMatchLimit] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = operations.copyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound {
            return .companion.ok()
        }
        guard status == errSecSuccess, let data = item as? Data, let text = String(data: data, encoding: .utf8) else {
            return .companion.failure(message: "Keychain read failed (\(status))")
        }
        return .companion.value(text: text, modifiedAtEpochMillis: 0)
    }

    func write(key: String, value: String) -> StoreResult {
        let data = Data(value.utf8)
        var status = operations.update(query(key) as CFDictionary, [kSecValueData: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query(key)
            item[kSecValueData] = data
            item[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            status = operations.add(item as CFDictionary, nil)
        }
        return status == errSecSuccess ? .companion.ok() : .companion.failure(message: "Keychain write failed (\(status))")
    }

    func delete(key: String) -> StoreResult {
        let status = operations.delete(query(key) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
            ? .companion.ok() : .companion.failure(message: "Keychain delete failed (\(status))")
    }
}
