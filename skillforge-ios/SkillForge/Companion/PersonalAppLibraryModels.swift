import Foundation

enum PersonalAppLibraryCardPresentation {
    static let summaryCharacterLimit = 96

    static func compactSummary(_ value: String?) -> String? {
        guard let value else { return nil }
        let normalized = value
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        guard !normalized.isEmpty else { return nil }
        guard normalized.count > summaryCharacterLimit else { return normalized }

        let contentLimit = summaryCharacterLimit - 1
        var prefix = String(normalized.prefix(contentLimit))
        if let lastSpace = prefix.lastIndex(of: " ") {
            let trailingFragment = prefix.distance(from: lastSpace, to: prefix.endIndex)
            let retainedCharacters = prefix.distance(from: prefix.startIndex, to: lastSpace)
            if trailingFragment <= 18, retainedCharacters >= contentLimit / 2 {
                prefix = String(prefix[..<lastSpace])
            }
        }
        return prefix.trimmingCharacters(in: .whitespacesAndNewlines) + "…"
    }

    static func monogram(for title: String) -> String {
        let normalized = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return "APP" }
        let words = normalized.split { character in
            character.isWhitespace || character.isPunctuation
        }
        guard let first = words.first else { return String(normalized.prefix(2)).uppercased() }
        if first.count == 2, first.allSatisfy(\.isLetter) {
            return String(first).uppercased()
        }
        if words.count >= 2 {
            return words.prefix(2)
                .compactMap(\.first)
                .map(String.init)
                .joined()
                .uppercased()
        }
        return String(first.prefix(2)).uppercased()
    }
}

struct PersonalAppPageAccumulator: Equatable {
    private(set) var items: [MobilePersonalApp] = []
    private(set) var nextCursor: String?
    private var consumedCursors = Set<String>()

    @discardableResult
    mutating func append(
        _ page: MobilePersonalAppPage,
        requestedCursor: String?
    ) -> Bool {
        if let requestedCursor {
            consumedCursors.insert(requestedCursor)
        }

        var indices = Dictionary(uniqueKeysWithValues: items.enumerated().map { ($1.artifactId, $0) })
        for item in page.items {
            if let index = indices[item.artifactId] {
                items[index] = item
            } else {
                indices[item.artifactId] = items.count
                items.append(item)
            }
        }

        guard let cursor = normalized(page.nextCursor),
              cursor != requestedCursor,
              !consumedCursors.contains(cursor) else {
            nextCursor = nil
            return page.nextCursor == nil
        }
        nextCursor = cursor
        return true
    }

    mutating func reset() {
        self = PersonalAppPageAccumulator()
    }

    mutating func stopPagination() {
        nextCursor = nil
    }

    mutating func update(_ app: MobilePersonalApp) {
        guard let index = items.firstIndex(where: { $0.artifactId == app.artifactId }) else { return }
        items[index] = app
    }

    private func normalized(_ cursor: String?) -> String? {
        let value = cursor?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? nil : value
    }
}

struct PersonalAppFetchedPage: Equatable, Sendable {
    let requestedCursor: String?
    let response: MobilePersonalAppPage
}

struct PersonalAppVisiblePageBatch: Equatable, Sendable {
    let pages: [PersonalAppFetchedPage]
    let reachedSafetyLimit: Bool

    var items: [MobilePersonalApp] {
        pages.flatMap(\.response.items)
    }
}

enum PersonalAppVisiblePageChaser {
    static let maximumEmptyPages = 32

    @MainActor
    static func fetch(
        startingCursor: String?,
        maximumEmptyPages: Int = maximumEmptyPages,
        page: (String?) async throws -> MobilePersonalAppPage
    ) async throws -> PersonalAppVisiblePageBatch {
        precondition(maximumEmptyPages > 0)
        var pages: [PersonalAppFetchedPage] = []
        var requestedCursor = normalized(startingCursor)
        var consumedCursors = Set<String>()
        if let requestedCursor { consumedCursors.insert(requestedCursor) }
        var emptyPageCount = 0

        while true {
            let response = try await page(requestedCursor)
            pages.append(PersonalAppFetchedPage(
                requestedCursor: requestedCursor,
                response: response
            ))
            if !response.items.isEmpty {
                return PersonalAppVisiblePageBatch(pages: pages, reachedSafetyLimit: false)
            }

            guard let nextCursor = normalized(response.nextCursor),
                  nextCursor != requestedCursor,
                  !consumedCursors.contains(nextCursor) else {
                return PersonalAppVisiblePageBatch(pages: pages, reachedSafetyLimit: false)
            }
            emptyPageCount += 1
            guard emptyPageCount < maximumEmptyPages else {
                return PersonalAppVisiblePageBatch(pages: pages, reachedSafetyLimit: true)
            }
            consumedCursors.insert(nextCursor)
            requestedCursor = nextCursor
        }
    }

