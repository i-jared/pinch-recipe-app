import SwiftUI

struct AddToCookbooksSheet: View {
    let recipe: Recipe
    var onAdded: (String) -> Void = { _ in }
    @Environment(ServerSync.self) private var server
    @Environment(\.dismiss) private var dismiss
    @State private var work = GroupWork()
    @State private var selected = Set<String>()
    @State private var creating = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) { Text(recipe.emoji).font(.largeTitle); Text(recipe.displayTitle).font(.rounded(.title3, .bold)) }.padding(.vertical, 8)
                }.listRowBackground(Color.clear)
                Section {
                    ForEach(server.allCookbooks.filter(\.canEdit)) { book in
                        let already = (book.mySharedRecipeIds ?? []).contains(recipe.id)
                        Button {
                            if selected.contains(book.id) { selected.remove(book.id) } else { selected.insert(book.id) }
                        } label: {
                            HStack(spacing: 14) {
                                Image(systemName: "books.vertical.fill").foregroundStyle(Theme.jadeDeep)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(book.name).font(.rounded(.headline)).foregroundStyle(Theme.ink)
                                    Text(already ? "Already in this cookbook" : book.isOwner ? ((book.memberCount ?? 1) > 1 ? "Shared by you" : "Private") : "Adds to \(book.ownerName)’s library")
                                        .font(.rounded(.caption)).foregroundStyle(Theme.inkSoft)
                                }
                                Spacer()
                                Image(systemName: already || selected.contains(book.id) ? "checkmark.circle.fill" : "circle").foregroundStyle(Theme.jadeDeep)
                            }.padding(.vertical, 6)
                        }.disabled(work.busy || already)
                    }
                    Button { creating = true } label: { Label("New cookbook", systemImage: "plus") }
                } footer: { Text("A recipe can belong to more than one cookbook.") }
                GroupFeedback(work: work)
            }.scrollContentBackground(.hidden).background(Theme.canvas).navigationTitle("Add to cookbook").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() }.disabled(work.busy) } }
                .safeAreaInset(edge: .bottom) {
                    Button { work.run {
                        var names: [String] = []
                        for book in server.allCookbooks where selected.contains(book.id) {
                            try await server.share(recipe, with: book); names.append(book.name); selected.remove(book.id)
                        }
                        onAdded("Added to " + names.joined(separator: ", ")); dismiss()
                    } } label: { Text(selected.isEmpty ? "Choose a cookbook" : "Add to cookbook\(selected.count > 1 ? "s" : "")").frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle()).disabled(work.busy || selected.isEmpty).padding(20).background(Theme.canvas)
                }
                .sheet(isPresented: $creating) { CreateGroupSheet { selected.insert($0.id) } }
        }.tint(Theme.jadeDeep).interactiveDismissDisabled(work.busy)
    }
}

struct InviteGroupSheet: View {
    let group: ServerGroup
    @Environment(ServerSync.self) private var server
    @Environment(\.dismiss) private var dismiss
    @State private var role = "viewer"
    @State private var link: URL?
    @State private var work = GroupWork()
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 22) {
                GroupCover(group: group).clipShape(RoundedRectangle(cornerRadius: 24))
                Text("Share this cookbook").font(.rounded(.title, .bold))
                Text("Send a link to \(group.name). They can join with an existing account or create one from the invitation.").font(.rounded(.body)).foregroundStyle(Theme.inkSoft)
                Picker("Access", selection: $role) { Text("Viewer").tag("viewer"); Text("Editor").tag("editor") }.pickerStyle(.segmented).disabled(work.busy)
                Text(role == "editor" ? "Editors can add and remove recipes. Their additions also appear in your library." : "Viewers can read, cook and save their own copies.").font(.rounded(.subheadline)).foregroundStyle(Theme.inkSoft)
                if let link { ShareLink(item: link) { Label("Share invitation", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }.buttonStyle(PrimaryButtonStyle()) }
                else { Button("Create invitation") { work.run { link = try await server.invitationLink(group, role: role) } }.buttonStyle(PrimaryButtonStyle()).disabled(work.busy) }
                GroupFeedback(work: work)
                Text("Anyone with this link can join. You can replace invitation links in Cookbook details.").font(.rounded(.footnote)).foregroundStyle(Theme.inkSoft)
                Spacer()
            }.padding(24).background(Theme.canvas).navigationTitle(group.name).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
                .task(id: role) { link = nil; work.run { link = try await server.invitationLink(group, role: role) } }
        }.tint(Theme.jadeDeep)
    }
}

