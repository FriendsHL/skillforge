import XCTest

@MainActor
final class RuntimeRecoveryUITests: XCTestCase {
    func testRetryableRuntimeErrorIsRedAndRetryRecoversWithoutDuplicatingUserMessage() {
        let app = launchApp()
        let status = app.descendants(matching: .any)["chat.runtimeStatus"]
        let detail = app.descendants(matching: .any)["chat.runtimeError"]
        let retry = app.buttons["chat.runtimeRetry"]

        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertEqual(status.value as? String, "error")
        XCTAssertEqual(status.label, "模型连接异常")
        XCTAssertTrue(detail.waitForExistence(timeout: 5))
        XCTAssertEqual(detail.label, "模型服务在响应前超时\n未检测到副作用")
        XCTAssertTrue(retry.waitForExistence(timeout: 5))
        XCTAssertTrue(retry.isHittable)
        XCTAssertGreaterThanOrEqual(retry.frame.height, 44)
        attachScreenshot(named: "runtime-error-retryable", app: app)

        retry.tap()

        let progress = app.descendants(matching: .any)["chat.runtimeRetry.progress"]
        XCTAssertTrue(progress.waitForExistence(timeout: 2))
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "chat.message.runtime-error-user").count, 1)

        XCTAssertTrue(waitForValue("connected", element: status, timeout: 5))
        XCTAssertFalse(retry.exists)
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "chat.message.runtime-error-user").count, 1)
        XCTAssertTrue(app.descendants(matching: .any)["chat.message.runtime-retry-success"].waitForExistence(timeout: 3))
        attachScreenshot(named: "runtime-retry-recovered", app: app)
    }

    func testNonRetryableRuntimeErrorDoesNotExposeRetry() {
        let app = launchApp(extraArguments: ["--runtime-failure-tool"])
        let status = app.descendants(matching: .any)["chat.runtimeStatus"]
        let detail = app.descendants(matching: .any)["chat.runtimeError"]

        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertEqual(status.value as? String, "error")
        XCTAssertEqual(status.label, "工具执行失败")
        XCTAssertTrue(detail.waitForExistence(timeout: 5))
        XCTAssertEqual(detail.label, "工具参数校验失败\n可能已产生副作用")
        XCTAssertFalse(app.buttons["chat.runtimeRetry"].exists)
    }

    func testStructuredFailureMatrixShowsResponsibilityAndSideEffectsWithoutUnsafeRetry() {
        let cases: [(
            arguments: [String],
            title: String,
            detail: String,
            retryVisible: Bool
        )] = [
            (["--runtime-failure-model-provider"], "模型服务异常", "模型服务拒绝了请求\n未检测到副作用", false),
            ([], "模型连接异常", "模型服务在响应前超时\n未检测到副作用", true),
            (["--runtime-failure-tool"], "工具执行失败", "工具参数校验失败\n可能已产生副作用", false),
            (["--runtime-failure-harness"], "Agent Runtime 异常", "Agent Runtime 状态机异常\n已确认产生副作用", false),
            (["--runtime-failure-user-action"], "用户操作未完成", "用户确认已超时\n未检测到副作用", false),
            (["--runtime-failure-unknown"], "未知运行异常", "运行时返回了未分类错误\n副作用状态未知", false)
        ]

        for fixture in cases {
            let app = launchApp(extraArguments: fixture.arguments)
            let status = app.descendants(matching: .any)["chat.runtimeStatus"]
            let detail = app.descendants(matching: .any)["chat.runtimeError"]
            let retry = app.buttons["chat.runtimeRetry"]

            XCTAssertTrue(status.waitForExistence(timeout: 5), "arguments=\(fixture.arguments)")
            XCTAssertEqual(status.value as? String, "error")
            XCTAssertEqual(status.label, fixture.title)
            XCTAssertTrue(detail.waitForExistence(timeout: 5))
            XCTAssertEqual(detail.label, fixture.detail)
            if fixture.retryVisible {
                XCTAssertTrue(retry.waitForExistence(timeout: 2))
            } else {
                XCTAssertFalse(retry.exists)
            }
            app.terminate()
        }
    }

    func testLegacyFailureWithOldRetryFlagFailsClosed() {
        let app = launchApp(extraArguments: ["--runtime-failure-legacy"])
        let status = app.descendants(matching: .any)["chat.runtimeStatus"]
        let detail = app.descendants(matching: .any)["chat.runtimeError"]

        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertEqual(status.label, "未知运行异常")
        XCTAssertEqual(detail.label, "旧版服务返回了通用错误\n副作用状态未知")
        XCTAssertFalse(app.buttons["chat.runtimeRetry"].exists)
    }

    func testBackendIdleCancelledStepShowsCancelledWithoutRetry() {
        let app = launchApp(extraArguments: ["--runtime-cancelled"])
        let status = app.descendants(matching: .any)["chat.runtimeStatus"]

        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertEqual(status.value as? String, "cancelled")
        XCTAssertEqual(status.label, "已取消")
        XCTAssertFalse(app.buttons["chat.runtimeRetry"].exists)
        attachScreenshot(named: "runtime-cancelled", app: app)
    }

    func testRuntimeErrorBannerAdaptsAtAccessibilityXXXLWithoutTruncatingCoreState() {
        let app = launchApp(extraArguments: [
            "--ui-testing-accessibility-xxxl"
        ])
        let status = app.descendants(matching: .any)["chat.runtimeStatus"]
        let detail = app.descendants(matching: .any)["chat.runtimeError"]
        let retry = app.buttons["chat.runtimeRetry"]
        let endpoint = app.descendants(matching: .any)["chat.runtimeEndpoint"]

        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertTrue(detail.waitForExistence(timeout: 5))
        XCTAssertTrue(retry.waitForExistence(timeout: 5))
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        attachScreenshot(named: "runtime-error-accessibility-xxxl", app: app)

        XCTAssertEqual(status.label, "模型连接异常", "Runtime detail must be announced separately exactly once")
        XCTAssertEqual(detail.label, "模型服务在响应前超时\n未检测到副作用")
        XCTAssertFalse(status.frame.isEmpty)
        XCTAssertTrue(status.frame.intersects(app.frame))
        XCTAssertGreaterThanOrEqual(detail.frame.width, 200)
        XCTAssertGreaterThanOrEqual(retry.frame.width, 200)
        XCTAssertGreaterThanOrEqual(retry.frame.height, 44)
        XCTAssertTrue(retry.isHittable)
        XCTAssertEqual(endpoint.label, "127.0.0.1")
        XCTAssertFalse(endpoint.frame.isEmpty)
        XCTAssertTrue(endpoint.frame.intersects(app.frame))
    }

    private func launchApp(extraArguments: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing-runtime-error"] + extraArguments
        app.launch()
        return app
    }

    private func waitForValue(_ value: String, element: XCUIElement, timeout: TimeInterval) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", value),
            object: element
        )
        return XCTWaiter.wait(for: [expectation], timeout: timeout) == .completed
    }

    private func attachScreenshot(named name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
