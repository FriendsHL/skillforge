# OBS-1 MRD

---
id: OBS-1
status: mrd
source: user
created: 2026-04-28
updated: 2026-04-28
---

## 用户诉求

用户需要在排查 session 时直接查看完整的 LLM request / response payload。Analyzer Agent 的分析有价值，但不能替代对真实请求体的直接确认。

## 背景

BUG-F、BUG-G 等事故都需要查看实际发给 OpenAI-compatible provider 的 payload 才能定性。现有 Trace 页会裁剪 input / output，Session 页和 Trace 页又是分离的，排查时需要人工来回拼接。

## 期望结果

用户能打开一个 session 级详情视图，看到消息时间线，展开关联的 LLM / Tool span，并按需加载完整 raw request / raw response。

## 约束

- P15 GetTraceTool 继续保持裁剪输出，避免浪费 Agent token。
- 完整 payload 是给人看的旁路通道，不作为 Agent 默认上下文。
- HTTP header 中的敏感字段不能落库。
- 首版不急着做自动诊断 UI，先观察真实 payload 和用户使用方式。

## 未决问题

- [ ] 开工前确认 Q1、Q2、Q4、Q5、Q6、Q7、Q8 草拟决策。
- [ ] 先统计现有 `t_trace_span` 的 LLM_CALL 行数，再决定 Flyway ETL 还是异步 ETL。
