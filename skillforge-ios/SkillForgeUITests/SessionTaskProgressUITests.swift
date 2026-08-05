import XCTest

final class SessionTaskProgressUITests: XCTestCase {
    @MainActor
    func testTaskProgressIsCompactAndExpandsWithoutBlockingChat() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-chat", "--ui-testing-session-tasks"]
        app.launch()

        let toggle = app.buttons["chat.taskProgress.toggle"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["chat.send"].exists)
        let current = app.descendants(matching: .any)["chat.taskProgress.task.fixture-task-current"]
        XCTAssertFalse(current.exists)

        toggle.tap()

        XCTAssertTrue(current.waitForExistence(timeout: 3))
        XCTAssertTrue(app.descendants(matching: .any)["chat.taskProgress.task.fixture-task-blocked"].exists)
        toggle.tap()
        XCTAssertFalse(current.exists)
    }
}
