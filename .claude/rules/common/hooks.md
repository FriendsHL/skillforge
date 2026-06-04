# Hooks

> Claude Code 自动化 hook 的跨语言通用基线。语言特化内容见 [java/hooks.md](../java/hooks.md) / [typescript/hooks.md](../typescript/hooks.md) / [web/hooks.md](../web/hooks.md)，各自以 `> extends` 引用本文件。

## 原则

- **优先用项目本地工具链** —— 不要把 hook 接到远程一次性包执行（如 `npx <远程包>`），用 repo 自带的依赖入口
- hook 命令要快、幂等、失败时给清晰报错
- 敏感操作（删除 / 覆盖 / 推送）的 hook 要保守

## PostToolUse（编辑后）

编辑文件后自动跑的检查，推荐顺序：

1. **格式化** —— 用项目既有 formatter（prettier / google-java-format 等）
2. **lint** —— 跑项目 linter，可 `--fix`
3. **类型检查 / 编译** —— `tsc --noEmit` / `mvn compile` 等
4. **构建验证** —— 重操作放 Stop hook，不每次编辑都跑

## PreToolUse（执行前）

- 守护危险命令（如 `rm -rf` / 强制 push）
- 守护超大文件写入（从 tool input content 判断行数，而不是读可能还不存在的文件）

## Stop（会话结束）

- 最终构建 / 测试验证，确保收尾时仓库可构建

## 配置位置

| 位置 | 影响范围 |
|---|---|
| `~/.claude/settings.json` | 全部项目 |
| `<project>/.claude/settings.json` | 协作者共享 |
| `<project>/.claude/settings.local.json` | 仅本机本项目 |