    private static func normalized(_ cursor: String?) -> String? {
        let value = cursor?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.isEmpty ? nil : value
    }
}

struct PersonalAppLocalRecord: Codable, Equatable, Identifiable, Sendable {
    let artifactId: String
    let sessionId: String
    let sourceMessageSeq: Int64
    let title: String
    let caption: String?
    let schemaVersion: Int
    let permissions: [String]
    let network: [String]
    let agentId: Int64
    let agentName: String
    let sessionTitle: String?
    let createdAt: String
    let lastOpenedAt: String?
    let favorite: Bool

    var id: String { artifactId }

    init(app: MobilePersonalApp) {
        self.init(
            artifactId: app.artifactId,
            sessionId: app.sessionId,
            sourceMessageSeq: app.sourceMessageSeq,
            title: app.title,
            caption: app.caption,
            schemaVersion: app.schemaVersion,
            permissions: app.permissions,
            network: app.network,
            agentId: app.agentId,
            agentName: app.agentName,
            sessionTitle: app.sessionTitle,
            createdAt: app.createdAt,
            lastOpenedAt: app.lastOpenedAt,
            favorite: app.favorite
        )
    }

    init(
        artifactId: String,
        sessionId: String,
        sourceMessageSeq: Int64,
        title: String,
        caption: String?,
        schemaVersion: Int,
        permissions: [String],
        network: [String],
        agentId: Int64,
        agentName: String,
        sessionTitle: String?,
        createdAt: String,
        lastOpenedAt: String?,
        favorite: Bool
    ) {
        self.artifactId = artifactId
        self.sessionId = sessionId
        self.sourceMessageSeq = sourceMessageSeq
        self.title = title
        self.caption = caption
        self.schemaVersion = schemaVersion
        self.permissions = permissions
        self.network = network
        self.agentId = agentId
        self.agentName = agentName
        self.sessionTitle = sessionTitle
        self.createdAt = createdAt
        self.lastOpenedAt = lastOpenedAt
        self.favorite = favorite
    }

    var asMobilePersonalApp: MobilePersonalApp {
        MobilePersonalApp(
            artifactId: artifactId,
            sessionId: sessionId,
            sourceMessageSeq: sourceMessageSeq,
            title: title,
            caption: caption,
            schemaVersion: schemaVersion,
            permissions: permissions,
            network: network,
            agentId: agentId,
            agentName: agentName,
            sessionTitle: sessionTitle,
            createdAt: createdAt,
            lastOpenedAt: lastOpenedAt,
            favorite: favorite,
            availability: .available
        )
    }
}

struct PersonalAppLibraryItem: Identifiable, Equatable, Sendable {
    let app: MobilePersonalApp
    let isDownloaded: Bool
    let isOfflineCopy: Bool

    var id: String { app.artifactId }
    var artifactId: String { app.artifactId }

    func updating(app: MobilePersonalApp) -> PersonalAppLibraryItem {
        PersonalAppLibraryItem(
            app: app,
            isDownloaded: isDownloaded,
            isOfflineCopy: isOfflineCopy
        )
    }
}

enum PersonalAppLibraryAuthority: Equatable {
    case server
    case offlineCache
    case downloadedIndex
}

