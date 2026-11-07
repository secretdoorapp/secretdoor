import Foundation
import Observation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// When the vault locks after the app leaves the screen.
enum AutoLockTimeout: Int, CaseIterable, Identifiable {
    case immediately = 0
    case oneMinute = 60
    case fiveMinutes = 300
    case fifteenMinutes = 900
    case thirtyMinutes = 1800
    case oneHour = 3600
    case fourHours = 14400
    case onRestart = -1

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .immediately: return String(localized: "Immediately")
        case .oneMinute: return String(localized: "1 minute")
        case .fiveMinutes: return String(localized: "5 minutes")
        case .fifteenMinutes: return String(localized: "15 minutes")
        case .thirtyMinutes: return String(localized: "30 minutes")
        case .oneHour: return String(localized: "1 hour")
        case .fourHours: return String(localized: "4 hours")
        case .onRestart: return String(localized: "On app restart")
        }
    }
}

/// App-wide state: which vault is active and whether it is locked.
@Observable
final class AppModel {
    enum Phase: Equatable { case setup, locked, unlocked }

    let storage: VaultStorage
    private(set) var phase: Phase = .setup
    private(set) var vaults: [VaultMeta] = []
    private(set) var activeMeta: VaultMeta?
    /// Makes the setup screen open "Restore from backup" straight away (Manage vaults > Import backup).
    var setupOpensRestore = false
    private(set) var session: VaultSession?
    /// Covers the screen while the app is in the app switcher.
    var isObscured = false
    var isBiometricUnlockEnabled = false
    let autoBackup = AutoBackupManager()

    private var backgroundedAt: Date?
    /// The debug demo vault (never registered for AutoFill).
    private var isDemo = false

    var autoLockTimeout: AutoLockTimeout {
        get {
            access(keyPath: \.autoLockTimeout)
            let stored = UserDefaults.standard.object(forKey: "autoLockTimeout") as? Int
            return stored.flatMap(AutoLockTimeout.init(rawValue:)) ?? .fiveMinutes
        }
        set {
            withMutation(keyPath: \.autoLockTimeout) {
                UserDefaults.standard.set(newValue.rawValue, forKey: "autoLockTimeout")
            }
        }
    }

    /// Seconds after which copied values are removed from the clipboard (0 = never).
    var clipboardClearSeconds: Int {
        get {
            access(keyPath: \.clipboardClearSeconds)
            return SharedContainer.clipboardClearSeconds
        }
        set {
            withMutation(keyPath: \.clipboardClearSeconds) {
                SharedContainer.clipboardClearSeconds = newValue
            }
        }
    }

    init(storage: VaultStorage? = nil) {
        #if DEBUG
        // Development builds started with `-SDDemoVault` open a vault of sample items, kept in a
        // separate folder so real vaults are not touched (used for screenshots).
        if ProcessInfo.processInfo.arguments.contains("-SDDemoVault") {
            self.storage = DemoVault.storage
            isDemo = true
            reload()
            if let (meta, userKey) = try? DemoVault.create(in: self.storage) { try? open(meta: meta, userKey: userKey) }
            return
        }
        #endif
        SharedContainer.migrateVaultsIfNeeded()
        self.storage = storage ?? SharedContainer.vaultStorage()
        reload()
    }

    /// Reads the vault list and shows the setup or unlock screen.
    func reload() {
        vaults = storage.vaults()
        let registry = storage.registry()
        activeMeta = vaults.first { $0.userId == registry.activeUserId } ?? vaults.first
        session = nil
        phase = activeMeta == nil ? .setup : .locked
        refreshBiometricState()
    }

    /// Face ID keys disappear when the enrolled faces or fingers change.
    func refreshBiometricState() {
        isBiometricUnlockEnabled = activeMeta.map { Keychain.hasBiometricKey(userId: $0.userId) } ?? false
    }

    // MARK: Creating and restoring

    func createVault(masterPassword: String, hint: String?, name: String?) async throws {
        let storage = storage
        let (meta, userKey) = try await Task.detached(priority: .userInitiated) {
            try storage.createVault(masterPassword: masterPassword, hint: hint, name: name)
        }.value
        try open(meta: meta, userKey: userKey)
    }

    /// Restores a backup file as a vault and unlocks it.
    func restoreBackup(_ data: Data, masterPassword: String, name: String?, asNewVault: Bool) async throws {
        let storage = storage
        let (meta, userKey) = try await Task.detached(priority: .userInitiated) {
            let opened = try BackupFile.open(data, masterPassword: masterPassword)
            let meta = try storage.restore(opened, name: name, asNewVault: asNewVault)
            return (meta, opened.userKey)
        }.value
        try open(meta: meta, userKey: userKey)
    }

