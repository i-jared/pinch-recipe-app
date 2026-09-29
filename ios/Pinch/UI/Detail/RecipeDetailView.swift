import SwiftUI

struct RecipeDetailView: View {
    let recipeID: String

    @Environment(RecipeStore.self) private var store
    @Environment(CookingSession.self) private var session
    @Environment(TimerCenter.self) private var timers
    @Environment(\.dismiss) private var dismiss
    @State private var showEditor = false
    @State private var showCookMode = false
    @State private var showShare = false
    @State private var confirmDelete = false
    @State private var viewer: PageViewerItem?
    @State private var keepAwake = false
    @State private var scrolledPastHero = false

    var body: some View {
        if let recipe = store.recipe(recipeID) {
            content(recipe)
        } else {
            ContentUnavailableView("This recipe was deleted", systemImage: "fork.knife", description: Text("It may have been removed on another device."))
                .background(Theme.canvas.ignoresSafeArea())
        }
    }

    private func content(_ recipe: Recipe) -> some View {
        GeometryReader { outer in
            scrollContent(recipe, topInset: outer.safeAreaInsets.top)
        }
        .background(Theme.canvas.ignoresSafeArea())
        .navigationTitle(scrolledPastHero ? recipe.displayTitle : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar { toolbar(recipe) }
        .sheet(isPresented: $showEditor) {
            NavigationStack {
                RecipeEditorView(recipe: recipe, mode: .edit, onSave: { edited in
                    store.save(edited)
                    showEditor = false
                }, onCancel: { _ in showEditor = false })
            }
            .tint(Theme.jadeDeep)
        }
        .fullScreenCover(isPresented: $showCookMode) {
            CookModeView(recipe: recipe)
        }
        .sheet(isPresented: $showShare) {
            ShareRecipeSheet(recipe: recipe, scale: session.scale(for: recipe.id))
                .tint(Theme.jadeDeep)
        }
        .fullScreenCover(item: $viewer) { item in
            PageViewer(photos: recipe.photos, startIndex: item.index)
        }
        .confirmationDialog("Delete \(recipe.displayTitle)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete recipe", role: .destructive) {
                dismiss()
                store.delete(recipe.id)
            }
        } message: {
            Text("This removes it from this device and from Google Drive if you've connected it.")
        }
        .onChange(of: keepAwake) { _, awake in UIApplication.shared.isIdleTimerDisabled = awake }
        .onDisappear { if keepAwake { UIApplication.shared.isIdleTimerDisabled = false } }
    }

    private func scrollContent(_ recipe: Recipe, topInset: CGFloat) -> some View {
        ScrollView {
            VStack(spacing: 0) {
                StretchyHero(recipe: recipe)
                VStack(alignment: .leading, spacing: 30) {
                    TitleBlock(recipe: recipe)
                    IngredientsCard(recipe: recipe)
                    StepsList(recipe: recipe)
                    if !recipe.notes.isEmpty { NotesCard(notes: recipe.notes) }
                    if !recipe.photos.isEmpty { pages(recipe) }
                    footer(recipe)
                }
                .padding(.horizontal, 20)
                .padding(.top, 30)
                .padding(.bottom, 150)
                .frame(maxWidth: 720, alignment: .leading)
                .frame(maxWidth: .infinity)
                .background(
                    UnevenRoundedRectangle(topLeadingRadius: 32, topTrailingRadius: 32, style: .continuous)
                        .fill(Theme.canvas)
                )
                .padding(.top, -32)
            }
        }
        .ignoresSafeArea(edges: .top)
        .onScrollGeometryChange(for: Bool.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top > 230
        } action: { _, past in
            withAnimation(.easeInOut(duration: 0.2)) { scrolledPastHero = past }
        }
        .overlay {
            // Our own bar background, so the title never collides with the buttons once
            // the cover has scrolled away (the system bar stays transparent over the cover).
            VStack(spacing: 0) {
                Theme.canvas
                    .frame(height: topInset)
                    .overlay(alignment: .bottom) { Rectangle().fill(Theme.line).frame(height: 0.5) }
                Spacer(minLength: 0)
            }
            .ignoresSafeArea()
            .opacity(scrolledPastHero ? 1 : 0)
            .allowsHitTesting(false)
        }
        .safeAreaInset(edge: .bottom) { bottomBar(recipe) }
    }

