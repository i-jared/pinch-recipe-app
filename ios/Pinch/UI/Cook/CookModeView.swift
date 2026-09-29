import SwiftUI

/// One step at a time, in big type, with the screen kept awake.
struct CookModeView: View {
    let recipe: Recipe

    @Environment(\.dismiss) private var dismiss
    @Environment(CookingSession.self) private var session
    @Environment(TimerCenter.self) private var timers
    @Environment(RecipeStore.self) private var store
    @State private var index = 0
    @State private var showIngredients = false

    private struct Entry: Identifiable {
        let item: RecipeItem
        let number: Int
        let heading: String?
        var id: String { item.id }
    }

    private var entries: [Entry] {
        var result: [Entry] = []
        var heading: String?
        var n = 0
        for item in recipe.steps {
            if item.isHeader {
                heading = item.text
            } else {
                n += 1
                result.append(Entry(item: item, number: n, heading: heading))
                heading = nil
            }
        }
        return result
    }

    var body: some View {
        let entries = entries
        VStack(spacing: 0) {
            topBar(total: entries.count)
            progress(total: entries.count)
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
            TabView(selection: $index) {
                ForEach(Array(entries.enumerated()), id: \.element.id) { i, entry in
                    CookStepPage(recipe: recipe, entry: entry.item, number: entry.number, heading: entry.heading)
                        .tag(i)
                }
                FinishedPage(recipe: recipe) {
                    store.markCooked(recipe.id)
                    session.reset(recipe.id)
                    dismiss()
                }
                .tag(entries.count)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.snappy, value: index)

            VStack(spacing: 10) {
                TimerDock(recipeID: recipe.id)
                if index < entries.count { controls(total: entries.count) }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
        }
        .background(recipe.palette.tint.ignoresSafeArea())
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            index = min(session.currentStep(for: recipe.id), entries.count)
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: index) { old, new in
            session.setCurrentStep(new, for: recipe.id)
            // Moving on means the previous step is done.
            if new > old, old < entries.count { session.setDone(entries[old].item.id, in: recipe.id, true) }
            Haptics.tap()
        }
        .sheet(isPresented: $showIngredients) {
            CookIngredientsSheet(recipe: recipe)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    private func topBar(total: Int) -> some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Theme.ink)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(Theme.surface))
            }
            .accessibilityLabel("Close cook mode")
            Spacer()
            VStack(spacing: 0) {
                Text(index < total ? "Step \(index + 1) of \(total)" : "All done")
                    .font(.rounded(.headline, .bold))
                    .foregroundStyle(Theme.ink)
                    .contentTransition(.numericText())
                Text(recipe.displayTitle)
                    .font(.rounded(.caption, .medium))
                    .foregroundStyle(Theme.inkSoft)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                showIngredients = true
            } label: {
                Image(systemName: "list.bullet")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Theme.ink)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(Theme.surface))
            }
            .accessibilityLabel("All ingredients")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
    }

    private func progress(total: Int) -> some View {
        HStack(spacing: 4) {
            ForEach(0..<max(total, 1), id: \.self) { i in
                Capsule()
                    .fill(i <= index ? recipe.palette.base : Theme.ink.opacity(0.1))
                    .frame(height: 6)
            }
        }
        .animation(.snappy, value: index)
        .accessibilityHidden(true)
    }

    private func controls(total: Int) -> some View {
        HStack(spacing: 12) {
            Button {
                index = max(0, index - 1)
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Theme.ink)
                    .frame(width: 64, height: 64)
                    .background(Circle().fill(Theme.surface))
            }
            .disabled(index == 0)
            .opacity(index == 0 ? 0.4 : 1)
            .accessibilityLabel("Previous step")

            Button {
                index = min(total, index + 1)
            } label: {
                Label(index == total - 1 ? "Finish" : "Next step", systemImage: index == total - 1 ? "checkmark" : "chevron.right")
                    .labelStyle(TrailingIconLabelStyle())
                    .font(.rounded(.title3, .bold))
                    .frame(maxWidth: .infinity, minHeight: 64)
            }
            .buttonStyle(PrimaryButtonStyle())
        }
    }
}

private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) {
            configuration.title
            configuration.icon
        }
    }
}

private struct CookStepPage: View {
    let recipe: Recipe
    let entry: RecipeItem
    let number: Int
    let heading: String?

    @Environment(TimerCenter.self) private var timers
    @Environment(CookingSession.self) private var session

