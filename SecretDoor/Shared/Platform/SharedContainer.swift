import Foundation

/// Storage shared by the app and its AutoFill extension (App Group `group.app.secretdoor`).
nonisolated enum SharedContainer {
    static let appGroup = "group.app.secretdoor"

    /// Settings both the app and the AutoFill extension read.
    static var defaults: UserDefaults {
        UserDefaults(suiteName: appGroup) ?? .standard
    }

    /// The vault storage, in the App Group container when it is available.
    static func vaultStorage() -> VaultStorage {
        VaultStorage(root: VaultStorage.defaultRoot(appGroup: appGroup))
    }

    /// Versions before AutoFill kept vaults in the app's own Application Support folder, which
    /// the extension cannot read. Moves them into the App Group container once.
    static func migrateVaultsIfNeeded() {
        let fileManager = FileManager.default
        guard let container = fileManager.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else { return }
        let newRoot = container.appendingPathComponent("Vaults", isDirectory: true)
        let oldRoot = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Vaults", isDirectory: true)
        guard fileManager.fileExists(atPath: oldRoot.path), !fileManager.fileExists(atPath: newRoot.path) else { return }
        do {
            try fileManager.createDirectory(at: container, withIntermediateDirectories: true)
            try fileManager.moveItem(at: oldRoot, to: newRoot)
        } catch {
            // Leave the vaults where they are; the app keeps working, AutoFill cannot see them.
            NSLog("SecretDoor: moving vaults to the App Group failed: \(error)")
        }
    }

    // MARK: AutoFill settings

    /// Show the active vault's logins above the keyboard (QuickType bar).
    static var showsKeyboardSuggestions: Bool {
        get { defaults.object(forKey: "autofill.keyboardSuggestions") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "autofill.keyboardSuggestions") }
    }

    /// Copy the login's verification code to the clipboard after filling it.
    static var copiesTotpAfterFill: Bool {
        get { defaults.object(forKey: "autofill.copyTotp") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "autofill.copyTotp") }
    }

    /// Seconds after which copied values are cleared (shared with the app's setting).
    static var clipboardClearSeconds: Int {
        get { defaults.object(forKey: "clipboardClearSeconds") as? Int ?? 90 }
        set { defaults.set(newValue, forKey: "clipboardClearSeconds") }
    }
}
