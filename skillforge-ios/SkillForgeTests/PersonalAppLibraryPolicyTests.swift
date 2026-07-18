import Foundation
import XCTest
@testable import SkillForge

final class PersonalAppLibraryPolicyTests: XCTestCase {
    func testAccumulatorDeduplicatesArtifactsAcrossKeysetPagesAndRejectsCursorLoop() {
        var accumulator = PersonalAppPageAccumulator()

        let firstAccepted = accumulator.append(
            MobilePersonalAppPage(
                items: [fixture(id: "app-a"), fixture(id: "app-b")],
                nextCursor: "cursor-2"
            ),
            requestedCursor: nil
        )
        let secondAccepted = accumulator.append(
            MobilePersonalAppPage(
                items: [fixture(id: "app-b", title: "Updated B"), fixture(id: "app-c")],
                nextCursor: "cursor-2"
            ),
            requestedCursor: "cursor-2"
        )

        XCTAssertTrue(firstAccepted)
        XCTAssertFalse(secondAccepted, "A repeated next cursor must terminate pagination")
        XCTAssertEqual(accumulator.items.map(\.artifactId), ["app-a", "app-b", "app-c"])
        XCTAssertEqual(accumulator.items[1].title, "Updated B")
        XCTAssertNil(accumulator.nextCursor)
    }

    func testVisiblePageChaserStopsCursorCyclesAndBoundsEmptyPages() async throws {
        nonisolated(unsafe) var cycleCalls = 0
        let cycle = try await PersonalAppVisiblePageChaser.fetch(startingCursor: nil) { cursor in
            cycleCalls += 1
            return MobilePersonalAppPage(items: [], nextCursor: cursor ?? "cursor-1")
        }
        XCTAssertEqual(cycleCalls, 2)
        XCTAssertFalse(cycle.reachedSafetyLimit)

        nonisolated(unsafe) var boundedCalls = 0
        let bounded = try await PersonalAppVisiblePageChaser.fetch(
            startingCursor: nil,
            maximumEmptyPages: 2
        ) { _ in
            boundedCalls += 1
            return MobilePersonalAppPage(items: [], nextCursor: "cursor-\(boundedCalls)")
        }
        XCTAssertEqual(boundedCalls, 2)
        XCTAssertTrue(bounded.reachedSafetyLimit)
    }

    func testSuccessfulServerMergeNeverResurrectsLocalOnlyArtifact() {
        let server = [fixture(id: "server-app")]
        let local = [localRecord(id: "server-app"), localRecord(id: "revoked-local-app")]

        let merged = PersonalAppLibraryMerge.merge(
            server: server,
            local: local,
            authority: .server
        )

        XCTAssertEqual(merged.map(\.artifactId), ["server-app"])
        XCTAssertTrue(merged[0].isDownloaded)
    }

    func testTransportFailureMayMergePreviouslyVerifiedLocalApps() {
        let local = [
            localRecord(id: "local-a", createdAt: "2026-07-16T09:00:00Z"),
            localRecord(id: "local-b", createdAt: "2026-07-17T09:00:00Z")
        ]

        let merged = PersonalAppLibraryMerge.merge(
            server: [],
            local: local,
            authority: .offlineCache
        )

        XCTAssertEqual(merged.map(\.artifactId), ["local-b", "local-a"])
        XCTAssertTrue(merged.allSatisfy(\.isDownloaded))
        XCTAssertTrue(merged.allSatisfy(\.isOfflineCopy))
    }

    func testOfflineRecentOrderParsesMixedFractionalAndWholeSecondInstants() {
        let local = [
            localRecord(id: "fractional", createdAt: "2026-07-17T09:00:00.900Z"),
            localRecord(id: "whole", createdAt: "2026-07-17T09:00:00Z")
        ]

        let merged = PersonalAppLibraryMerge.merge(
            server: [],
            local: local,
            authority: .offlineCache
        )

        XCTAssertEqual(merged.map(\.artifactId), ["fractional", "whole"])
    }

