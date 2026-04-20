# P6 最终实施方案：消息行存储 + 结构化 Compaction 边界

> 生成日期：2026-04-20  
> 目标：一次性完成消息存储架构升级，避免后续反复推翻重构

---

## 0. 设计目标与边界

### 0.1 目标

1. 将会话消息从 `t_session.messagesJson`（单 CLOB）迁移到 `t_session_message`（行存储）
2. 保证 **UI 历史全量可见**，compaction 不再“覆盖写丢历史”
3. 保证 **LLM 上下文可控**，通过结构化边界重建 context，而不是依赖文本前缀
4. 为后续 Tool 输出裁剪、checkpoint 回溯、分支恢复预留稳定扩展点

### 0.2 核心不变量（必须长期保持）

1. **消息只增不删**（append-only）
2. **消息序号单调递增**（每个 session 内 `seq_no` 唯一且严格递增）
3. **UI 读取全量历史**（包含 compaction 边界、summary、普通消息）
4. **LLM 读取上下文视图**（由服务层拼接，不直接等于 UI 全量）
5. **Compaction 是“追加语义边界”，不是“覆盖历史”**

---

## 1. 参考实现结论（Claude Code / OpenClaw）

### 1.1 来自 Claude Code 的关键启发

1. 使用结构化系统消息 `compact_boundary`，不是依赖 `"[Context summary...]"` 纯文本标记
2. 边界消息携带 metadata，后续加载可以按边界切片、重建上下文
3. UI 与模型上下文不是同一视图：UI 可展示完整历史，模型只消费边界后有效片段

### 1.2 来自 OpenClaw 的关键启发

1. compaction 具备 checkpoint 语义（压缩前快照、压缩后锚点）
2. 可以做 checkpoint list/get，并支持 branch/restore（先保留后续能力）
3. compaction 后保留可审计轨迹，便于线上定位摘要错误与回滚

### 1.3 对 SkillForge 的落地原则

1. 采用 Claude Code 的“结构化 boundary + summary”主线
2. 融合 OpenClaw 的 checkpoint 元数据能力（先落库、后开放恢复能力）
3. 保留旧 CLOB 作为迁移期回滚边界，稳定后再考虑移除

---

## 2. 数据模型（一次到位版）

## 2.1 新表：`t_session_message`

建议字段：

- `id` BIGSERIAL PK
- `session_id` VARCHAR(36) NOT NULL
- `seq_no` BIGINT NOT NULL
- `role` VARCHAR(16) NOT NULL
- `msg_type` VARCHAR(32) NOT NULL
- `content_json` TEXT
- `metadata_json` TEXT
- `pruned_at` TIMESTAMPTZ NULL
- `created_at` TIMESTAMPTZ NOT NULL DEFAULT now()

索引与约束：

- `UNIQUE(session_id, seq_no)`
- `INDEX(session_id, created_at)`
- `INDEX(session_id, msg_type, seq_no)`
- `INDEX(session_id, pruned_at)`（为未来裁剪查询预留）

`msg_type` 建议枚举值：

- `NORMAL`
- `COMPACT_BOUNDARY`
- `SUMMARY`
- `SYSTEM_EVENT`（可选预留）

## 2.2 新表：`t_session_compaction_checkpoint`（推荐 P6 同步落地）

建议字段：

- `id` VARCHAR(36) PK
- `session_id` VARCHAR(36) NOT NULL
- `boundary_seq_no` BIGINT NOT NULL
- `summary_seq_no` BIGINT
- `reason` VARCHAR(32) NOT NULL（manual/auto-threshold/overflow-retry/timeout-retry）
- `pre_range_start_seq_no` BIGINT
- `pre_range_end_seq_no` BIGINT
- `post_range_start_seq_no` BIGINT
- `post_range_end_seq_no` BIGINT
- `snapshot_ref` TEXT（预留，当前可为空）
- `created_at` TIMESTAMPTZ NOT NULL DEFAULT now()

