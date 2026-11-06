import Foundation

nonisolated public enum VaultError: Error, LocalizedError, Equatable {
    case wrongPassword
    case noVault
    case corrupted(String)

    public var errorDescription: String? {
        switch self {
        case .wrongPassword: return String(localized: "Invalid master password. Try again.")
        case .noVault: return String(localized: "No vault was found.")
        case let .corrupted(detail): return String(localized: "The vault data is damaged (\(detail)).")
        }
    }
}

/// Settings of one vault that are not secret, stored next to its data.
nonisolated public struct VaultMeta: Codable, Equatable, Sendable {
    public var userId: String
    public var name: String?
    public var masterPasswordHint: String?
    public var createdAt: Date
    public var lastBackupAt: Date?

    public init(userId: String, name: String?, masterPasswordHint: String?, createdAt: Date = Date()) {
        self.userId = userId
        self.name = name
        self.masterPasswordHint = masterPasswordHint
        self.createdAt = createdAt
    }

    public var displayName: String {
        if let name, !name.isEmpty { return name }
        return String(localized: "My vault")
    }
}

/// The encrypted data of one vault, exactly as the Android app stores it.
nonisolated public struct VaultRecords: Sendable {
    public var account: JSONValue
    public var accountKeys: JSONValue
    public var folders: [JSONValue]
    public var ciphers: [JSONValue]
}

