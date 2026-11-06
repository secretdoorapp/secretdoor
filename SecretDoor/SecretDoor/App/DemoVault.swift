#if DEBUG
import Foundation

/// Sample vault for development builds (`-SDDemoVault` launch argument, plus `-SDDemoEmpty` for
/// an empty one). Never in release builds.
enum DemoVault {
    static let password = "demo vault password"

    static var storage: VaultStorage {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return VaultStorage(root: support.appendingPathComponent("DemoVaults", isDirectory: true))
    }

    /// Recreates the demo vault with sample items and returns its key.
    static func create(in storage: VaultStorage) throws -> (VaultMeta, SymmetricKey64) {
        for meta in storage.vaults() { try? storage.deleteVault(userId: meta.userId) }
        let (meta, key) = try storage.createVault(masterPassword: password, hint: nil, name: "Demo")
        // `-SDDemoEmpty`: an empty vault, to check the empty state.
        if ProcessInfo.processInfo.arguments.contains("-SDDemoEmpty") { return (meta, key) }
        let work = FolderView(name: "Work")
        let personal = FolderView(name: "Personal")
        try storage.saveFolders([work, personal].map { try CipherCrypto.encryptFolder($0, userKey: key) }, userId: meta.userId)

        var items = [CipherView]()
        var mail = CipherView(type: .login)
        mail.name = "Example Mail"
        mail.login.username = "alice@example.com"
        mail.login.password = "correct-Horse-battery-7"
        mail.login.totp = "JBSWY3DPEHPK3PXP"
        mail.login.uris = [LoginUriView(uri: "https://mail.example.com")]
        mail.isFavorite = true
        mail.folderId = personal.id
        items.append(mail)
        var git = CipherView(type: .login)
        git.name = "Code hosting"
        git.login.username = "alice"
        git.login.password = "s3cret-Pass-word"
        git.folderId = work.id
        items.append(git)
        var card = CipherView(type: .card)
        card.name = "Visa"
        card.section = ["cardholderName": "Alice Example", "brand": "Visa", "number": "4111111111111111", "expMonth": "8", "expYear": "2029", "code": "123"]
        items.append(card)
        var identity = CipherView(type: .identity)
        identity.name = "Alice"
        identity.section = ["firstName": "Alice", "lastName": "Example", "email": "alice@example.com"]
        items.append(identity)
        var note = CipherView(type: .secureNote)
        note.name = "Wi-Fi"
        note.notes = "Network: Home\nPassword: hunter2hunter2"
        items.append(note)
        var ssh = CipherView(type: .sshKey)
        ssh.name = "Server"
        ssh.section = ["privateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\n…\n-----END OPENSSH PRIVATE KEY-----", "publicKey": "ssh-ed25519 AAAAC3Nza… alice", "keyFingerprint": "SHA256:Zm9vYmFy"]
        items.append(ssh)
        var bank = CipherView(type: .bankAccount)
        bank.name = "Checking"
        bank.section = ["bankName": "Example Bank", "accountNumber": "123456789"]
        items.append(bank)
        try storage.saveCiphers(items.map { try CipherCrypto.encrypt($0, original: nil, userKey: key) }, userId: meta.userId)
        return (meta, key)
    }
}
#endif