    @ToolbarContentBuilder
    private func toolbar(_ recipe: Recipe) -> some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button {
                store.toggleFavorite(recipe.id)
                Haptics.tap()
            } label: {
                Image(systemName: recipe.isFavorite ? "heart.fill" : "heart")
                    .foregroundStyle(recipe.isFavorite ? Theme.cherry : Theme.ink)
                    .symbolEffect(.bounce, value: recipe.isFavorite)
            }
            .accessibilityLabel(recipe.isFavorite ? "Remove from favorites" : "Add to favorites")

            Button {
                showShare = true
            } label: {
                Image(systemName: "square.and.arrow.up")
                    .foregroundStyle(Theme.ink)
            }
            .accessibilityLabel("Share recipe card")

            Menu {
                Button { showEditor = true } label: { Label("Edit recipe", systemImage: "pencil") }
                Toggle(isOn: $keepAwake) { Label("Keep screen on", systemImage: "sun.max") }
                Button { store.markCooked(recipe.id); Haptics.success() } label: { Label("Mark as cooked today", systemImage: "checkmark.seal") }
                if session.hasProgress(in: recipe.id) {
                    Button { withAnimation { session.reset(recipe.id) } } label: { Label("Clear checkmarks", systemImage: "arrow.counterclockwise") }
                }
                Divider()
                Button(role: .destructive) { confirmDelete = true } label: { Label("Delete recipe", systemImage: "trash") }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(Theme.ink)
            }
            .accessibilityLabel("More")
        }
    }

    private func bottomBar(_ recipe: Recipe) -> some View {
        VStack(spacing: 10) {
            TimerDock(recipeID: recipe.id)
            if !recipe.stepLines.isEmpty {
                Button {
                    showCookMode = true
                } label: {
                    Label(session.doneCount(in: recipe.id) > 0 ? "Keep cooking" : "Start cooking", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .shadow(color: Theme.jadeDeep.opacity(0.25), radius: 14, y: 6)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 6)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .background(alignment: .bottom) { BottomFade() }
    }

    private func pages(_ recipe: Recipe) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeading(recipe.photos.count == 1 ? "Original page" : "Original pages")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Array(recipe.photos.enumerated()), id: \.element) { index, name in
                        Button {
                            viewer = PageViewerItem(index: index)
                        } label: {
                            Group {
                                if let image = store.photos.thumbnail(named: name, maxPixelSize: 420) {
                                    Image(uiImage: image).resizable().scaledToFill()
                                } else {
                                    Theme.sunken
                                }
                            }
                            .frame(width: 110, height: 146)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.line, lineWidth: 1))
                        }
                        .buttonStyle(PressableCardStyle())
                        .accessibilityLabel("Open page \(index + 1)")
                    }
                }
            }
            .scrollClipDisabled()
        }
    }

    private func footer(_ recipe: Recipe) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Added \(recipe.createdAt.formatted(date: .long, time: .omitted))")
            if let cooked = recipe.lastCookedAt {
                Text("Last cooked \(cooked.formatted(.relative(presentation: .named)))")
            }
        }
        .font(.rounded(.footnote))
        .foregroundStyle(Theme.inkSoft)
    }
}

// MARK: - Hero

private struct StretchyHero: View {
    let recipe: Recipe
    private let height: CGFloat = 300

    var body: some View {
        GeometryReader { geo in
            let pull = max(0, geo.frame(in: .scrollView).minY)
            ZStack(alignment: .bottomLeading) {
                CoverArt(palette: recipe.palette, pattern: recipe.pattern, cell: 28)
                EmojiSticker(emoji: recipe.emoji, size: 96, tilt: -7)
                    .padding(.leading, 24)
                    .padding(.bottom, 52)
            }
            .frame(width: geo.size.width, height: height + pull)
            .offset(y: -pull)
        }
        .frame(height: height)
    }
}

// MARK: - Title

