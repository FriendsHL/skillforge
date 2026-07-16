import Foundation
import UIKit
import UserNotifications

struct PushRegistration {
    func requestAuthorizationIfNeeded() async throws -> Bool {
        let granted = try await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .badge, .sound])
        if granted {
            await MainActor.run { UIApplication.shared.registerForRemoteNotifications() }
        }
        return granted
    }
}

@MainActor
final class PushNotificationRouter: ObservableObject {
    static let shared = PushNotificationRouter()
    @Published private(set) var pendingSessionID: String?
    private init() {}
    func route(to sessionID: String) { pendingSessionID = sessionID }
    func consume(_ sessionID: String) {
        if pendingSessionID == sessionID { pendingSessionID = nil }
    }
}

enum PushEnvironment {
    #if DEBUG
    static let current = "development"
    #else
    static let current = "production"
    #endif
}

extension Notification.Name {
    static let skillForgeDidRegisterPushToken = Notification.Name("SkillForge.DidRegisterPushToken")
    static let skillForgePushRegistrationFailed = Notification.Name("SkillForge.PushRegistrationFailed")
    static let skillForgePushTokenUploadSucceeded = Notification.Name("SkillForge.PushTokenUploadSucceeded")
    static let skillForgePushTokenUploadFailed = Notification.Name("SkillForge.PushTokenUploadFailed")
}
