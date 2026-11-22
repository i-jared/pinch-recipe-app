import SwiftUI

@Observable @MainActor
final class GroupWork {
    var busy = false
    var error: String?
    func run(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil
        Task {
            defer { busy = false }
            do { try await action() }
            catch is CancellationError { }
            catch { self.error = error.localizedDescription }
        }
    }
}

struct GroupFeedback: View {
    let work: GroupWork
    var body: some View {
        if work.busy { ProgressView().frame(maxWidth: .infinity).padding() }
        if let error = work.error { Text(error).font(.rounded(.callout)).foregroundStyle(Theme.cherry).padding(.vertical, 8) }
    }
}

struct CookbookSearch: View {
    @Binding var text: String
    var placeholder = "Search recipes and ingredients"
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.inkSoft)
            TextField(placeholder, text: $text).autocorrectionDisabled().textInputAutocapitalization(.never)
            if !text.isEmpty { Button { text = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Clear search") }
        }
        .font(.rounded(.body)).padding(16).background(Theme.sunken, in: Capsule())
    }
}

struct GroupCover: View {
    let group: ServerGroup
    var body: some View {
        HStack(spacing: 3) {
            let previews = group.previews ?? []
            ForEach(0..<3) { index in
                let recipe = index < previews.count ? previews[index] : nil
                ZStack {
                    CoverArt(palette: recipe?.palette ?? [.jade, .butter, .tomato][index], pattern: recipe?.pattern ?? .gingham, cell: 20)
                    Text(recipe?.emoji ?? ["🥣", "🍋", "🥕"][index]).font(.system(size: 34))
                }
            }
        }.frame(height: 130).clipped().accessibilityHidden(true)
    }
}

struct RecipeGroupsView: View {
    @Environment(ServerSync.self) private var server
    @State private var work = GroupWork()
    @State private var creating = false
    @State private var joining = false
    @State private var created: ServerGroup?
    @State private var query = ""
    private var groups: [ServerGroup] { server.allCookbooks.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Your cookbooks").font(.rounded(.title2, .bold)).foregroundStyle(Theme.ink)
                Spacer()
                Menu {
                    Button("New cookbook", systemImage: "plus") { creating = true }
                    Button("Join a cookbook", systemImage: "person.badge.plus") { joining = true }
                } label: { Image(systemName: "plus").font(.system(size: 20, weight: .semibold)).foregroundStyle(Theme.ink).frame(width: 44, height: 44).background(Theme.sunken, in: Circle()) }.accessibilityLabel("Create or join a cookbook")
            }
            if !server.allCookbooks.isEmpty { CookbookSearch(text: $query, placeholder: "Find a cookbook") }
            if server.allCookbooks.isEmpty {
                ContentUnavailableView("Make room for your favorites", systemImage: "books.vertical", description: Text("Organize recipes into cookbooks. Keep them private or invite people to view or contribute."))
                Button("Create a cookbook") { creating = true }.buttonStyle(PrimaryButtonStyle())
                Button("Join an invitation") { joining = true }.buttonStyle(SoftButtonStyle())
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 260), spacing: 16)], spacing: 16) {
                ForEach(groups) { group in
                    NavigationLink { RecipeGroupView(group: group) } label: {
                        VStack(alignment: .leading, spacing: 0) {
                            GroupCover(group: group)
                            VStack(alignment: .leading, spacing: 8) {
                                Text(group.name).font(.rounded(.title3, .bold))
                                Text("\(group.recipeCount ?? 0) recipes · " + (group.isOwner ? ((group.memberCount ?? 1) > 1 ? "Shared by you" : "Private") : "\(group.ownerName)’s cookbook"))
                                    .font(.rounded(.subheadline)).foregroundStyle(Theme.inkSoft)
                                if !group.isOwner { Text(group.canEdit ? "Editor · You can add recipes" : "Viewer · Read and cook").font(.rounded(.caption)).foregroundStyle(Theme.jadeDeep) }
                            }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
                        }.background(Theme.surface).clipShape(RoundedRectangle(cornerRadius: 24)).foregroundStyle(Theme.ink)
                    }.buttonStyle(PressableCardStyle())
                }
            }
            GroupFeedback(work: work)
            if work.error != nil { Button("Try again") { work.run { try await server.loadGroups() } }.buttonStyle(SoftButtonStyle()) }
        }
        .task { if server.isConnected { work.run { try await server.loadGroups() } } }
        .sheet(isPresented: $creating) { CreateGroupSheet { created = $0 } }
        .sheet(isPresented: $joining) { JoinGroupSheet() }
        .navigationDestination(item: $created) { RecipeGroupView(group: $0) }
    }
}

