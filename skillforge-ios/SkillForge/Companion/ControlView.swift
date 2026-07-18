import SwiftUI

struct ControlView: View {
    let endpoint: URL
    let deviceToken: String
    let agents: [MobileAgentCatalogItem]
    let currentAgentName: String?
    let isActive: Bool
    let usesDeterministicFixture: Bool
    @ObservedObject var attachmentStore: AttachmentDownloadStore
    @StateObject private var connectionMonitor: ConnectionHealthMonitor
    let onUnauthorized: @MainActor @Sendable () -> Void
    let onOpenSession: (MobileSession) -> Void
    let onOpenSource: (MobileSession, Int64) -> Void

    @State private var schedules: [MobileScheduledTask]
    @State private var sessions: [MobileSession]
    @State private var fixtureRuns: [MobileScheduledTaskRun]
    @State private var isLoading: Bool
    @State private var scheduleErrorText: String?
    @State private var sessionErrorText: String?
    @State private var fixturePersonalApps: [MobilePersonalApp]

    init(
        endpoint: URL,
        deviceToken: String,
        agents: [MobileAgentCatalogItem],
        currentAgentName: String? = nil,
        isActive: Bool,
        initialSchedules: [MobileScheduledTask] = [],
        initialRuns: [MobileScheduledTaskRun] = [],
        initialSessions: [MobileSession] = [],
        initialPersonalApps: [MobilePersonalApp] = [],
        usesDeterministicFixture: Bool = false,
        attachmentStore: AttachmentDownloadStore,
        onUnauthorized: @escaping @MainActor @Sendable () -> Void = {},
        onOpenSession: @escaping (MobileSession) -> Void,
        onOpenSource: @escaping (MobileSession, Int64) -> Void = { _, _ in }
    ) {
        self.endpoint = endpoint
        self.deviceToken = deviceToken
        self.agents = agents
        self.currentAgentName = currentAgentName
        self.isActive = isActive
        self.usesDeterministicFixture = usesDeterministicFixture
        self.attachmentStore = attachmentStore
        self.onUnauthorized = onUnauthorized
        self.onOpenSession = onOpenSession
        self.onOpenSource = onOpenSource
        _schedules = State(initialValue: initialSchedules)
        _sessions = State(initialValue: initialSessions)
        _fixtureRuns = State(initialValue: initialRuns)
        _isLoading = State(initialValue: !usesDeterministicFixture)
        _fixturePersonalApps = State(initialValue: initialPersonalApps)
        let healthClient = MobileApiClient(baseURL: endpoint, deviceToken: deviceToken)
        let pairingIdentity = endpoint.absoluteString
        let probe: ConnectionHealthMonitor.Probe
        #if DEBUG
        if usesDeterministicFixture {
            probe = {}
        } else {
            probe = { _ = try await healthClient.me() }
        }
        #else
        probe = { _ = try await healthClient.me() }
        #endif
        _connectionMonitor = StateObject(
            wrappedValue: ConnectionHealthMonitor(
                pairingIdentity: pairingIdentity,
                probe: probe,
                onUnauthorized: onUnauthorized
            )
        )
    }

