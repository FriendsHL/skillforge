import SwiftUI

struct AgentsView: View {
    let endpoint: URL
    let deviceToken: String
    let agents: [MobileAgentCatalogItem]
    let isLoading: Bool
    let errorText: String?
    let usesDeterministicFixture: Bool
    let fixtureDetails: [Int64: MobileAgentDetail]
    let currentAgentID: Int64?
    let initialSessions: [MobileSession]
    let onStartConversation: (MobileAgentCatalogItem) -> Void
    let onOpenSession: (MobileSession) -> Void
    let onRetry: () -> Void
    let onUnauthorized: () -> Void

    @State private var searchText = ""
    @State private var selectedFilter: AgentsRosterFilter = .all

    init(
        endpoint: URL,
        deviceToken: String,
        agents: [MobileAgentCatalogItem],
        isLoading: Bool,
        errorText: String?,
        usesDeterministicFixture: Bool = false,
        fixtureDetails: [Int64: MobileAgentDetail] = [:],
        currentAgentID: Int64? = nil,
        initialSessions: [MobileSession] = [],
        onStartConversation: @escaping (MobileAgentCatalogItem) -> Void = { _ in },
        onOpenSession: @escaping (MobileSession) -> Void = { _ in },
        onRetry: @escaping () -> Void,
        onUnauthorized: @escaping () -> Void = {}
    ) {
        self.endpoint = endpoint
        self.deviceToken = deviceToken
        self.agents = agents
        self.isLoading = isLoading
        self.errorText = errorText
        self.usesDeterministicFixture = usesDeterministicFixture
        self.fixtureDetails = fixtureDetails
        self.currentAgentID = currentAgentID
        self.initialSessions = initialSessions
        self.onStartConversation = onStartConversation
        self.onOpenSession = onOpenSession
        self.onRetry = onRetry
        self.onUnauthorized = onUnauthorized
    }

