# OBS-4 PRD — 跨 agent / 跨 session trace 串联（root_trace_id）

## 1. 目标

引入 `root_trace_id` 作为 trace 层的一等公民字段，把"一次完整用户请求"在数据模型上**显式串联起来**：

- 一个 user message → 一个新 root_trace_id
- 主 agent 该 user message 内的所有后续 trace（含主 agent 收 child 结果后再起的新一轮 LLM call）继承同一个 root_trace_id
- 派出去的 subagent，subagent 内部所有 trace（含递归 child of child）也继承同一个 root_trace_id
- **一个 root_trace_id 在数据库 SQL 一次查询就能拿到一次调研的完整执行链**

UI 层把 SessionDetail 当前的瀑布流**升级**：数据来源从"按当前 trace_id 查 spans"改为"按当前 trace 的 root_trace_id 查整树"，渲染加二级折叠 inline group。**对终端用户不暴露新概念，仍叫"trace" / 瀑布流**。

## 2. 范围（M0 → M3）

### M0：Schema 扩展

| 表 | 变更 |
|---|---|
| `t_llm_trace` | 加 `root_trace_id VARCHAR(36) NULL` + 索引 `(root_trace_id, started_at)` |
| `t_session` | 加 `active_root_trace_id VARCHAR(36) NULL`（当前 user message 的 active root，user message 边界处更新） |

历史数据 backfill（V45 内）：

```sql
UPDATE t_llm_trace SET root_trace_id = trace_id WHERE root_trace_id IS NULL;
```

**M0 列保持 nullable**（不 SET NOT NULL）。原因：`PgLlmTraceStore.upsertTrace` 当前 INSERT SQL 不传 `root_trace_id` 列，M0 一次性 SET NOT NULL 会导致 M1 写入路径改造前任何新 trace 失败。NOT NULL 推到 M1 同 commit 内的 V46（写入路径已透传 root_trace_id 后再加 NOT NULL，原子切换）。

老 trace 自己当 root，unified view 退化为单 trace 视图（跟 OBS-2 当前体验等同）。`t_session.active_root_trace_id` 默认 NULL，仅新会话才填。

### M1：写入路径

- `ChatService.handleUserMessage` 收到 user message → 立即清空 `session.activeRootTraceId = null`（边界重置）
- `AgentLoopEngine.run` 创建 trace 时：
  - 读 `session.activeRootTraceId`：
    - 如果 NULL → 新 root：`trace.rootTraceId = trace.traceId`，**回填** `session.activeRootTraceId = trace.traceId`
    - 如果非 NULL → 继承：`trace.rootTraceId = session.activeRootTraceId`
- `TeamSendTool` / SubAgent 派发：subagent session 创建时复制父 session 的 `activeRootTraceId`，subagent 自己的 ChatService loop 沿用同样规则继承
- `SessionService.persistActiveRootTraceId(sessionId, rootTraceId)` 是新写入接口，事务保护，幂等
- **新增 V46 migration**：`ALTER TABLE t_llm_trace ALTER COLUMN root_trace_id SET NOT NULL` —— 在 M1 写入路径透传 root_trace_id 之后加 NOT NULL 约束（M0 V45 仅加 nullable + 回填，原因见 §M0）。V46 必须跟 M1 写入路径改造在**同一个 commit 内**部署，原子切换

### M2：Read API

- 新增 `GET /api/traces/{rootTraceId}/tree`：
  - 按 `root_trace_id` 一条 SQL 拿全部 traces
  - 按 trace_id IN (...) 一条 SQL 拿全部 spans
  - 返回结构：`{ rootTrace, traces: [{traceId, sessionId, agentName, depth, parentSessionId, status, spans: [...]}] }`
  - depth 由后端按 `t_session.parent_session_id` DFS 计算（每个 trace 标 depth；root_trace 自身 depth=0）
- `GET /api/traces?sessionId=X` 响应行加上 `rootTraceId` 字段（前端识别需要查"整树"的入口）

### M3：FE 二级折叠瀑布流

