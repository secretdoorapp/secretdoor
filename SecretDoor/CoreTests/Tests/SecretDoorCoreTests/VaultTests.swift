import Foundation
import XCTest
@testable import SecretDoorCore

/// Fast KDF settings so tests do not spend seconds in Argon2id.
private let testKdf = KdfConfig(kdfType: .argon2id, iterations: 2, memory: 16, parallelism: 2)

final class VaultTests: XCTestCase {
    private var root: URL!
    private var storage: VaultStorage!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("sd-tests-\(UUID().uuidString)")
        storage = VaultStorage(root: root)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    // MARK: Small pieces

    /// RFC 6238 appendix B (SHA-1).
    func testTotpRfcVectors() throws {
        var config = try XCTUnwrap(TotpConfig("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        config.digits = 8
        XCTAssertEqual(config.code(at: Date(timeIntervalSince1970: 59)), "94287082")
        XCTAssertEqual(config.code(at: Date(timeIntervalSince1970: 1_111_111_109)), "07081804")
        XCTAssertEqual(config.code(at: Date(timeIntervalSince1970: 20_000_000_000)), "65353130")
    }

    func testTotpUri() throws {
        let config = try XCTUnwrap(TotpConfig("otpauth://totp/ACME:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=ACME&digits=8&period=60"))
        XCTAssertEqual(config.digits, 8)
        XCTAssertEqual(config.period, 60)
        XCTAssertEqual(config.issuer, "ACME")
        XCTAssertEqual(config.accountName, "alice@example.com")
        XCTAssertNil(TotpConfig("not base32 !"))
    }

    func testEncStringRoundTripAndTamper() throws {
        let key = SymmetricKey64.generate()
        let encrypted = try EncString.encrypt("secret ünïcode", key: key).description
        XCTAssertTrue(EncString.isEncString(encrypted))
        XCTAssertEqual(try EncString(encrypted).decryptString(key: key), "secret ünïcode")
        XCTAssertThrowsError(try EncString(encrypted).decryptString(key: SymmetricKey64.generate()))
    }

    func testZipRoundTrip() throws {
        let entries = [
            Zip.Entry(name: "a.json", data: Data(String(repeating: "{\"x\":1}", count: 500).utf8)),
            Zip.Entry(name: "attachments/c1/a1", data: Crypto.randomBytes(3000)),
            Zip.Entry(name: "empty.json", data: Data()),
        ]
        XCTAssertEqual(try Zip.read(Zip.write(entries)), entries)
    }

    func testGenerator() {
        var options = PasswordOptions()
        options.length = 20
        options.special = true
        let password = PasswordGenerator.password(options)
        XCTAssertEqual(password.count, 20)
        XCTAssertTrue(password.contains(where: \.isNumber))
        XCTAssertTrue(password.contains(where: { "!@#$%^&*".contains($0) }))
        XCTAssertEqual(effLongWordlist.count, 7776)
        XCTAssertEqual(PasswordGenerator.passphrase(PassphraseOptions()).split(separator: "-").count, 6)
    }

    func testPkcs8Wrapping() throws {
        let key = SymmetricKey64.generate()
        let wrapped = try AccountKeyPair.makeWrappedPrivateKey(userKey: key)
        let der = try EncString(wrapped).decrypt(key: key)
        // SEQUENCE, then version 0 and the rsaEncryption OID.
        XCTAssertEqual(der.first, 0x30)
        XCTAssertNotNil(der.range(of: Data([0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01])))
    }

    // MARK: Vault

    func testCreateUnlockAndChangePassword() throws {
        let (meta, userKey) = try storage.createVault(masterPassword: "correct horse", hint: "horse", name: "Home", kdf: testKdf)
        XCTAssertEqual(storage.registry().activeUserId, meta.userId)
        XCTAssertEqual(try storage.unlock(userId: meta.userId, masterPassword: "correct horse"), userKey)
        XCTAssertThrowsError(try storage.unlock(userId: meta.userId, masterPassword: "wrong")) {
            XCTAssertEqual($0 as? VaultError, .wrongPassword)
        }
        try storage.changeMasterPassword(userId: meta.userId, userKey: userKey, newPassword: "battery staple", hint: nil)
        XCTAssertEqual(try storage.unlock(userId: meta.userId, masterPassword: "battery staple"), userKey)
        XCTAssertThrowsError(try storage.unlock(userId: meta.userId, masterPassword: "correct horse"))
        let account = try storage.records(userId: meta.userId).account
        XCTAssertEqual(account["profile"]?["email"]?.stringValue, meta.userId + "@secretdoor.local")
    }

    func testCipherRoundTripKeepsUnknownFields() throws {
        let key = SymmetricKey64.generate()
        var login = CipherView(type: .login)
        login.name = "Example"
        login.login.username = "alice"
        login.login.password = "p4ss"
        login.login.totp = "JBSWY3DPEHPK3PXP"
        login.login.uris = [LoginUriView(uri: "https://example.com", match: .host)]
        login.fields = [FieldView(name: "PIN", value: "1234", type: .hidden)]
        var record = try CipherCrypto.encrypt(login, original: nil, userKey: key)
        // A field written by a newer Android version must survive edits.
        record["futureField"] = .string("keep me")
        var decrypted = try XCTUnwrap(CipherCrypto.decrypt(record, userKey: key))
        XCTAssertEqual(decrypted.login.username, "alice")
        XCTAssertEqual(decrypted.login.uris.first?.match, .host)
        XCTAssertEqual(decrypted.fields.first?.value, "1234")
        XCTAssertFalse(decrypted.hasDecryptionErrors)
        decrypted.notes = "edited"
        let edited = try CipherCrypto.encrypt(decrypted, original: record, userKey: key)
        XCTAssertEqual(edited["futureField"]?.stringValue, "keep me")
        XCTAssertEqual(CipherCrypto.decrypt(edited, userKey: key)?.notes, "edited")

        var card = CipherView(type: .card)
        card.name = "Visa"
        card.section = ["number": "4111111111111111", "code": "123", "brand": "Visa"]
        let cardRecord = try CipherCrypto.encrypt(card, original: nil, userKey: key)
        XCTAssertEqual(CipherCrypto.decrypt(cardRecord, userKey: key)?.section["number"], "4111111111111111")
        XCTAssertEqual(CipherCrypto.decrypt(cardRecord, userKey: key)?.subtitle, "Visa, *1111")
    }

    func testItemKeyAndAttachment() throws {
        let userKey = SymmetricKey64.generate()
        let itemKey = SymmetricKey64.generate()
        var view = CipherView(type: .secureNote)
        view.name = "Note"
        var record = CipherCrypto.newRecord(id: view.id, now: Date())
        record["key"] = .string(try EncString.encrypt(itemKey.data, key: userKey).description)
        record = try CipherCrypto.encrypt(view, original: record, userKey: userKey)
        // Fields are encrypted with the item key, not the user key.
        XCTAssertThrowsError(try EncString(record["name"]!.stringValue!).decrypt(key: userKey))
        XCTAssertEqual(CipherCrypto.decrypt(record, userKey: userKey)?.name, "Note")

        let added = try CipherCrypto.addAttachment(fileName: "a.txt", content: Data("hello".utf8), to: record, userKey: userKey)
        XCTAssertEqual(
            try CipherCrypto.decryptAttachment(added.encryptedFile, attachmentId: added.attachmentId, record: added.record, userKey: userKey),
            Data("hello".utf8)
        )
        XCTAssertEqual(CipherCrypto.decrypt(added.record, userKey: userKey)?.attachments.first?.fileName, "a.txt")

        // Moving to another vault only re-encrypts the item key.
        let otherKey = SymmetricKey64.generate()
        let moved = try CipherCrypto.rewrap(added.record, from: userKey, to: otherKey)
        XCTAssertEqual(CipherCrypto.decrypt(moved, userKey: otherKey)?.name, "Note")
        XCTAssertEqual(moved["name"], added.record["name"])
    }

    func testBackupRestoreAndMerge() throws {
        let (meta, userKey) = try storage.createVault(masterPassword: "pw-one", hint: nil, name: "A", kdf: testKdf)
        var item = CipherView(type: .login)
        item.name = "Bank"
        item.login.password = "hunter2"
        let folder = FolderView(name: "Finance")
        item.folderId = folder.id
        var record = try CipherCrypto.encrypt(item, original: nil, userKey: userKey)
        let attachment = try CipherCrypto.addAttachment(fileName: "f.bin", content: Crypto.randomBytes(2_500_000), to: record, userKey: userKey)
        record = attachment.record
        try storage.saveCiphers([record], userId: meta.userId)
        try storage.saveFolders([try CipherCrypto.encryptFolder(folder, userKey: userKey)], userId: meta.userId)
        try storage.saveAttachment(attachment.encryptedFile, userId: meta.userId, cipherId: item.id, attachmentId: attachment.attachmentId)

        let fileKey = BackupFileKey.new(userKey: userKey)
        let backup = try storage.makeBackup(userId: meta.userId, fileKey: fileKey)
        XCTAssertEqual(backup.prefix(10), Data("SECRETDOOR".utf8))
        XCTAssertNotNil(try BackupFile.inspect(backup))
        // Nothing readable without the password.
        XCTAssertNil(backup.range(of: Data("ciphers.json".utf8)))
        XCTAssertThrowsError(try BackupFile.open(backup, masterPassword: "nope")) {
            XCTAssertEqual($0 as? BackupError, .wrongPassword)
        }
        // Tampering is detected.
        var tampered = backup
        tampered[tampered.count - 5] ^= 0x01
        XCTAssertThrowsError(try BackupFile.open(tampered, masterPassword: "pw-one"))

        let opened = try BackupFile.open(backup, masterPassword: "pw-one")
        XCTAssertEqual(opened.userKey, userKey)
        XCTAssertEqual(opened.contents.ciphers.count, 1)
        XCTAssertEqual(opened.contents.attachments.count, 1)

        // Restore as a separate vault on another "device".
        let otherStorage = VaultStorage(root: root.appendingPathComponent("other"))
        let restored = try otherStorage.restore(opened, name: nil, asNewVault: false)
        XCTAssertEqual(restored.userId, meta.userId)
        let restoredKey = try otherStorage.unlock(userId: restored.userId, masterPassword: "pw-one")
        let restoredRecord = try XCTUnwrap(otherStorage.records(userId: restored.userId).ciphers.first)
        XCTAssertEqual(CipherCrypto.decrypt(restoredRecord, userKey: restoredKey)?.login.password, "hunter2")
        let file = try Data(contentsOf: otherStorage.attachmentURL(userId: restored.userId, cipherId: item.id, attachmentId: attachment.attachmentId))
        XCTAssertEqual(try CipherCrypto.decryptAttachment(file, attachmentId: attachment.attachmentId, record: restoredRecord, userKey: restoredKey).count, 2_500_000)

        // Merge into a different vault: re-encrypted, folder merged by name, duplicates skipped.
        let (otherMeta, otherKey) = try otherStorage.createVault(masterPassword: "pw-two", hint: nil, name: "B", kdf: testKdf)
        try otherStorage.saveFolders([try CipherCrypto.encryptFolder(FolderView(name: "Finance"), userKey: otherKey)], userId: otherMeta.userId)
        let first = try otherStorage.merge(opened, into: otherMeta.userId, userKey: otherKey)
        XCTAssertEqual(first, .init(added: 1, updated: 0, skipped: 0))
        let second = try otherStorage.merge(opened, into: otherMeta.userId, userKey: otherKey)
        XCTAssertEqual(second, .init(added: 0, updated: 0, skipped: 1))
        let merged = try otherStorage.records(userId: otherMeta.userId)
        XCTAssertEqual(merged.folders.count, 1)
        let mergedView = try XCTUnwrap(CipherCrypto.decrypt(merged.ciphers[0], userKey: otherKey))
        XCTAssertEqual(mergedView.login.password, "hunter2")
        XCTAssertEqual(mergedView.folderId, merged.folders[0]["id"]?.stringValue)
    }

    func testDefaultArgon2idSpeed() throws {
        // The real settings (64 MiB) should stay usable on a phone; this measures the Mac.
        let start = Date()
        _ = try MasterKey.derive(password: "password", salt: "x@secretdoor.local", kdf: .defaultArgon2id)
        print("Argon2id 64 MiB took \(Date().timeIntervalSince(start)) s (debug build)")
    }
}
