# Session History Recovery Batch 8 结果

> 采样日期：2026-09-04；这是离线 deterministic evaluator 与本机 PostgreSQL 16 性能证据，不是生产
> flag 放行记录，也不是 live LLM A/B 质量声明。

## 固定版本

| 项目 | 值 |
| --- | --- |
| model | `scripted-history-agent-v1` |
| provider | `deterministic-fixture` |
| context window | 128,000 tokens |
| prompt | `session-history-eval-prompt-v1` |
| fixture | `session-history-s1-s25-v1` |
| projection | 1 |
| primary arms | A/B/C/D |
| evaluator-only variants | R0/R1/R2 |

## Deterministic 合同矩阵

- 覆盖：S1–S25 × A/B/C/D × R0/R1/R2，共 300 case。
- exact recovery：A `0%`、C `93.33%`、D `100%`；C-A `+93.33pp`、D-C `+6.67pp`、D-A
  `+100pp`。
- locator/ref correctness：`100%`。
- leakage：`0`。
- unnecessary History：`0%`。
- S25 quality drop：`1pp`。
- Search+Read schema overhead p95：`1041 tokens`（gate `<=1500`）。
- representative real HISTORY low-trust wire：`18,547 chars`（gate `<=32,000`）。
- S1/S25：全部组合 0 次 History。
- R0/R1/R2：均被记录为正交 evaluator key，生产 Recovery 注入为 false。

这些数字是 scripted fixture 的 runner/gate 自校准结果；它证明计算口径、矩阵完整性和防混组约束，不能
替代在固定真实 model/provider 下重放产生的外部质量数字。

## 100K PostgreSQL Search

最终固定采样（`ANALYZE`、2 warmups + 20 samples）为：

```text
[164, 164, 165, 165, 170, 174, 176, 177, 180, 182,
 186, 190, 195, 202, 206, 208, 208, 253, 296, 334] ms
p95 = 296 ms (gate <= 500 ms)
```

对应 `EXPLAIN (ANALYZE, BUFFERS)`：数据库执行 `39.189ms`，读取 100,000 rows，使用
`uq_session_message_session_seq` backward index scan 和 incremental sort（peak memory `29kB`）。端到端与 SQL
的差值表明主要余量消耗在 JDBC 搬运、Jackson decode/authorized projection、Java filter/sort，而非纯 SQL
扫描。

当前端到端已经通过 500ms 门，因此本批不修改生产 History query/repository。最终验证按 runbook 的
`ANALYZE + 20 samples` 固定协议执行完成。

## 尚需独立证据

- 固定真实 LLM/provider 的 A-D 质量实验（当前为 deterministic scripted calibration）。
- S11/S12/unknown-outcome 的真实 Dashboard 浏览器验证。
- 全量 crash/recovery、role enforcement、restore ABA/audit、archive fallback 与生产回滚演练汇总。


## 2026-09-09 专项复审补充

原始 TextNode JSON 工具结果脱敏已修复，当前 projection 为 2。上方 2026-09-04 的脚本数值保留为历史记录，
不转记为新版真实模型结果。本轮实际验证与未关闭门禁见
[专项 Review 记录](../requirements/active/2026-09-02-SESSION-HISTORY-RECOVERY/review-2026-09-09.md)。
新增 opt-in 真实模型烟测已尝试：Ark 订阅无效、百炼 Token Plan 周额度耗尽，仍无真实模型行为通过证据。


### DeepSeek smoke 更新

2026-09-09，主 Agent 切换为 `deepseek:deepseek-v4-pro` 后复测 **BUILD SUCCESS**（1 test、两个场景）。
缺失事实经 5 次 Search + 1 次 Read 精确恢复随机编号；信息充分场景零 History 调用。
这是合成存储/scope 下的真实模型 smoke，补足此前账号阻塞的模型行为证据，不能代替完整生产链路或 A-D 质量实验。
