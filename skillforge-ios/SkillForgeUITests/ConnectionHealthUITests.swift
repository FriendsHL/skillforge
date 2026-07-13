import XCTest

@MainActor
final class ConnectionHealthUITests: XCTestCase {
  func testSettingsConnectionSummaryOpensStructuredDiagnosticsWithoutRawErrors() {
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = ["--ui-testing-tabs"]
    app.launch()

    tapSettingsTab(in: app)

    let summary = app.buttons["settings.connection.summary"]
    XCTAssertTrue(summary.waitForExistence(timeout: 5))
    XCTAssertTrue(summary.isHittable)
    XCTAssertFalse(summary.label.isEmpty)
    XCTAssertTrue(summary.label.localizedCaseInsensitiveContains("running normally"))
    attachScreenshot(named: "connection-health-summary", app: app)
    summary.tap()

    XCTAssertTrue(app.navigationBars["Connection Diagnostics"].waitForExistence(timeout: 5))

    assertAccessibleDetailRow("settings.connection.endpoint", in: app)
    assertAccessibleDetailRow("settings.connection.service", in: app)
    assertAccessibleDetailRow("settings.connection.authorization", in: app)
    assertAccessibleDetailRow("settings.connection.realtime", in: app)
    assertAccessibleDetailRow("settings.connection.lastCheck", in: app)

    let checkAgain = app.buttons["settings.connection.checkAgain"]
    XCTAssertTrue(checkAgain.waitForExistence(timeout: 5))
    XCTAssertTrue(checkAgain.isHittable)
    XCTAssertFalse(checkAgain.label.isEmpty)
    attachScreenshot(named: "connection-health-detail", app: app)

    let visibleText = app.staticTexts.allElementsBoundByIndex
      .map(\.label)
      .joined(separator: "\n")
    XCTAssertFalse(visibleText.contains("Internal Server Error"))
    XCTAssertFalse(visibleText.contains("\"status\":500"))
    XCTAssertFalse(visibleText.contains("secret-stack"))
  }

  func testOfflineConnectionKeepsPairingAndOffersRetryWithoutRawErrors() {
    continueAfterFailure = false
    let app = XCUIApplication()
    app.launchArguments = ["--ui-testing-tabs", "--connection-health-offline"]
    app.launch()

    tapSettingsTab(in: app)

    let summary = app.buttons["settings.connection.summary"]
    XCTAssertTrue(summary.waitForExistence(timeout: 5))
    XCTAssertTrue(summary.label.localizedCaseInsensitiveContains("cannot reach server"))
    XCTAssertFalse(app.staticTexts["Pair SkillForge"].exists)
    summary.tap()

    let service = app.descendants(matching: .any)["settings.connection.service"]
    let authorization = app.descendants(matching: .any)["settings.connection.authorization"]
    XCTAssertTrue(service.waitForExistence(timeout: 5))
    XCTAssertEqual(service.value as? String, "Not verified")
    XCTAssertEqual(authorization.value as? String, "Not verified")
    attachScreenshot(named: "connection-health-offline", app: app)

    let checkAgain = app.buttons["settings.connection.checkAgain"]
    XCTAssertTrue(checkAgain.waitForExistence(timeout: 5))
    checkAgain.tap()
    XCTAssertTrue(checkAgain.waitForExistence(timeout: 5))
    XCTAssertFalse(app.staticTexts["Internal Server Error"].exists)
    XCTAssertFalse(app.staticTexts["secret-stack"].exists)
  }

  private func tapSettingsTab(in app: XCUIApplication) {
    let settings = app.buttons["tab.settings"]
    XCTAssertTrue(settings.waitForExistence(timeout: 5))
    XCTAssertTrue(settings.isHittable)
    settings.tap()
    XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
  }

  private func attachScreenshot(named name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }

  private func assertAccessibleDetailRow(
    _ identifier: String,
    in app: XCUIApplication,
    file: StaticString = #filePath,
    line: UInt = #line
  ) {
    let row = app.descendants(matching: .any)[identifier]
    XCTAssertTrue(row.waitForExistence(timeout: 5), "Missing \(identifier)", file: file, line: line)
    XCTAssertFalse(
      row.label.isEmpty,
      "\(identifier) needs a VoiceOver label",
      file: file,
      line: line
    )
    XCTAssertFalse(
      row.value as? String == nil,
      "\(identifier) needs a VoiceOver value",
      file: file,
      line: line
    )
  }
}
