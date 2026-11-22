import PhotosUI
import SwiftUI

/// Review a freshly scanned recipe, or edit a saved one.
struct RecipeEditorView: View {
    enum Mode {
        case new(readBy: RecipeImporter.ReadBy)
        case edit

        var isNew: Bool { if case .new = self { true } else { false } }
    }

    let mode: Mode
    let onSave: (Recipe) -> Void
    /// Receives the abandoned draft so page photos added here can be cleaned up.
    let onCancel: (Recipe) -> Void

    @Environment(RecipeStore.self) private var store
    @Environment(ServerSync.self) private var server
    @State private var draft: Recipe
    private let original: Recipe
    @State private var editMode: EditMode = .inactive
    @State private var showEmojiPicker = false
    @State private var showScanner = false
    @State private var showPhotoPicker = false
    @State private var pickedItems: [PhotosPickerItem] = []
    @State private var pickedRecipePhotos: [PhotosPickerItem] = []
    @State private var photoTask: Task<Void, Never>?
    @State private var recipePhotoError = false
    @State private var addingStage: RecipeImporter.Stage?
    @State private var addTask: Task<Void, Never>?
    @State private var addPageError: String?
    @State private var viewer: PageViewerItem?
    @State private var confirmDiscard = false
    @State private var newTag = ""
    @State private var photosAddedHere: [String] = []
    @FocusState private var focus: FocusField?

    enum FocusField: Hashable {
        case title, item(String), tag
    }

    init(recipe: Recipe, mode: Mode, onSave: @escaping (Recipe) -> Void, onCancel: @escaping (Recipe) -> Void) {
        self.mode = mode
        self.onSave = onSave
        self.onCancel = onCancel
        self.original = recipe
        _draft = State(initialValue: recipe)
    }