说明：  
当前阶段不强制实现 branch/restore API，但先把 checkpoint 数据沉淀下来，避免后续补字段迁移。

## 2.3 兼容字段（保留）

- `t_session.messages_json` 暂时保留（只作为回滚兜底）
- 新逻辑切换后不再作为主读路径

---

## 3. 服务层契约（读写分离）

## 3.1 SessionService 新接口

1. `List<Message> getFullHistory(String sessionId)`
   - 用于 UI / replay / 导出
   - 返回所有消息（按 `seq_no ASC`）
2. `List<Message> getContextMessages(String sessionId)`
   - 用于 AgentLoopEngine
   - 基于最后一个 `COMPACT_BOUNDARY` 与 `SUMMARY` 构建上下文
3. `AppendResult appendMessages(String sessionId, List<MessageAppendCommand> commands)`
   - 只追加，不更新旧消息
   - 由服务层分配连续 `seq_no`

## 3.2 兼容方法（过渡期）

- `getSessionMessages()` -> delegate 到 `getFullHistory()`
- `saveSessionMessages()` / `updateSessionMessages()` 标记 deprecated，内部改为兼容实现，避免外部调用直接崩

---

## 4. Compaction 语义改造

## 4.1 Full Compact

旧逻辑：输出压缩后的整段 messages 并覆盖写  
新逻辑：仅追加两条结构化消息

1. append `COMPACT_BOUNDARY`（`msg_type=COMPACT_BOUNDARY`）
2. append `SUMMARY`（`msg_type=SUMMARY`）

`COMPACT_BOUNDARY.metadata_json` 建议字段：

- `trigger`
- `tokens_before`
- `tokens_after`
- `last_summarized_seq_no`
- `first_kept_seq_no`
- `summary_seq_no`
- `checkpoint_id`
- `compacted_message_count`

## 4.2 Light Compact（未来 V2）

- 不做删除与覆盖
- 对过大 tool_result 做 `UPDATE pruned_at = now()`
- context 构建时把 pruned 行转换为占位文本（不丢语义锚点）

---

## 5. Context 构建规则（LLM 视图）

按最近 `COMPACT_BOUNDARY` 三段拼接：

1. 边界后的 `SUMMARY`（通常 1 条）
2. 边界后的年轻代消息（`NORMAL` / 必要系统消息）
3. 未被 `pruned_at` 的有效消息（或 pruned 占位）

说明：

- 若无 boundary：全量历史进入 context（由现有 token guard 控制）
- 若 boundary 存在但 summary 缺失：退化策略为仅使用边界后消息 + 告警日志

---

## 6. 前端渲染规则

`useChatMessages.ts` 从“文本前缀判断 summary”迁移为“结构化 `msg_type` 判断”：

1. `COMPACT_BOUNDARY` 渲染为分隔条（例如“对话已压缩”）
2. `SUMMARY` 渲染为摘要块（可折叠）
3. 旧前缀字符串保留 fallback（兼容历史数据）

---

## 7. 迁移与灰度（关键）

## 7.1 迁移步骤

1. **V18 DDL**
   - 创建 `t_session_message`
   - 创建 `t_session_compaction_checkpoint`
2. **V18 Java Migration（回填）**
   - 扫描 `t_session.messages_json`
   - 逐条写入 `t_session_message`（`seq_no` 从 0 递增）
   - 识别旧 summary 文本前缀可映射为 `msg_type=SUMMARY`
3. **双读校验期**
   - 新旧读路径并行对比（条数/首尾消息 hash）
   - 出现不一致打告警并保留回滚开关
4. **切读**
   - API/UI/Engine 切到新读路径
5. **切写**
   - 全量写入改为 append-only

## 7.2 回滚策略

1. 保留 `messages_json` 直到新路径稳定
2. 通过 feature flag 快速切回旧读路径
3. 新增表不删，仅停止消费

---

## 8. 风险与防护

