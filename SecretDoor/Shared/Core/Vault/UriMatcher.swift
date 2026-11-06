import Foundation

/// Decides whether a login's website matches the page or app AutoFill was opened for, with the
/// same match types as Bitwarden / SecretDoor for Android ("URI match detection").
nonisolated public enum UriMatcher {
    /// Second-level labels under which the registrable domain has three labels
    /// (`example.co.uk`). A short list instead of the full public suffix list.
    static let multiLabelSuffixes: Set<String> = [
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
        "co.nz", "org.nz", "net.nz", "govt.nz", "ac.nz",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "co.kr", "or.kr", "ne.kr",
        "com.br", "net.br", "org.br", "gov.br",
        "com.mx", "com.ar", "com.co", "com.pe", "com.uy", "com.ve", "com.ec",
        "co.za", "org.za", "gov.za",
        "co.in", "net.in", "org.in", "gov.in", "ac.in",
        "com.cn", "net.cn", "org.cn", "gov.cn",
        "com.hk", "com.tw", "com.sg", "com.my", "com.ph", "com.vn", "co.id", "co.th", "in.th",
        "com.tr", "gov.tr", "com.ua", "com.pl", "net.pl", "org.pl",
        "co.il", "org.il", "ac.il", "com.eg", "com.sa", "com.pk", "com.ng", "co.ke",
        "co.rs", "org.rs", "in.rs", "edu.rs", "com.hr", "co.ba", "com.ba", "com.mk", "co.me",
    ]

    /// The host of `value` (a URL, or a bare host such as `example.com`), lowercased.
    public static func host(of value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let withScheme = trimmed.contains("://") ? trimmed : "https://" + trimmed
        guard let host = URLComponents(string: withScheme)?.host?.lowercased(), !host.isEmpty else { return nil }
        return host.hasSuffix(".") ? String(host.dropLast()) : host
    }

    /// The registrable domain ("base domain") of a host: `login.example.co.uk` → `example.co.uk`.
    public static func baseDomain(of host: String) -> String {
        let host = host.lowercased()
        if isIPAddress(host) || host == "localhost" { return host }
        let labels = host.split(separator: ".").map(String.init)
        guard labels.count > 2 else { return host }
        let lastTwo = labels.suffix(2).joined(separator: ".")
        let count = multiLabelSuffixes.contains(lastTwo) ? 3 : 2
        return labels.suffix(count).joined(separator: ".")
    }

    static func isIPAddress(_ host: String) -> Bool {
        if host.contains(":") { return true } // IPv6
        let parts = host.split(separator: ".")
        return parts.count == 4 && parts.allSatisfy { UInt8($0) != nil }
    }

    /// Whether the login website `uri` (with match type `match`, `nil` = default) matches
    /// `target`, a URL or a bare domain from AutoFill.
    public static func matches(uri: String, match: UriMatchType?, target: String) -> Bool {
        let uri = uri.trimmingCharacters(in: .whitespacesAndNewlines)
        let target = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uri.isEmpty, !target.isEmpty else { return false }
        switch match ?? .domain {
        case .domain:
            guard let uriHost = host(of: uri), let targetHost = host(of: target) else { return false }
            return baseDomain(of: uriHost) == baseDomain(of: targetHost)
        case .host:
            guard let uriHost = host(of: uri), let targetHost = host(of: target) else { return false }
            return uriHost == targetHost && port(of: uri) == port(of: target)
        case .startsWith:
            return normalizedURL(target).hasPrefix(normalizedURL(uri))
        case .exact:
            return normalizedURL(target) == normalizedURL(uri)
        case .regularExpression:
            guard let expression = try? NSRegularExpression(pattern: uri, options: [.caseInsensitive]) else { return false }
            let range = NSRange(target.startIndex..., in: target)
            return expression.firstMatch(in: target, options: [], range: range) != nil
        case .never:
            return false
        }
    }

    /// Whether any website of `login` matches `target`.
    public static func matches(_ login: LoginView, target: String) -> Bool {
        login.uris.contains { matches(uri: $0.uri, match: $0.match, target: target) }
    }

    private static func port(of value: String) -> Int? {
        let withScheme = value.contains("://") ? value : "https://" + value
        return URLComponents(string: withScheme)?.port
    }

    private static func normalizedURL(_ value: String) -> String {
        value.contains("://") ? value : "https://" + value
    }
}
