import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(VaultSession.self) private var session
    @State private var error: String?
    @State private var showsRename = false
    @State private var newName = ""

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section("Security") {
                    if Biometrics.available != .none {
                        Toggle(isOn: Binding(
                            get: { model.isBiometricUnlockEnabled },
                            set: { enabled in
                                do { try model.setBiometricUnlock(enabled) } catch { self.error = error.displayMessage }
                            }
                        )) {
                            Label("Unlock with \(Biometrics.available.title)", systemImage: Biometrics.available.systemImage)
                        }
                    }
                    Picker(selection: $model.autoLockTimeout) {
                        ForEach(AutoLockTimeout.allCases) { Text($0.title).tag($0) }
                    } label: {
                        Label("Lock after", systemImage: "timer")
                    }
                    Picker(selection: $model.clipboardClearSeconds) {
                        Text("10 seconds").tag(10)
                        Text("30 seconds").tag(30)
                        Text("1 minute").tag(60)
                        Text("90 seconds").tag(90)
                        Text("5 minutes").tag(300)
                        Text("Never").tag(0)
                    } label: {
                        Label("Clear clipboard", systemImage: "doc.on.clipboard")
                    }
                    NavigationLink {
                        ChangePasswordView()
                    } label: {
                        Label("Change master password", systemImage: "key")
                    }
                    Button {
                        model.lock()
                    } label: {
                        Label("Lock now", systemImage: "lock")
                    }
                }

                Section("Vault") {
                    NavigationLink {
                        FoldersView()
                    } label: {
                        Label("Folders", systemImage: "folder")
                    }
                    NavigationLink {
                        BackupSettingsView()
                    } label: {
                        Label("Backup", systemImage: "externaldrive.badge.checkmark")
                    }
                    Button {
                        newName = session.meta.name ?? ""
                        showsRename = true
                    } label: {
                        LabeledContent {
                            Text(session.meta.displayName)
                        } label: {
                            Label("Vault name", systemImage: "character.cursor.ibeam")
                        }
                    }
                }

                Section {
                    if model.vaults.count > 1 {
                        ForEach(model.vaults, id: \.userId) { meta in
                            Button {
                                if meta.userId != session.userId { model.switchVault(to: meta.userId) }
                            } label: {
                                HStack {
                                    Label(meta.displayName, systemImage: "lock.rectangle")
                                    Spacer()
                                    if meta.userId == session.userId {
                                        Image(systemName: "checkmark").foregroundStyle(.tint)
                                    }
                                }
                            }
                        }
                    }
                    Button {
                        model.startAddingVault()
                    } label: {
                        Label("Add vault", systemImage: "plus.rectangle.on.rectangle")
                    }
                    NavigationLink {
                        DeleteVaultView()
                    } label: {
                        Label("Delete vault", systemImage: "trash")
                            .foregroundStyle(.red)
                    }
                } header: {
                    Text("Vaults")
                } footer: {
                    Text("Each vault has its own master password. Switching locks the current vault.")
                }

                Section("About") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    Link(destination: URL(string: "https://github.com/secretdoorapp/secretdoor")!) {
                        Label("Source code", systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                    NavigationLink {
                        AboutView()
                    } label: {
                        Label("Privacy and license", systemImage: "hand.raised")
                    }
                }
            }
            .navigationTitle("Settings")
            .alert("Vault name", isPresented: $showsRename) {
                TextField("My vault", text: $newName)
                Button("Save") {
                    do { try model.renameVault(newName) } catch { self.error = error.displayMessage }
                }
                Button("Cancel", role: .cancel) {}
            }
            .errorAlert($error)
        }
    }
}

