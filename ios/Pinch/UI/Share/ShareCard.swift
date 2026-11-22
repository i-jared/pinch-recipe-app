import SwiftUI

/// The picture people receive when a recipe is shared: the same cover, title, pills,
/// ingredients and steps as the app, laid out as one tall card. Always rendered in
/// light mode at a fixed text size so it looks the same on every phone that opens it.
struct ShareCardView: View {
    let recipe: Recipe
    let scale: Double
    let photos: PhotoStore

    private let ink = Color(hex: 0x17332C)
    private let inkSoft = Color(hex: 0x587068)
    private let width: CGFloat = 400

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .bottomLeading) {
                RecipeCover(recipe: recipe, photos: photos, cell: 26, maxPixelSize: 1440)
                    .frame(width: width, height: 168)
                EmojiSticker(emoji: recipe.emoji, size: 88, tilt: -7)
                    .padding(.leading, 26)
                    .offset(y: 36)
            }
            .zIndex(1)

            VStack(alignment: .leading, spacing: 24) {
                header
                if !recipe.ingredients.isEmpty { ingredients }
                if !recipe.steps.isEmpty { steps }
                if !recipe.notes.isEmpty { notes }
                footer
            }
            .padding(.horizontal, 26)
            .padding(.top, 52)
            .padding(.bottom, 24)
        }
        .frame(width: width, alignment: .leading)
        .background(Color.white)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(recipe.displayTitle)
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .foregroundStyle(ink)
                .fixedSize(horizontal: false, vertical: true)
            if !recipe.summary.isEmpty {
                Text(recipe.summary)
                    .font(.system(size: 15, design: .rounded))
                    .foregroundStyle(inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            let pills = pillTexts
            if !pills.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(pills, id: \.1) { icon, text in
                        Label(text, systemImage: icon)
                            .font(.system(size: 13, weight: .semibold, design: .rounded))
                            .foregroundStyle(ink)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(Color(hex: 0xE3ECE5)))
                    }
                }
                .padding(.top, 2)
            }
        }
    }

    private var pillTexts: [(String, String)] {
        var pills: [(String, String)] = []
        if !recipe.prepTime.isEmpty { pills.append(("clock", "Prep \(recipe.prepTime)")) }
        if !recipe.cookTime.isEmpty { pills.append(("flame", "Cook \(recipe.cookTime)")) }
        if !recipe.totalTime.isEmpty { pills.append(("hourglass", "Total \(recipe.totalTime)")) }
        if let servings = scaledServingsLabel { pills.append(("person.2", servings)) }
        if !recipe.source.isEmpty { pills.append(("book.closed", recipe.source)) }
        return pills
    }

    private var scaledServingsLabel: String? {
        guard abs(scale - 1) > 0.001, let base = recipe.servingsCount else { return recipe.servingsLabel }
        let n = Int((base * scale).rounded())
        return n == 1 ? "1 serving" : "\(n) servings"
    }

    private var ingredients: some View {
        VStack(alignment: .leading, spacing: 9) {
            sectionTitle("Ingredients")
            ForEach(recipe.ingredients) { item in
                if item.isHeader {
                    Text(item.text)
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .foregroundStyle(recipe.palette.deep)
                        .padding(.top, 6)
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Circle()
                            .fill(recipe.palette.base)
                            .frame(width: 7, height: 7)
                            .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 5 }
                        Text(RichText.ingredient(IngredientParser.scale(item.text, by: scale), accent: recipe.palette.deep, dimmed: false, boldFont: .system(size: 15, weight: .bold)))
                            .font(.system(size: 15))
                            .foregroundStyle(ink)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }

    private var steps: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("Steps")
            ForEach(numbered, id: \.0.id) { item, number in
                if item.isHeader {
                    Text(item.text)
                        .font(.system(size: 15, weight: .bold, design: .rounded))
                        .foregroundStyle(recipe.palette.deep)
                } else {
                    HStack(alignment: .top, spacing: 12) {
                        Text("\(number)")
                            .font(.system(size: 14, weight: .heavy, design: .rounded))
                            .foregroundStyle(recipe.palette.onBase)
                            .frame(width: 26, height: 26)
                            .background(Circle().fill(recipe.palette.base))
                        Text(RichText.step(item.text, font: .system(size: 15, weight: .bold)))
                            .font(.system(size: 15))
                            .lineSpacing(2)
                            .foregroundStyle(ink)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 3)
                    }
                }
            }
        }
    }

    private var numbered: [(RecipeItem, Int)] {
        var n = 0
        return recipe.steps.map { item in
            if !item.isHeader { n += 1 }
            return (item, n)
        }
    }

    private var notes: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Notes")
                .font(.system(size: 15, weight: .bold, design: .rounded))
            Text(recipe.notes)
                .font(.system(size: 14, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Color(hex: 0x5A4A12))
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(hex: 0xFFF3C4)))
    }

    private var footer: some View {
        HStack(spacing: 8) {
            CoverArt(palette: .jade, pattern: .gingham, cell: 6)
                .frame(width: 20, height: 20)
                .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            Text("Shared from Pinch")
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .foregroundStyle(inkSoft)
        }
        .padding(.top, 4)
    }

    private func sectionTitle(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 20, weight: .bold, design: .rounded))
            .foregroundStyle(ink)
            .padding(.bottom, 2)
    }
}

