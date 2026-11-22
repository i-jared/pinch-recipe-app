import SwiftUI

private final class SharedPageLease {
    let photos: PhotoStore
    let names: [String]
    init(photos: PhotoStore, names: [String]) { self.photos = photos; self.names = names }
    deinit { names.forEach(photos.delete) }
}

struct SharedCookbookRecipeView: View {
    let item: SharedRecipe
    let group: ServerGroup
    @Environment(ServerSync.self) private var server
    @Environment(RecipeStore.self) private var store
    @State private var pageLease: SharedPageLease?
    @State private var fresh: SharedRecipe?
    @State private var recipe: Recipe?
    @State private var error: String?
    @State private var retry = 0
    var body: some View {
        Group {
            if let recipe, let fresh {
                RecipeDetailView(recipeID: recipe.id, previewRecipe: recipe, sharedItem: fresh, sharedGroup: group)
            } else if let error {
                ContentUnavailableView {
                    Label("Couldn’t open this recipe", systemImage: "wifi.exclamationmark")
                } description: { Text(error) } actions: { Button("Try again") { retry += 1 } }
            } else { ProgressView("Opening recipe…").frame(maxWidth: .infinity, maxHeight: .infinity) }
        }.background(Theme.canvas).task(id: retry) {
            error = nil
            do {
                let result = try await server.preview(item, in: group)
                pageLease = SharedPageLease(photos: store.photos, names: result.1.photoFiles)
                fresh = result.0; recipe = result.1
            } catch is CancellationError { }
            catch { self.error = error.localizedDescription }
        }
    }
}
