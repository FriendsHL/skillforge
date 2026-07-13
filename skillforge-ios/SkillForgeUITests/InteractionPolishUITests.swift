import XCTest

@MainActor
final class InteractionPolishUITests: XCTestCase {
  private enum Fixture {
    static let preservedMessage = "The deterministic tab fixture keeps this transcript in memory."
    static let releaseAgentName = "Release Agent"
  }

  func testChatNewConversationCreatesSessionForSelectedAgentAndKeepsChatUsable() {
    let app = launchApp()
    let transcript = app.scrollViews["chat.transcript"]
    let initialSessionID = transcript.value as? String

    let newConversation = app.buttons["chat.newConversation"]
    XCTAssertTrue(newConversation.waitForExistence(timeout: 5))
    XCTAssertTrue(newConversation.isHittable)
    XCTAssertFalse(app.buttons["settings.disconnect"].exists)
    newConversation.tap()

    selectReleaseAgentAndCreate(in: app)

    XCTAssertTrue(app.staticTexts[Fixture.releaseAgentName].waitForExistence(timeout: 5))
    XCTAssertTrue(transcript.waitForExistence(timeout: 5))
    XCTAssertNotEqual(transcript.value as? String, initialSessionID)

    let composer = app.textFields["chat.composer"]
    XCTAssertTrue(composer.waitForExistence(timeout: 5))
    XCTAssertTrue(composer.isHittable)
    composer.tap()
    composer.typeText("Continue the release review")
    XCTAssertEqual(composer.value as? String, "Continue the release review")
  }