- `SessionDetail.tsx` 进入页面：用 `selectedTraceId` 反查它的 `rootTraceId`，调 `/api/traces/{rootId}/tree` 拿整树（替代当前按单 trace 查 spans）
- `SessionWaterfallPanel.tsx` 渲染逻辑升级：
  - 按主 agent 时间线渲染 parent 主 trace spans（保持原貌）
  - 遇到 `kind='tool'` 且 `name IN ('TeamCreate', 'TeamSend', 'SubAgent...')` 的 row → 转成**折叠组**
  - 折叠组默认收起，标题行显示：`▶ TeamCreate (派 N child / 共 X spans / 总 Y ms)`
  - 点击 ▶ 展开 → 列出 N 个 **child summary 行**：`agent_name | status badge | duration bar | 内部 span 数 | ▶`
  - 再点 child ▶ → inline 展开 child 内部 spans（缩进 1 级，含 LLM call / tool 调用，每个 span 显示 model 名 / tool 名）
  - 折叠/展开状态用 `useState` 维护（不持久化）
- 老 session（`root_trace_id = trace_id`，无 child）：API 返回的整树只有 1 个 trace，渲染等同当前体验，**用户看不到任何变化**

### M4：观察 + 收尾

- 1 周观察 dev 实际使用：长程多 agent 任务的 root_trace_id 继承是否正确（无断链 / 无误继承）
- todo backlog OBS-3 entry 标 superseded by OBS-4
- delivery-index.md 写入 OBS-4 交付行

## 3. 验收点

| # | 验收点 | 验证方法 |
|---|---|---|
| 1 | M0 migration 跑通；既有 IT 全过 | `mvn test` |
| 2 | M0 后 `t_llm_trace.root_trace_id` 全部非 NULL；老 trace 满足 `root_trace_id = trace_id` | SQL 核对 |
| 3 | M1 后新 user message 触发的所有主 agent trace 共享同一 `root_trace_id`（同一 user message 边界内） | 跑一次 6f18ecca 风格调研，SQL 核对 |
| 4 | M1 后派出的 subagent trace `root_trace_id` 等于父 session 当前 active root | 跑 TeamCreate 调研，SQL 核对 child traces |
| 5 | M1 后下一个 user message 进入时 `session.active_root_trace_id` 重置（不沿用上次） | 连续两条 user message，验证两次 root 不同 |
| 6 | M2 `/api/traces/{rootId}/tree` 在 6f18ecca 6 session / 138 span 的规模下 RT < 200ms | curl + 计时 |
| 7 | M3 SessionDetail 进入有 child 的 trace（如 6f18ecca）瀑布流默认显示 parent 主线 + 折叠组（不淹没） | 浏览器目检 + Playwright snapshot |
| 8 | M3 折叠组点击展开/收起正常，二级展开 child 内部 spans 正确显示 model / tool 名 | 浏览器目检 |
| 9 | M3 老 session（无 child）瀑布流跟 OBS-2 完全一致（无视觉变化） | 浏览器目检对比 |
| 10 | M3 没有跳转 child session 的需要（全部 inline 看完） | 用户体感验收 |

## 4. 已拍板决策

| #   | 决策                               | 选项                                                                                           | 备选（已拒）                                     |
| --- | -------------------------------- | -------------------------------------------------------------------------------------------- | ------------------------------------------ |
| Q1  | 边界规则：什么时候开新 root_trace_id        | ✅ **方案 a — 每个 user message 一个新 root**（自然的任务起点）                                               | b: 用户显式标 / c: 时间窗启发                        |
| Q2  | 历史数据 backfill 策略                 | ✅ **直接 `UPDATE root_trace_id = trace_id`** — 老 trace 自己当 root，unified view 退化为单 trace 行为     | 启发式按 user message 时间窗推断（启发式风险高，收益小）        |
| Q3  | UI 入口位置                          | ✅ **复用 SessionDetail 当前瀑布流**（升级数据 + 渲染）                                                      | 新 tab / 新页面 `/investigations/{rootId}`     |
| Q4  | 命名共存策略                           | ✅ **不暴露新概念给用户**：BE 字段 `root_trace_id`，UI 文案仍叫"trace" / 瀑布流；不引入 "investigation" / "调研流程" 等新名词 | 引入 investigation 作为新一级实体                   |
| Q5  | active root 状态存储                 | ✅ **持久化到 `t_session.active_root_trace_id`**（重启不丢失）                                           | ChatService 内存 map（重启后下个 trace 错继承上次 root） |
| Q6  | child of child 递归继承              | ✅ **递归继承父 session 的 active_root_trace_id**，无深度限制（实际 1-2 层为主）                                 | 仅 1 层；超过 1 层不串                             |
| Q7  | child session 完成后是否清 active_root | ✅ **child session 完成不清父 session 的 active_root**（父 session 仍可能起新 trace 收结果，需要继承）              | 清掉（导致汇总 trace 误开新 root）                    |

