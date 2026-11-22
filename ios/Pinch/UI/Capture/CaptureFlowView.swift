import PhotosUI
import SwiftUI

/// Scan → read → review, presented full screen from the library.
struct CaptureFlowView: View {
    let start: CaptureStart
    /// Called with the saved recipe's ID, or nil if the person backed out.
    let onFinish: (String?) -> Void

    @Environment(RecipeStore.self) private var store
    @Environment(ServerSync.self) private var server
    @AppStorage(AppSettings.smartParsingKey) private var smartParsing = true

    private enum Phase {
        case scanning
        case reading(RecipeImporter.Stage, [UIImage])
        case reviewing(Recipe, readBy: RecipeImporter.ReadBy)
        case failed(String)
    }

    @State private var phase: Phase?
    @State private var started = false
    @State private var work: Task<Void, Never>?

    /// Reading with the AI can take a little while, so it can be called off.
    private var cancelReading: (() -> Void)? {
        guard case .reading(let stage, _) = phase else { return nil }
        switch stage {
        case .fetching, .aiReading:
            return {
                work?.cancel()
                onFinish(nil)
            }
        case .reading, .tidying:
            return nil
        }
    }

    var body: some View {
        Group {
            switch phase {
            case .scanning:
                DocumentScannerView(
                    onFinish: { images in
                        if images.isEmpty { onFinish(nil) } else { read(images) }
                    },
                    onCancel: { onFinish(nil) }
                )
                .ignoresSafeArea()

            case .reading(let stage, let images):
                ReadingView(stage: stage, pages: images, onCancel: cancelReading)

            case .reviewing(let recipe, let readBy):
                NavigationStack {
                    RecipeEditorView(
                        recipe: recipe,
                        mode: .new(readBy: readBy),
                        onSave: { saved in
                            store.save(saved)
                            Haptics.success()
                            onFinish(saved.id)
                        },
                        onCancel: { discarded in
                            discarded.photoFiles.forEach(store.photos.delete)
                            onFinish(nil)
                        }
                    )
                }
                .tint(Theme.jadeDeep)

            case .failed(let message):
                FailedReadingView(message: message, onRetry: { onFinish(nil) }, onTypeIt: {
                    phase = .reviewing(RecipeImporter.makeRecipe(from: ParsedRecipe()), readBy: .typing)
                })

            case nil:
                ReadingView(stage: .reading(page: 1, of: 1), pages: [], onCancel: nil)
            }
        }
        .task { begin() }
    }

    private func begin() {
        guard !started else { return }
        started = true
        switch start {
        case .scan:
            phase = .scanning
        case .blank:
            phase = .reviewing(RecipeImporter.makeRecipe(from: ParsedRecipe()), readBy: .typing)
        case .images(let images):
            read(images)
        case .text(let text):
            if let url = RecipeImporter.link(in: text) {
                open(url)
                return
            }
            let ai = server.aiClient
            phase = .reading(ai != nil ? .aiReading(.text) : .tidying, [])
            work = Task {
                do {
                    let result = try await RecipeImporter.importText(text, ai: ai, smartParsing: smartParsing) { stage in
                        phase = .reading(stage, [])
                    }
                    phase = .reviewing(result.recipe, readBy: result.readBy)
                } catch {
                    // Only cancelling ends up here; the flow is already closing.
                }
            }
        case .url(let url):
            open(url)
        case .photos(let items):
            phase = .reading(.reading(page: 1, of: items.count), [])
            Task {
                var images: [UIImage] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        images.append(image)
                    }
                }
                if images.isEmpty {
                    phase = .failed("Couldn't open those photos. Try choosing them again.")
                } else {
                    read(images)
                }
            }
        }
    }

    private func read(_ images: [UIImage]) {
        phase = .reading(.reading(page: 1, of: images.count), images)
        let ai = server.aiClient
        work = Task {
            do {
                let result = try await RecipeImporter.importPages(images, photos: store.photos, ai: ai, smartParsing: smartParsing) { stage in
                    phase = .reading(stage, images)
                }
                phase = .reviewing(result.recipe, readBy: result.readBy)
            } catch is CancellationError {
                return
            } catch {
                phase = .failed(error.localizedDescription)
            }
        }
    }

    private func open(_ url: URL) {
        phase = .reading(.fetching(host: RecipeImporter.displayHost(url)), [])
        let ai = server.aiClient
        work = Task {
            do {
                let result = try await RecipeImporter.importURL(url, ai: ai) { stage in
                    phase = .reading(stage, [])
                }
                phase = .reviewing(result.recipe, readBy: result.readBy)
            } catch {
                if Task.isCancelled || error is CancellationError { return }
                phase = .failed(error.localizedDescription)
            }
        }
    }
}

