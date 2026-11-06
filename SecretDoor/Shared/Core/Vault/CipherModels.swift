import Foundation

/// Item types, with the numbers the Android app stores.
nonisolated public enum CipherType: Int, CaseIterable, Identifiable, Sendable {
    case login = 1
    case secureNote = 2
    case card = 3
    case identity = 4
    case sshKey = 5
    case bankAccount = 6
    case driversLicense = 7
    case passport = 8

    public var id: Int { rawValue }

    /// The order of the "Add item" menu (the same as the Android app).
    public static let displayOrder: [CipherType] = [
        .login, .card, .identity, .secureNote, .sshKey, .bankAccount, .driversLicense, .passport,
    ]

    /// The order of the vault screen's Types list (the same as the Android app).
    public static let vaultListOrder: [CipherType] = [
        .login, .card, .bankAccount, .identity, .driversLicense, .passport, .secureNote, .sshKey,
    ]

    /// The JSON key of this type's section (`login`, `card`, …).
    public var sectionKey: String {
        switch self {
        case .login: return "login"
        case .secureNote: return "secureNote"
        case .card: return "card"
        case .identity: return "identity"
        case .sshKey: return "sshKey"
        case .bankAccount: return "bankAccount"
        case .driversLicense: return "driversLicense"
        case .passport: return "passport"
        }
    }

    public var title: String {
        switch self {
        case .login: return String(localized: "Login")
        case .secureNote: return String(localized: "Secure note")
        case .card: return String(localized: "Card")
        case .identity: return String(localized: "Identity")
        case .sshKey: return String(localized: "SSH key")
        case .bankAccount: return String(localized: "Bank account")
        case .driversLicense: return String(localized: "Driver's license")
        case .passport: return String(localized: "Passport")
        }
    }

    public var systemImage: String {
        switch self {
        case .login: return "globe"
        case .secureNote: return "note.text"
        case .card: return "creditcard"
        case .identity: return "person.text.rectangle"
        case .sshKey: return "key.horizontal"
        case .bankAccount: return "building.columns"
        case .driversLicense: return "car"
        case .passport: return "book.closed"
        }
    }

    /// The fields of the type's section, in display order (not used for logins and notes).
    public var sectionFields: [SectionField] { SectionField.fields(for: self) }
}

/// One encrypted string field of a card, identity, SSH key, bank account, driver's license or
/// passport.
nonisolated public struct SectionField: Identifiable, Hashable, Sendable {
    public enum Kind: Sendable { case text, secret, multiline, number, email, phone }

    public let key: String
    public let label: String
    public let kind: Kind
    public var id: String { key }

    init(_ key: String, _ label: String, _ kind: Kind = .text) {
        self.key = key
        self.label = label
        self.kind = kind
    }

    static func fields(for type: CipherType) -> [SectionField] {
        switch type {
        case .login, .secureNote:
            return []
        case .card:
            return [
                .init("cardholderName", String(localized: "Cardholder name")),
                .init("brand", String(localized: "Brand")),
                .init("number", String(localized: "Number"), .secret),
                .init("expMonth", String(localized: "Expiration month"), .number),
                .init("expYear", String(localized: "Expiration year"), .number),
                .init("code", String(localized: "Security code"), .secret),
            ]
        case .identity:
            return [
                .init("title", String(localized: "Title")),
                .init("firstName", String(localized: "First name")),
                .init("middleName", String(localized: "Middle name")),
                .init("lastName", String(localized: "Last name")),
                .init("username", String(localized: "Username")),
                .init("company", String(localized: "Company")),
                .init("ssn", String(localized: "Social Security number"), .secret),
                .init("passportNumber", String(localized: "Passport number"), .secret),
                .init("licenseNumber", String(localized: "License number"), .secret),
                .init("email", String(localized: "Email"), .email),
                .init("phone", String(localized: "Phone"), .phone),
                .init("address1", String(localized: "Address 1")),
                .init("address2", String(localized: "Address 2")),
                .init("address3", String(localized: "Address 3")),
                .init("city", String(localized: "City / Town")),
                .init("state", String(localized: "State / Province")),
                .init("postalCode", String(localized: "Zip / Postal code")),
                .init("country", String(localized: "Country")),
            ]
        case .sshKey:
            return [
                .init("privateKey", String(localized: "Private key"), .secret),
                .init("publicKey", String(localized: "Public key"), .multiline),
                .init("keyFingerprint", String(localized: "Fingerprint")),
            ]
        case .bankAccount:
            return [
                .init("bankName", String(localized: "Bank name")),
                .init("nameOnAccount", String(localized: "Name on account")),
                .init("accountType", String(localized: "Account type")),
                .init("accountNumber", String(localized: "Account number"), .secret),
                .init("routingNumber", String(localized: "Routing number")),
                .init("branchNumber", String(localized: "Branch number")),
                .init("pin", String(localized: "PIN"), .secret),
                .init("swiftCode", String(localized: "SWIFT code")),
                .init("iban", String(localized: "IBAN"), .secret),
                .init("bankContactPhone", String(localized: "Bank contact phone"), .phone),
            ]
        case .driversLicense:
            return [
                .init("firstName", String(localized: "First name")),
                .init("middleName", String(localized: "Middle name")),
                .init("lastName", String(localized: "Last name")),
                .init("licenseNumber", String(localized: "License number"), .secret),
                .init("dateOfBirth", String(localized: "Date of birth")),
                .init("issuingCountry", String(localized: "Issuing country")),
                .init("issuingState", String(localized: "Issuing state")),
                .init("issuingAuthority", String(localized: "Issuing authority")),
                .init("issueDate", String(localized: "Issue date")),
                .init("expirationDate", String(localized: "Expiration date")),
                .init("licenseClass", String(localized: "License class")),
            ]
        case .passport:
            return [
                .init("surname", String(localized: "Surname")),
                .init("givenName", String(localized: "Given name")),
                .init("dateOfBirth", String(localized: "Date of birth")),
                .init("birthPlace", String(localized: "Place of birth")),
                .init("sex", String(localized: "Sex")),
                .init("nationality", String(localized: "Nationality")),
                .init("passportNumber", String(localized: "Passport number"), .secret),
                .init("passportType", String(localized: "Passport type")),
                .init("nationalIdentificationNumber", String(localized: "National ID number"), .secret),
                .init("issuingCountry", String(localized: "Issuing country")),
                .init("issuingAuthority", String(localized: "Issuing authority")),
                .init("issueDate", String(localized: "Issue date")),
                .init("expirationDate", String(localized: "Expiration date")),
            ]
        }
    }
}

