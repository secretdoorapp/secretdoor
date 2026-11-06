import Foundation
import Observation
import UIKit

/// Automatic backups: the user picks a folder once (for example in iCloud Drive) and SecretDoor
/// rewrites `<vault name>.secretdoor` there daily or weekly. Backups are encrypted with the key
/// stored in the Keychain, so they also run while the vault is locked. iOS gives apps no exact
/// schedule, so a due backup is written the next time the app goes to the background or the
/// vault changes.
@Observable
final class AutoBackupManager {
    enum Frequency: String, CaseIterable, Identifiable, Codable {
        case off, daily, weekly
        var id: String { rawValue }

        var title: String {
            switch self {
            case .off: return String(localized: "Off")
            case .daily: return String(localized: "Daily")
            case .weekly: return String(localized: "Weekly")
            }
        }

        var interval: TimeInterval? {
            switch self {
            case .off: return nil
            case .daily: return 24 * 3600
            case .weekly: return 7 * 24 * 3600
            }
        }
    }

    struct Settings: Codable, Equatable {
        var frequency: Frequency = .off
        var folderBookmark: Data?
        var folderName: String?
        var lastSuccess: Date?
        var lastError: String?
        var lastAttempt: Date?
    }

    private(set) var settingsByVault: [String: Settings] = [:]
    private(set) var runningVaults: Set<String> = []

    init() {
        if let data = UserDefaults.standard.data(forKey: "autoBackupSettings"),
           let decoded = try? JSONDecoder().decode([String: Settings].self, from: data) {
            settingsByVault = decoded
        }
    }

    func settings(userId: String) -> Settings { settingsByVault[userId] ?? Settings() }

    func setFrequency(_ frequency: Frequency, userId: String) {
        update(userId: userId) { $0.frequency = frequency }
    }

    /// Remembers the folder picked in the document picker (a security-scoped URL).
    func setFolder(_ url: URL, userId: String) throws {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        update(userId: userId) {
            $0.folderBookmark = bookmark
            $0.folderName = url.lastPathComponent
            $0.lastError = nil
            if $0.frequency == .off { $0.frequency = .daily }
        }
    }

    func removeSettings(userId: String) {
        settingsByVault[userId] = nil
        persist()
    }

    /// Writes a backup when one is due (or `force`).
    func runIfDue(storage: VaultStorage, meta: VaultMeta, afterChange: Bool = false, force: Bool = false) {
        let current = settings(userId: meta.userId)
        guard let bookmark = current.folderBookmark, !runningVaults.contains(meta.userId) else { return }
        if !force {
            guard let interval = current.frequency.interval else { return }
            if let last = current.lastSuccess, Date().timeIntervalSince(last) < interval { return }
            // After a failure, wait a while instead of retrying on every change.
            if let attempt = current.lastAttempt, current.lastError != nil, Date().timeIntervalSince(attempt) < 3600, !afterChange {
                return
            }
        }
        guard let key = Keychain.readBackupKey(userId: meta.userId) else {
            update(userId: meta.userId) {
                $0.lastError = String(localized: "Unlock the vault once so automatic backups can be made.")
            }
            return
        }
        runningVaults.insert(meta.userId)
        // Ask iOS for time to finish when this runs as the app goes to the background.
        let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "SecretDoor backup")
        let fileName = "\(meta.displayName.replacingOccurrences(of: "/", with: "-")).\(BackupFile.fileExtension)"
        Task.detached(priority: .utility) { [weak self] in
            let result: Result<Void, Error> = Result {
                var isStale = false
                let folder = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &isStale)
                let accessing = folder.startAccessingSecurityScopedResource()
                defer { if accessing { folder.stopAccessingSecurityScopedResource() } }
                let data = try storage.makeBackup(userId: meta.userId, fileKey: key)
                let target = folder.appendingPathComponent(fileName)
                var coordinationError: NSError?
                var writeError: Error?
                NSFileCoordinator().coordinate(writingItemAt: target, options: .forReplacing, error: &coordinationError) { url in
                    do { try data.write(to: url, options: .atomic) } catch { writeError = error }
                }
                if let error = coordinationError ?? writeError { throw error }
            }
            await MainActor.run {
                guard let self else {
                    UIApplication.shared.endBackgroundTask(backgroundTask)
                    return
                }
                defer { UIApplication.shared.endBackgroundTask(backgroundTask) }
                self.runningVaults.remove(meta.userId)
                self.update(userId: meta.userId) {
                    $0.lastAttempt = Date()
                    switch result {
                    case .success:
                        $0.lastSuccess = Date()
                        $0.lastError = nil
                    case let .failure(error):
                        $0.lastError = error.localizedDescription
                    }
                }
            }
        }
    }

    private func update(userId: String, _ change: (inout Settings) -> Void) {
        var value = settings(userId: userId)
        change(&value)
        settingsByVault[userId] = value
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(settingsByVault) {
            UserDefaults.standard.set(data, forKey: "autoBackupSettings")
        }
    }
}
