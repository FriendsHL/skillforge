import Foundation
import XCTest
@testable import SkillForge

@MainActor
final class AttachmentDownloadStoreTests: XCTestCase {
    func testLastVisibleOwnerCancelsInFlightDownload() async throws {
        let downloader = StoreTestDownloader(mode: .cancellable)
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureAttachment
        store.retain(attachment)
        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { await downloader.callCount == 1 }

        store.release(attachment)

        try await waitUntil { await downloader.cancellationCount == 1 }
        XCTAssertEqual(store.state(for: attachment), .idle)
    }

    func testSharedVisibleOwnerKeepsDownloadUntilFinalRelease() async throws {
        let downloader = StoreTestDownloader(mode: .cancellable)
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureAttachment
        store.retain(attachment)
        store.retain(attachment)
        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { await downloader.callCount == 1 }

        store.release(attachment)
        try await Task.sleep(for: .milliseconds(100))

        let cancellationCount = await downloader.cancellationCount
        XCTAssertEqual(cancellationCount, 0)
        XCTAssertEqual(store.state(for: attachment), .downloading)

        store.release(attachment)
        try await waitUntil { await downloader.cancellationCount == 1 }
    }

    func testCancelledOldAttemptCannotOverwriteSuccessfulRetry() async throws {
        let downloader = StoreTestDownloader(mode: .retryRace)
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureAttachment
        store.retain(attachment)
        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { await downloader.callCount == 1 }

        store.retry(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { await downloader.callCount == 2 }
        try await waitUntil { store.state(for: attachment) == .available(StoreTestDownloader.newURL) }
        try await Task.sleep(for: .milliseconds(300))

        XCTAssertEqual(store.state(for: attachment), .available(StoreTestDownloader.newURL))
    }

    func testDisconnectCleanupCancelsStateAndAwaitsRepositoryCacheClear() async throws {
        let downloader = StoreTestDownloader(mode: .cancellable)
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureAttachment
        store.retain(attachment)
        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { await downloader.callCount == 1 }

        await store.clearAllAndWait()

        let clearCount = await downloader.clearCount
        XCTAssertEqual(clearCount, 1)
        XCTAssertEqual(store.state(for: attachment), .idle)
    }

    func testReplacingRepositoryCancelsOldDownloadAndUsesNewEndpointForRetry() async throws {
        let oldDownloader = StoreTestDownloader(mode: .cancellable)
        let newDownloader = StoreTestDownloader(mode: .retryRace)
        let store = AttachmentDownloadStore(repository: oldDownloader)
        let attachment = fixtureAttachment
        store.retain(attachment)
        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { await oldDownloader.callCount == 1 }

        store.replaceRepository(newDownloader)
        try await waitUntil { await oldDownloader.cancellationCount == 1 }
        XCTAssertEqual(store.state(for: attachment), .idle)

        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})

