import Foundation
import XCTest
@testable import SkillForge

final class EndpointProbeTests: XCTestCase {
    override func tearDown() {
        URLProtocolStub.requestHandler = nil
        super.tearDown()
    }

    func testSelectsFirstReachableEndpoint() async throws {
        URLProtocolStub.requestHandler = { request in
            let status = request.url?.host == "bad.local" ? 503 : 401
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: status,
                httpVersion: nil,
                headerFields: nil
            )!
            return (response, Data())
        }

        let probe = EndpointProbe(session: Self.stubbedSession())
        let endpoint = await probe.firstReachableEndpoint(from: [
            "http://bad.local:8080",
            "http://good.local:8080"
        ])

        XCTAssertEqual(endpoint?.host(), "good.local")
    }

    func testPrefersReachablePrivateLANBeforeTailscaleHTTPS() async throws {
        URLProtocolStub.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 401,
                httpVersion: nil,
                headerFields: nil
            )!
            return (response, Data())
        }

        let probe = EndpointProbe(session: Self.stubbedSession())
        let endpoint = await probe.firstReachableEndpoint(from: [
            "https://macbook-air.example.ts.net",
            "http://192.168.1.6:3000"
        ])

        XCTAssertEqual(endpoint?.host(), "192.168.1.6")
    }

    func testFallsBackToTailscaleWhenPrivateLANIsUnavailable() async throws {
        URLProtocolStub.requestHandler = { request in
            if request.url?.host == "192.168.1.6" {
                throw URLError(.cannotConnectToHost)
            }
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 401,
                httpVersion: nil,
                headerFields: nil
            )!
            return (response, Data())
        }

        let probe = EndpointProbe(session: Self.stubbedSession())
        let endpoint = await probe.firstReachableEndpoint(from: [
            "https://macbook-air.example.ts.net",
            "http://192.168.1.6:3000"
        ])

        XCTAssertEqual(endpoint?.host(), "macbook-air.example.ts.net")
    }

    static func stubbedSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [URLProtocolStub.self]
        return URLSession(configuration: configuration)
    }
}

final class URLProtocolStub: URLProtocol {
    nonisolated(unsafe) static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let handler = Self.requestHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
