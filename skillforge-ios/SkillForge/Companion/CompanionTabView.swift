import SwiftUI
import UIKit

enum CompanionTab: Hashable {
    case chat
    case control
    case agents
    case settings
}

struct ChatRoute: Identifiable, Equatable {
    let id = UUID()
    let session: MobileSession
}

struct CompanionTabView: View {
    @EnvironmentObject private var appState: AppState
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(AppAppearance.storageKey) private var appearanceRawValue = AppAppearance.system.rawValue

    let endpoint: URL
    let deviceToken: String
    let device: MobileDeviceSummary?
    let defaultAgent: MobileAgentSummary?

    private let usesDeterministicFixture: Bool
    private let fixtureSessions: [MobileSession]
    private let fixtureSchedules: [MobileScheduledTask]
    private let fixtureRuns: [MobileScheduledTaskRun]
    private let fixtureAgentDetails: [Int64: MobileAgentDetail]
    private let fixtureMessages: [ChatMessage]
    private let preference: SelectedAgentPreference

    @State private var selectedTab: CompanionTab = .chat
    @State private var selectedAgent: MobileAgentSummary?
    @State private var agents: [MobileAgentCatalogItem]
    @State private var isLoadingAgents: Bool
    @State private var isAgentSelectionReady: Bool
    @State private var agentErrorText: String?
    @State private var chatRoute: ChatRoute?
    @State private var rootCleanupToken = 0
    @State private var fixtureDisconnected = false

    init(
        endpoint: URL,
        deviceToken: String,
        device: MobileDeviceSummary?,
        defaultAgent: MobileAgentSummary?,
        initialAgents: [MobileAgentCatalogItem] = [],
        initialSessions: [MobileSession] = [],
        initialSchedules: [MobileScheduledTask] = [],
        initialRuns: [MobileScheduledTaskRun] = [],
        initialAgentDetails: [Int64: MobileAgentDetail] = [:],
        initialMessages: [ChatMessage] = [],
        usesDeterministicFixture: Bool = false,
        userDefaults: UserDefaults = .standard
    ) {
        self.endpoint = endpoint
        self.deviceToken = deviceToken
        self.device = device
        self.defaultAgent = defaultAgent
        self.usesDeterministicFixture = usesDeterministicFixture
        fixtureSessions = initialSessions
        fixtureSchedules = initialSchedules
        fixtureRuns = initialRuns
        fixtureAgentDetails = initialAgentDetails
        fixtureMessages = initialMessages
        preference = SelectedAgentPreference(
            userDefaults: userDefaults,
            namespace: device?.id ?? endpoint.absoluteString
        )

        let initialSelection = AgentSelectionPolicy.resolve(
            storedId: preference.load(),
            defaultAgentId: defaultAgent?.id,
            catalog: initialAgents
        )?.summary ?? defaultAgent
        _selectedAgent = State(initialValue: initialSelection)
        _agents = State(initialValue: initialAgents)
        _isLoadingAgents = State(initialValue: !usesDeterministicFixture && initialAgents.isEmpty)
        _isAgentSelectionReady = State(initialValue: usesDeterministicFixture || !initialAgents.isEmpty)
    }

    @ViewBuilder
    var body: some View {
        if usesDeterministicFixture && fixtureDisconnected {
            PairingView()
        } else {
            companionTabs
        }
    }

