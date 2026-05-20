# Insight Loop 操作手册

> Insight Loop（前称 Flywheel / Optimization Loop）= 生产 session 失败信号 → 自动归因 → 候选生成 → A/B 评测 → 人审 → promote 的完整闭环。
>
> 这个文档是给 operator / dev 用的：怎么测、怎么读、怎么排错。

---

## 1. 是什么 / 在哪看

**Dashboard 入口**：`/insights/patterns?tab=flywheel` → **Insight Loop** tab

**3 类组件**：
- **DAG 流程图** — 15 个 step（4 entry + 6 auto + 3 user gate + 3 dormant canary）+ 箭头表数据流向
- **Sidebar (Per-Run mode)** — 最近 N=20 个 OptimizationEvent run，点选 → DAG 高亮该 run 当前位置
- **Drawer** — 点节点 → 右侧滑出详情（aggregate 显总量 metric / per-run 显该 run 在该 stage 信息 + description）

**底层数据模型** — 一个 OptimizationEvent (`t_optimization_event`) = 一次 loop 的 "run instance"：
```
pattern → 归因 → opt_event 创建 (stage='proposal_pending')
       ↓ operator approve
candidate (SkillDraft / PromptVersion 生成)
       ↓ auto trigger
A/B test
       ↓ pass threshold
operator promote → 上线
```

---

## 2. 两个 Mode

### Aggregate mode（默认）

看整个 pipeline 当前**总量 metric**。每节点 4 维：
- `in-flight` — 当前在该 step 的对象数
- `lag` — 距上次活动多久
- `recent error` — 近 24h 错误数
- `today aggregate` — 今天累计处理数

**health 颜色**（节点右上角 dot + H/W/S/D/E 字母 a11y fallback）：
- 🟢 **healthy** — lag < 2× cron interval + 无 recent error
- 🟡 **warn** — lag 2-3× cron interval 或 last 24h 有 1-2 error
- 🔴 **stale** — lag > 3× cron interval 或 ≥3 recent error 或 0 today
- ⚪ **dormant** — V87 disabled（⑦⑧⑨ canary）
- ⚫ **empty** — 该 surface 该 step 历史就 0 activity

**running pulse 动画**：AUTO + HYBRID 节点 `inFlight > 0` 或 cron `lastRunStatus='running'` 时显 1.5s 绿色慢闪 ring。USER GATE 不闪（等人 ≠ running），显示静态红 `[PEND N]` chip。

**Edge 动画**：相邻节点都 `inFlight > 0` 时显 dashed flow。

### Per-Run mode

切到 Per-Run → 左侧 sidebar 列最近 OptEvent run，点选一个 → DAG 高亮该 run journey：
- **当前 step**：绿色 ring（rejected→amber / failed→red 替代）
- **completed steps**：checkmark + muted
- **pre-OptEvent 节点**（ENTRY + ① ② ③）：灰化为 "context"（这些 step happen before run created）
- **chip 文案 per stage**：
  - `proposal_rejected` → "rejected here" + amber
  - `candidate_failed` / `ab_failed` → "failed here" + red
  - 其它 ERROR_STAGES 外 → "current" + green

点节点 → Drawer 显该 run 在该 stage 的：
- agent / current stage
- run start (created_at) / stage age (updated_at)
- pattern signature snippet
- **原因详情**（`t_optimization_event.description`）— attribution-curator 写的完整解释文本（rejection rationale / failure stack 等）
- footer "在 page 中打开 →" 跳 OptEvent 详情页

---

## 3. End-to-end 手动测试（6 阶段）

> 假设 BE 在 `:8080` 跑，token = 启动日志里 `Access Token: <hex>`，Per-Run 数据来自 `t_optimization_event` 实表。

### 阶段 1 — 准备：确保所有 cron 都 enabled（UI）

```
Dashboard → AgentList → 打开 "Show system agents" toggle
→ 找 attribution-curator / session-annotator / metrics-collector / memory-curator
→ AgentDrawer → status: enabled
→ /schedules page → 对应 task enabled=true
```

