import Foundation
import XCTest
@testable import SkillForge

@MainActor
final class AppStateTests: XCTestCase {
    override func tearDown() {
        URLProtocolStub.requestHandler = nil
        super.tearDown()
    }

    func testCompletePairingPublishesDeviceTokenForChat() async throws {
        let keychain = MemoryKeychainStore()
        let state = AppState(keychain: keychain, loadOnInit: false)
        let endpoint = URL(string: "http://192.168.1.6:8080")!

        let defaultAgent = MobileAgentSummary(id: 3, name: "Main Assistant")
        await state.completePairing(
            endpoint: endpoint,
            endpoints: [
                URL(string: "https://macbook-air.example.ts.net")!,
                endpoint
            ],
            deviceToken: "device-token",
            device: nil,
            defaultAgent: defaultAgent
        )

        guard case let .paired(pairedEndpoint, deviceToken, device, pairedDefaultAgent) = state.phase else {
            return XCTFail("Expected paired phase")
        }
        XCTAssertEqual(pairedEndpoint, endpoint)
        XCTAssertEqual(deviceToken, "device-token")
        XCTAssertNil(device)
        XCTAssertEqual(pairedDefaultAgent?.name, "Main Assistant")
        let storedEndpointData = try XCTUnwrap(
            try keychain.readString(for: .endpoints)?.data(using: .utf8)
        )
        XCTAssertEqual(
            try JSONDecoder().decode([String].self, from: storedEndpointData),
            ["http://192.168.1.6:8080", "https://macbook-air.example.ts.net"]
        )
    }

    func testStoredEndpointSetSelectsReachableLANBeforeTailscale() async throws {
        let keychain = MemoryKeychainStore()
        try keychain.saveString("https://macbook-air.example.ts.net", for: .endpoint)
        try keychain.saveString(
            "[\"https://macbook-air.example.ts.net\",\"http://192.168.1.6:3000\"]",
            for: .endpoints
        )
        try keychain.saveString("device-token", for: .deviceToken)
        URLProtocolStub.requestHandler = { request in
            let data = """
                {
                  "user":{"id":1},
                  "device":{"id":"device-1","deviceName":"iPhone","scopes":[]},
                  "defaultAgent":null,
                  "features":{"chat":true,"attachments":true,"push":false}
                }
                """.data(using: .utf8)!
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!
            return (response, data)
        }
        let state = AppState(
            keychain: keychain,
            session: EndpointProbeTests.stubbedSession(),
            loadOnInit: false
        )

        await state.loadStoredSession()

        guard case let .paired(endpoint, _, _, _) = state.phase else {
            return XCTFail("Expected stored pairing")
        }
        XCTAssertEqual(endpoint.host(), "192.168.1.6")
        XCTAssertEqual(
            try keychain.readString(for: .endpoint),
            "http://192.168.1.6:3000"
        )
    }

    func testRuntimeSelectionMovesFromTailscaleToReachableLAN() async throws {
        let keychain = MemoryKeychainStore()
        let tailscale = URL(string: "https://macbook-air.example.ts.net")!
        let lan = URL(string: "http://192.168.1.6:3000")!
        URLProtocolStub.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: nil
            )!
            let data = Data("""
                {
                  "user":{"id":1},
                  "device":{"id":"device-1","deviceName":"iPhone","scopes":[]},
                  "defaultAgent":{"id":3,"name":"Main Assistant"},
                  "features":{"chat":true,"attachments":true,"push":false}
                }
                """.utf8)
            return (response, data)
        }
        let state = AppState(
            keychain: keychain,
            session: EndpointProbeTests.stubbedSession(),
            loadOnInit: false
        )
        await state.completePairing(
            endpoint: tailscale,
            endpoints: [tailscale, lan],
            deviceToken: "device-token",
            device: nil
        )

        await state.refreshEndpointSelection()

        guard case let .paired(endpoint, _, _, _) = state.phase else {
            return XCTFail("Expected paired state")
        }
        XCTAssertEqual(endpoint, lan)
        XCTAssertEqual(try keychain.readString(for: .endpoint), lan.absoluteString)
    }

    func testTransientBootstrapFailureRetainsStoredPairing() async throws {
        let keychain = MemoryKeychainStore()
        try keychain.saveString("http://192.168.1.6:8080", for: .endpoint)
        try keychain.saveString("device-token", for: .deviceToken)
        URLProtocolStub.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 503,
                httpVersion: nil,
                headerFields: nil
            )!
            return (response, Data())
        }
        let state = AppState(
            keychain: keychain,
            session: EndpointProbeTests.stubbedSession(),
            loadOnInit: false
        )

        await state.loadStoredSession()

        guard case let .paired(endpoint, token, _, _) = state.phase else {
            return XCTFail("Expected stored pairing to remain available offline")
        }
        XCTAssertEqual(endpoint.host(), "192.168.1.6")
        XCTAssertEqual(token, "device-token")
        XCTAssertEqual(try keychain.readString(for: .deviceToken), "device-token")
    }

    func testUnauthorizedBootstrapClearsRevokedPairing() async throws {
        let keychain = MemoryKeychainStore()
        try keychain.saveString("http://192.168.1.6:8080", for: .endpoint)
        try keychain.saveString("revoked-token", for: .deviceToken)
        URLProtocolStub.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 401,
                httpVersion: nil,
                headerFields: nil
            )!
            return (response, Data())
        }
        let state = AppState(
            keychain: keychain,
            session: EndpointProbeTests.stubbedSession(),
            loadOnInit: false
        )

        await state.loadStoredSession()

        guard case .needsPairing = state.phase else {
            return XCTFail("Expected revoked token to require pairing")
        }
        XCTAssertNil(try keychain.readString(for: .deviceToken))
    }
}

private final class MemoryKeychainStore: KeychainStoring {
    private var values: [KeychainKey: String] = [:]

    func readString(for key: KeychainKey) throws -> String? {
        values[key]
    }

    func saveString(_ value: String, for key: KeychainKey) throws {
        values[key] = value
    }

    func deleteString(for key: KeychainKey) throws {
        values.removeValue(forKey: key)
    }
}
