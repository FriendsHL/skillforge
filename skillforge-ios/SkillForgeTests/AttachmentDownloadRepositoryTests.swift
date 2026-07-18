import CryptoKit
import Foundation
import XCTest
@testable import SkillForge

final class AttachmentDownloadRepositoryTests: XCTestCase {
    private var cacheRoot: URL!

    override func setUpWithError() throws {
        cacheRoot = FileManager.default.temporaryDirectory
            .appending(path: "AttachmentDownloadRepositoryTests-(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: cacheRoot, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        URLProtocolStub.requestHandler = nil
        DeferredURLProtocolStub.requestHandler = nil
        try? FileManager.default.removeItem(at: cacheRoot)
    }

    func testDownloadUsesAuthenticatedSessionAttachmentEndpoint() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            return Self.response(request, status: 200, headers: ["ETag": "\"etag-1\""]) // image bytes are opaque here
        }
        let repository = makeRepository(endpoint: "https://server.example/base", deviceID: "device-1")

        let url = try await repository.download(sessionID: "session-1", attachment: .fixture(id: "attachment-1"))

        XCTAssertEqual(observedRequest?.httpMethod, "GET")
        XCTAssertEqual(observedRequest?.url?.path, "/base/api/mobile/client/sessions/session-1/attachments/attachment-1/data")
        XCTAssertEqual(observedRequest?.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        XCTAssertEqual(observedRequest?.value(forHTTPHeaderField: "Accept"), "*/*")
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        #if targetEnvironment(simulator)
        // CoreSimulator does not surface NSFileProtectionKey after applying it.
        XCTAssertNil(attributes[.protectionKey])
        #else
        XCTAssertEqual(
            attributes[.protectionKey] as? FileProtectionType,
            .completeUntilFirstUserAuthentication
        )
        #endif
        XCTAssertEqual(
            try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup,
            true
        )
    }

    func testCachedDownloadRevalidatesWithETag() async throws {
        nonisolated(unsafe) var requests: [URLRequest] = []
        URLProtocolStub.requestHandler = { request in
            requests.append(request)
            if requests.count == 1 {
                return Self.response(request, status: 200, headers: ["ETag": "\"etag-1\""])
            }
            return Self.response(request, status: 304)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.fixture(id: "attachment-1")

        let first = try await repository.download(sessionID: "session-1", attachment: attachment)
        let second = try await repository.download(sessionID: "session-1", attachment: attachment)

        XCTAssertEqual(first, second)
        XCTAssertNil(requests[0].value(forHTTPHeaderField: "If-None-Match"))
        XCTAssertEqual(requests[1].value(forHTTPHeaderField: "If-None-Match"), "\"etag-1\"")
    }

    func testCacheIsIsolatedByEndpointAndDevice() async throws {
        URLProtocolStub.requestHandler = { request in Self.response(request, status: 200) }
        let attachment = ChatAttachment.fixture(id: "same-attachment")
        let first = makeRepository(endpoint: "https://one.example", deviceID: "device-1")
        let second = makeRepository(endpoint: "https://two.example", deviceID: "device-1")
        let third = makeRepository(endpoint: "https://one.example", deviceID: "device-2")

        let urls = try await [
            first.download(sessionID: "session-1", attachment: attachment),
            second.download(sessionID: "session-1", attachment: attachment),
            third.download(sessionID: "session-1", attachment: attachment)
        ]

        XCTAssertEqual(Set(urls.map(\.path)).count, 3)
    }

    func testUnauthorizedClearsPairedServerCacheAndReturnsUnauthorized() async throws {
        nonisolated(unsafe) var status = 200
        URLProtocolStub.requestHandler = { request in Self.response(request, status: status) }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.fixture(id: "attachment-1")
        let cachedURL = try await repository.download(sessionID: "session-1", attachment: attachment)
        status = 401

        do {
            _ = try await repository.download(sessionID: "session-1", attachment: attachment)
            XCTFail("Expected unauthorized")
        } catch {
            XCTAssertEqual(error as? AttachmentDownloadError, .unauthorized)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: cachedURL.path))
    }

