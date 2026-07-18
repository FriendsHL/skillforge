import XCTest

@MainActor
final class AgentFirstChatLayoutUITests: XCTestCase {
    private enum Fixture {
        static let assistantSurface = "chat.message.fixture-2.assistantSurface"
        static let userSurface = "chat.message.fixture-1.userSurface"
        static let composerSurface = "chat.composerSurface"
    }

    func testAgentFirstChatUsesWideAssistantCompactUserAndComposerSurfaces() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let assistantSurface = app.descendants(matching: .any)[Fixture.assistantSurface]
        let userSurface = app.descendants(matching: .any)[Fixture.userSurface]
        let header = app.descendants(matching: .any)["chat.header.agent"]
        let composerSurface = app.descendants(matching: .any)[Fixture.composerSurface]
        let attachment = app.buttons["chat.attachFile"]
        let send = app.buttons["chat.send"]
        let scrollToBottom = app.buttons["chat.scrollToBottom"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(header.waitForExistence(timeout: 5))
        XCTAssertTrue(composerSurface.waitForExistence(timeout: 5))
        XCTAssertTrue(attachment.waitForExistence(timeout: 5))
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToBottom.waitForExistence(timeout: 5))
        XCTAssertTrue(reveal(assistantSurface, in: transcript))

        let transcriptWidth = transcript.frame.width
        let readingWidth = transcriptWidth - 32
        let assistantWidth = assistantSurface.frame.width
        XCTAssertGreaterThan(transcriptWidth, 0)
        XCTAssertGreaterThan(readingWidth, 0)
        XCTAssertFalse(assistantSurface.frame.isEmpty)
        XCTAssertGreaterThanOrEqual(
            assistantWidth,
            readingWidth * 0.92,
            "Assistant results should use at least 92% of the transcript reading width"
        )

        attachScreenshot(named: "agent-first-chat-light", app: app)

        XCTAssertTrue(reveal(userSurface, in: transcript))
        let userWidth = userSurface.frame.width
        XCTAssertFalse(userSurface.frame.isEmpty)
        XCTAssertLessThanOrEqual(
            userWidth,
            readingWidth * 0.94,
            "Long user messages must remain within the 92% policy plus layout tolerance"
        )
        XCTAssertGreaterThan(
            assistantWidth,
            userWidth,
            "The assistant reading surface must remain wider than the user surface"
        )