    // MARK: Unlocking

    func unlock(masterPassword: String) async throws {
        guard let meta = activeMeta else { throw VaultError.noVault }
        let storage = storage
        let userKey = try await Task.detached(priority: .userInitiated) {
            try storage.unlock(userId: meta.userId, masterPassword: masterPassword)
        }.value
        try open(meta: meta, userKey: userKey)
    }

    func unlockWithBiometrics() async throws {
        guard let meta = activeMeta else { throw VaultError.noVault }
        let reason = String(localized: "Unlock \(meta.displayName)")
        let userKey = try await Task.detached(priority: .userInitiated) {
            try Keychain.readBiometricKey(userId: meta.userId, reason: reason)
        }.value
        try open(meta: meta, userKey: userKey)
    }

    /// Checks the master password of the unlocked vault (for "master password re-prompt").
    func verifyMasterPassword(_ password: String) async -> Bool {
        guard let session else { return false }
        let storage = storage
        let userId = session.userId
        let key = try? await Task.detached(priority: .userInitiated) {
            try storage.unlock(userId: userId, masterPassword: password)
        }.value
        return key == session.userKey
    }

    private func open(meta: VaultMeta, userKey: SymmetricKey64) throws {
        let session = try VaultSession(storage: storage, meta: meta, userKey: userKey)
        session.onChange = { [weak self] in self?.vaultDidChange() }
        self.session = session
        activeMeta = meta
        vaults = storage.vaults()
        phase = .unlocked
        // Automatic backups need the file key while the vault is locked.
        if Keychain.readBackupKey(userId: meta.userId) == nil {
            try? Keychain.storeBackupKey(BackupFileKey.new(userKey: userKey), userId: meta.userId)
        }
        refreshBiometricState()
        updateAutoFillSuggestions()
    }

    /// Offers the unlocked vault's logins above the keyboard (see `AutoFillIdentities`).
    func updateAutoFillSuggestions() {
        guard let session, !isDemo else { return }
        AutoFillIdentities.update(userId: session.userId, ciphers: session.ciphers)
    }

    func lock() {
        session = nil
        if activeMeta != nil { phase = .locked }
    }

    func setBiometricUnlock(_ enabled: Bool) throws {
        guard let session else { return }
        if enabled {
            try Keychain.storeBiometricKey(session.userKey, userId: session.userId)
        } else {
            Keychain.deleteBiometricKey(userId: session.userId)
        }
        refreshBiometricState()
    }

    // MARK: Vaults

    func switchVault(to userId: String) {
        try? storage.setActive(userId: userId)
        reload()
    }

    /// Shows the setup screen to add another vault (the current one stays on the device).
    func startAddingVault(restoringBackup: Bool = false) {
        setupOpensRestore = restoringBackup
        session = nil
        phase = .setup
    }

    func cancelAddingVault() {
        reload()
    }