private struct TitleBlock: View {
    let recipe: Recipe

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(recipe.displayTitle)
                .font(.system(.largeTitle, design: .rounded, weight: .bold))
                .foregroundStyle(Theme.ink)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)

            if !recipe.summary.isEmpty {
                Text(recipe.summary)
                    .font(.rounded(.body))
                    .foregroundStyle(Theme.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }

            let pills = metaPills
            let link = URL(string: recipe.sourceURL).flatMap { $0.scheme?.hasPrefix("http") == true ? $0 : nil }
            if !pills.isEmpty || !recipe.source.isEmpty || link != nil {
                FlowLayout(spacing: 8) {
                    ForEach(pills, id: \.text) { pill in
                        MetaPill(systemImage: pill.icon, text: pill.text)
                    }
                    if let link {
                        Link(destination: link) {
                            MetaPill(
                                systemImage: "safari",
                                text: recipe.source.isEmpty ? RecipeImporter.displayHost(link) : recipe.source,
                                tint: Theme.jadeDeep
                            )
                        }
                        .accessibilityHint("Opens the recipe's web page")
                    } else if !recipe.source.isEmpty {
                        MetaPill(systemImage: "book.closed", text: recipe.source, tint: Theme.inkSoft)
                    }
                }
            }

            if !recipe.tags.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(recipe.tags, id: \.self) { tag in
                        Text(tag)
                            .font(.rounded(.footnote, .bold))
                            .foregroundStyle(recipe.palette.deep)
                            .padding(.horizontal, 10)
                            .frame(minHeight: 26)
                            .background(Capsule().fill(recipe.palette.tint))
                    }
                }
            }
        }
    }

    private var metaPills: [(icon: String, text: String)] {
        var pills: [(String, String)] = []
        if !recipe.prepTime.isEmpty { pills.append(("clock", "Prep \(recipe.prepTime)")) }
        if !recipe.cookTime.isEmpty { pills.append(("flame", "Cook \(recipe.cookTime)")) }
        if !recipe.totalTime.isEmpty { pills.append(("hourglass", "Total \(recipe.totalTime)")) }
        if let servings = recipe.servingsLabel { pills.append(("person.2", servings)) }
        return pills
    }
}

// MARK: - Ingredients

struct IngredientsCard: View {
    let recipe: Recipe
    @Environment(CookingSession.self) private var session

    var body: some View {
        let scale = session.scale(for: recipe.id)
        VStack(alignment: .leading, spacing: 14) {
            SectionHeading("Ingredients", trailing: AnyView(
                ServingsScaler(baseServings: recipe.servingsCount, noun: recipe.servingsNoun, scale: Binding(
                    get: { session.scale(for: recipe.id) },
                    set: { session.setScale($0, for: recipe.id) }
                ))
            ))

            if recipe.ingredients.isEmpty {
                Text("No ingredients yet. Tap Edit to add some.")
                    .font(.rounded(.body))
                    .foregroundStyle(Theme.inkSoft)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(recipe.ingredients.enumerated()), id: \.element.id) { index, item in
                        if item.isHeader {
                            Text(item.text)
                                .font(.rounded(.headline, .bold))
                                .foregroundStyle(recipe.palette.deep)
                                .padding(.top, index == 0 ? 4 : 18)
                                .padding(.bottom, 4)
                                .accessibilityAddTraits(.isHeader)
                        } else {
                            IngredientRow(
                                text: IngredientParser.scale(item.text, by: scale),
                                accent: recipe.palette.deep,
                                isChecked: session.isChecked(item.id, in: recipe.id)
                            ) {
                                withAnimation(.snappy(duration: 0.2)) { session.toggleChecked(item.id, in: recipe.id) }
                                Haptics.tap()
                            }
                            if index < recipe.ingredients.count - 1 && !recipe.ingredients[index + 1].isHeader {
                                Rectangle().fill(Theme.line.opacity(0.7)).frame(height: 1).padding(.leading, 44)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.surface))
            }
        }
    }
}

struct IngredientRow: View {
    let text: String
    let accent: Color
    let isChecked: Bool
    let toggle: () -> Void

