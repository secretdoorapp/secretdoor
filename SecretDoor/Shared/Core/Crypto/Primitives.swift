import CommonCrypto
import CryptoKit
import Foundation
import Security

/// Errors thrown by SecretDoor's cryptography.
nonisolated public enum CryptoError: Error, Equatable {
    case invalidKey
    case invalidEncString
    case macMismatch
    case decryptionFailed
    case encryptionFailed
    case unsupported(String)
}

/// Low level primitives. Everything here matches what the Bitwarden SDK (used by SecretDoor for
/// Android) does, so data written by one app can be read by the other.
nonisolated public enum Crypto {
    /// Returns `count` cryptographically secure random bytes.
    public static func randomBytes(_ count: Int) -> Data {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return data
    }

    /// A uniformly distributed random integer in `0..<upperBound`.
    public static func randomInt(below upperBound: Int) -> Int {
        precondition(upperBound > 0)
        var generator = SecureRandomNumberGenerator()
        return Int.random(in: 0..<upperBound, using: &generator)
    }

    public static func sha1(_ data: Data) -> Data { Data(Insecure.SHA1.hash(data: data)) }

    public static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    public static func hmacSHA256(key: Data, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    public static func hmac(algorithm: HmacAlgorithm, key: Data, data: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        switch algorithm {
        case .sha1: return Data(HMAC<Insecure.SHA1>.authenticationCode(for: data, using: symmetricKey))
        case .sha256: return Data(HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey))
        case .sha512: return Data(HMAC<SHA512>.authenticationCode(for: data, using: symmetricKey))
        }
    }

    public enum HmacAlgorithm { case sha1, sha256, sha512 }

    /// HKDF-Expand (RFC 5869) with SHA-256, where `prk` is already a pseudorandom key.
    public static func hkdfExpand(prk: Data, info: Data, length: Int) -> Data {
        var output = Data()
        var previous = Data()
        var counter: UInt8 = 1
        while output.count < length {
            previous = hmacSHA256(key: prk, data: previous + info + Data([counter]))
            output.append(previous)
            counter += 1
        }
        return output.prefix(length)
    }

    /// HKDF (extract and expand) with SHA-256.
    public static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        hkdfExpand(prk: hmacSHA256(key: salt, data: ikm), info: info, length: length)
    }

    /// PBKDF2-HMAC-SHA256.
    public static func pbkdf2SHA256(password: Data, salt: Data, iterations: Int, length: Int) -> Data {
        var derived = Data(count: length)
        let status = derived.withUnsafeMutableBytes { derivedBytes in
            password.withUnsafeBytes { passwordBytes in
                salt.withUnsafeBytes { saltBytes in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordBytes.baseAddress?.assumingMemoryBound(to: CChar.self),
                        password.count,
                        saltBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        UInt32(iterations),
                        derivedBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                        length
                    )
                }
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 failed")
        return derived
    }

    /// AES-256-CBC with PKCS#7 padding.
    public static func aesCBC(encrypt: Bool, key: Data, iv: Data, data: Data) throws -> Data {
        guard key.count == kCCKeySizeAES256, iv.count == kCCBlockSizeAES128 else {
            throw CryptoError.invalidKey
        }
        var output = Data(count: data.count + kCCBlockSizeAES128)
        var outputLength = 0
        let outputCapacity = output.count
        let status = output.withUnsafeMutableBytes { outputBytes in
            data.withUnsafeBytes { dataBytes in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes in
                        CCCrypt(
                            CCOperation(encrypt ? kCCEncrypt : kCCDecrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyBytes.baseAddress, key.count,
                            ivBytes.baseAddress,
                            dataBytes.baseAddress, data.count,
                            outputBytes.baseAddress, outputCapacity,
                            &outputLength
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess else {
            throw encrypt ? CryptoError.encryptionFailed : CryptoError.decryptionFailed
        }
        return output.prefix(outputLength)
    }

    /// AES-256-GCM. The result is the ciphertext followed by the 16-byte tag (as in Java).
    public static func aesGCMSeal(key: Data, nonce: Data, plaintext: Data, associatedData: Data) throws -> Data {
        let sealed = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: AES.GCM.Nonce(data: nonce),
            authenticating: associatedData
        )
        return sealed.ciphertext + sealed.tag
    }

    /// Opens data sealed by `aesGCMSeal` (ciphertext followed by the tag).
    public static func aesGCMOpen(key: Data, nonce: Data, sealed: Data, associatedData: Data) throws -> Data {
        guard sealed.count >= 16 else { throw CryptoError.decryptionFailed }
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce),
                ciphertext: sealed.prefix(sealed.count - 16),
                tag: sealed.suffix(16)
            )
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: associatedData)
        } catch {
            throw CryptoError.decryptionFailed
        }
    }

    /// Compares two byte strings in constant time (for the same length).
    public static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for (x, y) in zip(a, b) { difference |= x ^ y }
        return difference == 0
    }
}

/// A `RandomNumberGenerator` backed by `SecRandomCopyBytes`.
nonisolated public struct SecureRandomNumberGenerator: RandomNumberGenerator {
    public init() {}

    public mutating func next() -> UInt64 {
        var value: UInt64 = 0
        let status = withUnsafeMutableBytes(of: &value) { buffer in
            SecRandomCopyBytes(kSecRandomDefault, 8, buffer.baseAddress!)
        }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return value
    }
}

nonisolated extension Data {
    /// Standard base64 without line breaks.
    public var base64: String { base64EncodedString() }

    /// Decodes standard base64, also accepting missing padding and base64url characters.
    public init?(base64Flexible string: String) {
        var value = string.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while value.count % 4 != 0 { value.append("=") }
        guard let data = Data(base64Encoded: value) else { return nil }
        self = data
    }

    public var hexString: String { map { String(format: "%02x", $0) }.joined() }

    public init?(hex: String) {
        let characters = Array(hex.utf8)
        guard characters.count % 2 == 0 else { return nil }
        var data = Data(capacity: characters.count / 2)
        var index = 0
        while index < characters.count {
            guard let byte = UInt8(String(decoding: characters[index..<index + 2], as: UTF8.self), radix: 16) else {
                return nil
            }
            data.append(byte)
            index += 2
        }
        self = data
    }

    /// Overwrites the bytes with zeros (best effort; copies may exist elsewhere).
    public mutating func wipe() {
        resetBytes(in: 0..<count)
    }
}
