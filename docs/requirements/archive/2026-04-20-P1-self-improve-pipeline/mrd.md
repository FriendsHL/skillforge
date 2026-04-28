# P1 MRD

---
id: P1
status: done
source: historical-backfill
created: 2026-04-16
updated: 2026-04-29
---

## 用户诉求

历史补录：SkillForge 需要具备自我评估、自我归因和自动改进 prompt/skill 的能力。

## 背景

Agent 平台如果没有系统性 eval 和改进闭环，就无法判断改动是否变好，也难以避免 Goodhart 问题。

## 期望结果

建立 scenario、runner、judge、attribution、A/B、prompt promotion 和 Goodhart 防护机制。

## 约束

- 评测执行需要隔离线程池，避免死锁。
- 自动晋升必须有阈值和防护。
- 评测数据要可追溯。

## 未决问题

- 无。主线已交付。