    var body: some View {
        NavigationStack {
            List {
                content
            }
            .scrollContentBackground(.hidden)
            .background(CompanionStyle.warmBackground)
            .safeAreaPadding(.bottom, 72)
            .navigationTitle("Agents")
            .searchable(text: $searchText, prompt: "Search Agents")
            .safeAreaInset(edge: .top, spacing: 0) {
                filterBar
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onRetry) {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(isLoading || usesDeterministicFixture)
                    .accessibilityLabel("Reload Agents")
                }
            }
            .navigationDestination(for: Int64.self) { agentId in
                if let agent = agents.first(where: { $0.id == agentId }) {
                    AgentDetailView(
                        agent: agent,
                        client: MobileApiClient(baseURL: endpoint, deviceToken: deviceToken),
                        fixtureDetail: fixtureDetails[agentId],
                        usesDeterministicFixture: usesDeterministicFixture,
                        initialSessions: initialSessions,
                        onStartConversation: onStartConversation,
                        onOpenSession: onOpenSession,
                        onUnauthorized: onUnauthorized
                    )
                }
            }
        }
    }

    private var filterBar: some View {
        Picker("Agent filter", selection: $selectedFilter) {
            ForEach(AgentsRosterFilter.allCases) { filter in
                Text(filter.title)
                    .tag(filter)
                    .accessibilityIdentifier("agents.filter.\(filter.rawValue)")
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(CompanionStyle.warmBackground)
        .accessibilityIdentifier("agents.filter")
    }

    @ViewBuilder
    private var content: some View {
        if isLoading && agents.isEmpty {
            Section {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Loading Agents")
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 28)
            }
        } else if let errorText, agents.isEmpty {
            Section {
                ContentUnavailableView {
                    Label("Could not load Agents", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(errorText)
                } actions: {
                    Button("Retry", systemImage: "arrow.clockwise", action: onRetry)
                        .buttonStyle(.borderedProminent)
                }
                .listRowBackground(Color.clear)
            }
        } else if filteredAgents.isEmpty {
            Section {
                if searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    ContentUnavailableView(
                        "No Agents",
                        systemImage: "person.2.slash",
                        description: Text("No inspectable Agent configurations are available.")
                    )
                } else {
                    ContentUnavailableView.search(text: searchText)
                }
            }
            .listRowBackground(Color.clear)
        } else {
            Section("Configuration Roster") {
                ForEach(filteredAgents) { agent in
                    NavigationLink(value: agent.id) {
                        AgentRosterRow(agent: agent, currentAgentID: currentAgentID)
                    }
                    .accessibilityIdentifier("agents.row.\(agent.id)")
                    .accessibilityLabel(
                        agent.rosterAccessibilitySummary(currentAgentID: currentAgentID)
                    )
                }
            }

            if let errorText {
                Section {
                    Label(errorText, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
    }

    private var filteredAgents: [MobileAgentCatalogItem] {
        AgentsRosterPolicy.filtered(agents, query: searchText, filter: selectedFilter)
    }
}

private struct AgentRosterRow: View {
    let agent: MobileAgentCatalogItem
    let currentAgentID: Int64?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                accessibilityLayout
            } else {
                standardLayout
            }
        }
        .padding(.vertical, 5)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(agent.rosterAccessibilitySummary(currentAgentID: currentAgentID))
    }

    private var standardLayout: some View {
        HStack(alignment: .top, spacing: 13) {
            agentIcon
            VStack(alignment: .leading, spacing: 6) {
                nameAndBadges
                description
                Text("\(agent.status.displayName) · \(agent.source.displayName)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var accessibilityLayout: some View {
        VStack(alignment: .leading, spacing: 10) {
            agentIcon
            Text(agent.name)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
            badges
            description
            metadataLine("Status", agent.status.displayName)
            metadataLine("Source", agent.source.displayName)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var agentIcon: some View {
        Image(systemName: agent.source == "shared" ? "person.2.circle.fill" : "person.crop.circle.fill")
            .font(.title2)
            .foregroundStyle(agent.isDefault ? CompanionStyle.orange : CompanionStyle.ink)
            .frame(width: 36, height: 36, alignment: .leading)
    }

    private var nameAndBadges: some View {
        HStack(alignment: .firstTextBaseline, spacing: 7) {
            Text(agent.name)
                .font(.body.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            badges
        }
    }

    private var badges: some View {
        HStack(spacing: 5) {
            ForEach(AgentsRosterPolicy.badges(for: agent, currentAgentID: currentAgentID)) { badge in
                Text(badge.title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(badge == .current ? Color.white : CompanionStyle.orange)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(
                        badge == .current ? CompanionStyle.ink : CompanionStyle.orange.opacity(0.12),
                        in: Capsule()
                    )
                    .accessibilityIdentifier("agents.row.\(agent.id).\(badge.rawValue)Badge")
            }
        }
    }

    @ViewBuilder
    private var description: some View {
        if let description = agent.description, !description.isEmpty {
            Text(description)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func metadataLine(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

enum AgentsRosterFilter: String, CaseIterable, Identifiable {
    case all
    case available
    case defaultAgent = "default"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "全部"
        case .available: "可用"
        case .defaultAgent: "默认"
        }
    }
}

enum AgentRosterBadge: String, Identifiable, Equatable {
    case current
    case defaultAgent = "default"

    var id: String { rawValue }
    var title: String { self == .current ? "当前" : "默认" }
}

enum AgentsRosterPolicy {
    static func filtered(
        _ agents: [MobileAgentCatalogItem],
        query: String,
        filter: AgentsRosterFilter
    ) -> [MobileAgentCatalogItem] {
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return agents.filter { agent in
            let matchesFilter = switch filter {
            case .all: true
            case .available: agent.status.localizedCaseInsensitiveCompare("active") == .orderedSame
            case .defaultAgent: agent.isDefault
            }
            guard matchesFilter, !normalizedQuery.isEmpty else { return matchesFilter }
            return [agent.name, agent.description]
                .compactMap { $0 }
                .contains { $0.localizedCaseInsensitiveContains(normalizedQuery) }
        }
    }

    static func badges(
        for agent: MobileAgentCatalogItem,
        currentAgentID: Int64?
    ) -> [AgentRosterBadge] {
        var badges: [AgentRosterBadge] = []
        if agent.id == currentAgentID { badges.append(.current) }
        if agent.isDefault { badges.append(.defaultAgent) }
        return badges
    }
}

enum AgentSessionScope: String, CaseIterable, Identifiable {
    case agent
    case all

    var id: String { rawValue }
    var title: String { self == .agent ? "该 Agent" : "全部 Agent" }
}

enum AgentSessionPolicy {
    static func filtered(
        _ sessions: [MobileSession],
        agentID: Int64,
        scope: AgentSessionScope
    ) -> [MobileSession] {
        scope == .agent ? sessions.filter { $0.agentId == agentID } : sessions
    }

    static func mostRecent(_ sessions: [MobileSession], agentID: Int64) -> MobileSession? {
        sessions
            .filter { $0.agentId == agentID }
            .max { lhs, rhs in (lhs.updatedAt ?? "") < (rhs.updatedAt ?? "") }
    }
}

private struct AgentDetailView: View {
    let agent: MobileAgentCatalogItem
    let client: MobileApiClient
    let fixtureDetail: MobileAgentDetail?
    let usesDeterministicFixture: Bool
    let initialSessions: [MobileSession]
    let onStartConversation: (MobileAgentCatalogItem) -> Void
    let onOpenSession: (MobileSession) -> Void
    let onUnauthorized: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @State private var detail: MobileAgentDetail?
    @State private var isLoading: Bool
    @State private var errorText: String?
    @State private var sessions: [MobileSession]
    @State private var sessionsErrorText: String?
    @State private var sessionsOpen = false
    @State private var sessionScope = AgentSessionScope.agent

    init(
        agent: MobileAgentCatalogItem,
        client: MobileApiClient,
        fixtureDetail: MobileAgentDetail?,
        usesDeterministicFixture: Bool,
        initialSessions: [MobileSession],
        onStartConversation: @escaping (MobileAgentCatalogItem) -> Void,
        onOpenSession: @escaping (MobileSession) -> Void,
        onUnauthorized: @escaping () -> Void
    ) {
        self.agent = agent
        self.client = client
        self.fixtureDetail = fixtureDetail
        self.usesDeterministicFixture = usesDeterministicFixture
        self.initialSessions = initialSessions
        self.onStartConversation = onStartConversation
        self.onOpenSession = onOpenSession
        self.onUnauthorized = onUnauthorized
        _detail = State(initialValue: fixtureDetail)
        _isLoading = State(initialValue: !usesDeterministicFixture)
        _sessions = State(initialValue: initialSessions)
    }

    var body: some View {
        Group {
            if let detail {
                detailList(detail)
            } else if isLoading {
                ProgressView("Loading configuration")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ContentUnavailableView {
                    Label("Configuration unavailable", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(errorText ?? "This Agent configuration cannot be inspected.")
                } actions: {
                    Button("Retry", systemImage: "arrow.clockwise") {
                        Task { await load() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(usesDeterministicFixture)
                }
            }
        }
        .background(CompanionStyle.warmBackground)
        .navigationTitle(agent.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .accessibilityIdentifier("agents.detail.\(agent.id)")
        .task {
            guard !usesDeterministicFixture, detail == nil else { return }
            await load()
        }
        .sheet(isPresented: $sessionsOpen) {
            NavigationStack {
                VStack(spacing: 0) {
                    Picker("Session scope", selection: $sessionScope) {
                        ForEach(AgentSessionScope.allCases) { scope in Text(scope.title).tag(scope) }
                    }
                    .pickerStyle(.segmented)
                    .padding()
                    SessionListView(
                        defaultAgent: agent.summary,
                        sessions: AgentSessionPolicy.filtered(sessions, agentID: agent.id, scope: sessionScope),
                        selectedSessionId: nil,
                        onSelect: { session in sessionsOpen = false; onOpenSession(session) },
                        onCreate: { sessionsOpen = false; onStartConversation(agent) },
                        onRefresh: { await loadSessions() }
                    )
                }
            }
        }
    }

    private func detailList(_ detail: MobileAgentDetail) -> some View {
        List {
            Section("Chat") {
                Button("开始新对话", systemImage: "plus.bubble") { onStartConversation(agent) }
                    .accessibilityIdentifier("agents.detail.startConversation")
                Button("继续最近对话", systemImage: "arrow.forward.circle") {
                    if let session = AgentSessionPolicy.mostRecent(sessions, agentID: agent.id) {
                        onOpenSession(session)
                    }
                }
                .disabled(AgentSessionPolicy.mostRecent(sessions, agentID: agent.id) == nil)
                .accessibilityIdentifier("agents.detail.continueRecent")
                Button("查看全部 Sessions", systemImage: "rectangle.stack") { sessionsOpen = true }
                    .accessibilityIdentifier("agents.detail.sessions")
                if let sessionsErrorText {
                    Label(sessionsErrorText, systemImage: "wifi.exclamationmark")
                        .font(.footnote)
                        .foregroundStyle(.red)
                    Button("重新加载 Sessions", systemImage: "arrow.clockwise") {
                        Task { await loadSessions() }
                    }
                }
            }
            Section("Overview") {
                if let description = detail.description, !description.isEmpty {
                    Text(description)
                        .foregroundStyle(.secondary)
                }
                detailField("Role", value: detail.role.displayValue)
                detailField(
                    "Model",
                    value: detail.modelId ?? "Not configured",
                    identifier: "agents.detail.model"
                )
                detailField("Status", value: detail.status.displayName)
                detailField("Source", value: detail.source.displayName)
                detailField("Visibility", value: detail.visibility.displayName)
                if detail.isDefault {
                    detailField("Default", value: "Yes")
                }
                if detail.configurationAccess == "summary" {
                    Label(
                        "Shared configuration exposes counts only. Capability names and private settings are redacted.",
                        systemImage: "lock.shield"
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
            }

            Section("Runtime") {
                detailField("Execution mode", value: detail.executionMode.displayValue)
                detailField("Thinking mode", value: detail.thinkingMode.displayValue)
                detailField("Reasoning effort", value: detail.reasoningEffort.displayValue)
                detailField("Maximum loops", value: detail.maxLoops.map(String.init) ?? "Not shared")
            }

            Section("Capabilities") {
                capabilityRow(
                    title: "Skills",
                    names: detail.skillNames,
                    value: String(detail.skillCount),
                    namesHidden: detail.configurationAccess == "summary",
                    identifier: "agents.detail.skills"
                )
                capabilityRow(
                    title: "Tools",
                    names: detail.toolNames,
                    value: detail.toolAccess.displayText(count: detail.toolCount),
                    namesHidden: detail.configurationAccess == "summary",
                    identifier: "agents.detail.tools"
                )
                LabeledContent("System skills", value: String(detail.enabledSystemSkillCount))
                promptRows(detail.promptMetadata)
            }
        }
        .scrollContentBackground(.hidden)
        .background(CompanionStyle.warmBackground)
        .safeAreaPadding(.bottom, 16)
    }

    @ViewBuilder
    private func detailField(
        _ label: String,
        value: String,
        identifier: String? = nil
    ) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 3) {
                Text(label)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier(identifier ?? label)
                Text(value)
                    .fixedSize(horizontal: false, vertical: true)
            }
        } else {
            LabeledContent {
                Text(value)
            } label: {
                Text(label)
                    .accessibilityIdentifier(identifier ?? label)
            }
            .accessibilityElement(children: .contain)
        }
    }

    private func capabilityRow(
        title: String,
        names: [String]?,
        value: String,
        namesHidden: Bool,
        identifier: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            LabeledContent {
                Text(value)
            } label: {
                Text(title)
                    .accessibilityIdentifier(identifier)
            }
            .accessibilityElement(children: .contain)
            if namesHidden {
                Text("Names hidden for shared configuration")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if let names, !names.isEmpty {
                Text(names.joined(separator: ", "))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func promptRows(_ metadata: MobileAgentPromptMetadata?) -> some View {
        if let metadata {
            promptRow("Agent prompt", metadata: metadata.agent)
            promptRow("Soul prompt", metadata: metadata.soul)
            promptRow("Tools prompt", metadata: metadata.tools)
        } else {
            LabeledContent("Prompt metadata", value: "Not shared")
        }
    }

    private func promptRow(_ title: String, metadata: MobilePromptFieldMetadata) -> some View {
        LabeledContent(
            title,
            value: metadata.configured ? "Configured · \(metadata.characterCount) characters" : "Not configured"
        )
    }

    @MainActor
    private func load() async {
        isLoading = true
        errorText = nil
        defer { isLoading = false }
        do {
            detail = try await client.getAgent(id: agent.id)
            await loadSessions()
        } catch {
            guard !Task.isCancelled else { return }
            errorText = errorMessage(for: error)
            if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
                onUnauthorized()
            }
        }
    }

    @MainActor
    private func loadSessions() async {
        guard !usesDeterministicFixture else { return }
        sessionsErrorText = nil
        do {
            sessions = try await client.listSessions()
        } catch {
            guard !Task.isCancelled else { return }
            sessionsErrorText = "Sessions 暂时无法加载。"
            if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
                onUnauthorized()
            }
        }
    }

    private func errorMessage(for error: Error) -> String {
        guard case let MobileApiError.httpStatus(status, _) = error else {
            return error.localizedDescription
        }
        switch status {
        case 401:
            return "This device connection has expired. Pair it again to inspect Agents."
        case 403:
            return "This paired device does not have Agent configuration access."
        case 404:
            return "This Agent is no longer available or visible to this device."
        default:
            return "SkillForge could not load this Agent (HTTP \(status))."
        }
    }
}

private extension MobileAgentCatalogItem {
    func rosterAccessibilitySummary(currentAgentID: Int64?) -> String {
        let badges = AgentsRosterPolicy.badges(for: self, currentAgentID: currentAgentID).map(\.title)
        return ([name] + badges + [description, status.displayName, source.displayName])
            .compactMap { $0 }
            .joined(separator: ", ")
    }
}

private extension MobileToolAccess {
    func displayText(count: Int) -> String {
        switch self {
        case .all:
            return "All registered (\(count))"
        case .allowlist:
            return "Allowlist (\(count))"
        case .unknown:
            return "Unknown"
        }
    }
}

private extension Optional where Wrapped == String {
    var displayValue: String {
        guard let self, !self.isEmpty else { return "Not configured" }
        return self.displayName
    }
}

private extension String {
    var displayName: String {
        replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
            .capitalized
    }
}
