import XCTest

@MainActor
final class OutboundAttachmentUITests: XCTestCase {
    private enum Fixture {
        static let message = "chat.message.outbound-attachments-message"
        static let imageCard = "attachment.card.outbound-image"
        static let documentCard = "attachment.card.outbound-document"
        static let retryCard = "attachment.card.outbound-retry"
        static let retryButton = "attachment.retry.outbound-retry"
        static let retryOpen = "attachment.open.outbound-retry"
        static let imageOpen = "attachment.open.outbound-image"
        static let imagePreview = "attachment.preview.outbound-image"
        static let imagePreviewDone = "attachment.preview.done.outbound-image"
        static let documentOpen = "attachment.open.outbound-document"
        static let documentPreview = "attachment.preview.outbound-document"
        static let documentPreviewDone = "attachment.preview.done.outbound-document"
        static let imageShare = "attachment.share.outbound-image"
    }

    func testOutboundCardsRetryAndPreviewPreserveTranscript() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let message = app.descendants(matching: .any)[Fixture.message]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(message.waitForExistence(timeout: 5))
        assertElementExists(Fixture.imageCard, in: app, transcript: transcript)
        assertElementExists(Fixture.documentCard, in: app, transcript: transcript)
        assertElementExists(Fixture.retryCard, in: app, transcript: transcript)
        XCTAssertTrue(app.descendants(matching: .any)[Fixture.imageCard].label.contains("release-chart.png"))
        XCTAssertTrue(app.descendants(matching: .any)[Fixture.imageCard].label.contains("ready"))
        XCTAssertTrue(app.descendants(matching: .any)[Fixture.retryCard].label.contains("download failed"))

        let retry = app.descendants(matching: .any)[Fixture.retryButton]
        scrollToElement(retry, in: transcript)
        XCTAssertTrue(retry.isHittable)
        retry.tap()

        let retryOpen = app.descendants(matching: .any)[Fixture.retryOpen]
        scrollToElement(retryOpen, in: transcript)
        XCTAssertTrue(retryOpen.isHittable)

        let imageOpen = app.descendants(matching: .any)[Fixture.imageOpen]
        scrollToElement(imageOpen, in: transcript)
        XCTAssertTrue(imageOpen.isHittable)
        imageOpen.tap()

        let preview = app.descendants(matching: .any)[Fixture.imagePreview]
        let done = app.buttons[Fixture.imagePreviewDone]
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertTrue(done.waitForExistence(timeout: 5))

        let previewScreenshot = XCTAttachment(screenshot: app.screenshot())
        previewScreenshot.name = "outbound-image-preview"
        previewScreenshot.lifetime = .keepAlways
        add(previewScreenshot)

        done.tap()
        XCTAssertTrue(waitForDisappearance(preview))
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(message.waitForExistence(timeout: 5))

        let documentOpen = app.buttons[Fixture.documentOpen]
        scrollToElement(documentOpen, in: transcript)
        XCTAssertTrue(documentOpen.isHittable)
        documentOpen.tap()
        let documentPreview = app.descendants(matching: .any)[Fixture.documentPreview]
        let documentDone = app.buttons[Fixture.documentPreviewDone]
        XCTAssertTrue(documentPreview.waitForExistence(timeout: 5))
        XCTAssertTrue(documentDone.waitForExistence(timeout: 5))
        documentDone.tap()
        XCTAssertTrue(waitForDisappearance(documentPreview))
        XCTAssertTrue(message.waitForExistence(timeout: 5))

        let transcriptScreenshot = XCTAttachment(screenshot: app.screenshot())
        transcriptScreenshot.name = "outbound-attachment-cards"
        transcriptScreenshot.lifetime = .keepAlways
        add(transcriptScreenshot)
    }

    func testImageSharePresentsSystemActivityController() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))

        let share = app.buttons[Fixture.imageShare]
        scrollToElement(share, in: transcript)
        XCTAssertTrue(share.isHittable)
        share.tap()

        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5))
        let close = app.buttons["header.closeButton"]
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        close.tap()
        XCTAssertTrue(waitForDisappearance(app.otherElements["ActivityListView"]))
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
    }

    func testAttachmentCardsRemainOperableAtAccessibilityXXXL() {
        let app = launchApp(extraArguments: [
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityExtraExtraExtraLarge"
        ])
        let transcript = app.scrollViews["chat.transcript"]
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))

        let imageCard = app.descendants(matching: .any)[Fixture.imageCard]
        scrollToElement(imageCard, in: transcript)
        XCTAssertTrue(imageCard.label.contains("release-chart.png"))
        let imageOpen = app.buttons[Fixture.imageOpen]
        scrollToElement(imageOpen, in: transcript)
        XCTAssertTrue(imageOpen.isHittable)

        let retry = app.buttons[Fixture.retryButton]
        scrollToElement(retry, in: transcript)
        XCTAssertTrue(retry.isHittable)
        XCTAssertTrue(app.descendants(matching: .any)[Fixture.retryCard].label.contains("download failed"))
    }

    private func launchApp(extraArguments: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-outbound-attachments"] + extraArguments
        app.launch()
        return app
    }

    private func assertElementExists(
        _ identifier: String,
        in app: XCUIApplication,
        transcript: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let element = app.descendants(matching: .any)[identifier]
        scrollToElement(element, in: transcript)
        XCTAssertTrue(element.exists, "Missing \(identifier)", file: file, line: line)
    }

    private func scrollToElement(_ element: XCUIElement, in scrollView: XCUIElement) {
        guard !element.waitForExistence(timeout: 1) || !element.isHittable else { return }
        for _ in 0..<8 where !element.isHittable {
            scrollView.swipeUp()
        }
        XCTAssertTrue(element.waitForExistence(timeout: 3))
    }

    private func waitForDisappearance(_ element: XCUIElement) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: element
        )
        return XCTWaiter.wait(for: [expectation], timeout: 5) == .completed
    }
}
