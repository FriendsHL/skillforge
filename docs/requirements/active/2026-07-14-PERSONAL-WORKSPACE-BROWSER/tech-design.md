# PERSONAL-WORKSPACE-BROWSER Tech Design

## 架构

```text
Chat RightRail / WorkspaceTab
  |-- This session -> existing attachment API
  `-- MySpace      -> GET /api/workspace/entries?path=...
                       GET /api/workspace/content?path=...
                              |
                    WorkspaceFileService
                      |-- configured root boundary
                      |-- normalized relative paths
                      |-- symlink containment checks
                      |-- sensitive-name deny rules
                      `-- per-repository gitignore policy
```

## 配置

```yaml
skillforge:
  workspace:
    root: ${SKILLFORGE_WORKSPACE_ROOT:${SKILLFORGE_ACP_REPO_ROOT:}}
    max-preview-bytes: 262144
    max-entries-per-directory: 500
```

`SKILLFORGE_WORKSPACE_ROOT` 是 Workspace 独立授权边界；仅在未配置时兼容回退到 ACP repo root。产品部署应显式设置独立变量。

## API

- `GET /api/workspace/entries?path=<relative>`：返回根 label、规范化相对路径、父路径、截断状态和当前层条目。
- `GET /api/workspace/content?path=<relative>`：返回安全文本预览、大小、更新时间、binary/truncated 标记。
- API 复用 Dashboard Bearer auth，不创建公开文件 URL。
- 响应统一 `Cache-Control: no-store`。

## 路径安全

1. 服务启动时解析配置根，但不把绝对路径下发前端。
2. 请求路径必须是相对路径；标准化后 resolve 到 root。
3. 对存在路径取 real path，并验证仍以 root real path 开头。
4. 每一级检查敏感名称与仓库忽略策略。
5. 不跟随会逃出授权根的符号链接。
6. 内容预览先校验常规文件、类型和大小，再做有界读取。

## 前端状态

- React Query key 包含 view/path/session/user cache scope。
- 保留已有数据时刷新失败，显示非破坏性提示，不把列表清空。
- 文件预览是 Workspace 内部子状态，不改变 Chat route。
- 当前目录过滤使用本地派生值，避免每次输入发请求。

## 测试

- `WorkspacePropertiesTest`：配置默认值和绑定。
- `WorkspaceFileServiceTest`：根、目录、文件、过滤策略、symlink、边界、截断。
- `WorkspaceControllerTest`：wire shape、状态码、no-store 和错误脱敏。
- `workspace.test.ts`、`WorkspaceTab.test.tsx`、`RightRailWorkspace.test.tsx`：契约和交互。

## 已知限制

- 文件变化采用请求时读取，没有文件系统 watcher。
- `.gitignore` 并发变化采用 best-effort 读取；没有对外写操作，因此不会造成写入竞态。
- 本地同账号恶意进程可在校验与读取之间替换文件，这是本机信任边界内的残余 TOCTOU 风险；未来开放写能力前必须使用更强的文件描述符级策略。
