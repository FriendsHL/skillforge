import XCTest
@testable import SkillForge

final class PairingScannerAvailabilityTests: XCTestCase {
    func testSimulatorUsesManualPairingFallback() {
        #if targetEnvironment(simulator)
        XCTAssertFalse(PairingScannerAvailability.canUseCameraScanner)
        XCTAssertTrue(PairingScannerAvailability.unavailableMessage.contains("Simulator"))
        #else
        XCTAssertFalse(PairingScannerAvailability.unavailableMessage.isEmpty)
        #endif
    }
}