    func testNotFoundReturnsUnavailableWithoutClearingOtherCachedFiles() async throws {
        nonisolated(unsafe) var requestedID = "available"
        URLProtocolStub.requestHandler = { request in
            Self.response(request, status: request.url?.path.contains(requestedID) == true ? 200 : 404)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let availableURL = try await repository.download(
            sessionID: "session-1",
            attachment: .fixture(id: "available")
        )
        requestedID = "never-matches"

        do {
            _ = try await repository.download(sessionID: "session-1", attachment: .fixture(id: "missing"))
            XCTFail("Expected unavailable")
        } catch {
            XCTAssertEqual(error as? AttachmentDownloadError, .unavailable)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: availableURL.path))
    }

    func testFailedDownloadCanBeRetried() async throws {
        nonisolated(unsafe) var requestCount = 0
        URLProtocolStub.requestHandler = { request in
            requestCount += 1
            return Self.response(request, status: requestCount == 1 ? 500 : 200)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.fixture(id: "retry")

        do {
            _ = try await repository.download(sessionID: "session-1", attachment: attachment)
            XCTFail("Expected initial failure")
        } catch {
            XCTAssertEqual(error as? AttachmentDownloadError, .httpStatus(500))
        }
        let retriedURL = try await repository.download(sessionID: "session-1", attachment: attachment)

        XCTAssertEqual(requestCount, 2)
        XCTAssertTrue(FileManager.default.fileExists(atPath: retriedURL.path))
    }

    func testInFlightCancellationLeavesNoCacheEntry() async throws {
        nonisolated(unsafe) var requestCount = 0
        let requestStarted = expectation(description: "download request started")
        let releaseResponse = DispatchSemaphore(value: 0)
        URLProtocolStub.requestHandler = { request in
            requestCount += 1
            requestStarted.fulfill()
            releaseResponse.wait()
            return Self.response(request, status: 200)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.fixture(id: "cancelled")
        let task = Task {
            try await repository.download(sessionID: "session-1", attachment: attachment)
        }
        await fulfillment(of: [requestStarted], timeout: 2)
        task.cancel()
        releaseResponse.signal()

        do {
            _ = try await task.value
            XCTFail("Expected cancellation")
        } catch is CancellationError {
            XCTAssertEqual(requestCount, 1)
        }
        let files = try FileManager.default.contentsOfDirectory(at: cacheRoot, includingPropertiesForKeys: nil)
        XCTAssertTrue(files.allSatisfy { url in
            (try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil).isEmpty) ?? true
        })
    }

    func testInvalidPathComponentFailsBeforeSendingDeviceToken() async throws {
        nonisolated(unsafe) var requestCount = 0
        URLProtocolStub.requestHandler = { request in
            requestCount += 1
            return Self.response(request, status: 200)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")

        do {
            _ = try await repository.download(
                sessionID: "../me",
                attachment: .fixture(id: "attachment-1")
            )
            XCTFail("Expected invalid identifier")
        } catch {
            XCTAssertEqual(error as? AttachmentDownloadError, .invalidIdentifier)
        }
        XCTAssertEqual(requestCount, 0)
    }

    func testDotPathComponentsFailBeforeSendingDeviceToken() async throws {
        nonisolated(unsafe) var requestCount = 0
        URLProtocolStub.requestHandler = { request in
            requestCount += 1
            return Self.response(request, status: 200)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")

        for invalidID in [".", ".."] {
            do {
                _ = try await repository.download(
                    sessionID: "session-1",
                    attachment: .fixture(id: invalidID)
                )
                XCTFail("Expected invalid identifier for \(invalidID)")
            } catch {
                XCTAssertEqual(error as? AttachmentDownloadError, .invalidIdentifier)
            }
        }
        XCTAssertEqual(requestCount, 0)
    }

    func testInteractiveArtifactIsDownloadedOnlyAfterHTMLHashAndManifestAreVerified() async throws {
        nonisolated(unsafe) var paths: [String] = []
        let html = Data("<html><body>Verified app</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            paths.append(request.url!.path)
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(
                    request,
                    status: 200,
                    data: Self.validManifestData
                )
            }
            return Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-1")

        let url = try await repository.prepareInteractiveArtifact(
            sessionID: "session-1",
            attachment: attachment
        )
        let downloadedIDs = try await repository.downloadedPersonalAppIDs()

        XCTAssertEqual(paths, [
            "/api/mobile/client/sessions/session-1/attachments/interactive-1/data",
            "/api/mobile/client/sessions/session-1/attachments/interactive-1/manifest"
        ])
        XCTAssertEqual(try Data(contentsOf: url), html)
        XCTAssertEqual(downloadedIDs, ["interactive-1"])
        let cachedManifest = try await repository.interactiveManifest(
            sessionID: "session-1",
            attachmentID: "interactive-1"
        )
        XCTAssertEqual(cachedManifest.title, "Verified App")
    }

    func testClearInteractiveArtifactInvalidatesDelayedVerifiedDownloadBeforeCommit() async throws {
        let requestStarted = expectation(description: "interactive HTML request started")
        let releaseResponse = DispatchSemaphore(value: 0)
        let html = Data("<html><body>Must not return after clear</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            requestStarted.fulfill()
            releaseResponse.wait()
            return Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-clear-race")
        let preparation = Task {
            try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        }
        await fulfillment(of: [requestStarted], timeout: 2)

        try await repository.clearInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        releaseResponse.signal()

        do {
            _ = try await preparation.value
            XCTFail("A response that predates clear must not recreate the Personal App")
        } catch is CancellationError {
            // The user-issued clear invalidates the older in-flight prepare.
        }
        let downloaded = try await repository.downloadedPersonalAppIDs()
        XCTAssertEqual(downloaded, [])
    }

    func testClearAllInvalidatesDelayedVerifiedDownloadBeforeCommit() async throws {
        let requestStarted = expectation(description: "interactive HTML request started")
        let releaseResponse = DispatchSemaphore(value: 0)
        let html = Data("<html><body>Must not return after clear all</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            requestStarted.fulfill()
            releaseResponse.wait()
            return Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-clear-all-race")
        let preparation = Task {
            try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        }
        await fulfillment(of: [requestStarted], timeout: 2)

        try await repository.clearCache()
        releaseResponse.signal()

        do {
            _ = try await preparation.value
            XCTFail("A response that predates clear-all must not recreate the cache namespace")
        } catch is CancellationError {
            // Namespace invalidation rejects every older in-flight prepare.
        }
        let downloaded = try await repository.downloadedPersonalAppIDs()
        XCTAssertEqual(downloaded, [])
    }

    func testServerRevokeInvalidatesAnOlderDelayedPrepareForTheSameArtifact() async throws {
        nonisolated(unsafe) var dataRequestCount = 0
        nonisolated(unsafe) var olderProtocol: DeferredURLProtocolStub?
        let countLock = NSLock()
        let olderRequestStarted = expectation(description: "older interactive HTML request started")
        let html = Data("<html><body>Stale after revoke</body></html>".utf8)
        let sha = Self.sha256(html)
        DeferredURLProtocolStub.requestHandler = { request, protocolStub in
            if request.url?.path.hasSuffix("/manifest") == true {
                protocolStub.succeed(Self.response(request, status: 200, data: Self.validManifestData))
                return
            }
            countLock.lock()
            dataRequestCount += 1
            let requestNumber = dataRequestCount
            countLock.unlock()
            if requestNumber == 1 {
                olderProtocol = protocolStub
                olderRequestStarted.fulfill()
                return
            }
            protocolStub.succeed(Self.response(request, status: 404))
        }
        let repository = makeRepository(
            endpoint: "https://server.example",
            deviceID: "device-1",
            session: Self.deferredSession()
        )
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-revoke-race")
        let olderPreparation = Task {
            try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        }
        await fulfillment(of: [olderRequestStarted], timeout: 2)

        do {
            _ = try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
            XCTFail("Expected the newer request to observe revocation")
        } catch {
            XCTAssertEqual(error as? AttachmentDownloadError, .unavailable)
        }
        let delayedProtocol = try XCTUnwrap(olderProtocol)
        delayedProtocol.succeed(Self.response(
            delayedProtocol.request,
            status: 200,
            headers: ["ETag": "\"\(sha)\""],
            data: html
        ))

        do {
            _ = try await olderPreparation.value
            XCTFail("The delayed success must not overwrite a newer revocation")
        } catch is CancellationError {
            // The revoke increments the artifact epoch before the old response resumes.
        }
        let downloaded = try await repository.downloadedPersonalAppIDs()
        XCTAssertEqual(downloaded, [])
    }

    func testNewerPrepareWinsWhenSameArtifactResponsesCompleteOutOfOrder() async throws {
        nonisolated(unsafe) var dataRequestCount = 0
        nonisolated(unsafe) var olderProtocol: DeferredURLProtocolStub?
        let countLock = NSLock()
        let olderRequestStarted = expectation(description: "older interactive HTML request started")
        let olderHTML = Data("<html><body>Older response</body></html>".utf8)
        let newerHTML = Data("<html><body>Newer response</body></html>".utf8)
        DeferredURLProtocolStub.requestHandler = { request, protocolStub in
            if request.url?.path.hasSuffix("/manifest") == true {
                protocolStub.succeed(Self.response(request, status: 200, data: Self.validManifestData))
                return
            }
            countLock.lock()
            dataRequestCount += 1
            let requestNumber = dataRequestCount
            countLock.unlock()
            if requestNumber == 1 {
                olderProtocol = protocolStub
                olderRequestStarted.fulfill()
                return
            }
            protocolStub.succeed(Self.response(
                request,
                status: 200,
                headers: ["ETag": "\"\(Self.sha256(newerHTML))\""],
                data: newerHTML
            ))
        }
        let repository = makeRepository(
            endpoint: "https://server.example",
            deviceID: "device-1",
            session: Self.deferredSession()
        )
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-newer-wins")
        let olderPreparation = Task {
            try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        }
        await fulfillment(of: [olderRequestStarted], timeout: 2)

        let newerURL = try await repository.prepareInteractiveArtifact(
            sessionID: "session-1",
            attachment: attachment
        )
        let delayedProtocol = try XCTUnwrap(olderProtocol)
        delayedProtocol.succeed(Self.response(
            delayedProtocol.request,
            status: 200,
            headers: ["ETag": "\"\(Self.sha256(olderHTML))\""],
            data: olderHTML
        ))

        do {
            _ = try await olderPreparation.value
            XCTFail("An older response must not overwrite a newer completed prepare")
        } catch is CancellationError {
            // Starting the newer prepare invalidates the older operation epoch.
        }
        XCTAssertEqual(try Data(contentsOf: newerURL), newerHTML)
        let downloaded = try await repository.downloadedPersonalAppIDs()
        XCTAssertEqual(downloaded, Set([attachment.id]))
    }

    func testInteractiveArtifactRejectsNonSHAETagAndDoesNotEnterDownloadIndex() async throws {
        URLProtocolStub.requestHandler = { request in
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            return Self.response(
                request,
                status: 200,
                headers: ["ETag": "\"not-a-sha\""],
                data: Data("<html></html>".utf8)
            )
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")

        do {
            _ = try await repository.prepareInteractiveArtifact(
                sessionID: "session-1",
                attachment: .interactiveFixture(id: "interactive-1")
            )
            XCTFail("Expected invalid response")
        } catch {
            XCTAssertEqual(error as? AttachmentDownloadError, .invalidResponse)
        }
        let downloadedIDs = try await repository.downloadedPersonalAppIDs()
        XCTAssertEqual(downloadedIDs, [])
    }

    func testInteractiveTransportFailureFallsBackOnlyToVerifiedBundle() async throws {
        nonisolated(unsafe) var shouldFail = false
        let html = Data("<html><body>Offline app</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if shouldFail { throw URLError(.notConnectedToInternet) }
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            return Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-offline")
        let cached = try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        shouldFail = true

        let offline = try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)

        XCTAssertEqual(offline, cached)
        XCTAssertEqual(try Data(contentsOf: offline), html)
    }

    func testInteractiveMalformedManifestDoesNotFallBackToCachedBundle() async throws {
        nonisolated(unsafe) var serveMalformedManifest = false
        let html = Data("<html><body>Previously verified</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(
                    request,
                    status: 200,
                    data: serveMalformedManifest ? Data("{}".utf8) : Self.validManifestData
                )
            }
            return serveMalformedManifest
                ? Self.response(request, status: 304)
                : Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-malformed")
        let cached = try await repository.prepareInteractiveArtifact(
            sessionID: "session-1",
            attachment: attachment
        )
        serveMalformedManifest = true

        do {
            _ = try await repository.prepareInteractiveArtifact(
                sessionID: "session-1",
                attachment: attachment
            )
            XCTFail("A malformed successful response must not be treated as a transient outage")
        } catch {
            XCTAssertEqual(error as? AttachmentDownloadError, .invalidResponse)
        }
        XCTAssertEqual(try Data(contentsOf: cached), html, "A rejected refresh must not corrupt the old bundle")
        let downloaded = try await repository.downloadedPersonalAppIDs()
        XCTAssertEqual(downloaded, ["interactive-malformed"])
    }

    func testInteractiveRevokeNeverFallsBackAndClearsArtifact() async throws {
        nonisolated(unsafe) var status = 200
        let html = Data("<html><body>Revoked app</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if status == 200, request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            return Self.response(request, status: status, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let repository = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.interactiveFixture(id: "interactive-revoked")
        let cached = try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)

        for revokedStatus in [403, 404] {
            status = revokedStatus
            do {
                _ = try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
                XCTFail("Expected revoke for HTTP \(revokedStatus)")
            } catch {
                XCTAssertTrue(
                    error as? AttachmentDownloadError == .forbidden
                        || error as? AttachmentDownloadError == .unavailable
                )
            }
            XCTAssertFalse(FileManager.default.fileExists(atPath: cached.path))
            let downloadedIDs = try await repository.downloadedPersonalAppIDs()
            XCTAssertEqual(downloadedIDs, [])

            status = 200
            _ = try await repository.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        }
    }

    func testLocalInteractiveIndexIsNamespacedByEndpointAndDevice() async throws {
        let html = Data("<html><body>Namespaced</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            return Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let first = makeRepository(endpoint: "https://one.example", deviceID: "device-1")
        let second = makeRepository(endpoint: "https://one.example", deviceID: "device-2")
        _ = try await first.prepareInteractiveArtifact(
            sessionID: "session-1",
            attachment: .interactiveFixture(id: "interactive-1")
        )

        let firstIDs = try await first.downloadedPersonalAppIDs()
        let secondIDs = try await second.downloadedPersonalAppIDs()
        XCTAssertEqual(firstIDs, ["interactive-1"])
        XCTAssertEqual(secondIDs, [])
    }

    func testCorruptedInteractiveIndexCannotTraverseOutsideNamespace() async throws {
        let endpoint = "https://server.example"
        let namespaceName = Self.sha256(Data("\(endpoint)|device-1".utf8))
        let namespace = cacheRoot.appending(path: namespaceName, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: namespace, withIntermediateDirectories: true)
        let sentinel = cacheRoot.appending(path: "do-not-delete.html")
        try Data("sentinel".utf8).write(to: sentinel)
        let index = """
        {"version":1,"entries":{"malicious":{"artifactId":"malicious","sessionId":"session-1","htmlFileName":"../do-not-delete.html","htmlSHA256":"\(String(repeating: "0", count: 64))","manifestFileName":"../do-not-delete.html","manifestSHA256":"\(String(repeating: "0", count: 64))","metadata":null}}}
        """
        try Data(index.utf8).write(to: namespace.appending(path: "personal-app-index.json"))
        let repository = makeRepository(endpoint: endpoint, deviceID: "device-1")

        let downloaded = try await repository.downloadedPersonalAppIDs()

        XCTAssertEqual(downloaded, [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: sentinel.path))
        XCTAssertEqual(try Data(contentsOf: sentinel), Data("sentinel".utf8))
    }

    func testCorruptedInteractiveIndexCannotRebindVerifiedBytesToAnotherArtifactIdentity() async throws {
        let endpoint = "https://server.example"
        let html = Data("<html><body>Identity bound</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            return Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let repository = makeRepository(endpoint: endpoint, deviceID: "device-1")
        _ = try await repository.prepareInteractiveArtifact(
            sessionID: "session-1",
            attachment: .interactiveFixture(id: "interactive-identity")
        )
        let namespaceName = Self.sha256(Data("\(endpoint)|device-1".utf8))
        let indexURL = cacheRoot
            .appending(path: namespaceName, directoryHint: .isDirectory)
            .appending(path: "personal-app-index.json")
        var root = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(contentsOf: indexURL)) as? [String: Any]
        )
        var entries = try XCTUnwrap(root["entries"] as? [String: Any])
        var entry = try XCTUnwrap(entries["interactive-identity"] as? [String: Any])
        entry["artifactId"] = "different-artifact"
        entries["interactive-identity"] = entry
        root["entries"] = entries
        try JSONSerialization.data(withJSONObject: root).write(to: indexURL, options: .atomic)

        let downloaded = try await repository.downloadedPersonalAppIDs()
        XCTAssertEqual(downloaded, [])
    }

    func testChatDownloadMetadataRemainsDiscoverableAfterRelaunchOffline() async throws {
        nonisolated(unsafe) var offline = false
        let html = Data("<html><body>Chat download</body></html>".utf8)
        let sha = Self.sha256(html)
        URLProtocolStub.requestHandler = { request in
            if offline { throw URLError(.notConnectedToInternet) }
            if request.url?.path.hasSuffix("/manifest") == true {
                return Self.response(request, status: 200, data: Self.validManifestData)
            }
            return Self.response(request, status: 200, headers: ["ETag": "\"\(sha)\""], data: html)
        }
        let first = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let attachment = ChatAttachment.interactiveFixture(id: "chat-downloaded")
        _ = try await first.prepareInteractiveArtifact(sessionID: "session-1", attachment: attachment)
        try await first.recordPersonalAppLocalMetadata(PersonalAppLocalRecord(
            artifactId: attachment.id,
            sessionId: "session-1",
            sourceMessageSeq: 42,
            title: "Chat Personal App",
            caption: "Downloaded from chat",
            schemaVersion: 1,
            permissions: [],
            network: [],
            agentId: 7,
            agentName: "Research Agent",
            sessionTitle: "Daily research",
            createdAt: "2026-07-17T09:00:00Z",
            lastOpenedAt: nil,
            favorite: false
        ))
        offline = true

        let relaunched = makeRepository(endpoint: "https://server.example", deviceID: "device-1")
        let local = try await relaunched.localPersonalApps()
        let offlineURL = try await relaunched.prepareInteractiveArtifact(
            sessionID: "session-1",
            attachment: attachment
        )

        XCTAssertEqual(local.map(\.artifactId), ["chat-downloaded"])
        XCTAssertEqual(try Data(contentsOf: offlineURL), html)
    }

    private func makeRepository(
        endpoint: String,
        deviceID: String,
        session: URLSession? = nil
    ) -> AttachmentDownloadRepository {
        AttachmentDownloadRepository(
            endpoint: URL(string: endpoint)!,
            deviceID: deviceID,
            deviceToken: "device-token",
            session: session ?? EndpointProbeTests.stubbedSession(),
            cacheRoot: cacheRoot
        )
    }

    private static func response(
        _ request: URLRequest,
        status: Int,
        headers: [String: String] = [:],
        data: Data = Data("artifact-data".utf8)
    ) -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: nil,
            headerFields: headers
        )!
        return (response, data)
    }

    private static let validManifestData = Data(#"{"schemaVersion":1,"title":"Verified App","fallback":"Open in chat","permissions":[],"network":[],"initialData":{},"stateSchema":{}}"#.utf8)

    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func deferredSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [DeferredURLProtocolStub.self]
        return URLSession(configuration: configuration)
    }
}

private final class DeferredURLProtocolStub: URLProtocol {
    nonisolated(unsafe) static var requestHandler: ((URLRequest, DeferredURLProtocolStub) -> Void)?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let requestHandler = Self.requestHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        requestHandler(request, self)
    }

    override func stopLoading() {}

    func succeed(_ result: (HTTPURLResponse, Data)) {
        client?.urlProtocol(self, didReceive: result.0, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: result.1)
        client?.urlProtocolDidFinishLoading(self)
    }
}

private extension ChatAttachment {
    static func fixture(id: String) -> ChatAttachment {
        ChatAttachment(
            id: id,
            kind: .pdf,
            mimeType: "application/pdf",
            filename: "report.pdf"
        )
    }

    static func interactiveFixture(id: String) -> ChatAttachment {
        ChatAttachment(
            id: id,
            kind: .interactive,
            mimeType: "text/html",
            filename: "personal-app.html",
            caption: "Verified app",
            title: "Verified App",
            artifactSchemaVersion: 1
        )
    }
}