  func testSessionListNewConversationUsesSameAgentPicker() {
    let app = launchApp()

    openSessionList(in: app)
    let openNewConversation = app.buttons["sessions.openNewConversation"]
    XCTAssertTrue(openNewConversation.waitForExistence(timeout: 5))
    XCTAssertTrue(openNewConversation.isHittable)
    XCTAssertFalse(app.buttons["settings.disconnect"].exists)
    openNewConversation.tap()

    XCTAssertTrue(app.buttons["newConversation.agent.1"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["newConversation.agent.2"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["newConversation.create"].exists)
    XCTAssertTrue(app.buttons["newConversation.cancel"].exists)

    selectReleaseAgentAndCreate(in: app)
    XCTAssertTrue(app.staticTexts[Fixture.releaseAgentName].waitForExistence(timeout: 5))
    XCTAssertTrue(app.scrollViews["chat.transcript"].waitForExistence(timeout: 5))
  }

  func testCancellingNewConversationPreservesTranscriptAndComposerDraft() {
    let app = launchApp()
    let transcript = app.scrollViews["chat.transcript"]
    let composer = app.textFields["chat.composer"]
    let draft = "Keep this draft when creation is cancelled"
    let originalSessionID = transcript.value as? String

    XCTAssertTrue(app.staticTexts[Fixture.preservedMessage].waitForExistence(timeout: 5))
    composer.tap()
    composer.typeText(draft)
    app.buttons["chat.newConversation"].tap()

    let releaseAgent = app.buttons["newConversation.agent.2"]
    XCTAssertTrue(releaseAgent.waitForExistence(timeout: 5))
    releaseAgent.tap()
    app.buttons["newConversation.cancel"].tap()

    XCTAssertTrue(transcript.waitForExistence(timeout: 5))
    XCTAssertEqual(transcript.value as? String, originalSessionID)
    XCTAssertTrue(app.staticTexts[Fixture.preservedMessage].exists)
    XCTAssertEqual(composer.value as? String, draft)
    XCTAssertTrue(app.staticTexts["Main Assistant"].exists)
  }

  func testSessionSearchAndStatusFilterAreAccessibleAndComposable() {
    let app = launchApp()
    openSessionList(in: app)

    let search = app.searchFields["sessions.search"]
    let filter = app.descendants(matching: .any)["sessions.filter"]
    XCTAssertTrue(search.waitForExistence(timeout: 5))
    XCTAssertTrue(filter.waitForExistence(timeout: 5))
    XCTAssertFalse(search.label.isEmpty)
    XCTAssertFalse(filter.label.isEmpty)

    let selectedSession = app.buttons["sessions.session.tabs-running"]
    XCTAssertTrue(selectedSession.waitForExistence(timeout: 3))
    XCTAssertEqual(selectedSession.value as? String, "Selected")
    app.collectionViews.firstMatch.swipeDown()
    XCTAssertTrue(selectedSession.waitForExistence(timeout: 3))
    XCTAssertEqual(selectedSession.value as? String, "Selected")

    search.tap()
    search.typeText("Review")
    XCTAssertTrue(app.staticTexts["Review deployment"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.staticTexts["Prepare release"].waitForNonExistence(timeout: 3))

    let waitingFilter = app.buttons["Waiting"]
    XCTAssertTrue(waitingFilter.waitForExistence(timeout: 3))
    waitingFilter.tap()
    XCTAssertTrue(app.staticTexts["Review deployment"].waitForExistence(timeout: 3))
    XCTAssertTrue(waitForSelection(of: waitingFilter))
  }

  func testSettingsExposeNotificationsAppearancePrivacyAndPersistAppearanceAcrossTabs() {
    let app = launchApp()
    tapTab("tab.settings", in: app)

    let notifications = app.staticTexts["Notifications"]
    scrollToElement(notifications, in: app)
    let enableNotifications = app.buttons["settings.notifications.enable"]
    XCTAssertTrue(enableNotifications.waitForExistence(timeout: 5))

    let appearance = app.descendants(matching: .any)["settings.appearance"]
    scrollToElement(appearance, in: app)
    let light = app.buttons["Light"]
    XCTAssertTrue(light.waitForExistence(timeout: 3))
    light.tap()
    XCTAssertTrue(waitForSelection(of: light))

    let privacy = app.staticTexts["Permissions & Privacy"]
    scrollToElement(privacy, in: app)

    tapTab("tab.chat", in: app)
    XCTAssertTrue(app.scrollViews["chat.transcript"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["settings.disconnect"].exists)
    tapTab("tab.settings", in: app)

    let persistedAppearance = app.descendants(matching: .any)["settings.appearance"]
    scrollToElement(persistedAppearance, in: app)
    XCTAssertTrue(waitForSelection(of: app.buttons["Light"]))
    let disconnect = app.buttons["settings.disconnect"]
    scrollToElement(disconnect, in: app)
    XCTAssertTrue(disconnect.exists)

    app.terminate()
    app.launch()
    tapTab("tab.settings", in: app)
    let relaunchedAppearance = app.descendants(matching: .any)["settings.appearance"]
    scrollToElement(relaunchedAppearance, in: app)
    XCTAssertTrue(waitForSelection(of: app.buttons["Light"]))
  }

  private func launchApp() -> XCUIApplication {
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = ["--ui-testing-tabs"]
    app.launch()
    return app
  }

  private func selectReleaseAgentAndCreate(in app: XCUIApplication) {
    let releaseAgent = app.buttons["newConversation.agent.2"]
    XCTAssertTrue(releaseAgent.waitForExistence(timeout: 5))
    XCTAssertTrue(releaseAgent.isHittable)
    releaseAgent.tap()

    let create = app.buttons["newConversation.create"]
    XCTAssertTrue(create.waitForExistence(timeout: 5))
    XCTAssertTrue(create.isEnabled)
    create.tap()
  }

  private func openSessionList(in app: XCUIApplication) {
    let sessions = app.buttons.matching(
      NSPredicate(format: "label == %@", "Sessions")
    ).firstMatch
    XCTAssertTrue(sessions.waitForExistence(timeout: 5))
    XCTAssertTrue(sessions.isHittable)
    sessions.tap()
    XCTAssertTrue(app.navigationBars["Sessions"].waitForExistence(timeout: 5))
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
    let selected = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "selected == true"),
      object: tab
    )
    XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 5), .completed, file: file, line: line)
  }

  private func scrollToElement(
    _ element: XCUIElement,
    in app: XCUIApplication,
    file: StaticString = #filePath,
    line: UInt = #line
  ) {
    let list = app.collectionViews.firstMatch
    XCTAssertTrue(list.waitForExistence(timeout: 5), file: file, line: line)
    for _ in 0..<8 where !element.isHittable {
      list.swipeUp()
    }
    XCTAssertTrue(element.waitForExistence(timeout: 3), file: file, line: line)
    XCTAssertTrue(element.isHittable, file: file, line: line)
  }

  private func waitForSelection(of element: XCUIElement) -> Bool {
    let selected = XCTNSPredicateExpectation(
      predicate: NSPredicate(format: "selected == true"),
      object: element
    )
    return XCTWaiter.wait(for: [selected], timeout: 3) == .completed
  }
}
