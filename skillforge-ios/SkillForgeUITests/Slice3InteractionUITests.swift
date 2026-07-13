import XCTest

@MainActor
final class Slice3InteractionUITests: XCTestCase {
    func testAnswersPendingCardsAndSendsAttachmentOnlyMessage() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-slice3"]
        app.launch()

        let askCard = app.staticTexts["选择发布环境"]
        let confirmationCard = app.staticTexts["批准执行部署？"]
        let option = app.buttons["pending.option.ask-fixture.测试环境"]
        let approve = app.buttons["pending.approve.confirmation-fixture"]
        let attachment = app.buttons["chat.attachment.remove.attachment-fixture"]
        let send = app.buttons["chat.send"]

        XCTAssertTrue(askCard.waitForExistence(timeout: 3))
        XCTAssertTrue(confirmationCard.waitForExistence(timeout: 3))
        XCTAssertTrue(attachment.waitForExistence(timeout: 3))

        let initialScreenshot = XCTAttachment(screenshot: app.screenshot())
        initialScreenshot.name = "slice3-pending-cards"
        initialScreenshot.lifetime = .keepAlways
        add(initialScreenshot)

        option.tap()
        XCTAssertTrue(waitForDisappearance(askCard))
        XCTAssertTrue(confirmationCard.exists)

        approve.tap()
        XCTAssertTrue(waitForDisappearance(confirmationCard))

        XCTAssertTrue(send.isEnabled)
        send.tap()
        XCTAssertTrue(waitForDisappearance(attachment))
        XCTAssertTrue(app.staticTexts["已发送附件："].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["待处理事项"].exists)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "slice3-pending-and-attachment"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testCustomAskAnswerAndConfirmationDenial() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-slice3"]
        app.launch()

        let askCard = app.staticTexts["选择发布环境"]
        let answer = app.textFields["pending.answer.ask-fixture"]
        let submit = app.buttons["pending.submit.ask-fixture"]
        let confirmationCard = app.staticTexts["批准执行部署？"]
        let deny = app.buttons["pending.deny.confirmation-fixture"]

        XCTAssertTrue(askCard.waitForExistence(timeout: 3))
        answer.tap()
        answer.typeText("灰度环境")
        XCTAssertTrue(submit.isEnabled)
        submit.tap()
        XCTAssertTrue(waitForDisappearance(askCard))

        XCTAssertTrue(confirmationCard.exists)
        deny.tap()
        XCTAssertTrue(waitForDisappearance(confirmationCard))
    }

    private func waitForDisappearance(_ element: XCUIElement) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: element
        )
        return XCTWaiter.wait(for: [expectation], timeout: 3) == .completed
    }
}