    private var companionTabs: some View {
        TabView(selection: $selectedTab) {
            Group {
                if isAgentSelectionReady {
                    ChatView(
                        endpoint: endpoint,
                        deviceToken: deviceToken,
                        device: device,
                        defaultAgent: selectedAgent,
                        availableAgents: agents,
                        initialSession: fixtureSessions.first,
                        initialSessions: fixtureSessions,
                        initialMessages: fixtureMessages,
                        usesDeterministicFixture: usesDeterministicFixture,
                        isActive: selectedTab == .chat,
                        route: chatRoute,
                        rootCleanupToken: rootCleanupToken,
                        onRouteHandled: clearHandledRoute,
                        onDisconnectRequested: disconnectLocally,
                        onChatAgentSelected: selectChatAgent
                    )
                    .id("companion.chat")
                } else {
                    ProgressView("Loading Agents")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(CompanionStyle.warmBackground)
                }
            }
            .tabItem {
                Label("Chat", systemImage: "bubble.left.and.bubble.right.fill")
                    .accessibilityIdentifier("tab.chat")
            }
            .tag(CompanionTab.chat)

            ControlView(
                endpoint: endpoint,
                deviceToken: deviceToken,
                agents: agents,
                isActive: selectedTab == .control,
                initialSchedules: fixtureSchedules,
                initialRuns: fixtureRuns,
                initialSessions: fixtureSessions,
                usesDeterministicFixture: usesDeterministicFixture,
                onUnauthorized: disconnectLocally,
                onOpenSession: openSession
            )
            .tabItem {
                Label("Control", systemImage: "square.grid.2x2.fill")
                    .accessibilityIdentifier("tab.control")
            }
            .tag(CompanionTab.control)

            AgentsView(
                endpoint: endpoint,
                deviceToken: deviceToken,
                agents: agents,
                isLoading: isLoadingAgents,
                errorText: agentErrorText,
                usesDeterministicFixture: usesDeterministicFixture,
                fixtureDetails: fixtureAgentDetails,
                onRetry: reloadAgents,
                onUnauthorized: disconnectLocally
            )
            .tabItem {
                Label("Agents", systemImage: "person.2.fill")
                    .accessibilityIdentifier("tab.agents")
            }
            .tag(CompanionTab.agents)

            SettingsView(
                endpoint: endpoint,
                device: device,
                client: client,
                usesDeterministicFixture: usesDeterministicFixture,
                onUnauthorized: disconnectLocally,
                onDisconnect: disconnectLocally
            )
            .tabItem {
                Label("Settings", systemImage: "gearshape.fill")
                    .accessibilityIdentifier("tab.settings")
            }
            .tag(CompanionTab.settings)
        }
        .tint(CompanionStyle.orange)
        .toolbarBackground(CompanionStyle.warmBackground, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        .background(
            TabBarAccessibilityConfigurator(
                identifiers: ["tab.chat", "tab.control", "tab.agents", "tab.settings"]
            )
            .frame(width: 0, height: 0)
        )
        .preferredColorScheme(selectedAppearance.colorScheme)
        .task {
            guard !usesDeterministicFixture else { return }
            await loadAgents()
        }
        .task(id: scenePhase) {
            guard !usesDeterministicFixture, scenePhase == .active else { return }
            while !Task.isCancelled {
                await appState.refreshEndpointSelection()
                try? await Task.sleep(for: .seconds(4))
            }
        }
    }

    private var client: MobileApiClient {
        MobileApiClient(baseURL: endpoint, deviceToken: deviceToken)
    }

    private var selectedAppearance: AppAppearance {
        AppAppearance.resolve(storedValue: appearanceRawValue)
    }

    private func selectChatAgent(_ agent: MobileAgentCatalogItem) {
        preference.save(agent.id)
        selectedAgent = agent.summary
    }

    private func openSession(_ session: MobileSession) {
        if let sessionAgent = agents.first(where: { $0.id == session.agentId }) {
            preference.save(sessionAgent.id)
            selectedAgent = sessionAgent.summary
        }
        selectedTab = .chat
        Task { @MainActor in
            await Task.yield()
            chatRoute = ChatRoute(session: session)
        }
    }

    private func clearHandledRoute(_ routeId: UUID) {
        guard chatRoute?.id == routeId else { return }
        chatRoute = nil
    }

    private func reloadAgents() {
        guard !usesDeterministicFixture else { return }
        Task { await loadAgents() }
    }

    @MainActor
    private func loadAgents() async {
        isLoadingAgents = true
        agentErrorText = nil
        defer {
            isLoadingAgents = false
            isAgentSelectionReady = true
        }

        do {
            let loaded = try await client.listAgents()
            guard !Task.isCancelled else { return }
            agents = loaded
            revalidateSelection(against: loaded)
        } catch {
            guard !Task.isCancelled else { return }
            if isUnauthorized(error) {
                disconnectLocally()
                return
            }
            agentErrorText = error.localizedDescription
        }
    }

    private func isUnauthorized(_ error: Error) -> Bool {
        guard case let MobileApiError.httpStatus(status, _) = error else { return false }
        return status == 401
    }

    private func revalidateSelection(against catalog: [MobileAgentCatalogItem]) {
        let selected = AgentSelectionPolicy.resolve(
            storedId: preference.load(),
            defaultAgentId: defaultAgent?.id,
            catalog: catalog
        )

        guard let selected else {
            preference.clear()
            return
        }
        preference.save(selected.id)
        selectedAgent = selected.summary
    }

    private func disconnectLocally() {
        preference.clear()
        NotificationCenter.default.post(name: .skillForgeChatRootDisconnect, object: nil)
        rootCleanupToken &+= 1
        if usesDeterministicFixture {
            fixtureDisconnected = true
            return
        }
        Task { @MainActor in
            await Task.yield()
            appState.resetPairing()
        }
    }
}

enum AgentSelectionPolicy {
    static func resolve(
        storedId: Int64?,
        defaultAgentId: Int64?,
        catalog: [MobileAgentCatalogItem]
    ) -> MobileAgentCatalogItem? {
        storedId.flatMap { id in catalog.first { $0.id == id } }
            ?? defaultAgentId.flatMap { id in catalog.first { $0.id == id } }
            ?? catalog.first
    }
}

private struct TabBarAccessibilityConfigurator: UIViewControllerRepresentable {
    let identifiers: [String]

