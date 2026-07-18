import XCTest
@testable import SkillForge

final class AgentsRosterPolicyTests: XCTestCase {
    private let agents = [
        MobileAgentCatalogItem(id: 1, name: "Main Assistant", description: "Coordinates work", status: "active", isDefault: true),
        MobileAgentCatalogItem(id: 2, name: "Release Agent", description: "Checks deployments", status: "active"),
        MobileAgentCatalogItem(id: 3, name: "Archived Research", description: "Historical research", status: "disabled")
    ]

    func testAvailableAndDefaultFiltersUseStatusAndCatalogDefaultIndependently() {
        XCTAssertEqual(AgentsRosterPolicy.filtered(agents, query: "", filter: .available).map(\.id), [1, 2])
        XCTAssertEqual(AgentsRosterPolicy.filtered(agents, query: "", filter: .defaultAgent).map(\.id), [1])
    }

    func testSearchCombinesWithSelectedFilterAcrossNameAndDescription() {
        XCTAssertEqual(
            AgentsRosterPolicy.filtered(agents, query: "deploy", filter: .available).map(\.id),
            [2]
        )
        XCTAssertTrue(AgentsRosterPolicy.filtered(agents, query: "research", filter: .available).isEmpty)
    }

    func testCurrentAndDefaultBadgesAreSeparateFacts() {
        XCTAssertEqual(
            AgentsRosterPolicy.badges(for: agents[0], currentAgentID: 2),
            [.defaultAgent]
        )
        XCTAssertEqual(
            AgentsRosterPolicy.badges(for: agents[1], currentAgentID: 2),
            [.current]
        )
        XCTAssertEqual(
            AgentsRosterPolicy.badges(for: agents[0], currentAgentID: 1),
            [.current, .defaultAgent]
        )
    }

    func testAgentSessionsCanSwitchBetweenOneAgentAndAllWithoutChangingOrder() {
        let sessions = [
            MobileSession(id: "recent-main", userId: 1, agentId: 1, title: "Main", status: "active", runtimeStatus: "idle", messageCount: 2, updatedAt: "2026-07-18T10:00:00Z"),
            MobileSession(id: "recent-release", userId: 1, agentId: 2, title: "Release", status: "active", runtimeStatus: "idle", messageCount: 5, updatedAt: "2026-07-18T11:00:00Z"),
            MobileSession(id: "old-release", userId: 1, agentId: 2, title: "Old", status: "active", runtimeStatus: "idle", messageCount: 8, updatedAt: "2026-07-17T11:00:00Z")
        ]

        XCTAssertEqual(AgentSessionPolicy.filtered(sessions, agentID: 2, scope: .agent).map(\.id), ["recent-release", "old-release"])
        XCTAssertEqual(AgentSessionPolicy.filtered(sessions, agentID: 2, scope: .all).map(\.id), sessions.map(\.id))
        XCTAssertEqual(AgentSessionPolicy.mostRecent(sessions, agentID: 2)?.id, "recent-release")
    }
}
