import Foundation
import Security

/// A 64-byte symmetric key: a 32-byte AES-256 key followed by a 32-byte HMAC-SHA256 key. This is
/// the type of a vault's user key, an item key and an attachment key (Bitwarden key type 2).
nonisolated public struct SymmetricKey64: Equatable, Sendable {
    public let encryptionKey: Data
    public let macKey: Data

    public init(encryptionKey: Data, macKey: Data) throws {
        guard encryptionKey.count == 32, macKey.count == 32 else { throw CryptoError.invalidKey }
        self.encryptionKey = encryptionKey
        self.macKey = macKey
    }

    public init(data: Data) throws {
        guard data.count == 64 else { throw CryptoError.invalidKey }
        try self.init(encryptionKey: data.prefix(32), macKey: data.suffix(32))
    }

    public var data: Data { encryptionKey + macKey }

    public static func generate() -> SymmetricKey64 {
        // swiftlint:disable:next force_try
        try! SymmetricKey64(data: Crypto.randomBytes(64))
    }
}

/// A Bitwarden encrypted string of type 2: `2.<iv>|<ciphertext>|<mac>` (AES-256-CBC,
/// HMAC-SHA256 over iv + ciphertext, everything base64).
nonisolated public struct EncString: Equatable, Sendable, CustomStringConvertible {
    public let iv: Data
    public let cipherText: Data
    public let mac: Data

    public init(iv: Data, cipherText: Data, mac: Data) {
        self.iv = iv
        self.cipherText = cipherText
        self.mac = mac
    }

    public init(_ string: String) throws {
        guard string.hasPrefix("2.") else {
            throw CryptoError.unsupported("Encrypted string type \(string.prefix(2))")
        }
        let parts = string.dropFirst(2).split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count == 3,
              let iv = Data(base64Encoded: String(parts[0])),
              let cipherText = Data(base64Encoded: String(parts[1])),
              let mac = Data(base64Encoded: String(parts[2])),
              iv.count == 16, mac.count == 32
        else { throw CryptoError.invalidEncString }
        self.init(iv: iv, cipherText: cipherText, mac: mac)
    }

    public var description: String { "2.\(iv.base64)|\(cipherText.base64)|\(mac.base64)" }

    /// Whether `value` looks like a type 2 encrypted string.
    public static func isEncString(_ value: String) -> Bool {
        value.hasPrefix("2.") && value.reduce(0) { $1 == "|" ? $0 + 1 : $0 } == 2
    }

    public static func encrypt(_ plaintext: Data, key: SymmetricKey64) throws -> EncString {
        let iv = Crypto.randomBytes(16)
        let cipherText = try Crypto.aesCBC(encrypt: true, key: key.encryptionKey, iv: iv, data: plaintext)
        let mac = Crypto.hmacSHA256(key: key.macKey, data: iv + cipherText)
        return EncString(iv: iv, cipherText: cipherText, mac: mac)
    }

    public static func encrypt(_ plaintext: String, key: SymmetricKey64) throws -> EncString {
        try encrypt(Data(plaintext.utf8), key: key)
    }

    public func decrypt(key: SymmetricKey64) throws -> Data {
        let expected = Crypto.hmacSHA256(key: key.macKey, data: iv + cipherText)
        guard Crypto.constantTimeEquals(expected, mac) else { throw CryptoError.macMismatch }
        return try Crypto.aesCBC(encrypt: false, key: key.encryptionKey, iv: iv, data: cipherText)
    }

    public func decryptString(key: SymmetricKey64) throws -> String {
        guard let string = String(data: try decrypt(key: key), encoding: .utf8) else {
            throw CryptoError.decryptionFailed
        }
        return string
    }

    public func decryptKey(key: SymmetricKey64) throws -> SymmetricKey64 {
        try SymmetricKey64(data: decrypt(key: key))
    }
}

/// Encrypted file content (attachments): one type byte (2), the 16-byte IV, the 32-byte MAC and
/// then the ciphertext.
nonisolated public enum EncArrayBuffer {
    public static func encrypt(_ plaintext: Data, key: SymmetricKey64) throws -> Data {
        let encrypted = try EncString.encrypt(plaintext, key: key)
        return Data([2]) + encrypted.iv + encrypted.mac + encrypted.cipherText
    }

    public static func decrypt(_ data: Data, key: SymmetricKey64) throws -> Data {
        let bytes = Data(data)
        guard bytes.count > 49, bytes[0] == 2 else { throw CryptoError.unsupported("Attachment encryption") }
        let encrypted = EncString(
            iv: bytes.subdata(in: 1..<17),
            cipherText: bytes.subdata(in: 49..<bytes.count),
            mac: bytes.subdata(in: 17..<49)
        )
        return try encrypted.decrypt(key: key)
    }
}

