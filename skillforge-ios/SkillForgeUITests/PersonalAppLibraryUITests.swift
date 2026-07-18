import XCTest

@MainActor
final class PersonalAppLibraryUITests: XCTestCase {
    private let firstCard = "personalApps.card.library-app-1"

    func testInitialLibraryLoadUsesCardSkeletonInsteadOfBlankContent() {
        let app = launch(extraArguments: ["--personal-app-library-hold-loading"])
        openLibrary(in: app)

        XCTAssertTrue(app.descendants(matching: .any)["personalApps.loading"].waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.descendants(matching: .any)["personalApps.loading.skeleton.1"].waitForExistence(timeout: 5)
        )
        attachScreenshot(named: "personal-app-library-loading", app: app)
    }

    func testWorkspaceNavigationSearchScopeFilterAndFavorite() {
        let app = launch()
        openLibrary(in: app)

        let initialCard = app.descendants(matching: .any)[firstCard]
        XCTAssertTrue(initialCard.waitForExistence(timeout: 5))
        let compactSummary = app.staticTexts["personalApps.summary.library-app-1"]
        XCTAssertTrue(compactSummary.waitForExistence(timeout: 5))
        XCTAssertLessThanOrEqual(compactSummary.label.count, 96)
        XCTAssertTrue(compactSummary.label.hasSuffix("…"))
        XCTAssertLessThan(
            initialCard.frame.height,
            300,
            "A normal Personal App card should not consume most of the Library viewport"
        )
        XCTAssertGreaterThanOrEqual(
            app.buttons["personalApps.open.library-app-1"].frame.width,
            80,
            "The compact primary action must keep its visible Open label"
        )
        XCTAssertGreaterThan(
            app.textFields["personalApps.search"].frame.minY,
            50,
            "The Personal Apps header must remain below the iPhone status-bar safe area"
        )
        XCTAssertTrue(app.descendants(matching: .any)["personalApps.generated.library-app-1"].label.contains("Generated"))
        XCTAssertTrue(app.descendants(matching: .any)["personalApps.lastOpened.library-app-1"].label.contains("Last opened"))
        XCTAssertEqual(
            app.descendants(matching: .any)["personalApps.offlineState.library-app-1"].label,
            "Offline ready"
        )
        XCTAssertEqual(
            app.descendants(matching: .any)["personalApps.permissionState.library-app-1"].label,
            "No permissions"
        )
        let search = app.textFields["personalApps.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("Release Readiness")
        search.typeText("\n")
        XCTAssertTrue(app.descendants(matching: .any)["personalApps.card.library-app-2"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.descendants(matching: .any)[firstCard].exists)

        app.buttons["personalApps.search.clear"].tap()
        XCTAssertTrue(app.descendants(matching: .any)[firstCard].waitForExistence(timeout: 5))
        app.buttons["personalApps.filter.agent"].tap()
        XCTAssertTrue(app.buttons["Archive Agent"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Research Agent"].waitForExistence(timeout: 3))
        app.buttons["Research Agent"].tap()
        app.buttons["personalApps.filter.session"].tap()
        XCTAssertTrue(app.buttons["Archived launch"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.buttons["Daily AI research"].waitForExistence(timeout: 3))
        app.buttons["Daily AI research"].tap()

        app.buttons["personalApps.favorite.library-app-1"].tap()
        app.buttons["personalApps.scope.favorite"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["personalApps.empty"].waitForExistence(timeout: 5))
        app.buttons["personalApps.scope.recent"].tap()
        XCTAssertTrue(app.descendants(matching: .any)[firstCard].waitForExistence(timeout: 5))
        app.buttons["personalApps.favorite.library-app-1"].tap()
        app.buttons["personalApps.scope.favorite"].tap()
        XCTAssertTrue(app.descendants(matching: .any)[firstCard].waitForExistence(timeout: 5))

        app.buttons["personalApps.scope.downloaded"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["personalApps.card.library-app-2"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.descendants(matching: .any)["personalApps.card.library-app-3"].exists)

        attachScreenshot(named: "personal-app-library-normal", app: app)
    }

    func testOpenDismissPreservesListPositionAndSourceJumpsToRemoteMessage() {
        let app = launch()
        openLibrary(in: app)

        let targetCard = app.descendants(matching: .any)["personalApps.card.library-app-6"]
        scrollToElement(targetCard, in: app)
        XCTAssertTrue(targetCard.isHittable)
        app.buttons["personalApps.open.library-app-6"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["personalApp.viewer.library-app-6"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()

        XCTAssertTrue(targetCard.waitForExistence(timeout: 5))
        XCTAssertTrue(targetCard.isHittable, "Dismissing the viewer must preserve the Library scroll position")
        app.buttons["personalApps.source.library-app-6"].tap()
        XCTAssertTrue(app.scrollViews["chat.transcript"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["chat.message.remote-42"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Source message for AI Brief"].exists)
    }

    func testLibraryViewerSubmitsStateThroughValidatedBridge() {
        let app = launch()
        openLibrary(in: app)

        let open = app.buttons["personalApps.open.library-app-1"]
        XCTAssertTrue(open.waitForExistence(timeout: 5))
        open.tap()
        XCTAssertTrue(app.descendants(matching: .any)["personalApp.viewer.library-app-1"]
            .waitForExistence(timeout: 5))

        let submit = app.webViews.buttons["Submit selection"]
        XCTAssertTrue(submit.waitForExistence(timeout: 5))
        submit.tap()
        let confirmation = app.alerts["Send this state to Agent?"]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.staticTexts.matching(NSPredicate(
            format: "label CONTAINS %@", "reviewed"
        )).firstMatch.exists)
        confirmation.buttons["Send"].tap()

        XCTAssertTrue(app.descendants(matching: .any)["personalApps.notice"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[
            "Submitted the current Personal App state to its source conversation."
        ].exists)
    }

    func testShareMakesPersonalAppImmediatelyAvailableInDownloadedScope() {
        let app = launch()
        openLibrary(in: app)

        let search = app.textFields["personalApps.search"]
        search.tap()
        search.typeText("Model Cost Explorer")
        search.typeText("\n")
        let card = app.descendants(matching: .any)["personalApps.card.library-app-3"]
        XCTAssertTrue(card.waitForExistence(timeout: 5))

        let share = app.buttons["personalApps.share.library-app-3"]
        scrollToElement(share, in: app)
        share.tap()
        XCTAssertTrue(app.otherElements["ActivityListView"].waitForExistence(timeout: 5))
        let close = app.buttons["header.closeButton"]
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        close.tap()

        app.buttons["personalApps.scope.downloaded"].tap()
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        XCTAssertEqual(
            app.descendants(matching: .any)["personalApps.offlineState.library-app-3"].label,
            "Offline ready"
        )
    }

    func testOfflineIndexAndUnavailableUnsupportedStatesAreExplicit() {
        let offlineApp = launch(extraArguments: ["--personal-app-library-offline"])
        openLibrary(in: offlineApp)
        XCTAssertTrue(offlineApp.descendants(matching: .any)["personalApps.offline"].waitForExistence(timeout: 5))
        XCTAssertTrue(offlineApp.descendants(matching: .any)[firstCard].waitForExistence(timeout: 5))
        XCTAssertTrue(offlineApp.descendants(matching: .any)["personalApps.card.library-app-2"].exists)
        attachScreenshot(named: "personal-app-library-offline", app: offlineApp)
        offlineApp.terminate()

        let app = launch()
        openLibrary(in: app)
        let unsupported = app.descendants(matching: .any)["personalApps.card.library-app-8"]
        scrollToElement(unsupported, in: app)
        XCTAssertTrue(app.descendants(matching: .any)["personalApps.unsupported.library-app-8"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.descendants(matching: .any)["personalApps.permissionState.library-app-8"].label.contains("camera"))
        XCTAssertFalse(app.buttons["personalApps.open.library-app-8"].isEnabled)

        let search = app.textFields["personalApps.search"]
        search.tap()
        search.typeText("Unavailable Legacy App")
        search.typeText("\n")
        let unavailable = app.descendants(matching: .any)["personalApps.card.library-app-9"]
        XCTAssertTrue(unavailable.waitForExistence(timeout: 5))
        XCTAssertTrue(unavailable.isHittable)
        XCTAssertFalse(app.buttons["personalApps.open.library-app-9"].isEnabled)
        XCTAssertEqual(app.buttons["personalApps.open.library-app-9"].label, "Unavailable")
        attachScreenshot(named: "personal-app-library-unavailable", app: app)
    }

    func testAccessibilityXXXLAndDarkModeKeepActionsReadableAndHittable() {
        let app = launch(extraArguments: [
            "--ui-testing-accessibility-xxxl",
            "--personal-app-library-dark"
        ])
        openLibrary(in: app)

        let card = app.descendants(matching: .any)[firstCard]
        XCTAssertTrue(card.waitForExistence(timeout: 5))
        for identifier in [
            "personalApps.open.library-app-1",
            "personalApps.source.library-app-1",
            "personalApps.share.library-app-1",
            "personalApps.clear.library-app-1"
        ] {
            let action = app.buttons[identifier]
            scrollToElement(action, in: app)
            XCTAssertTrue(action.isHittable, "\(identifier) must remain reachable at accessibility XXXL")
        }

        attachScreenshot(named: "personal-app-library-accessibility-dark", app: app)
    }

    private func launch(extraArguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-personal-app-library"] + extraArguments
        app.launch()
        return app
    }

    private func openLibrary(in app: XCUIApplication) {
        let control = app.buttons["tab.control"]
        XCTAssertTrue(control.waitForExistence(timeout: 5))
        control.tap()
        let workspace = app.descendants(matching: .any)["control.workspace"]
        for _ in 0..<6 where !workspace.exists {
            app.swipeUp()
        }
        XCTAssertTrue(workspace.waitForExistence(timeout: 5))
        workspace.tap()
        XCTAssertTrue(app.descendants(matching: .any)["workspace.screen"].waitForExistence(timeout: 5))
        let personalApps = app.descendants(matching: .any)["workspace.personalApps"]
        XCTAssertTrue(personalApps.waitForExistence(timeout: 5))
        personalApps.tap()
        XCTAssertTrue(app.navigationBars["Personal Apps"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["personalApps.search"].waitForExistence(timeout: 5))
    }

    private func scrollToElement(_ element: XCUIElement, in app: XCUIApplication) {
        let list = app.scrollViews["personalApps.list"]
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        for _ in 0..<12 where !element.isHittable {
            list.swipeUp()
        }
        XCTAssertTrue(element.waitForExistence(timeout: 5))
    }

    private func attachScreenshot(named name: String, app: XCUIApplication) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
