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

    private func makeRepository(endpoint: String, deviceID: String) -> AttachmentDownloadRepository {
        AttachmentDownloadRepository(
            endpoint: URL(string: endpoint)!,
            deviceID: deviceID,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession(),
            cacheRoot: cacheRoot
        )
    }

    private static func response(
        _ request: URLRequest,
        status: Int,
        headers: [String: String] = [:]
    ) -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: nil,
            headerFields: headers
        )!
        return (response, Data("artifact-data".utf8))
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
}
