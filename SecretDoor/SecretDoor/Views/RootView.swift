import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            switch model.phase {
            case .setup:
                SetupView()
            case .locked:
                UnlockView()
            case .unlocked:
                if let session = model.session {
                    MainTabView()
                        .environment(session)
                }
            }
            if model.isObscured {
                PrivacyCover()
                    .transition(.opacity)
            }
        }
        .animation(.default, value: model.phase)
    }
}

/// Hides the vault in the app switcher.
private struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 12) {
                Image("ArtLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 200, height: 200)
                    .accessibilityHidden(true)
                Text("SecretDoor")
                    .font(.title2.bold())
            }
        }
    }
}

struct MainTabView: View {
    var body: some View {
        TabView {
            VaultHomeView()
                .tabItem { Label("Vault", systemImage: "lock.rectangle.stack") }
            NavigationStack { GeneratorView() }
                .tabItem { Label("Generator", systemImage: "wand.and.stars") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