    /// Renames any vault (an empty name falls back to "My vault").
    func renameVault(userId: String, name: String) throws {
        guard var meta = vaults.first(where: { $0.userId == userId }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        meta.name = trimmed.isEmpty ? nil : trimmed
        try storage.saveMeta(meta)
        vaults = storage.vaults()
        if activeMeta?.userId == userId { activeMeta = meta }
        if session?.userId == userId { session?.meta = meta }
    }

    /// Erases a vault after checking that vault's own master password. Deleting the open vault
    /// returns to the unlock (or setup) screen; deleting another one leaves the open vault alone.
    func deleteVault(userId: String, masterPassword: String) async throws {
        let storage = storage
        _ = try await Task.detached(priority: .userInitiated) {
            try storage.unlock(userId: userId, masterPassword: masterPassword)
        }.value
        try storage.deleteVault(userId: userId)
        Keychain.deleteAll(userId: userId)
        AutoFillIdentities.removeAll()
        autoBackup.removeSettings(userId: userId)
        if session?.userId == userId || session == nil {
            reload()
        } else {
            vaults = storage.vaults()
            updateAutoFillSuggestions()
        }
    }

    func changeMasterPassword(current: String, new: String, hint: String?) async throws {
        guard await verifyMasterPassword(current), let session else { throw VaultError.wrongPassword }
        let storage = storage
        let userId = session.userId
        let userKey = session.userKey
        try await Task.detached(priority: .userInitiated) {
            try storage.changeMasterPassword(userId: userId, userKey: userKey, newPassword: new, hint: hint)
        }.value
        activeMeta = try storage.meta(userId: userId)
        session.meta = activeMeta!
    }

    // MARK: Life cycle

    func scenePhaseChanged(to phase: ScenePhase) {
        switch phase {
        case .active:
            isObscured = false
            if let backgroundedAt, self.phase == .unlocked {
                let timeout = autoLockTimeout
                if timeout != .onRestart, Date().timeIntervalSince(backgroundedAt) >= Double(timeout.rawValue) {
                    lock()
                }
            }
            backgroundedAt = nil
        case .inactive:
            isObscured = true
        case .background:
            isObscured = true
            if backgroundedAt == nil { backgroundedAt = Date() }
            if autoLockTimeout == .immediately { lock() }
            runAutoBackupIfDue()
        @unknown default:
            break
        }
    }

    private func vaultDidChange() {
        updateAutoFillSuggestions()
        runAutoBackupIfDue(afterChange: true)
    }

    func runAutoBackupIfDue(afterChange: Bool = false) {
        for meta in vaults {
            autoBackup.runIfDue(storage: storage, meta: meta, afterChange: afterChange)
        }
    }

    // MARK: Clipboard

    /// Copies `value`, kept off other devices (Universal Clipboard) and removed after the
    /// configured time.
    func copy(_ value: String) {
        AutoFillIdentities.copyToClipboard(value)
    }
}

/// An unlocked vault: the user key, the encrypted records and their decrypted views.
@Observable
final class VaultSession {
    let storage: VaultStorage
    var meta: VaultMeta
    let userKey: SymmetricKey64
    var userId: String { meta.userId }

    private(set) var ciphers: [CipherView] = []
    private(set) var folders: [FolderView] = []
    private var cipherRecords: [JSONValue] = []
    private var folderRecords: [JSONValue] = []
    @ObservationIgnored var onChange: (() -> Void)?

    init(storage: VaultStorage, meta: VaultMeta, userKey: SymmetricKey64) throws {
        self.storage = storage
        self.meta = meta
        self.userKey = userKey
        try reload()
    }

