import Foundation
import Security

enum KeychainKey: String {
    case endpoint = "skillforge.endpoint"
    case endpoints = "skillforge.endpoints"
    case deviceToken = "skillforge.deviceToken"
}

protocol KeychainStoring {
    func readString(for key: KeychainKey) throws -> String?
    func saveString(_ value: String, for key: KeychainKey) throws
    func deleteString(for key: KeychainKey) throws
}

enum KeychainError: Error {
    case unexpectedData
    case unhandledStatus(OSStatus)
}

final class KeychainStore: KeychainStoring {
    private let service = "com.skillforge.companion.dev"

    func readString(for key: KeychainKey) throws -> String? {
        var query = baseQuery(for: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.unhandledStatus(status) }
        guard
            let data = item as? Data,
            let value = String(data: data, encoding: .utf8)
        else {
            throw KeychainError.unexpectedData
        }
        return value
    }

    func saveString(_ value: String, for key: KeychainKey) throws {
        try deleteString(for: key)
        var query = baseQuery(for: key)
        query[kSecValueData as String] = Data(value.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unhandledStatus(status) }
    }

    func deleteString(for key: KeychainKey) throws {
        let status = SecItemDelete(baseQuery(for: key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unhandledStatus(status)
        }
    }

    private func baseQuery(for key: KeychainKey) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue
        ]
    }
}
