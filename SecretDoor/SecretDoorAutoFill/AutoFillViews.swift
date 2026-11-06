import SwiftUI

struct AutoFillRootView: View {
    @Environment(AutoFillModel.self) private var model

    var body: some View {
        NavigationStack {
            Group {
                switch model.phase {
                case .noVault:
                    ContentUnavailableView(
                        "No vault",
                        systemImage: "lock.slash",
                        description: Text("Open SecretDoor to create or restore a vault first.")
                    )
                case .locked:
                    AutoFillUnlockView()
                case .unlocked:
                    AutoFillListView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.cancel() }
                }
            }
        }
    }
}

private struct AutoFillUnlockView: View {
    @Environment(AutoFillModel.self) private var model
    @State private var password = ""
    @State private var isRevealed = false
    @State private var isWorking = false
    @State private var error: String?
    @State private var didTryBiometrics = false
    @FocusState private var isFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image("ArtMasterKey")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 72, height: 72)
                    .padding(.top, 24)
                    .accessibilityHidden(true)
                VStack(spacing: 4) {
                    Text("Unlock to fill a password").font(.title3.bold())
                    if let host = model.targetHost {
                        Text(host).foregroundStyle(.secondary)
                    }
                }
                if model.vaults.count > 1 {
                    Picker("Vault", selection: Binding(
                        get: { model.meta?.userId ?? "" },
                        set: { id in
                            model.selectVault(model.vaults.first { $0.userId == id })
                            password = ""
                            error = nil
                        }
                    )) {
                        ForEach(model.vaults, id: \.userId) { Text($0.displayName).tag($0.userId) }
                    }
                    .pickerStyle(.menu)
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Group {
                            if isRevealed {
                                TextField("Master password", text: $password)
                            } else {
                                SecureField("Master password", text: $password)
                            }
                        }
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($isFocused)
                        .submitLabel(.go)
                        .onSubmit(unlock)
                        Button {
                            isRevealed.toggle()
                        } label: {
                            Image(systemName: isRevealed ? "eye.slash" : "eye").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                    }
                    .padding(12)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                    if let error {
                        Text(error).font(.footnote).foregroundStyle(.red)
                    }
                }
                Button(action: unlock) {
                    HStack {
                        Text("Unlock")
                        if isWorking { ProgressView().padding(.leading, 4) }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(password.isEmpty || isWorking)
                if model.isBiometricUnlockAvailable {
                    Button(action: unlockWithBiometrics) {
                        Label("Unlock with \(Biometrics.available.title)", systemImage: Biometrics.available.systemImage)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }
            }
            .padding(24)
        }
        .task(id: model.meta?.userId) {
            if model.isBiometricUnlockAvailable, !didTryBiometrics {
                didTryBiometrics = true
                unlockWithBiometrics()
            } else {
                isFocused = true
            }
        }
    }

    private func unlock() {
        guard !password.isEmpty, !isWorking else { return }
        isWorking = true
        error = nil
        Task {
            defer { isWorking = false }
            do {
                try await model.unlock(masterPassword: password)
            } catch {
                self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                isFocused = true
            }
        }
    }

    private func unlockWithBiometrics() {
        Task {
            do {
                try await model.unlockWithBiometrics()
            } catch {
                isFocused = true
            }
        }
    }
}

private struct AutoFillListView: View {
    @Environment(AutoFillModel.self) private var model
    @State private var search = ""
    @State private var repromptPassword = ""
    @State private var isRepromptWrong = false

    var body: some View {
        @Bindable var model = model
        let suggestions = model.suggestions
        let query = search.lowercased()
        let others = model.logins.filter { query.isEmpty || $0.searchText.contains(query) }
        List {
            if search.isEmpty {
                Section {
                    if suggestions.isEmpty {
                        Text("No saved login matches this website. Search all logins below.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(suggestions) { LoginRow(login: $0) }
                } header: {
                    if let host = model.targetHost {
                        Text("Logins for \(host)")
                    } else {
                        Text("Suggestions")
                    }
                }
            }
            Section(search.isEmpty ? "All logins" : "Results") {
                ForEach(others) { LoginRow(login: $0) }
            }
        }
        .overlay {
            if model.logins.isEmpty {
                ContentUnavailableView("No logins", systemImage: "tray", description: Text("Add logins in the SecretDoor app."))
            } else if !search.isEmpty && others.isEmpty {
                ContentUnavailableView.search(text: search)
            }
        }
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search logins")
        .navigationTitle(model.meta.map(\.displayName) ?? "SecretDoor")
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            "Master password re-prompt",
            isPresented: Binding(get: { model.pendingReprompt != nil }, set: { if !$0 { model.pendingReprompt = nil } })
        ) {
            SecureField("Master password", text: $repromptPassword)
            Button("Fill") {
                let password = repromptPassword
                repromptPassword = ""
                Task { isRepromptWrong = !(await model.confirmReprompt(masterPassword: password)) }
            }
            Button("Cancel", role: .cancel) { repromptPassword = "" }
        } message: {
            Text("This login is protected. Enter your master password to fill it.")
        }
        .alert("Invalid master password", isPresented: $isRepromptWrong) {
            Button("OK", role: .cancel) {}
        }
    }
}

private struct LoginRow: View {
    @Environment(AutoFillModel.self) private var model
    let login: CipherView

    var body: some View {
        Button {
            model.select(login)
        } label: {
            HStack(spacing: 12) {
                Image("ArtLogin")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 32, height: 32)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(login.name).foregroundStyle(.primary).lineLimit(1)
                    if !login.login.username.isEmpty {
                        Text(login.login.username).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer()
                if login.reprompt {
                    Image(systemName: "lock").foregroundStyle(.secondary)
                }
            }
        }
    }
}