    func testOfflineRecentUsesLastOpenedWhileAllUsesCreatedAndIDsBreakTiesDescending() {
        let local = [
            localRecord(
                id: "older-created",
                createdAt: "2026-07-16T09:00:00Z",
                lastOpenedAt: "2026-07-18T09:00:00Z"
            ),
            localRecord(id: "newer-created", createdAt: "2026-07-17T09:00:00Z")
        ]
        let merged = PersonalAppLibraryMerge.merge(
            server: [],
            local: local,
            authority: .offlineCache
        )

        XCTAssertEqual(merged.map(\.artifactId), ["newer-created", "older-created"])
        XCTAssertEqual(
            PersonalAppLibraryFilter.apply(scope: .recent, to: merged).map(\.artifactId),
            ["older-created", "newer-created"]
        )
        XCTAssertEqual(
            PersonalAppLibraryFilter.apply(scope: .all, to: merged).map(\.artifactId),
            ["newer-created", "older-created"]
        )

        let tied = PersonalAppLibraryMerge.merge(
            server: [],
            local: [localRecord(id: "app-a"), localRecord(id: "app-b")],
            authority: .offlineCache
        )
        XCTAssertEqual(tied.map(\.artifactId), ["app-b", "app-a"])
        XCTAssertEqual(
            PersonalAppLibraryFilter.apply(scope: .recent, to: tied).map(\.artifactId),
            ["app-b", "app-a"]
        )
    }

    func testDownloadedScopeUsesVerifiedDeviceFactNotServerAvailability() {
        let server = [fixture(id: "downloaded"), fixture(id: "not-downloaded")]
        let merged = PersonalAppLibraryMerge.merge(
            server: server,
            local: [localRecord(id: "downloaded")],
            authority: .server
        )

        XCTAssertEqual(
            PersonalAppLibraryFilter.apply(scope: .downloaded, to: merged).map(\.artifactId),
            ["downloaded"]
        )
    }

    func testDownloadedIndexKeepsVerifiedArtifactMissingOnlyFromPartialServerPage() {
        let pageOne = [fixture(id: "new-server-app")]
        let local = [localRecord(id: "older-downloaded-app", createdAt: "2026-06-01T09:00:00Z")]

        let downloaded = PersonalAppLibraryMerge.merge(
            server: pageOne,
            local: local,
            authority: .downloadedIndex
        )

        XCTAssertEqual(downloaded.map(\.artifactId), ["older-downloaded-app"])
        XCTAssertTrue(downloaded[0].isDownloaded)
        XCTAssertFalse(downloaded[0].isOfflineCopy)
    }

    func testSourceRouteUsesStableRemoteSequenceAndTreatsMissingAsUnavailable() {
        let messages = [
            ChatMessage(id: "remote-40", role: .assistant, text: "Before", remoteSeqNo: 40),
            ChatMessage(id: "remote-42", role: .assistant, text: "Source", remoteSeqNo: 42)
        ]

        XCTAssertEqual(ChatSourceRoutePolicy.resolve(sourceMessageSeq: 42, messages: messages), .target("remote-42"))
        XCTAssertEqual(ChatSourceRoutePolicy.resolve(sourceMessageSeq: 41, messages: messages), .missing)
        XCTAssertEqual(ChatSourceRoutePolicy.resolve(sourceMessageSeq: nil, messages: messages), .none)
    }

    func testChatRoutePreservesOptionalSourceSequence() {
        let session = MobileSession(
            id: "session-source",
            userId: 1,
            agentId: 2,
            title: "Source",
            status: "active",
            runtimeStatus: "idle",
            messageCount: 1,
            updatedAt: nil
        )

        let route = ChatRoute(session: session, sourceMessageSeq: 42)

        XCTAssertEqual(route.session.id, "session-source")
        XCTAssertEqual(route.sourceMessageSeq, 42)
    }