struct CreateGroupSheet: View {
    @Environment(ServerSync.self) private var server
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var work = GroupWork()
    var onCreated: (ServerGroup) -> Void
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 22) {
                Text("Give your cookbook a name").font(.rounded(.title2, .bold))
                Text("Family favorites, Sunday dinner, or something entirely your own.").font(.rounded(.body)).foregroundStyle(Theme.inkSoft)
                TextField("Cookbook name", text: $name).font(.rounded(.title3)).padding(18).background(Theme.surface, in: RoundedRectangle(cornerRadius: 18)).submitLabel(.done)
                Button("Create cookbook") { work.run { let group = try await server.createGroup(name); dismiss(); onCreated(group) } }
                    .buttonStyle(PrimaryButtonStyle()).disabled(work.busy || name.trimmingCharacters(in: .whitespaces).isEmpty || name.count > 80)
                GroupFeedback(work: work)
                Spacer()
            }.padding(24).background(Theme.canvas).navigationTitle("New cookbook").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }.presentationDetents([.medium, .large]).tint(Theme.jadeDeep)
    }
}

struct RecipeGroupView: View {
    let group: ServerGroup
    @Environment(\.dismiss) private var dismiss
    @Environment(RecipeStore.self) private var store
    @Environment(ServerSync.self) private var server
    @State private var recipes: [SharedRecipe] = []
    @State private var query = ""
    @State private var sort = "Recently added"
    @State private var contributor = "Everyone"
    @State private var work = GroupWork()
    @State private var adding = false
    @State private var details = false
    @State private var invite = false
    @State private var removing: SharedRecipe?
    private var current: ServerGroup { server.allCookbooks.first { $0.id == group.id } ?? group }
    private var available: [SharedRecipe] {
        guard current.isOwner, let book = store.cookbooks.first(where: { $0.id == group.id }) else { return recipes }
        return store.recipes.filter { book.recipeIDs.contains($0.id) }.map { recipe in SharedRecipe(id: recipe.id, sharedBy: recipe.origin?.sharedBy ?? "You", canRemove: true, recipe: recipe, sharedAt: book.entries.first { $0.recipeId == recipe.id }?.updatedAt.timeIntervalSince1970) }
    }
    private var shown: [SharedRecipe] {
        let filtered = available.filter { item in
            (contributor == "Everyone" || item.sharedBy == contributor) && (query.isEmpty || ([item.recipe.title, item.recipe.summary] + item.recipe.ingredients.map(\.text) + item.recipe.tags).joined(separator: " ").localizedCaseInsensitiveContains(query))
        }
        return sort == "A to Z" ? filtered.sorted { $0.recipe.displayTitle.localizedStandardCompare($1.recipe.displayTitle) == .orderedAscending } : filtered.sorted { ($0.sharedAt ?? 0) > ($1.sharedAt ?? 0) }
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(current.name).font(.system(.largeTitle, design: .rounded, weight: .heavy)).foregroundStyle(Theme.ink).accessibilityAddTraits(.isHeader)
                Button { details = true } label: {
                    HStack { Label((current.memberCount ?? 1) == 1 ? "Only you" : "\(current.memberCount ?? 1) people", systemImage: "person.2.fill"); Spacer(); Text("Cookbook details"); Image(systemName: "chevron.right") }.font(.rounded(.subheadline, .semibold)).foregroundStyle(Theme.jadeDeep)
                }
                if available.isEmpty && !work.busy && work.error == nil {
                    GroupCover(group: current).clipShape(RoundedRectangle(cornerRadius: 24))
                    Text("Every cookbook starts with a favorite.").font(.rounded(.title2, .bold))
                    Text(current.isOwner ? "Add a few recipes, then invite your people to bring theirs." : current.canEdit ? "Add a favorite from your library to get things started." : "Recipes will appear here when the owner or an editor adds them.").font(.rounded(.body)).foregroundStyle(Theme.inkSoft)
                } else if !available.isEmpty {
                    CookbookSearch(text: $query)
                    HStack {
                        Menu { Picker("Sort recipes", selection: $sort) { Text("Recently added").tag("Recently added"); Text("A to Z").tag("A to Z") } } label: { Label(sort, systemImage: "arrow.up.arrow.down") }
                        Spacer()
                        Menu { Picker("Added by", selection: $contributor) { Text("Everyone").tag("Everyone"); ForEach(Array(Set(available.map(\.sharedBy))).sorted(), id: \.self) { Text($0).tag($0) } } } label: { Label(contributor, systemImage: "person") }
                    }.font(.rounded(.subheadline, .semibold)).foregroundStyle(Theme.inkSoft)
                }
                GroupFeedback(work: work)
                if work.error != nil { Button("Try again", action: refresh).buttonStyle(SoftButtonStyle()) }
                if !available.isEmpty && shown.isEmpty { Text("No recipes match. Try another ingredient or person.").foregroundStyle(Theme.inkSoft) }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 158, maximum: 260), spacing: 14)], spacing: 20) {
                    ForEach(shown) { item in
                        NavigationLink { if current.isOwner { RecipeDetailView(recipeID: item.recipe.id) } else { SharedCookbookRecipeView(item: item, group: current) } } label: {
                            VStack(alignment: .leading, spacing: 8) {
                                RecipeTinCard(recipe: item.recipe, showsBanner: current.isOwner)
                                Text("Added by \(item.sharedBy)").font(.rounded(.caption, .medium)).foregroundStyle(Theme.inkSoft)
                            }
                        }.buttonStyle(PressableCardStyle())
                            .contextMenu { if item.canRemove { Button("Remove from cookbook", role: .destructive) { removing = item } } }
                    }
                }
            }.padding(20).padding(.bottom, 30).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.background(Theme.canvas.ignoresSafeArea()).navigationTitle("").navigationBarTitleDisplayMode(.inline).toolbar(.visible, for: .navigationBar)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { if current.isOwner { Button("Invite", systemImage: "person.badge.plus") { if server.isConnected { invite = true } else { details = true } } } } }
            .safeAreaInset(edge: .bottom) { if current.canEdit { Button { adding = true } label: { Label("Add recipes", systemImage: "plus").frame(maxWidth: .infinity) }.buttonStyle(PrimaryButtonStyle()).padding(.horizontal, 20).padding(.vertical, 10).background(BottomFade()) } }
            .task { refresh() }
            .onChange(of: server.allCookbooks.map(\.id)) { before, after in
                if before.contains(group.id) && !after.contains(group.id) { dismiss() }
            }
            .onChange(of: server.groups) { _, _ in
                if !current.isOwner { work.run { recipes = try await server.sharedRecipes(current) } }
            }
            .refreshable { do { try await load() } catch { work.error = error.localizedDescription } }
            .sheet(isPresented: $adding, onDismiss: refresh) { AddGroupRecipesSheet(group: current, shared: available) }
            .sheet(isPresented: $details, onDismiss: refresh) { GroupDetailsSheet(group: current) }
            .sheet(isPresented: $invite) { InviteGroupSheet(group: current) }
            .confirmationDialog("Remove from this cookbook?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
                Button("Remove recipe", role: .destructive) { if let item = removing { work.run { try await server.remove(item, from: current); try await load() } }; removing = nil }
            } message: { Text("The recipe stays in your library and in any other cookbooks.") }
    }
    private func refresh() { work.run { try await load() } }
    private func load() async throws {
        try await server.loadGroups()
        recipes = try await server.sharedRecipes(current)
        UserDefaults.standard.set(recipes.map { $0.sharedAt ?? 0 }.max() ?? 0, forKey: "group.seen.\(group.id)")
    }
}