struct ChangePasswordView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var new = ""
    @State private var confirmation = ""
    @State private var hint = ""
    @State private var isWorking = false
    @State private var error: String?
    @State private var didChange = false

    var body: some View {
        Form {
            Section {
                PasswordField("Current master password", text: $current)
            }
            Section {
                PasswordField("New master password", text: $new, isNewPassword: true)
                PasswordField("Confirm new master password", text: $confirmation, isNewPassword: true)
                StrengthMeter(password: new).listRowSeparator(.hidden)
                TextField("Master password hint (optional)", text: $hint)
            } footer: {
                Text("Backups made before the change still need the old master password. Make a new backup afterwards.")
            }
            Section {
                Button {
                    change()
                } label: {
                    HStack {
                        Text("Change master password")
                        if isWorking { Spacer(); ProgressView() }
                    }
                }
                .disabled(current.isEmpty || new.count < 12 || new != confirmation || isWorking)
            } footer: {
                if !new.isEmpty, new.count < 12 {
                    Text("The master password must be at least 12 characters long.").foregroundStyle(.red)
                } else if !confirmation.isEmpty, new != confirmation {
                    Text("The passwords do not match.").foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Change master password")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { hint = model.activeMeta?.masterPasswordHint ?? "" }
        .errorAlert($error)
        .alert("Master password changed", isPresented: $didChange) {
            Button("OK") { dismiss() }
        } message: {
            Text("Remember to make a new backup.")
        }
    }

    private func change() {
        isWorking = true
        Task {
            defer { isWorking = false }
            do {
                try await model.changeMasterPassword(current: current, new: new, hint: hint.isEmpty ? nil : hint)
                didChange = true
            } catch {
                self.error = error.displayMessage
            }
        }
    }
}

struct DeleteVaultView: View {
    @Environment(AppModel.self) private var model
    @Environment(VaultSession.self) private var session
    @State private var password = ""
    @State private var isWorking = false
    @State private var error: String?
    @State private var showsConfirmation = false

    var body: some View {
        Form {
            Section {
                Text("This erases \(session.meta.displayName) and everything in it from this iPhone. Backup files are not affected. This cannot be undone.")
            }
            Section {
                PasswordField("Master password", text: $password)
            }
            Section {
                Button("Delete vault", role: .destructive) { showsConfirmation = true }
                    .disabled(password.isEmpty || isWorking)
            }
        }
        .navigationTitle("Delete vault")
        .confirmationDialog("Delete this vault permanently?", isPresented: $showsConfirmation, titleVisibility: .visible) {
            Button("Delete vault", role: .destructive) {
                isWorking = true
                Task {
                    defer { isWorking = false }
                    do { try await model.deleteVault(masterPassword: password) } catch { self.error = error.displayMessage }
                }
            }
        }
        .errorAlert($error)
    }
}

struct FoldersView: View {
    @Environment(VaultSession.self) private var session
    @State private var editingFolder: FolderView?
    @State private var name = ""
    @State private var showsEditor = false
    @State private var error: String?

    var body: some View {
        List {
            ForEach(session.folders) { folder in
                Button(folder.name) {
                    editingFolder = folder
                    name = folder.name
                    showsEditor = true
                }
                .foregroundStyle(.primary)
            }
            .onDelete { offsets in
                for index in offsets {
                    do { try session.deleteFolder(session.folders[index]) } catch { self.error = error.displayMessage }
                }
            }
        }
        .overlay {
            if session.folders.isEmpty {
                ContentUnavailableView("No folders", systemImage: "folder", description: Text("Folders help you organize your items."))
            }
        }
        .navigationTitle("Folders")
        .toolbar {
            Button {
                editingFolder = nil
                name = ""
                showsEditor = true
            } label: {
                Label("Add folder", systemImage: "plus")
            }
        }
        .alert(editingFolder == nil ? "New folder" : "Rename folder", isPresented: $showsEditor) {
            TextField("Name", text: $name)
            Button("Save") {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                guard !trimmed.isEmpty else { return }
                var folder = editingFolder ?? FolderView(name: trimmed)
                folder.name = trimmed
                do { try session.saveFolder(folder) } catch { self.error = error.displayMessage }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Deleting a folder (swipe left) moves its items to \"No folder\".")
        }
        .errorAlert($error)
    }
}

private enum Picking { case folder, backup }

struct BackupSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(VaultSession.self) private var session
    @State private var exportDocument: BackupDocument?
    @State private var isExporting = false
    /// What the file picker is open for (one `fileImporter` per view works reliably).
    @State private var picking = Picking.backup
    @State private var isPicking = false
    @State private var importData: Data?
    @State private var message: String?
    @State private var error: String?

    var body: some View {
        let settings = model.autoBackup.settings(userId: session.userId)
        Form {
            Section {
                Button {
                    do {
                        exportDocument = BackupDocument(data: try session.makeBackup())
                        isExporting = true
                    } catch {
                        self.error = error.displayMessage
                    }
                } label: {
                    Label("Save backup file", systemImage: "square.and.arrow.down")
                }
                if let last = session.meta.lastBackupAt {
                    LabeledContent("Last backup", value: last.formatted(date: .abbreviated, time: .shortened))
                }
            } footer: {
                Text("Your vault exists only on this iPhone. Save a backup somewhere else, for example in iCloud Drive or on a computer. You need the master password to restore it; it also works with SecretDoor for Android.")
            }

            Section {
                Button {
                    picking = .folder
                    isPicking = true
                } label: {
                    LabeledContent {
                        Text(settings.folderName ?? String(localized: "Not set"))
                    } label: {
                        Label("Backup folder", systemImage: "folder")
                    }
                }
                Picker("Frequency", selection: Binding(
                    get: { settings.frequency },
                    set: { model.autoBackup.setFrequency($0, userId: session.userId) }
                )) {
                    ForEach(AutoBackupManager.Frequency.allCases) { Text($0.title).tag($0) }
                }
                .disabled(settings.folderBookmark == nil)
                if settings.folderBookmark != nil {
                    Button {
                        model.autoBackup.runIfDue(storage: model.storage, meta: session.meta, force: true)
                    } label: {
                        HStack {
                            Label("Back up now", systemImage: "arrow.clockwise")
                            if model.autoBackup.runningVaults.contains(session.userId) { Spacer(); ProgressView() }
                        }
                    }
                }
                if let last = settings.lastSuccess {
                    LabeledContent("Last automatic backup", value: last.formatted(date: .abbreviated, time: .shortened))
                }
                if let lastError = settings.lastError {
                    Label(lastError, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                        .font(.footnote)
                }
            } header: {
                Text("Automatic backup")
            } footer: {
                Text("SecretDoor rewrites \"\(session.meta.displayName).secretdoor\" in the chosen folder when a backup is due, the next time you leave the app or change the vault. iCloud Drive keeps earlier versions of the file.")
            }

            Section {
                Button {
                    picking = .backup
                    isPicking = true
                } label: {
                    Label("Import backup", systemImage: "square.and.arrow.up.on.square")
                }
            } footer: {
                Text("Adds the items of a backup to this vault. Folders with the same name are merged, items that already exist are skipped and older copies are replaced by newer ones. To keep a backup separate, use Settings > Add vault > Restore from backup.")
            }
        }
        .navigationTitle("Backup")
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: .secretDoorBackup,
            defaultFilename: session.backupFileName
        ) { result in
            if case let .failure(failure) = result { error = failure.displayMessage }
            exportDocument = nil
        }
        .fileImporter(
            isPresented: $isPicking,
            allowedContentTypes: picking == .folder ? [.folder] : [.secretDoorBackup, .data, .item]
        ) { result in
            let mode = picking
            do {
                let url = try result.get()
                if mode == .folder {
                    try model.autoBackup.setFolder(url, userId: session.userId)
                    model.autoBackup.runIfDue(storage: model.storage, meta: session.meta, force: true)
                } else {
                    let data = try readPickedFile(url)
                    _ = try BackupFile.inspect(data)
                    importData = data
                }
            } catch {
                self.error = error.displayMessage
            }
        }
        .sheet(isPresented: Binding(get: { importData != nil }, set: { if !$0 { importData = nil } })) {
            if let importData {
                ImportBackupSheet(data: importData) { result in
                    self.importData = nil
                    message = String(localized: "Added \(result.added), updated \(result.updated), skipped \(result.skipped) items.")
                }
                .environment(session)
            }
        }
        .alert("Backup imported", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
        .errorAlert($error)
    }
}

private struct ImportBackupSheet: View {
    @Environment(VaultSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    let data: Data
    let onDone: (VaultStorage.MergeResult) -> Void
    @State private var password = ""
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PasswordField("Master password of the backup", text: $password)
                } footer: {
                    Text("The master password the vault had when the backup was made.")
                }
                Section {
                    Button {
                        isWorking = true
                        Task {
                            defer { isWorking = false }
                            do {
                                onDone(try await session.mergeBackup(data, masterPassword: password))
                            } catch {
                                self.error = error.displayMessage
                            }
                        }
                    } label: {
                        HStack {
                            Text("Import")
                            if isWorking { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(password.isEmpty || isWorking)
                }
            }
            .navigationTitle("Import backup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .errorAlert($error)
        }
    }
}

struct AboutView: View {
    var body: some View {
        Form {
            Section("Privacy") {
                Label("No account and no server: the vault is created and stays on this iPhone.", systemImage: "iphone")
                Label("Zero knowledge: everything is encrypted with a key only your master password unlocks. A lost master password cannot be recovered.", systemImage: "lock")
                Label("No network access, analytics or crash reporting.", systemImage: "network.slash")
                Label("Backups go only where you save them.", systemImage: "externaldrive")
            }
            .font(.subheadline)
            Section("License") {
                Text("SecretDoor is free software under the GNU General Public License v3. It is based on Bitwarden and is not affiliated with or endorsed by Bitwarden Inc. Passphrases use the EFF long wordlist (CC BY 3.0 US).")
                    .font(.subheadline)
                Link("GNU GPL v3", destination: URL(string: "https://www.gnu.org/licenses/gpl-3.0.html")!)
            }
        }
        .navigationTitle("Privacy and license")
    }
}
