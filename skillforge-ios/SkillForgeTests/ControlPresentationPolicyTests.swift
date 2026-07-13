import XCTest
@testable import SkillForge

final class ControlPresentationPolicyTests: XCTestCase {
    func testOrdersEnabledSchedulesByNextRunBeforePausedSchedules() {
        let schedules = [
            schedule(id: 1, enabled: false, nextFireAt: nil),
            schedule(id: 2, enabled: true, nextFireAt: "2026-07-12T10:00:00Z"),
            schedule(id: 3, enabled: true, nextFireAt: "2026-07-12T08:00:00Z")
        ]

        XCTAssertEqual(
            ControlPresentationPolicy.orderedSchedules(schedules).map(\.id),
            [3, 2, 1]
        )
    }

    func testSessionsAreOnlyOrderedByRecencyAndNotPresentedAsRuns() {
        let sessions = [
            session(id: "older", updatedAt: "2026-07-10T08:00:00Z"),
            session(id: "newer", updatedAt: "2026-07-10T10:00:00Z")
        ]

        XCTAssertEqual(
            ControlPresentationPolicy.orderedSessions(sessions).map(\.id),
            ["newer", "older"]
        )
    }

    func testScheduleAndRunPresentationUsesConcreteDomainFields() {
        let task = schedule(id: 7, enabled: true, nextFireAt: "2026-07-12T08:00:00Z")
        let run = MobileScheduledTaskRun(
            id: 71,
            taskId: 7,
            triggeredAt: "2026-07-11T08:00:00Z",
            finishedAt: "2026-07-11T08:01:00Z",
            status: "success",
            errorMessage: nil,
            sessionId: "session-7",
            manual: true
        )

        XCTAssertEqual(ControlPresentationPolicy.scheduleText(task), "Cron 0 0 7 * * *")
        XCTAssertEqual(ControlPresentationPolicy.statusText(task), "Ready")
        XCTAssertEqual(ControlPresentationPolicy.runStatusText(run.status), "Succeeded")
        XCTAssertTrue(ControlPresentationPolicy.runDetail(run).hasPrefix("Manual ·"))
        XCTAssertNotNil(ControlPresentationPolicy.parseDate(run.triggeredAt))
    }

    func testAgentSelectionPrefersStoredThenDefaultThenFirstCatalogEntry() {
        let catalog = [
            MobileAgentCatalogItem(id: 3, name: "Main Assistant"),
            MobileAgentCatalogItem(id: 8, name: "Release Agent")
        ]

        XCTAssertEqual(
            AgentSelectionPolicy.resolve(storedId: 8, defaultAgentId: 3, catalog: catalog)?.id,
            8
        )
        XCTAssertEqual(
            AgentSelectionPolicy.resolve(storedId: 99, defaultAgentId: 3, catalog: catalog)?.id,
            3
        )
        XCTAssertEqual(
            AgentSelectionPolicy.resolve(storedId: nil, defaultAgentId: nil, catalog: catalog)?.id,
            3
        )
    }

    private func schedule(id: Int64, enabled: Bool, nextFireAt: String?) -> MobileScheduledTask {
        MobileScheduledTask(
            id: id,
            name: "Morning brief",
            agentId: 3,
            cronExpr: "0 0 7 * * *",
            oneShotAt: nil,
            timezone: "Asia/Shanghai",
            promptPreview: "Summarize updates",
            sessionMode: "new",
            enabled: enabled,
            nextFireAt: nextFireAt,
            lastFireAt: nil,
            status: "idle",
            system: false
        )
    }

    private func session(id: String, updatedAt: String) -> MobileSession {
        MobileSession(
            id: id,
            userId: 1,
            agentId: 3,
            title: id,
            status: "active",
            runtimeStatus: "idle",
            messageCount: 1,
            updatedAt: updatedAt
        )
    }
}
