import SwiftUI

struct SettingsView: View {
    @Environment(DriveSync.self) private var drive
    @Environment(RecipeStore.self) private var store
    @AppStorage(AppSettings.smartParsingKey) private var smartParsing = true
    @State private var confirmDisconnect = false

    var body: some View {
        List {
            Section {
                AIReadingCard()
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                header("Reading recipes")
            }

            Section {
                DriveCard(confirmDisconnect: $confirmDisconnect)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: {
                header("Backup and sync")
            }

            Section {
                Toggle(isOn: $smartParsing) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Tidy with Apple Intelligence")
                            .font(.rounded(.body, .medium))
                        Text(SmartParsing.isAvailable
                             ? "Uses the on-device model to fix misread words and sort lines. Nothing leaves this iPhone."
                             : "Needs an iPhone with Apple Intelligence turned on. Pinch's built-in reader is used instead.")
                            .font(.rounded(.footnote))
                            .foregroundStyle(Theme.inkSoft)
                    }
                }
                .tint(Theme.jadeDeep)
                .disabled(!SmartParsing.isAvailable)
            } header: {
                header("Reading on the phone")
            } footer: {
                Text("Pinch reads on the phone when there's no OpenRouter key or no internet connection.")
            }

            Section {
                Button {
                    SampleRecipes.all.forEach(store.save)
                    Haptics.success()
                } label: {
                    Label("Add three sample recipes", systemImage: "sparkles")
                        .font(.rounded(.body, .medium))
                        .foregroundStyle(Theme.jadeDeep)
                }
            } header: {
                header("Recipes")
            } footer: {
                Text("\(store.recipes.count) recipe\(store.recipes.count == 1 ? "" : "s") saved on this \(UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone").")
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Label("Your recipes live on this device", systemImage: "lock.fill")
                        .font(.rounded(.headline, .bold))
                        .foregroundStyle(Theme.ink)
                    Text("Pinch saves your recipes on this device and works without an internet connection. If you add an OpenRouter key, the photos, text and web pages you add are sent to OpenRouter to be read, and nothing else. If you connect Google Drive, your recipes are backed up to a Pinch Recipes folder in your own Drive.")
                        .font(.rounded(.subheadline))
                        .foregroundStyle(Theme.inkSoft)
                }
                .padding(.vertical, 6)
                LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")
                    .font(.rounded(.body))
            } header: {
                header("About")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.canvas.ignoresSafeArea())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Disconnect Google Drive?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
            Button("Disconnect", role: .destructive) { Task { await drive.disconnect() } }
        } message: {
            Text("Your recipes stay on this iPhone and in your Drive. New changes just stop syncing.")
        }
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .font(.rounded(.subheadline, .bold))
            .foregroundStyle(Theme.inkSoft)
            .textCase(nil)
    }
}

private struct DriveCard: View {
    @Environment(DriveSync.self) private var drive
    @Binding var confirmDisconnect: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.jade.opacity(0.25))
                    Image(systemName: drive.isConnected ? "checkmark.icloud.fill" : "icloud.and.arrow.up.fill")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Theme.jadeDeep)
                }
                .frame(width: 50, height: 50)
                VStack(alignment: .leading, spacing: 3) {
                    Text(drive.isConnected ? "Google Drive is connected" : "Google Drive")
                        .font(.rounded(.headline, .bold))
                        .foregroundStyle(Theme.ink)
                    Text(statusText)
                        .font(.rounded(.subheadline))
                        .foregroundStyle(statusIsError ? Theme.cherry : Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            switch drive.state {
            case .unavailable:
                Text("This copy of Pinch was built without a Google sign-in client, so Drive backup is turned off. Everything else works as usual. (Developers: see docs/google-drive-setup.md.)")
                    .font(.rounded(.footnote))
                    .foregroundStyle(Theme.inkSoft)
            case .disconnected, .connecting:
                Button {
                    Task { await drive.connect() }
                } label: {
                    HStack {
                        if drive.state == .connecting { ProgressView().tint(Theme.onJade) }
                        Text(drive.state == .connecting ? "Connecting…" : "Connect Google Drive")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(drive.state == .connecting)
            default:
                HStack(spacing: 10) {
                    Button {
                        Task { await drive.sync() }
                    } label: {
                        HStack(spacing: 6) {
                            if drive.state == .syncing {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "arrow.triangle.2.circlepath")
                            }
                            Text(drive.state == .syncing ? "Syncing…" : "Sync now")
                        }
                    }
                    .buttonStyle(SoftButtonStyle())
                    .disabled(drive.state == .syncing)
                    Spacer()
                    Button("Disconnect") { confirmDisconnect = true }
                        .font(.rounded(.subheadline, .semibold))
                        .foregroundStyle(Theme.cherry)
                }
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Theme.surface))
    }

    private var statusIsError: Bool {
        if case .failed = drive.state { return true }
        return false
    }

    private var statusText: String {
        switch drive.state {
        case .unavailable:
            return "Not available in this build"
        case .disconnected:
            return "Back up your recipes to your own Drive and use them on your other phones and tablets, iPhone or Android."
        case .connecting:
            return "Finish signing in with Google…"
        case .syncing:
            return "Syncing with \(drive.accountEmail ?? "Google Drive")…"
        case .offline:
            return "You're offline. Changes will sync when you're back online."
        case .failed(let message):
            return message
        case .idle:
            let account = drive.accountEmail ?? "Google Drive"
            if let last = drive.lastSynced {
                return "\(account)\nLast synced \(last.formatted(.relative(presentation: .named)))"
            }
            return account
        }
    }
}

