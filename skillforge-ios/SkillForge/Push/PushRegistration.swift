import Foundation
import UserNotifications

struct PushRegistration {
    func requestAuthorizationIfNeeded() async throws -> Bool {
        try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])
    }
}
