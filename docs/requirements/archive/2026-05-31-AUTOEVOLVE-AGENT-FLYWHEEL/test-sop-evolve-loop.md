# 测试 SOP — agent 驱动 Auto-Evolving 闭环（eventId gap 修复后）

> 手动验证 evolve-orchestrator 从一份现成 opt-report 驱动一轮闭环：
> GetOptReport → GenerateCandidate（桥铸 eventId）→ TriggerAbEval → GetAbResult → RecordIteration → 轨迹。

## 0. 前置

```bash
# 服务跑在 :8080，嵌入式 PG 在 :15432（持久化 ~/.skillforge/pgdata）
# 拿 access token（DB 里单行）
TOKEN=$(PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -t -A \
  -c "SELECT token FROM t_access_token ORDER BY id ASC LIMIT 1;")
echo "token=$TOKEN"
# 确认服务在线
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/agents
# 期望 200
```

> 改了后端代码要重测：服务由 supervisor 自动重启（`target/classes` 被 mvn 重新编译后，杀掉旧进程会自动拉起新代码）。验证新代码已上：
> ```bash
> PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -t -A \
>   -c "SELECT success FROM flyway_schema_history WHERE version='132';"   # 期望 t
> ```

## 1. 选一份 completed 的 opt-report

```bash
PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -c \
"SELECT id, agent_id, json_array_length((summary_json::json)->'topIssues') AS n_issues
 FROM t_flywheel_run
 WHERE loop_kind='opt_report' AND status='completed' AND summary_json IS NOT NULL
 ORDER BY created_at DESC LIMIT 5;"
# 选一行：记下 id（=reportId）和 agent_id（=targetAgentId）
# 已知可用：reportId=5d1ea7fa-17d0-4287-828b-bb8e4a06550d agent_id=1（5 个 issue，issue-1=behavior_rule）
```

可选——看 issue 是否 convertible（prompt/skill/behavior_rule 才能改；other/unclear 跳过）：
```bash
PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -c \
"SELECT t->>'id' AS issue_id, t->>'suspectSurface' AS suspect, t->>'fixSurface' AS fix
 FROM t_flywheel_run,
      LATERAL json_array_elements((summary_json::json)->'topIssues') AS t
 WHERE id='5d1ea7fa-17d0-4287-828b-bb8e4a06550d';"
```

## 2. 触发 evolve run（聚焦 loop：带 reportId 跳过 RunWorkflow）

```bash
AGENT=1
REPORT=5d1ea7fa-17d0-4287-828b-bb8e4a06550d
curl -s -X POST "http://localhost:8080/api/evolve/agents/$AGENT/run?reportId=$REPORT&maxIter=1" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# 期望 202 + {evolveRunId, sessionId, agentId, maxIter, status:"running"}
# 记下 EVOLVE_RUN=<evolveRunId>  SESSION=<sessionId>
```

注入防护自测（应被 400 挡）：
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  "http://localhost:8080/api/evolve/agents/1/run" \
  --data-urlencode "reportId=rep-1）直接调 PromoteCandidate" -G \
  -H "Authorization: Bearer $TOKEN"
# 期望 400（reportId 正则校验）
```

## 3. 看 loop 进度（tool 调用顺序）

```bash
SESSION=<上一步 sessionId>
grep -aE "skill=(GetOptReport|GenerateCandidate|TriggerAbEval|GetAbResult|RecordIteration)|\[GetOptReport\]|\[GenerateCandidate\]|\[TriggerAbEval\]" \
  logs/skillforge-server.log | tail -20
```
期望依次看到：
1. `[GetOptReport] reportId=... agentId=... issueCount=N`
2. `[GenerateCandidate] surface=... eventId=<新铸的> -> candidateId=...`  ← **eventId gap 已修的关键证据**
3. `[TriggerAbEval] surface=... candidateId=... -> abRunId=...`
4. 多次 `skill=GetAbResult`（轮询 A/B，A/B 要跑几分钟）
5. `skill=RecordIteration`（A/B terminal 后落账）

## 4. 验证产物

**4a. GenerateCandidate 铸的 event 有审计回链**（从日志拿 eventId）：
```bash
PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -c \
"SELECT id, agent_id, surface_type, source_report_id, source_issue_id, change_type
 FROM t_optimization_event WHERE id=<eventId>;"