## 5. 范围之外（Out of Scope）

- **多 user message 间的 root 合并**：每条 user message 各自独立 root，不做"同一个会话多 query 合并成一个 root"
- **OpenTelemetry 标准对齐**（`traceparent` header / W3C trace context）：不做，自建路径
- **跨主 session 的 trace 串联**（用户在 session A 让 session B 帮做事这种场景）：不做，仅覆盖父子 session
- **multi-track Gantt 风格的并行 child timeline 渲染**：不做，二级折叠 inline group 够用
- **删除 / drop 老 trace `root_trace_id = trace_id` 数据**：永久保留兼容
- **新 root_trace_id 维度的列表页 / 搜索**：不做，从 SessionDetail 入口
- **跟 OBS-3 v1 已删除文件相关**：OBS-3 v1 已 revert，本需求不依赖任何 v1 残留代码

## 6. 风险

| 风险 | 缓解 |
|---|---|
| 触碰 `AgentLoopEngine`（核心文件清单），改 trace 创建时机 | Full 档对抗 review；Plan 先把不变量列清楚（trace 唯一性、root 继承时机、user message 边界判定） |
| 触碰 `ChatService`（核心文件清单），改 user message 边界处理 | 同上 |
| `active_root_trace_id` 持久化失败导致 root 链断 | 写入 `SessionEntity` 时 `@Transactional`；失败回滚整个 user message；fallback：trace 自己当 root（退化为单 trace） |
| subagent 派发时复制 active_root 漏接 → child trace 自己当 root（断链） | M1 后立刻跑 6f18ecca 风格调研做 e2e 验证；新 IT 覆盖 TeamSendTool 派发路径 |
| 多 user message 并发场景下 active_root 错位 | session 状态机本身是单线程消费 user message（waiting/running 互斥），不存在并发 user message 在同一 session；review 时 grep 所有 active_root 写入点确认 |
| FE 二级折叠状态机复杂（折叠组 + child summary 二级展开） | 折叠状态用 useState，每个折叠组独立维护；展开/收起逻辑写单测；上次 OBS-3 v1 fail 经验：默认全收起，绝不默认展开 child 内部 spans |
| `/api/traces/{rootId}/tree` 数据量大场景（root 下 50+ child） | 6f18ecca 实测 138 span / 6 session 是当前真实最大；返回 500 行以下 OK；超过加分页（暂不做） |
| FE 升级后老 session 行为变化 | M3 验收点 9 强制：老 session 视觉零变化；测一个 OBS-2 时代的简单 session 对比 |

## 7. 工期估算

| 里程碑 | 范围 | 工期 |
|---|---|---|
| M0 | schema migration + 历史 backfill | 0.5 天 |
| M1 | 写入路径（4 处）+ IT | 2-3 天 |
| M2 | Read API + DTO + IT | 1 天 |
| M3 | FE 瀑布流二级折叠 inline group | 3-5 天 |
| M4 | 观察 + 收尾 | 1 周 |

**总计：~1.5-2 周开发 + 1 周观察期**

## 8. 依赖

- **前置**：OBS-2 已完成（`t_llm_trace` / `t_llm_span` 单轨数据已稳定，`t_session_message.trace_id` 已填充）
- **不依赖**：OBS-3 v1（已 revert，零残留代码）
- **不引入新依赖**：纯 Java + PostgreSQL + 现有 React 18 / Ant Design 栈
