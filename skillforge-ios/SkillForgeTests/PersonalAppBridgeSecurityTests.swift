import Foundation
import XCTest
@testable import SkillForge

final class PersonalAppBridgeSecurityTests: XCTestCase {
    private let artifactID = "interactive-budget"
    private let budgetSchema: [String: MobileJSONValue] = [
        "type": .string("object"),
        "additionalProperties": .bool(false),
        "required": .array([.string("food")]),
        "properties": .object([
            "food": .object(["type": .string("integer")])
        ])
    ]

    func testParserAcceptsLegacySaveAndSubmitCommands() throws {
        let save = PersonalAppBridgeMessageParser.parse(
            handlerName: "skillforge",
            isMainFrame: true,
            body: [
                "method": "saveState",
                "artifactId": artifactID,
                "payload": ["food": 2_800]
            ],
            expectedArtifactID: artifactID
        )
        let submit = PersonalAppBridgeMessageParser.parse(
            handlerName: "skillforge",
            isMainFrame: true,
            body: [
                "method": "submitSnapshot",
                "artifactId": artifactID,
                "payload": [["food": 2_800]]
            ],
            expectedArtifactID: artifactID
        )

        guard case .saveState(let saveData) = save else {
            return XCTFail("Expected legacy saveState command")
        }
        guard case .submitSnapshot(let submitData) = submit else {
            return XCTFail("Expected legacy submitSnapshot command")
        }
        XCTAssertEqual(
            try JSONSerialization.jsonObject(with: saveData) as? [String: Int],
            ["food": 2_800]
        )
        XCTAssertEqual(
            try JSONSerialization.jsonObject(with: submitData) as? [[String: Int]],
            [["food": 2_800]]
        )
    }

    func testParserAcceptsValidatedRequestOpenURLAndPreservesURLComponents() {
        let command = PersonalAppBridgeMessageParser.parse(
            handlerName: "skillforge",
            isMainFrame: true,
            body: [
                "method": "requestOpenURL",
                "artifactId": artifactID,
                "url": "HTTPS://Example.COM:8443/a%20b?q=hello%20world#details"
            ],
            expectedArtifactID: artifactID
        )

        guard case .requestOpenURL(let url) = command else {
            return XCTFail("Expected requestOpenURL command")
        }
        XCTAssertEqual(
            url.absoluteString,
            "https://example.com:8443/a%20b?q=hello%20world#details"
        )
    }

    func testParserRequiresExpectedHandlerMainFrameAndArtifactIdentity() {
        let body: [String: Any] = [
            "method": "requestOpenURL",
            "artifactId": artifactID,
            "url": "https://example.com/report"
        ]

        XCTAssertNil(PersonalAppBridgeMessageParser.parse(
            handlerName: "other", isMainFrame: true,
            body: body, expectedArtifactID: artifactID
        ))
        XCTAssertNil(PersonalAppBridgeMessageParser.parse(
            handlerName: "skillforge", isMainFrame: false,
            body: body, expectedArtifactID: artifactID
        ))
        XCTAssertNil(PersonalAppBridgeMessageParser.parse(
            handlerName: "skillforge", isMainFrame: true,
            body: body, expectedArtifactID: "different-artifact"
        ))
    }

    func testParserRejectsUnknownMethodsWrongFieldTypesAndExtraFields() {
        let rejectedBodies: [[String: Any]] = [
            ["method": "unknown", "artifactId": artifactID, "url": "https://example.com"],
            ["method": 7, "artifactId": artifactID, "url": "https://example.com"],
            ["method": "requestOpenURL", "artifactId": 7, "url": "https://example.com"],
            ["method": "requestOpenURL", "artifactId": artifactID, "url": 7],
            ["method": "requestOpenURL", "artifactId": artifactID,
             "url": "https://example.com", "payload": [:]],
            ["method": "saveState", "artifactId": artifactID, "payload": ["food": 2_800],
             "url": "https://example.com"],
            ["method": "submitSnapshot", "artifactId": artifactID, "payload": "scalar"]
        ]

        for body in rejectedBodies {
            XCTAssertNil(PersonalAppBridgeMessageParser.parse(
                handlerName: "skillforge",
                isMainFrame: true,
                body: body,
                expectedArtifactID: artifactID
            ), "Unexpectedly accepted \(body)")
        }
    }

