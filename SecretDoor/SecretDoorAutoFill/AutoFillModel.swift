import AuthenticationServices
import Foundation
import Observation

/// State of the AutoFill sheet: unlock a vault, then pick (or directly fill) a login.
@Observable
final class AutoFillModel {
    enum Phase: Equatable { case noVault, locked, unlocked }

    let storage = SharedContainer.vaultStorage()
    private(set) var phase: Phase = .noVault
    private(set) var vaults: [VaultMeta] = []
    private(set) var meta: VaultMeta?
    private(set) var logins: [CipherView] = []
    private(set) var targets: [String] = []
    private(set) var isBiometricUnlockAvailable = false
    /// A login waiting for the master password (re-prompt) before it is filled.
    var pendingReprompt: CipherView?

    @ObservationIgnored private var userKey: SymmetricKey64?
    @ObservationIgnored private var unlockedWithMasterPassword = false
    /// The login chosen in the QuickType bar, filled right after unlocking.
    @ObservationIgnored private var requestedCipherId: String?

    @ObservationIgnored var complete: (ASPasswordCredential) -> Void = { _ in }
    @ObservationIgnored var cancel: () -> Void = {}

    /// The domain shown in the title ("Logins for example.com").
    var targetHost: String? { targets.lazy.compactMap(UriMatcher.host(of:)).first }

    // MARK: Starting

    func start(serviceIdentifiers: [ASCredentialServiceIdentifier], recordIdentifier: String?) {
        targets = serviceIdentifiers.map(\.identifier)
        vaults = storage.vaults()
        let requested = AutoFillIdentities.parse(recordIdentifier: recordIdentifier)
        requestedCipherId = requested?.cipherId
        let activeId = requested?.userId ?? storage.registry().activeUserId
        selectVault(vaults.first { $0.userId == activeId } ?? vaults.first)
    }

    func selectVault(_ meta: VaultMeta?) {
        self.meta = meta
        userKey = nil
        logins = []
        phase = meta == nil ? .noVault : .locked
        isBiometricUnlockAvailable = meta.map { Keychain.hasBiometricKey(userId: $0.userId) } ?? false
    }

    // MARK: Unlocking

    func unlock(masterPassword: String) async throws {
        guard let meta else { throw VaultError.noVault }
        let storage = storage
        let key = try await Task.detached(priority: .userInitiated) {
            try storage.unlock(userId: meta.userId, masterPassword: masterPassword)
        }.value
        unlockedWithMasterPassword = true
        try open(userKey: key)
    }

    func unlockWithBiometrics() async throws {
        guard let meta else { throw VaultError.noVault }
        let reason = String(localized: "Unlock \(meta.displayName) to fill a password")
        let key = try await Task.detached(priority: .userInitiated) {
            try Keychain.readBiometricKey(userId: meta.userId, reason: reason)
        }.value
        unlockedWithMasterPassword = false
        try open(userKey: key)
    }

    private func open(userKey: SymmetricKey64) throws {
        guard let meta else { return }
        let records = try storage.records(userId: meta.userId)
        self.userKey = userKey
        logins = records.ciphers
            .compactMap { CipherCrypto.decrypt($0, userKey: userKey) }
            .filter { $0.type == .login && !$0.isDeleted && $0.archivedDate == nil && !$0.hasDecryptionErrors }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        phase = .unlocked
        if let id = requestedCipherId, let login = logins.first(where: { $0.id == id }) {
            requestedCipherId = nil
            select(login)
        }
    }

    // MARK: Choosing a login

    /// Logins whose websites match the page or app.
    var suggestions: [CipherView] {
        logins.filter { login in targets.contains { UriMatcher.matches(login.login, target: $0) } }
    }

    func select(_ login: CipherView) {
        if login.reprompt && !unlockedWithMasterPassword {
            pendingReprompt = login
        } else {
            fill(login)
        }
    }

    /// Checks the master password for a re-prompt item and fills it.
    func confirmReprompt(masterPassword: String) async -> Bool {
        guard let login = pendingReprompt, let meta else { return false }
        let storage = storage
        let key = try? await Task.detached(priority: .userInitiated) {
            try storage.unlock(userId: meta.userId, masterPassword: masterPassword)
        }.value
        guard key != nil, key == userKey else { return false }
        pendingReprompt = nil
        fill(login)
        return true
    }

    private func fill(_ login: CipherView) {
        if SharedContainer.copiesTotpAfterFill, let totp = TotpConfig(login.login.totp) {
            AutoFillIdentities.copyToClipboard(totp.code())
        }
        complete(ASPasswordCredential(user: login.login.username, password: login.login.password))
    }
}
