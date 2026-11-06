import Foundation

/// Password and passphrase generator with the same options as the Android app.
nonisolated public struct PasswordOptions: Codable, Equatable, Sendable {
    public var length = 14
    public var uppercase = true
    public var lowercase = true
    public var numbers = true
    public var special = false
    public var minNumbers = 1
    public var minSpecial = 1
    public var avoidAmbiguous = false

    public init() {}
}

nonisolated public struct PassphraseOptions: Codable, Equatable, Sendable {
    public var words = 6
    public var separator = "-"
    public var capitalize = false
    public var includeNumber = false

    public init() {}
}

nonisolated public enum PasswordGenerator {
    static let uppercase = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    static let lowercase = Array("abcdefghijklmnopqrstuvwxyz")
    static let numbers = Array("0123456789")
    static let special = Array("!@#$%^&*")
    static let ambiguous = Set("IlO01")

    public static func password(_ options: PasswordOptions) -> String {
        var options = options
        if !options.uppercase && !options.lowercase && !options.numbers && !options.special {
            options.lowercase = true
        }
        func set(_ characters: [Character]) -> [Character] {
            options.avoidAmbiguous ? characters.filter { !ambiguous.contains($0) } : characters
        }
        var required = [[Character]]()
        var pool = [Character]()
        if options.uppercase { required.append(set(uppercase)); pool += set(uppercase) }
        if options.lowercase { required.append(set(lowercase)); pool += set(lowercase) }
        if options.numbers {
            for _ in 0..<max(1, options.minNumbers) { required.append(set(numbers)) }
            pool += set(numbers)
        }
        if options.special {
            for _ in 0..<max(1, options.minSpecial) { required.append(special) }
            pool += special
        }
        let length = max(options.length, required.count, 5)
        var characters = required.map { $0[Crypto.randomInt(below: $0.count)] }
        while characters.count < length { characters.append(pool[Crypto.randomInt(below: pool.count)]) }
        // Fisher-Yates shuffle with a secure source.
        for index in stride(from: characters.count - 1, to: 0, by: -1) {
            characters.swapAt(index, Crypto.randomInt(below: index + 1))
        }
        return String(characters)
    }

    public static func passphrase(_ options: PassphraseOptions) -> String {
        let count = min(max(options.words, 3), 20)
        var words = (0..<count).map { _ in effLongWordlist[Crypto.randomInt(below: effLongWordlist.count)] }
        if options.capitalize { words = words.map { $0.prefix(1).uppercased() + $0.dropFirst() } }
        if options.includeNumber {
            let index = Crypto.randomInt(below: words.count)
            words[index] += String(Crypto.randomInt(below: 10))
        }
        return words.joined(separator: options.separator)
    }

    /// A rough strength estimate (0–4) for the master password meter.
    public static func strength(of password: String) -> Int {
        guard !password.isEmpty else { return 0 }
        var poolSize = 0
        if password.contains(where: \.isLowercase) { poolSize += 26 }
        if password.contains(where: \.isUppercase) { poolSize += 26 }
        if password.contains(where: \.isNumber) { poolSize += 10 }
        if password.contains(where: { !$0.isLetter && !$0.isNumber }) { poolSize += 33 }
        let uniqueRatio = Double(Set(password).count) / Double(password.count)
        let entropy = Double(password.count) * log2(Double(max(poolSize, 2))) * min(1, uniqueRatio + 0.3)
        switch entropy {
        case ..<28: return 0
        case ..<40: return 1
        case ..<60: return 2
        case ..<80: return 3
        default: return 4
        }
    }
}