        XCTAssertFalse(header.frame.isEmpty)
        XCTAssertLessThanOrEqual(
            header.frame.height,
            64,
            "Healthy Session, Agent, and runtime identity should remain a two-line header"
        )
        XCTAssertFalse(composerSurface.frame.isEmpty)
        XCTAssertLessThanOrEqual(
            composerSurface.frame.height,
            72,
            "The normal composer surface should remain visually compact"
        )
        assertMinimumTapTarget(attachment)
        assertMinimumTapTarget(send)
        assertMinimumTapTarget(scrollToBottom)
    }

    func testAgentFirstChatKeepsKeySurfacesAndControlsUsableAtAccessibilityXXXL() {
        let app = launchApp(extraArguments: ["--ui-testing-accessibility-xxxl"])
        let transcript = app.scrollViews["chat.transcript"]
        let assistantSurface = app.descendants(matching: .any)[Fixture.assistantSurface]
        let userSurface = app.descendants(matching: .any)[Fixture.userSurface]
        let composerSurface = app.descendants(matching: .any)[Fixture.composerSurface]
        let composer = app.textFields["chat.composer"]
        let attachment = app.buttons["chat.attachFile"]
        let send = app.buttons["chat.send"]
        let sessions = app.buttons["Sessions"]
        let newConversation = app.buttons["chat.newConversation"]
        let scrollToBottom = app.buttons["chat.scrollToBottom"]
        let header = app.descendants(matching: .any)["chat.header.agent"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(composerSurface.waitForExistence(timeout: 5))
        XCTAssertTrue(composer.waitForExistence(timeout: 5))
        XCTAssertTrue(attachment.waitForExistence(timeout: 5))
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        XCTAssertTrue(sessions.waitForExistence(timeout: 5))
        XCTAssertTrue(newConversation.waitForExistence(timeout: 5))
        XCTAssertTrue(scrollToBottom.waitForExistence(timeout: 5))
        XCTAssertTrue(header.waitForExistence(timeout: 5))
        XCTAssertTrue(reveal(userSurface, in: transcript))
        XCTAssertTrue(reveal(assistantSurface, in: transcript))
        XCTAssertFalse(assistantSurface.frame.isEmpty)
        XCTAssertFalse(userSurface.frame.isEmpty)
        XCTAssertFalse(composerSurface.frame.isEmpty)
        XCTAssertLessThanOrEqual(
            header.frame.height,
            120,
            "Accessibility chrome must not consume the transcript with an unbounded header"
        )
        XCTAssertLessThanOrEqual(
            composerSurface.frame.height,
            140,
            "Accessibility chrome must keep the composer compact enough to leave a reading surface"
        )
        assertContained(header, in: app)
        assertContained(composerSurface, in: app)

        attachScreenshot(named: "agent-first-chat-accessibility-xxxl", app: app)

        XCTAssertTrue(composer.isHittable)
        XCTAssertTrue(attachment.isEnabled)
        XCTAssertTrue(attachment.isHittable)
        assertMinimumTapTarget(attachment)
        assertMinimumTapTarget(sessions)
        assertMinimumTapTarget(newConversation)
        assertMinimumTapTarget(scrollToBottom)

        composer.tap()
        composer.typeText("Check accessible send")
        XCTAssertTrue(send.isEnabled)
        XCTAssertTrue(send.isHittable)
        assertMinimumTapTarget(send)
    }

    func testAgentFirstChatRendersSupportedMarkdownAndToolResultInDarkMode() {
        let app = launchApp(extraArguments: ["--ui-testing-dark-mode"])
        let transcript = app.scrollViews["chat.transcript"]
        let assistantSurface = app.descendants(matching: .any)[Fixture.assistantSurface]
        let heading = app.descendants(matching: .any)["chat.message.fixture-2.markdown.heading.0"]
        let unorderedList = app.descendants(matching: .any)["chat.message.fixture-2.markdown.unorderedList.1"]
        let orderedList = app.descendants(matching: .any)["chat.message.fixture-2.markdown.orderedList.2"]
        let quote = app.descendants(matching: .any)["chat.message.fixture-2.markdown.quote.3"]
        let code = app.descendants(matching: .any)["chat.message.fixture-2.markdown.code.4"]
        let linkParagraph = app.descendants(matching: .any)["chat.message.fixture-2.markdown.paragraph.5"]
        let toolCard = app.descendants(matching: .any)["chat.toolCall.fixture-tool-2"]
        let toolDetails = app.buttons["chat.toolCall.fixture-tool-2.details"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(reveal(assistantSurface, in: transcript))
        XCTAssertTrue(heading.waitForExistence(timeout: 2))
        XCTAssertTrue(unorderedList.waitForExistence(timeout: 2))
        XCTAssertTrue(orderedList.waitForExistence(timeout: 2))
        XCTAssertTrue(quote.waitForExistence(timeout: 2))
        XCTAssertTrue(reveal(code, in: transcript))
        XCTAssertTrue(linkParagraph.waitForExistence(timeout: 2))
        XCTAssertTrue(toolCard.waitForExistence(timeout: 2))
        XCTAssertTrue(toolDetails.waitForExistence(timeout: 2))
        assertMinimumTapTarget(toolDetails)
        toolDetails.tap()
        XCTAssertEqual(toolDetails.label, "收起详情")
        toolDetails.tap()
        XCTAssertNotEqual(toolDetails.label, "收起详情")
        XCTAssertTrue(app.links["Open source"].waitForExistence(timeout: 2))

        attachScreenshot(named: "agent-first-chat-dark-markdown", app: app)
    }

    func testAgentFirstMarkdownCodeBlockShowsCopyFeedbackWithMinimumTapTarget() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let language = app.descendants(matching: .any)[
            "chat.message.fixture-2.markdown.codeLanguage.4"
        ]
        let copy = app.buttons["chat.message.fixture-2.markdown.codeCopy.4"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(reveal(copy, in: transcript))
        XCTAssertTrue(language.waitForExistence(timeout: 2))
        XCTAssertEqual(language.label, "SWIFT")
        XCTAssertEqual(copy.label, "复制 swift 代码")
        assertMinimumTapTarget(copy)

        copy.tap()

        XCTAssertEqual(copy.label, "已复制 swift 代码")
    }

    func testAgentFirstMarkdownCodeBlockRemainsUsableAtAccessibilityXXXL() {
        let app = launchApp(extraArguments: ["--ui-testing-accessibility-xxxl"])
        let transcript = app.scrollViews["chat.transcript"]
        let code = app.descendants(matching: .any)["chat.message.fixture-2.markdown.code.4"]
        let copy = app.buttons["chat.message.fixture-2.markdown.codeCopy.4"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(reveal(code, in: transcript))
        XCTAssertTrue(copy.waitForExistence(timeout: 2))
        XCTAssertTrue(copy.isHittable)
        assertMinimumTapTarget(copy)
        assertContained(copy, in: app)

        attachScreenshot(named: "agent-first-markdown-code-accessibility-xxxl", app: app)
    }

    func testAgentFirstStandardAttachmentCardAdaptsToDarkMode() {
        let app = launchApp(
            baseArguments: ["--ui-testing-outbound-attachments"],
            extraArguments: ["--ui-testing-dark-mode"]
        )
        let card = app.descendants(matching: .any)["attachment.card.outbound-document"]

        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCTAssertFalse(card.frame.isEmpty)
        attachScreenshot(named: "agent-first-attachment-dark", app: app)
    }

    func testAgentFirstComposerAttachmentRemovalUsesMinimumTapTarget() {
        let app = launchApp(extraArguments: ["--ui-testing-uploaded-attachment"])
        let removeAttachment = app.buttons["chat.attachment.remove.attachment-fixture"]

        XCTAssertTrue(removeAttachment.waitForExistence(timeout: 5))
        XCTAssertTrue(removeAttachment.isHittable)
        assertMinimumTapTarget(removeAttachment)
    }

    func testProductionCompanionShellKeepsComposerAdjacentToTabBar() {
        let app = launchApp(extraArguments: ["--ui-testing-companion-shell"])
        let transcript = app.scrollViews["chat.transcript"]
        let composerSurface = app.descendants(matching: .any)[Fixture.composerSurface]
        let chatTab = app.buttons["tab.chat"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(composerSurface.waitForExistence(timeout: 5))
        XCTAssertTrue(chatTab.waitForExistence(timeout: 5))
        XCTAssertLessThanOrEqual(composerSurface.frame.maxY, chatTab.frame.minY)
        XCTAssertLessThanOrEqual(
            chatTab.frame.minY - composerSurface.frame.maxY,
            16,
            "The production shell must not leave a large blank band below the composer"
        )
        attachScreenshot(named: "agent-first-production-shell", app: app)
    }

    func testAgentFirstChatRemainsUsableWhenDeviceRotationIsRequested() {
        let device = XCUIDevice.shared
        device.orientation = .portrait
        defer { device.orientation = .portrait }

        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let composerSurface = app.descendants(matching: .any)[Fixture.composerSurface]
        let send = app.buttons["chat.send"]

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        device.orientation = .landscapeLeft
        XCTAssertEqual(device.orientation, .landscapeLeft)
        XCTAssertTrue(composerSurface.waitForExistence(timeout: 3))
        XCTAssertTrue(send.waitForExistence(timeout: 3))
        XCTAssertGreaterThan(
            app.frame.height,
            app.frame.width,
            "The current iPhone companion policy is portrait-only even when device rotation is requested"
        )
        XCTAssertGreaterThan(transcript.frame.height, 80)
        assertContained(composerSurface, in: app)
        assertMinimumTapTarget(send)
        attachScreenshot(named: "agent-first-chat-rotation-request", app: app)
    }

    private func launchApp(
        baseArguments: [String] = ["--ui-testing-chat"],
        extraArguments: [String] = []
    ) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = baseArguments + extraArguments
        app.launch()
        return app
    }

    private func reveal(
        _ element: XCUIElement,
        in transcript: XCUIElement,
        attempts: Int = 8
    ) -> Bool {
        if element.waitForExistence(timeout: 1), element.frame.intersects(transcript.frame) {
            return true
        }
        for _ in 0..<attempts {
            transcript.swipeUp()
            if element.waitForExistence(timeout: 0.5), element.frame.intersects(transcript.frame) {
                return true
            }
        }
        return element.exists && element.frame.intersects(transcript.frame)
    }

    private func assertMinimumTapTarget(
        _ element: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertGreaterThanOrEqual(element.frame.width + 0.001, 44, file: file, line: line)
        XCTAssertGreaterThanOrEqual(element.frame.height + 0.001, 44, file: file, line: line)
    }

    private func assertContained(
        _ element: XCUIElement,
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertGreaterThanOrEqual(element.frame.minX, app.frame.minX, file: file, line: line)
        XCTAssertLessThanOrEqual(element.frame.maxX, app.frame.maxX, file: file, line: line)
        XCTAssertGreaterThanOrEqual(element.frame.minY, app.frame.minY, file: file, line: line)
        XCTAssertLessThanOrEqual(element.frame.maxY, app.frame.maxY, file: file, line: line)
    }

    private func attachScreenshot(named name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
