import XCTest

@MainActor
final class ChatKeyboardUITests: XCTestCase {
    func testScrollToBottomAfterKeyboardDismissKeepsTranscriptVisible() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let composer = app.textFields["chat.composer"]
        let scrollToBottom = app.buttons["chat.scrollToBottom"]
        let latestMessage = app.staticTexts["Latest fixture message"].firstMatch

        XCTAssertTrue(transcript.waitForExistence(timeout: 3))
        XCTAssertTrue(composer.waitForExistence(timeout: 3))
        XCTAssertTrue(scrollToBottom.waitForExistence(timeout: 3))
        XCTAssertFalse(app.staticTexts["Could not connect to the server."].waitForExistence(timeout: 1))

        scrollToBottom.tap()
        assertLatestMessageIsVisible(latestMessage, in: transcript)

        for _ in 0..<5 {
            scrollAwayFromLatest(latestMessage, in: transcript)
            composer.tap()
            XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 2))
            assertLatestMessageIsNotVisible(latestMessage, in: transcript)

            scrollToBottom.tap()

            XCTAssertTrue(waitForKeyboardToDisappear(in: app))
            XCTAssertTrue(transcript.exists)
            assertLatestMessageIsVisible(latestMessage, in: transcript)
        }

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "chat-after-keyboard-scroll-stress"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testTappingTranscriptDismissesKeyboard() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let composer = app.textFields["chat.composer"]

        XCTAssertTrue(composer.waitForExistence(timeout: 3))
        XCTAssertFalse(app.staticTexts["Could not connect to the server."].waitForExistence(timeout: 1))
        composer.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 2))

        transcript.tap()

        XCTAssertTrue(waitForKeyboardToDisappear(in: app))
        XCTAssertTrue(transcript.exists)
    }

    func testSendingTrimmedQueryClearsComposerImmediately() {
        let app = launchApp()
        let composer = app.textFields["chat.composer"]
        let send = app.buttons["chat.send"]

        XCTAssertTrue(composer.waitForExistence(timeout: 3))
        composer.tap()
        composer.typeText("  keep spacing  ")
        send.tap()

        let visibleValue = composer.value as? String ?? ""
        XCTAssertFalse(
            visibleValue.localizedCaseInsensitiveContains("keep spacing"),
            "Composer still contained: \(visibleValue)"
        )
    }

    private func launchApp() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-chat"]
        app.launch()
        return app
    }

    private func waitForKeyboardToDisappear(in app: XCUIApplication) -> Bool {
        let predicate = NSPredicate(format: "exists == false")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: app.keyboards.firstMatch)
        return XCTWaiter.wait(for: [expectation], timeout: 3) == .completed
    }

    private func assertLatestMessageIsVisible(
        _ latestMessage: XCUIElement,
        in transcript: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(latestMessage.waitForExistence(timeout: 3), file: file, line: line)
        XCTAssertFalse(latestMessage.frame.isEmpty, file: file, line: line)
        XCTAssertTrue(
            transcript.frame.insetBy(dx: -1, dy: -1).contains(latestMessage.frame),
            "Latest message should be fully inside the visible transcript instead of a blank scroll region",
            file: file,
            line: line
        )
    }

    private func scrollAwayFromLatest(
        _ latestMessage: XCUIElement,
        in transcript: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let start = transcript.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.28))
        let end = transcript.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.82))
        for _ in 0..<3 {
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        assertLatestMessageIsNotVisible(latestMessage, in: transcript, file: file, line: line)
    }

    private func assertLatestMessageIsNotVisible(
        _ latestMessage: XCUIElement,
        in transcript: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertFalse(
            latestMessage.exists && latestMessage.frame.intersects(transcript.frame),
            "The test must leave the bottom before exercising the recovery path",
            file: file,
            line: line
        )
    }
}