struct GroupDetailsSheet: View {
    let group: ServerGroup
    @Environment(ServerSync.self) private var server
    @Environment(\.dismiss) private var dismiss
    @State private var members: [GroupMember] = []
    @State private var name = ""
    @State private var work = GroupWork()
    @State private var action: String?
    @State private var person: GroupMember?
    @State private var inviting = false
    @State private var notice: String?
    private var current: ServerGroup { server.allCookbooks.first { $0.id == group.id } ?? group }
    var body: some View {
        NavigationStack {
            Form {
                if current.isOwner {
                    Section("Cookbook name") {
                        TextField("Cookbook name", text: $name)
                        Button("Save name") { work.run { try await server.rename(current, name: name); notice = "Name updated." } }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || name == current.name || name.count > 80)
                    }
                }
                Section("Your people") {
                    ForEach(members) { member in
                        HStack(spacing: 12) {
                            Text(String(member.username.prefix(1)).uppercased()).font(.rounded(.headline, .bold)).frame(width: 40, height: 40).background(Theme.sunken, in: Circle())
                            VStack(alignment: .leading) {
                                Text(member.username).font(.rounded(.headline))
                                Text(member.isOwner ? "Owner" : member.role.capitalized + (member.isYou ? " · You" : "")).font(.rounded(.caption)).foregroundStyle(Theme.inkSoft)
                            }
                            Spacer()
                            if current.isOwner && !member.isYou {
                                Menu {
                                    Button(member.role == "editor" ? "Change to viewer" : "Change to editor") { person = member; action = member.role == "editor" ? "viewer" : "editor" }
                                    Button("Remove member", role: .destructive) { person = member; action = "remove" }
                                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel("Manage \(member.username)")
                            }
                        }
                    }
                    if current.isOwner {
                        if server.isConnected { Button("Invite someone", systemImage: "person.badge.plus") { inviting = true } }
                        else { NavigationLink("Connect an account to invite people") { ServerSettingsView() } }
                    }
                }
                if current.isOwner && server.isConnected { Section { Button("Replace invitation links") { action = "rotate" } } footer: { Text("Existing members keep access. Previously sent invitations will stop working.") } }
                Section { Button(current.isOwner ? "Delete cookbook" : "Leave cookbook", role: .destructive) { action = "leave" } }
                if let notice { Text(notice).foregroundStyle(Theme.jadeDeep) }
                GroupFeedback(work: work)
            }.disabled(work.busy).scrollContentBackground(.hidden).background(Theme.canvas).navigationTitle("Cookbook details").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
                .task(id: server.isConnected) { name = current.name; if server.isConnected { work.run { try await server.sync(); members = try await server.members(current) } } }
                .sheet(isPresented: $inviting) { InviteGroupSheet(group: current) }
                .confirmationDialog(confirmationTitle, isPresented: Binding(get: { action != nil }, set: { if !$0 { action = nil } }), titleVisibility: .visible) {
                    Button("Confirm", role: action == "remove" || action == "leave" ? .destructive : nil) {
                        let chosen = action, member = person; action = nil
                        work.run {
                            switch chosen {
                            case "remove": if let member { try await server.removeMember(member, from: current) }
                            case "editor", "viewer": if let member, let chosen { try await server.changeRole(member, in: current, role: chosen) }
                            case "rotate": _ = try await server.invite(current); notice = "Old invitations have expired. Create a new link to invite someone."
                            case "leave": try await server.leave(current); dismiss(); return
                            default: break
                            }
                            members = try await server.members(current)
                        }
                    }
                } message: { Text(confirmationMessage) }
        }.tint(Theme.jadeDeep).interactiveDismissDisabled(work.busy)
    }
    private var confirmationTitle: String {
        switch action {
        case "remove": "Remove \(person?.username ?? "member")?"
        case "editor": "Give \(person?.username ?? "this person") editor access?"
        case "viewer": "Give \(person?.username ?? "this person") viewer access?"
        case "rotate": "Replace invitation links?"
        default: current.isOwner ? "Delete this cookbook?" : "Leave this cookbook?"
        }
    }
    private var confirmationMessage: String {
        switch action {
        case "remove": "Their access will be removed and current invitations will expire. Recipes they contributed stay in your library and cookbook."
        case "editor": "They will be able to add and remove recipes. Their additions appear in your library. Current invitation links will expire."
        case "viewer": "They will be able to read and cook recipes. Current invitation links will expire."
        case "rotate": "Existing members keep access. All previously sent invitations will stop working."
        default: current.isOwner ? "The cookbook will be removed for everyone. All recipes stay in personal libraries." : "You will lose access to this cookbook. Recipes you contributed and copies you saved stay in their libraries."
        }
    }
}

