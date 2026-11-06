import Foundation
import XCTest
@testable import SecretDoorCore

final class CryptoPrimitivesTests: XCTestCase {
    func testBlake2bAbc() {
        let hash = Blake2b.hash(Array("abc".utf8), outputLength: 64)
        XCTAssertEqual(
            Data(hash).hexString,
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"
        )
    }

    /// RFC 9106, section 5.3.
    func testArgon2idRfcVector() {
        let tag = Argon2id.hash(
            password: Data(repeating: 0x01, count: 32),
            salt: Data(repeating: 0x02, count: 16),
            iterations: 3,
            memoryKiB: 32,
            parallelism: 4,
            length: 32,
            secret: Data(repeating: 0x03, count: 8),
            associatedData: Data(repeating: 0x04, count: 12)
        )
        XCTAssertEqual(tag.hexString, "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659")
    }

    func testPbkdf2Sha256() {
        let key = Crypto.pbkdf2SHA256(password: Data("password".utf8), salt: Data("salt".utf8), iterations: 1, length: 32)
        XCTAssertEqual(key.hexString, "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")
    }

    /// RFC 5869, test case 1.
    func testHkdf() {
        let okm = Crypto.hkdf(
            ikm: Data(repeating: 0x0b, count: 22),
            salt: Data(hex: "000102030405060708090a0b0c")!,
            info: Data(hex: "f0f1f2f3f4f5f6f7f8f9")!,
            length: 42
        )
        XCTAssertEqual(
            okm.hexString,
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        )
    }

    func testAesCbcRoundTrip() throws {
        let key = Crypto.randomBytes(32)
        let iv = Crypto.randomBytes(16)
        let encrypted = try Crypto.aesCBC(encrypt: true, key: key, iv: iv, data: Data("hello".utf8))
        XCTAssertEqual(encrypted.count, 16)
        XCTAssertEqual(try Crypto.aesCBC(encrypt: false, key: key, iv: iv, data: encrypted), Data("hello".utf8))
    }
}
