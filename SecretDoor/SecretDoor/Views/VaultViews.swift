import SwiftUI

/// What an item list shows.
enum ItemFilter: Hashable {
    case type(CipherType)
    case folder(String?)
    case favorites
    case verificationCodes
    case trash

    var title: String {
        switch self {
        case let .type(type): return type.title
        case .folder(nil): return String(localized: "No folder")
        case .folder: return String(localized: "Folder")
        case .favorites: return String(localized: "Favorites")
        case .verificationCodes: return String(localized: "Verification codes")
        case .trash: return String(localized: "Trash")
        }
    }

    func matches(_ cipher: CipherView) -> Bool {
        switch self {
        case .trash: return cipher.isDeleted
        case let .type(type): return !cipher.isDeleted && cipher.type == type
        case let .folder(id): return !cipher.isDeleted && cipher.folderId == id
        case .favorites: return !cipher.isDeleted && cipher.isFavorite
        case .verificationCodes: return !cipher.isDeleted && cipher.type == .login && !cipher.login.totp.isEmpty
        }
    }
}

enum VaultRoute: Hashable {
    case item(String)
    case list(ItemFilter)
}

struct VaultHomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(VaultSession.self) private var session
    @State private var search = ""
    @State private var newItemType: CipherType?
    @State private var path: [VaultRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if search.isEmpty {
                    homeSections
                } else {
                    let results = session.activeCiphers.filter { $0.searchText.contains(search.lowercased()) }
                    if results.isEmpty {
                        ContentUnavailableView.search(text: search)
                    }
                    ForEach(results) { cipher in
                        NavigationLink(value: VaultRoute.item(cipher.id)) { ItemRow(cipher: cipher) }
                    }
                }
            }
            .overlay {
                if session.ciphers.isEmpty, search.isEmpty {
                    ContentUnavailableView {
                        VStack(spacing: 12) {
                            Image("ArtVaultItems")
                                .resizable()
                                .scaledToFit()
                                .frame(width: 128, height: 128)
                            Text("No items yet")
                        }
                    } description: {
                        Text("Add your first login, card or note with the + button.")
                    }
                }
            }
            .searchable(text: $search, prompt: "Search vault")
            .navigationTitle(model.vaults.count > 1 ? session.meta.displayName : String(localized: "My vault"))
            .navigationDestination(for: VaultRoute.self) { route in
                switch route {
                case let .item(id): ItemDetailView(cipherId: id)
                case let .list(filter): ItemListView(filter: filter)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        model.lock()
                    } label: {
                        Label("Lock", systemImage: "lock")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ForEach(CipherType.allCases) { type in
                            Button {
                                newItemType = type
                            } label: {
                                Label(type.title, systemImage: type.systemImage)
                            }
                        }
                    } label: {
                        Label("Add item", systemImage: "plus")
                    }
                }
            }
            .sheet(item: $newItemType) { type in
                NavigationStack {
                    ItemEditView(cipher: newCipher(type: type), isNew: true)
                }
                .environment(session)
            }
        }
    }

    private func newCipher(type: CipherType) -> CipherView {
        var cipher = CipherView(type: type)
        // A new item goes into the folder being viewed.
        if case let .list(.folder(id)) = path.last { cipher.folderId = id }
        if type == .login { cipher.login.uris = [LoginUriView(uri: "")] }
        return cipher
    }

    @ViewBuilder private var homeSections: some View {
        let active = session.activeCiphers
        let favorites = active.filter(\.isFavorite)
        if !favorites.isEmpty {
            Section("Favorites") {
                ForEach(favorites) { cipher in
                    NavigationLink(value: VaultRoute.item(cipher.id)) { ItemRow(cipher: cipher) }
                }
            }
        }
        Section("Types") {
            ForEach(CipherType.allCases) { type in
                let count = active.filter { $0.type == type }.count
                if count > 0 || [.login, .card, .identity, .secureNote].contains(type) {
                    NavigationLink(value: VaultRoute.list(.type(type))) {
                        CountRow(title: type.title, systemImage: type.systemImage, count: count, type: type)
                    }
                }
            }
            let codes = active.filter { ItemFilter.verificationCodes.matches($0) }.count
            if codes > 0 {
                NavigationLink(value: VaultRoute.list(.verificationCodes)) {
                    CountRow(title: String(localized: "Verification codes"), systemImage: "clock.badge.checkmark", count: codes)
                }
            }
        }
        if !session.folders.isEmpty || active.contains(where: { $0.folderId != nil }) {
            Section("Folders") {
                ForEach(session.folders) { folder in
                    NavigationLink(value: VaultRoute.list(.folder(folder.id))) {
                        CountRow(title: folder.name, systemImage: "folder", count: active.filter { $0.folderId == folder.id }.count)
                    }
                }
                NavigationLink(value: VaultRoute.list(.folder(nil))) {
                    CountRow(title: String(localized: "No folder"), systemImage: "folder.badge.questionmark", count: active.filter { $0.folderId == nil }.count)
                }
            }
        }
        Section {
            NavigationLink(value: VaultRoute.list(.trash)) {
                CountRow(title: String(localized: "Trash"), systemImage: "trash", count: session.trash.count)
            }
        }
    }
}