    var body: some View {
        NavigationStack {
            List {
                connectionSection
                currentWorkSection
                recentConversationsSection
                scheduleSection
                workspaceSection
            }
            .scrollContentBackground(.hidden)
            .background(CompanionStyle.warmBackground)
            .safeAreaPadding(.bottom, 72)
            .navigationTitle("Control")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task { await refreshAll() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(isLoading)
                    .accessibilityLabel("Reload Control")
                }
            }
            .refreshable { await refreshAll() }
        }
        .task(id: isActive) {
            guard isActive else {
                connectionMonitor.cancel()
                return
            }
            connectionMonitor.check(pairingIdentity: endpoint.absoluteString)
            guard !usesDeterministicFixture else { return }
            await refresh()
        }
        .onDisappear { connectionMonitor.cancel() }
    }

    private var client: MobileApiClient {
        MobileApiClient(baseURL: endpoint, deviceToken: deviceToken)
    }

    @MainActor
    private func refreshAll() async {
        connectionMonitor.check(pairingIdentity: endpoint.absoluteString)
        await refresh()
    }

    private var connectionSection: some View {
        Section("Connection") {
            Label {
                VStack(alignment: .leading, spacing: 3) {
                    Text(connectionMonitor.state.phase.controlTitle)
                        .font(.body.weight(.semibold))
                    Text(endpointDisplayText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if let currentAgentName, !currentAgentName.isEmpty {
                        Text("Current Agent · \(currentAgentName)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            } icon: {
                Image(systemName: "desktopcomputer")
                    .foregroundStyle(CompanionStyle.orange)
            }
            .accessibilityIdentifier("control.connection")
        }
    }

    @ViewBuilder
    private var currentWorkSection: some View {
        Section("Current work") {
            if isLoading && sessions.isEmpty {
                loadingRow("Loading current work")
            } else if let sessionErrorText, sessions.isEmpty {
                errorRow(sessionErrorText)
            } else if currentWorkSessions.isEmpty {
                Label("Nothing needs attention", systemImage: "checkmark.circle")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("control.currentWork.empty")
            } else {
                ForEach(currentWorkSessions) { session in
                    sessionButton(session, identifierPrefix: "control.currentWork")
                }
                if let sessionErrorText {
                    errorRow(sessionErrorText)
                }
            }
        }
    }

    @ViewBuilder
    private var recentConversationsSection: some View {
        Section("Recent conversations") {
            if isLoading && sessions.isEmpty {
                loadingRow("Loading conversations")
            } else if let sessionErrorText, sessions.isEmpty {
                errorRow(sessionErrorText)
            } else if recentSessions.isEmpty {
                Label("No conversations yet", systemImage: "bubble.left.and.bubble.right")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("control.recent.empty")
            } else {
                ForEach(recentSessions) { session in
                    sessionButton(session, identifierPrefix: "control.recent")
                }
                if let sessionErrorText {
                    errorRow(sessionErrorText)
                }
            }
        }
    }

    @ViewBuilder
    private var scheduleSection: some View {
        Section("Automations") {
            if isLoading && schedules.isEmpty {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("Loading schedules")
                        .foregroundStyle(.secondary)
                }
            } else if schedules.isEmpty {
                VStack(alignment: .leading, spacing: 5) {
                    Label("No scheduled automations", systemImage: "clock.badge.questionmark")
                        .font(.body.weight(.semibold))
                    Text(scheduleErrorText ?? "Create a schedule in the SkillForge Dashboard.")
                        .font(.caption)
                        .foregroundStyle(scheduleErrorText == nil ? Color.secondary : Color.red)
                }
                .padding(.vertical, 6)
            } else {
                ForEach(schedules) { schedule in
                    NavigationLink {
                        ScheduleDetailView(
                            initialTask: schedule,
                            agentName: agentName(for: schedule.agentId),
                            client: client,
                            initialRuns: fixtureRuns.filter { $0.taskId == schedule.id },
                            usesDeterministicFixture: usesDeterministicFixture,
                            onUnauthorized: onUnauthorized,
                            onTaskChanged: updateSchedule,
                            onOpenSession: openSession
                        )
                    } label: {
                        ScheduleRow(schedule: schedule, agentName: agentName(for: schedule.agentId))
                    }
                    .accessibilityIdentifier("control.schedule.\(schedule.id)")
                }
                if let scheduleErrorText {
                    Label(scheduleErrorText, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        }
    }

    private var workspaceSection: some View {
        Section("Workspace") {
            NavigationLink {
                WorkspaceView(
                    endpoint: endpoint,
                    deviceToken: deviceToken,
                    agents: agents,
                    sessions: sessions,
                    sessionErrorText: sessionErrorText,
                    initialPersonalApps: fixturePersonalApps,
                    usesDeterministicFixture: usesDeterministicFixture,
                    attachmentStore: attachmentStore,
                    onUnauthorized: onUnauthorized,
                    onOpenSession: onOpenSession,
                    onOpenSource: openPersonalAppSource
                )
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Workspace")
                            .font(.body.weight(.semibold))
                        Text("Personal Apps and conversations")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "square.grid.2x2.fill")
                        .foregroundStyle(CompanionStyle.orange)
                }
            }
            .accessibilityIdentifier("control.workspace")

            NavigationLink {
                ControlSessionsView(
                    sessions: sessions,
                    agents: agents,
                    errorText: sessionErrorText,
                    onOpenSession: onOpenSession
                )
            } label: {
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Sessions")
                            .font(.body.weight(.semibold))
                        Text(sessionSubtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "bubble.left.and.text.bubble.right.fill")
                        .foregroundStyle(.blue)
                }
            }
            .accessibilityIdentifier("control.sessions")
        }
    }

    @MainActor
    private func openPersonalAppSource(sessionID: String, sourceMessageSeq: Int64) async throws {
        if let session = sessions.first(where: { $0.id == sessionID }) {
            onOpenSource(session, sourceMessageSeq)
            return
        }
        guard !usesDeterministicFixture else { throw MobileApiError.invalidResponse }
        let session = try await client.getSession(sessionId: sessionID)
        onOpenSource(session, sourceMessageSeq)
    }

    private var sessionSubtitle: String {
        if let sessionErrorText, sessions.isEmpty {
            return sessionErrorText
        }
        return sessions.isEmpty ? "No conversations yet" : "\(sessions.count) conversations"
    }

    private var endpointDisplayText: String {
        guard let host = endpoint.host else { return "Paired endpoint saved" }
        if let port = endpoint.port { return "\(host):\(port)" }
        return host
    }

    private var currentWorkSessions: [MobileSession] {
        ControlPresentationPolicy.currentWorkSessions(sessions)
    }

    private var recentSessions: [MobileSession] {
        ControlPresentationPolicy.recentSessions(sessions, limit: 3)
    }

    private func sessionButton(_ session: MobileSession, identifierPrefix: String) -> some View {
        let status = ControlPresentationPolicy.workStatus(session)
        return Button {
            onOpenSession(session)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: status.symbol)
                    .foregroundStyle(status.color)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text(ControlPresentationPolicy.sessionTitle(session))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text("\(agentName(for: session.agentId)) · \(status.text)")
                        .font(.caption)
                        .foregroundStyle(status.color)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("\(identifierPrefix).\(session.id)")
    }

    private func loadingRow(_ text: String) -> some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(text).foregroundStyle(.secondary)
        }
    }

    private func errorRow(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.caption)
            .foregroundStyle(.red)
    }

    @MainActor
    private func refresh() async {
        isLoading = true
        scheduleErrorText = nil
        sessionErrorText = nil
        defer { isLoading = false }

        do {
            let loaded = try await client.listSchedules()
            guard !Task.isCancelled else { return }
            schedules = ControlPresentationPolicy.orderedSchedules(loaded)
        } catch {
            guard !Task.isCancelled else { return }
            if handleUnauthorized(error) { return }
            scheduleErrorText = scheduleError(error)
        }

        do {
            let loaded = try await client.listSessions()
            guard !Task.isCancelled else { return }
            sessions = loaded
        } catch {
            guard !Task.isCancelled else { return }
            if handleUnauthorized(error) { return }
            sessionErrorText = error.localizedDescription
        }
    }

    private func updateSchedule(_ task: MobileScheduledTask) {
        guard let index = schedules.firstIndex(where: { $0.id == task.id }) else { return }
        schedules[index] = task
        schedules = ControlPresentationPolicy.orderedSchedules(schedules)
    }

    @MainActor
    private func openSession(_ sessionId: String) async throws {
        if let session = sessions.first(where: { $0.id == sessionId }) {
            onOpenSession(session)
            return
        }
        guard !usesDeterministicFixture else {
            throw MobileApiError.invalidResponse
        }
        onOpenSession(try await client.getSession(sessionId: sessionId))
    }

    private func handleUnauthorized(_ error: Error) -> Bool {
        guard case let MobileApiError.httpStatus(status, _) = error, status == 401 else {
            return false
        }
        onUnauthorized()
        return true
    }

    private func scheduleError(_ error: Error) -> String {
        if case let MobileApiError.httpStatus(status, _) = error, status == 403 {
            return "Schedule access is not enabled for this paired device."
        }
        return error.localizedDescription
    }

    private func agentName(for id: Int64) -> String {
        agents.first(where: { $0.id == id })?.name ?? "Agent #\(id)"
    }
}

