import SwiftUI

struct SettingsView: View {
    @Environment(ServerSync.self) private var server
    @Environment(RecipeStore.self) private var store
    @State private var confirmSamples = false

    var body: some View {
        List {
            Section {
                NavigationLink { ServerSettingsView(selfHosted: server.isConnected && !server.isManaged) } label: {
                    settingsRow(server.isConnected && !server.isManaged ? "Self-hosting" : "Account",
                                subtitle: server.isConnected ? server.username : "Sign in to Pinch", icon: "person.crop.circle")
                }

            }
            Section {
                Button { confirmSamples = true } label: {
                    Label("Add sample recipes", systemImage: "book.closed").foregroundStyle(Theme.jadeDeep)
                }
            } footer: { Text("\(store.recipes.count) recipes on this device") }
            Section {
                NavigationLink("About & privacy") {
                    List {
                        Section {
                            Text("Your recipes live on this device and work offline. Connecting a server backs up your library to that server. Its host can access your backup. People you invite see only recipes in shared cookbooks.")
                            Text("Pinch sends recipe imports, ingredients and directions to OpenRouter when it reads recipes or matches ingredients to cooking steps.")
                        }
                        LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")
                    }
                    .navigationTitle("About & privacy").navigationBarTitleDisplayMode(.inline)
                }
            }
        }
        .listStyle(.insetGrouped).scrollContentBackground(.hidden)
        .background(Theme.canvas.ignoresSafeArea())
        .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
        .confirmationDialog("Add three sample recipes?", isPresented: $confirmSamples, titleVisibility: .visible) {
            Button("Add recipes") { SampleRecipes.all.forEach(store.save); Haptics.success() }
        }
    }

    private func settingsRow(_ title: String, subtitle: String, icon: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).foregroundStyle(Theme.jadeDeep).frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.rounded(.body, .semibold)).foregroundStyle(Theme.ink)
                Text(subtitle).font(.rounded(.footnote)).foregroundStyle(Theme.inkSoft)
            }.padding(.vertical, 5)
        }
    }
}

struct ReadingSettingsView: View {
    @AppStorage(AppSettings.smartParsingKey) private var smartParsing = true
    @AppStorage(AIConfig.cookModeKey) private var matchCookModeIngredients = false
    @AppStorage(AIConfig.modelKey) private var model = AIConfig.model

    var body: some View {
        List {
            Section {
                AIReadingCard(model: model).listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
            }
            if AIAccount.shared.isConnected {
                Section {
                    TextField("OpenRouter model ID", text: $model).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Toggle("Match Cook Mode ingredients with AI", isOn: $matchCookModeIngredients).tint(Theme.jadeDeep)
                } footer: { Text(AIConfig.cookModeDisclosure) }
            }
            if SmartParsing.isAvailable {
                Section {
                    Toggle("Tidy with Apple Intelligence", isOn: $smartParsing).tint(Theme.jadeDeep)
                } footer: { Text("Uses the on-device model when AI reading is unavailable.") }
            }
        }
        .scrollContentBackground(.hidden).background(Theme.canvas)
        .navigationTitle("Reading recipes").navigationBarTitleDisplayMode(.inline)
    }
}

/// Where the person adds their own OpenRouter key so the AI can read their recipes.
private struct AIReadingCard: View {
    let model: String
    private let account = AIAccount.shared
    @State private var keyText = ""
    @State private var checking = false
    @State private var message: String?
    @State private var messageIsError = false
    @State private var info: OpenRouterClient.KeyInfo?
    @State private var confirmRemove = false
    @State private var askConsent = false
    @FocusState private var keyFocused: Bool

    var body: some View {
        let selectedModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        let modelName = selectedModel.isEmpty || selectedModel == AIConfig.model ? AIConfig.modelDisplayName : selectedModel
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
                         ? "Photos, pasted text and web pages are read by \(modelName) through OpenRouter."
                         : "Add your own OpenRouter key to enable AI recipe reading.")
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
                    .onSubmit(requestSave)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.sunken))
                HStack(spacing: 10) {
                    PasteButton(payloadType: String.self) { strings in
                        if let first = strings.first { keyText = AIAccount.cleaned(first) }
                    }
                    .labelStyle(.iconOnly)
                    .buttonBorderShape(.capsule)
                    .tint(Theme.jadeDeep)
                    Button(action: requestSave) {
                        HStack {
                            if checking { ProgressView().tint(Theme.onJade) }
                            Text(checking ? "Checking…" : "Turn on AI reading")
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
        .alert("Send recipes to OpenRouter?", isPresented: $askConsent) {
            Button("Cancel", role: .cancel) {}
            Button("Turn on") { save() }
        } message: {
            Text(AIConfig.consentText)
        }
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

    /// Nothing is shared with the AI until the person agrees to it.
    private func requestSave() {
        guard !AIAccount.cleaned(keyText).isEmpty, !checking else { return }
        keyFocused = false
        askConsent = true
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
