import XCTest

@MainActor
final class ChatKeyboardUITests: XCTestCase {
    func testScrollToBottomOnVeryLongLazyTranscriptKeepsLatestContentVisible() {
        let app = launchApp(arguments: ["--ui-testing-chat-long"])
        let transcript = app.scrollViews["chat.transcript"]
        let scrollToBottom = app.buttons["chat.scrollToBottom"]
        let tapCount = app.staticTexts["chat.bottomButtonTapCount"]
        let completionCount = app.staticTexts["chat.bottomScrollCompletionCount"]
        let latestMessage = app.staticTexts["Latest fixture message"].firstMatch

        XCTAssertTrue(transcript.waitForExistence(timeout: 3))
        XCTAssertTrue(scrollToBottom.waitForExistence(timeout: 3))
        XCTAssertTrue(tapCount.waitForExistence(timeout: 3))
        XCTAssertTrue(completionCount.waitForExistence(timeout: 3))
        assertLatestMessageIsNotVisible(latestMessage, in: transcript)

        scrollToBottom.tap()

        assertLatestMessageIsVisible(latestMessage, in: transcript)
        let countBeforeFling = tapCount.value as? String
        let completionsBeforeFling = completionCount.value as? String
        let scrollButtonCoordinate = scrollToBottom.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)
        )
        let flingStart = transcript.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.24)
        )
        let flingEnd = transcript.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.88)
        )

        flingStart.press(
            forDuration: 0.01,
            thenDragTo: flingEnd,
            withVelocity: .fast,
            thenHoldForDuration: 0
        )
        scrollButtonCoordinate.tap()

        let expectedCount = String((Int(countBeforeFling ?? "0") ?? 0) + 1)
        expectation(
            for: NSPredicate(format: "value == %@", expectedCount),
            evaluatedWith: tapCount
        )
        waitForExpectations(timeout: 2)
        let expectedCompletion = String((Int(completionsBeforeFling ?? "0") ?? 0) + 1)
        expectation(
            for: NSPredicate(format: "value == %@", expectedCompletion),
            evaluatedWith: completionCount
        )
        waitForExpectations(timeout: 2)
        assertLatestMessageIsVisible(latestMessage, in: transcript)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "chat-long-transcript-after-scroll-to-bottom"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testScrollToBottomDuringFastDecelerationDeliversTapAndReachesLatestMessage() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let scrollToBottom = app.buttons["chat.scrollToBottom"]
        let tapCount = app.staticTexts["chat.bottomButtonTapCount"]
        let completionCount = app.staticTexts["chat.bottomScrollCompletionCount"]
        let latestMessage = app.staticTexts["Latest fixture message"].firstMatch

        XCTAssertTrue(transcript.waitForExistence(timeout: 3))
        XCTAssertTrue(scrollToBottom.waitForExistence(timeout: 3))
        XCTAssertTrue(tapCount.waitForExistence(timeout: 3))
        XCTAssertTrue(completionCount.waitForExistence(timeout: 3))

        scrollToBottom.tap()
        assertLatestMessageIsVisible(latestMessage, in: transcript)

        let countBeforeFling = tapCount.value as? String
        let completionsBeforeFling = completionCount.value as? String
        let scrollButtonCoordinate = scrollToBottom.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)
        )
        let flingStart = transcript.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.24)
        )
        let flingEnd = transcript.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.88)
        )

        flingStart.press(
            forDuration: 0.01,
            thenDragTo: flingEnd,
            withVelocity: .fast,
            thenHoldForDuration: 0
        )
        scrollButtonCoordinate.tap()

        let expectedCount = String((Int(countBeforeFling ?? "0") ?? 0) + 1)
        let tapDelivered = NSPredicate(format: "value == %@", expectedCount)
        expectation(for: tapDelivered, evaluatedWith: tapCount)
        waitForExpectations(timeout: 2)
        let expectedCompletion = String((Int(completionsBeforeFling ?? "0") ?? 0) + 1)
        let bottomConfirmed = NSPredicate(format: "value == %@", expectedCompletion)
        expectation(for: bottomConfirmed, evaluatedWith: completionCount)
        waitForExpectations(timeout: 2)
        assertLatestMessageIsVisible(latestMessage, in: transcript)
    }

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

    func testAcceptedQueryShowsAssistantActivityUntilFixtureCompletes() {
        let app = launchApp(arguments: ["--ui-testing-assistant-activity"])
        let composer = app.textFields["chat.composer"]
        let send = app.buttons["chat.send"]
        let activity = app.descendants(matching: .any)["chat.assistantActivity"]

        XCTAssertTrue(composer.waitForExistence(timeout: 3))
        composer.tap()
        composer.typeText("Show activity")
        send.tap()

        XCTAssertTrue(activity.waitForExistence(timeout: 2))
        XCTAssertEqual(activity.label, "正在运行")
        XCTAssertTrue(waitForElementToDisappear(activity, timeout: 7))
    }

    private func launchApp(arguments: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-chat"] + arguments
        app.launch()
        return app
    }

    private func waitForKeyboardToDisappear(in app: XCUIApplication) -> Bool {
        let predicate = NSPredicate(format: "exists == false")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: app.keyboards.firstMatch)
        return XCTWaiter.wait(for: [expectation], timeout: 3) == .completed
    }

    private func waitForElementToDisappear(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let predicate = NSPredicate(format: "exists == false")
        return XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: predicate, object: element)],
            timeout: timeout
        ) == .completed
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
