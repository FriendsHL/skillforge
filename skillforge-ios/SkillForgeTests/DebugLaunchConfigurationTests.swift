#if DEBUG
import XCTest
@testable import SkillForge

@MainActor
final class DebugLaunchConfigurationTests: XCTestCase {
    func testOutboundAttachmentArgumentSelectsDedicatedFixture() {
        XCTAssertEqual(
            DebugLaunchConfiguration.mode(arguments: ["SkillForge", "--ui-testing-outbound-attachments"]),
            .outboundAttachments
        )
    }

    func testStreamingHandoffArgumentSelectsDedicatedFixture() {
        XCTAssertEqual(
            DebugLaunchConfiguration.mode(arguments: ["SkillForge", "--ui-testing-streaming-handoff"]),
            .streamingHandoff
        )
    }

    func testPairingReviewArgumentSelectsDedicatedFixture() {
        XCTAssertEqual(
            DebugLaunchConfiguration.mode(arguments: ["SkillForge", "--ui-testing-pairing-review"]),
            .pairingReview
        )
    }

    func testPairingFixturesDoNotLoadRealKeychainState() {
        XCTAssertTrue(DebugLaunchConfiguration.isUITest(
            arguments: ["SkillForge", "--ui-testing-pairing-ready"]
        ))
        XCTAssertTrue(DebugLaunchConfiguration.isUITest(
            arguments: ["SkillForge", "--ui-testing-pairing-review"]
        ))
    }

    func testUnknownArgumentsDoNotEnableUITestFixture() {
        XCTAssertNil(DebugLaunchConfiguration.mode(arguments: ["SkillForge", "--unrelated-argument"]))
    }

    func testOutboundFixtureTakesPriorityOverOtherFixtureArguments() {
        XCTAssertEqual(
            DebugLaunchConfiguration.mode(arguments: [
                "SkillForge", "--ui-testing-chat", "--ui-testing-outbound-attachments"
            ]),
            .outboundAttachments
        )
    }

    func testSeededFailureRetriesToLocalFixtureWithoutRepository() async throws {
        let localURL = FileManager.default.temporaryDirectory.appending(path: "fixture.pdf")
        let attachment = ChatAttachment(
            id: "retry-fixture",
            kind: .pdf,
            mimeType: "application/pdf",
            filename: "fixture.pdf"
        )
        let store = AttachmentDownloadStore(
            fixtureStates: [attachment.id: .failed("Fixture failure")],
            retryURLs: [attachment.id: localURL]
        )
        XCTAssertEqual(store.state(for: attachment), .failed("Fixture failure"))

        store.retry(sessionID: "fixture-session", attachment: attachment, onUnauthorized: {})
        try await Task.sleep(for: .milliseconds(250))

        XCTAssertEqual(store.state(for: attachment), .available(localURL))
    }
}
#endif
