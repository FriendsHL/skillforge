import SwiftUI
import XCTest
@testable import SkillForge

@MainActor
final class ChatVisualStyleTests: XCTestCase {
    func testApprovedSoftBlueUserQueryBackgroundResolvesInLightAndDarkMode() {
        assertColor(
            CompanionStyle.userQueryBackground,
            style: .light,
            red: 0xEA,
            green: 0xF2,
            blue: 0xFF
        )
        assertColor(
            CompanionStyle.userQueryBackground,
            style: .dark,
            red: 0x18,
            green: 0x2A,
            blue: 0x44
        )
        assertColor(
            CompanionStyle.userQueryForeground,
            style: .light,
            red: 0x17,
            green: 0x23,
            blue: 0x3A
        )
        assertColor(
            CompanionStyle.userQueryForeground,
            style: .dark,
            red: 0xF5,
            green: 0xF8,
            blue: 0xFF
        )
        assertColor(
            CompanionStyle.userQueryBorder,
            style: .light,
            red: 0xC7,
            green: 0xD9,
            blue: 0xF8
        )
        assertColor(
            CompanionStyle.userQueryBorder,
            style: .dark,
            red: 0x35,
            green: 0x51,
            blue: 0x7A
        )
    }

    private func assertColor(
        _ color: Color,
        style: UIUserInterfaceStyle,
        red: Int,
        green: Int,
        blue: Int,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let resolved = UIColor(color).resolvedColor(
            with: UITraitCollection(userInterfaceStyle: style)
        )
        var actualRed: CGFloat = 0
        var actualGreen: CGFloat = 0
        var actualBlue: CGFloat = 0
        var alpha: CGFloat = 0

        XCTAssertTrue(
            resolved.getRed(
                &actualRed,
                green: &actualGreen,
                blue: &actualBlue,
                alpha: &alpha
            ),
            file: file,
            line: line
        )
        XCTAssertEqual(actualRed, CGFloat(red) / 255, accuracy: 0.002, file: file, line: line)
        XCTAssertEqual(actualGreen, CGFloat(green) / 255, accuracy: 0.002, file: file, line: line)
        XCTAssertEqual(actualBlue, CGFloat(blue) / 255, accuracy: 0.002, file: file, line: line)
        XCTAssertEqual(alpha, 1, accuracy: 0.002, file: file, line: line)
    }
}