    func testLocalAuthorityFiltersApplySearchAgentSessionAndTimeTogether() throws {
        let localItems = PersonalAppLibraryMerge.merge(
            server: [],
            local: [
                localRecord(id: "match", createdAt: "2026-07-17T09:00:00.123456Z"),
                PersonalAppLocalRecord(
                    artifactId: "other",
                    sessionId: "session-2",
                    sourceMessageSeq: 8,
                    title: "Old report",
                    caption: nil,
                    schemaVersion: 1,
                    permissions: [],
                    network: [],
                    agentId: 8,
                    agentName: "Other Agent",
                    sessionTitle: "Archive",
                    createdAt: "2026-01-01T09:00:00Z",
                    lastOpenedAt: nil,
                    favorite: false
                )
            ],
            authority: .downloadedIndex
        )
        let threshold = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-07-01T00:00:00Z"))

        let selected = PersonalAppLibrarySelection.apply(
            scope: .downloaded,
            search: "cached match",
            agentID: 7,
            sessionID: "session-1",
            createdAfter: threshold,
            to: localItems
        )

        XCTAssertEqual(selected.map(\.artifactId), ["match"])

        let agentNameOnly = PersonalAppLibrarySelection.apply(
            scope: .downloaded,
            search: "Research Agent",
            agentID: nil,
            sessionID: nil,
            createdAfter: nil,
            to: localItems
        )
        XCTAssertEqual(agentNameOnly, [], "Local q must match the server title/caption contract only")
    }

    func testRefreshAuthorityRejectsOlderResponseAfterFilterChange() {
        var authority = PersonalAppRefreshAuthority()
        let initial = authority.begin()
        let filtered = authority.begin()

        XCTAssertFalse(authority.accepts(initial))
        XCTAssertTrue(authority.accepts(filtered))
    }

    func testCallerRechecksRefreshAuthorityBeforeCommittingAwaitedBatch() {
        var authority = PersonalAppRefreshAuthority()
        let staleGeneration = authority.begin()
        XCTAssertTrue(
            authority.accepts(staleGeneration),
            "The final page callback may still be current immediately before returning its batch"
        )

        let currentGeneration = authority.begin()
        let staleBatch = MobilePersonalAppPage(items: [fixture(id: "stale-app")], nextCursor: nil)
        var accumulator = PersonalAppPageAccumulator()
        if authority.accepts(staleGeneration) {
            accumulator.append(staleBatch, requestedCursor: nil)
        }

        XCTAssertTrue(accumulator.items.isEmpty, "An older awaited batch must not mutate the new query")
        XCTAssertTrue(authority.accepts(currentGeneration))
    }

    func testSnapshotAndFavoriteMutationGatesAreSingleFlight() {
        var snapshot = PersonalAppSnapshotSubmissionGate()
        XCTAssertTrue(snapshot.begin())
        XCTAssertFalse(snapshot.begin())
        snapshot.finish()
        XCTAssertTrue(snapshot.begin())

        var favorite = PersonalAppFavoriteMutationGate()
        XCTAssertTrue(favorite.begin(artifactID: "app-a"))
        XCTAssertFalse(favorite.begin(artifactID: "app-a"))
        XCTAssertTrue(favorite.begin(artifactID: "app-b"))
        favorite.finish(artifactID: "app-a")
        XCTAssertTrue(favorite.begin(artifactID: "app-a"))
    }

    func testKeysetQueryPlanKeepsCreatedAfterAndAllFiltersStableAcrossPages() throws {
        let createdAfter = try XCTUnwrap(
            ISO8601DateFormatter().date(from: "2026-07-10T09:00:00Z")
        )
        let plan = PersonalAppLibraryQueryPlan(
            sort: .recent,
            search: "brief",
            agentId: 7,
            sessionId: "session-1",
            favorite: true,
            createdAfter: createdAfter
        )

        let firstPage = plan.query(cursor: nil)
        let secondPage = plan.query(cursor: "cursor-2")

        XCTAssertEqual(firstPage.createdAfter, secondPage.createdAfter)
        XCTAssertEqual(firstPage.search, secondPage.search)
        XCTAssertEqual(firstPage.agentId, secondPage.agentId)
        XCTAssertEqual(firstPage.sessionId, secondPage.sessionId)
        XCTAssertEqual(firstPage.favorite, secondPage.favorite)
        XCTAssertEqual(firstPage.sort, secondPage.sort)
    }

