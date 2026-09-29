import AudioToolbox
import Foundation
import Observation
import UIKit
import UserNotifications

struct KitchenTimer: Identifiable, Equatable {
    let id: UUID
    var label: String
    var recipeTitle: String
    var recipeID: String?
    var duration: Int
    var endDate: Date
    var isFinished = false

    var remaining: Int { max(0, Int(endDate.timeIntervalSinceNow.rounded(.up))) }
}

/// Running kitchen timers. Each timer also schedules a local notification so it still
/// rings if the phone is locked or another app is open.
@Observable
@MainActor
final class TimerCenter {
    private(set) var timers: [KitchenTimer] = []
    @ObservationIgnored private var tasks: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private var askedForNotifications = false

    var hasFinished: Bool { timers.contains(where: \.isFinished) }

    func start(seconds: Int, label: String, recipe: Recipe?) {
        guard seconds > 0 else { return }
        let timer = KitchenTimer(
            id: UUID(), label: label, recipeTitle: recipe?.displayTitle ?? "",
            recipeID: recipe?.id, duration: seconds, endDate: .now.addingTimeInterval(TimeInterval(seconds))
        )
        timers.append(timer)
        schedule(timer)
        Haptics.success()
        if !askedForNotifications {
            askedForNotifications = true
            Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) }
        }
    }

    func addMinute(_ id: UUID) {
        guard let index = timers.firstIndex(where: { $0.id == id }) else { return }
        var timer = timers[index]
        let base = timer.isFinished ? Date.now : timer.endDate
        timer.endDate = base.addingTimeInterval(60)
        timer.duration = max(timer.duration, Int(timer.endDate.timeIntervalSinceNow))
        timer.isFinished = false
        timers[index] = timer
        schedule(timer)
    }

    func cancel(_ id: UUID) {
        tasks[id]?.cancel()
        tasks[id] = nil
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
        timers.removeAll { $0.id == id }
    }

    func timers(for recipeID: String) -> [KitchenTimer] {
        timers.filter { $0.recipeID == recipeID }
    }

    private func schedule(_ timer: KitchenTimer) {
        tasks[timer.id]?.cancel()
        tasks[timer.id] = Task { [weak self] in
            let delay = timer.endDate.timeIntervalSinceNow
            if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
            guard !Task.isCancelled else { return }
            self?.finish(timer.id)
        }

        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [timer.id.uuidString])
        let content = UNMutableNotificationContent()
        content.title = "Time's up: \(timer.label)"
        content.body = timer.recipeTitle.isEmpty ? "Your timer is done." : timer.recipeTitle
        content.sound = .default
        let interval = max(1, timer.endDate.timeIntervalSinceNow)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        center.add(UNNotificationRequest(identifier: timer.id.uuidString, content: content, trigger: trigger))
    }

    private func finish(_ id: UUID) {
        guard let index = timers.firstIndex(where: { $0.id == id }), !timers[index].isFinished else { return }
        timers[index].isFinished = true
        Haptics.warning()
        // A few chimes, like a kitchen timer, unless it's dismissed first.
        Task { [weak self] in
            for _ in 0..<3 {
                guard let self, self.timers.contains(where: { $0.id == id && $0.isFinished }) else { return }
                AudioServicesPlayAlertSound(SystemSoundID(1005))
                try? await Task.sleep(for: .seconds(2.5))
            }
        }
    }
}

enum Haptics {
    @MainActor static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    @MainActor static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    @MainActor static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}

/// Shows timer notifications as banners even while the app is open.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationPresenter()

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }
}