    func reload() throws {
        let records = try storage.records(userId: meta.userId)
        cipherRecords = records.ciphers
        folderRecords = records.folders
        ciphers = records.ciphers.compactMap { CipherCrypto.decrypt($0, userKey: userKey) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        folders = records.folders.compactMap { CipherCrypto.decryptFolder($0, userKey: userKey) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    var activeCiphers: [CipherView] { ciphers.filter { !$0.isDeleted && $0.archivedDate == nil } }
    var trash: [CipherView] { ciphers.filter(\.isDeleted) }

    func cipher(id: String) -> CipherView? { ciphers.first { $0.id == id } }

    private func record(id: String) -> JSONValue? {
        cipherRecords.first { $0["id"]?.stringValue == id }
    }

    // MARK: Items

    /// Saves a new or edited item. Updates the password history when the password changed.
    /// Attachments are not changed here (see `addAttachment` and `deleteAttachment`).
    func save(_ view: CipherView) throws {
        var view = view
        let original = record(id: view.id)
        view.attachments = cipher(id: view.id)?.attachments ?? []
        if let old = cipher(id: view.id), old.type == .login, view.type == .login,
           !old.login.password.isEmpty, old.login.password != view.login.password {
            view.passwordHistory.insert(PasswordHistoryView(password: old.login.password, lastUsedDate: Date()), at: 0)
            view.passwordHistory = Array(view.passwordHistory.prefix(5))
            view.login.passwordRevisionDate = Date()
        }
        let encrypted = try CipherCrypto.encrypt(view, original: original, userKey: userKey)
        var records = cipherRecords
        if let index = records.firstIndex(where: { $0["id"]?.stringValue == view.id }) {
            records[index] = encrypted
        } else {
            records.append(encrypted)
        }
        try commitCiphers(records)
    }

    func moveToTrash(_ view: CipherView) throws {
        var view = view
        view.deletedDate = Date()
        try save(view)
    }

    func restoreFromTrash(_ view: CipherView) throws {
        var view = view
        view.deletedDate = nil
        try save(view)
    }

    func deletePermanently(_ view: CipherView) throws {
        try commitCiphers(cipherRecords.filter { $0["id"]?.stringValue != view.id })
        storage.deleteAttachments(userId: userId, cipherId: view.id)
    }

    func emptyTrash() throws {
        let deleted = trash
        try commitCiphers(cipherRecords.filter { record in !deleted.contains { $0.id == record["id"]?.stringValue } })
        for view in deleted { storage.deleteAttachments(userId: userId, cipherId: view.id) }
    }

    private func commitCiphers(_ records: [JSONValue]) throws {
        try storage.saveCiphers(records, userId: userId)
        try reload()
        onChange?()
    }

    // MARK: Attachments

    func addAttachment(to view: CipherView, fileName: String, content: Data) throws {
        // Save pending edits of a new item first so it has a record.
        if record(id: view.id) == nil { try save(view) }
        guard let original = record(id: view.id) else { return }
        let added = try CipherCrypto.addAttachment(fileName: fileName, content: content, to: original, userKey: userKey)
        try storage.saveAttachment(added.encryptedFile, userId: userId, cipherId: view.id, attachmentId: added.attachmentId)
        var records = cipherRecords
        if let index = records.firstIndex(where: { $0["id"]?.stringValue == view.id }) {
            var record = added.record
            record["revisionDate"] = .string(VaultDate.string(Date()))
            records[index] = record
        }
        try commitCiphers(records)
    }

    func deleteAttachment(_ attachment: AttachmentView, of view: CipherView) throws {
        guard var stored = cipher(id: view.id), let original = record(id: view.id) else { return }
        stored.attachments.removeAll { $0.id == attachment.id }
        let encrypted = try CipherCrypto.encrypt(stored, original: original, userKey: userKey)
        try commitCiphers(cipherRecords.map { $0["id"]?.stringValue == view.id ? encrypted : $0 })
        storage.deleteAttachments(userId: userId, cipherId: view.id, keeping: Set(stored.attachments.map(\.id)))
    }

    /// Decrypts an attachment to a temporary file (deleted when the vault locks or the app starts).
    func decryptAttachment(_ attachment: AttachmentView, of view: CipherView) throws -> URL {
        guard let record = record(id: view.id) else { throw VaultError.corrupted("item") }
        let encrypted = try Data(contentsOf: storage.attachmentURL(userId: userId, cipherId: view.id, attachmentId: attachment.id))
        let data = try CipherCrypto.decryptAttachment(encrypted, attachmentId: attachment.id, record: record, userKey: userKey)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("attachments-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let safeName = attachment.fileName.replacingOccurrences(of: "/", with: "_")
        let url = directory.appendingPathComponent(safeName.isEmpty ? "attachment" : safeName)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        return url
    }

    // MARK: Folders

    func saveFolder(_ folder: FolderView) throws {
        var folder = folder
        folder.revisionDate = Date()
        let encrypted = try CipherCrypto.encryptFolder(folder, userKey: userKey)
        var records = folderRecords
        if let index = records.firstIndex(where: { $0["id"]?.stringValue == folder.id }) {
            records[index] = encrypted
        } else {
            records.append(encrypted)
        }
        try storage.saveFolders(records, userId: userId)
        try reload()
        onChange?()
    }

    /// Deletes a folder; its items move to "No folder".
    func deleteFolder(_ folder: FolderView) throws {
        try storage.saveFolders(folderRecords.filter { $0["id"]?.stringValue != folder.id }, userId: userId)
        let records = cipherRecords.map { record -> JSONValue in
            guard record["folderId"]?.stringValue == folder.id else { return record }
            var updated = record
            updated["folderId"] = .null
            return updated
        }
        try commitCiphers(records)
    }

    // MARK: Backups

    func makeBackup() throws -> Data {
        let key = Keychain.readBackupKey(userId: userId) ?? BackupFileKey.new(userKey: userKey)
        let data = try storage.makeBackup(userId: userId, fileKey: key)
        meta = (try? storage.meta(userId: userId)) ?? meta
        return data
    }

    func mergeBackup(_ data: Data, masterPassword: String) async throws -> VaultStorage.MergeResult {
        let storage = storage
        let userId = userId
        let userKey = userKey
        let result = try await Task.detached(priority: .userInitiated) {
            let opened = try BackupFile.open(data, masterPassword: masterPassword)
            return try storage.merge(opened, into: userId, userKey: userKey)
        }.value
        try reload()
        onChange?()
        return result
    }

    var backupFileName: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HH-mm"
        let name = meta.displayName.replacingOccurrences(of: "/", with: "-")
        return "\(name)_\(formatter.string(from: Date())).\(BackupFile.fileExtension)"
    }
}
