import SwiftUI
import UIKit

struct WorkspaceView: View {
    let endpoint: URL
    let deviceToken: String
    let agents: [MobileAgentCatalogItem]
    let sessions: [MobileSession]
    let sessionErrorText: String?
    let initialPersonalApps: [MobilePersonalApp]
    let usesDeterministicFixture: Bool
    @ObservedObject var attachmentStore: AttachmentDownloadStore
    let onUnauthorized: @MainActor @Sendable () -> Void
    let onOpenSession: (MobileSession) -> Void
    let onOpenSource: @MainActor (String, Int64) async throws -> Void

    var body: some View {
        List {
            Section("Library") {
                NavigationLink {
                    PersonalAppLibraryView(
                        endpoint: endpoint,
                        deviceToken: deviceToken,
                        agents: agents,
                        sessions: sessions,
                        initialApps: initialPersonalApps,
                        usesDeterministicFixture: usesDeterministicFixture,
                        store: attachmentStore,
                        onUnauthorized: onUnauthorized,
                        onOpenSource: onOpenSource
                    )
                } label: {
                    WorkspaceRow(
                        title: "Personal Apps",
                        subtitle: "Interactive results, ready to reopen",
                        systemImage: "app.badge.fill",
                        tint: .indigo
                    )
                }
                .accessibilityIdentifier("workspace.personalApps")
            }

            Section("Conversations") {
                NavigationLink {
                    ControlSessionsView(
                        sessions: sessions,
                        agents: agents,
                        errorText: sessionErrorText,
                        onOpenSession: onOpenSession
                    )
                } label: {
                    WorkspaceRow(
                        title: "Sessions",
                        subtitle: sessions.isEmpty ? "No conversations yet" : "\(sessions.count) conversations",
                        systemImage: "bubble.left.and.text.bubble.right.fill",
                        tint: .blue
                    )
                }
                .accessibilityIdentifier("workspace.sessions")
            }
        }
        .scrollContentBackground(.hidden)
        .background(CompanionStyle.warmBackground)
        .navigationTitle("Workspace")
        .accessibilityIdentifier("workspace.screen")
    }
}

private struct WorkspaceRow: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let tint: Color

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.body.weight(.semibold))
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(.white)
                .frame(width: 38, height: 38)
                .background(tint.gradient, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }
}

private enum PersonalAppLibraryLoadState: Equatable {
    case loading
    case ready
    case offline(String)
    case unavailable(String)
}

private enum PersonalAppLibraryTimeFilter: String, CaseIterable, Identifiable {
    case anytime
    case sevenDays
    case thirtyDays

    var id: String { rawValue }
    var label: String {
        switch self {
        case .anytime: "Any time"
        case .sevenDays: "Last 7 days"
        case .thirtyDays: "Last 30 days"
        }
    }

    func createdAfter(relativeTo now: Date) -> Date? {
        switch self {
        case .anytime: nil
        case .sevenDays: Calendar.current.date(byAdding: .day, value: -7, to: now)
        case .thirtyDays: Calendar.current.date(byAdding: .day, value: -30, to: now)
        }
    }
}

private struct PersonalAppViewerRoute: Identifiable {
    let item: PersonalAppLibraryItem
    let url: URL
    var id: String { item.artifactId }
}

private struct PersonalAppSnapshotSubmission: Identifiable, Equatable {
    let id = UUID()
    let artifactID: String
    let sessionID: String
    let message: String
    var failure: String?
}

struct PersonalAppLibraryView: View {
    let endpoint: URL
    let deviceToken: String
    let agents: [MobileAgentCatalogItem]
    let sessions: [MobileSession]
    let initialApps: [MobilePersonalApp]
    let usesDeterministicFixture: Bool
    @ObservedObject var store: AttachmentDownloadStore
    let onUnauthorized: @MainActor @Sendable () -> Void
    let onOpenSource: @MainActor (String, Int64) async throws -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var accumulator = PersonalAppPageAccumulator()
    @State private var serverItems: [MobilePersonalApp] = []
    @State private var localRecords: [PersonalAppLocalRecord] = []
    @State private var displayedItems: [PersonalAppLibraryItem] = []
    @State private var loadState: PersonalAppLibraryLoadState = .loading
    @State private var scope: PersonalAppLibraryScope = .recent
    @State private var searchText = ""
    @State private var selectedAgentID: Int64?
    @State private var selectedSessionID: String?
    @State private var timeFilter: PersonalAppLibraryTimeFilter = .anytime
    @State private var isLoadingMore = false
    @State private var isOpeningID: String?
    @State private var viewerRoute: PersonalAppViewerRoute?
    @State private var shareURL: PersonalAppLibraryShareItem?
    @State private var operationMessage: String?
    @State private var pendingSnapshot: PersonalAppSnapshotSubmission?
    @State private var snapshotSubmissionGate = PersonalAppSnapshotSubmissionGate()
    @State private var favoriteMutationGate = PersonalAppFavoriteMutationGate()
    @State private var refreshAuthority = PersonalAppRefreshAuthority()
    @State private var activeQueryPlan: PersonalAppLibraryQueryPlan?