        try await waitUntil { await newDownloader.callCount == 1 }
        try await waitUntil { store.state(for: attachment) == .available(StoreTestDownloader.oldURL) }
    }

    func testChatLoadRejectsMismatchedLocalMetadataBeforeDownload() async throws {
        let downloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.newURL, .zero))
        let store = AttachmentDownloadStore(repository: downloader)
        let metadata = PersonalAppLocalRecord(
            artifactId: "other-artifact",
            sessionId: "other-session",
            sourceMessageSeq: fixturePersonalApp.sourceMessageSeq,
            title: fixturePersonalApp.title,
            caption: fixturePersonalApp.caption,
            schemaVersion: fixturePersonalApp.schemaVersion,
            permissions: fixturePersonalApp.permissions,
            network: fixturePersonalApp.network,
            agentId: fixturePersonalApp.agentId,
            agentName: fixturePersonalApp.agentName,
            sessionTitle: fixturePersonalApp.sessionTitle,
            createdAt: fixturePersonalApp.createdAt,
            lastOpenedAt: fixturePersonalApp.lastOpenedAt,
            favorite: fixturePersonalApp.favorite
        )

        store.load(
            sessionID: "session-1",
            attachment: fixtureInteractiveAttachment,
            localMetadata: metadata,
            onUnauthorized: {}
        )
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertEqual(
            store.state(for: fixtureInteractiveAttachment),
            .failed(AttachmentDownloadError.invalidIdentifier.localizedDescription)
        )
        let callCount = await downloader.callCount
        let metadataWriteCount = await downloader.localMetadataWriteCount
        let localApps = await store.localPersonalApps()
        XCTAssertEqual(callCount, 0, "Mismatched metadata must be rejected before download")
        XCTAssertEqual(metadataWriteCount, 0)
        XCTAssertTrue(localApps.isEmpty)
    }

    func testClearPersonalAppPreventsDelayedChatLoadFromWritingMetadataOrState() async throws {
        let downloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.oldURL, .milliseconds(250)))
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment

        store.load(
            sessionID: "session-1",
            attachment: attachment,
            localMetadata: PersonalAppLocalRecord(app: fixturePersonalApp),
            onUnauthorized: {}
        )
        try await waitUntil { await downloader.callCount == 1 }

        await store.clearPersonalApp(sessionID: "session-1", attachment: attachment)
        try await Task.sleep(for: .milliseconds(350))

        let metadataWriteCount = await downloader.localMetadataWriteCount
        let localApps = await store.localPersonalApps()
        XCTAssertEqual(store.state(for: attachment), .idle)
        XCTAssertEqual(metadataWriteCount, 0, "A cancelled stale Chat load must not write metadata")
        XCTAssertTrue(localApps.isEmpty)
    }

    func testClearPersonalAppPreventsDelayedDirectPrepareFromOverwritingIdle() async throws {
        let downloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.oldURL, .milliseconds(250)))
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment
        let preparation = Task {
            try await store.preparePersonalApp(
                sessionID: "session-1",
                attachment: attachment,
                metadata: fixturePersonalApp,
                onUnauthorized: {}
            )
        }
        try await waitUntil { await downloader.callCount == 1 }

        await store.clearPersonalApp(sessionID: "session-1", attachment: attachment)

        do {
            _ = try await preparation.value
            XCTFail("A cleared direct prepare must not publish its stale URL")
        } catch is CancellationError {
            // Clearing invalidates the direct prepare's per-artifact identity.
        }
        XCTAssertEqual(store.state(for: attachment), .idle)
        let localApps = await store.localPersonalApps()
        XCTAssertTrue(localApps.isEmpty, "A stale prepare must not write Personal App metadata")
    }

    func testNewerDirectPrepareWinsWhenOlderResultArrivesLater() async throws {
        let downloader = StoreTestDownloader(mode: .retryRace)
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment
        let older = Task {
            try await store.preparePersonalApp(
                sessionID: "session-1",
                attachment: attachment,
                onUnauthorized: {}
            )
        }
        try await waitUntil { await downloader.callCount == 1 }

        let newerURL = try await store.preparePersonalApp(
            sessionID: "session-1",
            attachment: attachment,
            onUnauthorized: {}
        )

        do {
            _ = try await older.value
            XCTFail("An older direct prepare must not overwrite the newer result")
        } catch is CancellationError {
            // The second prepare supersedes the first operation identity.
        }
        XCTAssertEqual(newerURL, StoreTestDownloader.newURL)
        XCTAssertEqual(store.state(for: attachment), .available(StoreTestDownloader.newURL))
    }

    func testReplacingRepositoryInvalidatesDelayedDirectPrepareFromOldEndpoint() async throws {
        let oldDownloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.oldURL, .milliseconds(250)))
        let newDownloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.newURL, .milliseconds(40)))
        let store = AttachmentDownloadStore(repository: oldDownloader)
        let attachment = fixtureInteractiveAttachment
        let oldPreparation = Task {
            try await store.preparePersonalApp(
                sessionID: "session-1",
                attachment: attachment,
                onUnauthorized: {}
            )
        }
        try await waitUntil { await oldDownloader.callCount == 1 }
        store.replaceRepository(newDownloader)

        let newURL = try await store.preparePersonalApp(
            sessionID: "session-1",
            attachment: attachment,
            onUnauthorized: {}
        )

        do {
            _ = try await oldPreparation.value
            XCTFail("The old repository must lose authority after replacement")
        } catch is CancellationError {
            // Repository replacement invalidates all direct prepare identities.
        }
        XCTAssertEqual(newURL, StoreTestDownloader.newURL)
        XCTAssertEqual(store.state(for: attachment), .available(StoreTestDownloader.newURL))
    }

    func testReplacingRepositoryDiscardsAvailableURLFromOldNamespace() async throws {
        let oldDownloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.oldURL, .zero))
        let newDownloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.newURL, .zero))
        let store = AttachmentDownloadStore(repository: oldDownloader)
        let attachment = fixtureInteractiveAttachment
        let oldURL = try await store.preparePersonalApp(
            sessionID: "session-1",
            attachment: attachment,
            onUnauthorized: {}
        )
        XCTAssertEqual(oldURL, StoreTestDownloader.oldURL)

        store.replaceRepository(newDownloader)
        XCTAssertEqual(store.state(for: attachment), .idle)

        let newURL = try await store.preparePersonalApp(
            sessionID: "session-1",
            attachment: attachment,
            onUnauthorized: {}
        )
        XCTAssertEqual(newURL, StoreTestDownloader.newURL)
        let oldCalls = await oldDownloader.callCount
        let newCalls = await newDownloader.callCount
        XCTAssertEqual(oldCalls, 1)
        XCTAssertEqual(newCalls, 1)
    }

    func testClearAllPreventsDelayedDirectPrepareFromRecreatingState() async throws {
        let downloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.oldURL, .milliseconds(250)))
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment
        let preparation = Task {
            try await store.preparePersonalApp(
                sessionID: "session-1",
                attachment: attachment,
                onUnauthorized: {}
            )
        }
        try await waitUntil { await downloader.callCount == 1 }

        await store.clearAllAndWait()

        do {
            _ = try await preparation.value
            XCTFail("Clear-all must invalidate delayed direct prepares")
        } catch is CancellationError {
            // Namespace cleanup remains authoritative over the delayed result.
        }
        XCTAssertEqual(store.state(for: attachment), .idle)
    }

    func testDirectPrepareSupersedesOlderChatLoadWithoutStateRegression() async throws {
        let downloader = StoreTestDownloader(mode: .retryRace)
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment
        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { await downloader.callCount == 1 }

        let directURL = try await store.preparePersonalApp(
            sessionID: "session-1",
            attachment: attachment,
            onUnauthorized: {}
        )
        try await Task.sleep(for: .milliseconds(300))

        XCTAssertEqual(directURL, StoreTestDownloader.newURL)
        XCTAssertEqual(store.state(for: attachment), .available(StoreTestDownloader.newURL))
    }

    func testPersonalAppPrepareWithMetadataIsImmediatelyDiscoverableAsDownloaded() async throws {
        let downloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.newURL, .zero))
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment
        let app = fixturePersonalApp

        _ = try await store.preparePersonalApp(
            sessionID: app.sessionId,
            attachment: attachment,
            metadata: app,
            onUnauthorized: {}
        )

        let localApps = await store.localPersonalApps()
        XCTAssertEqual(localApps.map(\.artifactId), [attachment.id])
    }

    func testRepeatedPersonalAppPrepareWithRealRepositoryAlwaysRevalidates() async throws {
        let downloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.newURL, .zero))
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment

        _ = try await store.preparePersonalApp(
            sessionID: "session-1",
            attachment: attachment,
            onUnauthorized: {}
        )
        _ = try await store.preparePersonalApp(
            sessionID: "session-1",
            attachment: attachment,
            onUnauthorized: {}
        )

        let callCount = await downloader.callCount
        XCTAssertEqual(callCount, 2)
    }

    func testPersonalAppPrepareRejectsMismatchedMetadataIdentityBeforeDownload() async throws {
        let downloader = StoreTestDownloader(mode: .fixed(StoreTestDownloader.newURL, .zero))
        let store = AttachmentDownloadStore(repository: downloader)
        let mismatched = MobilePersonalApp(
            artifactId: "other-artifact",
            sessionId: "other-session",
            sourceMessageSeq: fixturePersonalApp.sourceMessageSeq,
            title: fixturePersonalApp.title,
            caption: fixturePersonalApp.caption,
            schemaVersion: fixturePersonalApp.schemaVersion,
            permissions: fixturePersonalApp.permissions,
            network: fixturePersonalApp.network,
            agentId: fixturePersonalApp.agentId,
            agentName: fixturePersonalApp.agentName,
            sessionTitle: fixturePersonalApp.sessionTitle,
            createdAt: fixturePersonalApp.createdAt,
            lastOpenedAt: fixturePersonalApp.lastOpenedAt,
            favorite: fixturePersonalApp.favorite,
            availability: fixturePersonalApp.availability
        )

        do {
            _ = try await store.preparePersonalApp(
                sessionID: "session-1",
                attachment: fixtureInteractiveAttachment,
                metadata: mismatched,
                onUnauthorized: {}
            )
            XCTFail("Mismatched metadata must be rejected")
        } catch AttachmentDownloadError.invalidIdentifier {
            // Identity is validated before any repository work.
        }

        let callCount = await downloader.callCount
        XCTAssertEqual(callCount, 0)
        let localApps = await store.localPersonalApps()
        XCTAssertTrue(localApps.isEmpty)
    }

    func testChatLoadSupersedesOlderDirectPrepareWithoutStateRegression() async throws {
        let downloader = StoreTestDownloader(mode: .retryRace)
        let store = AttachmentDownloadStore(repository: downloader)
        let attachment = fixtureInteractiveAttachment
        let directPreparation = Task {
            try await store.preparePersonalApp(
                sessionID: "session-1",
                attachment: attachment,
                onUnauthorized: {}
            )
        }
        try await waitUntil { await downloader.callCount == 1 }

        store.load(sessionID: "session-1", attachment: attachment, onUnauthorized: {})
        try await waitUntil { store.state(for: attachment) == .available(StoreTestDownloader.newURL) }

        do {
            _ = try await directPreparation.value
            XCTFail("The older direct prepare must lose authority to the Chat load")
        } catch is CancellationError {
            // Both entry points share one per-artifact state generation.
        }
        XCTAssertEqual(store.state(for: attachment), .available(StoreTestDownloader.newURL))
    }

    private var fixtureAttachment: ChatAttachment {
        ChatAttachment(id: "store-fixture", kind: .pdf, filename: "fixture.pdf")
    }

    private var fixtureInteractiveAttachment: ChatAttachment {
        ChatAttachment(
            id: "store-interactive-fixture",
            kind: .interactive,
            mimeType: "text/html",
            filename: "fixture.html"
        )
    }

    private var fixturePersonalApp: MobilePersonalApp {
        MobilePersonalApp(
            artifactId: fixtureInteractiveAttachment.id,
            sessionId: "session-1",
            sourceMessageSeq: 42,
            title: "Shared Personal App",
            caption: "Immediately available in Downloaded",
            schemaVersion: 1,
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

    private func waitUntil(
        timeout: Duration = .seconds(2),
        condition: @escaping @MainActor () async -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while clock.now < deadline {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTFail("Timed out waiting for asynchronous state")
    }
}

private actor StoreTestDownloader: AttachmentDownloading {
    enum Mode {
        case cancellable
        case retryRace
        case fixed(URL, Duration)
    }

    static let oldURL = URL(fileURLWithPath: "/tmp/old.pdf")
    static let newURL = URL(fileURLWithPath: "/tmp/new.pdf")

    private(set) var callCount = 0
    private(set) var cancellationCount = 0
    private(set) var clearCount = 0
    private(set) var localMetadataWriteCount = 0
    private var personalApps: [PersonalAppLocalRecord] = []
    private let mode: Mode

    init(mode: Mode) {
        self.mode = mode
    }

    func download(sessionID: String, attachment: ChatAttachment) async throws -> URL {
        callCount += 1
        let attempt = callCount
        switch mode {
        case .cancellable:
            do {
                try await Task.sleep(for: .seconds(30))
                return Self.oldURL
            } catch is CancellationError {
                cancellationCount += 1
                throw CancellationError()
            }
        case .retryRace:
            if attempt == 1 {
                try? await Task.sleep(for: .milliseconds(250))
                return Self.oldURL
            }
            try await Task.sleep(for: .milliseconds(40))
            return Self.newURL
        case let .fixed(url, delay):
            try? await Task.sleep(for: delay)
            return url
        }
    }

    func clearCache() async throws {
        clearCount += 1
    }

    func localPersonalApps() async throws -> [PersonalAppLocalRecord] {
        personalApps
    }

    func recordPersonalAppMetadata(_ app: MobilePersonalApp) async throws {
        personalApps.removeAll { $0.artifactId == app.artifactId }
        personalApps.append(PersonalAppLocalRecord(app: app))
    }

    func recordPersonalAppLocalMetadata(_ record: PersonalAppLocalRecord) async throws {
        localMetadataWriteCount += 1
        personalApps.removeAll { $0.artifactId == record.artifactId }
        personalApps.append(record)
    }
}