enum PersonalAppLibraryMerge {
    static func merge(
        server: [MobilePersonalApp],
        local: [PersonalAppLocalRecord],
        authority: PersonalAppLibraryAuthority
    ) -> [PersonalAppLibraryItem] {
        let localByID = Dictionary(uniqueKeysWithValues: local.map { ($0.artifactId, $0) })
        switch authority {
        case .server:
            return server.map { app in
                PersonalAppLibraryItem(
                    app: app,
                    isDownloaded: localByID[app.artifactId] != nil,
                    isOfflineCopy: false
                )
            }
        case .offlineCache, .downloadedIndex:
            let serverByID = Dictionary(uniqueKeysWithValues: server.map { ($0.artifactId, $0) })
            return local
                .map { record in
                    let cachedApp = MobilePersonalApp(
                        artifactId: record.artifactId,
                        sessionId: record.sessionId,
                        sourceMessageSeq: record.sourceMessageSeq,
                        title: record.title,
                        caption: record.caption,
                        schemaVersion: record.schemaVersion,
                        permissions: record.permissions,
                        network: record.network,
                        agentId: record.agentId,
                        agentName: record.agentName,
                        sessionTitle: record.sessionTitle,
                        createdAt: record.createdAt,
                        lastOpenedAt: record.lastOpenedAt,
                        favorite: record.favorite,
                        availability: .available
                    )
                    return PersonalAppLibraryItem(
                        app: serverByID[record.artifactId] ?? cachedApp,
                        isDownloaded: true,
                        isOfflineCopy: authority == .offlineCache
                    )
                }
                .sorted(by: PersonalAppLibraryOrdering.created)
        }
    }
}

struct PersonalAppAgentOption: Identifiable, Equatable {
    let id: Int64
    let name: String
}

struct PersonalAppSessionOption: Identifiable, Equatable {
    let id: String
    let title: String
}

enum PersonalAppLibraryFilterOptions {
    static func agents(
        catalog: [MobileAgentCatalogItem],
        apps: [MobilePersonalApp]
    ) -> [PersonalAppAgentOption] {
        var values: [Int64: String] = [:]
        for app in apps { values[app.agentId] = app.agentName }
        for agent in catalog { values[agent.id] = agent.name }
        return values
            .map { PersonalAppAgentOption(id: $0.key, name: $0.value) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func sessions(
        catalog: [MobileSession],
        apps: [MobilePersonalApp]
    ) -> [PersonalAppSessionOption] {
        var values: [String: String] = [:]
        for app in apps { values[app.sessionId] = app.sessionTitle ?? app.sessionId }
        for session in catalog { values[session.id] = session.title ?? session.id }
        return values
            .map { PersonalAppSessionOption(id: $0.key, title: $0.value) }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }
}

struct PersonalAppLibraryQueryPlan: Equatable {
    let sort: MobilePersonalAppSort
    let search: String
    let agentId: Int64?
    let sessionId: String?
    let favorite: Bool?
    let createdAfter: Date?

    func query(cursor: String?, limit: Int = 25) -> MobilePersonalAppQuery {
        MobilePersonalAppQuery(
            cursor: cursor,
            limit: limit,
            sort: sort,
            search: search,
            agentId: agentId,
            sessionId: sessionId,
            favorite: favorite,
            createdAfter: createdAfter
        )
    }
}

enum PersonalAppLibraryScope: String, CaseIterable, Identifiable {
    case recent
    case favorite
    case downloaded
    case all

    var id: String { rawValue }

    var label: String {
        switch self {
        case .recent: "Recent"
        case .favorite: "Favorites"
        case .downloaded: "Downloaded"
        case .all: "All"
        }
    }
}

enum PersonalAppLibraryFilter {
    static func apply(
        scope: PersonalAppLibraryScope,
        to items: [PersonalAppLibraryItem]
    ) -> [PersonalAppLibraryItem] {
        switch scope {
        case .recent:
            items.sorted(by: PersonalAppLibraryOrdering.recent)
        case .all:
            items
        case .favorite:
            items.filter { $0.app.favorite }
        case .downloaded:
            items.filter(\.isDownloaded)
        }
    }
}

enum PersonalAppLibraryOrdering {
    static func recent(_ lhs: PersonalAppLibraryItem, _ rhs: PersonalAppLibraryItem) -> Bool {
        compareDescending(
            lhs: lhs.app.lastOpenedAt ?? lhs.app.createdAt,
            rhs: rhs.app.lastOpenedAt ?? rhs.app.createdAt,
            lhsID: lhs.artifactId,
            rhsID: rhs.artifactId
        )
    }

    static func created(_ lhs: PersonalAppLibraryItem, _ rhs: PersonalAppLibraryItem) -> Bool {
        compareDescending(
            lhs: lhs.app.createdAt,
            rhs: rhs.app.createdAt,
            lhsID: lhs.artifactId,
            rhsID: rhs.artifactId
        )
    }

    private static func compareDescending(
        lhs: String,
        rhs: String,
        lhsID: String,
        rhsID: String
    ) -> Bool {
        let lhsDate = PersonalAppISO8601.date(from: lhs)
        let rhsDate = PersonalAppISO8601.date(from: rhs)
        if let lhsDate, let rhsDate, lhsDate != rhsDate { return lhsDate > rhsDate }
        if lhs != rhs { return lhs > rhs }
        return lhsID > rhsID
    }
}

enum PersonalAppLibrarySelection {
    static func apply(
        scope: PersonalAppLibraryScope,
        search: String,
        agentID: Int64?,
        sessionID: String?,
        createdAfter: Date?,
        to items: [PersonalAppLibraryItem]
    ) -> [PersonalAppLibraryItem] {
        let normalizedSearch = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return PersonalAppLibraryFilter.apply(scope: scope, to: items).filter { item in
            if let agentID, item.app.agentId != agentID { return false }
            if let sessionID, item.app.sessionId != sessionID { return false }
            if let createdAfter {
                guard let createdAt = PersonalAppISO8601.date(from: item.app.createdAt),
                      createdAt >= createdAfter else { return false }
            }
            guard !normalizedSearch.isEmpty else { return true }
            return [item.app.title, item.app.caption]
                .compactMap { $0 }
                .contains { $0.localizedCaseInsensitiveContains(normalizedSearch) }
        }
    }
}

enum PersonalAppISO8601 {
    static func date(from value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value) { return date }
        let standard = ISO8601DateFormatter()
        standard.formatOptions = [.withInternetDateTime]
        return standard.date(from: value)
    }
}

struct PersonalAppRefreshAuthority: Equatable {
    private(set) var generation = 0

