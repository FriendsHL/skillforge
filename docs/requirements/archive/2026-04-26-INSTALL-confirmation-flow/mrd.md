# Install Confirmation MRD

---
id: INSTALL-CONFIRM
status: done
source: historical-backfill
created: 2026-04-26
updated: 2026-04-29
---

## 用户诉求

历史补录：安装外部 skill / agent / package 前，需要明确用户授权，避免 Agent 自行执行高风险安装。

## 背景

ClawHub / SkillHub install 涉及外部内容和执行能力，必须有 human-in-the-loop confirmation。

## 期望结果

高风险 install 操作生成确认请求，用户确认后才继续执行；Web 和飞书渠道都能表达确认卡。

## 约束

- 未确认必须 fail-closed。
- token 一次性使用。
- 确认流不能阻塞 hook 或主线程造成死锁。

## 未决问题

- 无。需求已交付。
