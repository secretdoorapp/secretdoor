import Foundation

nonisolated public enum BackupError: Error, Equatable, LocalizedError {
    case notABackup
    case newerVersion
    case damaged
    case wrongPassword
    case unsupportedVaultKey

    public var errorDescription: String? {
        switch self {
        case .notABackup: return String(localized: "This file is not a SecretDoor backup.")
        case .newerVersion: return String(localized: "This backup was made by a newer version of SecretDoor. Update the app first.")
        case .damaged: return String(localized: "The backup file is damaged.")
        case .wrongPassword: return String(localized: "Wrong master password for this backup.")
        case .unsupportedVaultKey: return String(localized: "This vault uses a key type SecretDoor for iPhone does not support.")
        }
    }
}

/// The decrypted content of a backup. Items, folders and attachments are still encrypted with
/// the backup vault's user key, exactly as they are stored on the device.
nonisolated public struct BackupContents: Sendable {
    public var createdAt: Date?
    /// `AccountJson` of the Android app.
    public var account: JSONValue
    /// `WrappedAccountCryptographicState` (`{"type":"v1","privateKey":"2.…"}`).
    public var accountKeys: JSONValue
    public var masterPasswordHint: String?
    public var folders: [JSONValue]
    public var ciphers: [JSONValue]
    /// Encrypted attachment files keyed by `<cipherId>/<attachmentId>`.
    public var attachments: [String: Data]

    public init(
        createdAt: Date?, account: JSONValue, accountKeys: JSONValue, masterPasswordHint: String?,
        folders: [JSONValue], ciphers: [JSONValue], attachments: [String: Data]
    ) {
        self.createdAt = createdAt
        self.account = account
        self.accountKeys = accountKeys
        self.masterPasswordHint = masterPasswordHint
        self.folders = folders
        self.ciphers = ciphers
        self.attachments = attachments
    }
}

/// An opened backup and the user key of the vault it was made from.
nonisolated public struct OpenedBackup: Sendable {
    public var contents: BackupContents
    public var userKey: SymmetricKey64
    public var masterPasswordUnlock: MasterPasswordUnlockData
}

/// The key that encrypts backup files of one vault, derived from its user key and a per-vault
/// salt. It is kept (in the Keychain) so automatic backups also work while the vault is locked.
nonisolated public struct BackupFileKey: Equatable, Sendable {
    public let salt: Data
    public let key: Data

    public init(salt: Data, key: Data) {
        self.salt = salt
        self.key = key
    }

    public static func derive(userKey: SymmetricKey64, salt: Data) -> BackupFileKey {
        BackupFileKey(
            salt: salt,
            key: Crypto.hkdf(ikm: userKey.data, salt: salt, info: Data("secretdoor-backup-file".utf8), length: 32)
        )
    }

    public static func new(userKey: SymmetricKey64) -> BackupFileKey {
        derive(userKey: userKey, salt: Crypto.randomBytes(32))
    }

    /// `<salt>:<key>` in base64, the same storage form as on Android.
    public var storageString: String { "\(salt.base64):\(key.base64)" }

    public init?(storageString: String) {
        let parts = storageString.split(separator: ":")
        guard parts.count == 2, let salt = Data(base64Encoded: String(parts[0])),
              let key = Data(base64Encoded: String(parts[1])) else { return nil }
        self.init(salt: salt, key: key)
    }
}