/// Key derivation settings of a vault, as stored in `masterPasswordUnlock.kdf`.
nonisolated public struct KdfConfig: Codable, Equatable, Sendable {
    public enum KdfType: Int, Codable, Sendable {
        case pbkdf2SHA256 = 0
        case argon2id = 1
    }

    public var kdfType: KdfType
    public var iterations: Int
    /// Argon2id memory in MiB.
    public var memory: Int?
    public var parallelism: Int?

    public init(kdfType: KdfType, iterations: Int, memory: Int?, parallelism: Int?) {
        self.kdfType = kdfType
        self.iterations = iterations
        self.memory = memory
        self.parallelism = parallelism
    }

    /// The settings SecretDoor uses for new vaults (the same as on Android).
    public static let defaultArgon2id = KdfConfig(kdfType: .argon2id, iterations: 3, memory: 64, parallelism: 4)

    private enum CodingKeys: String, CodingKey {
        case kdfType, iterations, memory, parallelism
    }

    private enum LegacyKeys: String, CodingKey {
        case kdfType = "KdfType", iterations = "Iterations", memory = "Memory", parallelism = "Parallelism"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if container.contains(.kdfType) {
            kdfType = try container.decode(KdfType.self, forKey: .kdfType)
            iterations = try container.decode(Int.self, forKey: .iterations)
            memory = try container.decodeIfPresent(Int.self, forKey: .memory)
            parallelism = try container.decodeIfPresent(Int.self, forKey: .parallelism)
        } else {
            let legacy = try decoder.container(keyedBy: LegacyKeys.self)
            kdfType = try legacy.decode(KdfType.self, forKey: .kdfType)
            iterations = try legacy.decode(Int.self, forKey: .iterations)
            memory = try legacy.decodeIfPresent(Int.self, forKey: .memory)
            parallelism = try legacy.decodeIfPresent(Int.self, forKey: .parallelism)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(kdfType, forKey: .kdfType)
        try container.encode(iterations, forKey: .iterations)
        try container.encode(memory, forKey: .memory)
        try container.encode(parallelism, forKey: .parallelism)
    }
}

/// Turns a master password into keys, the way the Bitwarden SDK does.
nonisolated public enum MasterKey {
    /// The 32-byte master key.
    public static func derive(password: String, salt: String, kdf: KdfConfig) throws -> Data {
        let passwordData = Data(password.utf8)
        let saltData = Data(salt.utf8)
        switch kdf.kdfType {
        case .pbkdf2SHA256:
            guard kdf.iterations >= 5000 else { throw CryptoError.unsupported("PBKDF2 iterations") }
            return Crypto.pbkdf2SHA256(password: passwordData, salt: saltData, iterations: kdf.iterations, length: 32)
        case .argon2id:
            guard let memory = kdf.memory, let parallelism = kdf.parallelism,
                  (15...1024).contains(memory), (1...16).contains(parallelism), kdf.iterations >= 2
            else { throw CryptoError.unsupported("Argon2id settings") }
            return Argon2id.hash(
                password: passwordData,
                salt: Crypto.sha256(saltData),
                iterations: kdf.iterations,
                memoryKiB: memory * 1024,
                parallelism: parallelism,
                length: 32
            )
        }
    }

    /// Expands the master key into the 64-byte key that wraps the user key.
    public static func stretch(_ masterKey: Data) throws -> SymmetricKey64 {
        try SymmetricKey64(
            encryptionKey: Crypto.hkdfExpand(prk: masterKey, info: Data("enc".utf8), length: 32),
            macKey: Crypto.hkdfExpand(prk: masterKey, info: Data("mac".utf8), length: 32)
        )
    }

    /// The hash Bitwarden uses to verify the master password without the key (one PBKDF2 round
    /// of the master key, salted with the password).
    public static func passwordHash(masterKey: Data, password: String) -> String {
        Crypto.pbkdf2SHA256(password: masterKey, salt: Data(password.utf8), iterations: 1, length: 32).base64
    }

    /// Decrypts the user key. Throws `CryptoError.macMismatch` for a wrong password.
    public static func unlockUserKey(
        password: String,
        unlock: MasterPasswordUnlockData
    ) throws -> SymmetricKey64 {
        let stretched = try stretch(derive(password: password, salt: unlock.salt, kdf: unlock.kdf))
        return try EncString(unlock.masterKeyWrappedUserKey).decryptKey(key: stretched)
    }

    /// Wraps `userKey` with a new master password.
    public static func wrapUserKey(
        _ userKey: SymmetricKey64,
        password: String,
        salt: String,
        kdf: KdfConfig
    ) throws -> MasterPasswordUnlockData {
        let stretched = try stretch(derive(password: password, salt: salt, kdf: kdf))
        return MasterPasswordUnlockData(
            salt: salt,
            kdf: kdf,
            masterKeyWrappedUserKey: try EncString.encrypt(userKey.data, key: stretched).description
        )
    }
}

/// What turns the master password into the user key (`MasterPasswordUnlockDataJson` on Android).
nonisolated public struct MasterPasswordUnlockData: Codable, Equatable, Sendable {
    public var salt: String
    public var kdf: KdfConfig
    public var masterKeyWrappedUserKey: String

    public init(salt: String, kdf: KdfConfig, masterKeyWrappedUserKey: String) {
        self.salt = salt
        self.kdf = kdf
        self.masterKeyWrappedUserKey = masterKeyWrappedUserKey
    }

    private enum CodingKeys: String, CodingKey {
        case salt, kdf, masterKeyWrappedUserKey
        case masterKeyEncryptedUserKey
        case legacySalt = "Salt", legacyKdf = "Kdf", legacyWrapped = "MasterKeyEncryptedUserKey"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let salt = try c.decodeIfPresent(String.self, forKey: .salt)
            ?? c.decodeIfPresent(String.self, forKey: .legacySalt),
            let kdf = try c.decodeIfPresent(KdfConfig.self, forKey: .kdf)
            ?? c.decodeIfPresent(KdfConfig.self, forKey: .legacyKdf),
            let wrapped = try c.decodeIfPresent(String.self, forKey: .masterKeyWrappedUserKey)
            ?? c.decodeIfPresent(String.self, forKey: .masterKeyEncryptedUserKey)
            ?? c.decodeIfPresent(String.self, forKey: .legacyWrapped)
        else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "masterPasswordUnlock"))
        }
        self.init(salt: salt, kdf: kdf, masterKeyWrappedUserKey: wrapped)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(salt, forKey: .salt)
        try c.encode(kdf, forKey: .kdf)
        try c.encode(masterKeyWrappedUserKey, forKey: .masterKeyWrappedUserKey)
    }
}

