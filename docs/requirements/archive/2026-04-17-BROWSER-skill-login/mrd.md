# BrowserSkill 登录态 MRD

---
id: BROWSER-LOGIN
status: done
source: historical-backfill
created: 2026-04-17
updated: 2026-04-29
---

## 用户诉求

历史补录：BrowserSkill 自动化任务需要复用登录态，避免每次重新登录。

## 背景

浏览器类任务经常依赖网站登录状态。没有持久化登录态会导致自动化流程中断或重复要求人工登录。

## 期望结果

BrowserSkill 能持久化 profile / session，并提供可控的使用流程。

## 约束

- 登录态涉及敏感数据，必须明确存储路径和使用边界。
- 不应把浏览器登录态误作为通用凭据管理。

## 未决问题

- 无。已归档为参考设计。