    var body: some View {
        Button(action: toggle) {
            HStack(alignment: .firstTextBaseline, spacing: 14) {
                Image(systemName: isChecked ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(isChecked ? Theme.jadeDeep : Theme.line)
                    .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 6 }
                Text(RichText.ingredient(text, accent: accent, dimmed: isChecked))
                    .font(.system(.body))
                    .strikethrough(isChecked, color: Theme.inkSoft)
                    .foregroundStyle(isChecked ? Theme.inkSoft : Theme.ink)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(text)
        .accessibilityValue(isChecked ? "Checked" : "")
        .accessibilityHint("Double-tap to check off")
    }
}

/// "4 servings" with − and +, or "Double batch" when the recipe doesn't say how many it serves.
struct ServingsScaler: View {
    let baseServings: Double?
    var noun: String? = nil
    @Binding var scale: Double

    private static let multipliers: [Double] = [0.5, 1, 1.5, 2, 3, 4]

    private var usesServings: Bool {
        guard let base = baseServings else { return false }
        return base >= 1 && base <= 16
    }

    var body: some View {
        HStack(spacing: 0) {
            stepButton("minus", enabled: canDecrease) { change(-1) }
                .accessibilityLabel(usesServings ? "Fewer servings" : "Smaller batch")
            Text(label)
                .font(.rounded(.subheadline, .bold))
                .foregroundStyle(scale == 1 ? Theme.ink : Theme.jadeDeep)
                .contentTransition(.numericText())
                .frame(minWidth: 92)
                .lineLimit(1)
            stepButton("plus", enabled: canIncrease) { change(1) }
                .accessibilityLabel(usesServings ? "More servings" : "Bigger batch")
        }
        .background(Capsule().fill(Theme.sunken))
        .animation(.snappy, value: scale)
    }

    private func stepButton(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Theme.ink)
                .frame(width: 40, height: 38)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
    }

    private var servings: Int { Int(((baseServings ?? 1) * scale).rounded()) }

    private var label: String {
        if usesServings {
            if let noun { return servings == 1 ? "1 \(TextKit.singular(noun))" : "\(servings) \(noun)" }
            return servings == 1 ? "1 serving" : "\(servings) servings"
        }
        switch scale {
        case 0.5: return "Half batch"
        case 1: return "Full batch"
        case 1.5: return "1½ batches"
        case 2: return "Double"
        case 3: return "Triple"
        default: return "\(IngredientParser.format(scale, decimal: false))× batch"
        }
    }

    private var canDecrease: Bool { usesServings ? servings > 1 : scale > Self.multipliers.first! }
    private var canIncrease: Bool { usesServings ? servings < 99 : scale < Self.multipliers.last! }

    private func change(_ direction: Int) {
        Haptics.tap()
        if usesServings, let base = baseServings {
            scale = Double(max(1, servings + direction)) / base
        } else {
            let index = Self.multipliers.firstIndex(where: { $0 >= scale - 0.001 }) ?? 1
            let next = min(max(0, index + direction), Self.multipliers.count - 1)
            scale = Self.multipliers[next]
        }
    }
}

// MARK: - Steps

private struct StepsList: View {
    let recipe: Recipe
    @Environment(CookingSession.self) private var session
    @Environment(TimerCenter.self) private var timers

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeading("Steps", trailing: progress.map { AnyView($0) })
            if recipe.steps.isEmpty {
                Text("No steps yet. Tap Edit to add some.")
                    .font(.rounded(.body))
                    .foregroundStyle(Theme.inkSoft)
            }
            ForEach(numberedSteps, id: \.item.id) { entry in
                if entry.item.isHeader {
                    Text(entry.item.text)
                        .font(.rounded(.headline, .bold))
                        .foregroundStyle(recipe.palette.deep)
                        .padding(.top, 6)
                        .accessibilityAddTraits(.isHeader)
                } else {
                    StepCard(
                        number: entry.number,
                        text: entry.item.text,
                        palette: recipe.palette,
                        isDone: session.isDone(entry.item.id, in: recipe.id),
                        onToggle: {
                            withAnimation(.snappy(duration: 0.2)) { session.toggleDone(entry.item.id, in: recipe.id) }
                            Haptics.tap()
                        },
                        onTimer: { match in
                            timers.start(seconds: match.seconds, label: StepText.timerLabel(entry.item.text, number: entry.number), recipe: recipe)
                        }
                    )
                }
            }
        }
    }

    private var numberedSteps: [(item: RecipeItem, number: Int)] {
        var n = 0
        return recipe.steps.map { item in
            if !item.isHeader { n += 1 }
            return (item, n)
        }
    }

    private var progress: Text? {
        let done = session.doneCount(in: recipe.id)
        guard done > 0 else { return nil }
        return Text("\(done) of \(recipe.stepLines.count) done")
            .font(.rounded(.subheadline, .semibold))
            .foregroundStyle(Theme.jadeDeep)
    }
}

struct StepCard: View {
    let number: Int
    let text: String
    let palette: CoverPalette
    let isDone: Bool
    let onToggle: () -> Void
    let onTimer: (DurationParser.Match) -> Void