struct JoinGroupSheet: View {
    var invitation: GroupInvitation? = nil
    @Environment(ServerSync.self) private var server
    @Environment(\.dismiss) private var dismiss
    @State private var link = ""
    @State private var pending: GroupInvitation?
    @State private var details: InvitationDetails?
    @State private var username = ""
    @State private var password = ""
    @State private var register = true
    @State private var work = GroupWork()
    @State private var joined = false
    private var differentServer: Bool { server.isConnected && pending?.server != server.server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    var body: some View {
        NavigationStack {
            Form {
                if joined {
                    Section { Label("You’re in!", systemImage: "checkmark.circle.fill").font(.rounded(.title2, .bold)); Text("\(details?.name ?? "Your cookbook") is ready in Cookbooks."); Button("Done") { dismiss() } }
                } else if let details {
                    Section {
                        Text("\(details.invitedBy) invited you to").foregroundStyle(Theme.inkSoft)
                        Text(details.name).font(.rounded(.title, .bold))
                        Text(details.role == "editor" ? "You’re invited as an editor. Additions also go into the owner’s library." : "You’re invited as a viewer. Read, cook and save your own copies.").foregroundStyle(Theme.inkSoft)
                    }
                    if differentServer {
                        Section { Text("This invitation uses a different account server. Disconnect your current account in Backup & sync before joining."); Text(pending?.server ?? "").font(.footnote) }
                    } else {
                        if !server.isConnected {
                            Section {
                                Picker("Account", selection: $register) { Text("Create account").tag(true); Text("Sign in").tag(false) }.pickerStyle(.segmented)
                                TextField("Username", text: $username).textContentType(.username)
                                SecureField("Password", text: $password).textContentType(register ? .newPassword : .password)
                            } footer: {
                                Text("Passwords must contain 10–256 characters.")
                                Text("Your private recipes will back up to \(pending?.server ?? "this server"). People you invite see only recipes in shared cookbooks.")
                            }.disabled(work.busy)
                        }
                        Section {
                            Button("Join cookbook") { work.run {
                                guard let pending else { return }
                                if !server.isConnected { try await server.connect(address: pending.server, username: username, password: password, registrationKey: "", register: register, invitationCode: pending.code) }
                                try await server.joinGroup(pending.code); password = ""; joined = true
                            } }.disabled(work.busy)
                        }
                    }
                } else {
                    Section {
                        Text("Open your invitation to find your people.").font(.rounded(.title3, .bold))
                        TextField("Paste invitation link", text: $link).keyboardType(.URL)
                        Button("Open invitation") { work.run {
                            let value: GroupInvitation
                            if server.isConnected && !link.contains(":") { value = GroupInvitation(server: server.server, code: link.trimmingCharacters(in: .whitespacesAndNewlines)) }
                            else { value = try GroupInvitation.parse(link) }
                            pending = value; details = try await server.invitationDetails(value)
                        } }.disabled(link.isEmpty || work.busy)
                    }
                }
                GroupFeedback(work: work)
            }.textInputAutocapitalization(.never).autocorrectionDisabled().scrollContentBackground(.hidden).background(Theme.canvas).navigationTitle("Join a cookbook").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() }.disabled(work.busy) } }
                .task { if let invitation { pending = invitation; link = invitation.server + "/join#" + invitation.code; work.run { details = try await server.invitationDetails(invitation) } } }
        }.tint(Theme.jadeDeep).interactiveDismissDisabled(work.busy)
    }
}
