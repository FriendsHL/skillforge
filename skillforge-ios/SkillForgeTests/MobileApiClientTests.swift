import Foundation
import XCTest
@testable import SkillForge

final class MobileApiClientTests: XCTestCase {
    override func tearDown() {
        URLProtocolStub.requestHandler = nil
        super.tearDown()
    }

    func testClaimsPairingWithOneTimeSecret() async throws {
        let payload = try PairingPayload.decode(from: """
        {
          "type": "skillforge.mobile_pairing",
          "version": 1,
          "serverName": "SkillForge Dev",
          "pairingId": "pairing-1",
          "pairingSecret": "secret",
          "endpoints": ["http://127.0.0.1:8080"],
          "expiresAt": "2026-07-09T06:05:00Z"
        }
        """)

        nonisolated(unsafe) var observedPath: String?
        nonisolated(unsafe) var observedBody: [String: Any]?

        URLProtocolStub.requestHandler = { request in
            observedPath = request.url?.path
            observedBody = try Self.requestBodyJSON(request)
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let data = Data("""
            {
              "deviceId": "device-1",
              "deviceToken": "device-token",
              "serverName": "SkillForge Dev",
              "user": { "id": 1 },
              "defaultAgent": { "id": 1, "name": "Main Assistant" },
              "features": { "chat": true, "attachments": false, "push": false }
            }
            """.utf8)
            return (response, data)
        }

        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            session: EndpointProbeTests.stubbedSession()
        )
        let response = try await client.claimPairing(payload: payload, deviceName: "iPhone", appVersion: "1.0")

