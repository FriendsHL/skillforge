import Foundation
import XCTest
@testable import SkillForge

final class ChatMessageTests: XCTestCase {
    func testDecodesAttachmentMetadataUsingSnakeAndCamelAliases() throws {
        let message = try decodeMessage("""
        {
          "seqNo": 30,
          "role": "assistant",
          "content": [
            {
              "type": "pdf_ref",
              "attachment_id": "pdf-1",
              "mime_type": "application/pdf",
              "filename": "report.pdf",
              "page_count": 8,
              "file_size": 4096,
              "caption": "Quarterly report"
            },
            {
              "type": "excel_ref",
              "attachmentId": "sheet-1",
              "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              "fileName": "forecast.xlsx",
              "sheetCount": 4,
              "size": 8192
            }
          ]
        }
        """)

        XCTAssertEqual(message.contentBlocks[0].attachment?.id, "pdf-1")
        XCTAssertEqual(message.contentBlocks[0].attachment?.kind, .pdf)
        XCTAssertEqual(message.contentBlocks[0].attachment?.pageCount, 8)
        XCTAssertEqual(message.contentBlocks[0].attachment?.byteSize, 4096)
        XCTAssertEqual(message.contentBlocks[0].attachment?.caption, "Quarterly report")
        XCTAssertEqual(message.contentBlocks[1].attachment?.id, "sheet-1")
        XCTAssertEqual(message.contentBlocks[1].attachment?.kind, .excel)
        XCTAssertEqual(message.contentBlocks[1].attachment?.filename, "forecast.xlsx")
        XCTAssertEqual(message.contentBlocks[1].attachment?.sheetCount, 4)
        XCTAssertEqual(message.contentBlocks[1].attachment?.byteSize, 8192)
    }

    func testNormalizesPureAndMixedAttachmentsWithoutChangingStableMessageIds() throws {
        let pure = try decodeMessage("""
        {
          "seqNo": 31,
          "role": "assistant",
          "content": [
            { "type": "image_ref", "attachmentId": "image-1", "filename": "chart.png" },
            { "type": "word_ref", "attachment_id": "word-1", "filename": "brief.docx" },
            { "type": "future_ref", "attachment_id": "future-1", "filename": "future.bin" }
          ]
        }
        """)
        let mixed = try decodeMessage("""
        {
          "seqNo": 32,
          "role": "user",
          "content": [
            { "type": "text", "text": "Input files" },
            { "type": "csv_ref", "attachment_id": "csv-1", "filename": "input.csv" }
          ]
        }
        """)

        let normalized = ChatMessage.normalize([pure, mixed])

        XCTAssertEqual(normalized.map(\.id), ["remote-31", "remote-32"])
        XCTAssertEqual(normalized[0].attachments.map(\.id), ["image-1", "word-1", "future-1"])
        XCTAssertEqual(normalized[0].attachments.map(\.kind), [.image, .word, .unknown("future_ref")])
        XCTAssertEqual(normalized[1].text, "Input files")
        XCTAssertEqual(normalized[1].attachments.map(\.id), ["csv-1"])
    }

    func testFiltersEmptyNormalMessages() throws {
        let message = try decodeMessage("""
        {
          "seqNo": 1,
          "role": "assistant",
          "content": "",
          "msgType": "normal",
          "messageType": "normal"
        }
        """)

        XCTAssertNil(ChatMessage(message: message))
    }

    func testPreservesLegitimateIdenticalAssistantTurnsWithDifferentSequenceNumbers() throws {
        let first = try decodeMessage("""
        { "seqNo": 40, "role": "assistant", "content": "同意" }
        """)
        let second = try decodeMessage("""
        { "seqNo": 44, "role": "assistant", "content": "同意" }
        """)

        let normalized = ChatMessage.normalize([first, second])

        XCTAssertEqual(normalized.map(\.id), ["remote-40", "remote-44"])
        XCTAssertEqual(normalized.map(\.text), ["同意", "同意"])
    }

    func testStripsSystemReminderFromVisibleUserMessage() throws {
        let message = try decodeMessage("""
        {
          "seqNo": 2,
          "role": "user",
          "content": "<system-reminder>\\nContext: 87% used\\n</system-reminder>\\n\\n你好",
          "msgType": "normal",
          "messageType": "normal"
        }
        """)

        XCTAssertEqual(ChatMessage(message: message)?.text, "你好")
    }

    func testMarkdownParserRemovesMarkdownSyntaxMarkers() throws {
        let rendered = MarkdownText.attributedString(from: "**加粗** 和 `code`")

        XCTAssertEqual(String(rendered.characters), "加粗 和 code")
    }

    func testStreamingMarkdownKeepsCompletedInlineMarkdownWithPartialTail() throws {
        let rendered = MarkdownText.attributedString(from: "**完成** 和 **半截", isStreaming: true)

        XCTAssertEqual(String(rendered.characters), "完成 和 半截")
    }

    func testParsesMarkdownIntoMobileReadableBlocks() throws {
        let blocks = MarkdownBlock.parse("""
        # 执行计划

        我会按 **三步** 处理：

        - 读取配置
        - 运行测试

        ```swift
        let status = "running"
        ```
        """)

        XCTAssertEqual(blocks.map(\.kind), [.heading, .paragraph, .unorderedList, .code])
        XCTAssertEqual(blocks[0].text, "执行计划")
        XCTAssertEqual(blocks[2].items, ["读取配置", "运行测试"])
        XCTAssertEqual(blocks[3].language, "swift")
        XCTAssertEqual(blocks[3].text, "let status = \"running\"")
    }

