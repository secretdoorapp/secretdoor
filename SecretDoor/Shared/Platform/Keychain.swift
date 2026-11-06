import Foundation
import LocalAuthentication
import Security

/// Secrets kept in the iOS Keychain, never in files and never synced to iCloud.
nonisolated enum Keychain {
    private static let service = "app.secretdoor"

    enum KeychainError: Error, LocalizedError {
        case status(OSStatus)
        case cancelled

        var errorDescription: String? {
            switch self {
            case .cancelled: return String(localized: "Cancelled.")
            case let .status(status):
                return (SecCopyErrorMessageString(status, nil) as String?) ?? "Keychain error \(status)"
            }
        }
    }

    // MARK: Biometric unlock

    /// Stores the vault's user key so Face ID / Touch ID can unlock it. The item is invalidated
    /// when biometrics change (a new face or finger is enrolled) and never leaves this device.
    static func storeBiometricKey(_ key: SymmetricKey64, userId: String) throws {
        deleteBiometricKey(userId: userId)
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            .biometryCurrentSet,
            &error
        ) else { throw KeychainError.status(errSecParam) }
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: "biometric.\(userId)",
            kSecAttrAccessControl: access,
            kSecValueData: key.data,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }

    /// Asks for Face ID / Touch ID and returns the user key.
    static func readBiometricKey(userId: String, reason: String) throws -> SymmetricKey64 {
        let context = LAContext()
        context.localizedReason = reason
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: "biometric.\(userId)",
            kSecReturnData: true,
            kSecUseAuthenticationContext: context,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecUserCanceled || status == errSecAuthFailed { throw KeychainError.cancelled }
        guard status == errSecSuccess, let data = result as? Data else { throw KeychainError.status(status) }
        return try SymmetricKey64(data: data)
    }

    static func hasBiometricKey(userId: String) -> Bool {
        let context = LAContext()
        context.interactionNotAllowed = true
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: "biometric.\(userId)",
            kSecUseAuthenticationContext: context,
        ]
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        // "Interaction not allowed" means the item exists but needs Face ID.
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    static func deleteBiometricKey(userId: String) {
        delete(account: "biometric.\(userId)")
    }

    // MARK: Backup file key

    /// The key automatic backups are encrypted with, readable while the phone is locked (after
    /// it was unlocked once since starting) so backups can run in the background.
    static func storeBackupKey(_ key: BackupFileKey, userId: String) throws {
        delete(account: "backup.\(userId)")
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: "backup.\(userId)",
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData: Data(key.storageString.utf8),
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }

    static func readBackupKey(userId: String) -> BackupFileKey? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: "backup.\(userId)",
            kSecReturnData: true,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data, let string = String(data: data, encoding: .utf8)
        else { return nil }
        return BackupFileKey(storageString: string)
    }

    static func deleteAll(userId: String) {
        delete(account: "biometric.\(userId)")
        delete(account: "backup.\(userId)")
    }

    private static func delete(account: String) {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// What kind of biometrics the device has, for labels and icons.
nonisolated enum Biometrics {
    case none, faceID, touchID, opticID

    static var available: Biometrics {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return .none }
        switch context.biometryType {
        case .faceID: return .faceID
        case .touchID: return .touchID
        case .opticID: return .opticID
        default: return .none
        }
    }

    var title: String {
        switch self {
        case .none: return String(localized: "Biometrics")
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        case .opticID: return "Optic ID"
        }
    }

    var systemImage: String {
        switch self {
        case .faceID, .none: return "faceid"
        case .touchID: return "touchid"
        case .opticID: return "opticid"
        }
    }
}
