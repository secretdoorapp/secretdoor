import SwiftUI
import UniformTypeIdentifiers

/// First screen when there is no vault (or when adding another vault).
struct SetupView: View {
    @Environment(AppModel.self) private var model
    @State private var path: [Route] = []

    enum Route: Hashable { case create, restore }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 24) {
                Spacer()
                Image("ArtLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 200, height: 200)
                    .accessibilityHidden(true)
                VStack(spacing: 8) {
                    Text("SecretDoor")
                        .font(.largeTitle.bold())
                    Text("An offline password manager. Your vault lives only on this iPhone, encrypted with your master password.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                VStack(spacing: 12) {
                    NavigationLink(value: Route.create) {
                        Text("Create vault").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    NavigationLink(value: Route.restore) {
                        Text("Restore from backup").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }
            }
            .padding(24)
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .create: CreateVaultView()
                case .restore: RestoreBackupView()
                }
            }
            .toolbar {
                if !model.vaults.isEmpty {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { model.cancelAddingVault() }
                    }
                }
            }
        }
    }
}

struct CreateVaultView: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var password = ""
    @State private var confirmation = ""
    @State private var hint = ""
    @State private var isWorking = false
    @State private var error: String?
    @State private var showsWeakWarning = false

    private static let minimumLength = 12

    private var problem: LocalizedStringKey? {
        if password.count < Self.minimumLength { return "The master password must be at least 12 characters long." }
        if password != confirmation { return "The passwords do not match." }
        if !hint.isEmpty, hint.localizedCaseInsensitiveContains(password) { return "The hint must not contain the password." }
        return nil
    }

    var body: some View {
        Form {
            if !model.vaults.isEmpty {
                Section {
                    TextField("Vault name", text: $name)
                } footer: {
                    Text("Shown when you switch between vaults.")
                }
            }
            Section {
                PasswordField("Master password", text: $password, isNewPassword: true)
                PasswordField("Confirm master password", text: $confirmation, isNewPassword: true)
                StrengthMeter(password: password)
                    .listRowSeparator(.hidden)
            } header: {
                Text("Master password")
            } footer: {
                Text("The master password unlocks the vault and every backup of it. It cannot be recovered if you forget it: SecretDoor has no copy of it and no account.")
            }
            Section {
                TextField("Master password hint (optional)", text: $hint)
            } footer: {
                Text("Stored only on this iPhone, not encrypted. It is not included in backups.")
            }
            Section {
                Button {
                    if PasswordGenerator.strength(of: password) < 2 {
                        showsWeakWarning = true
                    } else {
                        create()
                    }
                } label: {
                    HStack {
                        Text("Create vault")
                        if isWorking {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(problem != nil || isWorking)
            } footer: {
                if let problem, !password.isEmpty { Text(problem).foregroundStyle(.red) }
            }
        }
        .navigationTitle("Create vault")
        .navigationBarTitleDisplayMode(.inline)
        .errorAlert($error)
        .alert("Weak master password", isPresented: $showsWeakWarning) {
            Button("Use anyway", role: .destructive) { create() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Anyone who gets a backup file can try to guess the master password. A longer password, or a passphrase of several random words, is much harder to guess.")
        }
    }

    private func create() {
        isWorking = true
        Task {
            defer { isWorking = false }
            do {
                try await model.createVault(
                    masterPassword: password,
                    hint: hint.isEmpty ? nil : hint,
                    name: name.trimmingCharacters(in: .whitespaces).isEmpty ? nil : name
                )
            } catch {
                self.error = error.displayMessage
            }
        }
    }
}

struct RestoreBackupView: View {
    @Environment(AppModel.self) private var model
    @State private var isPicking = false
    @State private var fileData: Data?
    @State private var fileName = ""
    @State private var createdAt: Date?
    @State private var password = ""
    @State private var name = ""
    @State private var isWorking = false
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                Button {
                    isPicking = true
                } label: {
                    Label(fileData == nil ? "Choose backup file" : "Choose another file", systemImage: "doc.badge.arrow.up")
                }
                if fileData != nil {
                    LabeledContent("File", value: fileName)
                    if let createdAt {
                        LabeledContent("Made on", value: createdAt.formatted(date: .abbreviated, time: .shortened))
                    }
                }
            } footer: {
                Text("A .secretdoor file made by SecretDoor on iPhone or Android.")
            }
            if fileData != nil {
                Section {
                    PasswordField("Master password", text: $password)
                } footer: {
                    Text("The master password the vault had when the backup was made.")
                }
                if !model.vaults.isEmpty {
                    Section {
                        TextField("Vault name", text: $name)
                    } footer: {
                        Text("The backup is added as a separate vault; your other vaults are not changed.")
                    }
                }
                Section {
                    Button {
                        restore()
                    } label: {
                        HStack {
                            Text("Restore")
                            if isWorking {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(password.isEmpty || isWorking)
                }
            }
        }
        .navigationTitle("Restore from backup")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $isPicking, allowedContentTypes: [.secretDoorBackup, .data, .item]) { result in
            do {
                let url = try result.get()
                let data = try readPickedFile(url)
                createdAt = try BackupFile.inspect(data)
                fileData = data
                fileName = url.lastPathComponent
            } catch {
                self.error = error.displayMessage
            }
        }
        .errorAlert($error)
    }

    private func restore() {
        guard let fileData else { return }
        isWorking = true
        Task {
            defer { isWorking = false }
            do {
                try await model.restoreBackup(
                    fileData,
                    masterPassword: password,
                    name: name.isEmpty ? nil : name,
                    asNewVault: !model.vaults.isEmpty
                )
            } catch {
                self.error = error.displayMessage
            }
        }
    }
}
