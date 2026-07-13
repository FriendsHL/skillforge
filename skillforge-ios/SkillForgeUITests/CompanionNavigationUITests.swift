import XCTest

@MainActor
final class CompanionNavigationUITests: XCTestCase {
    private enum Fixture {
        static let launchArgument = "--ui-testing-tabs"
        static let tabIdentifiers = ["tab.chat", "tab.control", "tab.agents", "tab.settings"]
        static let scheduleRow = "control.schedule.7"
        static let sessionsRow = "control.sessions"
        static let routedSessionRow = "control.session.tabs-waiting"
        static let crossAgentSessionRow = "control.session.tabs-release-agent"
        static let routedRunRow = "control.run.71"
        static let routedTaskMessage = "chat.message.tabs-fixture-message"
        static let routedTaskMessageText = "The deterministic tab fixture keeps this transcript in memory."
        static let alternateAgentRow = "agents.row.2"
        static let alternateAgentName = "Release Agent"
        static let preservedMessageText = "The deterministic tab fixture keeps this transcript in memory."
    }

    func testPairedFixtureOpensChatWithExactlyFourTabs() {
        let app = launchApp()

        assertExactlyFourTabs(in: app)
        XCTAssertTrue(app.scrollViews["chat.transcript"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["tab.chat"].isSelected)
    }

    func testSwitchingAcrossTabsPreservesChatTranscriptAndComposerDraft() {
        let app = launchApp()
        let transcript = app.scrollViews["chat.transcript"]
        let composer = app.textFields["chat.composer"]
        let draft = "Keep this draft while navigating"

        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[Fixture.preservedMessageText].waitForExistence(timeout: 5))
        XCTAssertTrue(composer.waitForExistence(timeout: 5))
        composer.tap()
        composer.typeText(draft)
        XCTAssertEqual(composer.value as? String, draft)
        transcript.tap()
        XCTAssertTrue(waitForKeyboardToDisappear(in: app))

        tapTab("tab.control", in: app)
        XCTAssertTrue(app.descendants(matching: .any)[Fixture.scheduleRow].waitForExistence(timeout: 5))

        tapTab("tab.agents", in: app)
        XCTAssertTrue(app.descendants(matching: .any)[Fixture.alternateAgentRow].waitForExistence(timeout: 5))

        tapTab("tab.settings", in: app)
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))

        tapTab("tab.chat", in: app)
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertTrue(transcript.isHittable, "The retained Chat transcript must remain visible and interactive")
        XCTAssertTrue(app.staticTexts[Fixture.preservedMessageText].waitForExistence(timeout: 5))
        XCTAssertTrue(composer.waitForExistence(timeout: 5))
        XCTAssertEqual(composer.value as? String, draft)
    }

    func testControlSessionSelectionRoutesToExpectedChatSessionMessage() {
        let app = launchApp()

        XCTAssertTrue(app.staticTexts["Agent 运行中"].waitForExistence(timeout: 5))
        tapTab("tab.control", in: app)
        let sessions = app.descendants(matching: .any)[Fixture.sessionsRow]
        XCTAssertTrue(sessions.waitForExistence(timeout: 5))
        sessions.tap()
        let routedSession = app.descendants(matching: .any)[Fixture.routedSessionRow]
        XCTAssertTrue(routedSession.waitForExistence(timeout: 5))
        XCTAssertTrue(routedSession.isHittable)
        routedSession.tap()

        assertChatIsVisible(in: app)
        XCTAssertTrue(app.staticTexts["等待确认"].waitForExistence(timeout: 5))
        let routedMessage = app.descendants(matching: .any)[Fixture.routedTaskMessage]
        XCTAssertTrue(routedMessage.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[Fixture.routedTaskMessageText].waitForExistence(timeout: 5))
    }

    func testControlSessionForAnotherAgentRoutesToThatAgentChat() {
        let app = launchApp()

        tapTab("tab.control", in: app)
        let sessions = app.descendants(matching: .any)[Fixture.sessionsRow]
        XCTAssertTrue(sessions.waitForExistence(timeout: 5))
        sessions.tap()
        let routedSession = app.descendants(matching: .any)[Fixture.crossAgentSessionRow]
        XCTAssertTrue(routedSession.waitForExistence(timeout: 5))
        XCTAssertTrue(routedSession.isHittable)
        routedSession.tap()

        assertChatIsVisible(in: app)
        XCTAssertTrue(app.staticTexts[Fixture.alternateAgentName].waitForExistence(timeout: 5))
        XCTAssertEqual(app.scrollViews["chat.transcript"].value as? String, "tabs-release-agent")
    }

    func testScheduleRunRoutesToItsConcreteChatSession() {
        let app = launchApp()

        tapTab("tab.control", in: app)
        let schedule = app.descendants(matching: .any)[Fixture.scheduleRow]
        XCTAssertTrue(schedule.waitForExistence(timeout: 5))
        schedule.tap()
        let run = app.descendants(matching: .any)[Fixture.routedRunRow]
        scrollToElement(run, in: app)
        XCTAssertTrue(run.isHittable)
        run.tap()

        assertChatIsVisible(in: app)
        XCTAssertEqual(app.scrollViews["chat.transcript"].value as? String, "tabs-running")
    }

    func testScheduleCanRunNowAndPauseWithoutLeavingControl() {
        let app = launchApp()

        tapTab("tab.control", in: app)
        app.descendants(matching: .any)[Fixture.scheduleRow].tap()

        let runNow = app.buttons["control.schedule.run.7"]
        XCTAssertTrue(runNow.waitForExistence(timeout: 5))
        runNow.tap()
        XCTAssertTrue(app.staticTexts["Run queued"].waitForExistence(timeout: 5))

        let toggle = app.buttons["control.schedule.toggle.7"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.tap()
        XCTAssertTrue(app.staticTexts["Schedule paused"].waitForExistence(timeout: 5))

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "control-schedule-detail"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testAgentConfigurationDetailDoesNotMutateChatState() {
        let app = launchApp()

        let transcript = app.scrollViews["chat.transcript"]
        let composer = app.textFields["chat.composer"]
        let draft = "Keep this Agent inspection draft"
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertEqual(transcript.value as? String, "tabs-running")
        XCTAssertTrue(app.staticTexts["Main Assistant"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[Fixture.preservedMessageText].waitForExistence(timeout: 5))
        XCTAssertTrue(composer.waitForExistence(timeout: 5))
        composer.tap()
        composer.typeText(draft)
        transcript.tap()
        XCTAssertTrue(waitForKeyboardToDisappear(in: app))

        tapTab("tab.agents", in: app)
        let alternateAgent = app.descendants(matching: .any)[Fixture.alternateAgentRow]
        XCTAssertTrue(alternateAgent.waitForExistence(timeout: 5))
        XCTAssertTrue(alternateAgent.isHittable)
        alternateAgent.tap()

        let detail = app.descendants(matching: .any)["agents.detail.2"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5))
        XCTAssertFalse(app.tabBars.firstMatch.exists, "Agent detail must hide the floating tab bar")
        let model = app.descendants(matching: .any)["agents.detail.model"]
        XCTAssertTrue(model.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["gpt-5-codex"].waitForExistence(timeout: 5))

        let skills = app.descendants(matching: .any)["agents.detail.skills"]
        scrollToElement(skills, in: app)
        XCTAssertTrue(app.staticTexts["release-check, code-review, incident-triage"].waitForExistence(timeout: 5))

        let tools = app.descendants(matching: .any)["agents.detail.tools"]
        scrollToElement(tools, in: app)
        XCTAssertTrue(app.staticTexts["Allowlist (6)"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Read, Search, Shell, GitHub, Task, AskUser"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.scrollViews["chat.transcript"].exists, "Opening Agent detail must not route to Chat")

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "agents-configuration-detail"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        let backButton = app.navigationBars[Fixture.alternateAgentName].buttons["Agents"]
        XCTAssertTrue(backButton.waitForExistence(timeout: 5))
        backButton.tap()
        XCTAssertTrue(alternateAgent.waitForExistence(timeout: 5))

        tapTab("tab.chat", in: app)
        XCTAssertTrue(transcript.waitForExistence(timeout: 5))
        XCTAssertEqual(transcript.value as? String, "tabs-running")
        XCTAssertTrue(app.staticTexts["Main Assistant"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[Fixture.preservedMessageText].waitForExistence(timeout: 5))
        XCTAssertEqual(composer.value as? String, draft)
        assertExactlyFourTabs(in: app)
    }

    func testAgentsRosterAndDetailAtMaximumDynamicTypeRemainReadableAndNavigable() {
        let app = launchApp(extraArguments: [
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityExtraExtraExtraLarge"
        ])

        tapTab("tab.agents", in: app)
        let alternateAgent = app.descendants(matching: .any)[Fixture.alternateAgentRow]
        scrollToElement(alternateAgent, in: app)
        XCTAssertTrue(alternateAgent.label.contains(Fixture.alternateAgentName))
        XCTAssertTrue(alternateAgent.label.contains("gpt-5-codex"))
        XCTAssertTrue(alternateAgent.label.localizedCaseInsensitiveContains("active"))
        XCTAssertTrue(alternateAgent.isHittable)
        alternateAgent.tap()

        let detail = app.descendants(matching: .any)["agents.detail.2"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5))

        let statusLabel = app.staticTexts["Status"]
        slowlyScrollUntilHittable(statusLabel, in: app)
        XCTAssertTrue(statusLabel.isHittable)
        XCTAssertTrue(app.staticTexts["Active"].waitForExistence(timeout: 5))

        let visibilityLabel = app.staticTexts["Visibility"]
        slowlyScrollUntilHittable(visibilityLabel, in: app)
        XCTAssertTrue(visibilityLabel.isHittable)
        XCTAssertTrue(app.staticTexts["Private"].waitForExistence(timeout: 5))

        let backButton = app.navigationBars[Fixture.alternateAgentName].buttons["Agents"]
        XCTAssertTrue(backButton.waitForExistence(timeout: 5))
        XCTAssertTrue(backButton.isHittable)
        backButton.tap()
        XCTAssertTrue(alternateAgent.waitForExistence(timeout: 5))
    }

    func testConfirmedLocalDisconnectReachesPairingWithoutRevokeLabel() {
        let app = launchApp()

        tapTab("tab.settings", in: app)
        let disconnect = app.buttons["settings.disconnect"]
        scrollToElement(disconnect, in: app)
        XCTAssertTrue(disconnect.isHittable)
        XCTAssertTrue(disconnect.label.localizedCaseInsensitiveContains("disconnect"))
        XCTAssertFalse(disconnect.label.localizedCaseInsensitiveContains("revoke"))
        disconnect.tap()

        let confirmation = app.alerts.firstMatch
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertFalse(confirmation.label.localizedCaseInsensitiveContains("revoke"))

        let confirmDisconnect = confirmation.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] %@", "disconnect")
        ).firstMatch
        XCTAssertTrue(confirmDisconnect.waitForExistence(timeout: 3))
        XCTAssertFalse(confirmDisconnect.label.localizedCaseInsensitiveContains("revoke"))
        confirmDisconnect.tap()

        XCTAssertTrue(app.navigationBars["Pair SkillForge"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Connect to your SkillForge server"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.tabBars.firstMatch.exists)
    }

    private func launchApp(extraArguments: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = [Fixture.launchArgument] + extraArguments
        app.launch()
        return app
    }

    private func assertExactlyFourTabs(
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let tabBar = app.tabBars.firstMatch
        XCTAssertTrue(tabBar.waitForExistence(timeout: 5), file: file, line: line)
        XCTAssertEqual(tabBar.buttons.count, Fixture.tabIdentifiers.count, file: file, line: line)

        for identifier in Fixture.tabIdentifiers {
            let tab = app.buttons[identifier]
            XCTAssertTrue(tab.waitForExistence(timeout: 3), file: file, line: line)
        }
    }

    private func tapTab(
        _ identifier: String,
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let tab = app.buttons[identifier]
        XCTAssertTrue(tab.waitForExistence(timeout: 5), file: file, line: line)
        XCTAssertTrue(tab.isHittable, file: file, line: line)
        tab.tap()
        XCTAssertTrue(waitForSelection(of: tab), "Expected \(identifier) to become selected", file: file, line: line)
    }

    private func assertChatIsVisible(
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let chatTab = app.buttons["tab.chat"]
        XCTAssertTrue(waitForSelection(of: chatTab), file: file, line: line)
        XCTAssertTrue(app.scrollViews["chat.transcript"].waitForExistence(timeout: 5), file: file, line: line)
        XCTAssertTrue(app.textFields["chat.composer"].waitForExistence(timeout: 5), file: file, line: line)
    }

    private func waitForSelection(of element: XCUIElement) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "selected == true"),
            object: element
        )
        return XCTWaiter.wait(for: [expectation], timeout: 5) == .completed
    }

    private func waitForKeyboardToDisappear(in app: XCUIApplication) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: app.keyboards.firstMatch
        )
        return XCTWaiter.wait(for: [expectation], timeout: 5) == .completed
    }

    private func scrollToElement(
        _ element: XCUIElement,
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 5), file: file, line: line)
        for _ in 0..<4 where !element.isHittable {
            list.swipeUp()
        }
        XCTAssertTrue(element.waitForExistence(timeout: 3), file: file, line: line)
        XCTAssertTrue(element.isHittable, file: file, line: line)
    }

    private func slowlyScrollUntilHittable(
        _ element: XCUIElement,
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.waitForExistence(timeout: 5), file: file, line: line)
        for _ in 0..<10 where !element.isHittable {
            list.swipeUp(velocity: .slow)
        }
        XCTAssertTrue(element.waitForExistence(timeout: 3), file: file, line: line)
        XCTAssertTrue(element.isHittable, file: file, line: line)
    }
}

