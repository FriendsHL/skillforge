# OBS-2 MRD — Trace 数据模型分裂的排查痛点

## 1. 当前数据模型现状（继 OBS-1 后的混合状态）

```
t_session                       用户的会话
  └─ t_session_message          消息行，无 trace_id
  └─ Trace / Span 层（双轨）
       ├─ 旧轨：t_trace_span    单表混存：AGENT_LOOP(124) / LLM_CALL(852) / TOOL_CALL(877) / 4 类 event(4)
       └─ 新轨：OBS-1 引入
              ├─ t_llm_trace    trace 实体，仅 LLM 维度（126 行，含 33 ETL legacy）
              └─ t_llm_span     LLM span，字段丰富（856 行，含 176 ETL legacy）
```

## 2. 用户实际遇到的痛点

### 痛点 A：session 详情 waterfall 看不到最新 trace 的 span

- 现象：session 有 ~30 个 trace、500+ span，UI 上点最新几个 trace，瀑布流只剩"agent 一根棒子"，LLM/tool span 全消失
- 根因：`/api/observability/sessions/{id}/spans` 默认 `limit=200`，service 层 merge 两表后按 startedAt ASC 排序截前 200 → 最旧的 200 条占满，**最新的 trace 子 span 被截断**
- 临时修复：前端把 `limit` 提到 1000，但单 session > 1000 span 时再撞墙

### 痛点 B：tool / event span 详情字段缺失

- LLM span 有 blob_ref / blob_status / cache_read_tokens / usage_json / cost_usd / finish_reason / reasoning_content / request_id
- Tool span 只在 `t_trace_span`，input/output 直接塞列，没 blob 抽离，超长 tool result 把表行膨胀
- 4 类 event span（ASK_USER / INSTALL_CONFIRM / COMPACT / AGENT_CONFIRM）字段更少，前端瀑布流根本不展示

### 痛点 C：messages 没法按 trace 切分

- per-trace 视图本来应该让 stats bar / messages timeline 跟选中 trace 走
- 但 `t_session_message` 只有 `created_at`，没 `trace_id`
- 切分必须靠时间窗近似（`[trace.startTime, nextTrace.startTime)`），边界 case 多（trace 取消 / 并发 user message）

### 痛点 D：双轨数据漂移容易

- Phase A 发现 33 trace + 174 LLM 调用没在新表（OBS-1 上线前的历史数据），Phase A 已回填
- 但只要双轨在写，就有再次漂移风险（某个 Provider 路径漏接 observer / observer 异步写失败 / migration 调整）
- 双轨语义重叠（同一个 LLM 调用在两边各有一行）增加调试和审计成本

### 痛点 E：trace 列表 N+1

- `GET /api/traces?sessionId=X` 走 `t_trace_span where span_type='AGENT_LOOP'` 后，**每个 trace 再跑一次子 span 查询**算 llmCallCount / toolCallCount / totalChildDuration
- 30 trace 的 session = 31 次查询；trace 多了性能下降明显
- t_llm_trace 已经有聚合字段（total_input_tokens / total_output_tokens），但 API 不用

## 3. 影响

- 排查 LLM 调用 / tool 调用 / cancel / max_tokens retry 等场景时，需要看 t_trace_span 还是 t_llm_span 不一致 → 多次切表
- 加新 span kind（hook_call / agent_handoff / sub_agent_dispatch）时只能塞 `t_trace_span`，t_llm_span 字段不通用 → 新功能继续走旧轨
- 长期看 trace 数据模型是产品观测能力的地基，地基双轨是技术债

## 4. 不要做的事

- **不新建 `t_span` 第三套表**（用户明确否决）：扩展 `t_llm_span` 加 `kind` 列即可
- **不 rename 表**：`t_llm_span` 装 tool 后名字虽不准，但 rename 影响 entity / repository / 50+ 处 query，性价比低
- **不立即 drop `t_trace_span`**：先 read-only / 归档，观察 ≥ 1 周再砍
- **不 backfill `t_session_message.trace_id` 历史数据**：边界 case 多，新数据从 migration 后写入即可，老 session 走 fallback 显示

## 5. 成功标准（用户视角）

- session 详情页打开任意 trace（含 30+ trace 老 session 的最早一条），瀑布流完整显示该 trace 的 LLM + tool + event span
- tool span 详情面板能看到 blob 化的大输出（如 Bash long output / FileRead 整文件）
- per-trace 视图下 stats bar / messages 跟着选中 trace 切换（messages 走新写入的 `trace_id` 字段）
- 加新 span kind 只需新增 entity 字段或 attributes_json 键，不需要新建表
