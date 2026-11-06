import SwiftUI

struct ItemEditView: View {
    @Environment(VaultSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var cipher: CipherView
    let isNew: Bool
    @State private var error: String?
    @State private var showsGenerator = false
    @State private var showsDiscardConfirmation = false
    private let original: CipherView

    init(cipher: CipherView, isNew: Bool) {
        _cipher = State(initialValue: cipher)
        original = cipher
        self.isNew = isNew
    }

    var body: some View {
        Form {
            Section {
                TextField("Name", text: $cipher.name)
                Picker("Folder", selection: $cipher.folderId) {
                    Text("No folder").tag(String?.none)
                    ForEach(session.folders) { folder in
                        Text(folder.name).tag(Optional(folder.id))
                    }
                }
                Toggle("Favorite", isOn: $cipher.isFavorite)
                Toggle("Master password re-prompt", isOn: $cipher.reprompt)
            }

            switch cipher.type {
            case .login: loginSections
            case .secureNote: EmptyView()
            default: sectionFields
            }

            Section("Notes") {
                TextField("Notes", text: $cipher.notes, axis: .vertical)
                    .lineLimit(3...12)
            }

            customFieldsSection
        }
        .navigationTitle(isNew ? Text("New \(cipher.type.title.lowercased())") : Text("Edit item"))
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled(cipher != original)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") {
                    if cipher != original { showsDiscardConfirmation = true } else { dismiss() }
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save", action: save)
                    .disabled(cipher.name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .confirmationDialog("Discard your changes?", isPresented: $showsDiscardConfirmation, titleVisibility: .visible) {
            Button("Discard changes", role: .destructive) { dismiss() }
        }
        .sheet(isPresented: $showsGenerator) {
            NavigationStack {
                GeneratorView { generated in
                    cipher.login.password = generated
                    showsGenerator = false
                }
            }
        }
        .errorAlert($error)
    }

    @ViewBuilder private var loginSections: some View {
        Section("Login") {
            TextField("Username", text: $cipher.login.username)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            HStack {
                PasswordField("Password", text: $cipher.login.password, isNewPassword: true)
                Button {
                    if cipher.login.password.isEmpty {
                        cipher.login.password = PasswordGenerator.password(GeneratorSettings.load().password)
                    } else {
                        showsGenerator = true
                    }
                } label: {
                    Image(systemName: "wand.and.stars")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(Text("Generate password"))
            }
            TextField("Authenticator key (TOTP)", text: $cipher.login.totp)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.body.monospaced())
            if !cipher.login.totp.isEmpty, TotpConfig(cipher.login.totp) == nil {
                Text("This is not a valid authenticator key.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        Section {
            ForEach($cipher.login.uris) { $uri in
                HStack {
                    TextField("Website (URI)", text: $uri.uri)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Menu {
                        Picker("Match detection", selection: $uri.match) {
                            Text("Default").tag(UriMatchType?.none)
                            ForEach(UriMatchType.allCases) { match in
                                Text(match.title).tag(Optional(match))
                            }
                        }
                    } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                    .accessibilityLabel(Text("Match detection"))
                }
            }
            .onDelete { cipher.login.uris.remove(atOffsets: $0) }
            Button {
                cipher.login.uris.append(LoginUriView(uri: ""))
            } label: {
                Label("Add website", systemImage: "plus")
            }
        } header: {
            Text("Websites")
        }
    }

    @ViewBuilder private var sectionFields: some View {
        Section(cipher.type.title) {
            ForEach(cipher.type.sectionFields) { field in
                let binding = Binding(
                    get: { cipher.section[field.key] ?? "" },
                    set: { cipher.section[field.key] = $0 }
                )
                switch field.kind {
                case .secret where cipher.type == .sshKey, .multiline:
                    VStack(alignment: .leading) {
                        Text(field.label).font(.caption).foregroundStyle(.secondary)
                        TextField(field.label, text: binding, axis: .vertical)
                            .font(.footnote.monospaced())
                            .lineLimit(2...8)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                case .secret:
                    PasswordField(LocalizedStringKey(field.label), text: binding)
                case .number:
                    TextField(field.label, text: binding).keyboardType(.numberPad)
                case .email:
                    TextField(field.label, text: binding)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                case .phone:
                    TextField(field.label, text: binding).keyboardType(.phonePad)
                case .text:
                    TextField(field.label, text: binding)
                }
            }
        }
    }

    @ViewBuilder private var customFieldsSection: some View {
        Section("Custom fields") {
            ForEach($cipher.fields) { $field in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        TextField("Field name", text: $field.name)
                            .font(.subheadline.bold())
                        Picker("Type", selection: $field.type) {
                            ForEach(FieldType.allCases.filter { $0 != .linked || field.type == .linked }) { type in
                                Text(type.title).tag(type)
                            }
                        }
                        .labelsHidden()
                    }
                    switch field.type {
                    case .boolean:
                        Toggle("Value", isOn: Binding(get: { field.value == "true" }, set: { field.value = $0 ? "true" : "false" }))
                    case .hidden:
                        PasswordField("Value", text: $field.value)
                    case .linked:
                        Text("Linked to another field of the item (kept as is).")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    case .text:
                        TextField("Value", text: $field.value)
                    }
                }
            }
            .onDelete { cipher.fields.remove(atOffsets: $0) }
            Menu {
                ForEach(FieldType.allCases.filter { $0 != .linked }) { type in
                    Button(type.title) { cipher.fields.append(FieldView(type: type)) }
                }
            } label: {
                Label("Add field", systemImage: "plus")
            }
        }
    }

    private func save() {
        var cipher = cipher
        cipher.name = cipher.name.trimmingCharacters(in: .whitespaces)
        cipher.login.uris.removeAll { $0.uri.trimmingCharacters(in: .whitespaces).isEmpty }
        do {
            try session.save(cipher)
            dismiss()
        } catch {
            self.error = error.displayMessage
        }
    }
}