struct AddGroupRecipesSheet: View {
    let group: ServerGroup
    let shared: [SharedRecipe]
    @Environment(RecipeStore.self) private var store
    @Environment(ServerSync.self) private var server
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var selected = Set<String>()
    @State private var completed = Set<String>()
    @State private var work = GroupWork()
    var body: some View {
        NavigationStack {
            List {
                Section { CookbookSearch(text: $query) }.listRowBackground(Color.clear)
                ForEach(store.recipes.filter { query.isEmpty || $0.displayTitle.localizedCaseInsensitiveContains(query) }) { recipe in
                    let already = completed.contains(recipe.id) || (group.mySharedRecipeIds ?? []).contains(recipe.id)
                    Button {
                        if selected.contains(recipe.id) { selected.remove(recipe.id) } else { selected.insert(recipe.id) }
                    } label: {
                        HStack(spacing: 14) {
                            Text(recipe.emoji).font(.title)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(recipe.displayTitle).font(.rounded(.headline)).foregroundStyle(Theme.ink)
                                if already { Text("Already in this cookbook").font(.rounded(.caption)).foregroundStyle(Theme.inkSoft) }
                            }
                            Spacer()
                            Image(systemName: already || selected.contains(recipe.id) ? "checkmark.circle.fill" : "circle").foregroundStyle(Theme.jadeDeep)
                        }.padding(.vertical, 6)
                    }.disabled(work.busy || already)
                }
                if store.recipes.isEmpty { Text("Add a recipe to your library, then put it in this cookbook.").foregroundStyle(Theme.inkSoft) }
                GroupFeedback(work: work)
            }.scrollContentBackground(.hidden).background(Theme.canvas).navigationTitle("Add recipes").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() }.disabled(work.busy) } }
                .safeAreaInset(edge: .bottom) {
                    Button("Add \(selected.count) recipe\(selected.count == 1 ? "" : "s")") { work.run {
                        for recipe in store.recipes where selected.contains(recipe.id) {
                            try await server.share(recipe, with: group); completed.insert(recipe.id); selected.remove(recipe.id)
                        }
                        try await server.loadGroups(); dismiss()
                    } }.buttonStyle(PrimaryButtonStyle()).disabled(work.busy || selected.isEmpty).padding(20).background(Theme.canvas)
                }
        }.tint(Theme.jadeDeep).interactiveDismissDisabled(work.busy)
    }
}
