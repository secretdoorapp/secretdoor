import SwiftUI

struct UnlockView: View {
    @Environment(AppModel.self) private var model
    @State private var password = ""
    @State private var isWorking = false
    @State private var error: String?
    @State private var showsHint = false
    @State private var didTryBiometrics = false
    @FocusState private var isPasswordFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Image("ArtMasterKey")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 88, height: 88)
                        .accessibilityHidden(true)
                        .padding(.top, 48)
                    VStack(spacing: 4) {
                        Text("Your vault is locked")
                            .font(.title2.bold())
                        if let meta = model.activeMeta, model.vaults.count > 1 {
                            Text("Vault: \(meta.displayName)")
                                .foregroundStyle(.secondary)
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        PasswordField("Master password", text: $password)
                            .focused($isPasswordFocused)
                            .submitLabel(.go)
                            .onSubmit(unlock)
                            .padding(12)
                            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                        if let error {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(.red)
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

                    if model.isBiometricUnlockEnabled {
                        Button {
                            unlockWithBiometrics()
                        } label: {
                            Label("Unlock with \(Biometrics.available.title)", systemImage: Biometrics.available.systemImage)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                    }

                    if let hint = model.activeMeta?.masterPasswordHint, !hint.isEmpty {
                        Button("Show master password hint") { showsHint = true }
                            .font(.footnote)
                            .alert("Master password hint", isPresented: $showsHint) {
                                Button("OK", role: .cancel) {}
                            } message: {
                                Text(hint)
                            }
                    }
                }
                .padding(24)
            }
            .toolbar {
                if model.vaults.count > 1 {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            ForEach(model.vaults, id: \.userId) { meta in
                                Button {
                                    model.switchVault(to: meta.userId)
                                    password = ""
                                    error = nil
                                    didTryBiometrics = false
                                } label: {
                                    if meta.userId == model.activeMeta?.userId {
                                        Label(meta.displayName, systemImage: "checkmark")
                                    } else {
                                        Text(meta.displayName)
                                    }
                                }
                            }
                        } label: {
                            Label("Switch vault", systemImage: "person.2.circle")
                        }
                    }
                }
            }
        }
        .task(id: model.activeMeta?.userId) {
            if model.isBiometricUnlockEnabled, !didTryBiometrics {
                didTryBiometrics = true
                unlockWithBiometrics()
            } else {
                isPasswordFocused = true
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
                password = ""
            } catch {
                self.error = error.displayMessage
                isPasswordFocused = true
            }
        }
    }

    private func unlockWithBiometrics() {
        Task {
            do {
                try await model.unlockWithBiometrics()
            } catch Keychain.KeychainError.cancelled {
                isPasswordFocused = true
            } catch {
                self.error = String(localized: "\(Biometrics.available.title) is not available. Unlock with your master password; you can turn it on again in Settings.")
                model.refreshBiometricState()
                isPasswordFocused = true
            }
        }
    }
}