    var body: some View {
        VStack(spacing: 0) {
            libraryHeader
            statusBanner
            content
        }
        .background(CompanionStyle.warmBackground)
        .navigationTitle("Personal Apps")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: searchText) { oldValue, newValue in
            if !oldValue.isEmpty, newValue.isEmpty { refreshForControlChange() }
        }
        .onChange(of: scope) { _, _ in refreshForControlChange() }
        .onChange(of: selectedAgentID) { _, _ in refreshForControlChange() }
        .onChange(of: selectedSessionID) { _, _ in refreshForControlChange() }
        .onChange(of: timeFilter) { _, _ in refreshForControlChange() }
        .task { await refresh() }
        .fullScreenCover(item: $viewerRoute) { route in
            PersonalAppViewer(
                sessionID: route.item.app.sessionId,
                attachment: attachment(for: route.item.app),
                htmlURL: route.url,
                store: store,
                onUnauthorized: onUnauthorized,
                onSubmitSnapshot: { message in
                    queueSnapshot(message, for: route.item.app)
                }
            )
        }
        .sheet(item: $shareURL) { item in
            PersonalAppLibraryActivityView(items: [item.url])
        }
    }

    private var libraryHeader: some View {
        VStack(spacing: 0) {
            searchBar
            scopePicker
            filterBar
        }
        .padding(.bottom, 2)
        .background(.regularMaterial)
        .overlay(alignment: .bottom) {
            Divider().opacity(0.55)
        }
        .zIndex(1)
    }

    private var searchBar: some View {
        HStack(spacing: 9) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            TextField("Search Personal Apps", text: $searchText)
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                .onSubmit { refreshForControlChange() }
                .accessibilityIdentifier("personalApps.search")
            if !searchText.isEmpty {
                Button {
                    searchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
                .accessibilityIdentifier("personalApps.search.clear")
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.top, 10)
    }

    @ViewBuilder
    private var scopePicker: some View {
        if dynamicTypeSize.isAccessibilitySize {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    Menu {
                        ForEach(PersonalAppLibraryScope.allCases) { candidate in
                            Button(candidate.label) { scope = candidate }
                        }
                    } label: {
                        Label("View: \(scope.label)", systemImage: "square.grid.2x2")
                            .font(.body.weight(.semibold))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(Color(uiColor: .secondarySystemGroupedBackground), in: Capsule())
                    }
                    .accessibilityIdentifier("personalApps.scope.menu")

                    Menu {
                        Section("Agent") {
                            Button("All agents") { selectedAgentID = nil }
                            ForEach(agentOptions, id: \.id) { agent in
                                Button(agent.name) { selectedAgentID = agent.id }
                            }
                        }
                        Section("Session") {
                            Button("All sessions") { selectedSessionID = nil }
                            ForEach(sessionOptions, id: \.id) { session in
                                Button(session.title) { selectedSessionID = session.id }
                            }
                        }
                        Section("Created") {
                            ForEach(PersonalAppLibraryTimeFilter.allCases) { candidate in
                                Button(candidate.label) { timeFilter = candidate }
                            }
                        }
                    } label: {
                        Label("Filters", systemImage: "line.3.horizontal.decrease")
                            .font(.body.weight(.semibold))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(Color(uiColor: .secondarySystemGroupedBackground), in: Capsule())
                    }
                    .accessibilityIdentifier("personalApps.filter.menu")
                    .accessibilityValue(accessibilityFilterSummary)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(PersonalAppLibraryScope.allCases) { candidate in
                        Button {
                            scope = candidate
                        } label: {
                            Text(candidate.label)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(scope == candidate ? .white : .primary)
                                .padding(.horizontal, 14)
                                .frame(minHeight: 38)
                                .background(
                                    scope == candidate ? Color.indigo : Color(uiColor: .secondarySystemGroupedBackground),
                                    in: Capsule()
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("personalApps.scope.\(candidate.rawValue)")
                        .accessibilityAddTraits(scope == candidate ? .isSelected : [])
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
    }

    @ViewBuilder
    private var filterBar: some View {
        if !dynamicTypeSize.isAccessibilitySize {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Menu {
                        Button("All agents") { selectedAgentID = nil }
                        ForEach(agentOptions, id: \.id) { agent in
                            Button(agent.name) { selectedAgentID = agent.id }
                        }
                    } label: {
                        FilterChip(title: selectedAgentName ?? "Agent", active: selectedAgentID != nil)
                    }
                    .accessibilityIdentifier("personalApps.filter.agent")

                    Menu {
                        Button("All sessions") { selectedSessionID = nil }
                        ForEach(sessionOptions, id: \.id) { session in
                            Button(session.title) { selectedSessionID = session.id }
                        }
                    } label: {
                        FilterChip(title: selectedSessionTitle ?? "Session", active: selectedSessionID != nil)
                    }
                    .accessibilityIdentifier("personalApps.filter.session")

                    Menu {
                        ForEach(PersonalAppLibraryTimeFilter.allCases) { candidate in
                            Button(candidate.label) { timeFilter = candidate }
                        }
                    } label: {
                        FilterChip(title: timeFilter.label, active: timeFilter != .anytime)
                    }
                    .accessibilityIdentifier("personalApps.filter.time")
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
        }
    }

    @ViewBuilder
    private var statusBanner: some View {
        if case let .offline(message) = loadState {
            banner(message, systemImage: "wifi.slash", color: .orange, identifier: "personalApps.offline")
        } else if let operationMessage {
            banner(operationMessage, systemImage: "info.circle", color: .indigo, identifier: "personalApps.notice")
        }
        if let pendingSnapshot, let failure = pendingSnapshot.failure {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                Text(failure)
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .background(Color.orange.opacity(0.10))
            .accessibilityIdentifier("personalApps.snapshot.failure")
        }
    }

    @ViewBuilder
    private var content: some View {
        switch loadState {
        case .loading where displayedItems.isEmpty:
            PersonalAppLibraryLoadingView()
        case let .unavailable(message) where displayedItems.isEmpty:
            ContentUnavailableView {
                Label("Personal Apps unavailable", systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button("Try Again") { Task { await refresh() } }
                    .accessibilityIdentifier("personalApps.retry")
            }
        default:
            if filteredItems.isEmpty {
                ContentUnavailableView(
                    emptyTitle,
                    systemImage: scope == .downloaded ? "arrow.down.circle" : "app.dashed",
                    description: Text(emptyDescription)
                )
                .accessibilityIdentifier("personalApps.empty")
            } else {
                ScrollView {
                    LazyVStack(spacing: 14) {
                        ForEach(filteredItems) { item in
                            PersonalAppLibraryCard(
                                item: item,
                                isOpening: isOpeningID == item.artifactId,
                                isFavoriting: favoriteMutationGate.contains(artifactID: item.artifactId),
                                onOpen: { Task { await open(item) } },
                                onFavorite: { Task { await toggleFavorite(item) } },
                                onShare: { Task { await share(item) } },
                                onSource: { Task { await openSource(item) } },
                                onClearCache: { Task { await clearCache(item) } }
                            )
                            .id(item.artifactId)
                            .task { await loadMoreIfNeeded(after: item) }
                        }
                        if isLoadingMore {
                            ProgressView().padding()
                                .accessibilityIdentifier("personalApps.loadingMore")
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 6)
                    .padding(.bottom, 32)
                }
                .refreshable { await refresh() }
                .accessibilityIdentifier("personalApps.list")
            }
        }
    }

    private var filteredItems: [PersonalAppLibraryItem] {
        PersonalAppLibrarySelection.apply(
            scope: scope,
            search: searchText,
            agentID: selectedAgentID,
            sessionID: selectedSessionID,
            createdAfter: activeQueryPlan?.createdAfter,
            to: displayedItems
        )
    }

    private var filterUniverse: [MobilePersonalApp] {
        var byID = Dictionary(uniqueKeysWithValues: localRecords.map { ($0.artifactId, $0.asMobilePersonalApp) })
        for item in serverItems { byID[item.artifactId] = item }
        return Array(byID.values)
    }

    private var agentOptions: [PersonalAppAgentOption] {
        PersonalAppLibraryFilterOptions.agents(catalog: agents, apps: filterUniverse)
    }

    private var sessionOptions: [PersonalAppSessionOption] {
        PersonalAppLibraryFilterOptions.sessions(catalog: sessions, apps: filterUniverse)
    }

    private var selectedAgentName: String? {
        guard let selectedAgentID else { return nil }
        return agentOptions.first(where: { $0.id == selectedAgentID })?.name
    }

    private var selectedSessionTitle: String? {
        guard let selectedSessionID else { return nil }
        return sessionOptions.first(where: { $0.id == selectedSessionID })?.title
    }

    private var accessibilityFilterSummary: String {
        [selectedAgentName ?? "All agents", selectedSessionTitle ?? "All sessions", timeFilter.label]
            .joined(separator: ", ")
    }

    private var emptyTitle: String {
        scope == .downloaded ? "No downloaded Personal Apps" : "No Personal Apps found"
    }

    private var emptyDescription: String {
        scope == .downloaded
            ? "Open a Personal App once to keep a verified offline copy on this device."
            : "Try another search or filter, or generate an interactive result in Chat."
    }

    @MainActor
    private func refresh() async {
        let generation = refreshAuthority.begin()
        let queryPlan = makeQueryPlan(referenceDate: Date())
        activeQueryPlan = queryPlan
        loadState = displayedItems.isEmpty ? .loading : .ready
        operationMessage = nil
        accumulator.reset()
        localRecords = await store.localPersonalApps()
        guard refreshAuthority.accepts(generation) else { return }

        #if DEBUG
        if usesDeterministicFixture {
            if ProcessInfo.processInfo.arguments.contains("--personal-app-library-hold-loading") {
                do {
                    try await Task.sleep(for: .seconds(30))
                } catch {
                    return
                }
                guard refreshAuthority.accepts(generation) else { return }
            }
            serverItems = initialApps
            for app in initialApps { await store.recordPersonalAppMetadata(app) }
            localRecords = await store.localPersonalApps()
            guard refreshAuthority.accepts(generation) else { return }
            if ProcessInfo.processInfo.arguments.contains("--personal-app-library-offline") {
                displayedItems = PersonalAppLibraryMerge.merge(
                    server: [],
                    local: localRecords,
                    authority: .offlineCache
                )
                loadState = .offline("Showing verified copies saved on this device.")
            } else {
                rebuildDisplayedItems()
                loadState = .ready
            }
            return
        }
        #endif

        do {
            let batch = try await fetchVisiblePageBatch(
                queryPlan: queryPlan,
                startingCursor: nil,
                generation: generation
            )
            guard refreshAuthority.accepts(generation) else { return }
            let fetchedItems = append(batch)
            serverItems = accumulator.items
            for app in fetchedItems { await store.recordPersonalAppMetadata(app) }
            localRecords = await store.localPersonalApps()
            guard refreshAuthority.accepts(generation) else { return }
            rebuildDisplayedItems()
            loadState = .ready
        } catch {
            guard refreshAuthority.accepts(generation) else { return }
            await handleListFailure(error)
        }
    }

    @MainActor
    private func loadMoreIfNeeded(after item: PersonalAppLibraryItem) async {
        guard scope != .downloaded,
              item.id == filteredItems.last?.id,
              let cursor = accumulator.nextCursor,
              let queryPlan = activeQueryPlan,
              !isLoadingMore,
              !usesDeterministicFixture else { return }
        isLoadingMore = true
        let generation = refreshAuthority.generation
        defer { isLoadingMore = false }
        do {
            let batch = try await fetchVisiblePageBatch(
                queryPlan: queryPlan,
                startingCursor: cursor,
                generation: generation
            )
            guard refreshAuthority.accepts(generation) else { return }
            let fetchedItems = append(batch)
            serverItems = accumulator.items
            for app in fetchedItems { await store.recordPersonalAppMetadata(app) }
            localRecords = await store.localPersonalApps()
            guard refreshAuthority.accepts(generation) else { return }
            rebuildDisplayedItems()
        } catch {
            guard refreshAuthority.accepts(generation) else { return }
            if isUnauthorized(error) {
                onUnauthorized()
            } else {
                operationMessage = "Could not load more Personal Apps. Pull to refresh and try again."
            }
        }
    }

    @MainActor
    private func fetchVisiblePageBatch(
        queryPlan: PersonalAppLibraryQueryPlan,
        startingCursor: String?,
        generation: Int
    ) async throws -> PersonalAppVisiblePageBatch {
        try await PersonalAppVisiblePageChaser.fetch(startingCursor: startingCursor) { cursor in
            let page = try await client.listPersonalApps(query: queryPlan.query(cursor: cursor))
            guard refreshAuthority.accepts(generation) else { throw CancellationError() }
            return page
        }
    }

    @MainActor
    private func append(_ batch: PersonalAppVisiblePageBatch) -> [MobilePersonalApp] {
        for page in batch.pages {
            _ = accumulator.append(page.response, requestedCursor: page.requestedCursor)
        }
        if batch.reachedSafetyLimit {
            accumulator.stopPagination()
            operationMessage = "Pagination stopped after too many empty server pages. Pull to refresh to try again."
        }
        return batch.items
    }

    private func makeQueryPlan(referenceDate: Date) -> PersonalAppLibraryQueryPlan {
        PersonalAppLibraryQueryPlan(
            sort: scope == .all ? .created : .recent,
            search: searchText,
            agentId: selectedAgentID,
            sessionId: selectedSessionID,
            favorite: scope == .favorite ? true : nil,
            createdAfter: timeFilter.createdAfter(relativeTo: referenceDate)
        )
    }

    @MainActor
    private func rebuildDisplayedItems() {
        if scope == .downloaded {
            displayedItems = PersonalAppLibraryMerge.merge(
                server: serverItems,
                local: localRecords,
                authority: .downloadedIndex
            )
        } else {
            displayedItems = PersonalAppLibraryMerge.merge(
                server: serverItems,
                local: localRecords,
                authority: .server
            )
        }
    }

    @MainActor
    private func handleListFailure(_ error: Error) async {
        if isUnauthorized(error) {
            onUnauthorized()
            displayedItems = []
            return
        }
        if isTransient(error), !localRecords.isEmpty {
            displayedItems = PersonalAppLibraryMerge.merge(
                server: [],
                local: localRecords,
                authority: .offlineCache
            )
            loadState = .offline("SkillForge is unreachable. Showing verified copies saved on this device.")
            return
        }
        displayedItems = []
        loadState = .unavailable(controlledMessage(for: error))
    }

    @MainActor
    private func open(_ item: PersonalAppLibraryItem) async {
        guard isOpenable(item.app), isOpeningID == nil else { return }
        isOpeningID = item.artifactId
        operationMessage = nil
        defer { isOpeningID = nil }
        let attachment = attachment(for: item.app)
        do {
            let url = try await store.preparePersonalApp(
                sessionID: item.app.sessionId,
                attachment: attachment,
                onUnauthorized: onUnauthorized
            )
            var updated = item.app
            var shouldRefreshAfterOpen = false
            if !usesDeterministicFixture {
                do {
                    let receipt = try await client.recordPersonalAppOpened(artifactId: item.artifactId)
                    updated = updated.withPreference(receipt)
                    updateServerItem(updated)
                    _ = refreshAuthority.begin()
                    shouldRefreshAfterOpen = true
                } catch {
                    if isUnauthorized(error) {
                        onUnauthorized()
                        return
                    }
                    if isExplicitUnavailable(error) {
                        await revoke(item)
                        return
                    }
                    operationMessage = "Opened the verified device copy; SkillForge could not record this open yet."
                }
            }
            await store.recordPersonalAppMetadata(updated)
            localRecords = await store.localPersonalApps()
            rebuildDisplayedItems()
            guard store.state(for: attachment) == .available(url) else {
                operationMessage = "Opening was cancelled because the verified local copy changed or was removed."
                return
            }
            let routedItem = PersonalAppLibraryItem(app: updated, isDownloaded: true, isOfflineCopy: false)
            viewerRoute = PersonalAppViewerRoute(item: routedItem, url: url)
            if shouldRefreshAfterOpen {
                Task { await refresh() }
            }
        } catch {
            if isExplicitUnavailable(error) {
                await revoke(item)
            } else if !isUnauthorized(error) {
                operationMessage = controlledMessage(for: error)
            }
        }
    }

    @MainActor
    private func toggleFavorite(_ item: PersonalAppLibraryItem) async {
        guard favoriteMutationGate.begin(artifactID: item.artifactId) else { return }
        defer { favoriteMutationGate.finish(artifactID: item.artifactId) }
        let desired = !item.app.favorite
        if usesDeterministicFixture {
            let receipt = MobilePersonalAppReceipt(
                artifactId: item.artifactId,
                favorite: desired,
                lastOpenedAt: item.app.lastOpenedAt
            )
            updateServerItem(item.app.withPreference(receipt))
            return
        }
        do {
            let receipt = try await client.setPersonalAppFavorite(
                artifactId: item.artifactId,
                favorite: desired
            )
            let current = serverItems.first(where: { $0.artifactId == item.artifactId }) ?? item.app
            let updated = current.withPreference(receipt)
            updateServerItem(updated)
            await store.recordPersonalAppMetadata(updated)
            localRecords = await store.localPersonalApps()
            rebuildDisplayedItems()
            _ = refreshAuthority.begin()
            Task { await refresh() }
        } catch {
            if isUnauthorized(error) { onUnauthorized() }
            else if isExplicitUnavailable(error) { await revoke(item) }
            else { operationMessage = controlledMessage(for: error) }
        }
    }

    @MainActor
    private func share(_ item: PersonalAppLibraryItem) async {
        guard isOpenable(item.app), isOpeningID == nil else { return }
        isOpeningID = item.artifactId
        defer { isOpeningID = nil }
        do {
            let url = try await store.preparePersonalApp(
                sessionID: item.app.sessionId,
                attachment: attachment(for: item.app),
                metadata: item.app,
                onUnauthorized: onUnauthorized
            )
            localRecords = await store.localPersonalApps()
            rebuildDisplayedItems()
            shareURL = PersonalAppLibraryShareItem(url: url)
        } catch {
            if isExplicitUnavailable(error) { await revoke(item) }
            else if !isUnauthorized(error) { operationMessage = controlledMessage(for: error) }
        }
    }

    @MainActor
    private func openSource(_ item: PersonalAppLibraryItem) async {
        do {
            try await onOpenSource(item.app.sessionId, item.app.sourceMessageSeq)
        } catch {
            if isUnauthorized(error) {
                onUnauthorized()
            } else {
                operationMessage = "The source conversation is no longer available."
            }
        }
    }

    @MainActor
    private func clearCache(_ item: PersonalAppLibraryItem) async {
        await store.clearPersonalApp(
            sessionID: item.app.sessionId,
            attachment: attachment(for: item.app)
        )
        localRecords = await store.localPersonalApps()
        rebuildDisplayedItems()
        operationMessage = "Removed the local copy. The Personal App remains in SkillForge."
    }

    @MainActor
    private func revoke(_ item: PersonalAppLibraryItem) async {
        await store.clearPersonalApp(
            sessionID: item.app.sessionId,
            attachment: attachment(for: item.app)
        )
        updateServerItem(item.app.withAvailability(.unavailable))
        localRecords = await store.localPersonalApps()
        rebuildDisplayedItems()
        operationMessage = "This Personal App is no longer available. Its local copy was removed."
    }

    @MainActor
    private func updateServerItem(_ app: MobilePersonalApp) {
        accumulator.update(app)
        if let index = serverItems.firstIndex(where: { $0.artifactId == app.artifactId }) {
            serverItems[index] = app
        }
        rebuildDisplayedItems()
    }

    @MainActor
    private func queueSnapshot(_ message: String, for app: MobilePersonalApp) {
        guard snapshotSubmissionGate.begin() else {
            operationMessage = "A Personal App state submission is already in progress."
            return
        }
        pendingSnapshot = PersonalAppSnapshotSubmission(
            artifactID: app.artifactId,
            sessionID: app.sessionId,
            message: message,
            failure: nil
        )
        Task { await submitPendingSnapshot() }
    }

    @MainActor
    private func submitPendingSnapshot() async {
        defer { snapshotSubmissionGate.finish() }
        guard var submission = pendingSnapshot else { return }
        if usesDeterministicFixture {
            pendingSnapshot = nil
            operationMessage = "Submitted the current Personal App state to its source conversation."
            return
        }
        do {
            _ = try await client.sendMessage(sessionId: submission.sessionID, text: submission.message)
            guard pendingSnapshot?.id == submission.id else { return }
            pendingSnapshot = nil
            operationMessage = "Submitted the current Personal App state to its source conversation."
        } catch {
            if isUnauthorized(error) {
                onUnauthorized()
                return
            }
            submission.failure = "Delivery could not be confirmed. Check the source conversation before deliberately submitting this state again."
            pendingSnapshot = submission
        }
    }

    private func attachment(for app: MobilePersonalApp) -> ChatAttachment {
        ChatAttachment(
            id: app.artifactId,
            kind: .interactive,
            mimeType: "text/html",
            filename: "\(app.artifactId).html",
            caption: app.caption,
            title: app.title,
            artifactSchemaVersion: app.schemaVersion
        )
    }

    private func isOpenable(_ app: MobilePersonalApp) -> Bool {
        PersonalAppCapabilityPolicy.status(for: app) == .supported
    }

    private var client: MobileApiClient {
        MobileApiClient(baseURL: endpoint, deviceToken: deviceToken)
    }

    private func isUnauthorized(_ error: Error) -> Bool {
        if case let MobileApiError.httpStatus(status, _) = error { return status == 401 }
        return error as? AttachmentDownloadError == .unauthorized
    }

    private func isExplicitUnavailable(_ error: Error) -> Bool {
        if let error = error as? AttachmentDownloadError {
            return error == .forbidden || error == .unavailable
        }
        if case let MobileApiError.personalAppRejected(status, _, _) = error {
            return status == 403 || status == 404
        }
        return false
    }

    private func isTransient(_ error: Error) -> Bool {
        if error is URLError { return true }
        if case let MobileApiError.personalAppRejected(status, _, _) = error {
            return status == 408 || status == 429 || status >= 500
        }
        return false
    }

    private func controlledMessage(for error: Error) -> String {
        if isExplicitUnavailable(error) { return "This Personal App is no longer available." }
        if isTransient(error) { return "SkillForge is temporarily unreachable. Please try again." }
        return "Personal Apps could not be loaded. Please try again."
    }

    @MainActor
    private func refreshForControlChange() {
        guard !usesDeterministicFixture else {
            activeQueryPlan = makeQueryPlan(referenceDate: Date())
            rebuildDisplayedItems()
            return
        }
        Task { await refresh() }
    }

    private func banner(
        _ message: String,
        systemImage: String,
        color: Color,
        identifier: String
    ) -> some View {
        Label(message, systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .background(color.opacity(0.10))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier(identifier)
    }
}

private struct FilterChip: View {
    let title: String
    let active: Bool

    var body: some View {
        HStack(spacing: 5) {
            Text(title).lineLimit(1)
            Image(systemName: "chevron.down").font(.caption2.weight(.bold))
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(active ? Color.indigo : .secondary)
        .padding(.horizontal, 11)
        .frame(minHeight: 34)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: Capsule())
        .overlay { Capsule().stroke(active ? Color.indigo.opacity(0.5) : Color.clear, lineWidth: 1) }
    }
}

private struct PersonalAppLibraryLoadingView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 9) {
                    ProgressView()
                        .controlSize(.small)
                    Text("Loading Personal Apps")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 2)

                skeletonCard(identifier: "personalApps.loading.skeleton.1")
                skeletonCard(identifier: "personalApps.loading.skeleton.2")
            }
            .padding(.horizontal, 16)
            .padding(.top, 14)
            .padding(.bottom, 32)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Loading Personal Apps")
        .accessibilityIdentifier("personalApps.loading")
    }

    private func skeletonCard(identifier: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 11) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color(uiColor: .tertiarySystemFill))
                    .frame(width: 64, height: 82)

                VStack(alignment: .leading, spacing: 8) {
                    RoundedRectangle(cornerRadius: 5)
                        .fill(Color(uiColor: .tertiarySystemFill))
                        .frame(height: 18)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color(uiColor: .tertiarySystemFill))
                        .frame(maxWidth: 210, minHeight: 11, maxHeight: 11)
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color(uiColor: .tertiarySystemFill))
                        .frame(maxWidth: 160, minHeight: 11, maxHeight: 11)
                    HStack(spacing: 6) {
                        Capsule().fill(Color(uiColor: .tertiarySystemFill)).frame(width: 82, height: 21)
                        Capsule().fill(Color(uiColor: .tertiarySystemFill)).frame(width: 96, height: 21)
                    }
                }
            }

            HStack(spacing: 7) {
                RoundedRectangle(cornerRadius: 11).fill(Color(uiColor: .tertiarySystemFill))
                RoundedRectangle(cornerRadius: 11).fill(Color(uiColor: .tertiarySystemFill))
                RoundedRectangle(cornerRadius: 11).fill(Color(uiColor: .tertiarySystemFill)).frame(width: 44)
            }
            .frame(height: 44)
        }
        .padding(12)
        .background(Color(uiColor: .secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Loading Personal App card")
        .accessibilityIdentifier(identifier)
    }
}

