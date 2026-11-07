import Foundation

/// Exports the items of an unlocked vault in the formats of SecretDoor for Android (which uses the
/// Bitwarden export formats): JSON, CSV and password-protected JSON.
///
/// Unlike a backup file, an export can be read by other password managers. The JSON and CSV
/// formats are not encrypted.
nonisolated public enum VaultExport {
    public enum Format: Sendable {
        case json
        /// Logins and secure notes only, as in the Bitwarden CSV format.
        case csv
        /// JSON encrypted with a key derived from `filePassword` (PBKDF2-SHA256, 600,000 rounds).
        case encryptedJson(filePassword: String)

        public var fileExtension: String {
            switch self {
            case .json, .encryptedJson: return "json"
            case .csv: return "csv"
            }
        }
    }

    public struct Output: Sendable {
        public var data: Data
        /// Items the format cannot hold (cards, identities, … in CSV).
        public var skippedItems: Int
    }

    /// PBKDF2 rounds of password-protected exports (the Bitwarden default).
    static let fileKdf = KdfConfig(kdfType: .pbkdf2SHA256, iterations: 600_000, memory: nil, parallelism: nil)

    /// Items in the trash are not exported.
    public static func export(
        ciphers: [CipherView],
        folders: [FolderView],
        format: Format,
        now: Date = Date()
    ) throws -> Output {
        let items = ciphers.filter { !$0.isDeleted }
        switch format {
        case .csv:
            return csv(items, folders: folders)
        case .json:
            return Output(data: try jsonData(items, folders: folders), skippedItems: 0)
        case let .encryptedJson(filePassword):
            let plain = try jsonData(items, folders: folders, encrypted: false)
            return Output(data: try encrypt(plain, filePassword: filePassword), skippedItems: 0)
        }
    }

    // MARK: JSON

    private static func jsonData(_ items: [CipherView], folders: [FolderView], encrypted: Bool = false) throws -> Data {
        let document: [String: Any] = [
            "encrypted": encrypted,
            "folders": folders.map { ["id": $0.id, "name": $0.name] as [String: Any] },
            "items": items.map(itemObject),
        ]
        return try JSONSerialization.data(
            withJSONObject: document,
            options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        )
    }

    private static func itemObject(_ cipher: CipherView) -> [String: Any] {
        var object: [String: Any] = [
            "id": cipher.id,
            "organizationId": NSNull(),
            "folderId": null(cipher.folderId),
            "type": cipher.type.rawValue,
            "reprompt": cipher.reprompt ? 1 : 0,
            "name": cipher.name,
            "notes": nullIfEmpty(cipher.notes),
            "favorite": cipher.isFavorite,
            "fields": cipher.fields.map { field -> [String: Any] in
                [
                    "name": field.name,
                    "value": field.value,
                    "type": field.type.rawValue,
                    "linkedId": null(field.linkedId),
                ]
            },
            "collectionIds": NSNull(),
            "revisionDate": timestamp(cipher.revisionDate),
            "creationDate": timestamp(cipher.creationDate),
            "deletedDate": NSNull(),
        ]
        if !cipher.passwordHistory.isEmpty {
            object["passwordHistory"] = cipher.passwordHistory.map {
                ["lastUsedDate": timestamp($0.lastUsedDate), "password": $0.password] as [String: Any]
            }
        }
        switch cipher.type {
        case .login:
            object["login"] = [
                "uris": cipher.login.uris.map { uri -> [String: Any] in
                    ["match": null(uri.match?.rawValue), "uri": uri.uri]
                },
                "username": nullIfEmpty(cipher.login.username),
                "password": nullIfEmpty(cipher.login.password),
                "totp": nullIfEmpty(cipher.login.totp),
            ] as [String: Any]
        case .secureNote:
            object["secureNote"] = ["type": 0]
        default:
            // Cards, identities, SSH keys, bank accounts, driver's licenses and passports keep
            // the field names the app stores them under.
            object[cipher.type.sectionKey] = Dictionary(
                uniqueKeysWithValues: cipher.type.sectionFields.map { field in
                    let value = cipher.section[field.key] ?? ""
                    return (field.key, nullIfEmpty(value))
                }
            )
        }
        return object
    }

    private static func null<T>(_ value: T?) -> Any {
        value.map { $0 as Any } ?? NSNull()
    }

    private static func nullIfEmpty(_ value: String) -> Any {
        if value.isEmpty { return NSNull() }
        return value
    }

    private static func timestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    // MARK: Password-protected JSON

    private static func encrypt(_ plain: Data, filePassword: String) throws -> Data {
        let salt = Crypto.randomBytes(16).base64EncodedString()
        let masterKey = try MasterKey.derive(password: filePassword, salt: salt, kdf: fileKdf)
        let key = try MasterKey.stretch(masterKey)
        let document: [String: Any] = [
            "encrypted": true,
            "passwordProtected": true,
            "salt": salt,
            "kdfType": fileKdf.kdfType.rawValue,
            "kdfIterations": fileKdf.iterations,
            "kdfMemory": NSNull() as Any,
            "kdfParallelism": NSNull(),
            "encKeyValidation_DO_NOT_EDIT": try EncString.encrypt(UUID().uuidString.lowercased(), key: key).description,
            "data": try EncString.encrypt(plain, key: key).description,
        ]
        return try JSONSerialization.data(
            withJSONObject: document,
            options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        )
    }

    /// Reads a password-protected export back (used by the tests; the app does not import it).
    static func decrypt(_ data: Data, filePassword: String) throws -> Data {
        guard
            let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let salt = object["salt"] as? String,
            let iterations = object["kdfIterations"] as? Int,
            let encrypted = object["data"] as? String
        else { throw BackupError.notABackup }
        let kdf = KdfConfig(kdfType: .pbkdf2SHA256, iterations: iterations, memory: nil, parallelism: nil)
        let key = try MasterKey.stretch(MasterKey.derive(password: filePassword, salt: salt, kdf: kdf))
        return try EncString(encrypted).decrypt(key: key)
    }

    // MARK: CSV

    private static let csvHeader = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp"

    private static func csv(_ items: [CipherView], folders: [FolderView]) -> Output {
        let folderNames = Dictionary(folders.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
        var lines = [csvHeader]
        var skipped = 0
        for cipher in items {
            guard cipher.type == .login || cipher.type == .secureNote else {
                skipped += 1
                continue
            }
            let isLogin = cipher.type == .login
            let columns: [String] = [
                cipher.folderId.flatMap { folderNames[$0] } ?? "",
                cipher.isFavorite ? "1" : "",
                isLogin ? "login" : "note",
                cipher.name,
                cipher.notes,
                cipher.fields.map { "\($0.name): \($0.value)" }.joined(separator: "\n"),
                cipher.reprompt ? "1" : "0",
                isLogin ? cipher.login.uris.map(\.uri).joined(separator: "\n") : "",
                isLogin ? cipher.login.username : "",
                isLogin ? cipher.login.password : "",
                isLogin ? cipher.login.totp : "",
            ]
            lines.append(columns.map(csvField).joined(separator: ","))
        }
        return Output(data: Data((lines.joined(separator: "\n") + "\n").utf8), skippedItems: skipped)
    }

    private static func csvField(_ value: String) -> String {
        guard value.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return value }
        return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
