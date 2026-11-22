import SwiftUI
import UserNotifications

@main
struct PinchApp: App {
    @State private var store: RecipeStore
    @State private var timers = TimerCenter()
    @State private var session = CookingSession()
    @State private var server: ServerSync
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let store = RecipeStore()
        let server = ServerSync(store: store)
        store.didChangeLocally = { [weak server] in server?.scheduleSync() }
        _store = State(initialValue: store)
        _server = State(initialValue: server)
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared

        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-seedSamples"), store.recipes.isEmpty {
            SampleRecipes.all.forEach(store.save)
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(timers)
                .environment(session)
                .environment(server)
                .tint(Theme.jadeDeep)
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { await server.syncIfConnected() } }
                }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    while !Task.isCancelled {
                        do { try await Task.sleep(for: .seconds(30)) } catch { return }
                        await server.syncIfConnected()
                    }
                }
        }
    }
}

enum Route: Hashable {
    case recipe(String)
    case settings
}

struct RootView: View {
    @Environment(ServerSync.self) private var server
    @State private var path: [Route] = []
    @State private var linkError: String?
    @Namespace private var zoom

    var body: some View {
        @Bindable var server = server
            NavigationStack(path: $path) {
                LibraryView(path: $path, zoom: zoom)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .recipe(let id): RecipeDetailView(recipeID: id).navigationTransition(.zoom(sourceID: id, in: zoom))
                        case .settings: SettingsView()
                        }
                    }
            }.tint(Theme.jadeDeep)
            .onOpenURL { url in
                guard url.scheme != ManagedAuthorization.scheme else { return }
                do { server.pendingInvitation = try GroupInvitation.parse(url.absoluteString) }
                catch { linkError = error.localizedDescription }
            }
            .sheet(item: $server.pendingInvitation) { JoinGroupSheet(invitation: $0) }
            .alert("Couldn’t open invitation", isPresented: Binding(get: { linkError != nil }, set: { if !$0 { linkError = nil } })) { Button("OK") { linkError = nil } } message: { Text(linkError ?? "") }
    }
}

/// What the cook has ticked off while making a recipe. Kept in memory for the session,
/// so moving between the recipe and cook mode keeps your place.
@Observable
@MainActor
final class CookingSession {
    private var checked: [String: Set<String>] = [:]
    private var done: [String: Set<String>] = [:]
    private var scales: [String: Double] = [:]
    private var stepIndex: [String: Int] = [:]
    var ingredientMatches: [CookModeAI.Input: [[RecipeItem]]] = [:]

    func isChecked(_ itemID: String, in recipeID: String) -> Bool {
        checked[recipeID]?.contains(itemID) ?? false
    }

    func toggleChecked(_ itemID: String, in recipeID: String) {
        var set = checked[recipeID] ?? []
        if set.contains(itemID) { set.remove(itemID) } else { set.insert(itemID) }
        checked[recipeID] = set
    }

    func isDone(_ stepID: String, in recipeID: String) -> Bool {
        done[recipeID]?.contains(stepID) ?? false
    }

    func toggleDone(_ stepID: String, in recipeID: String) {
        var set = done[recipeID] ?? []
        if set.contains(stepID) { set.remove(stepID) } else { set.insert(stepID) }
        done[recipeID] = set
    }

    func setDone(_ stepID: String, in recipeID: String, _ value: Bool) {
        var set = done[recipeID] ?? []
        if value { set.insert(stepID) } else { set.remove(stepID) }
        done[recipeID] = set
    }

    func doneCount(in recipeID: String) -> Int { done[recipeID]?.count ?? 0 }
    func checkedCount(in recipeID: String) -> Int { checked[recipeID]?.count ?? 0 }

    func scale(for recipeID: String) -> Double { scales[recipeID] ?? 1 }
    func setScale(_ scale: Double, for recipeID: String) { scales[recipeID] = scale }

    func currentStep(for recipeID: String) -> Int { stepIndex[recipeID] ?? 0 }
    func setCurrentStep(_ index: Int, for recipeID: String) { stepIndex[recipeID] = index }

    func hasProgress(in recipeID: String) -> Bool {
        !(checked[recipeID]?.isEmpty ?? true) || !(done[recipeID]?.isEmpty ?? true)
    }

    func reset(_ recipeID: String) {
        checked[recipeID] = nil
        done[recipeID] = nil
        stepIndex[recipeID] = nil
    }
}

enum AppSettings {
    static let smartParsingKey = "smartParsing"
}
