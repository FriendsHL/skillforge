import XCTest
@testable import SkillForge

final class MobileRealtimeStateTests: XCTestCase {
    func testIgnoresLegacyAssistantDeltaWhenTextDeltaCarriesSameChunk() {
        var state = MobileRealtimeState()

        state.applyTextDelta(type: "assistant_delta", chunk: "## 标题\n")
        state.applyTextDelta(type: "text_delta", chunk: "## 标题\n")

        XCTAssertEqual(state.streamingText, "## 标题\n")
    }

    func testKeepsConsecutiveIdenticalChunksFromCanonicalDeltaSource() {
        var state = MobileRealtimeState()

        state.applyTextDelta(type: "assistant_delta", chunk: "\n\n")
        state.applyTextDelta(type: "text_delta", chunk: "\n\n")
        state.applyTextDelta(type: "assistant_delta", chunk: "\n\n")
        state.applyTextDelta(type: "text_delta", chunk: "\n\n")

        XCTAssertEqual(state.streamingText, "\n\n\n\n")
    }

    func testKeepsTransientToolCallAcrossRemoteRefreshWithoutToolCall() {
        var state = MobileRealtimeState()
        state.upsertTool(id: "toolu_1", name: "ReadFile", inputPreview: "{\"path\":\"README.md\"}", output: nil, status: .pending)

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "我先读取文件。")
            ],
            clearStreamingText: true,
            clearToolCalls: true
        )

        XCTAssertEqual(state.streamingToolCalls.count, 1)
        XCTAssertEqual(state.streamingToolCalls[0].id, "toolu_1")
    }

    func testKeepsStreamingTextAcrossRemoteRefreshWithoutAssistantText() {
        var state = MobileRealtimeState()
        state.applyTextDelta(type: "text_delta", chunk: "## 实时标题\n")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .user, text: "整理一个方案")
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertEqual(state.streamingText, "## 实时标题\n")
    }

    func testClearsStreamingTextWhenRemoteRefreshContainsAssistantText() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 3)
        state.applyTextDelta(type: "text_delta", chunk: "## 实时标题\n")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "## 实时标题\n完整内容", remoteSeqNo: 4)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertTrue(state.streamingText.isEmpty)
    }

    func testCommittedRemoteMessageConsumesPendingThrottledTextBeforeHandoff() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 10)
        state.applyTextDelta(type: "text_delta", chunk: "最终")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "最终回复", remoteSeqNo: 11)
            ],
            pendingText: "回复",
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertTrue(state.streamingText.isEmpty)
    }

    func testOldIdenticalAssistantTextCannotConsumeCurrentTurn() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 20)
        state.applyTextDelta(type: "assistant_delta", chunk: "相同回复")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "相同回复", remoteSeqNo: 18)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertEqual(state.streamingText, "相同回复")
    }

    func testConsumesPersistedAssistantIterationsIncrementally() {
        var state = MobileRealtimeState()
        state.beginStreaming(after: 30)
        state.applyTextDelta(type: "assistant_delta", chunk: "正在检查")
        state.finishStreamSegment()
        state.applyTextDelta(type: "assistant_delta", chunk: "检查完成")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "正在检查", remoteSeqNo: 31)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )
        XCTAssertEqual(state.streamingText, "检查完成")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .assistant, text: "正在检查", remoteSeqNo: 31),
                ChatMessage(role: .assistant, text: "检查完成", remoteSeqNo: 34)
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )
        XCTAssertTrue(state.streamingText.isEmpty)
    }

    func testKeepsStreamingTextWhenRemoteRefreshOnlyContainsOlderAssistantText() {
        var state = MobileRealtimeState()
        state.applyTextDelta(type: "text_delta", chunk: "## 新任务\n")

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(role: .user, text: "上一个任务"),
                ChatMessage(role: .assistant, text: "这是历史回复")
            ],
            clearStreamingText: true,
            clearToolCalls: false
        )

        XCTAssertEqual(state.streamingText, "## 新任务\n")
    }

    func testClearsTransientToolCallWhenRemoteRefreshContainsSameToolCall() {
        var state = MobileRealtimeState()
        state.upsertTool(id: "toolu_1", name: "ReadFile", inputPreview: "{\"path\":\"README.md\"}", output: nil, status: .pending)

        state.applyRemoteRefresh(
            remoteMessages: [
                ChatMessage(
                    role: .assistant,
                    text: "我先读取文件。",
                    toolCalls: [
                        ChatMessage.ToolCall(
                            id: "toolu_1",
                            name: "ReadFile",
                            inputPreview: "{\"path\":\"README.md\"}",
                            output: nil,
                            status: .pending
                        )
                    ]
                )
            ],
            clearStreamingText: true,
            clearToolCalls: true
        )

        XCTAssertTrue(state.streamingToolCalls.isEmpty)
    }
}
