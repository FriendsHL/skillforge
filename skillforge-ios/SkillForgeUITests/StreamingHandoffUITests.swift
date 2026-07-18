import XCTest

@MainActor
final class StreamingHandoffUITests: XCTestCase {
    func testStreamingSnapshotHandoffsNeverLeaveADataBackedBlankTranscript() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-streaming-handoff"]
        app.launch()

        let transcript = app.scrollViews["chat.transcript"]
        let composer = app.textFields["chat.composer"]
        let finalMessage = app.descendants(matching: .any)["chat.message.remote-streaming-final-5"]
        let handoffComplete = app.descendants(matching: .any)["chat.streamingHandoff.complete"]
        let scrollToBottom = app.buttons["chat.scrollToBottom"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToBottom.waitForExistence(timeout: 5))
        XCTAssertTrue(composer.waitForExistence(timeout: 5))
        composer.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 3))
        assertTranscriptContainsVisibleMessage(app: app, transcript: transcript, checkpoint: "streaming")
        assertTranscriptContainsVisibleMessage(app: app, transcript: transcript, checkpoint: "tool-complete")
        XCTAssertTrue(waitForCheckpoint("persisted", in: app, timeout: 5))
        let firstCycleTail = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier == %@ OR identifier == %@",
            "chat.message.remote-streaming-final-1",
            "chat.message.streaming-ui-test-streaming-handoff-session"
        ))
        XCTAssertEqual(
            firstCycleTail.count,
            1,
            "The first persisted row and its transient predecessor must never coexist"
        )
        continueCheckpoint("persisted", in: app)
        XCTAssertTrue(handoffComplete.waitForExistence(timeout: 15))
        transcript.tap()
        let keyboardGone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: app.keyboards.firstMatch
        )
        XCTAssertEqual(XCTWaiter.wait(for: [keyboardGone], timeout: 3), .completed)
        XCTAssertTrue(finalMessage.waitForExistence(timeout: 5))
        XCTAssertTrue(
            finalMessage.frame.intersects(transcript.frame),
            "Messages still exist, but no final message intersects the transcript viewport"
        )

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "streaming-handoff-visible-transcript"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testUserScrollAwayReleasesStreamingTailIdentityAfterHandoff() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-streaming-handoff"]
        app.launch()

        let transcript = app.scrollViews["chat.transcript"]
        let composer = app.textFields["chat.composer"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(composer.waitForExistence(timeout: 5))
        composer.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 3))
        XCTAssertTrue(waitForCheckpoint("streaming", in: app, timeout: 5))

        transcript.swipeDown()
        continueCheckpoint("streaming", in: app)
        XCTAssertTrue(waitForCheckpoint("tool-complete", in: app, timeout: 5))
        let latestButton = app.buttons["chat.scrollToBottom"]
        XCTAssertTrue(latestButton.waitForExistence(timeout: 3))
        XCTAssertTrue(
            latestButton.label.contains("1 条新消息"),
            "One streaming Assistant turn must count once while follow is paused"
        )
        continueCheckpoint("tool-complete", in: app)
        XCTAssertTrue(waitForCheckpoint("persisted", in: app, timeout: 5))
        continueCheckpoint("persisted", in: app)

        XCTAssertTrue(
            app.descendants(matching: .any)["chat.streamingHandoff.complete"].waitForExistence(timeout: 25)
        )
        XCTAssertTrue(
            app.descendants(matching: .any)["chat.streamingHandoff.identity-released"]
                .waitForExistence(timeout: 3),
            "The transient tail identity must be released when an unpinned handoff completes"
        )
        let messages = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier BEGINSWITH 'chat.message.'")
        )
        XCTAssertTrue(
            messages.allElementsBoundByIndex.contains { $0.frame.intersects(transcript.frame) },
            "Scrolling away from the tail must preserve a visible transcript"
        )
    }

    private func assertTranscriptContainsVisibleMessage(
        app: XCUIApplication,
        transcript: XCUIElement,
        checkpoint: String
    ) {
        XCTAssertTrue(
            waitForCheckpoint(checkpoint, in: app, timeout: 5),
            "Missing \(checkpoint) fixture checkpoint"
        )
        let messages = app.descendants(matching: .any).matching(
            NSPredicate(format: "identifier BEGINSWITH 'chat.message.'")
        )
        XCTAssertTrue(
            messages.allElementsBoundByIndex.contains { $0.frame.intersects(transcript.frame) },
            "Transcript had no visible message during \(checkpoint)"
        )
        continueCheckpoint(checkpoint, in: app)
    }

    private func waitForCheckpoint(
        _ checkpoint: String,
        in app: XCUIApplication,
        timeout: TimeInterval
    ) -> Bool {
        let marker = app.descendants(matching: .any)["chat.streamingHandoff.\(checkpoint)"]
        return marker.waitForExistence(timeout: timeout)
    }

    private func continueCheckpoint(_ checkpoint: String, in app: XCUIApplication) {
        let button = app.buttons["chat.streamingHandoff.continue.\(checkpoint)"]
        XCTAssertTrue(button.waitForExistence(timeout: 3))
        button.tap()
    }
}
