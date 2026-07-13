import SwiftUI
import UserNotifications
import XCTest

@testable import SkillForge

final class InteractionPolishPolicyTests: XCTestCase {
  private var defaults: UserDefaults!
  private var suiteName: String!

  override func setUp() {
    super.setUp()
    suiteName = "InteractionPolishPolicyTests.\(UUID().uuidString)"
    defaults = UserDefaults(suiteName: suiteName)
    defaults.removePersistentDomain(forName: suiteName)
  }

  override func tearDown() {
    defaults.removePersistentDomain(forName: suiteName)
    defaults = nil
    suiteName = nil
    super.tearDown()
  }

  func testSessionSearchMatchesTitleAndRuntimeStatusCaseInsensitively() {
    let sessions = fixtureSessions()

    XCTAssertEqual(
      SessionListPolicy.filteredSessions(sessions, query: "DEPLOY", filter: .all).map(\.id),
      ["waiting"]
    )
    XCTAssertEqual(
      SessionListPolicy.filteredSessions(sessions, query: "running", filter: .all).map(\.id),
      ["running"]
    )
  }

  func testSessionStatusGroupsNormalizeWaitingErrorAndOtherStates() {
    let sessions = fixtureSessions()

    XCTAssertEqual(SessionListPolicy.group(for: sessions[0]), .running)
    XCTAssertEqual(SessionListPolicy.group(for: sessions[1]), .waiting)
    XCTAssertEqual(SessionListPolicy.group(for: sessions[2]), .error)
    XCTAssertEqual(SessionListPolicy.group(for: sessions[3]), .other)
  }

  func testSessionSearchAndStatusFilterComposeWithoutChangingRecencyOrder() {
    let sessions = fixtureSessions()

    XCTAssertEqual(
      SessionListPolicy.filteredSessions(sessions, query: "deployment", filter: .waiting).map(\.id),
      ["waiting"]
    )
    XCTAssertEqual(
      SessionListPolicy.filteredSessions(sessions, query: "", filter: .all).map(\.id),
      ["running", "waiting", "error", "idle"]
    )
  }

  func testAppearanceMapsSystemLightAndDarkToColorSchemes() {
    XCTAssertNil(AppAppearance.system.colorScheme)
    XCTAssertEqual(AppAppearance.light.colorScheme, .light)
    XCTAssertEqual(AppAppearance.dark.colorScheme, .dark)
    XCTAssertEqual(AppAppearance.allCases.map(\.rawValue), ["system", "light", "dark"])
  }

  func testAppearanceResolvesStoredRawValueAndFallsBackToSystem() {
    XCTAssertEqual(AppAppearance.resolve(storedValue: "dark"), .dark)
    XCTAssertEqual(AppAppearance.resolve(storedValue: "unsupported-future-value"), .system)
    XCTAssertEqual(AppAppearance.resolve(storedValue: nil), .system)
  }

  func testNotificationPresentationOnlyOffersPermissionWhenUndetermined() {
    let presentation = NotificationPresentationPolicy.presentation(for: .notDetermined)

    XCTAssertEqual(presentation.statusText, "Not enabled")
    XCTAssertTrue(presentation.canRequestPermission)
    XCTAssertFalse(presentation.shouldOpenSystemSettings)
    XCTAssertFalse(presentation.isAuthorized)
  }

  func testNotificationPresentationUsesSystemSettingsForDeniedAuthorization() {
    let presentation = NotificationPresentationPolicy.presentation(for: .denied)

    XCTAssertEqual(presentation.statusText, "Disabled in Settings")
    XCTAssertFalse(presentation.canRequestPermission)
    XCTAssertTrue(presentation.shouldOpenSystemSettings)
    XCTAssertFalse(presentation.isAuthorized)
  }

  func testNotificationPresentationDoesNotClaimServerRegistrationWhenAuthorized() {
    for status in [UNAuthorizationStatus.authorized, .provisional, .ephemeral] {
      let presentation = NotificationPresentationPolicy.presentation(for: status)

      XCTAssertEqual(presentation.statusText, "Allowed on this iPhone")
      XCTAssertFalse(presentation.canRequestPermission)
      XCTAssertFalse(presentation.shouldOpenSystemSettings)
      XCTAssertTrue(presentation.isAuthorized)
      XCTAssertFalse(presentation.isRegisteredWithServer)
    }
  }

  private func fixtureSessions() -> [MobileSession] {
    [
      session(
        id: "running", title: "Prepare release", runtimeStatus: "running",
        updatedAt: "2026-07-12T10:04:00Z"),
      session(
        id: "waiting", title: "Review deployment", runtimeStatus: "waiting_user",
        updatedAt: "2026-07-12T10:03:00Z"),
      session(
        id: "error", title: "Release failure", runtimeStatus: "error",
        updatedAt: "2026-07-12T10:02:00Z"),
      session(
        id: "idle", title: "Release notes", runtimeStatus: "idle", updatedAt: "2026-07-12T10:01:00Z"
      ),
    ]
  }

  private func session(
    id: String,
    title: String,
    runtimeStatus: String,
    updatedAt: String
  ) -> MobileSession {
    MobileSession(
      id: id,
      userId: 1,
      agentId: 1,
      title: title,
      status: "active",
      runtimeStatus: runtimeStatus,
      messageCount: 1,
      updatedAt: updatedAt
    )
  }
}
