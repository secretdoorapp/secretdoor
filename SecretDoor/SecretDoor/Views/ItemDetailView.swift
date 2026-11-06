import QuickLook
import SwiftUI
import UniformTypeIdentifiers

struct ItemDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(VaultSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let cipherId: String

    @State private var isEditing = false
    @State private var isRepromptPassed = false
    @State private var error: String?
    @State private var showsDeleteConfirmation = false
    @State private var isPickingAttachment = false
    @State private var previewURL: URL?

    var body: some View {
        if let cipher = session.cipher(id: cipherId) {
            if cipher.reprompt && !isRepromptPassed {
                RepromptView { isRepromptPassed = true }
            } else {
                content(cipher)
            }
        } else {
            ContentUnavailableView("Item not found", systemImage: "questionmark.folder")
        }
    }

    private func content(_ cipher: CipherView) -> some View {
        List {
            Section {
                HStack(spacing: 12) {
                    TypeIcon(type: cipher.type, size: 44)
                    VStack(alignment: .leading) {
                        Text(cipher.name).font(.headline)
                        Text(cipher.type.title).font(.subheadline).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if !cipher.isDeleted {
                        Button {
                            var updated = cipher
                            updated.isFavorite.toggle()
                            save(updated)
                        } label: {
                            Image(systemName: cipher.isFavorite ? "star.fill" : "star")
                                .foregroundStyle(cipher.isFavorite ? .yellow : .secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(cipher.isFavorite ? Text("Remove from favorites") : Text("Add to favorites"))
                    }
                }
                if cipher.hasDecryptionErrors {
                    Label("Parts of this item could not be decrypted. Editing is disabled to protect the original.", systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            }

            if cipher.type == .login { loginSections(cipher) }

            let sectionFields = cipher.type.sectionFields.filter { !(cipher.section[$0.key] ?? "").isEmpty }
            if !sectionFields.isEmpty {
                Section(cipher.type.title) {
                    ForEach(sectionFields) { field in
                        FieldRow(
                            label: field.label,
                            value: cipher.section[field.key] ?? "",
                            isSecret: field.kind == .secret,
                            isMonospaced: cipher.type == .sshKey,
                            isMultiline: cipher.type == .sshKey
                        )
                    }
                }
            }

            if !cipher.notes.isEmpty {
                Section("Notes") {
                    FieldRow(label: String(localized: "Notes"), value: cipher.notes, isMultiline: true)
                }
            }

            if !cipher.fields.isEmpty {
                Section("Custom fields") {
                    ForEach(cipher.fields) { field in
                        switch field.type {
                        case .boolean:
                            LabeledContent(field.name) {
                                Image(systemName: field.value == "true" ? "checkmark.square.fill" : "square")
                            }
                        case .linked:
                            LabeledContent(field.name, value: String(localized: "Linked field"))
                        default:
                            FieldRow(label: field.name, value: field.value, isSecret: field.type == .hidden)
                        }
                    }
                }
            }

            attachmentsSection(cipher)

            Section {
                if let folder = session.folders.first(where: { $0.id == cipher.folderId }) {
                    LabeledContent("Folder", value: folder.name)
                }
                LabeledContent("Created", value: cipher.creationDate.formatted(date: .abbreviated, time: .shortened))
                LabeledContent("Updated", value: cipher.revisionDate.formatted(date: .abbreviated, time: .shortened))
                if let date = cipher.login.passwordRevisionDate {
                    LabeledContent("Password updated", value: date.formatted(date: .abbreviated, time: .shortened))
                }
                if !cipher.passwordHistory.isEmpty {
                    NavigationLink {
                        PasswordHistoryList(history: cipher.passwordHistory)
                    } label: {
                        LabeledContent("Password history", value: "\(cipher.passwordHistory.count)")
                    }
                }
            }
            .font(.subheadline)

            Section {
                if cipher.isDeleted {
                    Button("Restore") {
                        perform { try session.restoreFromTrash(cipher) }
                    }
                    Button("Delete permanently", role: .destructive) { showsDeleteConfirmation = true }
                } else {
                    Button("Move to trash", role: .destructive) {
                        perform { try session.moveToTrash(cipher) }
                        dismiss()
                    }
                }
            }
        }
        .navigationTitle(cipher.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !cipher.isDeleted {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Edit") { isEditing = true }
                        .disabled(cipher.hasDecryptionErrors)
                }
            }
        }
        .sheet(isPresented: $isEditing) {
            NavigationStack { ItemEditView(cipher: cipher, isNew: false) }
                .environment(session)
        }
        .confirmationDialog("Delete this item permanently? This cannot be undone.", isPresented: $showsDeleteConfirmation, titleVisibility: .visible) {
            Button("Delete permanently", role: .destructive) {
                perform { try session.deletePermanently(cipher) }
                dismiss()
            }
        }
        .fileImporter(isPresented: $isPickingAttachment, allowedContentTypes: [.item]) { result in
            perform {
                let url = try result.get()
                let data = try readPickedFile(url)
                guard data.count <= 100 * 1024 * 1024 else {
                    throw CocoaError(.fileReadTooLarge)
                }
                try session.addAttachment(to: cipher, fileName: url.lastPathComponent, content: data)
            }
        }
        .quickLookPreview($previewURL)
        .onChange(of: previewURL) { old, new in
            // Remove the decrypted copy when the preview closes.
            if new == nil, let old { try? FileManager.default.removeItem(at: old.deletingLastPathComponent()) }
        }
        .errorAlert($error)
    }

    @ViewBuilder private func loginSections(_ cipher: CipherView) -> some View {
        let login = cipher.login
        if !login.username.isEmpty || !login.password.isEmpty || !login.totp.isEmpty {
            Section("Login") {
                if !login.username.isEmpty {
                    FieldRow(label: String(localized: "Username"), value: login.username)
                }
                if !login.password.isEmpty {
                    FieldRow(label: String(localized: "Password"), value: login.password, isSecret: true)
                }
                if !login.totp.isEmpty {
                    TotpField(key: login.totp)
                }
            }
        }
        if !login.uris.isEmpty {
            Section("Websites") {
                ForEach(login.uris) { uri in
                    HStack {
                        FieldRow(label: String(localized: "Website"), value: uri.uri)
                        if let url = launchURL(uri.uri) {
                            Button {
                                openURL(url)
                            } label: {
                                Image(systemName: "arrow.up.forward.square")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(Text("Open website"))
                        }
                    }
                }
            }
        }
        if login.passkeyCount > 0 {
            Section {
                Label("This login has a passkey. Passkeys made on Android are kept, but cannot be used on iPhone yet.", systemImage: "person.badge.key")
                    .font(.footnote)
            }
        }
    }

    @ViewBuilder private func attachmentsSection(_ cipher: CipherView) -> some View {
        Section("Attachments") {
            ForEach(cipher.attachments) { attachment in
                Button {
                    perform { previewURL = try session.decryptAttachment(attachment, of: cipher) }
                } label: {
                    HStack {
                        Label(attachment.fileName, systemImage: "doc")
                        Spacer()
                        if let size = attachment.sizeName {
                            Text(size).foregroundStyle(.secondary).font(.caption)
                        }
                    }
                }
                .swipeActions {
                    if !cipher.isDeleted {
                        Button("Delete", role: .destructive) {
                            perform { try session.deleteAttachment(attachment, of: cipher) }
                        }
                    }
                }
            }
            if !cipher.isDeleted && !cipher.hasDecryptionErrors {
                Button {
                    isPickingAttachment = true
                } label: {
                    Label("Add attachment", systemImage: "paperclip")
                }
            }
        }
    }

    private func launchURL(_ value: String) -> URL? {
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("androidapp://") else { return nil }
        let withScheme = trimmed.contains("://") ? trimmed : "https://" + trimmed
        guard let url = URL(string: withScheme), let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme) else {
            return nil
        }
        return url
    }

    private func save(_ cipher: CipherView) {
        perform { try session.save(cipher) }
    }

    private func perform(_ action: () throws -> Void) {
        do { try action() } catch { self.error = error.displayMessage }
    }
}

/// The live verification code of a login.
struct TotpField: View {
    @Environment(AppModel.self) private var model
    let key: String

    var body: some View {
        if let config = TotpConfig(key) {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let code = config.code(at: context.date)
                let remaining = config.secondsRemaining(at: context.date)
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Verification code (TOTP)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(formatTotp(code))
                            .font(.title3.monospacedDigit().bold())
                    }
                    Spacer()
                    ZStack {
                        Circle().stroke(Color(.systemFill), lineWidth: 3)
                        Circle()
                            .trim(from: 0, to: CGFloat(remaining) / CGFloat(config.period))
                            .stroke(remaining <= 5 ? Color.red : Color.accentColor, lineWidth: 3)
                            .rotationEffect(.degrees(-90))
                        Text("\(remaining)").font(.caption2.monospacedDigit())
                    }
                    .frame(width: 30, height: 30)
                    Button {
                        model.copy(code)
                    } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(Text("Copy verification code"))
                }
            }
        } else {
            LabeledContent("Verification code (TOTP)", value: String(localized: "Invalid key"))
        }
    }
}

struct PasswordHistoryList: View {
    let history: [PasswordHistoryView]

    var body: some View {
        List(history, id: \.self) { entry in
            FieldRow(
                label: entry.lastUsedDate.formatted(date: .abbreviated, time: .shortened),
                value: entry.password,
                isSecret: true
            )
        }
        .navigationTitle("Password history")
    }
}

/// Asks for the master password before showing an item marked "Master password re-prompt".
struct RepromptView: View {
    @Environment(AppModel.self) private var model
    let onSuccess: () -> Void
    @State private var password = ""
    @State private var isWrong = false
    @State private var isWorking = false

    var body: some View {
        Form {
            Section {
                PasswordField("Master password", text: $password)
                    .onSubmit(check)
            } header: {
                Text("Master password re-prompt")
            } footer: {
                if isWrong {
                    Text("Invalid master password. Try again.").foregroundStyle(.red)
                } else {
                    Text("This item is protected. Enter your master password to view it.")
                }
            }
            Button("Continue", action: check)
                .disabled(password.isEmpty || isWorking)
        }
    }

    private func check() {
        isWorking = true
        Task {
            defer { isWorking = false }
            if await model.verifyMasterPassword(password) {
                onSuccess()
            } else {
                isWrong = true
            }
        }
    }
}
