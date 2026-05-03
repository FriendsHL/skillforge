# OBS-4 MRD — trace 串不起来的排查痛点

## 1. 当前 trace 语义边界

OBS-2 完成后，trace 数据模型已经统一在 `t_llm_trace` / `t_llm_span` 单轨：

```
t_session                       会话（每次调用 / 每个 agent 一条）
  ├─ t_session_message          消息行（OBS-2 已加 trace_id）
  └─ t_llm_trace                trace 实体（agent loop 一次完整 LLM call）
       └─ t_llm_span            span（kind = llm | tool | event）
```

**但 `trace` 的语义是"单次 agent loop"**：从 user → assistant 一轮 iteration → tool_use → loop → ... → assistant final → 结束。一旦主 agent 派 subagent（TeamCreate）后等待，loop 就"结束"了 → trace 关闭。subagent 跑出来的结果通过新的 user message 触发主 agent 新一轮 loop → **新 trace**。

所以实际数据：**一次用户请求 = N 个独立 trace + M 个独立 session**，物理上没有任何关联字段。

## 2. 用户实际遇到的痛点

### 痛点 A：长程任务跨 N 个 trace，session 详情瀑布流断片

**现场**：session `6f18ecca-1d6c-418a-87ca-15b23be45a7f`（2026-05-02 调研任务）

```
SQL 实测：parent session 17 spans / 8 traces
         child × 5 sessions / 共 121 spans / 5 traces
         总深度 1（无 child of child）
```

用户视角：发起一个调研，在 SessionDetail 看，瀑布流只能选一个 trace（`trace_1` 17 行 / `trace_2` 8 行 / ...）。**看不到"主 agent 在 t1 派出 5 个 child，5 个 child 在 t1-t2 期间各自调了什么工具 / 什么模型，主 agent 在 t2 收到结果后做了什么汇总"**。这个完整执行流是**用户最想看的视角**，但当前架构不支持。

### 痛点 B：subagent 内部活动只能跳转看

要看 child 调了什么，必须 `SubagentJumpLink` 跳转 child session（每次跳一次离开父 session 上下文，5 个 child 跳 4 次回来 4 次 = 8 次切页）。

### 痛点 C：跨 session 父子关系 schema 上有，但 trace 层缺

`t_session.parent_session_id` 已经能 DFS 拿到 child sessions（OBS-3 v1 用过这个路径），但每个 child session 内部的 trace 仍然是独立 ID — 只能用启发式（按时间窗）猜哪些 trace "属于一次调研"，启发式有误差（user 中间插一句"先停"会破坏链路）。

### 痛点 D：OBS-3 v1 试图在 UI 层硬拼凑失败

2026-05-03 上午 OBS-3 v1 实施了"UI 层 unified trace tree"：BE DFS 拿 descendants spans + FE NestedWaterfallRenderer 嵌套渲染。结果：

- BE 启发式拼装数据，逻辑边界靠时间窗 / parent_session_id 多级 DFS，复杂
- FE nested 全摊开把 121 child spans 平铺到父瀑布流，**parent 主线 17 行被淹没**
- 用户实测后回退（"前端看起来就不对，点进去都是各种 child"）

根本原因：**没有数据层一等公民字段表达"一次调研"，UI 怎么渲染都是在拼凑**。

## 3. 影响

- 调研、长程任务、多 agent 协作场景下用户**没法直接观察完整执行流**
- subagent 调试要跨 N 次跳转，体感差
- OBS-3 v1 失败教训：**数据模型不动，UI 层硬拼凑会反复返工**
- 长期看，distributed tracing 是 multi-agent 系统观测能力的地基，缺这一字段后续每一个跨 session 的观测需求都会绕弯子

## 4. 不要做的事

- **不引入新概念给用户**：`root_trace_id` 是 BE 字段，UI 上仍然叫"trace" / 瀑布流，不暴露 "investigation" / "调研流程" 等新名词
- **不跨 session 共享 trace_id**：OBS-3 v1 曾考虑过被拒（破坏 OBS-1 §9.2 "child session 独立性"不变量；trace_id 仍是单 agent loop 的 ID）
- **不启发式 backfill 历史数据**：直接 `UPDATE root_trace_id = trace_id`，老 trace 自己当 root，unified view 退化为单 trace 视图，行为完全等同 OBS-2 当前体验，不会变奇怪
- **不开新页面 / 新 tab**：复用 SessionDetail 现有瀑布流，仅升级数据来源 + 渲染逻辑（二级折叠 inline group）
- **不做并行 child 的 multi-track Gantt 渲染**：太重，二级折叠够覆盖核心需求

## 5. 成功标准（用户视角）

- 进入主 session 详情页，瀑布流自动按当前 trace 的 `root_trace_id` 显示**整条调研链**：主 agent 多个 trace + subagent 内部所有 LLM call / tool call，时序正确
- TeamCreate / SubAgent 派发 row 默认折叠（一行显示"派 N child / 共 X spans / 总耗时 Y"），不淹没父主线
- 点击折叠组 → inline 展开 child summary（每个 child 一行：agent 名 + status badge + 总耗时 + 内部 span 数）
- 再点 child summary → inline 展开 child 内部 spans（缩进 1 级，含 LLM call / 工具 / 模型）
- 不离开当前 session 详情页，无跳转
- 老 session（`root_trace_id = trace_id`）行为不变（瀑布流跟 OBS-2 完全一致），不会因为升级"看起来变奇怪"