    var body: some View {
        List {
            if case .new(let readBy) = mode { introBanner(readBy: readBy) }
            pagesSection
            coverSection
            nameSection
            detailsSection
            tagsSection
            itemsSection(title: "Ingredients", items: $draft.ingredients, kind: .ingredient)
            itemsSection(title: "Steps", items: $draft.steps, kind: .step)
            notesSection
            if !draft.rawText.isEmpty { scannedTextSection }
        }
        .listStyle(.insetGrouped)
        .disabled(photoTask != nil)
        .scrollContentBackground(.hidden)
        .background(Theme.canvas.ignoresSafeArea())
        .scrollDismissesKeyboard(.interactively)
        .environment(\.editMode, $editMode)
        .navigationTitle(mode.isNew ? "Check it over" : "Edit recipe")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { attemptCancel() }
                    .disabled(photoTask != nil)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(mode.isNew ? "Save recipe" : "Save") { save() }
                    .fontWeight(.bold)
                    .disabled(photoTask != nil)
            }
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { focus = nil }
            }
        }
        .interactiveDismissDisabled(hasChanges || photoTask != nil)
        .confirmationDialog(mode.isNew ? "Discard this recipe?" : "Discard your changes?", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button(mode.isNew ? "Discard recipe" : "Discard changes", role: .destructive) { cancel() }
            Button("Keep editing", role: .cancel) {}
        }
        .sheet(isPresented: $showEmojiPicker) {
            EmojiPickerSheet(selection: $draft.emoji)
                .presentationDetents([.medium, .large])
                .tint(Theme.jadeDeep)
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScannerView(
                onFinish: { images in
                    showScanner = false
                    addPages(images)
                },
                onCancel: { showScanner = false }
            )
            .ignoresSafeArea()
        }
        .fullScreenCover(item: $viewer) { item in
            PageViewer(photos: draft.photos, startIndex: item.index)
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickedItems, maxSelectionCount: 10, selectionBehavior: .ordered, matching: .images)
        .onChange(of: pickedItems) { _, items in
            guard !items.isEmpty else { return }
            pickedItems = []
            Task {
                var images: [UIImage] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) { images.append(image) }
                }
                addPages(images)
            }
        }
        .onChange(of: pickedRecipePhotos) { _, items in
            guard !items.isEmpty else { return }
            pickedRecipePhotos = []
            addRecipePhotos(items)
        }
        .onDisappear { photoTask?.cancel() }
        .overlay {
            if let stage = addingStage {
                AddingPageOverlay(stage: stage) { addTask?.cancel() }
            }
        }
        .alert("Couldn't add that page", isPresented: Binding(get: { addPageError != nil }, set: { if !$0 { addPageError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(addPageError ?? "")
        }
        .alert("Couldn't add all the photos", isPresented: $recipePhotoError) {
            Button("OK", role: .cancel) {}
        }
    }

    // MARK: Sections

    private func introBanner(readBy: RecipeImporter.ReadBy) -> some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                Text(draft.emoji).font(.system(size: 30))
                VStack(alignment: .leading, spacing: 4) {
                    Text(summaryLine)
                        .font(.rounded(.headline, .bold))
                        .foregroundStyle(Theme.ink)
                    Text(bannerText(readBy))
                        .font(.rounded(.subheadline))
                        .foregroundStyle(Theme.inkSoft)
                    if case .fallback(let reason) = readBy {
                        Label("AI reading didn't work: \(reason).", systemImage: "exclamationmark.triangle.fill")
                            .font(.rounded(.footnote, .semibold))
                            .foregroundStyle(Theme.cherry)
                            .padding(.top, 2)
                    }
                }
            }
            .padding(.vertical, 4)
            .listRowBackground(draft.palette.tint)
        }
    }

    private func bannerText(_ readBy: RecipeImporter.ReadBy) -> String {
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        switch readBy {
        case .ai:
            return "Read with AI. Give the amounts a quick check, and tap anything to fix it."
        case .website:
            return "Copied from the website's recipe card. Tap anything to fix it."
        case .appleIntelligence:
            return "Tidied up on this \(device) with Apple Intelligence. Tap anything to fix it."
        case .phone:
            return "Read on this \(device). Tap anything to fix it. For much better reading, add an OpenRouter key in Settings."
        case .fallback:
            return "Pinch read this on the \(device) instead. Tap anything to fix it."
        case .typing:
            return "Tap anything to fix it. Swipe a line to delete it, or use its menu to move it."
        }
    }

    private var summaryLine: String {
        let i = draft.ingredientLines.count, s = draft.stepLines.count
        if i == 0 && s == 0 { return "Start with the recipe's name" }
        return "Found \(i) ingredient\(i == 1 ? "" : "s") and \(s) step\(s == 1 ? "" : "s")"
    }

    private var pagesSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Array(draft.photos.enumerated()), id: \.element) { index, name in
                        Button {
                            viewer = PageViewerItem(index: index)
                        } label: {
                            PageThumbnail(name: name, number: index + 1)
                        }
                        .buttonStyle(.plain)
                        .contextMenu { pageMenu(index: index) }
                    }
                    addPageButton
                }
                .padding(.vertical, 6)
            }
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
        } header: {
            editorHeader(draft.photos.isEmpty ? "Pages" : "Pages (\(draft.photos.count))")
        } footer: {
            Text(draft.photos.isEmpty
                 ? "Recipe split across pages? Add each page and Pinch joins them up."
                 : "Touch and hold a page to reorder or remove it.")
        }
    }

    private var addPageButton: some View {
        Menu {
            if DocumentScannerView.isSupported {
                Button { showScanner = true } label: { Label("Scan pages", systemImage: "camera") }
            }
            Button { showPhotoPicker = true } label: { Label("Choose photos", systemImage: "photo.on.rectangle") }
        } label: {
            VStack(spacing: 8) {
                Image(systemName: "plus")
                    .font(.system(size: 22, weight: .bold))
                Text(draft.photos.isEmpty ? "Add a page" : "Add page")
                    .font(.rounded(.caption, .bold))
            }
            .foregroundStyle(Theme.jadeDeep)
            .frame(width: 84, height: 112)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Theme.jadeDeep.opacity(0.5), style: StrokeStyle(lineWidth: 2, dash: [6, 5]))
            )
        }
    }

    @ViewBuilder
    private func pageMenu(index: Int) -> some View {
        if index > 0 {
            Button { draft.photos.swapAt(index, index - 1) } label: { Label("Move earlier", systemImage: "arrow.left") }
        }
        if index < draft.photos.count - 1 {
            Button { draft.photos.swapAt(index, index + 1) } label: { Label("Move later", systemImage: "arrow.right") }
        }
        Button(role: .destructive) {
            draft.photos.remove(at: index)
        } label: {
            Label("Remove page", systemImage: "trash")
        }
    }

    private var coverSection: some View {
        Section {
            HStack(spacing: 16) {
                Button {
                    showEmojiPicker = true
                } label: {
                    ZStack {
                        RecipeCover(recipe: draft, photos: store.photos, cell: 14)
                        EmojiSticker(emoji: draft.emoji, size: 50, tilt: -6)
                    }
                    .frame(width: 84, height: 84)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(alignment: .bottomTrailing) {
                        Image(systemName: "pencil.circle.fill")
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, Theme.jadeDeep)
                            .font(.system(size: 24))
                            .offset(x: 6, y: 6)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Change emoji, currently \(draft.emoji)")

                VStack(alignment: .leading, spacing: 12) {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(CoverPalette.allCases, id: \.self) { palette in
                                Button {
                                    draft.palette = palette
                                } label: {
                                    Circle()
                                        .fill(palette.base)
                                        .frame(width: 30, height: 30)
                                        .overlay(Circle().strokeBorder(Theme.ink, lineWidth: draft.palette == palette ? 3 : 0))
                                        .padding(2)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(palette.displayName)
                                .accessibilityAddTraits(draft.palette == palette ? .isSelected : [])
                            }
                        }
                    }
                    HStack(spacing: 8) {
                        ForEach(CoverPattern.allCases, id: \.self) { pattern in
                            Button {
                                draft.pattern = pattern
                            } label: {
                                CoverArt(palette: draft.palette, pattern: pattern, cell: 9)
                                    .frame(width: 34, height: 34)
                                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(Theme.ink, lineWidth: draft.pattern == pattern ? 3 : 0))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(pattern.displayName)
                            .accessibilityAddTraits(draft.pattern == pattern ? .isSelected : [])
                        }
                    }
                }
            }
            .padding(.vertical, 6)
            if !draft.recipePhotos.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Tap a photo to use it as the banner.")
                        .font(.rounded(.footnote))
                        .foregroundStyle(Theme.inkSoft)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(Array(draft.recipePhotos.enumerated()), id: \.element) { index, name in
                                recipePhotoTile(name, index: index)
                            }
                        }
                    }
                }
                if draft.bannerPhoto != nil {
                    Button("Use patterned cover") { draft.bannerPhoto = nil }
                }
            }
            if photoTask != nil {
                ProgressView("Adding photos…")
            } else if draft.recipePhotos.count < 100 {
                PhotosPicker(selection: $pickedRecipePhotos, maxSelectionCount: min(10, 100 - draft.recipePhotos.count),
                             selectionBehavior: .ordered, matching: .images) {
                    Label("Add recipe photos", systemImage: "photo.on.rectangle")
                }
            }
        } header: {
            editorHeader("Cover")
        }
    }

    private func recipePhotoTile(_ name: String, index: Int) -> some View {
        let selected = draft.bannerPhoto == name
        return Button {
            draft.bannerPhoto = name
        } label: {
            Theme.sunken.overlay {
                if let image = store.photos.thumbnail(named: name) {
                    Image(uiImage: image).resizable().scaledToFill()
                }
            }
            .frame(width: 96, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(alignment: .bottomTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, Theme.jadeDeep)
                        .font(.title3)
                        .padding(4)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Use photo \(index + 1) as banner")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .contextMenu {
            Button("Remove photo", systemImage: "trash", role: .destructive) {
                draft.recipePhotos.removeAll { $0 == name }
                if selected { draft.bannerPhoto = draft.recipePhotos.first }
            }
        }
    }

    private var nameSection: some View {
        Section {
            TextField("Recipe name", text: $draft.title, axis: .vertical)
                .font(.rounded(.title2, .bold))
                .foregroundStyle(Theme.ink)
                .focused($focus, equals: .title)
                .submitLabel(.done)
            TextField("A line or two about it (optional)", text: $draft.summary, axis: .vertical)
                .font(.rounded(.body))
                .lineLimit(1...5)
        } header: {
            editorHeader("Name")
        }
    }

    private var detailsSection: some View {
        Section {
            detailRow("Servings", systemImage: "person.2", text: $draft.servings, prompt: "e.g. 4")
            detailRow("Prep", systemImage: "clock", text: $draft.prepTime, prompt: "e.g. 15 min")
            detailRow("Cook", systemImage: "flame", text: $draft.cookTime, prompt: "e.g. 1 hr")
            detailRow("Total", systemImage: "hourglass", text: $draft.totalTime, prompt: "e.g. 1 hr 15 min")
            detailRow("From", systemImage: "book.closed", text: $draft.source, prompt: "e.g. Grandma's card")
            if !draft.sourceURL.isEmpty || !original.sourceURL.isEmpty {
                HStack(spacing: 12) {
                    Label("Web page", systemImage: "safari")
                        .font(.rounded(.body, .medium))
                        .foregroundStyle(Theme.ink)
                        .frame(width: 118, alignment: .leading)
                    TextField("https://…", text: $draft.sourceURL)
                        .font(.rounded(.body))
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            }
        } header: {
            editorHeader("Details")
        }
    }

    private func detailRow(_ label: String, systemImage: String, text: Binding<String>, prompt: String) -> some View {
        HStack(spacing: 12) {
            Label(label, systemImage: systemImage)
                .font(.rounded(.body, .medium))
                .foregroundStyle(Theme.ink)
                .frame(width: 118, alignment: .leading)
            TextField(prompt, text: text)
                .font(.rounded(.body))
                .multilineTextAlignment(.trailing)
        }
    }

    private var tagsSection: some View {
        Section {
            FlowLayout(spacing: 8) {
                ForEach(draft.tags, id: \.self) { tag in
                    Button {
                        draft.tags.removeAll { $0 == tag }
                    } label: {
                        HStack(spacing: 4) {
                            Text(tag)
                            Image(systemName: "xmark").font(.system(size: 10, weight: .bold))
                        }
                        .font(.rounded(.subheadline, .semibold))
                        .foregroundStyle(draft.palette.deep)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 32)
                        .background(Capsule().fill(draft.palette.tint))
                        .fixedSize()
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Remove tag \(tag)")
                }
                ForEach(tagSuggestions, id: \.self) { tag in
                    Button {
                        draft.tags.append(tag)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "plus").font(.system(size: 10, weight: .bold))
                            Text(tag)
                        }
                        .font(.rounded(.subheadline, .medium))
                        .foregroundStyle(Theme.inkSoft)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 32)
                        .background(Capsule().strokeBorder(Theme.line, lineWidth: 1.5))
                        .fixedSize()
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Add tag \(tag)")
                }
            }
            .padding(.vertical, 4)
            TextField("Add a tag, like Dinner or Holidays", text: $newTag)
                .font(.rounded(.body))
                .focused($focus, equals: .tag)
                .submitLabel(.done)
                .onSubmit(addTag)
        } header: {
            editorHeader("Tags")
        }
    }

    private var tagSuggestions: [String] {
        let common = ["Breakfast", "Lunch", "Dinner", "Dessert", "Baking", "Snack", "Drinks", "Vegetarian", "Holidays", "Family"]
        let pool = store.allTags + common
        var seen = Set(draft.tags)
        var result: [String] = []
        for tag in pool where !seen.contains(tag) {
            seen.insert(tag)
            result.append(tag)
            if result.count == 5 { break }
        }
        return result
    }

    private func addTag() {
        let tag = newTag.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tag.isEmpty else { return }
        let formatted = TextKit.capitalizeFirstLetter(tag)
        if !draft.tags.contains(where: { $0.caseInsensitiveCompare(formatted) == .orderedSame }) {
            draft.tags.append(formatted)
        }
        newTag = ""
    }

    private enum ItemKind { case ingredient, step }

    private func itemsSection(title: String, items: Binding<[RecipeItem]>, kind: ItemKind) -> some View {
        Section {
            ForEach(items) { $item in
                ItemRow(
                    item: $item,
                    number: kind == .step ? stepNumber(for: item.id) : nil,
                    placeholder: kind == .ingredient ? "e.g. 2 cups flour" : "Describe this step",
                    focus: $focus,
                    onSplit: { remainder in insert(after: item.id, text: remainder, in: items) },
                    menu: { rowMenu(item: item, kind: kind, items: items) }
                )
            }
            .onDelete { items.wrappedValue.remove(atOffsets: $0) }
            .onMove { items.wrappedValue.move(fromOffsets: $0, toOffset: $1) }

            HStack(spacing: 16) {
                Button {
                    let item = RecipeItem(text: "")
                    items.wrappedValue.append(item)
                    focus = .item(item.id)
                } label: {
                    Label(kind == .ingredient ? "Add ingredient" : "Add step", systemImage: "plus.circle.fill")
                        .font(.rounded(.body, .semibold))
                }
                .buttonStyle(.borderless)
                Spacer()
                Button {
                    let item = RecipeItem(text: "", isHeader: true)
                    items.wrappedValue.append(item)
                    focus = .item(item.id)
                } label: {
                    Label("Add heading", systemImage: "textformat")
                        .font(.rounded(.subheadline, .medium))
                }
                .buttonStyle(.borderless)
                .foregroundStyle(Theme.inkSoft)
            }
        } header: {
            HStack {
                editorHeader(title)
                Spacer()
                if items.wrappedValue.count > 1 {
                    Button(editMode.isEditing ? "Done" : "Reorder") {
                        withAnimation { editMode = editMode.isEditing ? .inactive : .active }
                    }
                    .font(.rounded(.subheadline, .semibold))
                    .textCase(nil)
                }
            }
        }
    }

    @ViewBuilder
    private func rowMenu(item: RecipeItem, kind: ItemKind, items: Binding<[RecipeItem]>) -> some View {
        Button {
            if let i = items.wrappedValue.firstIndex(where: { $0.id == item.id }) { items.wrappedValue[i].isHeader.toggle() }
        } label: {
            Label(item.isHeader ? "Make it a regular line" : "Make it a heading", systemImage: "textformat")
        }
        Button {
            items.wrappedValue.removeAll { $0.id == item.id }
            var moved = item
            moved.isHeader = false
            if kind == .ingredient { draft.steps.append(moved) } else { draft.ingredients.append(moved) }
        } label: {
            Label(kind == .ingredient ? "Move to steps" : "Move to ingredients", systemImage: "arrow.up.arrow.down")
        }
        if kind == .step, let i = items.wrappedValue.firstIndex(where: { $0.id == item.id }), i > 0 {
            Button {
                let previous = items.wrappedValue[i - 1]
                items.wrappedValue[i - 1].text = RecipeParser.join(previous.text, item.text)
                items.wrappedValue.remove(at: i)
            } label: {
                Label("Join with step above", systemImage: "arrow.up.to.line")
            }
        }
        Button(role: .destructive) {
            items.wrappedValue.removeAll { $0.id == item.id }
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    private func stepNumber(for id: String) -> Int? {
        var n = 0
        for step in draft.steps {
            if !step.isHeader { n += 1 }
            if step.id == id { return step.isHeader ? nil : n }
        }
        return nil
    }

    private func insert(after id: String, text: String, in items: Binding<[RecipeItem]>) {
        guard let index = items.wrappedValue.firstIndex(where: { $0.id == id }) else { return }
        let item = RecipeItem(text: text)
        items.wrappedValue.insert(item, at: index + 1)
        focus = .item(item.id)
    }

    private var notesSection: some View {
        Section {
            TextField("Tips, swaps, who loved it", text: $draft.notes, axis: .vertical)
                .font(.rounded(.body))
                .lineLimit(3...12)
        } header: {
            editorHeader("Notes")
        }
    }

    private var scannedTextSection: some View {
        Section {
            DisclosureGroup(draft.photos.isEmpty && !draft.sourceURL.isEmpty ? "Show the text from the page" : "Show the scanned text") {
                Text(draft.rawText)
                    .font(.system(.callout, design: .monospaced))
                    .foregroundStyle(Theme.inkSoft)
                    .textSelection(.enabled)
                    .padding(.vertical, 6)
            }
            .font(.rounded(.body, .medium))
        } footer: {
            Text(draft.photos.isEmpty && !draft.sourceURL.isEmpty
                 ? "The recipe as the website lists it, in case something landed in the wrong place. Touch and hold to copy."
                 : "Everything Pinch read from your pages, in case something landed in the wrong place. Touch and hold to copy.")
        }
    }

    private func editorHeader(_ title: String) -> some View {
        Text(title)
            .font(.rounded(.subheadline, .bold))
            .foregroundStyle(Theme.inkSoft)
            .textCase(nil)
    }

    // MARK: Actions

    private var hasChanges: Bool { draft != original || mode.isNew }

    private func addRecipePhotos(_ items: [PhotosPickerItem]) {
        let photos = store.photos
        photoTask = Task {
            var names: [String] = []
            defer { photoTask = nil }
            for item in items.prefix(100 - draft.recipePhotos.count) {
                if Task.isCancelled { break }
                guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
                if let name = try? await Task.detached(operation: { try photos.importPhoto(data) }).value {
                    names.append(name)
                }
            }
            guard !Task.isCancelled else { names.forEach(photos.delete); return }
            if draft.recipePhotos.isEmpty { draft.bannerPhoto = names.first }
            draft.recipePhotos += names
            photosAddedHere += names
            recipePhotoError = names.count < items.count
        }
    }

    private func addPages(_ images: [UIImage]) {
        guard !images.isEmpty else { return }
        let before = Set(draft.photos)
        addTask = Task {
            do {
                var working = draft
                try await RecipeImporter.appendPages(images, to: &working, photos: store.photos, ai: server.aiClient) { stage in
                    addingStage = stage
                }
                photosAddedHere += working.photos.filter { !before.contains($0) }
                withAnimation { draft = working }
                Haptics.success()
            } catch is CancellationError {
                // Called off from the overlay; the new pages were already cleaned up.
            } catch {
                addPageError = error.localizedDescription
            }
            addingStage = nil
            addTask = nil
        }
    }

    private func save() {
        addTag()
        var recipe = draft
        recipe.title = recipe.title.trimmingCharacters(in: .whitespacesAndNewlines)
        recipe.summary = recipe.summary.trimmingCharacters(in: .whitespacesAndNewlines)
        recipe.notes = recipe.notes.trimmingCharacters(in: .whitespacesAndNewlines)
        recipe.servings = recipe.servings.trimmingCharacters(in: .whitespacesAndNewlines)
        recipe.sourceURL = recipe.sourceURL.trimmingCharacters(in: .whitespacesAndNewlines)
        recipe.prepTime = DurationParser.normalized(recipe.prepTime)
        recipe.cookTime = DurationParser.normalized(recipe.cookTime)
        recipe.totalTime = DurationParser.normalized(recipe.totalTime)
        recipe.ingredients = recipe.ingredients.compactMap(cleaned)
        recipe.steps = recipe.steps.compactMap(cleaned)
        if recipe.emoji.trimmingCharacters(in: .whitespaces).isEmpty { recipe.emoji = "🍽️" }
        let removed = Set(original.photoFiles).union(photosAddedHere).subtracting(recipe.photoFiles)
        onSave(recipe)
        removed.forEach(store.photos.delete)
    }

    private func cleaned(_ item: RecipeItem) -> RecipeItem? {
        var copy = item
        copy.text = item.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return copy.text.isEmpty ? nil : copy
    }

    private func attemptCancel() {
        let pristineNew = mode.isNew && draft.title.isEmpty && draft.ingredients.isEmpty && draft.steps.isEmpty && draft.photoFiles.isEmpty
        if pristineNew || (!mode.isNew && draft == original) {
            cancel()
        } else {
            confirmDiscard = true
        }
    }

    private func cancel() {
        photosAddedHere.forEach(store.photos.delete)
        var abandoned = original
        if !mode.isNew { abandoned.photos = []; abandoned.recipePhotos = []; abandoned.bannerPhoto = nil }
        onCancel(abandoned)
    }
}

/// One editable ingredient or step. Pressing return starts a new line below.
private struct ItemRow<MenuContent: View>: View {
    @Binding var item: RecipeItem
    let number: Int?
    let placeholder: String
    var focus: FocusState<RecipeEditorView.FocusField?>.Binding
    let onSplit: (String) -> Void
    @ViewBuilder let menu: () -> MenuContent

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Group {
                if item.isHeader {
                    Image(systemName: "textformat")
                } else if let number {
                    Text("\(number)").monospacedDigit()
                } else {
                    Image(systemName: "circle.fill").font(.system(size: 6))
                }
            }
            .font(.rounded(.subheadline, .bold))
            .foregroundStyle(Theme.jadeDeep)
            .frame(width: 20)

            TextField(item.isHeader ? "Heading, like For the sauce" : placeholder, text: $item.text, axis: .vertical)
                .font(item.isHeader ? .rounded(.body, .bold) : .rounded(.body))
                .focused(focus, equals: .item(item.id))
                .onChange(of: item.text) { _, newValue in
                    guard let newline = newValue.firstIndex(of: "\n") else { return }
                    let head = String(newValue[..<newline])
                    let tail = String(newValue[newValue.index(after: newline)...]).trimmingCharacters(in: .newlines)
                    item.text = head
                    onSplit(tail)
                }

            Menu(content: menu) {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Theme.inkSoft)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("More for this line")
        }
    }
}

