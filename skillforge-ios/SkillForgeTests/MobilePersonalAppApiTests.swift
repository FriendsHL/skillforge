import Foundation
import XCTest
@testable import SkillForge

final class MobilePersonalAppApiTests: XCTestCase {
    override func tearDown() {
        URLProtocolStub.requestHandler = nil
        super.tearDown()
    }

    func testListPersonalAppsUsesExactContractAndKeysetQuery() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            return Self.response(request, status: 200, body: Self.pageJSON)
        }
        let client = makeClient()
        let createdAfter = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-07-01T00:00:00Z"))

        let page = try await client.listPersonalApps(query: MobilePersonalAppQuery(
            cursor: "cursor-1",
            limit: 25,
            sort: .recent,
            search: "brief",
            agentId: 7,
            sessionId: "session-1",
            favorite: true,
            createdAfter: createdAfter
        ))

        let request = try XCTUnwrap(observedRequest)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/base/api/mobile/client/personal-apps")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        let query = try XCTUnwrap(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(Dictionary(uniqueKeysWithValues: query.map { ($0.name, $0.value ?? "") }), [
            "cursor": "cursor-1",
            "limit": "25",
            "sort": "recent",
            "q": "brief",
            "agentId": "7",
            "sessionId": "session-1",
            "favorite": "true",
            "createdAfter": "2026-07-01T00:00:00Z"
        ])
        XCTAssertEqual(page.nextCursor, "cursor-2")
        XCTAssertEqual(page.items.first?.artifactId, "artifact-1")
        XCTAssertEqual(page.items.first?.sourceMessageSeq, 42)
        XCTAssertEqual(page.items.first?.availability, .unknown("temporarily_hidden"))
    }

    func testVisiblePageChaserFollowsEmptyCursorPageUntilValidItem() async throws {
        nonisolated(unsafe) var requests: [URLRequest] = []
        URLProtocolStub.requestHandler = { request in
            requests.append(request)
            if requests.count == 1 {
                return Self.response(
                    request,
                    status: 200,
                    body: #"{"items":[],"nextCursor":"cursor-2"}"#
                )
            }
            return Self.response(request, status: 200, body: Self.pageJSON)
        }
        let client = makeClient()

        let batch = try await PersonalAppVisiblePageChaser.fetch(startingCursor: nil) { cursor in
            try await client.listPersonalApps(query: MobilePersonalAppQuery(cursor: cursor))
        }

        XCTAssertEqual(requests.count, 2)
        XCTAssertNil(URLComponents(url: requests[0].url!, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "cursor" }))
        XCTAssertEqual(
            URLComponents(url: requests[1].url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "cursor" })?.value,
            "cursor-2"
        )
        XCTAssertEqual(batch.pages.count, 2)
        XCTAssertEqual(batch.items.map(\.artifactId), ["artifact-1"])
        XCTAssertFalse(batch.reachedSafetyLimit)
    }

    func testWhitespaceOnlySearchIsOmittedInsteadOfRejected() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            return Self.response(request, status: 200, body: Self.pageJSON)
        }

        _ = try await makeClient().listPersonalApps(query: MobilePersonalAppQuery(search: "  \n\t "))

        let request = try XCTUnwrap(observedRequest)
        let query = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertNil(query?.first(where: { $0.name == "q" }))
    }

    func testPreferenceAndOpenedEndpointsUseControlledBodies() async throws {
        nonisolated(unsafe) var requests: [URLRequest] = []
        URLProtocolStub.requestHandler = { request in
            requests.append(request)
            return Self.response(
                request,
                status: 200,
                body: #"{"artifactId":"artifact-1","favorite":true,"lastOpenedAt":"2026-07-17T10:00:00Z"}"#
            )
        }
        let client = makeClient()

        let preference = try await client.setPersonalAppFavorite(artifactId: "artifact-1", favorite: true)
        let opened = try await client.recordPersonalAppOpened(artifactId: "artifact-1")

        XCTAssertEqual(preference.artifactId, "artifact-1")
        XCTAssertEqual(opened.lastOpenedAt, "2026-07-17T10:00:00Z")
        XCTAssertEqual(requests.map(\.httpMethod), ["PATCH", "POST"])
        XCTAssertEqual(requests.map { $0.url?.path }, [
            "/base/api/mobile/client/personal-apps/artifact-1/preference",
            "/base/api/mobile/client/personal-apps/artifact-1/opened"
        ])
        let body = try XCTUnwrap(Self.requestBodyData(requests[0]))
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: Bool], ["favorite": true])
        XCTAssertNil(requests[1].httpBody)
    }

    func testInvalidIdentifierAndLimitFailBeforeNetwork() async throws {
        nonisolated(unsafe) var requestCount = 0
        URLProtocolStub.requestHandler = { request in
            requestCount += 1
            return Self.response(request, status: 200, body: Self.pageJSON)
        }
        let client = makeClient()

        await XCTAssertThrowsErrorAsync {
            _ = try await client.listPersonalApps(query: MobilePersonalAppQuery(limit: 51))
        }
        await XCTAssertThrowsErrorAsync {
            _ = try await client.setPersonalAppFavorite(artifactId: "../secret", favorite: true)
        }

        XCTAssertEqual(requestCount, 0)
    }

    func testPersonalAppErrorEnvelopeDoesNotExposeRawBody() async throws {
        URLProtocolStub.requestHandler = { request in
            Self.response(
                request,
                status: 403,
                body: #"{"code":"PERSONAL_APP_FORBIDDEN","message":"Access is no longer available","debug":"/private/path"}"#
            )
        }

        do {
            _ = try await makeClient().recordPersonalAppOpened(artifactId: "artifact-1")
            XCTFail("Expected a controlled rejection")
        } catch let MobileApiError.personalAppRejected(status, code, message) {
            XCTAssertEqual(status, 403)
            XCTAssertEqual(code, "PERSONAL_APP_FORBIDDEN")
            XCTAssertEqual(message, "Access is no longer available")
            XCTAssertFalse(message.contains("/private/path"))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    private func makeClient() -> MobileApiClient {
        MobileApiClient(
            baseURL: URL(string: "https://server.example/base")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )
    }

    private static func response(
        _ request: URLRequest,
        status: Int,
        body: String
    ) -> (HTTPURLResponse, Data) {
        (
            HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!,
            Data(body.utf8)
        )
    }

    private static func requestBodyData(_ request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var result = Data()
        var buffer = [UInt8](repeating: 0, count: 1_024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            result.append(buffer, count: count)
        }
        return result
    }

    private static let pageJSON = #"{"items":[{"artifactId":"artifact-1","sessionId":"session-1","sourceMessageSeq":42,"title":"AI Brief","caption":"35 updates","schemaVersion":1,"permissions":[],"network":[],"agentId":7,"agentName":"Research Agent","sessionTitle":"Daily research","createdAt":"2026-07-17T09:00:00Z","lastOpenedAt":null,"favorite":false,"availability":"temporarily_hidden"}],"nextCursor":"cursor-2"}"#
}

private func XCTAssertThrowsErrorAsync(
    _ expression: () async throws -> Void,
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        try await expression()
        XCTFail("Expected error", file: file, line: line)
    } catch {
        // Expected.
    }
}