    func testFilterOptionsUnionCatalogEntriesAbsentFromFirstServerPage() {
        let archivedAgent = MobileAgentCatalogItem(id: 99, name: "Archive Agent")
        let archivedSession = MobileSession(
            id: "archived-session",
            userId: 1,
            agentId: archivedAgent.id,
            title: "Archived launch",
            status: "active",
            runtimeStatus: "idle",
            messageCount: 12,
            updatedAt: "2026-06-01T09:00:00Z"
        )
        let firstPage = [fixture(id: "page-one"), fixture(id: "page-two")]

        let agentOptions = PersonalAppLibraryFilterOptions.agents(
            catalog: [archivedAgent],
            apps: firstPage
        )
        let sessionOptions = PersonalAppLibraryFilterOptions.sessions(
            catalog: [archivedSession],
            apps: firstPage
        )

        XCTAssertEqual(Set(agentOptions.map(\.id)), [7, 99])
        XCTAssertEqual(Set(sessionOptions.map(\.id)), ["session-1", "archived-session"])
    }

    func testUnsupportedCapabilitiesAreExplicitlyDistinguishedFromUnavailable() {
        let supported = fixture(id: "supported", title: "Supported")
        let unsupportedSchema = fixture(id: "schema", title: "Schema", schemaVersion: 2)
        let unsupportedPermissions = MobilePersonalApp(
            artifactId: "permissions",
            sessionId: "session-1",
            sourceMessageSeq: 42,
            title: "Permissions",
            caption: nil,
            schemaVersion: 1,
            permissions: ["camera"],
            network: [],
            agentId: 7,
            agentName: "Research Agent",
            sessionTitle: "Daily research",
            createdAt: "2026-07-17T09:00:00Z",
            lastOpenedAt: nil,
            favorite: false,
            availability: .available
        )

        XCTAssertEqual(PersonalAppCapabilityPolicy.status(for: supported), .supported)
        XCTAssertEqual(
            PersonalAppCapabilityPolicy.status(for: unsupportedSchema),
            .unsupported("This Personal App uses a newer format.")
        )
        XCTAssertEqual(
            PersonalAppCapabilityPolicy.status(for: unsupportedPermissions),
            .unsupported("This Personal App requests capabilities that iPhone does not allow.")
        )
        XCTAssertEqual(
            PersonalAppCapabilityPolicy.status(for: supported.withAvailability(.unavailable)),
            .unavailable
        )
    }

    private func fixture(
        id: String,
        title: String = "Personal App",
        schemaVersion: Int = 1
    ) -> MobilePersonalApp {
        MobilePersonalApp(
            artifactId: id,
            sessionId: "session-1",
            sourceMessageSeq: 42,
            title: title,
            caption: "Interactive result",
            schemaVersion: schemaVersion,
            permissions: [],
            network: [],
            agentId: 7,
            agentName: "Research Agent",
            sessionTitle: "Daily research",
            createdAt: "2026-07-17T09:00:00Z",
            lastOpenedAt: nil,
            favorite: false,
            availability: .available
        )
    }

    private func localRecord(
        id: String,
        createdAt: String = "2026-07-17T09:00:00Z",
        lastOpenedAt: String? = nil
    ) -> PersonalAppLocalRecord {
        PersonalAppLocalRecord(
            artifactId: id,
            sessionId: "session-1",
            sourceMessageSeq: 42,
            title: "Cached \(id)",
            caption: "Verified offline copy",
            schemaVersion: 1,
            permissions: [],
            network: [],
            agentId: 7,
            agentName: "Research Agent",
            sessionTitle: "Daily research",
            createdAt: createdAt,
            lastOpenedAt: lastOpenedAt,
            favorite: false
        )
    }
}
