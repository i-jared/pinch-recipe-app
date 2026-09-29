import PhotosUI
import SwiftUI

/// Holds the search index outside of SwiftUI state so querying doesn't trigger updates.
final class SearchEngine {
    private var search = FuzzySearch()
    func results(for query: String, in recipes: [Recipe]) -> [FuzzySearch.Result] {
        search.search(query, in: recipes)
    }
}

enum CaptureStart: Identifiable {
    case scan
    case photos([PhotosPickerItem])
    case images([UIImage])
    case text(String)
    case url(URL)
    case blank

    var id: String {
        switch self {
        case .scan: "scan"
        case .photos(let items): "photos-\(items.count)-\(items.first?.itemIdentifier ?? "")"
        case .images(let images): "images-\(images.count)-\(ObjectIdentifier(images.first ?? UIImage()).hashValue)"
        case .text(let text): "text-\(text.hashValue)"
        case .url(let url): "url-\(url.absoluteString)"
        case .blank: "blank"
        }
    }
}

struct LibraryView: View {
    @Environment(RecipeStore.self) private var store
    @Binding var path: [Route]
    let zoom: Namespace.ID

    enum Filter: Hashable {
        case all, favorites, quick, tag(String)
    }

    enum Sort: String, CaseIterable {
        case newest = "Newest"
        case alphabetical = "A to Z"
        case quickest = "Quickest"
        case recentlyCooked = "Recently cooked"
    }

    @State private var query = ""
    @State private var filter: Filter = .all
    @AppStorage("librarySort") private var sort: Sort = .newest
    @State private var engine = SearchEngine()
    @State private var capture: CaptureStart?
    @State private var showPhotoPicker = false
    @State private var pickedItems: [PhotosPickerItem] = []
    @State private var showPasteSheet = false
    @State private var pastedText: String?
    @State private var showWebSheet = false
    @State private var enteredURL: URL?
    @State private var pendingDelete: Recipe?
    @FocusState private var searchFocused: Bool

    var body: some View {
        GeometryReader { geo in
            library(topInset: geo.safeAreaInsets.top)
        }
    }

