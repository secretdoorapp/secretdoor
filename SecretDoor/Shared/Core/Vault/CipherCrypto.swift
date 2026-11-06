import Foundation

/// Converts between encrypted records (`SyncResponseJson.Cipher` / `Folder` of the Android app,
/// stored as JSON) and decrypted views.
///
/// Encrypting starts from the original record, so anything this app does not edit (passkeys,
/// fields added by newer versions) is kept exactly as it was.
nonisolated public enum CipherCrypto {
    // MARK: Folders

    public static func decryptFolder(_ record: JSONValue, userKey: SymmetricKey64) -> FolderView? {
        guard let id = record["id"]?.stringValue else { return nil }
        let name = record["name"]?.stringValue.flatMap { try? EncString($0).decryptString(key: userKey) }
        return FolderView(
            id: id,
            name: name ?? String(localized: "[Folder name could not be decrypted]"),
            revisionDate: VaultDate.parse(record["revisionDate"]?.stringValue) ?? Date()
        )
    }

    public static func encryptFolder(_ folder: FolderView, userKey: SymmetricKey64) throws -> JSONValue {
        .object([
            "id": .string(folder.id),
            "name": .string(try EncString.encrypt(folder.name, key: userKey).description),
            "revisionDate": .string(VaultDate.string(folder.revisionDate)),
        ])
    }

    // MARK: Item keys

    /// The key that encrypts the item's fields: its own item key if it has one, else the user key.
    public static func itemKey(of record: JSONValue, userKey: SymmetricKey64) throws -> SymmetricKey64 {
        if let key = record["key"]?.stringValue, !key.isEmpty {
            return try EncString(key).decryptKey(key: userKey)
        }
        return userKey
    }

    // MARK: Decrypting

    public static func decrypt(_ record: JSONValue, userKey: SymmetricKey64) -> CipherView? {
        guard let id = record["id"]?.stringValue,
              let type = record["type"]?.intValue.flatMap(CipherType.init(rawValue:))
        else { return nil }
        var view = CipherView(id: id, type: type)
        let key: SymmetricKey64
        do {
            key = try itemKey(of: record, userKey: userKey)
        } catch {
            view.name = String(localized: "[Item could not be decrypted]")
            view.hasDecryptionErrors = true
            return view
        }
        var hasErrors = false
        func text(_ value: JSONValue?) -> String? {
            guard let string = value?.stringValue, !string.isEmpty else { return nil }
            do {
                return try EncString(string).decryptString(key: key)
            } catch {
                hasErrors = true
                return nil
            }
        }

        if let data = record["data"], !data.isNull {
            // Items in the newer "V2" format need keys this vault type does not have.
            hasErrors = true
        }

        view.name = text(record["name"]) ?? ""
        view.notes = text(record["notes"]) ?? ""
        view.folderId = record["folderId"]?.stringValue
        view.isFavorite = record["favorite"]?.boolValue ?? false
        view.reprompt = record["reprompt"]?.intValue == 1
        view.creationDate = VaultDate.parse(record["creationDate"]?.stringValue) ?? Date()
        view.revisionDate = VaultDate.parse(record["revisionDate"]?.stringValue) ?? view.creationDate
        view.deletedDate = VaultDate.parse(record["deletedDate"]?.stringValue)
        view.archivedDate = VaultDate.parse(record["archivedDate"]?.stringValue)

        switch type {
        case .login:
            if let login = record["login"], !login.isNull {
                view.login.username = text(login["username"]) ?? ""
                view.login.password = text(login["password"]) ?? ""
                view.login.totp = text(login["totp"]) ?? ""
                view.login.passwordRevisionDate = VaultDate.parse(login["passwordRevisionDate"]?.stringValue)
                view.login.uris = (login["uris"]?.arrayValue ?? []).compactMap { uri in
                    guard let value = text(uri["uri"]) else { return nil }
                    return LoginUriView(uri: value, match: uri["match"]?.intValue.flatMap(UriMatchType.init(rawValue:)))
                }
                view.login.passkeyCount = login["fido2Credentials"]?.arrayValue?.count ?? 0
            }
        case .secureNote:
            break
        default:
            if let section = record[type.sectionKey]?.objectValue {
                for field in type.sectionFields {
                    if let value = text(section[field.key]) { view.section[field.key] = value }
                }
            }
        }

        view.fields = (record["fields"]?.arrayValue ?? []).map { field in
            FieldView(
                name: text(field["name"]) ?? "",
                value: text(field["value"]) ?? "",
                type: field["type"]?.intValue.flatMap(FieldType.init(rawValue:)) ?? .text,
                linkedId: field["linkedId"]?.intValue
            )
        }
        view.passwordHistory = (record["passwordHistory"]?.arrayValue ?? []).compactMap { entry in
            guard let password = text(entry["password"]) else { return nil }
            return PasswordHistoryView(
                password: password,
                lastUsedDate: VaultDate.parse(entry["lastUsedDate"]?.stringValue) ?? Date()
            )
        }
        view.attachments = (record["attachments"]?.arrayValue ?? []).compactMap { attachment in
            guard let id = attachment["id"]?.stringValue else { return nil }
            return AttachmentView(
                id: id,
                fileName: text(attachment["fileName"]) ?? String(localized: "Attachment"),
                size: attachment["size"]?.intValue ?? 0,
                sizeName: attachment["sizeName"]?.stringValue
            )
        }
        view.hasDecryptionErrors = hasErrors
        return view
    }

    // MARK: Encrypting

    /// Encrypts `view`. `original` is the stored record of an existing item (`nil` for a new one).
    public static func encrypt(_ view: CipherView, original: JSONValue?, userKey: SymmetricKey64, now: Date = Date()) throws -> JSONValue {
        var record = original ?? newRecord(id: view.id, now: now)
        let key = try itemKey(of: record, userKey: userKey)
        func enc(_ value: String) throws -> JSONValue {
            value.isEmpty ? .null : .string(try EncString.encrypt(value, key: key).description)
        }

        record["id"] = .string(view.id)
        record["type"] = .number(Double(view.type.rawValue))
        record["name"] = try enc(view.name.isEmpty ? "-" : view.name)
        record["notes"] = try enc(view.notes)
        record["folderId"] = .optionalString(view.folderId)
        record["favorite"] = .bool(view.isFavorite)
        record["reprompt"] = .number(view.reprompt ? 1 : 0)
        record["revisionDate"] = .string(VaultDate.string(now))
        record["deletedDate"] = .optionalString(view.deletedDate.map(VaultDate.string))
        record["archivedDate"] = .optionalString(view.archivedDate.map(VaultDate.string))

        for other in CipherType.allCases where other != view.type {
            record[other.sectionKey] = .null
        }
        switch view.type {
        case .login:
            var login = record["login"]?.objectValue ?? [:]
            login["username"] = try enc(view.login.username)
            login["password"] = try enc(view.login.password)
            login["totp"] = try enc(view.login.totp.trimmingCharacters(in: .whitespacesAndNewlines))
            login["passwordRevisionDate"] = .optionalString(view.login.passwordRevisionDate.map(VaultDate.string))
            login["uri"] = .null
            let uris = try view.login.uris
                .filter { !$0.uri.trimmingCharacters(in: .whitespaces).isEmpty }
                .map { uri -> JSONValue in
                    let value = uri.uri.trimmingCharacters(in: .whitespaces)
                    return .object([
                        "uri": .string(try EncString.encrypt(value, key: key).description),
                        "match": uri.match.map { .number(Double($0.rawValue)) } ?? .null,
                        "uriChecksum": .string(try EncString.encrypt(Crypto.sha256(Data(value.utf8)).base64, key: key).description),
                    ])
                }
            login["uris"] = uris.isEmpty ? .null : .array(uris)
            if login["fido2Credentials"] == nil { login["fido2Credentials"] = .null }
            record["login"] = .object(login)
        case .secureNote:
            record["secureNote"] = .object(["type": .number(0)])
        default:
            var section = record[view.type.sectionKey]?.objectValue ?? [:]
            for field in view.type.sectionFields {
                section[field.key] = try enc(view.section[field.key] ?? "")
            }
            if view.type == .sshKey, section["privateKey"] == .null {
                // The Android app requires a private key.
                section["privateKey"] = .string(try EncString.encrypt("", key: key).description)
            }
            record[view.type.sectionKey] = .object(section)
        }

        let fields = try view.fields.map { field -> JSONValue in
            .object([
                "name": try enc(field.name),
                "value": try enc(field.value),
                "type": .number(Double(field.type.rawValue)),
                "linkedId": field.linkedId.map { .number(Double($0)) } ?? .null,
            ])
        }
        record["fields"] = fields.isEmpty ? .null : .array(fields)
        let history = try view.passwordHistory.map { entry -> JSONValue in
            .object([
                "password": .string(try EncString.encrypt(entry.password, key: key).description),
                "lastUsedDate": .string(VaultDate.string(entry.lastUsedDate)),
            ])
        }
        record["passwordHistory"] = history.isEmpty ? .null : .array(history)

        // Attachments: keep the records of the attachments that are still there.
        let keptIds = Set(view.attachments.map(\.id))
        let attachments = (record["attachments"]?.arrayValue ?? []).filter { attachment in
            attachment["id"]?.stringValue.map(keptIds.contains) ?? false
        }
        record["attachments"] = attachments.isEmpty ? .null : .array(attachments)
        return record
    }

    /// The fields every item record needs for the Android app to read it.
    static func newRecord(id: String, now: Date) -> JSONValue {
        .object([
            "id": .string(id),
            "organizationId": .null,
            "folderId": .null,
            "collectionIds": .array([]),
            "organizationUseTotp": .bool(false),
            "edit": .bool(true),
            "viewPassword": .bool(true),
            "permissions": .object(["delete": .bool(true), "restore": .bool(true)]),
            "creationDate": .string(VaultDate.string(now)),
            "revisionDate": .string(VaultDate.string(now)),
            "key": .null,
            "encryptedFor": .null,
            "data": .null,
            "attachments": .null,
        ])
    }

    // MARK: Attachments

    /// Adds an attachment to `record` and returns the updated record and the encrypted file.
    public static func addAttachment(
        fileName: String,
        content: Data,
        to record: JSONValue,
        userKey: SymmetricKey64
    ) throws -> (record: JSONValue, attachmentId: String, encryptedFile: Data) {
        let key = try itemKey(of: record, userKey: userKey)
        let attachmentKey = SymmetricKey64.generate()
        let encryptedFile = try EncArrayBuffer.encrypt(content, key: attachmentKey)
        let attachmentId = UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "")
        let entry = JSONValue.object([
            "id": .string(attachmentId),
            "fileName": .string(try EncString.encrypt(fileName, key: key).description),
            "key": .string(try EncString.encrypt(attachmentKey.data, key: key).description),
            "size": .number(Double(encryptedFile.count)),
            "sizeName": .string(ByteCountFormatter.string(fromByteCount: Int64(content.count), countStyle: .file)),
            "url": .null,
        ])
        var updated = record
        updated["attachments"] = .array((record["attachments"]?.arrayValue ?? []) + [entry])
        return (updated, attachmentId, encryptedFile)
    }

    /// Decrypts an attachment file of `record`.
    public static func decryptAttachment(
        _ encryptedFile: Data,
        attachmentId: String,
        record: JSONValue,
        userKey: SymmetricKey64
    ) throws -> Data {
        let key = try itemKey(of: record, userKey: userKey)
        let attachment = record["attachments"]?.arrayValue?.first { $0["id"]?.stringValue == attachmentId }
        if let wrapped = attachment?["key"]?.stringValue, !wrapped.isEmpty {
            return try EncArrayBuffer.decrypt(encryptedFile, key: EncString(wrapped).decryptKey(key: key))
        }
        return try EncArrayBuffer.decrypt(encryptedFile, key: key)
    }

    // MARK: Re-encrypting for another vault

    /// Re-encrypts an item record for another user key. An item with its own key only needs that
    /// key re-encrypted; otherwise every encrypted string in it is (as on Android).
    public static func rewrap(_ record: JSONValue, from: SymmetricKey64, to: SymmetricKey64) throws -> JSONValue {
        if let key = record["key"]?.stringValue, !key.isEmpty {
            var updated = record
            updated["key"] = .string(try rewrapString(key, from: from, to: to))
            return updated
        }
        return try record.mapStrings { value in
            EncString.isEncString(value) ? try rewrapString(value, from: from, to: to) : value
        }
    }

    public static func rewrapString(_ value: String, from: SymmetricKey64, to: SymmetricKey64) throws -> String {
        try EncString.encrypt(EncString(value).decrypt(key: from), key: to).description
    }
}
