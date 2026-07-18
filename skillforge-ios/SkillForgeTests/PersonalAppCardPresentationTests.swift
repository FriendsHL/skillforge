import Foundation
import XCTest
@testable import SkillForge

final class PersonalAppCardPresentationTests: XCTestCase {
    func testLibrarySummaryCollapsesWhitespaceAndKeepsShortDescriptions() {
        XCTAssertEqual(
            PersonalAppLibraryCardPresentation.compactSummary(
                "  35 AI updates\nwith filters,   favorites, and saved review state.  "
            ),
            "35 AI updates with filters, favorites, and saved review state."
        )
        XCTAssertNil(PersonalAppLibraryCardPresentation.compactSummary(" \n\t "))
        XCTAssertNil(PersonalAppLibraryCardPresentation.compactSummary(nil))
    }

    func testLibrarySummaryBoundsLongDescriptionsWithoutChangingSearchSource() {
        let longDescription = Array(repeating: "interactive result", count: 12).joined(separator: " ")

        let summary = PersonalAppLibraryCardPresentation.compactSummary(longDescription)

        XCTAssertNotNil(summary)
        XCTAssertLessThanOrEqual(summary?.count ?? .max, 96)
        XCTAssertTrue(summary?.hasSuffix("…") == true)
        XCTAssertEqual(longDescription.count, 227, "The presentation helper must not mutate the source caption")
    }

    func testLibraryMonogramUsesCompactMeaningfulInitials() {
        XCTAssertEqual(PersonalAppLibraryCardPresentation.monogram(for: "AI Brief"), "AI")
        XCTAssertEqual(PersonalAppLibraryCardPresentation.monogram(for: "Release Readiness Board"), "RR")
        XCTAssertEqual(PersonalAppLibraryCardPresentation.monogram(for: "七月家庭预算"), "七月")
    }

    func testCapabilityFactsAreStableAndSeparateFromProvenance() {
        XCTAssertEqual(
            PersonalAppCardPresentation.capabilityText,
            "Offline · No permissions"
        )
    }

    func testProvenanceSupportsSourceAndTimePresenceMatrix() throws {
        let date = try Date.ISO8601FormatStyle().parse("2026-07-16T18:30:00Z")
        let locale = Locale(identifier: "en_US")
        let timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))

        XCTAssertNil(PersonalAppCardPresentation.provenanceText(
            sourceLabel: nil,
            createdAt: nil,
            locale: locale,
            timeZone: timeZone
        ))
        XCTAssertEqual(PersonalAppCardPresentation.provenanceText(
            sourceLabel: "Research Agent",
            createdAt: nil,
            locale: locale,
            timeZone: timeZone
        ), "Research Agent")

        let timeOnly = try XCTUnwrap(PersonalAppCardPresentation.provenanceText(
            sourceLabel: nil,
            createdAt: date,
            locale: locale,
            timeZone: timeZone
        ))
        XCTAssertTrue(timeOnly.contains("Jul 16, 2026"))
        XCTAssertTrue(timeOnly.contains("6:30"))

        let sourceAndTime = try XCTUnwrap(PersonalAppCardPresentation.provenanceText(
            sourceLabel: "Research Agent",
            createdAt: date,
            locale: locale,
            timeZone: timeZone
        ))
        XCTAssertTrue(sourceAndTime.hasPrefix("Research Agent · "))
        XCTAssertTrue(sourceAndTime.contains("Jul 16, 2026"))
    }

    func testSourcePolicyUsesOnlyExactAgentIDMatches() {
        let research = MobileAgentCatalogItem(id: 7, name: "Research Agent")
        let defaultAgent = MobileAgentSummary(id: 1, name: "Main Assistant")

        XCTAssertEqual(PersonalAppSourceLabelPolicy.resolve(
            sessionAgentID: 7,
            availableAgents: [research],
            defaultAgent: defaultAgent
        ), "Research Agent")
        XCTAssertEqual(PersonalAppSourceLabelPolicy.resolve(
            sessionAgentID: 1,
            availableAgents: [research],
            defaultAgent: defaultAgent
        ), "Main Assistant")
        XCTAssertNil(PersonalAppSourceLabelPolicy.resolve(
            sessionAgentID: 99,
            availableAgents: [research],
            defaultAgent: defaultAgent
        ))
        XCTAssertNil(PersonalAppSourceLabelPolicy.resolve(
            sessionAgentID: nil,
            availableAgents: [research],
            defaultAgent: defaultAgent
        ))
    }

    func testSourcePolicyDoesNotLeakMismatchedDefaultOrBlankNames() {
        XCTAssertNil(PersonalAppSourceLabelPolicy.resolve(
            sessionAgentID: 7,
            availableAgents: [MobileAgentCatalogItem(id: 7, name: "   ")],
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant")
        ))
        XCTAssertNil(PersonalAppSourceLabelPolicy.resolve(
            sessionAgentID: 9,
            availableAgents: [],
            defaultAgent: MobileAgentSummary(id: 1, name: "Main Assistant")
        ))
    }

    func testPrimaryActionUsesNeutralDarkToneInsteadOfGlobalAccent() {
        XCTAssertEqual(PersonalAppCardPresentation.primaryActionTone, .neutralDark)
    }

    func testAccessibilitySummaryDoesNotInventAgentOrTimestampFacts() {
        let summary = PersonalAppCardPresentation.accessibilitySummary(
            title: "July budget",
            caption: "Adjust the July family budget."
        )

        XCTAssertEqual(
            summary,
            "July budget. Adjust the July family budget. Personal App"
        )
        XCTAssertFalse(summary.contains("Generated by Agent"))
        XCTAssertEqual(summary.components(separatedBy: "Personal App").count - 1, 1)
    }
}
