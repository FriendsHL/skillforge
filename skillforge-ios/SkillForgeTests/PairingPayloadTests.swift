import XCTest
@testable import SkillForge

final class PairingPayloadTests: XCTestCase {
    func testDecodesSkillForgePairingPayload() throws {
        let payload = try PairingPayload.decode(from: payloadText(type: PairingPayload.expectedType))

        XCTAssertEqual(payload.serverName, "SkillForge Dev")
        XCTAssertEqual(payload.endpoints, ["http://127.0.0.1:8080"])
    }

    func testRejectsUnsupportedPayloadType() {
        XCTAssertThrowsError(try PairingPayload.decode(from: payloadText(type: "other")))
    }

    private func payloadText(type: String) -> String {
        let object: [String: Any] = [
            "type": type,
            "version": 1,
            "serverName": "SkillForge Dev",
            "pairingId": UUID().uuidString,
            "pairingSecret": UUID().uuidString,
            "endpoints": ["http://127.0.0.1:8080"],
            "expiresAt": "2026-07-09T06:05:00Z"
        ]
        let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }
}

@MainActor
final class PairingReviewTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    func testDecodeMovesToReviewWithoutProbingAndClearsRawEntry() throws {
        var probedEndpoints: [String] = []
        let model = PairingFlowModel(
            now: { self.now },
            probe: { endpoints in
                probedEndpoints = endpoints
                return URL(string: "https://forge.example.com")
            }
        )

        try model.decode(payloadText(expiresAt: now.addingTimeInterval(300)))

        XCTAssertEqual(probedEndpoints, [])
        XCTAssertEqual(model.payloadText, "")
        guard case let .review(review) = model.stage else {
            return XCTFail("Expected decoded payload to require review")
        }
        XCTAssertEqual(review.serverName, "Personal SkillForge")
        XCTAssertEqual(review.endpointDisplays, ["https://forge.example.com:8443", "http://192.168.1.20:8080"])
        XCTAssertEqual(review.expiry, .valid(until: now.addingTimeInterval(300)))
    }

    func testPrepareClaimOnlyProbesAfterExplicitConfirmation() async throws {
        var probeCount = 0
        let model = PairingFlowModel(
            now: { self.now },
            probe: { _ in
                probeCount += 1
                return URL(string: "https://forge.example.com:8443")
            }
        )
        try model.decode(payloadText(expiresAt: now.addingTimeInterval(300)))
        XCTAssertEqual(probeCount, 0)

        let context = await model.prepareClaim()

        XCTAssertNotNil(context)
        XCTAssertEqual(probeCount, 1)
        XCTAssertEqual(context?.endpoint.host, "forge.example.com")
        XCTAssertEqual(model.stage, .claiming)

        let duplicateContext = await model.prepareClaim()
        XCTAssertNil(duplicateContext)
        XCTAssertEqual(probeCount, 1)
    }

    func testExpiredPayloadCanBeReviewedButCannotBeClaimed() async throws {
        var probeCount = 0
        let model = PairingFlowModel(
            now: { self.now },
            probe: { _ in
                probeCount += 1
                return URL(string: "https://forge.example.com")
            }
        )
        try model.decode(payloadText(expiresAt: now.addingTimeInterval(-1)))

        guard case let .review(review) = model.stage else {
            return XCTFail("Expected expired payload review")
        }
        XCTAssertEqual(review.expiry, .expired)
        let context = await model.prepareClaim()
        XCTAssertNil(context)
        XCTAssertEqual(probeCount, 0)
        XCTAssertEqual(model.error?.kind, .expiredPayload)
        guard case let .review(refreshedReview) = model.stage else {
            return XCTFail("Expected review to refresh after expiry")
        }
        XCTAssertEqual(refreshedReview.expiry, .expired)
    }

    func testDuplicateEndpointsAppearOnceInReview() throws {
        let model = PairingFlowModel(now: { self.now }, probe: { _ in nil })
        try model.decode(payloadText(
            endpoints: ["https://forge.example.com", "https://forge.example.com"],
            expiresAt: now.addingTimeInterval(300)
        ))

        guard case let .review(review) = model.stage else {
            return XCTFail("Expected pairing review")
        }
        XCTAssertEqual(review.endpointDisplays, ["https://forge.example.com"])
    }

    func testUnsafeEndpointIsRejectedBeforeReview() {
        let model = PairingFlowModel(now: { self.now }, probe: { _ in nil })

        XCTAssertThrowsError(
            try model.decode(payloadText(
                endpoints: ["file:///private/data", "ftp://forge.example.com/archive"],
                expiresAt: now.addingTimeInterval(300)
            ))
        )
        XCTAssertEqual(model.error?.kind, .invalidPayload)
        XCTAssertEqual(model.payloadText, "")
    }

    func testServerFailureUsesSafeCopyWithoutRawResponseBody() {
        let presentation = PairingErrorPresentation.claimFailure(
            MobileApiError.httpStatus(500, "secret-stack: internal database details")
        )

        XCTAssertEqual(presentation.kind, .serverUnavailable)
        XCTAssertFalse(presentation.message.contains("secret-stack"))
        XCTAssertFalse(presentation.message.contains("database"))
        XCTAssertEqual(presentation.recoveryTitle, "Try Again")
    }

    func testExistingBadRequestEnvelopeMapsUsedSecretWithoutExposingBody() {
        let presentation = PairingErrorPresentation.claimFailure(
            MobileApiError.httpStatus(400, #"{"error":"pairing is not pending"}"#)
        )

        XCTAssertEqual(presentation.kind, .alreadyUsed)
        XCTAssertFalse(presentation.message.contains("not pending"))
        XCTAssertEqual(presentation.recoveryTitle, "Scan New QR")
    }

    func testReviewExpiresWhileItRemainsVisible() {
        let review = PairingReview(
            serverName: "Personal SkillForge",
            endpointDisplays: ["https://forge.example.com"],
            expiry: .valid(until: now.addingTimeInterval(30))
        )

        XCTAssertFalse(review.isExpired(at: now))
        XCTAssertTrue(review.isExpired(at: now.addingTimeInterval(31)))
    }

    private func payloadText(
        endpoints: [String] = [
            "https://forge.example.com:8443/internal/path?token=hidden",
            "192.168.1.20:8080/base"
        ],
        expiresAt: Date
    ) -> String {
        let object: [String: Any] = [
            "type": PairingPayload.expectedType,
            "version": 1,
            "serverName": "Personal SkillForge",
            "pairingId": UUID().uuidString,
            "pairingSecret": UUID().uuidString,
            "endpoints": endpoints,
            "expiresAt": ISO8601DateFormatter().string(from: expiresAt)
        ]
        let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }
}