    func testParserRejectsOversizedMessage() {
        XCTAssertEqual(PersonalAppBridgeMessageParser.maximumMessageBytes, 64 * 1_024)
        let body: [String: Any] = [
            "method": "saveState",
            "artifactId": artifactID,
            "payload": ["note": String(repeating: "x", count: 70 * 1_024)]
        ]

        XCTAssertNil(PersonalAppBridgeMessageParser.parse(
            handlerName: "skillforge",
            isMainFrame: true,
            body: body,
            expectedArtifactID: artifactID
        ))
    }

    func testLegacyManifestPermissionAndNetworkDenialsStillDecodeUnchanged() throws {
        let data = Data(#"""
        {
          "schemaVersion": 1,
          "title": "Budget",
          "fallback": "Budget fallback",
          "permissions": [],
          "network": [],
          "initialData": {"food": 2600},
          "stateSchema": {"type": "object"}
        }
        """#.utf8)

        let manifest = try JSONDecoder().decode(InteractiveArtifactManifest.self, from: data)

        XCTAssertEqual(manifest.permissions, [])
        XCTAssertEqual(manifest.network, [])
        XCTAssertTrue(PersonalAppHTMLCSPInserter.policy.contains("connect-src 'none'"))
    }

    func testURLPolicyAllowsOnlyAbsoluteHTTPAndHTTPSWithAHost() {
        XCTAssertEqual(
            PersonalAppExternalURLPolicy.validatedURL("http://example.com")?.absoluteString,
            "http://example.com"
        )
        XCTAssertEqual(
            PersonalAppExternalURLPolicy.validatedURL("https://example.com/path?q=1#section")?.absoluteString,
            "https://example.com/path?q=1#section"
        )

        for value in [
            "relative/path",
            "//example.com/path",
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///tmp/report.html",
            "about:blank",
            "mailto:user@example.com",
            "tel:+1234567890",
            "skillforge://report",
            "https:///missing-host",
            "http:example.com"
        ] {
            XCTAssertNil(PersonalAppExternalURLPolicy.validatedURL(value), value)
        }
    }

    func testURLPolicyRejectsCredentialsControlsAndSchemeObfuscation() {
        for value in [
            "https://user@example.com/report",
            "https://user:secret@example.com/report",
            "https://example.com/line\nbreak",
            "https://example.com/line\u{0000}break",
            " https://example.com",
            "https ://example.com",
            "https%3A%2F%2Fexample.com",
            "ht\ntps://example.com",
            "https://example.com\\@evil.example"
        ] {
            XCTAssertNil(PersonalAppExternalURLPolicy.validatedURL(value), value)
        }
    }

    func testURLPolicyUsesServerCompatibleASCIIHostBoundary() {
        for value in [
            "https://localhost/report",
            "https://xn--fsqu00a.xn--0zwm56d/report",
            "https://127.0.0.1/report"
        ] {
            XCTAssertNotNil(PersonalAppExternalURLPolicy.validatedURL(value), value)
        }

        for value in [
            "https://foo_bar.com/report",
            "https://%65xample.com/report",
            "https://例子.com/report",
            "https://.example.com/report",
            "https://example..com/report",
            "https://-example.com/report",
            "https://example-.com/report",
            "https://127.1/report",
            "https://256.0.0.1/report",
            "https://[::1]/report"
        ] {
            XCTAssertNil(PersonalAppExternalURLPolicy.validatedURL(value), value)
        }
    }

    func testURLPolicyEnforcesUTF8LengthLimit() {
        let prefix = "https://example.com/"
        let accepted = prefix + String(repeating: "a", count: 2_048 - prefix.utf8.count)
        let rejected = accepted + "a"

        XCTAssertNotNil(PersonalAppExternalURLPolicy.validatedURL(accepted))
        XCTAssertNil(PersonalAppExternalURLPolicy.validatedURL(rejected))
    }

    func testCSPInserterSkipsCommentDecoyAndUsesRealHeadTag() throws {
        let html = """
        <!doctype html><!-- decoy <head data-wrong='1'> -->
        <html><HeAd data-note='a > b'><script>window.fixture = true</script></hEaD><body></body></html>
        """

        let secured = PersonalAppHTMLCSPInserter.secure(html)
        let realHead = try XCTUnwrap(secured.range(of: "<HeAd data-note='a > b'>"))
        let csp = try XCTUnwrap(secured.range(of: "http-equiv=\"Content-Security-Policy\""))
        let script = try XCTUnwrap(secured.range(of: "<script>"))

        XCTAssertGreaterThan(csp.lowerBound, realHead.upperBound)
        XCTAssertLessThan(csp.upperBound, script.lowerBound)
        XCTAssertTrue(secured.contains("<!-- decoy <head data-wrong='1'> -->"))
        XCTAssertEqual(secured.components(separatedBy: "Content-Security-Policy").count - 1, 1)
    }

    func testCSPInserterDoesNotTreatHeaderAsHead() throws {
        let html = "<html><header>Title</header><body><img src='https://example.com/a.png'></body></html>"

        let secured = PersonalAppHTMLCSPInserter.secure(html)
        let syntheticHead = try XCTUnwrap(secured.range(of: "<head>"))
        let header = try XCTUnwrap(secured.range(of: "<header>"))
        let csp = try XCTUnwrap(secured.range(of: "Content-Security-Policy"))

        XCTAssertLessThan(syntheticHead.lowerBound, header.lowerBound)
        XCTAssertGreaterThan(csp.lowerBound, syntheticHead.upperBound)
        XCTAssertLessThan(csp.upperBound, header.lowerBound)
    }

    func testCSPInserterCreatesSyntheticHeadBeforeResourcesWhenHeadIsMissing() throws {
        let html = "<!doctype html><body><link rel='stylesheet' href='https://example.com/a.css'><script>fetch('https://example.com')</script></body>"

        let secured = PersonalAppHTMLCSPInserter.secure(html)
        let csp = try XCTUnwrap(secured.range(of: "Content-Security-Policy"))
        let link = try XCTUnwrap(secured.range(of: "<link"))
        let script = try XCTUnwrap(secured.range(of: "<script>"))

        XCTAssertLessThan(csp.upperBound, link.lowerBound)
        XCTAssertLessThan(csp.upperBound, script.lowerBound)
        XCTAssertTrue(secured.contains("<head>"))
        XCTAssertTrue(secured.contains("</head>"))
    }

    func testCSPInserterFailsClosedBeforeScriptAndStyleRawTextHeadDecoys() throws {
        let html = """
        <!doctype html><html>
        <script>const decoy = '<head data-script="true">';</script>
        <style>.sample::before { content: '<head data-style="true">'; }</style>
        <HeAd data-real="true"><title>Report</title></HeAd><body></body></html>
        """

        let secured = PersonalAppHTMLCSPInserter.secure(html)
        let csp = try XCTUnwrap(secured.range(of: "Content-Security-Policy"))
        let script = try XCTUnwrap(secured.range(of: "<script>"))
        let style = try XCTUnwrap(secured.range(of: "<style>"))

        XCTAssertLessThan(csp.upperBound, script.lowerBound)
        XCTAssertLessThan(csp.upperBound, style.lowerBound)
        XCTAssertTrue(secured.contains("const decoy = '<head data-script=\"true\">'"))
        XCTAssertTrue(secured.contains("content: '<head data-style=\"true\">'"))
        XCTAssertEqual(secured.components(separatedBy: "Content-Security-Policy").count - 1, 1)
    }

    func testCSPInserterFailsClosedBeforeTemplateTextareaAndTitleHeadDecoys() throws {
        let decoys = [
            "<template><head data-decoy='template'></head></template>",
            "<textarea><head data-decoy='textarea'></textarea>",
            "<title><head data-decoy='title'></title>",
            "visible prefix text <head data-decoy='text'>"
        ]

        for decoy in decoys {
            let html = "<!doctype html><html>\(decoy)<HeAd data-real='true'></HeAd><body></body></html>"
            let secured = PersonalAppHTMLCSPInserter.secure(html)
            let csp = try XCTUnwrap(secured.range(of: "Content-Security-Policy"))
            let decoyRange = try XCTUnwrap(secured.range(of: decoy))

            XCTAssertLessThan(csp.upperBound, decoyRange.lowerBound, decoy)
            XCTAssertTrue(secured.contains(decoy), decoy)
            XCTAssertEqual(
                secured.components(separatedBy: "Content-Security-Policy").count - 1,
                1,
                decoy
            )
        }
    }

    func testCSPInserterAllowsOnlyDocumentPrefixBeforeExplicitHead() throws {
        let html = "\u{FEFF} \n<!-- before doctype --><!DoCtYpE html>\n<!-- before html -->"
            + "<HtMl lang='en'>\n<!-- before head --><HeAd data-real='true'><title>Report</title></HeAd>"

        let secured = PersonalAppHTMLCSPInserter.secure(html)
        let explicitHead = try XCTUnwrap(secured.range(of: "<HeAd data-real='true'>"))
        let csp = try XCTUnwrap(secured.range(of: "Content-Security-Policy"))
        let title = try XCTUnwrap(secured.range(of: "<title>"))

        XCTAssertGreaterThan(csp.lowerBound, explicitHead.upperBound)
        XCTAssertLessThan(csp.upperBound, title.lowerBound)
    }

    func testCSPRetainsOfflineDirectivesAndDeniesBaseURI() {
        let policy = PersonalAppHTMLCSPInserter.policy

        for directive in [
            "default-src 'none'",
            "connect-src 'none'",
            "form-action 'none'",
            "frame-src 'none'",
            "base-uri 'none'",
            "object-src 'none'",
            "worker-src 'none'"
        ] {
            XCTAssertTrue(policy.contains(directive), directive)
        }
    }

    func testValidatedStateAccessRejectsInvalidSaveWithoutWriting() {
        var writes: [(Data, String)] = []
        let access = PersonalAppValidatedStateAccess(
            loadRaw: { _ in nil },
            saveRaw: { data, artifactID in writes.append((data, artifactID)) }
        )

        XCTAssertFalse(access.save(
            Data(#"{"food":"2800"}"#.utf8),
            artifactID: artifactID,
            schema: budgetSchema
        ))
        XCTAssertTrue(writes.isEmpty)
    }

    func testValidatedStateAccessPersistsValidSave() {
        let validState = Data(#"{"food":2800}"#.utf8)
        var writes: [(Data, String)] = []
        let access = PersonalAppValidatedStateAccess(
            loadRaw: { _ in nil },
            saveRaw: { data, artifactID in writes.append((data, artifactID)) }
        )

        XCTAssertTrue(access.save(
            validState,
            artifactID: artifactID,
            schema: budgetSchema
        ))
        XCTAssertEqual(writes.count, 1)
        XCTAssertEqual(writes.first?.0, validState)
        XCTAssertEqual(writes.first?.1, artifactID)
    }

    func testValidatedStateAccessDistinguishesMissingInvalidAndValidState() {
        let invalidState = Data(#"{"food":"stale"}"#.utf8)
        let validState = Data(#"{"food":2800}"#.utf8)
        let missingAccess = PersonalAppValidatedStateAccess(
            loadRaw: { _ in nil },
            saveRaw: { _, _ in XCTFail("Load must not write") }
        )
        let invalidAccess = PersonalAppValidatedStateAccess(
            loadRaw: { _ in invalidState },
            saveRaw: { _, _ in XCTFail("Load must not write") }
        )
        let validAccess = PersonalAppValidatedStateAccess(
            loadRaw: { _ in validState },
            saveRaw: { _, _ in XCTFail("Load must not write") }
        )

        XCTAssertEqual(
            missingAccess.loadResult(artifactID: artifactID, schema: budgetSchema),
            .missing
        )
        XCTAssertEqual(
            invalidAccess.loadResult(artifactID: artifactID, schema: budgetSchema),
            .invalid
        )
        XCTAssertEqual(
            validAccess.loadResult(artifactID: artifactID, schema: budgetSchema),
            .valid(validState)
        )
    }

    func testBridgeRateLimiterAllowsOnlyOneRapidSaveThenRecovers() {
        var limiter = PersonalAppBridgeRateLimiter()
        var writes = 0

        for now in [10.0, 10.05, 10.1, 10.49] {
            if limiter.shouldAccept(.saveState, now: now) {
                writes += 1
            }
        }
        XCTAssertEqual(writes, 1)
        XCTAssertTrue(limiter.shouldAccept(.saveState, now: 10.5))
    }

    func testBridgeRateLimiterSharesConfirmationBucketForSubmitAndOpen() {
        var limiter = PersonalAppBridgeRateLimiter()

        XCTAssertTrue(limiter.shouldAccept(.submitSnapshot, now: 20))
        XCTAssertFalse(limiter.shouldAccept(.requestOpenURL, now: 20.1))
        XCTAssertFalse(limiter.shouldAccept(.submitSnapshot, now: 21.99))
        XCTAssertTrue(limiter.shouldAccept(.requestOpenURL, now: 22))
    }

    func testPermissionPolicyDeniesFileMediaAndMotionCapabilities() {
        XCTAssertNil(PersonalAppWebPermissionPolicy.openPanelResult)
        XCTAssertEqual(PersonalAppWebPermissionPolicy.permissionDecision, .deny)
        for token in [
            "HTMLElement.prototype",
            "HTMLInputElement.prototype",
            "Event.prototype.composedPath",
            "EventTarget.prototype.addEventListener",
            "Reflect.apply",
            "window",
            "showPicker",
            "MutationObserver",
            "preventDefault",
            "capture"
        ] {
            XCTAssertTrue(PersonalAppFileInputGuard.script.contains(token), token)
        }
    }

    func testClipboardGuardCapturesNativeMethodsBeforeArtifactTampering() {
        for token in [
            "Reflect.apply",
            "Set.prototype.has",
            "const nativeString = String",
            "String.prototype.toLowerCase",
            "EventTarget.prototype.addEventListener",
            "EventTarget.prototype.dispatchEvent",
            "Event.prototype.preventDefault",
            "Event.prototype.stopImmediatePropagation",
            "window"
        ] {
            XCTAssertTrue(PersonalAppClipboardGuard.script.contains(token), token)
        }
    }

    func testNavigationGateAllowsOnlyTheFirstMainFrameAboutDocument() {
        var gate = PersonalAppInitialNavigationGate()

        XCTAssertFalse(gate.shouldAllow(
            isOtherNavigation: true,
            isMainFrame: true,
            scheme: "https"
        ))
        XCTAssertTrue(gate.shouldAllow(
            isOtherNavigation: true,
            isMainFrame: true,
            scheme: "about"
        ))
        XCTAssertFalse(gate.shouldAllow(
            isOtherNavigation: true,
            isMainFrame: true,
            scheme: "about"
        ))
        XCTAssertFalse(gate.shouldAllow(
            isOtherNavigation: false,
            isMainFrame: true,
            scheme: "about"
        ))
        gate.rearmForSecuredDocument()
        XCTAssertTrue(gate.shouldAllow(
            isOtherNavigation: true,
            isMainFrame: true,
            scheme: "about"
        ))
        XCTAssertFalse(gate.shouldAllow(
            isOtherNavigation: true,
            isMainFrame: true,
            scheme: "about"
        ))
    }

    func testBootstrapScriptJSONEncodesArtifactIdentity() throws {
        let artifactID = "artifact-'quote-\"double\nline"
        let encodedArtifactID = try XCTUnwrap(
            String(data: JSONEncoder().encode(artifactID), encoding: .utf8)
        )

        let script = PersonalAppBootstrapScriptBuilder.build(
            artifactID: artifactID,
            initialData: ["food": .number("2600")],
            savedState: nil
        )

        XCTAssertTrue(script.contains("const skillForgeArtifactID = \(encodedArtifactID);"))
        XCTAssertFalse(script.contains("artifactId:'"))
        XCTAssertFalse(script.contains(artifactID))
        for token in [
            "Document.prototype",
            "execCommand",
            "Clipboard",
            "readText",
            "writeText",
            "preventDefault",
            "configurable: false"
        ] {
            XCTAssertTrue(script.contains(token), token)
        }
    }

    func testConfirmationStateIsFirstWinsUntilCancelled() throws {
        let firstURL = try XCTUnwrap(URL(string: "https://example.com/first"))
        let secondURL = try XCTUnwrap(URL(string: "https://example.com/second"))
        var state = PersonalAppConfirmationState()

        XCTAssertTrue(state.propose(.externalURL(firstURL), now: 30))
        XCTAssertFalse(state.propose(.externalURL(secondURL), now: 30.1))
        XCTAssertEqual(state.pending, .externalURL(firstURL))

        state.cancel(now: 30.2)
        XCTAssertNil(state.pending)
        XCTAssertFalse(state.propose(.externalURL(secondURL), now: 31))
        XCTAssertTrue(state.propose(
            .snapshot(Data(#"{"food":2800}"#.utf8)),
            now: 32.2
        ))
    }

    @MainActor
    func testExternalURLIsOpenedExactlyOnceOnlyAfterConsumption() throws {
        let url = try XCTUnwrap(URL(string: "https://example.com:8443/report?q=1#result"))
        let spy = ExternalURLOpenerSpy()
        let opener = PersonalAppExternalURLOpener { openedURL in
            spy.openedURLs.append(openedURL)
        }
        var state = PersonalAppConfirmationState()
        XCTAssertTrue(state.propose(.externalURL(url)))

        XCTAssertEqual(spy.openedURLs, [])
        if let acceptedURL = state.takeExternalURL() {
            opener.open(acceptedURL)
        }
        XCTAssertEqual(spy.openedURLs, [url])
        XCTAssertNil(state.takeExternalURL())
        XCTAssertEqual(spy.openedURLs, [url])
    }
}

@MainActor
private final class ExternalURLOpenerSpy {
    var openedURLs: [URL] = []
}