/// Files of the vaults on this device. Everything except `meta.json` is encrypted with the
/// vault's user key (as on Android). Layout:
///
///     Vaults/registry.json
///     Vaults/<userId>/meta.json, account.json, account_keys.json, folders.json, ciphers.json
///     Vaults/<userId>/attachments/<cipherId>/<attachmentId>
nonisolated public final class VaultStorage: @unchecked Sendable {
    public struct Registry: Codable, Equatable, Sendable {
        public var activeUserId: String?
        public var userIds: [String] = []
    }

    public static let localEmailSuffix = "@secretdoor.local"

    public let root: URL
    private let fileManager = FileManager.default
    private let lock = NSLock()

    public init(root: URL) {
        self.root = root
    }

    /// `Vaults` in the shared app group container (so the AutoFill extension can read it), or in
    /// Application Support when there is no app group.
    public static func defaultRoot(appGroup: String?) -> URL {
        if let appGroup,
           let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            return container.appendingPathComponent("Vaults", isDirectory: true)
        }
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("Vaults", isDirectory: true)
    }

    // MARK: Registry

    public func registry() -> Registry {
        (try? JSON.decode(Registry.self, from: Data(contentsOf: root.appendingPathComponent("registry.json")))) ?? Registry()
    }

    public func saveRegistry(_ registry: Registry) throws {
        try write(JSON.encode(registry), to: root.appendingPathComponent("registry.json"))
    }

    public func vaults() -> [VaultMeta] {
        registry().userIds.compactMap { try? meta(userId: $0) }
    }

    public func setActive(userId: String) throws {
        var registry = registry()
        registry.activeUserId = userId
        try saveRegistry(registry)
    }

    // MARK: Per-vault files

    public func directory(userId: String) -> URL {
        root.appendingPathComponent(userId, isDirectory: true)
    }

    public func attachmentURL(userId: String, cipherId: String, attachmentId: String) -> URL {
        directory(userId: userId)
            .appendingPathComponent("attachments", isDirectory: true)
            .appendingPathComponent(cipherId, isDirectory: true)
            .appendingPathComponent(attachmentId)
    }

    public func meta(userId: String) throws -> VaultMeta {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(VaultMeta.self, from: Data(contentsOf: directory(userId: userId).appendingPathComponent("meta.json")))
    }

    public func saveMeta(_ meta: VaultMeta) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try write(encoder.encode(meta), to: directory(userId: meta.userId).appendingPathComponent("meta.json"))
    }

    public func records(userId: String) throws -> VaultRecords {
        let directory = directory(userId: userId)
        func load<T: Decodable>(_ name: String, _ type: T.Type) throws -> T {
            do {
                return try JSON.decode(type, from: Data(contentsOf: directory.appendingPathComponent(name)))
            } catch {
                throw VaultError.corrupted(name)
            }
        }
        return VaultRecords(
            account: try load("account.json", JSONValue.self),
            accountKeys: try load("account_keys.json", JSONValue.self),
            folders: (try? load("folders.json", [JSONValue].self)) ?? [],
            ciphers: (try? load("ciphers.json", [JSONValue].self)) ?? []
        )
    }

    public func saveAccount(_ account: JSONValue, userId: String) throws {
        try write(JSON.encode(account), to: directory(userId: userId).appendingPathComponent("account.json"))
    }

    public func saveFolders(_ folders: [JSONValue], userId: String) throws {
        try write(JSON.encode(folders), to: directory(userId: userId).appendingPathComponent("folders.json"))
    }

    public func saveCiphers(_ ciphers: [JSONValue], userId: String) throws {
        try write(JSON.encode(ciphers), to: directory(userId: userId).appendingPathComponent("ciphers.json"))
    }

    public func saveAttachment(_ data: Data, userId: String, cipherId: String, attachmentId: String) throws {
        try write(data, to: attachmentURL(userId: userId, cipherId: cipherId, attachmentId: attachmentId))
    }

    public func deleteAttachments(userId: String, cipherId: String, keeping: Set<String> = []) {
        let folder = directory(userId: userId).appendingPathComponent("attachments/\(cipherId)", isDirectory: true)
        guard let files = try? fileManager.contentsOfDirectory(atPath: folder.path) else { return }
        for file in files where !keeping.contains(file) {
            try? fileManager.removeItem(at: folder.appendingPathComponent(file))
        }
    }

    /// All encrypted attachment files, keyed by `<cipherId>/<attachmentId>`.
    public func allAttachments(userId: String) -> [String: Data] {
        let folder = directory(userId: userId).appendingPathComponent("attachments", isDirectory: true)
        var result = [String: Data]()
        for cipherId in (try? fileManager.contentsOfDirectory(atPath: folder.path)) ?? [] {
            let cipherFolder = folder.appendingPathComponent(cipherId, isDirectory: true)
            for attachmentId in (try? fileManager.contentsOfDirectory(atPath: cipherFolder.path)) ?? [] {
                if let data = try? Data(contentsOf: cipherFolder.appendingPathComponent(attachmentId)) {
                    result["\(cipherId)/\(attachmentId)"] = data
                }
            }
        }
        return result
    }

    public func deleteVault(userId: String) throws {
        try? fileManager.removeItem(at: directory(userId: userId))
        var registry = registry()
        registry.userIds.removeAll { $0 == userId }
        if registry.activeUserId == userId { registry.activeUserId = registry.userIds.first }
        try saveRegistry(registry)
    }

    // MARK: Creating vaults

    /// Creates a new vault (Argon2id, as on Android) and makes it the active vault.
    @discardableResult
    public func createVault(
        masterPassword: String,
        hint: String?,
        name: String?,
        kdf: KdfConfig = .defaultArgon2id,
        now: Date = Date()
    ) throws -> (meta: VaultMeta, userKey: SymmetricKey64) {
        let userId = UUID().uuidString.lowercased()
        let salt = userId + Self.localEmailSuffix
        let userKey = SymmetricKey64.generate()
        let unlock = try MasterKey.wrapUserKey(userKey, password: masterPassword, salt: salt, kdf: kdf)
        let privateKey = try AccountKeyPair.makeWrappedPrivateKey(userKey: userKey)
        let account = Self.makeAccount(userId: userId, name: name, unlock: unlock, now: now)
        let meta = VaultMeta(userId: userId, name: name, masterPasswordHint: hint, createdAt: now)
        try install(
            meta: meta,
            records: VaultRecords(
                account: account,
                accountKeys: .object(["type": .string("v1"), "privateKey": .string(privateKey)]),
                folders: [],
                ciphers: []
            ),
            attachments: [:]
        )
        return (meta, userKey)
    }

    /// An `AccountJson` like the one SecretDoor for Android creates for a local vault.
    static func makeAccount(userId: String, name: String?, unlock: MasterPasswordUnlockData, now: Date) -> JSONValue {
        let kdf = unlock.kdf
        return .object([
            "profile": .object([
                "userId": .string(userId),
                "email": .string(unlock.salt),
                "emailVerified": .bool(true),
                "isTwoFactorEnabled": .bool(false),
                "name": .optionalString(name),
                "stamp": .null,
                "orgIdentifier": .null,
                "avatarColor": .null,
                "hasPremiumPersonally": .bool(true),
                "hasPremiumFromOrganization": .null,
                "forcePasswordResetReason": .null,
                "kdfType": .number(Double(kdf.kdfType.rawValue)),
                "kdfIterations": .number(Double(kdf.iterations)),
                "kdfMemory": kdf.memory.map { .number(Double($0)) } ?? .null,
                "kdfParallelism": kdf.parallelism.map { .number(Double($0)) } ?? .null,
                "userDecryptionOptions": .object([
                    "hasMasterPassword": .bool(true),
                    "masterPasswordUnlock": unlockJSON(unlock),
                    "trustedDeviceOption": .null,
                    "keyConnectorOption": .null,
                ]),
                "creationDate": .string(VaultDate.string(now)),
            ]),
            "settings": .object(["environmentUrls": .null]),
        ])
    }

    static func unlockJSON(_ unlock: MasterPasswordUnlockData) -> JSONValue {
        (try? JSON.decode(JSONValue.self, from: JSON.encode(unlock))) ?? .null
    }

    /// Stores a vault and adds it to the registry as the active vault.
    public func install(meta: VaultMeta, records: VaultRecords, attachments: [String: Data]) throws {
        lock.lock(); defer { lock.unlock() }
        let directory = directory(userId: meta.userId)
        try? fileManager.removeItem(at: directory)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        do {
            try saveMeta(meta)
            try saveAccount(records.account, userId: meta.userId)
            try write(JSON.encode(records.accountKeys), to: directory.appendingPathComponent("account_keys.json"))
            try saveFolders(records.folders, userId: meta.userId)
            try saveCiphers(records.ciphers, userId: meta.userId)
            for (path, data) in attachments {
                let ids = path.split(separator: "/")
                guard ids.count == 2 else { continue }
                try saveAttachment(data, userId: meta.userId, cipherId: String(ids[0]), attachmentId: String(ids[1]))
            }
            var registry = registry()
            if !registry.userIds.contains(meta.userId) { registry.userIds.append(meta.userId) }
            registry.activeUserId = meta.userId
            try saveRegistry(registry)
        } catch {
            try? fileManager.removeItem(at: directory)
            throw error
        }
    }

    // MARK: Unlocking

    public func masterPasswordUnlock(userId: String) throws -> MasterPasswordUnlockData {
        guard let unlock = BackupFile.masterPasswordUnlock(fromAccount: try records(userId: userId).account) else {
            throw VaultError.corrupted("masterPasswordUnlock")
        }
        return unlock
    }

    /// Checks the master password and returns the user key.
    public func unlock(userId: String, masterPassword: String) throws -> SymmetricKey64 {
        let unlock = try masterPasswordUnlock(userId: userId)
        do {
            return try MasterKey.unlockUserKey(password: masterPassword, unlock: unlock)
        } catch CryptoError.macMismatch, CryptoError.decryptionFailed {
            throw VaultError.wrongPassword
        }
    }

    /// Wraps the (unchanged) user key with a new master password. Older backups still need the
    /// old password.
    public func changeMasterPassword(userId: String, userKey: SymmetricKey64, newPassword: String, hint: String?) throws {
        var records = try records(userId: userId)
        let current = try masterPasswordUnlock(userId: userId)
        let kdf = current.kdf.kdfType == .argon2id ? current.kdf : .defaultArgon2id
        let unlock = try MasterKey.wrapUserKey(userKey, password: newPassword, salt: current.salt, kdf: kdf)
        records.account = Self.updatingUnlock(records.account, unlock: unlock)
        try saveAccount(records.account, userId: userId)
        var meta = try meta(userId: userId)
        meta.masterPasswordHint = hint
        try saveMeta(meta)
    }

    static func updatingUnlock(_ account: JSONValue, unlock: MasterPasswordUnlockData) -> JSONValue {
        var account = account
        guard var profile = account["profile"] else { return account }
        var options = profile["userDecryptionOptions"] ?? .object(["hasMasterPassword": .bool(true)])
        options["masterPasswordUnlock"] = unlockJSON(unlock)
        profile["userDecryptionOptions"] = options
        profile["kdfType"] = .number(Double(unlock.kdf.kdfType.rawValue))
        profile["kdfIterations"] = .number(Double(unlock.kdf.iterations))
        profile["kdfMemory"] = unlock.kdf.memory.map { .number(Double($0)) } ?? .null
        profile["kdfParallelism"] = unlock.kdf.parallelism.map { .number(Double($0)) } ?? .null
        account["profile"] = profile
        return account
    }

    // MARK: Backups

    /// Writes a backup of a vault. Works while the vault is locked, given the stored file key.
    public func makeBackup(userId: String, fileKey: BackupFileKey, now: Date = Date()) throws -> Data {
        let records = try records(userId: userId)
        guard let unlock = BackupFile.masterPasswordUnlock(fromAccount: records.account) else {
            throw VaultError.corrupted("masterPasswordUnlock")
        }
        let data = try BackupFile.write(
            contents: BackupContents(
                createdAt: now,
                account: records.account,
                accountKeys: records.accountKeys,
                masterPasswordHint: nil,
                folders: records.folders,
                ciphers: records.ciphers,
                attachments: allAttachments(userId: userId)
            ),
            masterPasswordUnlock: unlock,
            fileKey: fileKey,
            createdAt: now
        )
        if var meta = try? meta(userId: userId) {
            meta.lastBackupAt = now
            try? saveMeta(meta)
        }
        return data
    }

    /// Restores a backup as a vault on this device (keeping its ID, unless a vault with that ID
    /// already exists or `asNewVault` is set) and makes it the active vault.
    @discardableResult
    public func restore(_ backup: OpenedBackup, name: String?, asNewVault: Bool) throws -> VaultMeta {
        var account = backup.contents.account
        var userId = account["profile"]?["userId"]?.stringValue ?? UUID().uuidString.lowercased()
        if asNewVault || registry().userIds.contains(userId) {
            // A new ID keeps the vault apart; the email is the key salt, so it stays the same.
            userId = UUID().uuidString.lowercased()
        }
        var profile = account["profile"] ?? .object([:])
        profile["userId"] = .string(userId)
        let trimmedName = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmedName, !trimmedName.isEmpty { profile["name"] = .string(trimmedName) }
        account["profile"] = profile
        let meta = VaultMeta(
            userId: userId,
            name: profile["name"]?.stringValue,
            masterPasswordHint: backup.contents.masterPasswordHint
        )
        try install(
            meta: meta,
            records: VaultRecords(
                account: account,
                accountKeys: backup.contents.accountKeys,
                folders: backup.contents.folders,
                ciphers: backup.contents.ciphers
            ),
            attachments: backup.contents.attachments
        )
        return meta
    }

    public struct MergeResult: Equatable, Sendable {
        public var added = 0
        public var updated = 0
        public var skipped = 0
    }

    /// Adds the folders and items of a backup to an unlocked vault, like the Android app:
    /// everything is re-encrypted for this vault and checked first; folders merge by name; an item
    /// with the same ID is replaced only by a newer copy; an item with the same content is skipped.
    public func merge(_ backup: OpenedBackup, into userId: String, userKey: SymmetricKey64) throws -> MergeResult {
        var records = try records(userId: userId)
        let sameKey = backup.userKey == userKey
        func rewrapFolder(_ folder: JSONValue) throws -> JSONValue {
            sameKey ? folder : try folder.mapStrings { EncString.isEncString($0) ? try CipherCrypto.rewrapString($0, from: backup.userKey, to: userKey) : $0 }
        }

        // Folders
        var folderIdsByName = [String: String]()
        for folder in records.folders {
            if let view = CipherCrypto.decryptFolder(folder, userKey: userKey) { folderIdsByName[view.name] = view.id }
        }
        let existingFolderIds = Set(records.folders.compactMap { $0["id"]?.stringValue })
        var folderIdMap = [String: String]()
        var newFolders = [JSONValue]()
        for folder in backup.contents.folders {
            guard let id = folder["id"]?.stringValue else { continue }
            var rewrapped = try rewrapFolder(folder)
            guard let name = rewrapped["name"]?.stringValue.flatMap({ try? EncString($0).decryptString(key: userKey) }) else {
                throw VaultError.corrupted("folder")
            }
            if let existing = folderIdsByName[name] {
                folderIdMap[id] = existing
            } else {
                let newId = existingFolderIds.contains(id) ? UUID().uuidString.lowercased() : id
                rewrapped["id"] = .string(newId)
                newFolders.append(rewrapped)
                folderIdsByName[name] = newId
                folderIdMap[id] = newId
            }
        }

        // Items
        var result = MergeResult()
        var ciphersById = [String: Int]()
        for (index, cipher) in records.ciphers.enumerated() {
            if let id = cipher["id"]?.stringValue { ciphersById[id] = index }
        }
        var fingerprints = Set(records.ciphers.compactMap { CipherCrypto.decrypt($0, userKey: userKey).map(Self.fingerprint) })
        var replacements = [(Int, JSONValue)]()
        var additions = [JSONValue]()
        for cipher in backup.contents.ciphers {
            guard let id = cipher["id"]?.stringValue else { continue }
            var rewrapped = sameKey ? cipher : try CipherCrypto.rewrap(cipher, from: backup.userKey, to: userKey)
            if let folderId = cipher["folderId"]?.stringValue {
                rewrapped["folderId"] = .optionalString(folderIdMap[folderId] ?? (existingFolderIds.contains(folderId) ? folderId : nil))
            }
            guard let view = CipherCrypto.decrypt(rewrapped, userKey: userKey), !view.hasDecryptionErrors || sameKey else {
                throw VaultError.corrupted("item")
            }
            let fingerprint = Self.fingerprint(view)
            if let index = ciphersById[id] {
                let existingDate = VaultDate.parse(records.ciphers[index]["revisionDate"]?.stringValue) ?? .distantPast
                if view.revisionDate > existingDate {
                    replacements.append((index, rewrapped))
                    result.updated += 1
                } else {
                    result.skipped += 1
                }
            } else if fingerprints.contains(fingerprint) {
                result.skipped += 1
            } else {
                additions.append(rewrapped)
                fingerprints.insert(fingerprint)
                result.added += 1
            }
        }

        for (index, record) in replacements { records.ciphers[index] = record }
        records.ciphers += additions
        for record in replacements.map(\.1) + additions {
            guard let cipherId = record["id"]?.stringValue else { continue }
            deleteAttachments(userId: userId, cipherId: cipherId)
            for (path, data) in backup.contents.attachments where path.hasPrefix(cipherId + "/") {
                try saveAttachment(data, userId: userId, cipherId: cipherId, attachmentId: String(path.dropFirst(cipherId.count + 1)))
            }
        }
        try saveFolders(records.folders + newFolders, userId: userId)
        try saveCiphers(records.ciphers, userId: userId)
        return result
    }

    /// The content that identifies an item, used to skip items that already exist.
    static func fingerprint(_ view: CipherView) -> String {
        var parts: [String] = [
            String(view.type.rawValue), view.name, view.notes,
            view.login.username, view.login.password, view.login.totp,
        ]
        parts += view.login.uris.map(\.uri)
        parts += view.section.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }
        parts += view.fields.map { "\($0.name)=\($0.value)" }
        return parts.joined(separator: "\u{1f}")
    }

    // MARK: Files

    private func write(_ data: Data, to url: URL) throws {
        try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        // The data is encrypted anyway; this protection still allows automatic backups and
        // AutoFill while the phone is locked after it was unlocked once.
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: url, options: .atomic)
        #endif
    }
}
