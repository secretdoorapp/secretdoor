import Foundation

/// Verification codes (TOTP, RFC 6238), accepting the same keys as Bitwarden: a base32 secret,
/// an `otpauth://totp/…` URI or a `steam://` secret.
nonisolated public struct TotpConfig: Equatable, Sendable {
    public enum Algorithm: String, Sendable { case sha1 = "SHA1", sha256 = "SHA256", sha512 = "SHA512" }

    public var secret: Data
    public var digits = 6
    public var period = 30
    public var algorithm = Algorithm.sha1
    public var isSteam = false
    public var issuer: String?
    public var accountName: String?

    public init?(_ key: String) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.lowercased().hasPrefix("otpauth://") {
            guard let components = URLComponents(string: trimmed),
                  components.host?.lowercased() == "totp"
            else { return nil }
            let items = components.queryItems ?? []
            func item(_ name: String) -> String? { items.first { $0.name.lowercased() == name }?.value }
            guard let secret = item("secret").flatMap(Base32.decode), !secret.isEmpty else { return nil }
            self.secret = secret
            if let digits = item("digits").flatMap(Int.init), (5...10).contains(digits) { self.digits = digits }
            if let period = item("period").flatMap(Int.init), period > 0 { self.period = period }
            if let algorithm = item("algorithm").flatMap({ Algorithm(rawValue: $0.uppercased()) }) { self.algorithm = algorithm }
            let label = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let labelParts = label.split(separator: ":", maxSplits: 1).map { String($0).trimmingCharacters(in: .whitespaces) }
            issuer = item("issuer") ?? (labelParts.count == 2 ? labelParts[0] : nil)
            accountName = labelParts.last
            if item("encoder")?.lowercased() == "steam" {
                isSteam = true
                self.digits = 5
            }
        } else if trimmed.lowercased().hasPrefix("steam://") {
            guard let secret = Base32.decode(String(trimmed.dropFirst("steam://".count))), !secret.isEmpty else { return nil }
            self.secret = secret
            isSteam = true
            digits = 5
        } else {
            guard let secret = Base32.decode(trimmed), !secret.isEmpty else { return nil }
            self.secret = secret
        }
    }

    public func code(at date: Date = Date()) -> String {
        let counter = UInt64(max(0, date.timeIntervalSince1970) / Double(period))
        var message = Data()
        for shift in stride(from: 56, through: 0, by: -8) { message.append(UInt8((counter >> UInt64(shift)) & 0xff)) }
        let hash: Data
        switch algorithm {
        case .sha1: hash = Crypto.hmac(algorithm: .sha1, key: secret, data: message)
        case .sha256: hash = Crypto.hmac(algorithm: .sha256, key: secret, data: message)
        case .sha512: hash = Crypto.hmac(algorithm: .sha512, key: secret, data: message)
        }
        let bytes = [UInt8](hash)
        let offset = Int(bytes[bytes.count - 1] & 0x0f)
        var value = (UInt32(bytes[offset] & 0x7f) << 24) | (UInt32(bytes[offset + 1]) << 16)
            | (UInt32(bytes[offset + 2]) << 8) | UInt32(bytes[offset + 3])
        if isSteam {
            let alphabet = Array("23456789BCDFGHJKMNPQRTVWXY")
            var code = ""
            for _ in 0..<digits {
                code.append(alphabet[Int(value % UInt32(alphabet.count))])
                value /= UInt32(alphabet.count)
            }
            return code
        }
        var modulus: UInt64 = 1
        for _ in 0..<digits { modulus *= 10 }
        let code = String(UInt64(value) % modulus)
        return String(repeating: "0", count: max(0, digits - code.count)) + code
    }

    /// Seconds until the code changes.
    public func secondsRemaining(at date: Date = Date()) -> Int {
        period - Int(date.timeIntervalSince1970) % period
    }
}

nonisolated public enum Base32 {
    public static func decode(_ string: String) -> Data? {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var lookup = [Character: UInt8]()
        for (index, character) in alphabet.enumerated() { lookup[character] = UInt8(index) }
        let cleaned = string.uppercased().filter { !" -=".contains($0) }
        var buffer: UInt32 = 0
        var bits = 0
        var output = Data()
        for character in cleaned {
            guard let value = lookup[character] else { return nil }
            buffer = (buffer << 5) | UInt32(value)
            bits += 5
            if bits >= 8 {
                bits -= 8
                output.append(UInt8((buffer >> UInt32(bits)) & 0xff))
            }
        }
        return output
    }
}