/// The one orchestrated moment in the flow: pages fan out while a highlighter sweeps them.
struct ReadingView: View {
    let stage: RecipeImporter.Stage
    let pages: [UIImage]
    let onCancel: (() -> Void)?
    @State private var sweep = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 32) {
            Spacer()
            ZStack {
                if pages.isEmpty {
                    placeholderCard
                } else {
                    ForEach(Array(pages.prefix(3).enumerated()), id: \.offset) { index, page in
                        let angle = Double(index - min(pages.count, 3) / 2) * 7
                        Image(uiImage: page)
                            .resizable()
                            .scaledToFill()
                            .frame(width: 170, height: 220)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(.white, lineWidth: 4))
                            .shadow(color: .black.opacity(0.15), radius: 10, y: 5)
                            .rotationEffect(.degrees(angle))
                            .offset(x: CGFloat(index) * 14 - 14)
                    }
                }
                // Highlighter sweep
                RoundedRectangle(cornerRadius: 6)
                    .fill(Theme.butter.opacity(0.75))
                    .frame(width: 200, height: 18)
                    .blendMode(.multiply)
                    .offset(y: sweep ? 95 : -95)
                    .opacity(reduceMotion ? 0 : 1)
            }
            .frame(height: 260)
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) { sweep = true }
            }

            VStack(spacing: 8) {
                Text(title)
                    .font(.rounded(.title2, .bold))
                    .foregroundStyle(Theme.ink)
                    .contentTransition(.numericText())
                Text(detail)
                    .font(.rounded(.body))
                    .foregroundStyle(Theme.inkSoft)
                    .multilineTextAlignment(.center)
            }
            .animation(.default, value: title)
            Spacer()
            if let onCancel {
                Button("Cancel", action: onCancel)
                    .buttonStyle(SoftButtonStyle())
            }
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.canvas.ignoresSafeArea())
    }

    private var placeholderCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let host = webHost {
                HStack(spacing: 6) {
                    Image(systemName: "globe")
                    Text(host).lineLimit(1)
                }
                .font(.rounded(.caption, .bold))
                .foregroundStyle(Theme.jadeDeep)
            }
            RoundedRectangle(cornerRadius: 3).fill(Theme.cherry).frame(height: 4)
            ForEach(0..<5, id: \.self) { i in
                RoundedRectangle(cornerRadius: 3).fill(Theme.line).frame(width: i == 0 ? 90 : 140, height: 8)
            }
        }
        .padding(20)
        .frame(width: 190, height: 220, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.surface))
        .rotationEffect(.degrees(-4))
    }

    private var webHost: String? {
        switch stage {
        case .fetching(let host): host
        case .aiReading(.website): ""
        default: nil
        }
    }

    private var title: String {
        switch stage {
        case .reading(let page, let total) where total > 1: "Reading page \(page) of \(total)"
        case .reading: "Reading your recipe"
        case .tidying: "Tidying things up"
        case .fetching: "Opening the page"
        case .aiReading(.website): "Finding the recipe"
        case .aiReading: "Reading your recipe"
        }
    }

    private var detail: String {
        switch stage {
        case .reading: "Picking out the words on each page."
        case .tidying: "Sorting ingredients from steps."
        case .fetching(let host): "Getting the recipe from \(host)."
        case .aiReading(.pages(let count)): "The AI is reading \(count == 1 ? "the page" : "all \(count) pages") and sorting the ingredients from the steps. This takes a few seconds."
        case .aiReading(.website): "The AI is picking out the recipe and leaving out everything else on the page."
        case .aiReading(.text): "The AI is sorting the ingredients from the steps."
        }
    }
}

struct FailedReadingView: View {
    let message: String
    let onRetry: () -> Void
    let onTypeIt: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("🔍")
                .font(.system(size: 64))
            Text("Couldn't read that one")
                .font(.rounded(.title2, .bold))
                .foregroundStyle(Theme.ink)
            Text(message)
                .font(.rounded(.body))
                .foregroundStyle(Theme.inkSoft)
                .multilineTextAlignment(.center)
            VStack(spacing: 12) {
                Button("Type it in instead", action: onTypeIt)
                    .buttonStyle(PrimaryButtonStyle())
                Button("Back to recipes", action: onRetry)
                    .buttonStyle(SoftButtonStyle())
            }
            .padding(.top, 8)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.canvas.ignoresSafeArea())
    }
}