    var body: some View {
        let durations = DurationParser.matches(in: text)
        HStack(alignment: .top, spacing: 14) {
            Button(action: onToggle) {
                ZStack {
                    Circle().fill(isDone ? Theme.sunken : palette.base)
                    if isDone {
                        Image(systemName: "checkmark")
                            .font(.system(size: 15, weight: .heavy))
                            .foregroundStyle(Theme.jadeDeep)
                    } else {
                        Text("\(number)")
                            .font(.rounded(.headline, .heavy))
                            .foregroundStyle(palette.onBase)
                    }
                }
                .frame(width: 38, height: 38)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isDone ? "Step \(number), done" : "Mark step \(number) done")

            VStack(alignment: .leading, spacing: 12) {
                Text(RichText.step(text))
                    .font(.system(.body))
                    .lineSpacing(4)
                    .foregroundStyle(isDone ? Theme.inkSoft : Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)
                if !durations.isEmpty {
                    FlowLayout(spacing: 8) {
                        ForEach(Array(durations.enumerated()), id: \.offset) { _, match in
                            Button {
                                onTimer(match)
                            } label: {
                                Label("\(match.label) timer", systemImage: "timer")
                                    .font(.rounded(.subheadline, .bold))
                                    .foregroundStyle(Theme.ink)
                                    .padding(.horizontal, 12)
                                    .frame(minHeight: 36)
                                    .background(Capsule().fill(Theme.butter))
                            }
                            .buttonStyle(PressableCardStyle())
                            .accessibilityLabel("Start a \(match.label) timer")
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture(perform: onToggle)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Theme.surface))
        .opacity(isDone ? 0.75 : 1)
    }
}

struct NotesCard: View {
    let notes: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Notes", systemImage: "pencil.and.scribble")
                .font(.rounded(.headline, .bold))
            Text(notes)
                .font(.rounded(.body))
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Theme.noteInk)
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Theme.note))
    }
}

// MARK: - Rich text

enum RichText {
    /// Bolds the quantity and unit so they're easy to find mid-recipe.
    static func ingredient(_ text: String, accent: Color, dimmed: Bool, boldFont: Font = .system(.body, weight: .bold)) -> AttributedString {
        guard let span = IngredientParser.highlightSpan(text) else { return AttributedString(text) }
        let chars = Array(text)
        let start = min(span.start, chars.count), end = min(span.end, chars.count)
        var head = AttributedString(String(chars[..<start]))
        var quantity = AttributedString(String(chars[start..<end]))
        quantity.font = boldFont
        if !dimmed { quantity.foregroundColor = accent }
        let tail = AttributedString(String(chars[end...]))
        head.append(quantity)
        head.append(tail)
        return head
    }

    private static let temperature = TextKit.regex(#"\b\d{2,3}\s?°\s?[FC]?\b|\b\d{2,3}\s?degrees(?:\s?[FC]\b)?|\b\d{2,3}\s?°"#)

    /// Bolds durations and oven temperatures in a step.
    static func step(_ text: String, style: Font.TextStyle = .body) -> AttributedString {
        step(text, font: .system(style, weight: .bold))
    }

    static func step(_ text: String, font boldFont: Font) -> AttributedString {
        var ranges = DurationParser.matches(in: text).map { NSRange(location: $0.location, length: $0.length) }
        ranges += TextKit.matches(temperature, in: text).map(\.range)
        ranges.sort { $0.location < $1.location }
        let ns = text as NSString
        var result = AttributedString()
        var cursor = 0
        for range in ranges where range.location >= cursor {
            result.append(AttributedString(ns.substring(with: NSRange(location: cursor, length: range.location - cursor))))
            var bold = AttributedString(ns.substring(with: range))
            bold.font = boldFont
            result.append(bold)
            cursor = range.location + range.length
        }
        result.append(AttributedString(ns.substring(from: cursor)))
        return result
    }
}

enum StepText {
    /// A short name for a timer from the start of the step: "Simmer gently".
    private static let trailingFiller: Set<String> = ["in", "the", "a", "an", "for", "to", "with", "of", "and", "or", "on", "into", "until", "about", "over", "at"]

    static func timerLabel(_ text: String, number: Int) -> String {
        let cut = text.firstIndex(where: { ",.;:(".contains($0) }) ?? text.endIndex
        var words = String(text[..<cut]).split(separator: " ").map(String.init)
        if words.joined(separator: " ").count > 26 { words = Array(words.prefix(4)) }
        while let last = words.last, trailingFiller.contains(last.lowercased()) || last.first?.isNumber == true { words.removeLast() }
        let phrase = words.joined(separator: " ")
        return phrase.isEmpty ? "Step \(number)" : phrase
    }
}
