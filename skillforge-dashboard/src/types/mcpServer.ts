/**
 * MCP-CLIENT-MVP — FE types.
 *
 * Mirrors the BE `t_mcp_server` row + dry-run / connection-status surfaces
 * exposed by `/api/mcp-servers`. See
 * `docs/requirements/active/MCP-CLIENT-MVP/tech-design.md` for the canonical
 * shape (V61 migration + REST table) and `prd.md` §5 for the dashboard
 * acceptance points.
 *
 * INV-3 (server-name format `[a-z0-9_]+`, length ≤ 32) is enforced both in
 * the BE service layer and again in `McpServerEditDrawer`'s Form rule, so
 * the type itself stays a plain `string` — narrow it client-side at the
 * form boundary, not in the wire type.
 */

/**
 * Connection status of the underlying stdio child process / JSON-RPC
 * session. The BE may also surface `null` / `undefined` for rows that have
 * never been spawned (e.g. just-created `enabled=false` rows) — page-level
 * code falls back to `'disconnected'` in that case.
 */
export type McpServerStatus = 'connected' | 'disconnected' | 'error';

/**
 * Transport the BE uses to talk to the server.
 *  - `stdio` — spawn a child process, speak JSON-RPC over its stdin/stdout
 *    (`command` + `args` + `env` apply).
 *  - `http`  — POST JSON-RPC to a remote endpoint (`url` + `headers` apply).
 *
 * String-literal union mirrors the BE `transport VARCHAR(16)` column
 * (V152 migration). Immutable post-create on the BE — see {@link McpServerUpdate}.
 */
export type McpTransport = 'stdio' | 'http';

/**
 * One row of `t_mcp_server` as returned by `GET /api/mcp-servers`.
 * BE camelCase per Spring Jackson default.
 *
 * `args` and `env` are JSONB columns parsed BE-side and surfaced to the FE
 * as native arrays / objects (not stringified JSON). `${VAR_NAME}`
 * placeholders inside `env` / `headers` values are resolved against
 * System.getenv at spawn / request time (INV-5) — FE keeps them verbatim.
 */
export interface McpServer {
  id: number;
  /** Used as prefix in `mcp_<name>_<tool>` tool registration. `[a-z0-9_]+`, ≤ 32. */
  name: string;
  /** Transport family. Defaults to `'stdio'` for legacy rows (V152 default). */
  transport: McpTransport;
  /**
   * First arg of ProcessBuilder, e.g. `npx` or `java`. **`null` for `http`
   * servers** — the BE dropped the `command NOT NULL` constraint in V152, so
   * http rows carry a null command and a non-null {@link url} instead.
   */
  command: string | null;
  /** Process args array, e.g. `["-y", "@modelcontextprotocol/server-time"]`. stdio only. */
  args: string[];
  /** Env-var map passed to the child process; values may use `${VAR}` placeholders. stdio only. */
  env: Record<string, string>;
  /** Remote JSON-RPC endpoint. Non-null for `http` servers; `null`/absent for `stdio`. */
  url?: string | null;
  /**
   * HTTP request headers (e.g. `Authorization`). `http` only. Secret values
   * are masked to `***` by the BE before returning, exactly like {@link env}
   * — FE forwards `***` rows verbatim so the BE preserve-on-mask path fires.
   */
  headers?: Record<string, string>;
  description?: string | null;
  enabled: boolean;
  /** Connection state — BE-derived; `undefined` when row has never been spawned. */
  status?: McpServerStatus;
  /** Number of `tools/list` entries advertised by the server. `undefined` when not yet connected. */
  toolCount?: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * One MCP tool descriptor as returned by `tools/list`. The `inputSchema` is
 * arbitrary JSON Schema — keep it as `Record<string, unknown>` so consumers
 * can render or pretty-print without forcing a strict type narrow.
 */
export interface McpToolDescriptor {
  name: string;
  description?: string | null;
  inputSchema?: Record<string, unknown> | null;
}

/**
 * Response of `POST /api/mcp-servers/{id}/test-connection`. Always 200 with
 * a discriminated body: `{success: true, tools: [...]}` on a clean
 * initialize+list+close cycle, `{success: false, error: "..."}` on any
 * stdio / protocol error. The BE never throws 5xx for connection issues
 * here — that would lose the reason string in axios's generic error
 * handling.
 */
export interface TestConnectionResponse {
  success: boolean;
  tools?: McpToolDescriptor[];
  error?: string;
}

/**
 * `POST /api/mcp-servers` body. `enabled` defaults to true server-side, so
 * leaving it undefined creates an enabled row.
 */
export interface McpServerCreate {
  name: string;
  /** `'stdio'` or `'http'`. BE defaults absent to `'stdio'`; the drawer always sends it. */
  transport: McpTransport;
  /** Required for `stdio`; omitted for `http`. */
  command?: string;
  /** stdio argv. */
  args?: string[];
  /** stdio env map; values may use `${VAR}` placeholders. */
  env?: Record<string, string>;
  /** Required for `http`; omitted for `stdio`. */
  url?: string;
  /** http request headers; values may use `${VAR}` placeholders. */
  headers?: Record<string, string>;
  description?: string | null;
  enabled?: boolean;
}

/**
 * `PUT /api/mcp-servers/{id}` body. All fields optional for patch
 * semantics — the BE leaves untouched columns alone.
 *
 * **`name` is intentionally absent**: server names are the FK surrogate
 * inside every agent's comma-list `mcp_server_ids`, so renaming would
 * silently break per-agent enablement (no JOIN keeps these in sync). The
 * edit drawer also greys out the field; this type-level omission is
 * defence-in-depth so a future caller can't accidentally re-enable the
 * rename path. Use a delete + create cycle if rename is genuinely needed.
 *
 * **`transport` is also intentionally absent**: like `name`, it is immutable
 * post-create on the BE (a stdio↔http flip would invalidate the
 * `chk_mcp_transport` CHECK constraint and orphan the command/url columns).
 * The edit drawer disables the transport selector and only sends the fields
 * relevant to the row's existing transport (command/args/env for stdio;
 * url/headers for http).
 */
export interface McpServerUpdate {
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  url?: string;
  headers?: Record<string, string>;
  description?: string | null;
  enabled?: boolean;
}

/**
 * 409 Conflict body returned by `DELETE /api/mcp-servers/{id}` when one or
 * more agents still have this server in their `mcp_server_ids` (INV-12).
 *
 * Tolerant parser in `mcpServers.ts` accepts both `{conflict: ["a","b"]}`
 * and the older `{message, agents: [...]}` envelope, so this type is the
 * canonical shape only — `parseDeleteConflict` returns
 * `{ agentNames: string[] }` regardless.
 */
export interface DeleteConflictBody {
  conflict?: string[];
  agents?: string[];
  message?: string;
}
