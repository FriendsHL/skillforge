# ANNOTATOR-BEHAVIOR-SIGNALS

> 创建：2026-05-22
> 状态：done / archived（commit `f273bae7`）
> 模式：Mid pipeline（纯 BE，无 schema 变更）

## User Request

当前 session-annotator 只看 outcome（成功/失败）和 suspect_surface，看不到 agent 的行为效率问题：工具调用过度、迭代轮次过高、该用 skill 但没用、调了错误工具。这些行为信号已经在 `t_llm_trace` 和 `t_llm_span` 里，但 annotator 没有读取。

## 目标

1. 新增 `SpanBehaviorStatsTool`：从 trace/span 数据里提取行为统计（per-tool 调用次数、总轮次、耗时、error span 数）
2. 把工具注册到 session-annotator 的 tool_ids，在 STEP 1.5 调用
3. 更新 session-annotator system prompt，增加新 outcome 类型和 fault_type 维度
4. 不改 `t_session_annotation` schema（MVP，验证 pattern 产出质量后再决定加列）

## 新增 outcome 类型

| outcome | 触发条件 | 对应优化方向 |
|---|---|---|
| `tool_overuse` | 单 tool 调用次数 > 10（Bash）/ > 15（任意工具） | behavior_rule（加约束）/ system prompt |
| `loop_inefficiency` | 总轮次 > 20 且 outcome 非 success | prompt（目标表达、终止条件） |
| `skill_miss` | session 目标明显匹配某 skill 但 tool_use 记录里没出现 | skill description / trigger |
| `slow_execution` | total_duration_ms > 300,000（5 分钟） | prompt / model 选型 |

（现有 `infrastructure_failure` / `cost_high` / `failure` / `success` / `partial_success` 保留）

## 新增 fault_type 维度（写入 reasoning）

借鉴 tau-bench `auto_error_identification.py`：
- `called_wrong_tool`：调了错误的工具（或没调对应 skill）
- `used_wrong_tool_argument`：工具找对但参数错
- `goal_partially_completed`：任务没完成（loop 中途放弃或部分完成）
- `thinking_error`：推理/规划阶段方向错（无法靠 trace 直接判断，LLM 推断）

## SpanBehaviorStatsTool 返回结构

```json
{
  "sessionId": "xxx",
  "totalTurns": 32,
  "totalDurationMs": 187000,
  "perToolCounts": [
    { "tool": "Bash", "count": 18 },
    { "tool": "ReadFile", "count": 4 },
    { "tool": "WriteFile", "count": 1 }
  ],
  "errorSpanCount": 3,
  "hasLlmError": false,
  "topTool": "Bash",
  "topToolCount": 18
}
```

查询来源：
- `t_llm_trace WHERE session_id = ?`：totalDurationMs, tool_call_count, event_count, status
- `t_llm_span WHERE session_id = ? AND kind = 'tool'`：GROUP BY name → perToolCounts

## session-annotator prompt 改动

新增 STEP 1.5（在 STEP 1 DetectSignalAnnotations 之后，STEP 2 LLM 推理之前）：

```
STEP 1.5 — 行为统计（deterministic）：
  对每个需要 LLM 标注的 sessionId，调 SpanBehaviorStats(sessionId=<id>)
  返回：{ totalTurns, totalDurationMs, perToolCounts, errorSpanCount, topTool, topToolCount }
  将结果带入 STEP 2 的 LLM 推理上下文。
  注意：这步是纯数据提取，不需要 LLM 判断。
```

STEP 2.2 推理规则新增：

```
行为效率判断规则（基于 STEP 1.5 数据）：
  - topToolCount > 10 → 优先考虑 outcome=tool_overuse, suspect_surface=behavior_rule
  - totalTurns > 20 且 outcome 不是 success → outcome=loop_inefficiency, suspect_surface=prompt
  - totalDurationMs > 300000 → outcome=slow_execution
  - 若 trace 里有 "tool not found" / "no matching skill" 类错误 → fault_type=called_wrong_tool

fault_type 字段规则（写入 reasoning 结构中）：
  - called_wrong_tool：调用了不存在的工具，或明显有可用 skill 未调用
  - used_wrong_tool_argument：工具名对但报参数错误
  - goal_partially_completed：agent 中途停止，任务未全部完成
  - thinking_error：推理路径明显偏离任务目标（如反复循环同一操作）
```

## 验收点

1. `mvn test` BUILD SUCCESS（新 tool 有单元测试）
2. session-annotator tool_ids 包含 SpanBehaviorStatsTool（DB 验证）
3. 触发一次 on-demand opt loop → annotator session trace 里出现 `SpanBehaviorStats` tool_use 调用
4. 对已知 loop 次数高的 session 标注后，outcome 出现 `tool_overuse` 或 `loop_inefficiency`
5. `t_session_pattern` 在下次 RecomputeClusters 后出现新 signature（含新 outcome 类型）

## 已知 follow-up（不在本包范围）

- 如果新 outcome 类型能产出有效 curator 提案，下一包加 `behavior_signals_json` JSONB 列到 `t_session_annotation`，让 curator 直接读结构化数据
- RecomputeClusters 的 cluster signature 可以扩展加入 fault_type 维度
- `skill_miss` 检测依赖"可用 skill 列表 vs session goal"的语义对比，LLM 推断准确率存疑，后续单独评估
