import SwiftUI

@main
struct SkillForgeApp: App {
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