**注意**：CRON-DUAL-SCHEDULE-FIX (commit `c3225e0`) 后，UI 是 cron 的**单一真实来源**。Dashboard 关 = 真停，开 = 真启。

### 阶段 2 — 产生 entry 信号

任选一条：
- 真活让某个 Code/Design Agent 跑一个会失败的 task（chat 输入"deliberate failure test"）
- 上传一个 skill zip 到 `/skills`
- `/skills` → "Extract from session" 选个老 session 抽 skill
- agent 配置页改 prompt → 自动生成 PromptVersion

### 阶段 3 — 手动触发 session-annotator（不等 hourly cron）

```bash
# 找 schedule id
psql -c "SELECT id, name FROM t_scheduled_task WHERE name LIKE '%annotator%'"
# 手动 fire
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/schedules/{id}/trigger"
```

**验证**：
```sql
SELECT * FROM t_session_annotation ORDER BY created_at DESC LIMIT 5;
-- 应该新增几条 outcome 标注
```

### 阶段 4 — 同样手动跑 cluster + attribution-curator

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/schedules/{cluster_id}/trigger"
curl -X POST -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/schedules/{attribution_id}/trigger"
# OR 用 admin endpoint 直接触发 dispatcher（绕过 LLM 加速）：
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/attribution/admin/trigger-dispatch?max=5"
```

**验证**：
```sql
SELECT id, signature, member_count FROM t_session_pattern ORDER BY created_at DESC LIMIT 5;
SELECT id, agent_id, surface_type, stage FROM t_optimization_event ORDER BY created_at DESC LIMIT 5;
```

### 阶段 5 — 在 Insight Loop panel 看真活

切到 Per-Run → sidebar 列出新 run → 点新 run → DAG 高亮在 G1（proposal_pending）等审 → 点 G1 节点 → Drawer 显该 run 信息。

### 阶段 6 — Approve → 走完整 loop

UI: `/insights/patterns?tab=optimization&stage=proposal_pending` → 点新 row → Approve

**验证**：
```sql
-- candidate 生成
SELECT stage, candidate_skill_draft_uuid FROM t_optimization_event WHERE id=X;
SELECT * FROM t_skill_draft WHERE id=...;
-- A/B run 创建
SELECT * FROM t_skill_ab_run WHERE id=...;
```

在 Insight Loop sidebar 点该 run → DAG 高亮位置从 G1 → ④ → ⑤（A/B 跑完）→ G3 等审 → promote/discard 终态。

---

## 4. 常见 noise + 解读

### Stale pattern 反复 reject

**症状**：sidebar 一直显 "X 分钟前 / 1h 前 rejected" 但 description 文本永远是 5+ 天前的同一个失败说明。

**根因**：`t_session_pattern` 含 5 天前 metrics-collector 失败 session，attribution-curator 每小时拉去重新归因，LLM 读 5 天前 trace 看到 "bailian API 401" 文本忠实复述。

**真问题不在 loop，是 stale data**。修法（暂未做 — backlog）：
- `t_session_pattern` 加 `latest_member_at` 字段，cron 跳过 latest_member_at > 7 天的
- attribution-curator agent prompt 加"先验证 input session 是否近期"判定

### attribution-curator 自己每周失败 N 次

**症状**：`/agents` → System tab 看 attribution-curator monitor card 显 lastRunStatus='failure' 多次。

**排错路径**：
```sql
SELECT id, runtime_status, created_at FROM t_session
WHERE agent_id=9 AND runtime_status='error'
ORDER BY created_at DESC LIMIT 5;
-- 拿 session id → /sessions/<id> 看 trace + error message
```

### Per-Run sidebar 空

- `mode=perRun` 但 `runs.length === 0` → API 没返数据
- 检查：`curl /api/flywheel/runs?limit=20` 看 BE 返 `{items: [...]}` 是否非空
- 如果 BE 真返空 → 没有满足 filter 的 OptEvent（hideTerminal=true 排了 promoted/discarded，可能全 in terminal）
- 切 sidebar "show terminal" filter chip 看看

### 节点不闪绿（aggregate mode）

- USER GATE / ENTRY / DORMANT **本来就不闪**（设计）
- AUTO + HYBRID 节点也不闪 → 检查 `useFlywheelObservability` hook 是否拿到 `inFlight > 0` 或 cron `lastRunStatus='running'`
- 浏览器 console 看 `/api/system-agents/monitor` 响应是否含 `lastRunStatus`

### Drawer 没显示原因详情

- per-run mode + 选中 run + 点节点 → 看不到 "原因详情" section
- 原因：`activeRun.description == null`
- 该 OptEvent 在 DB 里 description 列就是 null（pending stages 通常 null，terminal stages 才填）

---

## 5. cron 控制（CRON-DUAL-SCHEDULE-FIX 后）

**单一真实来源**：Dashboard → `/schedules` page 或 system agent monitor card。

- **停 cron**：UI 切 disabled，**真停**（c3225e0 之后 @Scheduled annotation 删了，UI 控制就是 BE 实控）
- **启 cron**：UI 切 enabled → UserTaskScheduler register → 下一个 cron 时刻 fire

**注意**：`AttributionDispatcherService.cleanupOrphanSentinels()` (cron `0 50 * * * *`) 仍是 `@Scheduled` 注解 — pure 清理 dispatch_initiated orphan row，**不创建新 OptEvent**，不归 UI 控（housekeeping 不该 expose 给 user）。

---

## 6. Smoke test 速查

```bash
TOKEN=$(grep "Access Token:" /tmp/be-restart.log | sed -E 's/.*Access Token: ([a-f0-9]+).*/\1/' | head -1)

