import AuthenticationServices
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Registers the active vault's logins with iOS so they are offered above the keyboard. Only the
/// username, the website and an item reference are given to iOS, never a password; iOS keeps
/// them on this device.
nonisolated enum AutoFillIdentities {
    /// `<vault id>|<item id>`, so the extension knows which vault to unlock.
    static func recordIdentifier(userId: String, cipherId: String) -> String { "\(userId)|\(cipherId)" }

    static func parse(recordIdentifier: String?) -> (userId: String, cipherId: String)? {
        guard let parts = recordIdentifier?.split(separator: "|"), parts.count == 2 else { return nil }
        return (String(parts[0]), String(parts[1]))
    }

    /// Replaces the registered logins with those of `ciphers` (or removes them all).
    static func update(userId: String, ciphers: [CipherView]) {
        let store = ASCredentialIdentityStore.shared
        store.getState { state in
            guard state.isEnabled else { return }
            guard SharedContainer.showsKeyboardSuggestions else {
                store.removeAllCredentialIdentities(nil)
                return
            }
            var identities = [ASPasswordCredentialIdentity]()
            for cipher in ciphers where cipher.type == .login && !cipher.isDeleted && cipher.archivedDate == nil {
                guard !cipher.login.username.isEmpty || !cipher.login.password.isEmpty else { continue }
                var seen = Set<String>()
                for uri in cipher.login.uris where uri.match != .never {
                    guard let host = UriMatcher.host(of: uri.uri), seen.insert(host).inserted else { continue }
                    let service = ASCredentialServiceIdentifier(
                        identifier: uri.uri.contains("://") ? uri.uri : host,
                        type: uri.uri.contains("://") ? .URL : .domain
                    )
                    identities.append(ASPasswordCredentialIdentity(
                        serviceIdentifier: service,
                        user: cipher.login.username.isEmpty ? cipher.name : cipher.login.username,
                        recordIdentifier: recordIdentifier(userId: userId, cipherId: cipher.id)
                    ))
                }
            }
            store.replaceCredentialIdentities(with: identities) { _, error in
                if let error { NSLog("SecretDoor: updating AutoFill suggestions failed: \(error)") }
            }
        }
    }

    static func removeAll() {
        ASCredentialIdentityStore.shared.removeAllCredentialIdentities(nil)
    }

    /// Copies `value` the way the app does: kept off other devices, cleared after a while.
    static func copyToClipboard(_ value: String) {
        var options: [UIPasteboard.OptionsKey: Any] = [.localOnly: true]
        let seconds = SharedContainer.clipboardClearSeconds
        if seconds > 0 { options[.expirationDate] = Date().addingTimeInterval(Double(seconds)) }
        UIPasteboard.general.setItems([[UTType.plainText.identifier: value]], options: options)
    }
}
