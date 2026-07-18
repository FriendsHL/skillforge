import XCTest

@MainActor
final class LaunchExperienceUITests: XCTestCase {
    func testRestoringWorkspaceHasAnImmediateBrandedFirstFrame() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-launch-loading"]
        app.launch()

        let surface = app.descendants(matching: .any)["app.launch"]
        XCTAssertTrue(surface.waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["SkillForge"].exists)
        XCTAssertTrue(app.staticTexts["Restoring your workspace"].exists)
        XCTAssertTrue(app.activityIndicators["app.launch.progress"].exists)
        XCTAssertLessThanOrEqual(surface.frame.minY, 1)
        XCTAssertGreaterThanOrEqual(surface.frame.maxY, app.frame.maxY - 1)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "launch-restoring-workspace"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