    func makeUIViewController(context: Context) -> Controller {
        Controller(identifiers: identifiers)
    }

    func updateUIViewController(_ controller: Controller, context: Context) {
        controller.identifiers = identifiers
        controller.configureTabItems(attempt: 0)
    }

    final class Controller: UIViewController {
        var identifiers: [String]

        init(identifiers: [String]) {
            self.identifiers = identifiers
            super.init(nibName: nil, bundle: nil)
            view.isHidden = true
            view.isUserInteractionEnabled = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            configureTabItems(attempt: 0)
        }

        func configureTabItems(attempt: Int) {
            DispatchQueue.main.asyncAfter(deadline: .now() + (attempt == 0 ? 0 : 0.05)) { [weak self] in
                guard let self else { return }
                guard let root = view.window?.rootViewController,
                      let tabController = Self.findTabController(from: root),
                      tabController.tabBar.items?.count == identifiers.count
                else {
                    if attempt < 20 {
                        configureTabItems(attempt: attempt + 1)
                    }
                    return
                }
                for (item, identifier) in zip(tabController.tabBar.items ?? [], identifiers) {
                    item.accessibilityIdentifier = identifier
                }
            }
        }

        private static func findTabController(from controller: UIViewController) -> UITabBarController? {
            if let tabController = controller as? UITabBarController {
                return tabController
            }
            for child in controller.children {
                if let found = findTabController(from: child) {
                    return found
                }
            }
            if let presented = controller.presentedViewController {
                return findTabController(from: presented)
            }
            return nil
        }
    }
}

private struct SelectedAgentPreference {
    let userDefaults: UserDefaults
    let namespace: String

    private var key: String {
        "skillforge.mobile.selectedAgentId.\(namespace)"
    }

    func load() -> Int64? {
        (userDefaults.object(forKey: key) as? NSNumber)?.int64Value
    }

    func save(_ id: Int64) {
        userDefaults.set(NSNumber(value: id), forKey: key)
    }

    func clear() {
        userDefaults.removeObject(forKey: key)
    }
}

enum CompanionStyle {
    static let warmBackground = Color(uiColor: .systemGroupedBackground)
    static let ink = Color(red: 0.08, green: 0.08, blue: 0.07)
    static let orange = Color(red: 0.93, green: 0.35, blue: 0.08)
}
