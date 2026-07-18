import Foundation
import XCTest
@testable import SkillForge

final class PersonalAppSnapshotValidatorTests: XCTestCase {
    private enum TestLimits {
        static let snapshotBytes = 64 * 1024
        static let depth = 8
        static let schemaNodes = 1_024
        static let dataNodes = 8_192
    }

    private let schema: [String: MobileJSONValue] = [
        "type": .string("object"),
        "additionalProperties": .bool(false),
        "required": .array([.string("food")]),
        "properties": .object([
            "food": .object(["type": .string("integer")]),
            "note": .object(["type": .string("string")])
        ])
    ]

    private let budgetSchema: [String: MobileJSONValue] = [
        "type": .string("object"),
        "additionalProperties": .bool(false),
        "required": .array([.string("income"), .string("amounts"), .string("note")]),
        "properties": .object([
            "income": .object(["type": .string("number")]),
            "amounts": .object([
                "type": .string("object"),
                "additionalProperties": .object(["type": .string("number")])
            ]),
            "note": .object(["type": .string("string")])
        ])
    ]

    func testAcceptsSchemaValidSnapshot() {
        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"food":2800,"note":"family"}"#.utf8),
            schema: schema
        ))
    }

    func testResourceLimitsMatchTheServerAndMobileContracts() {
        XCTAssertEqual(PersonalAppSnapshotValidator.maximumSnapshotBytes, TestLimits.snapshotBytes)
        XCTAssertEqual(PersonalAppSnapshotValidator.maximumDepth, TestLimits.depth)
        XCTAssertEqual(PersonalAppSnapshotValidator.maximumSchemaNodeCount, TestLimits.schemaNodes)
        XCTAssertEqual(PersonalAppSnapshotValidator.maximumDataNodeCount, TestLimits.dataNodes)
    }

    func testRejectsMissingRequiredUnknownAndWrongType() {
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"note":"family"}"#.utf8), schema: schema))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"food":2800,"secret":true}"#.utf8), schema: schema))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"food":"2800"}"#.utf8), schema: schema))
    }

    func testRejectsNonObjectAndOversizedPayload() {
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data("[]".utf8), schema: schema))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(repeating: 0x61, count: TestLimits.snapshotBytes + 1),
            schema: schema
        ))
    }

    func testAcceptsPayloadAt64KiBBoundary() {
        var data = Data(#"{"food":2800}"#.utf8)
        data.append(Data(
            repeating: 0x20,
            count: TestLimits.snapshotBytes - data.count
        ))

        XCTAssertEqual(data.count, 64 * 1024)
        XCTAssertTrue(PersonalAppSnapshotValidator.validate(data: data, schema: schema))
    }

    func testValidatesBudgetAmountsWithAdditionalPropertiesSchema() {
        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"income":20000,"amounts":{"rent":6000,"groceries":2600.5},"note":"July"}"#.utf8),
            schema: budgetSchema
        ))

        for invalidAmount in [#""6000""#, "true", #"{"value":6000}"#] {
            let data = Data(
                #"{"income":20000,"amounts":{"rent":\#(invalidAmount)},"note":"July"}"#.utf8
            )
            XCTAssertFalse(
                PersonalAppSnapshotValidator.validate(data: data, schema: budgetSchema),
                "Expected nested amount \(invalidAmount) to be rejected"
            )
        }
    }

    func testValidatesNestedArrayAndObjectItemsRecursively() {
        let nestedSchema: [String: MobileJSONValue] = [
            "type": .string("object"),
            "additionalProperties": .bool(false),
            "properties": .object([
                "groups": .object([
                    "type": .string("array"),
                    "items": .object([
                        "type": .string("object"),
                        "additionalProperties": .bool(false),
                        "required": .array([.string("name"), .string("values")]),
                        "properties": .object([
                            "name": .object(["type": .string("string")]),
                            "values": .object([
                                "type": .string("array"),
                                "items": .object(["type": .string("integer")])
                            ])
                        ])
                    ])
                ])
            ])
        ]

        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"groups":[{"name":"A","values":[1,2.0]}]}"#.utf8),
            schema: nestedSchema
        ))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"groups":[{"name":"A","values":[1,true]}]}"#.utf8),
            schema: nestedSchema
        ))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"groups":[{"name":"A","values":[1],"secret":"x"}]}"#.utf8),
            schema: nestedSchema
        ))
    }

    func testDistinguishesBooleansFromJSONNumbers() {
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"income":true,"amounts":{},"note":"July"}"#.utf8),
            schema: budgetSchema
        ))

        let booleanSchema: [String: MobileJSONValue] = [
            "type": .string("object"),
            "properties": .object([
                "enabled": .object(["type": .string("boolean")])
            ])
        ]
        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"enabled":true}"#.utf8), schema: booleanSchema))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"enabled":1}"#.utf8), schema: booleanSchema))
    }

    func testExplicitTrueAdditionalPropertiesAllowsUnknownKeys() {
        let openSchema: [String: MobileJSONValue] = [
            "type": .string("object"),
            "additionalProperties": .bool(true),
            "properties": .object([
                "name": .object(["type": .string("string")])
            ])
        ]

        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"name":"July","future":{"nested":true}}"#.utf8),
            schema: openSchema
        ))
    }

    func testRejectsUnknownAndMalformedSchemaTypesBeforeInspectingData() {
        let unknownType: [String: MobileJSONValue] = [
            "type": .string("object"),
            "properties": .object([
                "future": .object(["type": .string("currency")])
            ])
        ]
        let malformedType: [String: MobileJSONValue] = [
            "type": .string("object"),
            "properties": .object([
                "future": .object(["type": .bool(true)])
            ])
        ]
        let malformedItems: [String: MobileJSONValue] = [
            "type": .string("object"),
            "properties": .object([
                "future": .object([
                    "type": .string("array"),
                    "items": .string("not-a-schema")
                ])
            ])
        ]
        let malformedRequired: [String: MobileJSONValue] = [
            "type": .string("object"),
            "required": .array([.number("1")])
        ]
        for invalidSchema in [
            unknownType,
            malformedType,
            malformedItems,
            malformedRequired
        ] {
            XCTAssertFalse(PersonalAppSnapshotValidator.validate(
                data: Data("{}".utf8), schema: invalidSchema
            ))
        }
    }

    func testRejectsUnsupportedKeywordsOnScalarSchemasEvenWhenPropertyIsAbsent() {
        let unsupportedScalarSchemas: [[String: MobileJSONValue]] = [
            ["type": .string("number"), "minimum": .number("0")],
            ["type": .string("number"), "maximum": .number("10")],
            ["type": .string("string"), "enum": .array([.string("one")])],
            ["type": .string("string"), "const": .string("fixed")],
            ["type": .string("string"), "minLength": .number("1")],
            ["type": .string("integer"), "futureKeyword": .bool(true)]
        ]

        for unsupportedSchema in unsupportedScalarSchemas {
            let rootSchema: [String: MobileJSONValue] = [
                "type": .string("object"),
                "properties": .object([
                    "optional": .object(unsupportedSchema)
                ])
            ]
            XCTAssertFalse(PersonalAppSnapshotValidator.validate(
                data: Data("{}".utf8),
                schema: rootSchema
            ))
        }
    }

    func testRejectsUnsupportedKeywordsAtRootAndNestedSchemaObjects() {
        let rootUnknown: [String: MobileJSONValue] = [
            "type": .string("object"),
            "futureKeyword": .bool(true)
        ]
        let nestedUnknown: [String: MobileJSONValue] = [
            "type": .string("object"),
            "properties": .object([
                "optional": .object([
                    "type": .string("object"),
                    "properties": .object([:]),
                    "minimum": .number("0")
                ])
            ])
        ]
        let arrayUsingObjectKeyword: [String: MobileJSONValue] = [
            "type": .string("object"),
            "properties": .object([
                "optional": .object([
                    "type": .string("array"),
                    "properties": .object([:])
                ])
            ])
        ]

        for invalidSchema in [rootUnknown, nestedUnknown, arrayUsingObjectKeyword] {
            XCTAssertFalse(PersonalAppSnapshotValidator.validate(
                data: Data("{}".utf8),
                schema: invalidSchema
            ))
        }
    }

    func testIntegerValidationPreservesNumbersBeyondDoublePrecision() {
        let integerSchema = makeScalarPropertySchema(type: "integer")

        for validNumber in ["2.0", "9007199254740993", "-9007199254740993"] {
            XCTAssertTrue(PersonalAppSnapshotValidator.validate(
                data: Data(#"{"value":\#(validNumber)}"#.utf8),
                schema: integerSchema
            ), "Expected exact integer \(validNumber) to be accepted")
        }
        for nonInteger in ["9007199254740993.5", "1e-400"] {
            XCTAssertFalse(PersonalAppSnapshotValidator.validate(
                data: Data(#"{"value":\#(nonInteger)}"#.utf8),
                schema: integerSchema
            ), "Expected non-integer or unrepresentable Decimal \(nonInteger) to be rejected")
        }
    }

    func testNumberAcceptsExactDecimalButRejectsUnrepresentableDecimal() {
        let numberSchema = makeScalarPropertySchema(type: "number")

        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"value":9007199254740993.5}"#.utf8),
            schema: numberSchema
        ))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data(#"{"value":1e-400}"#.utf8),
            schema: numberSchema
        ))
    }

    func testEnforcesSchemaDepthAtServerContractBoundary() {
        let maximumDepthSchema = makeNestedSchema(objectLevels: 3)
        let tooDeepSchema = makeNestedSchema(objectLevels: 4)
        let data = Data(#"{"child":{"child":{"child":"ok"}}}"#.utf8)

        XCTAssertTrue(PersonalAppSnapshotValidator.validate(data: data, schema: maximumDepthSchema))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(data: data, schema: tooDeepSchema))
    }

    func testEnforcesDataDepthBoundaryEvenForUnconstrainedProperties() throws {
        let openSchema: [String: MobileJSONValue] = ["type": .string("object")]

        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: try makeNestedData(depth: TestLimits.depth),
            schema: openSchema
        ))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: try makeNestedData(depth: TestLimits.depth + 1),
            schema: openSchema
        ))
    }

    func testEnforcesSchemaNodeBoundary() {
        let propertyCountAtBoundary = (TestLimits.schemaNodes - 4) / 2
        XCTAssertEqual(4 + propertyCountAtBoundary * 2,
                       TestLimits.schemaNodes)

        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: Data("{}".utf8),
            schema: makeWideSchema(propertyCount: propertyCountAtBoundary)
        ))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: Data("{}".utf8),
            schema: makeWideSchema(propertyCount: propertyCountAtBoundary + 1)
        ))
    }

    func testEnforcesDataNodeBoundary() throws {
        let arraySchema: [String: MobileJSONValue] = [
            "type": .string("object"),
            "properties": .object([
                "items": .object(["type": .string("array")])
            ])
        ]
        let elementCountAtBoundary = TestLimits.dataNodes - 2

        XCTAssertTrue(PersonalAppSnapshotValidator.validate(
            data: try JSONSerialization.data(withJSONObject: [
                "items": Array(repeating: 0, count: elementCountAtBoundary)
            ]),
            schema: arraySchema
        ))
        XCTAssertFalse(PersonalAppSnapshotValidator.validate(
            data: try JSONSerialization.data(withJSONObject: [
                "items": Array(repeating: 0, count: elementCountAtBoundary + 1)
            ]),
            schema: arraySchema
        ))
    }

    private func makeNestedSchema(objectLevels: Int) -> [String: MobileJSONValue] {
        guard objectLevels > 0 else { return ["type": .string("string")] }
        return [
            "type": .string("object"),
            "properties": .object([
                "child": .object(makeNestedSchema(objectLevels: objectLevels - 1))
            ])
        ]
    }

    private func makeScalarPropertySchema(type: String) -> [String: MobileJSONValue] {
        [
            "type": .string("object"),
            "additionalProperties": .bool(false),
            "required": .array([.string("value")]),
            "properties": .object([
                "value": .object(["type": .string(type)])
            ])
        ]
    }

    private func makeNestedData(depth: Int) throws -> Data {
        precondition(depth >= 2)
        var value: Any = "leaf"
        for _ in 1..<depth {
            value = ["child": value]
        }
        return try JSONSerialization.data(withJSONObject: value)
    }

    private func makeWideSchema(propertyCount: Int) -> [String: MobileJSONValue] {
        let properties = Dictionary(uniqueKeysWithValues: (0..<propertyCount).map { index in
            ("p\(index)", MobileJSONValue.object(["type": .string("string")]))
        })
        return [
            "type": .string("object"),
            "additionalProperties": .bool(false),
            "properties": .object(properties)
        ]
    }
}