    private func library(topInset: CGFloat) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                if !store.recipes.isEmpty {
                    searchField
                    if query.isEmpty { filterBar }
                }
                content
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 120)
            .frame(maxWidth: 1100)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.immediately)
        .background(Theme.canvas.ignoresSafeArea())
        .overlay(alignment: .top) {
            // Keeps scrolled tins from running under the clock and battery icons.
            Theme.canvas.opacity(0.94)
                .frame(height: topInset)
                .offset(y: -topInset)
                .allowsHitTesting(false)
        }
        .toolbar(.hidden, for: .navigationBar)
        #if DEBUG
        .task {
            // Development aid: `-importPages /path/one.jpg,/path/two.jpg` runs the scan flow
            // on files, since the simulator has no camera.
            let args = ProcessInfo.processInfo.arguments
            if let i = args.firstIndex(of: "-importPages"), i + 1 < args.count {
                let images = args[i + 1].split(separator: ",").compactMap { UIImage(contentsOfFile: String($0)) }
                if !images.isEmpty { capture = .images(images) }
            }
            // `-importURL https://…` runs the website flow.
            if let i = args.firstIndex(of: "-importURL"), i + 1 < args.count, let url = WebRecipeImporter.normalizedURL(args[i + 1]) {
                capture = .url(url)
            }
        }
        #endif
        .safeAreaInset(edge: .bottom) { addBar }
        .fullScreenCover(item: $capture) { start in
            CaptureFlowView(start: start) { savedID in
                capture = nil
                if let savedID {
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(450))
                        path.append(.recipe(savedID))
                    }
                }
            }
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickedItems, maxSelectionCount: 10, selectionBehavior: .ordered, matching: .images)
        .onChange(of: pickedItems) { _, items in
            guard !items.isEmpty else { return }
            let chosen = items
            pickedItems = []
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(400))
                capture = .photos(chosen)
            }
        }
        .sheet(isPresented: $showPasteSheet, onDismiss: {
            if let text = pastedText {
                pastedText = nil
                capture = .text(text)
            }
        }) {
            PasteRecipeSheet { text in
                pastedText = text
                showPasteSheet = false
            }
            .tint(Theme.jadeDeep)
        }
        .sheet(isPresented: $showWebSheet, onDismiss: {
            if let url = enteredURL {
                enteredURL = nil
                capture = .url(url)
            }
        }) {
            WebRecipeSheet { url in
                enteredURL = url
                showWebSheet = false
            }
            .presentationDetents([.medium, .large])
            .tint(Theme.jadeDeep)
        }
        .confirmationDialog(
            "Delete \(pendingDelete?.displayTitle ?? "recipe")?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete recipe", role: .destructive) {
                if let recipe = pendingDelete { store.delete(recipe.id) }
                pendingDelete = nil
            }
        } message: {
            Text("This removes it from this device and from Google Drive if you've connected it.")
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Pinch")
                    .font(.system(.largeTitle, design: .rounded, weight: .heavy))
                    .foregroundStyle(Theme.ink)
                Text(subtitle)
                    .font(.rounded(.subheadline, .medium))
                    .foregroundStyle(Theme.inkSoft)
            }
            Spacer()
            NavigationLink(value: Route.settings) {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Theme.ink)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Theme.sunken))
            }
            .accessibilityLabel("Settings")
        }
    }

    private var subtitle: String {
        switch store.recipes.count {
        case 0: "Your recipe box"
        case 1: "1 recipe in your box"
        default: "\(store.recipes.count) recipes in your box"
        }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.inkSoft)
            TextField("Search recipes and ingredients", text: $query)
                .font(.rounded(.body, .medium))
                .foregroundStyle(Theme.ink)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($searchFocused)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.inkSoft)
                }
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 50)
        .background(Capsule().fill(Theme.sunken))
    }

    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                FilterChip(title: "All", isSelected: filter == .all) { filter = .all }
                if store.recipes.contains(where: \.isFavorite) {
                    FilterChip(title: "Favorites", systemImage: "heart.fill", isSelected: filter == .favorites) { filter = .favorites }
                }
                if store.recipes.contains(where: { ($0.totalMinutes ?? .max) <= 30 }) {
                    FilterChip(title: "30 min or less", systemImage: "bolt.fill", isSelected: filter == .quick) { filter = .quick }
                }
                ForEach(store.allTags, id: \.self) { tag in
                    FilterChip(title: tag, isSelected: filter == .tag(tag)) { filter = .tag(tag) }
                }
                Menu {
                    Picker("Sort", selection: $sort) {
                        ForEach(Sort.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.up.arrow.down")
                        Text(sort.rawValue)
                    }
                    .font(.rounded(.subheadline, .semibold))
                    .foregroundStyle(Theme.inkSoft)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 36)
                }
                .accessibilityLabel("Sort recipes, currently \(sort.rawValue)")
            }
            .padding(.vertical, 2)
        }
        .scrollClipDisabled()
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if store.recipes.isEmpty {
            EmptyLibraryView(onSample: { SampleRecipes.all.forEach(store.save) })
            .padding(.top, 24)
        } else if !query.trimmingCharacters(in: .whitespaces).isEmpty {
            searchResults
        } else {
            let recipes = filteredRecipes
            if recipes.isEmpty {
                Text("Nothing here yet. Recipes you mark with a heart or tag show up in these filters.")
                    .font(.rounded(.body))
                    .foregroundStyle(Theme.inkSoft)
                    .padding(.top, 40)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 158, maximum: 260), spacing: 14)], spacing: 14) {
                    ForEach(recipes) { recipe in
                        NavigationLink(value: Route.recipe(recipe.id)) {
                            RecipeTinCard(recipe: recipe)
                                .matchedTransitionSource(id: recipe.id, in: zoom)
                        }
                        .buttonStyle(PressableCardStyle())
                        .contextMenu { cardMenu(recipe) }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func cardMenu(_ recipe: Recipe) -> some View {
        Button {
            store.toggleFavorite(recipe.id)
        } label: {
            Label(recipe.isFavorite ? "Remove from favorites" : "Add to favorites", systemImage: recipe.isFavorite ? "heart.slash" : "heart")
        }
        Button(role: .destructive) {
            pendingDelete = recipe
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    private var filteredRecipes: [Recipe] {
        var list = store.recipes
        switch filter {
        case .all: break
        case .favorites: list = list.filter(\.isFavorite)
        case .quick: list = list.filter { ($0.totalMinutes ?? .max) <= 30 }
        case .tag(let tag): list = list.filter { $0.tags.contains(tag) }
        }
        switch sort {
        case .newest: list.sort { $0.createdAt > $1.createdAt }
        case .alphabetical: list.sort { $0.displayTitle.localizedCaseInsensitiveCompare($1.displayTitle) == .orderedAscending }
        case .quickest: list.sort { ($0.totalMinutes ?? .max) < ($1.totalMinutes ?? .max) }
        case .recentlyCooked: list.sort { ($0.lastCookedAt ?? .distantPast) > ($1.lastCookedAt ?? .distantPast) }
        }
        return list
    }

    @ViewBuilder
    private var searchResults: some View {
        let results = engine.results(for: query, in: store.recipes)
        if results.isEmpty {
            VStack(spacing: 8) {
                Text("No recipes match “\(query)”")
                    .font(.rounded(.headline, .bold))
                    .foregroundStyle(Theme.ink)
                Text("Try an ingredient, like “lemon”, or part of a name.")
                    .font(.rounded(.subheadline))
                    .foregroundStyle(Theme.inkSoft)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 40)
        } else {
            LazyVStack(spacing: 10) {
                ForEach(results) { result in
                    NavigationLink(value: Route.recipe(result.recipe.id)) {
                        SearchResultRow(result: result)
                            .matchedTransitionSource(id: result.recipe.id, in: zoom)
                    }
                    .buttonStyle(PressableCardStyle())
                }
            }
        }
    }

    // MARK: Adding

    private var addBar: some View {
        VStack(spacing: 10) {
            TimerDock()
            HStack(spacing: 10) {
                Button(action: startScan) {
                    Label(DocumentScannerView.isSupported ? "Scan a recipe" : "Add from photos", systemImage: DocumentScannerView.isSupported ? "camera.fill" : "photo.on.rectangle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())

                Menu {
                    Button { showPhotoPicker = true } label: { Label("Choose photos", systemImage: "photo.on.rectangle") }
                    Button { showWebSheet = true } label: { Label("From a website", systemImage: "safari") }
                    Button { showPasteSheet = true } label: { Label("Paste text", systemImage: "doc.on.clipboard") }
                    Button { capture = .blank } label: { Label("Type it in", systemImage: "square.and.pencil") }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(Theme.onJade)
                        .frame(width: 54, height: 54)
                        .background(Circle().fill(Theme.jade))
                }
                .accessibilityLabel("More ways to add a recipe")
            }
            .shadow(color: Theme.jadeDeep.opacity(0.25), radius: 14, y: 6)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 6)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .background(alignment: .bottom) { BottomFade() }
    }

    private func startScan() {
        searchFocused = false
        if DocumentScannerView.isSupported {
            capture = .scan
        } else {
            showPhotoPicker = true
        }
    }
}

/// Slight press-down feedback for tappable cards.
struct PressableCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.75), value: configuration.isPressed)
    }
}

/// A recipe on the shelf: a patterned tin with a white label for the name.
struct RecipeTinCard: View {
    let recipe: Recipe

    var body: some View {
        ZStack(alignment: .bottom) {
            CoverArt(palette: recipe.palette, pattern: recipe.pattern, cell: 20)
            VStack(spacing: 0) {
                HStack(alignment: .top) {
                    EmojiSticker(emoji: recipe.emoji, size: 54, tilt: CoverSuggester.stableIndex(recipe.id, count: 2) == 0 ? -8 : 7)
                    Spacer()
                    if recipe.isFavorite {
                        Image(systemName: "heart.fill")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(Theme.cherry)
                            .frame(width: 30, height: 30)
                            .background(Circle().fill(.white))
                            .accessibilityLabel("Favorite")
                    }
                }
                .padding(12)
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 5) {
                    Text(recipe.displayTitle)
                        .font(.rounded(.headline, .bold))
                        .foregroundStyle(Color(hex: 0x17332C))
                        .lineLimit(3)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let time = recipe.displayTime {
                        Label(time, systemImage: "clock")
                            .font(.rounded(.caption, .semibold))
                            .foregroundStyle(Color(hex: 0x587068))
                            .labelStyle(.titleAndIcon)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.white))
                .padding(8)
            }
        }
        .aspectRatio(0.8, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

struct SearchResultRow: View {
    let result: FuzzySearch.Result

    var body: some View {
        let recipe = result.recipe
        HStack(spacing: 14) {
            ZStack {
                CoverArt(palette: recipe.palette, pattern: recipe.pattern, cell: 14)
                Text(recipe.emoji).font(.system(size: 28))
            }
            .frame(width: 64, height: 64)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(recipe.displayTitle)
                    .font(.rounded(.headline, .bold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(2)
                if let snippet = result.snippet {
                    Text(snippet)
                        .font(.rounded(.subheadline))
                        .foregroundStyle(Theme.inkSoft)
                        .lineLimit(2)
                } else if let time = recipe.displayTime {
                    Label(time, systemImage: "clock")
                        .font(.rounded(.subheadline, .medium))
                        .foregroundStyle(Theme.inkSoft)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Theme.inkSoft.opacity(0.6))
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Theme.surface))
        .accessibilityElement(children: .combine)
    }
}

struct EmptyLibraryView: View {
    let onSample: () -> Void

    var body: some View {
        VStack(spacing: 22) {
            ZStack {
                miniTin(.saffron, .stripes, "🥧", angle: -14, x: -78, y: 16)
                miniTin(.jade, .dots, "🥗", angle: 12, x: 78, y: 18)
                miniTin(.tomato, .gingham, "🍝", angle: -3, x: 0, y: -6)
            }
            .frame(height: 190)
            .accessibilityHidden(true)

            VStack(spacing: 10) {
                Text("Your recipe box is empty")
                    .font(.rounded(.title2, .bold))
                    .foregroundStyle(Theme.ink)
                Text("Scan a recipe card, a cookbook page or a magazine clipping, or bring one in from a website. Pinch reads it and files the recipe for you.")
                    .font(.rounded(.body))
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 360)
            }
            Button("Add three sample recipes", action: onSample)
                .buttonStyle(SoftButtonStyle())
        }
        .frame(maxWidth: .infinity)
    }

    private func miniTin(_ palette: CoverPalette, _ pattern: CoverPattern, _ emoji: String, angle: Double, x: CGFloat, y: CGFloat) -> some View {
        ZStack {
            CoverArt(palette: palette, pattern: pattern, cell: 16)
            EmojiSticker(emoji: emoji, size: 58, tilt: 0)
        }
        .frame(width: 118, height: 146)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .shadow(color: palette.base.opacity(0.35), radius: 12, y: 6)
        .rotationEffect(.degrees(angle))
        .offset(x: x, y: y)
    }
}

/// Paste a recipe copied from a website, note or message.
struct PasteRecipeSheet: View {
    let onSubmit: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text("Paste the whole recipe, name and all. Pinch sorts out the ingredients and steps.")
                    .font(.rounded(.subheadline))
                    .foregroundStyle(Theme.inkSoft)
                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text("Grandma's Banana Bread\n3 ripe bananas\n⅓ cup melted butter\n…")
                            .font(.rounded(.body))
                            .foregroundStyle(Theme.inkSoft.opacity(0.6))
                            .padding(.horizontal, 5)
                            .padding(.vertical, 8)
                    }
                    TextEditor(text: $text)
                        .font(.rounded(.body))
                        .scrollContentBackground(.hidden)
                        .focused($focused)
                }
                .padding(10)
                .frame(maxHeight: .infinity)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.surface))
                HStack {
                    PasteButton(payloadType: String.self) { strings in
                        if let first = strings.first { text = first }
                    }
                    .buttonBorderShape(.capsule)
                    .tint(Theme.jadeDeep)
                    Spacer()
                    Button("Read recipe") { onSubmit(text) }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .opacity(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.5 : 1)
                }
            }
            .padding(20)
            .background(Theme.canvas.ignoresSafeArea())
            .navigationTitle("Paste a recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear { focused = true }
        }
    }
}