    mutating func begin() -> Int {
        generation &+= 1
        return generation
    }

    func accepts(_ candidate: Int) -> Bool {
        candidate == generation
    }
}

struct PersonalAppSnapshotSubmissionGate: Equatable {
    private(set) var isSubmitting = false

    mutating func begin() -> Bool {
        guard !isSubmitting else { return false }
        isSubmitting = true
        return true
    }

    mutating func finish() {
        isSubmitting = false
    }
}

struct PersonalAppFavoriteMutationGate: Equatable {
    private(set) var artifactIDs = Set<String>()

    mutating func begin(artifactID: String) -> Bool {
        artifactIDs.insert(artifactID).inserted
    }

    mutating func finish(artifactID: String) {
        artifactIDs.remove(artifactID)
    }

    func contains(artifactID: String) -> Bool {
        artifactIDs.contains(artifactID)
    }
}

enum PersonalAppCapabilityStatus: Equatable {
    case supported
    case unavailable
    case unsupported(String)
}

enum PersonalAppCapabilityPolicy {
    static func status(for app: MobilePersonalApp) -> PersonalAppCapabilityStatus {
        guard app.availability.isAvailable else { return .unavailable }
        guard app.schemaVersion == 1 else {
            return .unsupported("This Personal App uses a newer format.")
        }
        guard app.permissions.isEmpty, app.network.isEmpty else {
            return .unsupported("This Personal App requests capabilities that iPhone does not allow.")
        }
        return .supported
    }
}

enum ChatSourceRouteResolution: Equatable {
    case none
    case target(String)
    case missing
}

enum ChatSourceRoutePolicy {
    static func resolve(
        sourceMessageSeq: Int64?,
        messages: [ChatMessage]
    ) -> ChatSourceRouteResolution {
        guard let sourceMessageSeq else { return .none }
        let id = "remote-\(sourceMessageSeq)"
        return messages.contains(where: { $0.id == id || $0.remoteSeqNo == sourceMessageSeq })
            ? .target(id)
            : .missing
    }
}
