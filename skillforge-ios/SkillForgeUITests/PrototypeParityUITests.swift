import XCTest

@MainActor
final class PrototypeParityUITests: XCTestCase {
    func testCapturesCurrentCompanionShellForPrototypeParity() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-tabs"]
        app.launch()

        assertAndCapture(
            tab: "tab.chat",
            visibleElement: app.scrollViews["chat.transcript"],
            screenshotName: "parity-current-chat",
            in: app
        )
        assertAndCapture(
            tab: "tab.control",
            visibleElement: app.descendants(matching: .any)["control.schedule.7"],
            screenshotName: "parity-current-control",
            in: app
        )
        assertAndCapture(
            tab: "tab.agents",
            visibleElement: app.descendants(matching: .any)["agents.row.2"],
            screenshotName: "parity-current-agents",
            in: app
        )
        assertAndCapture(
            tab: "tab.settings",
            visibleElement: app.navigationBars["Settings"],
            screenshotName: "parity-current-settings",
            in: app
        )
    }

    private func assertAndCapture(
        tab identifier: String,
        visibleElement: XCUIElement,
        screenshotName: String,
        in app: XCUIApplication
    ) {
        let tab = app.buttons[identifier]
        XCTAssertTrue(tab.waitForExistence(timeout: 5), "Missing \(identifier)")
        XCTAssertTrue(tab.isHittable, "\(identifier) must be tappable")
        tab.tap()
        XCTAssertTrue(visibleElement.waitForExistence(timeout: 5), "Missing current surface for \(identifier)")

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = screenshotName
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