@MainActor
final class PairingReviewUITests: XCTestCase {
    func testReadyScreenPrioritizesScanAndKeepsPayloadEntryCollapsed() {
        let app = launchApp(mode: "--ui-testing-pairing-ready")

        let scan = app.buttons["pairing.scan"]
        let paste = app.buttons["pairing.paste.toggle"]
        XCTAssertTrue(scan.waitForExistence(timeout: 5))
        XCTAssertTrue(paste.waitForExistence(timeout: 5))
        XCTAssertFalse(app.textViews["pairing.payload.input"].exists)
        XCTAssertFalse(app.textFields.matching(
            NSPredicate(format: "placeholderValue CONTAINS[c] %@", "setup code")
        ).firstMatch.exists)
    }

    func testReviewShowsOnlySafeMetadataAndRequiresConfirmation() {
        let app = launchApp(mode: "--ui-testing-pairing-review")

        XCTAssertTrue(app.otherElements["pairing.review"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Pairing Review Server"].exists)
        XCTAssertTrue(app.staticTexts["https://review.example.com:8443"].exists)
        XCTAssertTrue(app.staticTexts["Valid for 5 minutes"].exists)
        XCTAssertTrue(app.buttons["pairing.confirm"].exists)
        XCTAssertFalse(app.textViews["pairing.payload.input"].exists)

        let accessibilityText = app.descendants(matching: .any).allElementsBoundByIndex
            .flatMap { [$0.label, $0.value as? String].compactMap { $0 } }
            .joined(separator: "\n")
        XCTAssertFalse(accessibilityText.localizedCaseInsensitiveContains("pairingSecret"))
        XCTAssertFalse(accessibilityText.localizedCaseInsensitiveContains("pairingId"))
        XCTAssertFalse(accessibilityText.contains("skillforge.mobile_pairing"))

        app.buttons["pairing.confirm"].tap()
        XCTAssertTrue(app.staticTexts["Pairing confirmation received"].waitForExistence(timeout: 5))
    }

    func testPasteEntryRemainsUsableWithKeyboardOnSmallScreen() {
        let app = launchApp(
            mode: "--ui-testing-pairing-ready",
            extraArguments: [
                "-UIPreferredContentSizeCategoryName",
                "UICTContentSizeCategoryAccessibilityExtraExtraExtraLarge"
            ]
        )

        let paste = app.buttons["pairing.paste.toggle"]
        XCTAssertTrue(paste.waitForExistence(timeout: 5))
        paste.tap()

        let input = app.textViews["pairing.payload.input"]
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 3))

        let review = app.buttons["pairing.payload.review"]
        app.swipeUp()
        XCTAssertTrue(review.waitForExistence(timeout: 5))
        XCTAssertTrue(review.isHittable)
    }

    private func launchApp(mode: String, extraArguments: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = [mode] + extraArguments
        app.launch()
        return app
    }
}
