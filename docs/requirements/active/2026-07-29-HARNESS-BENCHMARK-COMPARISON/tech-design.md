# 技术设计：Harness Benchmark Comparison

## 运行模型

新增独立 `benchmark-runner`，不嵌入生产 Agent Loop：

```text
Dataset -> Environment -> HarnessAdapter -> TraceNormalizer -> Official Verifier
                                                        -> Run Manifest
```

Runner 拉取并校验数据集，创建隔离环境，启动 Harness，收集标准事件，调用官方
verifier，最后写不可变结果清单。

## Adapter

统一接口：

```text
prepare(task, budget)
run(task) -> HarnessRun
collectArtifacts()
terminate()
```

首批 Adapter：SkillForge、Claude Code、Codex CLI、OpenHands。认证信息只从环境变量读取，
结果仅保存 provider/model 标识和 key fingerprint。

## Run Manifest

- benchmark/dataset/version/taskId/verifierHash
- harness/name/version/commit/configHash
- provider/model/reasoning parameters
- image digest、CPU/RAM、network policy
- startedAt/finishedAt/exitReason
- token/cache/cost/tool/compact/recovery metrics
- verifier result、artifact hashes、trace path

聚合报告必须从 Manifest 重算，禁止手工填分。

## 失败分类

```text
TASK_FAILURE
POLICY_FAILURE
MODEL_CONTEXT_OVERFLOW
HARNESS_CRASH
PROVIDER_RATE_LIMIT
PROVIDER_UNAVAILABLE
ENVIRONMENT_FAILURE
VERIFIER_FAILURE
BUDGET_EXHAUSTED
```

只有任务、策略和预算失败进入能力分母；基础设施失败单列，避免 429 污染评测信号。

## Ablation

- P1 Assembly boundary on/off
- P4 Deferred Tool Schema on/off
- P5 Compact runtime restore on/off
- P6 Progressive Memory on/off
- SubAgent on/off

## 安全与成本

- 容器不访问宿主文件，workspace 按任务销毁。
- 网络默认关闭；需要网络时使用域名 allowlist。
- 每任务硬 token/费用/时间上限。
- kill 整个进程树，禁止遗留后台任务。
- 对轨迹中的 secret、PII、授权 header 做结构化脱敏。