/// Takes a recipe's web address, typed or pasted.
struct WebRecipeSheet: View {
    let onSubmit: (URL) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var showInvalid = false
    @FocusState private var focused: Bool
    private let account = AIAccount.shared

    private var url: URL? { WebRecipeImporter.normalizedURL(text) }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Paste the link to any recipe page. Pinch copies the recipe and leaves out the ads and life stories.")
                    .font(.rounded(.subheadline))
                    .foregroundStyle(Theme.inkSoft)
                HStack(spacing: 10) {
                    Image(systemName: "link")
                        .foregroundStyle(Theme.jadeDeep)
                    TextField("https://…", text: $text)
                        .font(.rounded(.body))
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.go)
                        .focused($focused)
                        .onSubmit(submit)
                    if !text.isEmpty {
                        Button {
                            text = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.inkSoft)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear")
                    }
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.surface))
                if showInvalid {
                    Text("That doesn't look like a web address.")
                        .font(.rounded(.footnote, .semibold))
                        .foregroundStyle(Theme.cherry)
                }
                if !account.isConnected {
                    Label("Most recipe sites work as is. For the rest, add an OpenRouter key in Settings and AI will find the recipe.", systemImage: "sparkles")
                        .font(.rounded(.footnote))
                        .foregroundStyle(Theme.inkSoft)
                }
                HStack {
                    PasteButton(payloadType: String.self) { strings in
                        if let first = strings.first {
                            text = first.trimmingCharacters(in: .whitespacesAndNewlines)
                            showInvalid = false
                        }
                    }
                    .buttonBorderShape(.capsule)
                    .tint(Theme.jadeDeep)
                    Spacer()
                    Button("Get recipe", action: submit)
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .opacity(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.5 : 1)
                }
                Spacer(minLength: 0)
            }
            .padding(20)
            .background(Theme.canvas.ignoresSafeArea())
            .navigationTitle("From a website")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear { focused = true }
            .onChange(of: text) { _, _ in showInvalid = false }
        }
    }

    private func submit() {
        guard let url else {
            showInvalid = true
            return
        }
        onSubmit(url)
    }
}