private struct CountRow: View {
    let title: String
    let systemImage: String
    let count: Int
    var type: CipherType?

    var body: some View {
        HStack {
            if let type {
                Label {
                    Text(title)
                } icon: {
                    TypeIcon(type: type, size: 30)
                }
            } else {
                Label(title, systemImage: systemImage)
            }
            Spacer()
            Text("\(count)")
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }
}

struct ItemRow: View {
    let cipher: CipherView

    var body: some View {
        HStack(spacing: 12) {
            TypeIcon(type: cipher.type, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(cipher.name.isEmpty ? String(localized: "(no name)") : cipher.name)
                        .lineLimit(1)
                    if cipher.isFavorite {
                        Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow)
                    }
                    if !cipher.attachments.isEmpty {
                        Image(systemName: "paperclip").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                if !cipher.subtitle.isEmpty {
                    Text(cipher.subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
    }
}

struct ItemListView: View {
    @Environment(VaultSession.self) private var session
    let filter: ItemFilter
    @State private var search = ""
    @State private var showsEmptyTrashConfirmation = false
    @State private var error: String?

    private var title: String {
        if case let .folder(id?) = filter {
            return session.folders.first { $0.id == id }?.name ?? filter.title
        }
        return filter.title
    }

    var body: some View {
        let items = session.ciphers
            .filter(filter.matches)
            .filter { search.isEmpty || $0.searchText.contains(search.lowercased()) }
        List {
            ForEach(items) { cipher in
                NavigationLink(value: VaultRoute.item(cipher.id)) {
                    if filter == .verificationCodes {
                        TotpRow(cipher: cipher)
                    } else {
                        ItemRow(cipher: cipher)
                    }
                }
            }
        }
        .overlay {
            if items.isEmpty {
                if search.isEmpty {
                    ContentUnavailableView(filter == .trash ? "Trash is empty" : "No items", systemImage: filter == .trash ? "trash" : "tray")
                } else {
                    ContentUnavailableView.search(text: search)
                }
            }
        }
        .searchable(text: $search)
        .navigationTitle(title)
        .toolbar {
            if filter == .trash, !items.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Empty", role: .destructive) { showsEmptyTrashConfirmation = true }
                }
            }
        }
        .confirmationDialog("Permanently delete every item in the trash?", isPresented: $showsEmptyTrashConfirmation, titleVisibility: .visible) {
            Button("Empty trash", role: .destructive) {
                do { try session.emptyTrash() } catch { self.error = error.displayMessage }
            }
        }
        .errorAlert($error)
    }
}

/// A login with its current verification code.
private struct TotpRow: View {
    let cipher: CipherView

    var body: some View {
        HStack {
            ItemRow(cipher: cipher)
            Spacer()
            if let config = TotpConfig(cipher.login.totp) {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    VStack(alignment: .trailing) {
                        Text(formatTotp(config.code(at: context.date)))
                            .font(.body.monospacedDigit().bold())
                        Text("\(config.secondsRemaining(at: context.date)) s")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

/// `123456` → `123 456`.
func formatTotp(_ code: String) -> String {
    guard code.count == 6 || code.count == 8, code.allSatisfy(\.isNumber) else { return code }
    let half = code.index(code.startIndex, offsetBy: code.count / 2)
    return "\(code[..<half]) \(code[half...])"
}