1. **并发 seq_no 冲突**
   - `appendMessages` 在事务内加 session 级锁或使用 `MAX(seq_no)+1` 的安全分配策略
2. **boundary/summary 脱节**
   - 必须同事务写入；失败整体回滚
3. **迁移性能风险**
   - 批量分页迁移（按 session 分批）
4. **历史渲染异常**
   - 前端保留 legacy fallback，避免一次切换导致历史空白

---

## 9. P6 最终任务拆分（更新版）

1. **P6-1** V18 Schema + Entity + Repository（含 `pruned_at` 与 checkpoint 表）
2. **P6-2** SessionService 三视图接口 + 兼容层
3. **P6-3** Compaction append-only 改造（boundary + summary）
4. **P6-4** AgentLoopEngine 切到 `getContextMessages` + `appendMessages`
5. **P6-5** V18 Java Migration + 双读校验
6. **P6-6** 前端 `msg_type` 渲染改造 + fallback
7. **P6-7** 灰度切换与回滚演练
8. **P6-8（后续）** Tool 输出裁剪启用（`pruned_at`）
9. **P6-9（后续）** checkpoint list/get API（branch/restore 可后续迭代）

---

## 10. 结论

本方案的关键不是“把 CLOB 改成行表”，而是把消息层升级为：

1. **Append-only 事件流**
2. **结构化 compaction 边界**
3. **可回溯 checkpoint 元数据**

这样可以同时满足：

- UI 永久历史保留
- LLM 上下文可控
- 工具裁剪平滑演进
- 线上可追溯与可恢复

这套模型可长期支撑后续优化，不需要再做架构级返工。

---

## 11. 实施清单（文件与方法级）

本节用于直接执行开发，按阶段串行推进，避免“边改边返工”。

## 11.1 Phase A：Schema 与实体（先打地基）

**目标**：先让新表可写可读，但不切主路径。

后端文件清单：

1. 新增 migration SQL
   - `skillforge-server/src/main/resources/db/migration/V18__session_message_store.sql`
2. 新增实体
   - `skillforge-server/src/main/java/com/skillforge/server/entity/SessionMessageEntity.java`
   - `skillforge-server/src/main/java/com/skillforge/server/entity/SessionCompactionCheckpointEntity.java`
3. 新增仓储
   - `skillforge-server/src/main/java/com/skillforge/server/repository/SessionMessageRepository.java`
   - `skillforge-server/src/main/java/com/skillforge/server/repository/SessionCompactionCheckpointRepository.java`

关键约束：

- `UNIQUE(session_id, seq_no)` 必须落库
- `msg_type`、`role`、`metadata_json` 保持宽松字符串，不在 DB 层过早强枚举
- `pruned_at` 本期先落字段，不启用业务逻辑

## 11.2 Phase B：SessionService 三视图与 append-only

**目标**：在服务层形成新读写模型，同时兼容旧调用方。

后端文件清单：

1. 修改
   - `skillforge-server/src/main/java/com/skillforge/server/service/SessionService.java`
2. 新增 DTO/内部模型（如需要）
   - `MessageAppendCommand`（内部类或独立类）
   - `AppendResult`（返回首尾 seq_no、写入条数）

方法改造建议：

1. 新增 `getFullHistory(sessionId)`（读 `t_session_message`）
2. 新增 `getContextMessages(sessionId)`（按最后 boundary 构建上下文）
3. 新增 `appendMessages(sessionId, commands)`（事务内分配 `seq_no` 并 INSERT）
4. 兼容层：
   - `getSessionMessages()` -> `getFullHistory()`
   - `saveSessionMessages()`：仅迁移期保留，不再用于主路径
   - `updateSessionMessages()`：迁移期保留，逐步收敛调用点

并发策略建议：

- `appendMessages` 内先查 `max(seq_no)` 再批量写，且同事务下加 session 级串行（可复用现有 stripe lock）

## 11.3 Phase C：Chat / Compaction 主流程切换

