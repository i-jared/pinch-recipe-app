import SwiftUI
import AuthenticationServices

struct ServerSettingsView: View {
    var selfHosted = false
    @Environment(ServerSync.self) private var server
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var registrationKey = ""
    @State private var register = false
    @State private var busy = false
    @State private var error: String?
    @State private var disconnect = false
    @State private var deleteAccount = false
    @State private var deletionPassword = ""

    var body: some View {
        Form {
            if server.isConnected {
                Section(selfHosted ? "Your server" : "Pinch account") {
                    if selfHosted { LabeledContent("Address", value: server.server) }
                    LabeledContent("Account", value: server.username)
                    if let date = server.lastSynced {
                        LabeledContent("Last backup", value: date.formatted(.relative(presentation: .named)))
                    }
                    if selfHosted { Button {
                        Task { await server.syncIfConnected() }
                    } label: {
                        HStack {
                            Text(server.isSyncing ? "Backing up…" : "Back up now")
                            if server.isSyncing { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(server.isSyncing) }
                }
                if let message = server.message {
                    Section { Text(message).foregroundStyle(Theme.cherry) }
                }
                Section {
                    Button(selfHosted ? "Disconnect" : "Sign out", role: .destructive) { disconnect = true }.disabled(busy)
                    Button("Delete account", role: .destructive) { deleteAccount = true }.disabled(busy)
                    if let error { Text(error).foregroundStyle(Theme.cherry) }
                } footer: {
                    Text("Your library syncs automatically.")
                }
            } else {
                Section {
                    if selfHosted {
                        TextField("https://recipes.example.com", text: $address)
                            .keyboardType(.URL).textContentType(.URL)
                    }
                    TextField("Username", text: $username).textContentType(.username)
                    SecureField("Password", text: $password)
                        .textContentType(register ? .newPassword : .password)
                    if selfHosted { Toggle("Create an account", isOn: $register) }
                    if selfHosted && register {
                        SecureField("Registration key from your host", text: $registrationKey)
                    }
                } header: { Text(selfHosted ? "Connect to your server" : register ? "Create a Pinch account" : "Sign in to Pinch") } footer: {
                    if register || selfHosted { Text("Passwords must contain 10–256 characters.") }
                    if selfHosted {
                        Text("Connecting merges this device’s library with your private server backup.")
                    } else {
                        Text("Signing in syncs this device’s library to Pinch. Recipe imports and Cook Mode use OpenRouter to read recipes and match ingredients to steps.")
                    }
                }.disabled(busy)
                Section {
                    Button {
                        busy = true; error = nil
                        Task {
                            defer { busy = false }
                            do {
                                if selfHosted {
                                    try await server.connect(address: address, username: username, password: password, registrationKey: registrationKey, register: register)
                                } else {
                                    try await server.connectManaged(username: username, password: password, register: register)
                                }
                                password = ""; registrationKey = ""
                            } catch { self.error = error.localizedDescription }
                        }
                    } label: {
                        HStack {
                            Text(busy ? "Connecting…" : register ? "Create account" : selfHosted ? "Connect & back up" : "Sign in")
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy)
                    if !selfHosted {
                        if !register { Button("Sign in in browser", action: signInInBrowser).disabled(busy) }
                        Button(register ? "I already have an account" : "Create an account") { register.toggle(); error = nil }
                            .disabled(busy)
                    }
                    if let error { Text(error).foregroundStyle(Theme.cherry) }
                } footer: {
                    if selfHosted { Text("Your server host can access what you back up. Only recipes in shared cookbooks are visible to people you invite.") }
                }
                if !selfHosted {
                    Section {
                        NavigationLink("Use your own server") { ServerSettingsView(selfHosted: true) }.disabled(busy)
                    }
                }
            }
            if selfHosted && !server.isManaged {
                Section { NavigationLink("Reading recipes") { ReadingSettingsView() } }
            }
        }
        .textInputAutocapitalization(.never).autocorrectionDisabled()
        .scrollContentBackground(.hidden).background(Theme.canvas)
        .navigationTitle(selfHosted ? "Self-hosting" : "Account").navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .onAppear { if selfHosted { address = server.server }; username = server.username }
        .alert("Delete server account?", isPresented: $deleteAccount) {
            SecureField("Confirm your password", text: $deletionPassword)
            Button("Cancel", role: .cancel) { deletionPassword = "" }
            Button("Delete account", role: .destructive) {
                busy = true; error = nil
                Task {
                    defer { busy = false; deletionPassword = "" }
                    do { try await server.deleteAccount(password: deletionPassword) }
                    catch { self.error = error.localizedDescription }
                }
            }
        } message: {
            Text("This permanently removes your server backup, cookbooks you own. Recipes saved on devices stay. Enter your password to confirm.")
        }
        .confirmationDialog(selfHosted ? "Disconnect from your server?" : "Sign out of Pinch?", isPresented: $disconnect, titleVisibility: .visible) {
            Button(selfHosted ? "Disconnect" : "Sign out", role: .destructive) { Task { await server.disconnect() } }
        } message: { Text("Your local recipes and server backup will be kept.") }
    }

    private func signInInBrowser() {
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let request = ManagedAuthorization()
                let callback = try await webAuthenticationSession.authenticate(using: request.url, callbackURLScheme: ManagedAuthorization.scheme)
                try await server.completeManagedAuthorization(request, callback: callback)
            } catch let failure as ASWebAuthenticationSessionError where failure.code == .canceledLogin { }
            catch { self.error = error.localizedDescription }
        }
    }
}