        XCTAssertEqual(response.deviceToken, "device-token")
        XCTAssertEqual(observedPath, "/api/mobile/pairings/pairing-1/claim")
        XCTAssertEqual(observedBody?["pairingSecret"] as? String, "secret")
        XCTAssertEqual(observedBody?["deviceName"] as? String, "iPhone")
        XCTAssertEqual(observedBody?["platform"] as? String, "ios")
    }

    func testRegistersAPNsTokenWithAuthorizedMobileEndpoint() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data("""
            {
              "id": "00000000-0000-0000-0000-000000000001",
              "environment": "development",
              "status": "active",
              "registeredAt": "2026-07-16T09:00:00Z"
            }
            """.utf8))
        }
        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        let result = try await client.registerPushToken("ab" + String(repeating: "01", count: 31), environment: "development")

        let request = try XCTUnwrap(observedRequest)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/mobile/client/push-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        let body = try XCTUnwrap(Self.requestBodyJSON(request))
        XCTAssertEqual(body["environment"] as? String, "development")
        XCTAssertEqual(result.status, "active")
    }

    func testUsesMobileChatSessionEndpoints() async throws {
        nonisolated(unsafe) var observedRequests: [(method: String?, path: String?, auth: String?, body: [String: Any]?)] = []

        URLProtocolStub.requestHandler = { request in
            let observedBody = try Self.requestBodyJSON(request)
            observedRequests.append((
                method: request.httpMethod,
                path: request.url?.path,
                auth: request.value(forHTTPHeaderField: "Authorization"),
                body: observedBody
            ))

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: request.url?.path.hasSuffix("/messages") == true && request.httpMethod == "POST" ? 202 : 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let data: Data
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/mobile/client/sessions"):
                data = Data("""
                [
                  {
                    "id": "session-1",
                    "agentId": 3,
                    "title": "Mobile session",
                    "status": "active",
                    "runtimeStatus": "idle",
                    "messageCount": 1
                  }
                ]
                """.utf8)
            case ("POST", "/api/mobile/client/sessions"):
                data = Data("""
                {
                  "id": "session-2",
                  "agentId": 3,
                  "title": "New Session",
                  "status": "active",
                  "runtimeStatus": "idle",
                  "messageCount": 0
                }
                """.utf8)
            case ("GET", "/api/mobile/client/sessions/session-1/messages"):
                data = Data("""
                [
                  {
                    "seqNo": 1,
                    "role": "assistant",
                    "content": "Hello from SkillForge",
                    "msgType": "normal",
                    "messageType": "normal",
                    "createdAt": "2026-07-10T06:00:00Z"
                  }
                ]
                """.utf8)
            case ("POST", "/api/mobile/client/sessions/session-1/messages"):
                data = Data("""
                { "sessionId": "session-1", "status": "accepted" }
                """.utf8)
            default:
                data = Data("{}".utf8)
            }
            return (response, data)
        }

        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        let sessions = try await client.listSessions()
        let created = try await client.createSession(agentId: 3)
        let messages = try await client.getMessages(sessionId: "session-1")
        let accepted = try await client.sendMessage(sessionId: "session-1", text: "hello")

        XCTAssertEqual(sessions.first?.id, "session-1")
        XCTAssertEqual(created.id, "session-2")
        XCTAssertEqual(messages.first?.displayText, "Hello from SkillForge")
        XCTAssertEqual(accepted.status, "accepted")
        XCTAssertEqual(observedRequests.map(\.path), [
            "/api/mobile/client/sessions",
            "/api/mobile/client/sessions",
            "/api/mobile/client/sessions/session-1/messages",
            "/api/mobile/client/sessions/session-1/messages"
        ])
        XCTAssertTrue(observedRequests.allSatisfy { $0.auth == "Bearer device-token" })
        XCTAssertEqual(observedRequests[1].body?["agentId"] as? Int, 3)
        XCTAssertEqual(observedRequests[3].body?["message"] as? String, "hello")
    }

    func testListAgentsUsesAuthorizedEndpointAndDecodesSafeConfigurationSummary() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data(Self.agentListJSON.utf8))
        }

        let agents = try await agentClient().listAgents()

        let request = try XCTUnwrap(observedRequest)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/mobile/client/agents")
        XCTAssertNil(request.url?.query)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        XCTAssertNil(try Self.requestBodyJSON(request))

        let agent = try XCTUnwrap(agents.first)
        XCTAssertEqual(agent.id, 2)
        XCTAssertEqual(agent.name, "Release Agent")
        XCTAssertEqual(agent.description, "Coordinates release readiness and change review.")
        XCTAssertEqual(agent.role, "release-manager")
        XCTAssertEqual(agent.modelId, "ark:glm-5.2")
        XCTAssertEqual(agent.status, "active")
        XCTAssertEqual(agent.source, "owned")
        XCTAssertEqual(agent.visibility, "private")
        XCTAssertFalse(agent.isDefault)
        XCTAssertEqual(agent.executionMode, "agent_loop")
        XCTAssertEqual(agent.skillCount, 2)
        XCTAssertEqual(agent.toolCount, 2)
        XCTAssertEqual(agent.toolAccess, .allowlist)
        XCTAssertEqual(agent.configurationAccess, "detail")

        let unrestrictedAgent = try XCTUnwrap(agents.dropFirst().first)
        XCTAssertEqual(unrestrictedAgent.id, 3)
        XCTAssertEqual(unrestrictedAgent.name, "General Agent")
        XCTAssertEqual(unrestrictedAgent.toolAccess, .all)
        XCTAssertEqual(unrestrictedAgent.toolCount, 0)
    }

    func testGetAgentUsesAuthorizedDetailEndpointAndDecodesNormalizedSafeConfiguration() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data(Self.agentDetailJSON.utf8))
        }

        let detail = try await agentClient().getAgent(id: 2)

        let request = try XCTUnwrap(observedRequest)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/mobile/client/agents/2")
        XCTAssertNil(request.url?.query)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        XCTAssertNil(try Self.requestBodyJSON(request))

        XCTAssertEqual(detail.id, 2)
        XCTAssertEqual(detail.name, "Release Agent")
        XCTAssertEqual(detail.modelId, "ark:glm-5.2")
        XCTAssertEqual(detail.maxLoops, 18)
        XCTAssertEqual(detail.thinkingMode, "enabled")
        XCTAssertEqual(detail.reasoningEffort, "medium")
        XCTAssertEqual(detail.skillNames, ["release-planning", "change-review"])
        XCTAssertEqual(detail.toolAccess, .allowlist)
        XCTAssertEqual(detail.toolNames, ["ReadFile", "Bash"])
        XCTAssertEqual(detail.enabledSystemSkillCount, 1)
        XCTAssertTrue(detail.promptMetadata?.agent.configured == true)
        XCTAssertEqual(detail.promptMetadata?.agent.characterCount, 1240)
        XCTAssertTrue(detail.promptMetadata?.soul.configured == true)
        XCTAssertEqual(detail.promptMetadata?.soul.characterCount, 320)
        XCTAssertTrue(detail.promptMetadata?.tools.configured == false)
        XCTAssertEqual(detail.promptMetadata?.tools.characterCount, 0)

        let wireObject = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(Self.agentDetailJSON.utf8)) as? [String: Any]
        )
        for forbiddenKey in [
            "systemPrompt", "agentPromptText", "soulPromptText", "toolsPromptText",
            "credentials", "lifecycleHooks", "ownerId", "config"
        ] {
            XCTAssertNil(wireObject[forbiddenKey], "Mobile Agent detail must not expose \(forbiddenKey)")
        }
    }

    func testAgentToolAccessUnknownValuesFailClosed() throws {
        let data = Data("""
        [
          {"id":1,"name":"Missing","toolCount":3},
          {"id":2,"name":"Future","toolCount":3,"toolAccess":"future-policy"},
          {"id":3,"name":"Wrong type","toolCount":3,"toolAccess":true}
        ]
        """.utf8)

        let agents = try JSONDecoder().decode([MobileAgentCatalogItem].self, from: data)

        XCTAssertEqual(agents.map(\.toolAccess), [.unknown, .unknown, .unknown])
    }

    func testListSchedulesUsesAuthorizedMobileEndpointAndDecodesPresentationFields() async throws {
        nonisolated(unsafe) var observedRequests: [(
            method: String?,
            path: String?,
            query: String?,
            auth: String?,
            body: [String: Any]?
        )] = []

        URLProtocolStub.requestHandler = { request in
            observedRequests.append((
                method: request.httpMethod,
                path: request.url?.path,
                query: request.url?.query,
                auth: request.value(forHTTPHeaderField: "Authorization"),
                body: try Self.requestBodyJSON(request)
            ))

            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: request.url?.path.hasSuffix("/trigger") == true ? 202 : 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let data: Data
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/mobile/client/schedules"):
                data = Data(Self.scheduleJSON(enabled: true).utf8)
            case ("GET", "/api/mobile/client/schedules/42/runs"):
                data = Data("""
                [
                  {
                    "id": 901,
                    "taskId": 42,
                    "triggeredAt": "2026-07-11T02:00:00Z",
                    "finishedAt": "2026-07-11T02:01:30Z",
                    "status": "success",
                    "errorMessage": null,
                    "sessionId": "schedule-session-1",
                    "manual": true
                  }
                ]
                """.utf8)
            case ("POST", "/api/mobile/client/schedules/42/trigger"):
                data = Data("""
                { "taskId": 42, "status": "trigger_requested" }
                """.utf8)
            case ("PUT", "/api/mobile/client/schedules/42/enabled"):
                data = Data(Self.scheduleJSON(enabled: false, wrappedInArray: false).utf8)
            default:
                XCTFail("Unexpected schedule request: \(request.httpMethod ?? "nil") \(request.url?.absoluteString ?? "nil")")
                data = Data("{}".utf8)
            }
            return (response, data)
        }

        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        let schedules = try await client.listSchedules()
        let schedule = try XCTUnwrap(schedules.first)
        XCTAssertEqual(schedule.id, 42)
        XCTAssertEqual(schedule.name, "Nightly review")
        XCTAssertEqual(schedule.agentId, 3)
        XCTAssertEqual(schedule.cronExpr, "0 0 2 * * *")
        XCTAssertNil(schedule.oneShotAt)
        XCTAssertEqual(schedule.timezone, "Asia/Shanghai")
        XCTAssertEqual(schedule.promptPreview, "Review pending releases")
        XCTAssertEqual(schedule.sessionMode, "new_session")
        XCTAssertTrue(schedule.enabled)
        XCTAssertEqual(schedule.nextFireAt, "2026-07-12T02:00:00Z")
        XCTAssertEqual(schedule.lastFireAt, "2026-07-11T02:00:00Z")
        XCTAssertEqual(schedule.status, "active")
        XCTAssertFalse(schedule.system)

        XCTAssertEqual(observedRequests.map(\.method), ["GET"])
        XCTAssertEqual(observedRequests.map(\.path), ["/api/mobile/client/schedules"])
        XCTAssertNil(observedRequests[0].query)
        XCTAssertTrue(observedRequests.allSatisfy { $0.auth == "Bearer device-token" })
        XCTAssertNil(observedRequests[0].body)
    }

    func testListScheduleRunsUsesLimitQueryAndDecodesChatRoute() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data("""
            [
              {
                "id": 901,
                "taskId": 42,
                "triggeredAt": "2026-07-11T02:00:00Z",
                "finishedAt": "2026-07-11T02:01:30Z",
                "status": "success",
                "errorMessage": null,
                "sessionId": "schedule-session-1",
                "manual": true
              }
            ]
            """.utf8))
        }

        let runs = try await scheduleClient().listScheduleRuns(taskId: 42, limit: 50)

        let request = try XCTUnwrap(observedRequest)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/mobile/client/schedules/42/runs")
        XCTAssertEqual(request.url?.query, "limit=50")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        XCTAssertNil(try Self.requestBodyJSON(request))
        let run = try XCTUnwrap(runs.first)
        XCTAssertEqual(run.id, 901)
        XCTAssertEqual(run.taskId, 42)
        XCTAssertEqual(run.triggeredAt, "2026-07-11T02:00:00Z")
        XCTAssertEqual(run.finishedAt, "2026-07-11T02:01:30Z")
        XCTAssertEqual(run.status, "success")
        XCTAssertNil(run.errorMessage)
        XCTAssertEqual(run.sessionId, "schedule-session-1")
        XCTAssertTrue(run.manual)
    }

    func testTriggerSchedulePostsAuthorizedEmptyRequestAndDecodesAcceptedStatus() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 202,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data("""
            { "taskId": 42, "status": "trigger_requested" }
            """.utf8))
        }

        let trigger = try await scheduleClient().triggerSchedule(taskId: 42)

        let request = try XCTUnwrap(observedRequest)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/mobile/client/schedules/42/trigger")
        XCTAssertNil(request.url?.query)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        XCTAssertNil(try Self.requestBodyJSON(request))
        XCTAssertEqual(trigger, MobileScheduleActionResponse(taskId: 42, status: "trigger_requested"))
    }

    func testSetScheduleEnabledPutsOnlyBooleanAndDecodesUpdatedSchedule() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data(Self.scheduleJSON(enabled: false, wrappedInArray: false).utf8))
        }

        let updated = try await scheduleClient().setScheduleEnabled(taskId: 42, enabled: false)

        let request = try XCTUnwrap(observedRequest)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.path, "/api/mobile/client/schedules/42/enabled")
        XCTAssertNil(request.url?.query)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        let body = try XCTUnwrap(Self.requestBodyJSON(request))
        XCTAssertEqual(body.count, 1)
        XCTAssertEqual(body["enabled"] as? Bool, false)
        XCTAssertFalse(updated.enabled)
        XCTAssertEqual(updated.id, 42)
    }

    func testListsMobileSafeAgentCatalogWithDeviceAuthorization() async throws {
        nonisolated(unsafe) var observedPath: String?
        nonisolated(unsafe) var observedAuthorization: String?
        URLProtocolStub.requestHandler = { request in
            observedPath = request.url?.path
            observedAuthorization = request.value(forHTTPHeaderField: "Authorization")
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data("""
            [
              {
                "id": 3,
                "name": "Main Assistant",
                "description": "Default assistant",
                "status": "active",
                "isDefault": true,
                "toolAccess": "all"
              }
            ]
            """.utf8))
        }

        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        let agents = try await client.listAgents()

        XCTAssertEqual(observedPath, "/api/mobile/client/agents")
        XCTAssertEqual(observedAuthorization, "Bearer device-token")
        XCTAssertEqual(agents, [
            MobileAgentCatalogItem(
                id: 3,
                name: "Main Assistant",
                description: "Default assistant",
                isDefault: true,
                toolAccess: .all
            )
        ])
    }

    func testDecodesBootstrapDefaultAgent() async throws {
        URLProtocolStub.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let data = Data("""
            {
              "user": { "id": 1 },
              "device": { "id": "device-1", "deviceName": "iPhone", "scopes": ["chat:read"] },
              "defaultAgent": { "id": 3, "name": "Main Assistant" },
              "features": { "chat": true, "attachments": true, "push": false, "realtime": false }
            }
            """.utf8)
            return (response, data)
        }

        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        let response = try await client.me()

        XCTAssertEqual(response.defaultAgent?.id, 3)
        XCTAssertEqual(response.defaultAgent?.name, "Main Assistant")
    }

    func testDecodesBootstrapDefaultAgentWithoutId() async throws {
        URLProtocolStub.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let data = Data("""
            {
              "user": { "id": 1 },
              "device": { "id": "device-1", "deviceName": "iPhone", "scopes": ["chat:read"] },
              "defaultAgent": { "id": null, "name": "Main Assistant" },
              "features": { "chat": true, "attachments": true, "push": false, "realtime": false }
            }
            """.utf8)
            return (response, data)
        }

        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        let response = try await client.me()

        XCTAssertNil(response.defaultAgent?.id)
        XCTAssertEqual(response.defaultAgent?.name, "Main Assistant")
    }

    func testBuildsAuthorizedMobileChatWebSocketRequestWithoutQueryToken() throws {
        let client = MobileApiClient(
            baseURL: URL(string: "http://192.168.1.6:3000")!,
            deviceToken: "token value",
            session: EndpointProbeTests.stubbedSession()
        )

        let request = try client.chatWebSocketRequest(sessionId: "session-1")
        let url = try XCTUnwrap(request.url)

        XCTAssertEqual(url.scheme, "ws")
        XCTAssertEqual(url.host, "192.168.1.6")
        XCTAssertEqual(url.port, 3000)
        XCTAssertEqual(url.path, "/ws/mobile/chat/session-1")
        XCTAssertNil(URLComponents(url: url, resolvingAgainstBaseURL: false)?.query)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer token value")
    }

    func testUsesMobileInteractionEndpoints() async throws {
        nonisolated(unsafe) var requests: [(path: String, body: [String: Any])] = []
        URLProtocolStub.requestHandler = { request in
            requests.append((
                request.url!.path,
                try Self.requestBodyJSON(request) ?? [:]
            ))
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data("{\"status\":\"ok\"}".utf8))
        }
        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        _ = try await client.answerAsk(sessionId: "session-1", askId: "ask-1", answer: "测试环境")
        _ = try await client.answerConfirmation(
            sessionId: "session-1",
            confirmationId: "confirmation-1",
            decision: .approved
        )

        XCTAssertEqual(requests.map(\.path), [
            "/api/mobile/client/sessions/session-1/answer",
            "/api/mobile/client/sessions/session-1/confirmation"
        ])
        XCTAssertEqual(requests[0].body["askId"] as? String, "ask-1")
        XCTAssertEqual(requests[0].body["answer"] as? String, "测试环境")
        XCTAssertEqual(requests[1].body["confirmationId"] as? String, "confirmation-1")
        XCTAssertEqual(requests[1].body["decision"] as? String, "approved")
    }

    func testUploadsAttachmentAsAuthorizedMultipartRequest() async throws {
        nonisolated(unsafe) var observedRequest: URLRequest?
        URLProtocolStub.requestHandler = { request in
            observedRequest = request
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let data = Data("""
            {
              "id": "attachment-1",
              "sessionId": "session-1",
              "kind": "image",
              "mimeType": "image/png",
              "filename": "screen.png",
              "sizeBytes": 4,
              "pageCount": null,
              "status": "uploaded"
            }
            """.utf8)
            return (response, data)
        }
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("screen-(UUID().uuidString).png")
        try Data([0x89, 0x50, 0x4E, 0x47]).write(to: fileURL)
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let client = MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )

        let attachment = try await client.uploadAttachment(
            sessionId: "session-1",
            fileURL: fileURL,
            mimeType: "image/png"
        )

        XCTAssertEqual(attachment.id, "attachment-1")
        XCTAssertEqual(observedRequest?.url?.path, "/api/mobile/client/sessions/session-1/attachments")
        XCTAssertEqual(observedRequest?.value(forHTTPHeaderField: "Authorization"), "Bearer device-token")
        XCTAssertTrue(observedRequest?.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/form-data; boundary=") == true)
        let body = try XCTUnwrap(Self.requestBodyData(observedRequest!))
        let bodyText = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(bodyText.contains("name=\"file\""))
        XCTAssertTrue(bodyText.contains("filename=\"\(fileURL.lastPathComponent)\""))
        XCTAssertTrue(bodyText.contains("Content-Type: image/png"))
    }

    private static func requestBodyJSON(_ request: URLRequest) throws -> [String: Any]? {
        guard let data = requestBodyData(request) else { return nil }
        guard !data.isEmpty else { return nil }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func scheduleClient() -> MobileApiClient {
        MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )
    }

    private func agentClient() -> MobileApiClient {
        MobileApiClient(
            baseURL: URL(string: "http://127.0.0.1:8080")!,
            deviceToken: "device-token",
            session: EndpointProbeTests.stubbedSession()
        )
    }

    private static let agentListJSON = """
    [
      {
        "id": 2,
        "name": "Release Agent",
        "description": "Coordinates release readiness and change review.",
        "role": "release-manager",
        "modelId": "ark:glm-5.2",
        "status": "active",
        "source": "owned",
        "visibility": "private",
        "isDefault": false,
        "executionMode": "agent_loop",
        "skillCount": 2,
        "toolCount": 2,
        "toolAccess": "allowlist",
        "configurationAccess": "detail"
      },
      {
        "id": 3,
        "name": "General Agent",
        "description": "Uses the unrestricted tool catalog.",
        "role": "general",
        "modelId": "claude-sonnet",
        "status": "active",
        "source": "owned",
        "visibility": "private",
        "isDefault": false,
        "executionMode": "agent_loop",
        "skillCount": 0,
        "toolCount": 0,
        "toolAccess": "all",
        "configurationAccess": "detail"
      }
    ]
    """

    private static let agentDetailJSON = """
    {
      "id": 2,
      "name": "Release Agent",
      "description": "Coordinates release readiness and change review.",
      "role": "release-manager",
      "modelId": "ark:glm-5.2",
      "status": "active",
      "source": "owned",
      "visibility": "private",
      "isDefault": false,
      "executionMode": "agent_loop",
      "skillCount": 2,
      "toolCount": 2,
      "toolAccess": "allowlist",
      "configurationAccess": "detail",
      "maxLoops": 18,
      "thinkingMode": "enabled",
      "reasoningEffort": "medium",
      "skillNames": ["release-planning", "change-review"],
      "toolNames": ["ReadFile", "Bash"],
      "enabledSystemSkillCount": 1,
      "promptMetadata": {
        "agent": { "configured": true, "characterCount": 1240 },
        "soul": { "configured": true, "characterCount": 320 },
        "tools": { "configured": false, "characterCount": 0 }
      }
    }
    """

    private static func scheduleJSON(enabled: Bool, wrappedInArray: Bool = true) -> String {
        let schedule = """
        {
          "id": 42,
          "name": "Nightly review",
          "agentId": 3,
          "cronExpr": "0 0 2 * * *",
          "oneShotAt": null,
          "timezone": "Asia/Shanghai",
          "promptPreview": "Review pending releases",
          "sessionMode": "new_session",
          "enabled": \(enabled),
          "nextFireAt": "2026-07-12T02:00:00Z",
          "lastFireAt": "2026-07-11T02:00:00Z",
          "status": "active",
          "system": false
        }
        """
        return wrappedInArray ? "[\(schedule)]" : schedule
    }

    private static func requestBodyData(_ request: URLRequest) -> Data? {
        if let body = request.httpBody {
            return body
        } else if let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 1024)
            var bytes = Data()
            while stream.hasBytesAvailable {
                let read = stream.read(&buffer, maxLength: buffer.count)
                if read <= 0 { break }
                bytes.append(buffer, count: read)
            }
            return bytes
        } else {
            return nil
        }
    }
}