private struct PageThumbnail: View {
    let name: String
    let number: Int
    @Environment(RecipeStore.self) private var store

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            if let image = store.photos.thumbnail(named: name) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Theme.sunken
            }
            Text("\(number)")
                .font(.rounded(.caption, .heavy))
                .foregroundStyle(Theme.onJade)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Theme.jade))
                .padding(6)
        }
        .frame(width: 84, height: 112)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityLabel("Page \(number)")
    }
}

private struct AddingPageOverlay: View {
    let stage: RecipeImporter.Stage
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.25).ignoresSafeArea()
            VStack(spacing: 14) {
                ProgressView().controlSize(.large).tint(Theme.jadeDeep)
                Text(label)
                    .font(.rounded(.headline, .bold))
                    .foregroundStyle(Theme.ink)
                if case .aiReading = stage {
                    Button("Cancel", action: onCancel)
                        .font(.rounded(.subheadline, .semibold))
                        .foregroundStyle(Theme.jadeDeep)
                }
            }
            .padding(28)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.surface))
        }
    }

    private var label: String {
        switch stage {
        case .reading(let page, let total) where total > 1: return "Reading page \(page) of \(total)"
        case .aiReading(.pages(let count)): return count == 1 ? "AI is reading the new page" : "AI is reading the new pages"
        default: return "Reading the new page"
        }
    }
}

