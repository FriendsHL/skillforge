import Foundation
import XCTest

@testable import SkillForge

final class ConnectionHealthPolicyTests: XCTestCase {
  func testSuccessfulProbeIsHealthy() {
    let result = ConnectionHealthPolicy.classify(error: nil)

    XCTAssertEqual(result, .healthy)
  }

  func testOfflineURLErrorIsClassifiedAsOffline() {
    let error = URLError(.notConnectedToInternet)

    let result = ConnectionHealthPolicy.classify(error: error)

    XCTAssertEqual(result, .offline)
    XCTAssertEqual(ConnectionHealthPhase.offline.summaryTitle, "Cannot reach server")
  }

  func testHTTP5xxIsClassifiedAsServiceIssueWithoutExposingRawBody() {
    let rawBody = #"{"status":500,"error":"Internal Server Error","trace":"secret-stack"}"#
    let result = ConnectionHealthPolicy.classify(
      error: MobileApiError.httpStatus(503, rawBody)
    )
    let message = ConnectionHealthPhase.serviceIssue.summaryDetail

    XCTAssertEqual(result, .serviceIssue)
    XCTAssertFalse(message.contains(rawBody))
    XCTAssertFalse(message.contains("Internal Server Error"))
    XCTAssertFalse(message.contains("secret-stack"))
  }

  func testHTTP401IsUnauthorizedWithoutExposingRawBody() {
    let rawBody = #"{"error":"expired-device-token","token":"must-not-leak"}"#
    let result = ConnectionHealthPolicy.classify(
      error: MobileApiError.httpStatus(401, rawBody)
    )
    let message = ConnectionHealthPhase.serviceIssue.summaryDetail

    XCTAssertEqual(result, .unauthorized)
    XCTAssertFalse(message.contains(rawBody))
    XCTAssertFalse(message.contains("expired-device-token"))
    XCTAssertFalse(message.contains("must-not-leak"))
  }
}

@MainActor
final class ConnectionHealthMonitorTests: XCTestCase {
  func testUnauthorizedProbeInvokesDisconnect() async {
    var unauthorizedCount = 0
    let monitor = ConnectionHealthMonitor(
      pairingIdentity: "pairing-a",
      probe: { throw MobileApiError.httpStatus(401, #"{"token":"secret"}"#) },
      onUnauthorized: { unauthorizedCount += 1 }
    )

    monitor.check(pairingIdentity: "pairing-a")
    await waitUntil { unauthorizedCount == 1 }

    XCTAssertEqual(unauthorizedCount, 1)
  }

  func testServiceFailurePreservesPairing() async {
    var unauthorizedCount = 0
    let monitor = ConnectionHealthMonitor(
      pairingIdentity: "pairing-a",
      probe: { throw MobileApiError.httpStatus(503, "backend details") },
      onUnauthorized: { unauthorizedCount += 1 }
    )

    monitor.check(pairingIdentity: "pairing-a")
    await waitUntil { monitor.state.phase == .serviceIssue }

    XCTAssertEqual(unauthorizedCount, 0)
    XCTAssertEqual(monitor.state.phase, .serviceIssue)
  }

  func testOfflineFailurePreservesPairing() async {
    var unauthorizedCount = 0
    let monitor = ConnectionHealthMonitor(
      pairingIdentity: "pairing-a",
      probe: { throw URLError(.notConnectedToInternet) },
      onUnauthorized: { unauthorizedCount += 1 }
    )

    monitor.check(pairingIdentity: "pairing-a")
    await waitUntil { monitor.state.phase == .offline }

    XCTAssertEqual(unauthorizedCount, 0)
    XCTAssertEqual(monitor.state.phase, .offline)
  }

  func testConcurrentCheckRequestsShareSingleProbe() async {
    let probe = ControlledConnectionProbe()
    let monitor = ConnectionHealthMonitor(
      pairingIdentity: "pairing-a",
      probe: { try await probe.run() },
      onUnauthorized: {}
    )

    monitor.check(pairingIdentity: "pairing-a")
    monitor.check(pairingIdentity: "pairing-a")
    await waitUntil { probe.isWaiting }

    XCTAssertEqual(probe.callCount, 1)
    monitor.cancel()
    probe.finish()
  }

  func testCancelledStaleUnauthorizedCannotDisconnectNewPairing() async {
    let probe = ControlledConnectionProbe()
    var unauthorizedCount = 0
    let monitor = ConnectionHealthMonitor(
      pairingIdentity: "pairing-a",
      probe: { try await probe.run() },
      onUnauthorized: { unauthorizedCount += 1 }
    )

    monitor.check(pairingIdentity: "pairing-a")
    await waitUntil { probe.isWaiting }
    monitor.cancel()
    probe.finish(throwing: MobileApiError.httpStatus(401, "expired"))
    await Task.yield()

    XCTAssertEqual(unauthorizedCount, 0)
    XCTAssertEqual(monitor.state.phase, .notChecked)
  }

  func testWrongPairingIdentityCannotStartProbe() async {
    var probeCount = 0
    let monitor = ConnectionHealthMonitor(
      pairingIdentity: "pairing-a",
      probe: { probeCount += 1 },
      onUnauthorized: {}
    )

    monitor.check(pairingIdentity: "pairing-b")
    await Task.yield()

    XCTAssertEqual(probeCount, 0)
    XCTAssertEqual(monitor.state.phase, .notChecked)
  }

  private func waitUntil(
    _ predicate: @escaping @MainActor () -> Bool,
    file: StaticString = #filePath,
    line: UInt = #line
  ) async {
    for _ in 0..<100 where !predicate() {
      await Task.yield()
    }
    XCTAssertTrue(predicate(), file: file, line: line)
  }
}

@MainActor
private final class ControlledConnectionProbe {
  private var continuation: CheckedContinuation<Void, Error>?
  private(set) var callCount = 0

  var isWaiting: Bool { continuation != nil }

  func run() async throws {
    callCount += 1
    try await withCheckedThrowingContinuation { continuation in
      self.continuation = continuation
    }
  }

  func finish() {
    continuation?.resume()
    continuation = nil
  }

  func finish(throwing error: Error) {
    continuation?.resume(throwing: error)
    continuation = nil
  }
}