# 1. /api/flywheel/runs 真活
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/flywheel/runs?limit=5" | python3 -m json.tool

# 2. system agent monitor 真活（aggregate mode 数据源）
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/system-agents/monitor" | python3 -m json.tool

# 3. attribution events 当前 stage 分布
psql -c "SELECT stage, COUNT(*) FROM t_optimization_event GROUP BY stage ORDER BY 2 DESC"

# 4. 看 cron 真活状态
psql -c "SELECT id, name, enabled, last_fire_at, NOW()-last_fire_at AS lag FROM t_scheduled_task ORDER BY id"
```

---

## 7. 相关文档

- 整体方案：[`plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md`](../plans/PROD-OPTIMIZATION-FLYWHEEL/plan.md)
- 需求包归档：
  - `requirements/archive/2026-05-20-FLYWHEEL-VISUAL-STATUS/` — 第一版 card-style panel
  - `requirements/archive/2026-05-20-FLYWHEEL-FLOWCHART/` — 改 React Flow DAG
  - `requirements/archive/2026-05-20-FLYWHEEL-PER-RUN/` — per-run sidebar + mode toggle
- 关键 commit:
  - `092267f` 第一版 observability panel
  - `65c8643` DAG flowchart
  - `538b828` per-run mode
  - `5e25067` envelope shape hotfix
  - `007919a` chip wording + amber rejected + description in Drawer
  - `c3225e0` UI rename + CRON-DUAL-SCHEDULE-FIX
  - `f350c16` rules update — footgun #6 outer envelope shape

---

## 8. Backlog（已记录待修）

- **CRON-DUAL-SCHEDULE-FIX 主例**已修 (c3225e0)，其它 `@Scheduled` vs `t_scheduled_task` 双轨需要系统性 audit
- **Stale pattern auto-expire** — pattern_member 全是 >7 天前的应跳过
- **attribution-curator 自身错误率高** — 需要 root cause investigation
- **USER-SSO-MULTITENANT** (P1 战略) — 单用户 dogfood → 多租户
- **FE-RENDER-AUDIT** (P2) — Profile dashboard 是否过度 re-render
