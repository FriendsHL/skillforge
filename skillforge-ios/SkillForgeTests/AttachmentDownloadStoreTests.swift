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

    private var fixtureAttachment: ChatAttachment {
        ChatAttachment(id: "store-fixture", kind: .pdf, filename: "fixture.pdf")
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
    }

    static let oldURL = URL(fileURLWithPath: "/tmp/old.pdf")
    static let newURL = URL(fileURLWithPath: "/tmp/new.pdf")

    private(set) var callCount = 0
    private(set) var cancellationCount = 0
    private(set) var clearCount = 0
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
        }
    }

    func clearCache() async throws {
        clearCount += 1
    }
}