    var body: some View {
        let durations = DurationParser.matches(in: entry.text)
        let uses = StepIngredients.matching(entry.text, in: recipe)
        let scale = session.scale(for: recipe.id)
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                if let heading {
                    Text(heading)
                        .font(.rounded(.headline, .bold))
                        .foregroundStyle(recipe.palette.deep)
                }
                Text(RichText.step(entry.text, style: .title))
                    .font(.system(.title, weight: .medium))
                    .lineSpacing(6)
                    .foregroundStyle(Theme.ink)
                    .fixedSize(horizontal: false, vertical: true)

                if !durations.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(Array(durations.enumerated()), id: \.offset) { _, match in
                            Button {
                                timers.start(seconds: match.seconds, label: StepText.timerLabel(entry.text, number: number), recipe: recipe)
                            } label: {
                                Label("Start \(match.label) timer", systemImage: "timer")
                                    .font(.rounded(.title3, .bold))
                                    .foregroundStyle(Theme.ink)
                                    .padding(.horizontal, 20)
                                    .frame(minHeight: 56)
                                    .background(Capsule().fill(Theme.butter))
                            }
                            .buttonStyle(PressableCardStyle())
                        }
                    }
                }

                if !uses.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("For this step")
                            .font(.rounded(.headline, .bold))
                            .foregroundStyle(Theme.inkSoft)
                            .padding(.bottom, 4)
                        ForEach(uses) { item in
                            IngredientRow(
                                text: IngredientParser.scale(item.text, by: scale),
                                accent: recipe.palette.deep,
                                isChecked: session.isChecked(item.id, in: recipe.id)
                            ) {
                                withAnimation(.snappy(duration: 0.2)) { session.toggleChecked(item.id, in: recipe.id) }
                            }
                        }
                    }
                    .padding(18)
                    .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Theme.surface))
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }
}

private struct FinishedPage: View {
    let recipe: Recipe
    let onDone: () -> Void
    @State private var burst = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            ZStack {
                ForEach(0..<10, id: \.self) { i in
                    let angle = Double(i) / 10 * 2 * .pi
                    Text(i.isMultiple(of: 2) ? recipe.emoji : "✨")
                        .font(.system(size: i.isMultiple(of: 2) ? 34 : 24))
                        .offset(x: burst ? cos(angle) * 120 : 0, y: burst ? sin(angle) * 120 : 0)
                        .opacity(burst ? 1 : 0)
                        .scaleEffect(burst ? 1 : 0.3)
                }
                EmojiSticker(emoji: recipe.emoji, size: 120, tilt: burst ? -8 : 0)
                    .scaleEffect(burst ? 1 : 0.8)
            }
            .frame(height: 280)
            .accessibilityHidden(true)
            Text("That's the last step")
                .font(.rounded(.largeTitle, .bold))
                .foregroundStyle(Theme.ink)
                .multilineTextAlignment(.center)
            Text("Enjoy your \(recipe.displayTitle).")
                .font(.rounded(.title3))
                .foregroundStyle(Theme.inkSoft)
                .multilineTextAlignment(.center)
            Spacer()
            Button(action: onDone) {
                Text("Done cooking").frame(maxWidth: .infinity, minHeight: 64)
            }
            .buttonStyle(PrimaryButtonStyle())
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
        }
        .padding(.horizontal, 24)
        .onAppear {
            Haptics.success()
            if reduceMotion { burst = true } else {
                withAnimation(.spring(response: 0.6, dampingFraction: 0.55)) { burst = true }
            }
        }
        .onDisappear { burst = false }
    }
}

private struct CookIngredientsSheet: View {
    let recipe: Recipe
    @Environment(CookingSession.self) private var session

    var body: some View {
        ScrollView {
            IngredientsCard(recipe: recipe)
                .padding(20)
        }
        .background(Theme.canvas.ignoresSafeArea())
    }
}

/// Which ingredients a step mentions, so cook mode can show just those.
enum StepIngredients {
    private static let generic: Set<String> = ["powder", "extract", "juice", "sauce", "paste", "baking", "seed", "leaf", "ground", "light", "dark", "brown", "white", "red", "green", "yellow", "black"]

    static func matching(_ stepText: String, in recipe: Recipe) -> [RecipeItem] {
        let stepWords = Set(TextKit.words(stepText).map(TextKit.singular))
        return recipe.ingredientLines.filter { item in
            let keys = IngredientParser.keyWords(item.text)
            guard !keys.isEmpty else { return false }
            if let last = keys.last, stepWords.contains(last) { return true }
            return keys.contains { !generic.contains($0) && stepWords.contains($0) }
        }
    }
}