/// The RSA key pair every Bitwarden account has. SecretDoor does not use it (it was for sharing
/// with organizations), but the Android app expects a valid one, so new vaults get one.
nonisolated public enum AccountKeyPair {
    /// Returns the private key as PKCS#8 DER, encrypted with `userKey`.
    public static func makeWrappedPrivateKey(userKey: SymmetricKey64) throws -> String {
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits: 2048,
            kSecAttrIsPermanent: false,
        ]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error),
              let pkcs1 = SecKeyCopyExternalRepresentation(privateKey, &error) as Data?
        else { throw CryptoError.encryptionFailed }
        return try EncString.encrypt(pkcs8(fromPKCS1: pkcs1), key: userKey).description
    }

    /// Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo.
    static func pkcs8(fromPKCS1 pkcs1: Data) -> Data {
        let version = Data([0x02, 0x01, 0x00])
        // SEQUENCE { OID rsaEncryption, NULL }
        let algorithm = Data([0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00])
        let octetString = Data([0x04]) + derLength(pkcs1.count) + pkcs1
        let body = version + algorithm + octetString
        return Data([0x30]) + derLength(body.count) + body
    }

    private static func derLength(_ length: Int) -> Data {
        if length < 0x80 { return Data([UInt8(length)]) }
        var bytes = [UInt8]()
        var value = length
        while value > 0 {
            bytes.insert(UInt8(value & 0xff), at: 0)
            value >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }
}