**目标**：把“覆盖写”替换为“追加写”。

后端文件清单：

1. 修改
   - `skillforge-server/src/main/java/com/skillforge/server/service/ChatService.java`
   - `skillforge-server/src/main/java/com/skillforge/server/service/CompactionService.java`
   - `skillforge-server/src/main/java/com/skillforge/server/service/ReplayService.java`
   - `skillforge-server/src/main/java/com/skillforge/server/service/SessionTitleService.java`
   - `skillforge-server/src/main/java/com/skillforge/server/memory/SessionDigestExtractor.java`

改造要点：

1. `ChatService`
   - 用户输入、排队输入、loop 结束都改为 `appendMessages`
   - 去除直接读改写 `messagesJson` 的逻辑
2. `CompactionService`
   - `persistCompactResult()` 改为 `persistBoundary()` 语义
   - 写入 `COMPACT_BOUNDARY` + `SUMMARY` 两行，不改旧行
3. 消费历史的服务统一改为 `getFullHistory()`，避免误用上下文视图

## 11.4 Phase D：API 与前端适配

**目标**：前端改结构化渲染，不依赖 summary 文本前缀。

后端文件清单：

1. 修改
   - `skillforge-server/src/main/java/com/skillforge/server/controller/ChatController.java`（返回模型若需扩展 `msg_type`）

前端文件清单：

1. 修改
   - `skillforge-dashboard/src/hooks/useChatMessages.ts`
   - `skillforge-dashboard/src/pages/Chat.tsx`（若有消息类型分支）
   - `skillforge-dashboard/src/components/ChildAgentFeed.tsx`（若有消息解析重复逻辑）
2. 保持 API 入口不变
   - `skillforge-dashboard/src/api/index.ts` 的 `getSessionMessages` 可不改路径

渲染策略：

1. 优先使用后端结构化 `msg_type`
2. 对老数据继续保留 `"[Context summary from ...]"` fallback

## 11.5 Phase E：数据回填与灰度切换

**目标**：线上安全迁移，不影响现网会话。

后端文件清单：

1. 新增迁移执行组件（推荐）
   - `skillforge-server/src/main/java/com/skillforge/server/migration/SessionMessageBackfillRunner.java`
2. 修改（可选）
   - `skillforge-server/src/main/java/com/skillforge/server/repository/SessionRepository.java`（增加分页扫描方法）

执行步骤：

1. 先回填历史 `messagesJson` -> `t_session_message`
2. 启用双读对账（按 session 校验 count + 首尾 hash）
3. 通过 feature flag 切读
4. 稳定后切写为 append-only
5. 保留旧 CLOB 一段观察期

## 11.6 Phase F：测试改造与验收

后端测试优先修改：

1. `skillforge-server/src/test/java/com/skillforge/server/compact/CompactionServiceTest.java`
2. `skillforge-server/src/test/java/com/skillforge/server/compact/ChatServiceB3OrderingTest.java`
3. `skillforge-server/src/test/java/com/skillforge/server/service/ChatServiceLifecycleHookTest.java`
4. `skillforge-server/src/test/java/com/skillforge/server/service/ReplayServiceTest.java`

前端测试优先新增/修改：

1. `useChatMessages` 的 `msg_type` 解析单测
2. boundary + summary 渲染快照/行为测试

回归范围：

1. 手动 compact 后，历史消息仍可在 UI 完整查看
2. loop 继续运行时，context 只使用 boundary 后有效片段
3. 旧会话（仅 CLOB）迁移后展示与迁移前一致
4. 高并发发送消息下不出现 `seq_no` 冲突

## 11.7 DoD（完成定义）

以下条件同时满足才算 P6 完成：

1. 生产写路径不再依赖 `messagesJson` 覆盖写
2. full compact 不再改写旧消息，只追加 boundary + summary
3. 前端在新老数据下都能正确渲染历史
4. 双读对账在灰度期内无系统性差异
5. 回滚开关验证通过（可切回旧读路径）