    func testNormalizesToolUseAndResultIntoAssistantToolCard() throws {
        let assistant = try decodeMessage("""
        {
          "seqNo": 10,
          "role": "assistant",
          "content": [
            { "type": "text", "text": "我先检查配置。" },
            { "type": "tool_use", "id": "toolu_1", "name": "ConfigRead", "input": { "path": "application.yml" } }
          ],
          "msgType": "normal",
          "messageType": "normal"
        }
        """)
        let toolResult = try decodeMessage("""
        {
          "seqNo": 11,
          "role": "user",
          "content": [
            { "type": "tool_result", "tool_use_id": "toolu_1", "content": "ark provider configured", "is_error": false }
          ],
          "msgType": "normal",
          "messageType": "normal"
        }
        """)

        let normalized = ChatMessage.normalize([assistant, toolResult])

        XCTAssertEqual(normalized.count, 1)
        XCTAssertEqual(normalized[0].role, .assistant)
        XCTAssertEqual(normalized[0].text, "我先检查配置。")
        XCTAssertEqual(normalized[0].toolCalls.count, 1)
        XCTAssertEqual(normalized[0].toolCalls[0].id, "toolu_1")
        XCTAssertEqual(normalized[0].toolCalls[0].name, "ConfigRead")
        XCTAssertTrue(normalized[0].toolCalls[0].inputPreview.contains("application.yml"))
        XCTAssertEqual(normalized[0].toolCalls[0].output, "ark provider configured")
        XCTAssertEqual(normalized[0].toolCalls[0].status, .success)
    }

    func testRunningStatusLabelUsesAgentRunningText() throws {
        XCTAssertEqual(
            ChatStatusText.resolve(isAgentRunning: true, isRefreshing: true, runtimeStatus: "running", hasError: false),
            "Agent 运行中"
        )
        XCTAssertEqual(
            ChatStatusText.resolve(isAgentRunning: false, isRefreshing: true, runtimeStatus: "idle", hasError: false),
            "同步中"
        )
    }

    func testBuildsPendingAskFromPersistedControlMetadata() throws {
        let message = try decodeMessage("""
        {
          "seqNo": 20,
          "role": "assistant",
          "content": "请选择发布环境",
          "msgType": "system_event",
          "messageType": "ask_user",
          "controlId": "ask-20",
          "answeredAt": null,
          "metadata": {
            "question": "请选择发布环境",
            "context": "部署前需要确认",
            "allowOther": true,
            "options": [
              { "label": "测试环境", "description": "只部署到 staging" },
              { "label": "生产环境", "description": "部署到 production" }
            ]
          }
        }
        """)

        let pending = PendingInteraction.persisted(from: [message])

        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending[0].id, "ask-20")
        XCTAssertEqual(pending[0].kind, .ask)
        XCTAssertEqual(pending[0].question, "请选择发布环境")
        XCTAssertEqual(pending[0].context, "部署前需要确认")
        XCTAssertEqual(pending[0].options.map(\.label), ["测试环境", "生产环境"])
        XCTAssertTrue(pending[0].allowOther)
        XCTAssertTrue(ChatMessage.normalize([message]).isEmpty)
    }

    func testAnsweredControlIsNotReturnedAsPending() throws {
        let message = try decodeMessage("""
        {
          "seqNo": 21,
          "role": "assistant",
          "content": "是否继续？",
          "msgType": "system_event",
          "messageType": "confirmation",
          "controlId": "confirm-21",
          "answeredAt": "2026-07-10T08:00:00Z",
          "metadata": { "question": "是否继续？" }
        }
        """)

        XCTAssertTrue(PendingInteraction.persisted(from: [message]).isEmpty)
        XCTAssertTrue(ChatMessage.normalize([message]).isEmpty)
    }

    func testBuildsRealtimeConfirmationFromWebSocketPayload() throws {
        let event = try JSONDecoder().decode(MobileChatEvent.self, from: Data("""
        {
          "type": "confirmation_required",
          "sessionId": "session-1",
          "payload": {
            "confirmationId": "confirm-live",
            "sessionId": "session-1",
            "installTool": "npm",
            "installTarget": "marked",
            "commandPreview": "npm install marked",
            "title": "确认安装依赖",
            "description": "Agent 请求安装 marked",
            "choices": [
              { "value": "approved", "label": "批准", "style": "primary" },
              { "value": "denied", "label": "拒绝", "style": "danger" }
            ]
          }
        }
        """.utf8))

        let pending = PendingInteraction(realtimeEvent: event)

        XCTAssertEqual(pending?.id, "confirm-live")
        XCTAssertEqual(pending?.kind, .confirmation)
        XCTAssertEqual(pending?.question, "确认安装依赖")
        XCTAssertEqual(pending?.context, "Agent 请求安装 marked")
    }

    private func decodeMessage(_ json: String) throws -> MobileSessionMessage {
        try JSONDecoder().decode(MobileSessionMessage.self, from: Data(json.utf8))
    }
}
