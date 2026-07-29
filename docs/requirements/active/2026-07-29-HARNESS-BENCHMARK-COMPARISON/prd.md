# PRD：SkillForge Harness 横向 Benchmark

## 要回答的问题

- SkillForge Agent Loop 是否提高成功率，而不只是增加 token 和工具调用？
- Prompt Assembly、Tool Search、Compact Recovery、Memory 各自贡献多少？
- 与其他 Harness 的差距来自模型、工具、上下文管理，还是恢复/编排能力？

## Benchmark 矩阵

| 维度 | 主 benchmark | SkillForge 能力映射 | 主指标 |
| --- | --- | --- | --- |
| Terminal/通用执行 | Terminal-Bench 2.0（Harbor） | Bash/File/Web/长循环 | pass@1、成功率、费用 |
| 软件工程 | SWE-bench Verified/Live | Repo 工具、Compact、恢复 | resolved、测试通过率 |
| 工具交互 | τ²-bench | Tool Schema、权限、状态机 | task success、policy violation |
| 长期记忆 | LongMemEval/V2 | Memory recall/provenance/update | 五类能力分数、误召回 |
| SkillForge 专项 | 内部 deterministic suite | kill recovery、多模态、MCP、SubAgent | invariant pass、恢复时间 |

公开排名和内部工程回归分开呈现，不混合成一个总分。

## 公平性约束

- 同模型、provider、temperature/reasoning effort、最大输出。
- 同 wall-clock、token、费用和最大循环预算。
- 不允许针对测试 ID、答案或 verifier 写特判。
- 不修改 benchmark timeout、资源和测试。
- 公开任务至少重复 3 次，报告均值、置信区间和失败分布。
- Harness 改造使用配对 A/B，每次只改变一个变量。

## 指标

一级：成功率、pass@1、policy compliance、恢复后最终成功率。

二级：输入/输出 token、cache read、工具调用数、无效重复调用、P50/P95 时延、
费用、compact 次数、恢复次数、上下文溢出率。

诊断：首次正确工具时间、参数错误率、检索命中率、memory provenance 正确率、
tool pairing 破坏数、stale reference 数。

## 验收

- 一条命令可运行单 benchmark/单 Harness/固定任务子集。
- 原始轨迹可追溯到 commit、配置、模型、数据集和 verifier 版本。
- 相同输入可重放，失败可归类为 model/harness/provider/environment/verifier。
- 基础设施失败不计为能力失败。
- 形成 SkillForge 与至少两个外部 Harness 的同模型对照报告。