private struct ScheduleRow: View {
    let schedule: MobileScheduledTask
    let agentName: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: schedule.enabled ? "clock.arrow.circlepath" : "pause.circle.fill")
                .foregroundStyle(schedule.enabled ? CompanionStyle.orange : .secondary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(schedule.name)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    if schedule.system {
                        Text("SYSTEM")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)
                    }
                }
                Text("\(agentName) · \(ControlPresentationPolicy.scheduleText(schedule))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(ControlPresentationPolicy.nextRunText(schedule))
                    .font(.caption2)
                    .foregroundStyle(schedule.enabled ? Color.secondary : Color.orange)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(ControlPresentationPolicy.statusText(schedule))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(ControlPresentationPolicy.statusColor(schedule))
        }
        .padding(.vertical, 5)
    }
}

private struct ScheduleDetailView: View {
    @State private var task: MobileScheduledTask
    @State private var runs: [MobileScheduledTaskRun]
    @State private var isLoadingRuns: Bool
    @State private var isMutating = false
    @State private var errorText: String?
    @State private var statusText: String?

    let agentName: String
    let client: MobileApiClient
    let usesDeterministicFixture: Bool
    let onUnauthorized: () -> Void
    let onTaskChanged: (MobileScheduledTask) -> Void
    let onOpenSession: (String) async throws -> Void

