import SwiftUI
import UIKit
@preconcurrency import UserNotifications

final class SkillForgeAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        NotificationCenter.default.post(name: .skillForgeDidRegisterPushToken, object: token)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NotificationCenter.default.post(name: .skillForgePushRegistrationFailed, object: error.localizedDescription)
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        []
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard let sessionID = response.notification.request.content.userInfo["sessionId"] as? String,
              sessionID.isEmpty == false else { return }
        await MainActor.run { PushNotificationRouter.shared.route(to: sessionID) }
    }
}

@main
struct SkillForgeApp: App {
    @UIApplicationDelegateAdaptor(SkillForgeAppDelegate.self) private var appDelegate
    @StateObject private var appState: AppState

    init() {
        #if DEBUG
        _appState = StateObject(wrappedValue: AppState(loadOnInit: !DebugLaunchConfiguration.isUITest))
        #else
        _appState = StateObject(wrappedValue: AppState())
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
        }
    }
}

private struct RootView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        Group {
            #if DEBUG
            if DebugLaunchConfiguration.isStreamingHandoffUITest {
                ChatView.streamingHandoffUITestFixture()
            } else if DebugLaunchConfiguration.isOutboundAttachmentsUITest {
                ChatView.outboundAttachmentsUITestFixture()
            } else if DebugLaunchConfiguration.isPairingUITest {
                PairingView()
            } else if DebugLaunchConfiguration.isTabsUITest {
                CompanionTabView.tabsUITestFixture()
            } else if DebugLaunchConfiguration.isSlice3UITest {
                ChatView.slice3UITestFixture()
            } else if DebugLaunchConfiguration.isChatUITest {
                ChatView.uiTestFixture()
            } else {
                phaseView
            }
            #else
            phaseView
            #endif
        }
    }

    @ViewBuilder
    private var phaseView: some View {
        Group {
            switch appState.phase {
            case .loading:
                ProgressView("Loading SkillForge")
            case .needsPairing:
                PairingView()
            case let .paired(endpoint, deviceToken, device, defaultAgent):
                CompanionTabView(
                    endpoint: endpoint,
                    deviceToken: deviceToken,
                    device: device,
                    defaultAgent: defaultAgent
                )
            }
        }
    }
}
