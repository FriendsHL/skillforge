import XCTest
@testable import SkillForge

final class SessionTaskProgressTests: XCTestCase {
    func testSnapshotDecodesAllStatusesAndPresentationHidesDeleted() throws {
        let snapshot = try JSONDecoder().decode(MobileSessionTaskSnapshot.self, from: Data("""
        {
          "sessionId": "session-1",
          "generatedAt": "2026-08-05T10:00:00Z",
          "tasks": [
            {"taskId":"a","subject":"完成接口","description":"实现接口","activeForm":"正在实现接口","status":"in_progress","owner":null,"blocked":false,"blockedBy":[],"blocks":["b"],"createdAt":"2026-08-05T09:00:00Z","updatedAt":"2026-08-05T09:30:00Z","version":2},
            {"taskId":"b","subject":"补测试","description":"补齐测试","activeForm":null,"status":"pending","owner":"ios","blocked":true,"blockedBy":["a"],"blocks":[],"createdAt":"2026-08-05T09:01:00Z","updatedAt":"2026-08-05T09:20:00Z","version":1},
            {"taskId":"c","subject":"旧任务","description":"不再相关","activeForm":null,"status":"deleted","owner":null,"blocked":false,"blockedBy":[],"blocks":[],"createdAt":"2026-08-05T08:00:00Z","updatedAt":"2026-08-05T09:10:00Z","version":3}
          ]
        }
        """.utf8))

        XCTAssertEqual(snapshot.tasks.count, 3)
        let presentation = SessionTaskProgressPolicy.presentation(for: snapshot.tasks)
        XCTAssertEqual(presentation.visibleTasks.map(\.taskId), ["a", "b"])
        XCTAssertEqual(presentation.completedCount, 0)
        XCTAssertEqual(presentation.totalCount, 2)
        XCTAssertEqual(presentation.currentAction, "正在实现接口")
        XCTAssertEqual(presentation.blockedCount, 1)
    }

    func testReducerUpsertsByTaskIdAndVersionWithoutDeletingMissingTasks() throws {
        let oldA = task(id: "a", status: .pending, version: 1)
        let oldB = task(id: "b", status: .pending, version: 4)
        let newerA = task(id: "a", status: .completed, version: 2)
        let staleB = task(id: "b", status: .completed, version: 3)

        var state = SessionTaskState.ready(sessionID: "session-1", tasks: [oldA, oldB])
        state.merge(snapshot: MobileSessionTaskSnapshot(
            sessionId: "session-1",
            tasks: [newerA, staleB],
            generatedAt: "2026-08-05T10:00:00Z"
        ))
        XCTAssertEqual(state.tasksByID["a"]?.status, .completed)
        XCTAssertEqual(state.tasksByID["b"]?.status, .pending)

        state.merge(snapshot: MobileSessionTaskSnapshot(
            sessionId: "session-1",
            tasks: [],
            generatedAt: "2026-08-05T10:01:00Z"
        ))
        XCTAssertEqual(state.tasksByID.count, 2, "An empty snapshot must not delete known tasks")
    }

    func testSnapshotForAnotherSessionIsIgnored() {
        let existing = task(id: "a", status: .pending, version: 1)
        var state = SessionTaskState.ready(sessionID: "session-1", tasks: [existing])

        state.merge(snapshot: MobileSessionTaskSnapshot(
            sessionId: "session-2",
            tasks: [task(id: "b", status: .completed, version: 1)],
            generatedAt: "2026-08-05T10:00:00Z"
        ))

        XCTAssertEqual(state.tasksByID.keys.sorted(), ["a"])
    }