    init(
        initialTask: MobileScheduledTask,
        agentName: String,
        client: MobileApiClient,
        initialRuns: [MobileScheduledTaskRun],
        usesDeterministicFixture: Bool,
        onUnauthorized: @escaping () -> Void,
        onTaskChanged: @escaping (MobileScheduledTask) -> Void,
        onOpenSession: @escaping (String) async throws -> Void
    ) {
        _task = State(initialValue: initialTask)
        _runs = State(initialValue: initialRuns)
        _isLoadingRuns = State(initialValue: !usesDeterministicFixture)
        self.agentName = agentName
        self.client = client
        self.usesDeterministicFixture = usesDeterministicFixture
        self.onUnauthorized = onUnauthorized
        self.onTaskChanged = onTaskChanged
        self.onOpenSession = onOpenSession
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Label(ControlPresentationPolicy.statusText(task), systemImage: statusSymbol)
                            .foregroundStyle(ControlPresentationPolicy.statusColor(task))
                        Spacer()
                        Text(task.enabled ? "Enabled" : "Paused")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(task.enabled ? .green : .secondary)
                    }
                    Text(task.promptPreview ?? "No prompt preview")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(4)
                }
                .padding(.vertical, 4)
            }

            Section("Schedule") {
                LabeledContent("Agent", value: agentName)
                LabeledContent("Trigger", value: ControlPresentationPolicy.scheduleText(task))
                LabeledContent("Timezone", value: task.timezone)
                LabeledContent("Session", value: task.sessionMode == "reuse" ? "Reuse" : "New each run")
                LabeledContent("Next", value: ControlPresentationPolicy.nextRunText(task))
            }

            Section("Actions") {
                Button {
                    Task { await runNow() }
                } label: {
                    Label("Run Now", systemImage: "play.fill")
                }
                .disabled(isMutating)
                .accessibilityIdentifier("control.schedule.run.\(task.id)")

                Button {
                    Task { await toggleEnabled() }
                } label: {
                    Label(task.enabled ? "Pause Schedule" : "Enable Schedule",
                          systemImage: task.enabled ? "pause.fill" : "checkmark")
                }
                .disabled(isMutating)
                .accessibilityIdentifier("control.schedule.toggle.\(task.id)")

                if isMutating {
                    ProgressView()
                }
                if let statusText {
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(.green)
                }
                if let errorText {
                    Text(errorText)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            Section("Recent Runs") {
                if isLoadingRuns && runs.isEmpty {
                    ProgressView("Loading runs")
                } else if runs.isEmpty {
                    ContentUnavailableView(
                        "No runs yet",
                        systemImage: "clock.badge.questionmark",
                        description: Text("Run this schedule to create an execution record.")
                    )
                } else {
                    ForEach(runs) { run in
                        runRow(run)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(CompanionStyle.warmBackground)
        .navigationTitle(task.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task {
            guard !usesDeterministicFixture else { return }
            await loadRuns()
        }
    }

    private var statusSymbol: String {
        return switch task.status.lowercased() {
        case "running": "bolt.fill"
        case "error": "exclamationmark.triangle.fill"
        default: task.enabled ? "checkmark.circle.fill" : "pause.circle.fill"
        }
    }

    private func runRow(_ run: MobileScheduledTaskRun) -> some View {
        Button {
            if let sessionId = run.sessionId {
                Task { @MainActor in
                    do {
                        try await onOpenSession(sessionId)
                    } catch {
                        handle(error)
                    }
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: ControlPresentationPolicy.runSymbol(run.status))
                    .foregroundStyle(ControlPresentationPolicy.runColor(run.status))
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 3) {
                    Text(ControlPresentationPolicy.runStatusText(run.status))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(ControlPresentationPolicy.runDetail(run))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    if let errorMessage = run.errorMessage, !errorMessage.isEmpty {
                        Text(errorMessage)
                            .font(.caption2)
                            .foregroundStyle(.red)
                            .lineLimit(2)
                    }
                }
                Spacer()
                if run.sessionId != nil {
                    Image(systemName: "bubble.left.fill")
                        .foregroundStyle(.blue)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(run.sessionId == nil)
        .accessibilityIdentifier("control.run.\(run.id)")
    }

    @MainActor
    private func loadRuns() async {
        isLoadingRuns = true
        defer { isLoadingRuns = false }
        do {
            runs = try await client.listScheduleRuns(taskId: task.id)
        } catch {
            handle(error)
        }
    }

    @MainActor
    private func runNow() async {
        isMutating = true
        errorText = nil
        statusText = nil
        defer { isMutating = false }
        if usesDeterministicFixture {
            statusText = "Run queued"
            return
        }
        do {
            _ = try await client.triggerSchedule(taskId: task.id)
            statusText = "Run queued"
            try? await Task.sleep(for: .milliseconds(400))
            await loadRuns()
        } catch {
            handle(error)
        }
    }

    @MainActor
    private func toggleEnabled() async {
        isMutating = true
        errorText = nil
        statusText = nil
        defer { isMutating = false }
        do {
            let updated = usesDeterministicFixture
                ? task.withEnabled(!task.enabled)
                : try await client.setScheduleEnabled(taskId: task.id, enabled: !task.enabled)
            task = updated
            onTaskChanged(updated)
            statusText = updated.enabled ? "Schedule enabled" : "Schedule paused"
        } catch {
            handle(error)
        }
    }

    private func handle(_ error: Error) {
        if case let MobileApiError.httpStatus(status, _) = error, status == 401 {
            onUnauthorized()
        } else {
            errorText = error.localizedDescription
        }
    }
}

struct ControlSessionsView: View {
    let sessions: [MobileSession]
    let agents: [MobileAgentCatalogItem]
    let errorText: String?
    let onOpenSession: (MobileSession) -> Void

    var body: some View {
        List {
            if sessions.isEmpty {
                ContentUnavailableView {
                    Label("No sessions", systemImage: "bubble.left.and.bubble.right")
                } description: {
                    Text(errorText ?? "Start a conversation in Chat and it will appear here.")
                }
                .listRowBackground(Color.clear)
            } else {
                ForEach(ControlPresentationPolicy.orderedSessions(sessions)) { session in
                    Button {
                        onOpenSession(session)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "bubble.left.fill")
                                .foregroundStyle(.blue)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(displayTitle(session))
                                    .font(.body.weight(.semibold))
                                    .foregroundStyle(.primary)
                                Text("\(agentName(session.agentId)) · \(session.runtimeStatus ?? session.status ?? "idle")")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("control.session.\(session.id)")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(CompanionStyle.warmBackground)
        .navigationTitle("Sessions")
        .toolbar(.hidden, for: .tabBar)
    }

    private func agentName(_ id: Int64) -> String {
        agents.first(where: { $0.id == id })?.name ?? "Agent #\(id)"
    }

    private func displayTitle(_ session: MobileSession) -> String {
        guard let title = session.title?.trimmingCharacters(in: .whitespacesAndNewlines),
              !title.isEmpty else {
            return "New Session"
        }
        return title
    }
}

enum ControlPresentationPolicy {
    static func orderedSchedules(_ schedules: [MobileScheduledTask]) -> [MobileScheduledTask] {
        schedules.sorted { lhs, rhs in
            if lhs.enabled != rhs.enabled { return lhs.enabled && !rhs.enabled }
            return (lhs.nextFireAt ?? "9999") < (rhs.nextFireAt ?? "9999")
        }
    }

    static func orderedSessions(_ sessions: [MobileSession]) -> [MobileSession] {
        sessions.sorted { ($0.updatedAt ?? "") > ($1.updatedAt ?? "") }
    }

    static func currentWorkSessions(_ sessions: [MobileSession], limit: Int = 5) -> [MobileSession] {
        Array(
            orderedSessions(sessions)
                .filter { SessionListPolicy.group(for: $0) != .other }
                .prefix(max(0, limit))
        )
    }

    static func recentSessions(_ sessions: [MobileSession], limit: Int = 3) -> [MobileSession] {
        Array(orderedSessions(sessions).prefix(max(0, limit)))
    }

    static func workStatus(_ session: MobileSession) -> ControlWorkStatus {
        switch SessionListPolicy.group(for: session) {
        case .running: .running
        case .waiting: .waiting
        case .error: .error
        case .other: .idle
        }
    }

    static func sessionTitle(_ session: MobileSession) -> String {
        guard let title = session.title?.trimmingCharacters(in: .whitespacesAndNewlines),
              !title.isEmpty else { return "New Session" }
        return title
    }

    static func scheduleText(_ task: MobileScheduledTask) -> String {
        if let cronExpr = task.cronExpr { return "Cron \(cronExpr)" }
        if let oneShotAt = task.oneShotAt, let date = parseDate(oneShotAt) {
            return date.formatted(date: .abbreviated, time: .shortened)
        }
        return "Schedule configured"
    }

    static func nextRunText(_ task: MobileScheduledTask) -> String {
        guard task.enabled else { return "Paused" }
        guard let value = task.nextFireAt, let date = parseDate(value) else { return "Next run pending" }
        return "Next \(date.formatted(.relative(presentation: .named)))"
    }

    static func statusText(_ task: MobileScheduledTask) -> String {
        guard task.enabled else { return "Paused" }
        return switch task.status.lowercased() {
        case "running": "Running"
        case "error": "Error"
        case "completed": "Complete"
        default: "Ready"
        }
    }

    static func statusColor(_ task: MobileScheduledTask) -> Color {
        guard task.enabled else { return Color.secondary }
        return switch task.status.lowercased() {
        case "running": .blue
        case "error": .red
        default: .green
        }
    }

    static func runStatusText(_ status: String) -> String {
        switch status.lowercased() {
        case "running": "Running"
        case "success": "Succeeded"
        case "failure": "Failed"
        case "paused": "Waiting"
        case "skipped": "Skipped"
        default: status.capitalized
        }
    }

    static func runSymbol(_ status: String) -> String {
        switch status.lowercased() {
        case "running": "bolt.fill"
        case "success": "checkmark.circle.fill"
        case "failure": "exclamationmark.triangle.fill"
        case "paused": "person.crop.circle.badge.questionmark"
        default: "forward.end.circle.fill"
        }
    }

    static func runColor(_ status: String) -> Color {
        switch status.lowercased() {
        case "running": .blue
        case "success": .green
        case "failure": .red
        case "paused": CompanionStyle.orange
        default: .secondary
        }
    }

    static func runDetail(_ run: MobileScheduledTaskRun) -> String {
        let trigger = run.manual ? "Manual" : "Scheduled"
        guard let date = parseDate(run.triggeredAt) else { return trigger }
        return "\(trigger) · \(date.formatted(date: .abbreviated, time: .shortened))"
    }

    static func parseDate(_ value: String?) -> Date? {
        guard let value else { return nil }
        return (try? Date.ISO8601FormatStyle(includingFractionalSeconds: true).parse(value))
            ?? (try? Date.ISO8601FormatStyle().parse(value))
    }
}

enum ControlWorkStatus {
    case running
    case waiting
    case error
    case idle

    var text: String {
        switch self {
        case .running: "Running"
        case .waiting: "Waiting"
        case .error: "Needs attention"
        case .idle: "Idle"
        }
    }

    var symbol: String {
        switch self {
        case .running: "bolt.fill"
        case .waiting: "person.crop.circle.badge.questionmark"
        case .error: "exclamationmark.triangle.fill"
        case .idle: "bubble.left.fill"
        }
    }

    var color: Color {
        switch self {
        case .running: .blue
        case .waiting: .orange
        case .error: .red
        case .idle: .secondary
        }
    }
}

private extension ConnectionHealthPhase {
    var controlTitle: String {
        switch self {
        case .notChecked: "SkillForge Mac paired"
        case .checking: "Checking SkillForge Mac"
        case .healthy: "SkillForge Mac connected"
        case .offline: "SkillForge Mac offline"
        case .serviceIssue: "SkillForge Mac needs attention"
        }
    }
}