/// Emoji for covers, grouped loosely by course, plus a field for any emoji at all.
struct EmojiPickerSheet: View {
    @Binding var selection: String
    @Environment(\.dismiss) private var dismiss
    @State private var custom = ""

    static let choices = [
        "🍝", "🍕", "🍲", "🥗", "🍛", "🍜", "🌮", "🍔", "🥪", "🍗", "🥩", "🍖", "🐟", "🍤", "🍣", "🥟",
        "🍳", "🥞", "🥣", "🍞", "🥐", "🥯", "🧀", "🥔", "🌽", "🥕", "🥦", "🍄", "🥑", "🍅", "🌶️", "🧄",
        "🍰", "🎂", "🧁", "🥧", "🍪", "🍫", "🍩", "🍨", "🍮", "🍎", "🍋", "🍓", "🫐", "🍑", "🍌", "🎃",
        "☕️", "🍵", "🥤", "🍹", "🍷", "🫙", "🥢", "🍽️", "🧑‍🍳", "❤️", "⭐️", "🎄",
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 52), spacing: 8)], spacing: 8) {
                    ForEach(Self.choices, id: \.self) { emoji in
                        Button {
                            selection = emoji
                            dismiss()
                        } label: {
                            Text(emoji)
                                .font(.system(size: 32))
                                .frame(width: 52, height: 52)
                                .background(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .fill(selection == emoji ? Theme.jade.opacity(0.35) : Theme.sunken)
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(20)
                HStack {
                    TextField("Or type any emoji", text: $custom)
                        .font(.rounded(.body))
                        .padding(.horizontal, 14)
                        .frame(minHeight: 46)
                        .background(Capsule().fill(Theme.sunken))
                    Button("Use") {
                        if let first = custom.first {
                            selection = String(first)
                            dismiss()
                        }
                    }
                    .buttonStyle(SoftButtonStyle())
                    .disabled(custom.isEmpty)
                }
                .padding(.horizontal, 20)
            }
            .background(Theme.canvas.ignoresSafeArea())
            .navigationTitle("Pick an emoji")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