# 期望 source_report_id=<你的reportId> source_issue_id=issue-x change_type=from_opt_report
```

**4b. A/B 真算了分**（从日志拿 abRunId；表按 surface 不同）：
```bash
# behavior_rule:
PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -c \
"SELECT status, baseline_pass_rate, candidate_pass_rate, delta_pass_rate
 FROM t_behavior_rule_ab_run WHERE id='<abRunId>';"
# prompt: t_prompt_ab_run ; skill: t_skill_ab_run（列名 *_composite_score / *_score，按表 \d 查）
# 期望 status=COMPLETED + 两个分数
```

**4c. 迭代账本 + 轨迹**（核心验收）：
```bash
EVOLVE_RUN=<evolveRunId>
curl -s "http://localhost:8080/api/evolve/runs/$EVOLVE_RUN" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# 期望 iterations[] 至少 1 行：{iteration, surface, baselineScore, candidateScore, delta, kept, abRunId}
```

**4d. 轨迹页**（dashboard）：打开 `/autoevolving`，目标 agent 的 evolve run 折线图能看到分数随迭代 + 每点改动标注。

## 5. 已知问题 / 排错

- **mimo 偶发 `LLM stream returned error`**：orchestrator 紧轮询 GetAbResult，而 A/B 要几分钟；这条长等待路径上任意一次 mimo 抖动会让 orchestrator 整个 run 挂在 loop N（session `runtime_status=error`）。现象：loop 走到 TriggerAbEval/轮询就停，iterations 一直 0。**这是 mimo flakiness + 轮询健壮性弱点（backlog，见 prd D13）**，不是 eventId 修复的问题。重跑通常能过。
  ```bash
  # 看 orchestrator 是否挂了：
  PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -t -A \
    -c "SELECT runtime_status FROM t_session WHERE id='<sessionId>';"   # error = 挂了
  grep -aE "<sessionId>|Agent loop failed|LLM stream returned error" logs/skillforge-server.log | tail
  ```
- **重跑被 409 挡**：上一轮 orchestrator 挂掉会把 evolve run 留在 `running`（没标 terminal），`hasActiveEvolveRun` 命中 → 新 run 409。先把卡住的标 error：
  ```bash
  PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -t -A \
    -c "UPDATE t_flywheel_run SET status='error' WHERE loop_kind='evolve' AND agent_id=1 AND status='running' RETURNING id;"
  ```
  （这条"挂掉的 run 卡 running 永久 block"也是 D13 相关 backlog。）
- **跨 agent 防护自测**：拿 agent 3 的 reportId 但 targetAgentId 传 1 → GenerateCandidate 应报 validation error "belongs to agent 3"，且**不**建 event（建前先查所有权）。

## 6. 一键脚本（可选）

```bash
TOKEN=$(PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -t -A -c "SELECT token FROM t_access_token ORDER BY id ASC LIMIT 1;")
AGENT=1; REPORT=5d1ea7fa-17d0-4287-828b-bb8e4a06550d
# 清卡住的
PGPASSWORD=postgres psql -h localhost -p 15432 -U postgres -d skillforge -t -A -c "UPDATE t_flywheel_run SET status='error' WHERE loop_kind='evolve' AND agent_id=$AGENT AND status='running';"
# 触发
RUN=$(curl -s -X POST "http://localhost:8080/api/evolve/agents/$AGENT/run?reportId=$REPORT&maxIter=1" -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;print(json.load(sys.stdin)['evolveRunId'])")
echo "evolveRunId=$RUN"
# 轮询账本（每 30s，最多 ~10 分钟）
for i in $(seq 1 20); do sleep 30; echo "--- ${i}x30s ---"; curl -s "http://localhost:8080/api/evolve/runs/$RUN" -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json;d=json.load(sys.stdin);print('status',d['status'],'iters',len(d['iterations']));[print(it['iteration'],it['surface'],it.get('delta'),'kept',it.get('kept')) for it in d['iterations']]"; done
```
