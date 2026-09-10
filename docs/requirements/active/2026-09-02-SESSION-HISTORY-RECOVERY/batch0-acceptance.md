# Batch 0 验收记录

> 结果：`BATCH0_ACCEPTED`
> 日期：2026-09-02
> 责任方：root/Judge

## 冻结输入

- r6 方案 SHA-256：`29f87c8d288ed130bbe3e06ab4f003c9b1fd65588053819b54ff1d611338acbf`；
- r6 方案复核：`PASS_WITH_WARNINGS`；
- Batch 0 独立 Full spec review r1：Stage 1 `PASS`、Stage 2 `PASS_WITH_WARNINGS`、无 blocker，候选状态
  `BATCH0_ACCEPTED_CANDIDATE`。

独立复核的两条 warning 已在最终文档中关闭：

1. reviewer 只产生候选状态，root/Judge 才能记录仓库最终验收并释放 Batch 1；
2. W-R6-1 只承诺一个被接受的 durable run claim、inbox drain 和逻辑 transcript continuation。Provider 没有稳定
   idempotency contract 时，不承诺 post-dispatch/pre-persistence 崩溃窗口中的物理 HTTP exactly-once。

## 最终六文档清单

| 文档 | SHA-256 |
| --- | --- |
| `index.md` | `faccd0ef80f29f672fd3a2b1da80f3d537fd886cf4ca71ce2e71fc41b8be0ded` |
| `mrd.md` | `50838091d118e3c38e5809233613593b55a6e77b8af900a90790034604817cb9` |
| `prd.md` | `2061c3666302dbbee796d753d5d1d85a86af92ff2a003021b4881480eab8e711` |
| `tech-design.md` | `79454d1e53a9759526c95764fa553c00ef93c5258a89531dd6da1f2f6a27baf8` |
| `delivery-plan.md` | `8cf6eef2e53268a5d3d6ac16d418a993514358d219ef6c5ee8a77d5959129abb` |
| `research-report.md` | `c4c68be8379c30bb34048b634589e7879b339e0416eddf77ea67e95ae8f71bb6` |

## 放行检查

- S1–S25 在 PRD 各出现且只出现一次；
- 六个允许的 feature flags 与 r6 一致；
- 相对 Markdown 链接已检查，无缺失目标；
- `git diff --check` 通过；
- 记录本验收前，diff 中没有 V196–V198、entity、test、migration 或 production-code 变更；
- W-R6-1 与 W-R6-2 仍是 Batch 1 的强制 executable acceptance contract，不因文档验收被视为已实现。

Batch 1 foundation 自本记录起可以开始。任何对冻结协议的修改都会使本验收失效，并要求重新执行 Batch 0 独立复核。
