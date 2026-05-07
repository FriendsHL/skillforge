# MCP Client MVP

---
id: MCP-CLIENT-MVP
mode: full
status: done
priority: P1
risk: Full
created: 2026-05-07
updated: 2026-05-07
---

## 摘要

引入 Model Context Protocol (MCP) 客户端能力：SkillForge 作为 MCP host 连接外部 stdio MCP server，把 server 暴露的 tools 注入到 SkillRegistry 给 agent 使用。

**MVP 范围**：仅 Client（不做 Server）+ 仅 stdio transport（不做 HTTP/SSE）+ 全局 yaml 配置 + per-agent 选子集 + dashboard CRUD UI + env var auth + `mcp_<server>_<tool>` 前缀 + best-effort 启动 + lazy 重连。

**默认接入 dogfooding**：
- `@modelcontextprotocol/server-time`（官方，零配置，时区 / 当前时间，V61 migration 默认 seed 一行）

**协议层测试**走 Mockito 单测 + Phase Final 真 time server 端到端联调（不写内部 Java IT fixture，2026-05-07 ratify）。

后续 MCP server（Firecrawl / Postgres / Notion / Calendar 等）由用户在 dashboard `/mcp-servers` CRUD 自助接入，不在本期范围。

## 阅读顺序

1. [MRD](mrd.md) — 用户原始痛点 + 价值
2. [PRD](prd.md) — MVP 范围 / 验收点 / 决策点
3. [技术方案](tech-design.md) — 架构 / 11 ratify 决策 / INV / 模块拆分

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | [mrd.md](mrd.md) |
| PRD | [prd.md](prd.md) |
| 技术方案 | [tech-design.md](tech-design.md) |