/// Reads and writes `.secretdoor` backup files (format version 2, compatible with SecretDoor for
/// Android):
///
/// `SECRETDOOR` · version byte · header length (big-endian Int32) · JSON header · payload
///
/// The payload is a ZIP file encrypted with AES-256-GCM in 1 MiB chunks (STREAM construction):
/// each chunk is its length (Int32) followed by ciphertext and tag; the nonce is the header's
/// 7-byte prefix, the chunk index (Int32) and a last-chunk flag; the file prefix is the
/// associated data of every chunk.
nonisolated public enum BackupFile {
    public static let fileExtension = "secretdoor"
    static let format = "secretdoor-backup"
    static let version = 2
    static let magic = Data("SECRETDOOR".utf8)
    static let chunkSize = 1 << 20
    static let maxHeaderSize = 64 * 1024

    struct Header: Codable {
        var format: String
        var version: Int
        var createdAt: String
        var masterPasswordUnlock: MasterPasswordUnlockData
        var accountKeys: String
        var keySalt: String
        var noncePrefix: String
    }

    struct Manifest: Codable {
        var format: String
        var version: Int
        var createdAt: String
    }

    // MARK: Writing

    /// Builds a backup file. `contents` must already be encrypted with the vault's user key, and
    /// `fileKey` derived from that key.
    public static func write(
        contents: BackupContents,
        masterPasswordUnlock: MasterPasswordUnlockData,
        fileKey: BackupFileKey,
        createdAt: Date = Date()
    ) throws -> Data {
        let createdAtString = VaultDate.string(createdAt)
        let accountKeysString = try JSON.encodeString(contents.accountKeys)
        let noncePrefix = Crypto.randomBytes(7)
        let header = Header(
            format: format,
            version: version,
            createdAt: createdAtString,
            masterPasswordUnlock: masterPasswordUnlock,
            accountKeys: accountKeysString,
            keySalt: fileKey.salt.base64,
            noncePrefix: noncePrefix.base64
        )
        let prefix = filePrefix(headerBytes: try JSON.encode(header))

        var entries = [
            Zip.Entry(name: "manifest.json", data: try JSON.encode(Manifest(format: format, version: version, createdAt: createdAtString))),
            // The master password hint is left out: it would only help someone guessing the password.
            Zip.Entry(name: "account.json", data: try JSON.encode(JSONValue.object(["account": contents.account, "masterPasswordHint": .null]))),
            Zip.Entry(name: "account_keys.json", data: Data(accountKeysString.utf8)),
            Zip.Entry(name: "folders.json", data: try JSON.encode(contents.folders)),
            Zip.Entry(name: "ciphers.json", data: try JSON.encode(contents.ciphers)),
        ]
        for (path, data) in contents.attachments.sorted(by: { $0.key < $1.key }) {
            entries.append(Zip.Entry(name: "attachments/\(path)", data: data))
        }
        let payload = try Zip.write(entries)

        var output = prefix
        var index = 0
        var offset = 0
        repeat {
            let end = min(offset + chunkSize, payload.count)
            let isLast = end == payload.count
            let sealed = try Crypto.aesGCMSeal(
                key: fileKey.key,
                nonce: nonce(prefix: noncePrefix, index: index, isLast: isLast),
                plaintext: payload.subdata(in: offset..<end),
                associatedData: prefix
            )
            output.appendBE32(UInt32(sealed.count))
            output.append(sealed)
            offset = end
            index += 1
        } while offset < payload.count
        return output
    }

    // MARK: Reading

    /// Checks that `data` is a backup and returns when it was made (no password needed).
    public static func inspect(_ data: Data) throws -> Date? {
        if let (header, _, _) = try readHeader(data) {
            return VaultDate.parse(header.createdAt)
        }
        let manifest = try readZip(Zip.read(data)).createdAt
        return manifest
    }

    /// Decrypts a backup with the master password of the vault it was made from.
    public static func open(_ data: Data, masterPassword: String) throws -> OpenedBackup {
        guard let (header, prefix, payloadStart) = try readHeader(data) else {
            // Version 1: a plain ZIP file; the password is checked against its account.
            let contents: BackupContents
            do { contents = try readZip(Zip.read(data)) } catch is ZipError { throw BackupError.notABackup }
            guard let unlock = Self.masterPasswordUnlock(fromAccount: contents.account) else { throw BackupError.damaged }
            let userKey = try unlockUserKey(password: masterPassword, unlock: unlock)
            return OpenedBackup(contents: contents, userKey: userKey, masterPasswordUnlock: unlock)
        }
        let userKey = try unlockUserKey(password: masterPassword, unlock: header.masterPasswordUnlock)
        guard let salt = Data(base64Encoded: header.keySalt), let noncePrefix = Data(base64Encoded: header.noncePrefix),
              noncePrefix.count == 7
        else { throw BackupError.damaged }
        let fileKey = BackupFileKey.derive(userKey: userKey, salt: salt)
        let payload = try decryptPayload(data, from: payloadStart, key: fileKey.key, noncePrefix: noncePrefix, associatedData: prefix)
        let entries: [Zip.Entry]
        do { entries = try Zip.read(payload) } catch { throw BackupError.damaged }
        var contents = try readZip(entries)
        if contents.createdAt == nil { contents.createdAt = VaultDate.parse(header.createdAt) }
        return OpenedBackup(contents: contents, userKey: userKey, masterPasswordUnlock: header.masterPasswordUnlock)
    }

    static func unlockUserKey(password: String, unlock: MasterPasswordUnlockData) throws -> SymmetricKey64 {
        do {
            return try MasterKey.unlockUserKey(password: password, unlock: unlock)
        } catch CryptoError.macMismatch, CryptoError.decryptionFailed {
            throw BackupError.wrongPassword
        } catch CryptoError.invalidKey {
            throw BackupError.unsupportedVaultKey
        }
    }

    /// `account.profile.userDecryptionOptions.masterPasswordUnlock` of an `AccountJson`.
    public static func masterPasswordUnlock(fromAccount account: JSONValue) -> MasterPasswordUnlockData? {
        let options = account["profile"]?["userDecryptionOptions"] ?? account["profile"]?["accountDecryptionOptions"]
        guard let value = options?["masterPasswordUnlock"], !value.isNull,
              let data = try? JSON.encode(value) else { return nil }
        return try? JSON.decode(MasterPasswordUnlockData.self, from: data)
    }

    private static func readHeader(_ data: Data) throws -> (Header, Data, Int)? {
        guard data.count >= magic.count, data.prefix(magic.count) == magic else { return nil }
        let bytes = [UInt8](data.prefix(magic.count + 5))
        guard bytes.count == magic.count + 5 else { throw BackupError.damaged }
        let fileVersion = Int(bytes[magic.count])
        guard fileVersion <= version else { throw BackupError.newerVersion }
        let lengthOffset = magic.count + 1
        let headerLength = Int(readBE32(Data(bytes), at: lengthOffset))
        let headerStart = magic.count + 5
        guard (1...maxHeaderSize).contains(headerLength), data.count >= headerStart + headerLength else {
            throw BackupError.damaged
        }
        let headerBytes = data.subdata(in: (data.startIndex + headerStart)..<(data.startIndex + headerStart + headerLength))
        guard let header = try? JSON.decode(Header.self, from: headerBytes) else { throw BackupError.damaged }
        guard header.format == format else { throw BackupError.notABackup }
        return (header, filePrefix(headerBytes: headerBytes), headerStart + headerLength)
    }

    private static func decryptPayload(_ data: Data, from start: Int, key: Data, noncePrefix: Data, associatedData: Data) throws -> Data {
        let bytes = data.subdata(in: (data.startIndex + start)..<data.endIndex)
        var payload = Data()
        var position = 0
        var index = 0
        func readLength(at offset: Int) -> Int? {
            guard offset + 4 <= bytes.count else { return nil }
            return Int(readBE32(bytes, at: offset))
        }
        guard var length = readLength(at: 0) else { throw BackupError.damaged }
        while true {
            guard length >= 16, length <= chunkSize + 16, position + 4 + length <= bytes.count else { throw BackupError.damaged }
            let chunkStart = bytes.startIndex + position + 4
            let sealed = bytes.subdata(in: chunkStart..<(chunkStart + length))
            position += 4 + length
            let nextLength = readLength(at: position)
            if nextLength == nil, position != bytes.count { throw BackupError.damaged }
            let isLast = nextLength == nil
            do {
                payload.append(try Crypto.aesGCMOpen(
                    key: key,
                    nonce: nonce(prefix: noncePrefix, index: index, isLast: isLast),
                    sealed: sealed,
                    associatedData: associatedData
                ))
            } catch {
                throw BackupError.damaged
            }
            index += 1
            guard let next = nextLength else { break }
            length = next
        }
        return payload
    }

    private static func readZip(_ entries: [Zip.Entry]) throws -> BackupContents {
        var manifest: Manifest?
        var account: JSONValue?
        var accountKeys: JSONValue?
        var folders = [JSONValue]()
        var ciphers = [JSONValue]()
        var attachments = [String: Data]()
        let idCharacters = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")
        for entry in entries {
            switch entry.name {
            case "manifest.json": manifest = try? JSON.decode(Manifest.self, from: entry.data)
            case "account.json": account = try JSON.decode(JSONValue.self, from: entry.data)
            case "account_keys.json": accountKeys = try JSON.decode(JSONValue.self, from: entry.data)
            case "folders.json": folders = try JSON.decode([JSONValue].self, from: entry.data)
            case "ciphers.json": ciphers = try JSON.decode([JSONValue].self, from: entry.data)
            default:
                guard entry.name.hasPrefix("attachments/") else { continue }
                let ids = entry.name.dropFirst("attachments/".count).split(separator: "/", omittingEmptySubsequences: false)
                guard ids.count == 2, ids.allSatisfy({ !$0.isEmpty && $0.unicodeScalars.allSatisfy(idCharacters.contains) }) else {
                    throw BackupError.damaged
                }
                attachments["\(ids[0])/\(ids[1])"] = entry.data
            }
        }
        guard let manifest, manifest.format == format else { throw BackupError.notABackup }
        guard manifest.version <= version else { throw BackupError.newerVersion }
        guard let accountEntry = account, let accountValue = accountEntry["account"], let accountKeys else {
            throw BackupError.damaged
        }
        return BackupContents(
            createdAt: VaultDate.parse(manifest.createdAt),
            account: accountValue,
            accountKeys: accountKeys,
            masterPasswordHint: accountEntry["masterPasswordHint"]?.stringValue,
            folders: folders,
            ciphers: ciphers,
            attachments: attachments
        )
    }

    /// Reads a big-endian UInt32 at `offset` (relative to the start of `data`).
    static func readBE32(_ data: Data, at offset: Int) -> UInt32 {
        var value: UInt32 = 0
        for byte in data[(data.startIndex + offset)..<(data.startIndex + offset + 4)] {
            value = value << 8 | UInt32(byte)
        }
        return value
    }

    static func filePrefix(headerBytes: Data) -> Data {
        var prefix = magic
        prefix.append(UInt8(version))
        prefix.appendBE32(UInt32(headerBytes.count))
        prefix.append(headerBytes)
        return prefix
    }

    static func nonce(prefix: Data, index: Int, isLast: Bool) -> Data {
        var nonce = prefix
        nonce.appendBE32(UInt32(index))
        nonce.append(isLast ? 1 : 0)
        return nonce
    }
}