/// Where the person adds their own OpenRouter key so the AI can read their recipes.
private struct AIReadingCard: View {
    private let account = AIAccount.shared
    @State private var keyText = ""
    @State private var checking = false
    @State private var message: String?
    @State private var messageIsError = false
    @State private var info: OpenRouterClient.KeyInfo?
    @State private var confirmRemove = false
    @FocusState private var keyFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.butter.opacity(0.55))
                    Image(systemName: "sparkles")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Theme.ink)
                }
                .frame(width: 50, height: 50)
                VStack(alignment: .leading, spacing: 3) {
                    Text(account.isConnected ? "AI reading is on" : "Read recipes with AI")
                        .font(.rounded(.headline, .bold))
                        .foregroundStyle(Theme.ink)
                    Text(account.isConnected
                         ? "Photos, pasted text and web pages are read by \(AIConfig.modelDisplayName) through OpenRouter."
                         : "Add your own OpenRouter key and \(AIConfig.modelDisplayName) reads your photos and web pages. It's far more accurate than reading on the phone, and costs well under a cent a recipe.")
                        .font(.rounded(.subheadline))
                        .foregroundStyle(Theme.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            if account.isConnected {
                HStack(spacing: 10) {
                    Label(account.maskedKey, systemImage: "key.fill")
                        .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                        .foregroundStyle(Theme.ink)
                        .lineLimit(1)
                    Spacer()
                    Button("Remove key") { confirmRemove = true }
                        .font(.rounded(.subheadline, .semibold))
                        .foregroundStyle(Theme.cherry)
                }
                if let info {
                    Text(usageText(info))
                        .font(.rounded(.footnote))
                        .foregroundStyle(Theme.inkSoft)
                }
            } else {
                SecureField("Paste your OpenRouter key", text: $keyText)
                    .font(.rounded(.body))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($keyFocused)
                    .submitLabel(.done)
                    .onSubmit(save)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.sunken))
                HStack(spacing: 10) {
                    PasteButton(payloadType: String.self) { strings in
                        if let first = strings.first { keyText = AIAccount.cleaned(first) }
                    }
                    .labelStyle(.iconOnly)
                    .buttonBorderShape(.capsule)
                    .tint(Theme.jadeDeep)
                    Button(action: save) {
                        HStack {
                            if checking { ProgressView().tint(Theme.onJade) }
                            Text(checking ? "Checking…" : "Save key")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(AIAccount.cleaned(keyText).isEmpty || checking)
                    .opacity(AIAccount.cleaned(keyText).isEmpty ? 0.5 : 1)
                }
                Link(destination: AIConfig.keysPage) {
                    Label("Get a key at openrouter.ai", systemImage: "arrow.up.right.square")
                        .font(.rounded(.subheadline, .semibold))
                        .foregroundStyle(Theme.jadeDeep)
                }
            }

            if let message {
                Text(message)
                    .font(.rounded(.footnote, .semibold))
                    .foregroundStyle(messageIsError ? Theme.cherry : Theme.jadeDeep)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Theme.surface))
        .task(id: account.apiKey) { await refreshInfo() }
        .confirmationDialog("Remove your OpenRouter key?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove key", role: .destructive) {
                account.remove()
                info = nil
                message = nil
            }
        } message: {
            Text("New recipes will be read on this iPhone instead. You can add the key again any time.")
        }
    }

    private func usageText(_ info: OpenRouterClient.KeyInfo) -> String {
        var text = "Spent with this key so far: \(Self.dollars(info.usage))"
        if let remaining = info.limitRemaining { text += " · \(Self.dollars(remaining)) left on its limit" }
        return text
    }

    private static func dollars(_ amount: Double) -> String {
        amount.formatted(.currency(code: "USD").precision(.fractionLength(amount < 1 ? 3 : 2)))
    }

    private func save() {
        let key = AIAccount.cleaned(keyText)
        guard !key.isEmpty, !checking else { return }
        keyFocused = false
        checking = true
        message = nil
        Task {
            defer { checking = false }
            do {
                let checked = try await OpenRouterClient.checkKey(key)
                account.save(key)
                keyText = ""
                info = checked
                message = "Key saved. New recipes will be read with AI."
                messageIsError = false
                Haptics.success()
            } catch OpenRouterClient.Failure.invalidKey {
                message = "OpenRouter didn't recognize that key. Copy it again from openrouter.ai/keys."
                messageIsError = true
            } catch {
                // Offline or OpenRouter hiccup: keep the key and check it later.
                account.save(key)
                keyText = ""
                message = "Key saved. Pinch couldn't check it with OpenRouter just now, and will try when you next add a recipe."
                messageIsError = false
            }
        }
    }

    private func refreshInfo() async {
        guard let key = account.apiKey else { return }
        do {
            info = try await OpenRouterClient.checkKey(key)
        } catch OpenRouterClient.Failure.invalidKey {
            message = "OpenRouter no longer accepts this key. Remove it and add a new one."
            messageIsError = true
        } catch {
            // Offline: nothing to show.
        }
    }
}