/// URI match detection (`UriMatchTypeJson`).
nonisolated public enum UriMatchType: Int, CaseIterable, Identifiable, Sendable {
    case domain = 0, host, startsWith, exact, regularExpression, never
    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .domain: return String(localized: "Base domain")
        case .host: return String(localized: "Host")
        case .startsWith: return String(localized: "Starts with")
        case .exact: return String(localized: "Exact")
        case .regularExpression: return String(localized: "Regular expression")
        case .never: return String(localized: "Never")
        }
    }
}

nonisolated public struct LoginUriView: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var uri: String
    /// `nil` uses the default (base domain).
    public var match: UriMatchType?

    public init(uri: String, match: UriMatchType? = nil) {
        self.uri = uri
        self.match = match
    }
}

nonisolated public struct LoginView: Hashable, Sendable {
    public var username: String = ""
    public var password: String = ""
    public var totp: String = ""
    public var uris: [LoginUriView] = []
    public var passwordRevisionDate: Date?
    /// Number of passkeys stored with the login (kept, but not usable on iPhone yet).
    public var passkeyCount = 0

    public init() {}
}

/// Custom field types (`FieldTypeJson`).
nonisolated public enum FieldType: Int, CaseIterable, Identifiable, Sendable {
    case text = 0, hidden, boolean, linked
    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .text: return String(localized: "Text")
        case .hidden: return String(localized: "Hidden")
        case .boolean: return String(localized: "Checkbox")
        case .linked: return String(localized: "Linked")
        }
    }
}

nonisolated public struct FieldView: Identifiable, Hashable, Sendable {
    public var id = UUID()
    public var name: String
    public var value: String
    public var type: FieldType
    /// For linked fields (`LinkedIdTypeJson`), kept as is.
    public var linkedId: Int?

    public init(name: String = "", value: String = "", type: FieldType = .text, linkedId: Int? = nil) {
        self.name = name
        self.value = value
        self.type = type
        self.linkedId = linkedId
    }
}

nonisolated public struct PasswordHistoryView: Hashable, Sendable {
    public var password: String
    public var lastUsedDate: Date
}

nonisolated public struct AttachmentView: Identifiable, Hashable, Sendable {
    public var id: String
    public var fileName: String
    public var size: Int
    public var sizeName: String?
}

/// A decrypted item.
nonisolated public struct CipherView: Identifiable, Hashable, Sendable {
    public var id: String
    public var type: CipherType
    public var name: String = ""
    public var notes: String = ""
    public var folderId: String?
    public var isFavorite = false
    /// Ask for the master password before showing the item.
    public var reprompt = false
    public var login = LoginView()
    /// Decrypted section fields (card, identity, …) by JSON key.
    public var section: [String: String] = [:]
    public var fields: [FieldView] = []
    public var passwordHistory: [PasswordHistoryView] = []
    public var attachments: [AttachmentView] = []
    public var creationDate = Date()
    public var revisionDate = Date()
    public var deletedDate: Date?
    public var archivedDate: Date?
    /// Some parts could not be decrypted (or use an unsupported format); saving is disabled.
    public var hasDecryptionErrors = false

    public init(id: String = UUID().uuidString.lowercased(), type: CipherType) {
        self.id = id
        self.type = type
    }

    public var isDeleted: Bool { deletedDate != nil }

    /// Second line in lists.
    public var subtitle: String {
        switch type {
        case .login: return login.username
        case .card:
            let brand = section["brand"] ?? ""
            let number = section["number"] ?? ""
            let last4 = number.count >= 4 ? "*" + number.suffix(4) : ""
            return [brand, last4].filter { !$0.isEmpty }.joined(separator: ", ")
        case .identity:
            return [section["firstName"], section["lastName"]].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        case .sshKey: return section["keyFingerprint"] ?? ""
        case .bankAccount: return section["bankName"] ?? ""
        case .driversLicense:
            return [section["firstName"], section["lastName"]].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        case .passport:
            return [section["givenName"], section["surname"]].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        case .secureNote: return ""
        }
    }

    /// Text searched by the search field.
    public var searchText: String {
        var parts = [name, notes, login.username] + login.uris.map(\.uri)
        parts += section.filter { key, _ in
            !(type.sectionFields.first { $0.key == key }?.kind == .secret)
        }.map(\.value)
        parts += fields.filter { $0.type != .hidden }.map { "\($0.name) \($0.value)" }
        return parts.joined(separator: "\n").lowercased()
    }
}

nonisolated public struct FolderView: Identifiable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var revisionDate: Date

    public init(id: String = UUID().uuidString.lowercased(), name: String, revisionDate: Date = Date()) {
        self.id = id
        self.name = name
        self.revisionDate = revisionDate
    }
}