    func testRelationOnlySnapshotWithSameVersionAndNewerUpdatedAtIsApplied() {
        let blocked = task(
            id: "a", status: .pending, version: 3,
            blocked: true, blockedBy: ["root"], updatedAt: "2026-08-05T09:00:00Z"
        )
        let unblocked = task(
            id: "a", status: .pending, version: 3,
            blocked: false, blockedBy: [], updatedAt: "2026-08-05T09:01:00Z"
        )
        var state = SessionTaskState.ready(sessionID: "session-1", tasks: [blocked])

        state.merge(snapshot: .init(
            sessionId: "session-1",
            tasks: [unblocked],
            generatedAt: "2026-08-05T09:01:00Z"
        ))

        XCTAssertEqual(state.tasksByID["a"]?.blocked, false)
        XCTAssertEqual(state.tasksByID["a"]?.blockedBy, [])
    }

    func testToolCardsSummarizeTaskChangesAndKeepHistoricalTodoFallback() {
        let create = ToolActivityPresentationPolicy.resolve(.init(
            id: "1", name: "TaskCreate", inputPreview: #"{"subject":"Build"}"#,
            output: #"{"success":true,"task":{"subject":"Build","status":"pending","activeForm":"Building"}}"#,
            status: .success
        ))
        let update = ToolActivityPresentationPolicy.resolve(.init(
            id: "2", name: "task_update", inputPreview: #"{"status":"completed"}"#,
            output: #"{"success":true,"task":{"subject":"Build","status":"completed","activeForm":"Building"}}"#,
            status: .success
        ))
        let todo = ToolActivityPresentationPolicy.resolve(.init(
            id: "3", name: "TodoWrite", inputPreview: "legacy todos",
            output: nil, status: .success
        ))

        XCTAssertEqual(create.title, "已创建任务")
        XCTAssertEqual(create.summary, "Build · 待处理")
        XCTAssertEqual(update.title, "已更新任务")
        XCTAssertEqual(update.summary, "Build · 状态：已完成")
        XCTAssertEqual(todo.title, "已记录历史计划")
        XCTAssertEqual(todo.summary, "legacy todos")
    }

    func testTaskUpdateCardDescribesTheFieldsChangedByThisCall() {
        let update = ToolActivityPresentationPolicy.resolve(.init(
            id: "4", name: "TaskUpdate",
            inputPreview: #"{"description":"New acceptance criteria","activeForm":"Reviewing","addBlockedBy":["task-a"]}"#,
            output: #"{"success":true,"task":{"subject":"Build","status":"in_progress","activeForm":"Reviewing"}}"#,
            status: .success
        ))

        XCTAssertEqual(update.summary, "Build · 更新了描述 · 当前动作：Reviewing · 新增依赖 1 项")
    }

    func testTaskUpdateCardDescribesOwnerAndMetadataChanges() {
        let assigned = ToolActivityPresentationPolicy.resolve(.init(
            id: "5", name: "TaskUpdate",
            inputPreview: #"{"owner":"ios","metadata":{"priority":"p0"}}"#,
            output: #"{"success":true,"task":{"subject":"Build","status":"pending","owner":"ios"}}"#,
            status: .success
        ))
        let cleared = ToolActivityPresentationPolicy.resolve(.init(
            id: "6", name: "TaskUpdate",
            inputPreview: #"{"owner":""}"#,
            output: #"{"success":true,"task":{"subject":"Build","status":"pending","owner":null}}"#,
            status: .success
        ))

        XCTAssertEqual(assigned.summary, "Build · 负责人：ios · 更新了元数据")
        XCTAssertEqual(cleared.summary, "Build · 清除负责人")
    }

    private func task(
        id: String,
        status: MobileSessionTaskStatus,
        version: Int64,
        blocked: Bool = false,
        blockedBy: [String] = [],
        updatedAt: String = "2026-08-05T09:00:00Z"
    ) -> MobileSessionTask {
        MobileSessionTask(
            taskId: id,
            subject: id,
            description: "description",
            activeForm: nil,
            status: status,
            owner: nil,
            blocked: blocked,
            blockedBy: blockedBy,
            blocks: [],
            createdAt: "2026-08-05T09:00:00Z",
            updatedAt: updatedAt,
            version: version
        )
    }
}