@MainActor
enum ShareCardRenderer {
    struct Card {
        let image: UIImage
        let url: URL
    }

    static func render(_ recipe: Recipe, scale: Double, photos: PhotoStore) -> Card? {
        let content = ShareCardView(recipe: recipe, scale: scale, photos: photos)
            .environment(\.colorScheme, .light)
            .environment(\.dynamicTypeSize, .large)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 3
        renderer.isOpaque = true
        guard let image = renderer.uiImage, let data = image.pngData() else { return nil }
        let directory = AppPaths.shareCards
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(fileName(for: recipe.displayTitle))
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            return nil
        }
        return Card(image: image, url: url)
    }

    static func fileName(for title: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: " -'"))
        let cleaned = String(title.unicodeScalars.filter { allowed.contains($0) })
            .trimmingCharacters(in: .whitespaces)
        return (cleaned.isEmpty ? "Recipe" : String(cleaned.prefix(60))) + ".png"
    }
}

/// Preview of the card with a share button, so you see exactly what they'll get.
struct ShareRecipeSheet: View {
    let recipe: Recipe
    let scale: Double
    @Environment(\.dismiss) private var dismiss
    @Environment(RecipeStore.self) private var store
    @State private var card: ShareCardRenderer.Card?
    @State private var failed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if let card {
                        Image(uiImage: card.image)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .shadow(color: .black.opacity(0.14), radius: 18, y: 8)
                            .accessibilityLabel("Recipe card for \(recipe.displayTitle)")
                        Text("Sent as a picture, so anyone can open it. They don't need Pinch.")
                            .font(.rounded(.footnote))
                            .foregroundStyle(Theme.inkSoft)
                            .multilineTextAlignment(.center)
                    } else if failed {
                        Text("Couldn't draw the recipe card. Try again in a moment.")
                            .font(.rounded(.body))
                            .foregroundStyle(Theme.inkSoft)
                            .padding(.top, 80)
                    } else {
                        ProgressView().padding(.top, 120)
                    }
                }
                .padding(20)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .background(Theme.canvas.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 10) {
                    if let card {
                        ShareLink(
                            item: card.url,
                            preview: SharePreview(recipe.displayTitle, image: Image(uiImage: card.image))
                        ) {
                            Label("Share recipe card", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(PrimaryButtonStyle())
                    }
                    if let url = URL(string: recipe.sourceURL), ["https", "http"].contains(url.scheme ?? "") {
                        ShareLink(item: url) { Label("Share original link", systemImage: "link") }
                            .buttonStyle(SoftButtonStyle())
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
                .frame(maxWidth: 520)
                .background(BottomFade())
            }
            .navigationTitle("Share recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                card = ShareCardRenderer.render(recipe, scale: scale, photos: store.photos)
                failed = card == nil
            }
        }
    }
}
