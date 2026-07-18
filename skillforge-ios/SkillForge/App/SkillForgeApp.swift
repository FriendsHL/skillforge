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
        if DebugLaunchConfiguration.isInteractiveArtifactUITest {
            if ProcessInfo.processInfo.arguments.contains("--personal-app-valid-saved-state") {
                try? PersonalAppStateStore.save(
                    Data(#"{"food":2800}"#.utf8),
                    artifactID: "interactive-budget"
                )
            } else if ProcessInfo.processInfo.arguments.contains("--personal-app-invalid-saved-state") {
                try? PersonalAppStateStore.save(
                    Data(#"{"food":"stale"}"#.utf8),
                    artifactID: "interactive-budget"
                )
            } else {
                PersonalAppStateStore.reset(artifactID: "interactive-budget")
            }
        }
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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Group {
            #if DEBUG
            if DebugLaunchConfiguration.isStreamingHandoffUITest {
                ChatView.streamingHandoffUITestFixture()
            } else if DebugLaunchConfiguration.isPersonalAppLibraryUITest {
                CompanionTabView.personalAppLibraryUITestFixture()
                    .dynamicTypeSize(
                        DebugLaunchConfiguration.usesAccessibilityXXXL
                            ? .accessibility5
                            : dynamicTypeSize
                    )
                    .preferredColorScheme(
                        ProcessInfo.processInfo.arguments.contains("--personal-app-library-dark")
                            ? .dark
                            : .light
                    )
            } else if DebugLaunchConfiguration.isRuntimeErrorUITest {
                ChatView.runtimeErrorUITestFixture()
                    .dynamicTypeSize(
                        DebugLaunchConfiguration.usesAccessibilityXXXL
                            ? .accessibility5
                            : dynamicTypeSize
                    )
            } else if DebugLaunchConfiguration.isInteractiveArtifactUITest {
                ChatView.interactiveArtifactUITestFixture()
                    .environment(
                        \.personalAppExternalURLOpener,
                        PersonalAppExternalURLOpener { _ in }
                    )
                    .dynamicTypeSize(
                        DebugLaunchConfiguration.usesAccessibilityXXXL
                            ? .accessibility5
                            : dynamicTypeSize
                    )
                    .preferredColorScheme(
                        DebugLaunchConfiguration.usesPersonalAppCardDarkMode ? .dark : .light
                    )
            } else if DebugLaunchConfiguration.isOutboundAttachmentsUITest {
                ChatView.outboundAttachmentsUITestFixture()
                    .preferredColorScheme(
                        DebugLaunchConfiguration.usesChatDarkMode ? .dark : .light
                    )
            } else if DebugLaunchConfiguration.isPairingUITest {
                PairingView()
            } else if DebugLaunchConfiguration.isTabsUITest {
                CompanionTabView.tabsUITestFixture()
            } else if DebugLaunchConfiguration.isSlice3UITest {
                ChatView.slice3UITestFixture()
            } else if DebugLaunchConfiguration.isChatUITest {
                if DebugLaunchConfiguration.usesCompanionShell {
                    CompanionTabView.tabsUITestFixture()
                        .dynamicTypeSize(
                            DebugLaunchConfiguration.usesAccessibilityXXXL
                                ? .accessibility5
                                : dynamicTypeSize
                        )
                } else {
                    ChatView.uiTestFixture()
                        .dynamicTypeSize(
                            DebugLaunchConfiguration.usesAccessibilityXXXL
                                ? .accessibility5
                                : dynamicTypeSize
                        )
                        .preferredColorScheme(
                            DebugLaunchConfiguration.usesChatDarkMode ? .dark : .light
                        )
                }
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
                SkillForgeLaunchView()
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

struct SkillForgeLaunchView: View {
    var body: some View {
        ZStack {
            Color("LaunchBackground")
                .ignoresSafeArea()

            VStack(spacing: 18) {
                launchMark

                VStack(spacing: 6) {
                    Text("SkillForge")
                        .font(.title.bold())
                        .foregroundStyle(.primary)
                    Text("Restoring your workspace")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                ProgressView()
                    .tint(CompanionStyle.orange)
                    .accessibilityLabel("Restoring your workspace")
                    .accessibilityIdentifier("app.launch.progress")
            }
            .padding(32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("app.launch")
    }

    private var launchMark: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(CompanionStyle.ink)
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(CompanionStyle.orange)
                .frame(width: 19, height: 19)
                .offset(x: -27, y: -27)
            Circle()
                .fill(Color(red: 0.31, green: 0.78, blue: 0.55))
                .frame(width: 15, height: 15)
                .offset(x: 28, y: -27)
            Text("SF")
                .font(.system(size: 34, weight: .medium, design: .rounded))
                .foregroundStyle(.white)
        }
        .frame(width: 96, height: 96)
        .shadow(color: .black.opacity(0.12), radius: 18, y: 9)
        .accessibilityHidden(true)
    }
}
