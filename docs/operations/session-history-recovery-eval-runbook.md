# Session History Recovery Batch 8 评测 Runbook

本文只描述离线 evaluator 和 PostgreSQL 性能门。它不打开任何生产 flag，不注册 Recovery
Attachment/marker/hook，也不能替代 S11/S12/unknown-outcome 的真实 Dashboard 浏览器验收或 crash
fault-injection 套件。

## 固定输入

- 主 arm：`A/B/C/D`，精确定义与冻结 PRD 一致。
- 正交变量：`R0=NONE`、`R1=REFERENCE_ONLY`、`R2=SANITIZED_LEGACY`，仅存在于 evaluator result key。
- model：`scripted-history-agent-v1`。
- provider：`deterministic-fixture`。
- context window：128,000 tokens。
- prompt version：`session-history-eval-prompt-v1`。
- fixture version：`session-history-s1-s25-v1`。
- projection version：2（2026-09-09 修复原始 TextNode 工具结果脱敏；旧 cursor 应返回 `HISTORY_PROJECTION_CHANGED`）。
- fixture：`skillforge-server/src/test/resources/eval/session-history-recovery/s1-s25-v1.json`。

该 deterministic suite 是合同回归与 evaluator 校准，不应冒充真实模型质量实验。真实模型比较必须复制
同一 fixture、prompt、context、projection，分别记录 model/provider，且不得把不同版本的 arm 混在同一
差值中。

## 运行

共享工作树先确认没有其他 Maven 在使用 `target/`，不要执行 `clean`：

```bash
mvn -pl skillforge-server -am -DskipTests test-compile
mvn -pl skillforge-server -am -Dtest=SessionHistoryRecoveryEvalTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl skillforge-server -am -Dtest=SessionHistorySearchPerformancePostgresIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

若本机 Docker 已缓存 PostgreSQL，但 Docker Hub 暂时无法拉取 Ryuk，可仅在隔离开发机临时使用：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
  mvn -pl skillforge-server -am -Dtest=SessionHistorySearchPerformancePostgresIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

禁用 Ryuk 会失去 Testcontainers 自动清理保证，不用于 CI；测试结束后应确认临时容器已停止。

## 断言口径

- 25 scenarios × 4 arms × 3 Recovery variants = 300 个唯一 case。
- D-A exact recovery `>=20pp`，并输出 C-A、D-C。
- ref correctness `>=98%`；leakage `=0`；unnecessary History `<10%`。
- S1/S25 的每个组合都必须 `historyCalls=0`。
- S15 的每个组合都走 `FILE_READ`；S16 都走 `TASK_LIST`；R1/R2 不改变生产路径。
- S25 D 相对 A quality drop `<=2pp`。
- Search+Read 的真实 JSON schemas 由 `TokenEstimator` 计数，p95 门为
  `min(1500, contextWindow*5%)`。
- `SessionHistoryWireFormatter` 的真实低信任 wrapper `<=32000` chars。

性能基准在 PostgreSQL 16 插入单 Session 100,000 条 row-store 文本消息，目标关键词只位于最后一条；
先 `ANALYZE`，预热 2 次，再采样 20 次端到端 `SessionHistoryQueryService.search`，以 nearest-rank
p95 断言 `<=500ms`。测试同时输出当前 message scan 的 `EXPLAIN (ANALYZE, BUFFERS)`。

## 结果判读与停止条件

- 任一安全/质量门失败：Batch 8 不可放行。
- DB `EXPLAIN` 快但端到端慢：先检查 JDBC 行搬运、Jackson 投影和 JVM 排序，不直接加索引。
- DB scan 本身慢：再检查 owner/session/seq/order 索引与统计信息。
- 任何跨 Session 泄漏、History 自指命中、wire 被 40K truncator 截断、生产 Recovery 被注入：立即停止。
- 回滚演练必须先关 envelope，再关 master，并由新二进制闭合 unresolved attempt/inbox；本 evaluator
  不执行生产回滚动作。


## 可选真实模型烟测（2026-09-09）

`SessionHistoryLiveProviderIT` 使用生产 Provider、History schema、Search/Read query、validator 和 wire formatter，
存储与当前 Session scope 使用合成夹具。模型必须在缺失编号时自行 Search → Read 后原样回答；尾部信息充分时不调用 History。
它不启动生产 Session、不修改 feature flags，也不证明数据库 durability 或完整 AgentLoopEngine 闭环。
这两条 smoke 不能替代固定模型的完整 A/B/C/D × S1–S25 质量实验。

```bash
HISTORY_LIVE_SMOKE=true HISTORY_LIVE_PROVIDER=ark \
  mvn -pl skillforge-server -am -Dtest=SessionHistoryLiveProviderIT \
  -Dsurefire.failIfNoSpecifiedTests=false test

HISTORY_LIVE_SMOKE=true HISTORY_LIVE_PROVIDER=bailian-token-plan \
  mvn -pl skillforge-server -am -Dtest=SessionHistoryLiveProviderIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

需要分别通过环境提供 `ARK_API_KEY` 或 `BAILIAN_TOKEN_PLAN_API_KEY`；不把密钥写进测试代码或命令。
可用 `HISTORY_LIVE_MODEL` 覆盖对应 provider 的默认模型。普通回归不会运行外部调用。
2026-09-09 实跑：Ark 返回 `InvalidSubscription`，百炼 Token Plan 返回 `insufficient_quota`（周额度耗尽），
因此真实模型行为验收仍为 **BLOCKED_ENV**，不能宣称恢复质量达标。


### DeepSeek 直连复测

```bash
HISTORY_LIVE_SMOKE=true HISTORY_LIVE_PROVIDER=deepseek HISTORY_LIVE_MODEL=deepseek-v4-pro \
  mvn -pl skillforge-server -am -Dtest=SessionHistoryLiveProviderIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

凭据为 `DEEPSEEK_API_KEY`，与当前 Main Assistant 的 `deepseek:deepseek-v4-pro` 配置一致。
2026-09-09 复测通过：1 test，零 failure/error/skip，`BUILD SUCCESS`。缺失编号场景 5 次 Search + 1 次 Read，
精确回答随机编号；信息充分场景 0 次 History。两场景共 5 次 provider 请求（可含并行工具调用）。
这消除了真实模型 smoke 的账号阻塞，但 5 次 Search 的定位效率需后续对照实验评估；仍非完整生产 Session/E2E 或 A-D 质量验收。

### Search 效率对照

在上述 opt-in 命令中加 `HISTORY_LIVE_SEARCH_BASELINE=true` 可使用优化前的工具说明；
省略或设为 false 使用当前生产说明。两组固定工具顺序，其他夹具/执行路径相同，每次随机生成目标编号。
`HISTORY_SMOKE_TOOL` 记录合成查询参数、ref 数与工具耗时；`HISTORY_SMOKE_PROVIDER` 记录模型耗时和 usage。
比较时分别统计工具调用数与 provider 轮数，并重复采样；并行重复 Search 的减少不等于少一轮模型调用。
2026-09-09 的小样本对照及限制见需求目录 `review-2026-09-09.md` 的 Search 效率专项。