private struct PersonalAppLibraryCard: View {
    let item: PersonalAppLibraryItem
    let isOpening: Bool
    let isFavoriting: Bool
    let onOpen: () -> Void
    let onFavorite: () -> Void
    let onShare: () -> Void
    let onSource: () -> Void
    let onClearCache: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if dynamicTypeSize.isAccessibilitySize {
                accessibilityOverview
            } else {
                compactOverview
            }

            if case let .unsupported(reason) = capabilityStatus {
                Label(reason, systemImage: "exclamationmark.shield")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("personalApps.unsupported.\(item.artifactId)")
            }

            actionLayout
        }
        .padding(12)
        .background(cardBackground)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(cardStroke, lineWidth: 1)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .shadow(color: .black.opacity(colorScheme == .dark ? 0.18 : 0.055), radius: 10, y: 5)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("personalApps.card.\(item.artifactId)")
    }

    private var compactOverview: some View {
        HStack(alignment: .top, spacing: 11) {
            appIcon
            VStack(alignment: .leading, spacing: 5) {
                titleRow
                summaryText
                sourceMetadata
                timelineMetadata
                statusBadges
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var accessibilityOverview: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .top, spacing: 11) {
                appIcon
                titleRow
            }
            summaryText
            sourceMetadata
            timelineMetadata
            statusBadges
        }
    }

    private var titleRow: some View {
        HStack(alignment: .top, spacing: 4) {
            Text(item.app.title)
                .font(.headline)
                .foregroundStyle(.primary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("personalApps.title.\(item.artifactId)")
            Spacer(minLength: 0)
            favoriteButton
        }
    }

    @ViewBuilder
    private var summaryText: some View {
        if let summary = PersonalAppLibraryCardPresentation.compactSummary(item.app.caption) {
            Text(summary)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 2)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("personalApps.summary.\(item.artifactId)")
        }
    }

    private var sourceMetadata: some View {
        Label {
            Text("\(item.app.agentName) · \(item.app.sessionTitle ?? item.app.sessionId)")
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
        } icon: {
            Image(systemName: "arrow.triangle.branch")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityElement(children: .combine)
    }

    private var timelineMetadata: some View {
        VStack(alignment: .leading, spacing: 2) {
            generatedFact
            lastOpenedFact
        }
        .font(.caption2)
        .foregroundStyle(.tertiary)
    }

    private var generatedFact: some View {
        Text("Created \(Self.compactDateText(item.app.createdAt))")
            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Generated \(Self.dateText(item.app.createdAt))")
            .accessibilityIdentifier("personalApps.generated.\(item.artifactId)")
    }

    private var lastOpenedFact: some View {
        Text(lastOpenedCompactText)
            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(lastOpenedText)
            .accessibilityIdentifier("personalApps.lastOpened.\(item.artifactId)")
    }

    @ViewBuilder
    private var statusBadges: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 5) {
                downloadedStatusBadge
                permissionStatusBadge
                capabilityStatusBadge
            }
        } else {
            HStack(spacing: 5) {
                downloadedStatusBadge
                permissionStatusBadge
                capabilityStatusBadge
            }
            .lineLimit(1)
            .minimumScaleFactor(0.72)
        }
    }

    private var downloadedStatusBadge: some View {
        badge(downloadedBadgeText, color: item.isDownloaded ? .teal : .secondary)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(item.isDownloaded ? "Offline ready" : "Online only")
            .accessibilityIdentifier("personalApps.offlineState.\(item.artifactId)")
    }

    private var permissionStatusBadge: some View {
        badge(permissionFactCount == 0 ? "NO PERMISSIONS" : "RESTRICTED", color: permissionBadgeColor)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(permissionText)
            .accessibilityIdentifier("personalApps.permissionState.\(item.artifactId)")
    }

    @ViewBuilder
    private var capabilityStatusBadge: some View {
        if capabilityStatus == .unavailable {
            badge("UNAVAILABLE", color: .red)
        } else if case .unsupported = capabilityStatus {
            badge("UNSUPPORTED", color: .orange)
        }
    }

    private var favoriteButton: some View {
        Button(action: onFavorite) {
            Group {
                if isFavoriting {
                    ProgressView()
                } else {
                    Image(systemName: item.app.favorite ? "star.fill" : "star")
                        .foregroundStyle(item.app.favorite ? .yellow : .secondary)
                }
            }
            .font(.body.weight(.semibold))
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isFavoriting)
        .accessibilityLabel(item.app.favorite ? "Remove from favorites" : "Add to favorites")
        .accessibilityIdentifier("personalApps.favorite.\(item.artifactId)")
    }

    @ViewBuilder
    private var actionLayout: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(spacing: 8) {
                primaryAction
                compactButton("Source", image: "arrow.turn.down.right", action: onSource)
                    .accessibilityIdentifier("personalApps.source.\(item.artifactId)")
                compactButton("Share", image: "square.and.arrow.up", action: onShare)
                    .disabled(capabilityStatus != .supported)
                    .accessibilityIdentifier("personalApps.share.\(item.artifactId)")
                if item.isDownloaded {
                    compactButton("Clear local copy", image: "trash.slash", action: onClearCache)
                        .accessibilityIdentifier("personalApps.clear.\(item.artifactId)")
                }
            }
        } else {
            HStack(spacing: 7) {
                primaryAction
                    .frame(minWidth: 82, idealWidth: 92, maxWidth: 100)
                    .layoutPriority(2)
                compactButton("Source", image: "arrow.turn.down.right", action: onSource)
                    .frame(minWidth: 96)
                    .layoutPriority(1)
                    .accessibilityIdentifier("personalApps.source.\(item.artifactId)")
                iconButton("Share", image: "square.and.arrow.up", action: onShare)
                    .disabled(capabilityStatus != .supported)
                    .accessibilityIdentifier("personalApps.share.\(item.artifactId)")
                if item.isDownloaded {
                    iconButton("Clear", image: "trash.slash", action: onClearCache)
                        .accessibilityIdentifier("personalApps.clear.\(item.artifactId)")
                }
            }
        }
    }

    private var primaryAction: some View {
        Button(action: onOpen) {
            HStack {
                if isOpening { ProgressView().tint(.white) }
                else { Image(systemName: "arrow.up.right.square") }
                Text(primaryActionTitle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
            .font(.caption.weight(.bold))
            .foregroundStyle(primaryActionForeground)
            .frame(maxWidth: .infinity, minHeight: 44)
            .padding(.horizontal, 8)
            .background(primaryActionBackground, in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(isOpening || capabilityStatus != .supported)
        .accessibilityIdentifier("personalApps.open.\(item.artifactId)")
    }

    private var appIcon: some View {
        Text(PersonalAppLibraryCardPresentation.monogram(for: item.app.title))
            .font(.title3.weight(.bold))
            .minimumScaleFactor(0.65)
            .lineLimit(1)
            .foregroundStyle(.white)
            .frame(width: 64, height: dynamicTypeSize.isAccessibilitySize ? 64 : 82)
            .background(
                LinearGradient(colors: appIconColors, startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .accessibilityHidden(true)
    }

    private var appIconColors: [Color] {
        switch item.artifactId.utf8.reduce(0, { ($0 + Int($1)) % 3 }) {
        case 0: [Color(red: 0.12, green: 0.16, blue: 0.29), .indigo]
        case 1: [Color(red: 0.06, green: 0.24, blue: 0.20), .green]
        default: [Color(red: 0.30, green: 0.16, blue: 0.08), .orange]
        }
    }

    private var capabilityStatus: PersonalAppCapabilityStatus {
        PersonalAppCapabilityPolicy.status(for: item.app)
    }

    private var primaryActionTitle: String {
        switch capabilityStatus {
        case .supported: "Open"
        case .unavailable: "Unavailable"
        case .unsupported: "Unsupported"
        }
    }

    private var lastOpenedText: String {
        guard let lastOpenedAt = item.app.lastOpenedAt else { return "Never opened" }
        return "Last opened \(Self.dateText(lastOpenedAt))"
    }

    private var lastOpenedCompactText: String {
        guard let lastOpenedAt = item.app.lastOpenedAt else { return "Never opened" }
        return "Opened \(Self.compactDateText(lastOpenedAt))"
    }

    private var downloadedBadgeText: String {
        if item.isOfflineCopy { return "OFFLINE COPY" }
        return item.isDownloaded ? "DOWNLOADED" : "ONLINE"
    }

    private var permissionFactCount: Int {
        item.app.permissions.count + item.app.network.count
    }

    private var permissionText: String {
        guard permissionFactCount > 0 else { return "No permissions" }
        let permissions = item.app.permissions.isEmpty
            ? []
            : ["permissions: \(item.app.permissions.joined(separator: ", "))"]
        let network = item.app.network.isEmpty
            ? []
            : ["network: \(item.app.network.joined(separator: ", "))"]
        return "Unsupported \((permissions + network).joined(separator: "; "))"
    }

    private var permissionBadgeColor: Color {
        permissionFactCount == 0 ? .indigo : .orange
    }

    private var primaryActionBackground: Color {
        capabilityStatus == .supported
            ? Color(uiColor: .label)
            : Color(uiColor: .systemGray4)
    }

    private var primaryActionForeground: Color {
        capabilityStatus == .supported
            ? Color(uiColor: .systemBackground)
            : Color(uiColor: .secondaryLabel)
    }

    private var cardBackground: Color {
        capabilityStatus == .unavailable
            ? Color.red.opacity(colorScheme == .dark ? 0.10 : 0.035)
            : Color(uiColor: .secondarySystemGroupedBackground)
    }

    private var cardStroke: Color {
        if capabilityStatus == .unavailable {
            return Color.red.opacity(colorScheme == .dark ? 0.55 : 0.25)
        }
        return Color(uiColor: .separator).opacity(colorScheme == .dark ? 0.58 : 0.20)
    }

    private func badge(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.heavy))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(color.opacity(0.12), in: Capsule())
    }

    private func compactButton(_ title: String, image: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: image)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.78)
                .frame(maxWidth: .infinity, minHeight: 44)
                .padding(.horizontal, 7)
                .background(Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 11))
        }
        .buttonStyle(.plain)
    }

    private func iconButton(_ title: String, image: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: image)
                .font(.body.weight(.semibold))
                .frame(width: 44, height: 44)
                .background(Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 11))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    private static func dateText(_ value: String) -> String {
        guard let date = PersonalAppISO8601.date(from: value) else { return value }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    private static func compactDateText(_ value: String) -> String {
        guard let date = PersonalAppISO8601.date(from: value) else { return value }
        return date.formatted(
            Date.FormatStyle()
                .month(.abbreviated)
                .day()
                .hour(.defaultDigits(amPM: .abbreviated))
                .minute()
        )
    }
}

private struct PersonalAppLibraryShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

private struct PersonalAppLibraryActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
