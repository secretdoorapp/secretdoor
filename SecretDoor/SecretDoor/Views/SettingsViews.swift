import AuthenticationServices
import SwiftUI
import UniformTypeIdentifiers

/// Settings is a list of screens in the same order as SecretDoor for Android.
struct SettingsView: View {
    var body: some View {
        NavigationStack {
            List {
                NavigationLink {
                    AccountSecurityView()
                } label: {
                    Label("Account security", systemImage: "lock.shield")
                }
                NavigationLink {
                    AutoFillSettingsView()
                } label: {
                    Label("Autofill", systemImage: "key.viewfinder")
                }
                NavigationLink {
                    VaultSettingsView()
                } label: {
                    Label("Vault", systemImage: "lock.rectangle.stack")
                }
                NavigationLink {
                    AppearanceView()
                } label: {
                    Label("Appearance", systemImage: "paintbrush")
                }
                NavigationLink {
                    OtherSettingsView()
                } label: {
                    Label("Other", systemImage: "ellipsis.circle")
                }
                NavigationLink {
                    AboutSettingsView()
                } label: {
                    Label("About", systemImage: "info.circle")
                }
            }
            .navigationTitle("Settings")
        }
    }
}

struct AccountSecurityView: View {
    @Environment(AppModel.self) private var model
    @State private var error: String?

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
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
                    Label("Session timeout", systemImage: "timer")
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
        }
        .navigationTitle("Account security")
        .errorAlert($error)
    }
}

struct VaultSettingsView: View {
    var body: some View {
        List {
            NavigationLink {
                ManageVaultsView()
            } label: {
                Label("Manage vaults", systemImage: "lock.rectangle.stack")
            }
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
        }
        .navigationTitle("Vault")
    }
}

struct AppearanceView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                Picker("Theme", selection: $model.theme) {
                    ForEach(AppTheme.allCases) { Text($0.title).tag($0) }
                }
            } footer: {
                Text("Change the application\u{2019}s color theme")
            }
        }
        .navigationTitle("Appearance")
    }
}

struct OtherSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
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
            }
        }
        .navigationTitle("Other")
    }
}

struct AboutSettingsView: View {
    var body: some View {
        Form {
            Section {
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
        .navigationTitle("About")
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

/// Lists every vault on the device; each one can be renamed or deleted, as in SecretDoor for Android.
struct ManageVaultsView: View {
    @Environment(AppModel.self) private var model
    @State private var renaming: VaultMeta?
    @State private var newName = ""
    @State private var deleting: VaultMeta?
    @State private var password = ""
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        List {
            Section {
                ForEach(model.vaults, id: \.userId) { meta in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(meta.displayName)
                            if meta.userId == model.activeMeta?.userId {
                                Text("Current vault").font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Menu {
                            Button("Rename vault") {
                                newName = meta.name ?? ""
                                renaming = meta
                            }
                            Button("Delete vault", role: .destructive) {
                                password = ""
                                deleting = meta
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
            } footer: {
                Text("Each vault has its own master password. Switching locks the current vault.")
            }
            Section {
                Button("Create a new vault") { model.startAddingVault() }
                Button("Import backup") { model.startAddingVault(restoringBackup: true) }
            }
        }
        .navigationTitle("Manage vaults")
        .alert("Rename vault", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Vault name", text: $newName)
            Button("Save") {
                if let renaming {
                    do { try model.renameVault(userId: renaming.userId, name: newName) } catch { self.error = error.displayMessage }
                }
                renaming = nil
            }
            Button("Cancel", role: .cancel) { renaming = nil }
        }
        .alert(
            "Delete vault",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })
        ) {
            SecureField("Master password", text: $password)
            Button("Delete vault", role: .destructive) { delete() }
                .disabled(isWorking)
            Button("Cancel", role: .cancel) { deleting = nil }
        } message: {
            Text("This erases \(deleting?.displayName ?? "") and everything in it from this iPhone. Backup files are not affected. This cannot be undone. Enter its master password to continue.")
        }
        .errorAlert($error)
    }

    private func delete() {
        guard let meta = deleting, !password.isEmpty else { return }
        isWorking = true
        Task {
            defer { isWorking = false; deleting = nil }
            do { try await model.deleteVault(userId: meta.userId, masterPassword: password) } catch { self.error = error.displayMessage }
        }
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
    @State private var asksImportMode = false
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
                    asksImportMode = true
                } label: {
                    Label("Import backup", systemImage: "square.and.arrow.up.on.square")
                }
            } footer: {
                Text("Adds the items of a backup to this vault. Folders with the same name are merged, items that already exist are skipped and older copies are replaced by newer ones. Or add the backup as a separate vault.")
            }
        }
        .navigationTitle("Backup")
        .confirmationDialog("Import backup", isPresented: $asksImportMode, titleVisibility: .visible) {
            Button("Merge into this vault") {
                picking = .backup
                isPicking = true
            }
            Button("Add as a separate vault") { model.startAddingVault(restoringBackup: true) }
            Button("Cancel", role: .cancel) {}
        }
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
                Text("SecretDoor is free software under the GNU General Public License v3, with an additional permission to distribute it through Apple's App Store. It uses the same formats as SecretDoor for Android, which is based on Bitwarden; it is not affiliated with or endorsed by Bitwarden Inc. Passphrases use the EFF long wordlist (CC BY 3.0 US).")
                    .font(.subheadline)
                Link("GNU GPL v3", destination: URL(string: "https://www.gnu.org/licenses/gpl-3.0.html")!)
                Link("License of SecretDoor for iPhone", destination: URL(string: "https://github.com/secretdoorapp/secretdoor/blob/main/SecretDoor/LICENSE.md")!)
            }
        }
        .navigationTitle("Privacy and license")
    }
}

struct AutoFillSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var isEnabled: Bool?
    @State private var showsSuggestions = SharedContainer.showsKeyboardSuggestions
    @State private var copiesTotp = SharedContainer.copiesTotpAfterFill
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Form {
            Section {
                LabeledContent("Status") {
                    switch isEnabled {
                    case true?: Label("On", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    case false?: Text("Off").foregroundStyle(.secondary)
                    case nil: ProgressView()
                    }
                }
                if isEnabled == false {
                    Button {
                        ASSettingsHelper.openCredentialProviderAppSettings { _ in }
                    } label: {
                        Label("Turn on AutoFill", systemImage: "gear")
                    }
                }
            } footer: {
                Text("To fill passwords in Safari and other apps, open Settings > General > AutoFill & Passwords and turn on SecretDoor. Then tap a password field and choose SecretDoor, or use the key button above the keyboard.")
            }

            Section {
                Toggle("Suggest logins above the keyboard", isOn: $showsSuggestions)
                    .onChange(of: showsSuggestions) { _, value in
                        SharedContainer.showsKeyboardSuggestions = value
                        if value { model.updateAutoFillSuggestions() } else { AutoFillIdentities.removeAll() }
                    }
            } footer: {
                Text("iOS then knows the usernames and websites of the unlocked vault (never the passwords) and shows matching logins above the keyboard. With this off, open SecretDoor from the key button instead.")
            }

            Section {
                Toggle("Copy verification code after filling", isOn: $copiesTotp)
                    .onChange(of: copiesTotp) { _, value in SharedContainer.copiesTotpAfterFill = value }
            } footer: {
                Text("For logins with an authenticator key, the current code is copied so you can paste it on the next page.")
            }
        }
        .navigationTitle("Autofill")
        .task(id: scenePhase) { await refreshState() }
    }

    private func refreshState() async {
        let state = await ASCredentialIdentityStore.shared.state()
        isEnabled = state.isEnabled
    }
}
