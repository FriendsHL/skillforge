import React, { useEffect } from 'react';
import {
  Drawer,
  Form,
  Input,
  Select,
  Switch,
  Button,
  Space,
  Divider,
  Typography,
  message,
} from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  createMcpServer,
  updateMcpServer,
} from '../../api/mcpServers';
import { useAuth } from '../../contexts/AuthContext';
import type {
  McpServer,
  McpServerCreate,
  McpServerUpdate,
  McpTransport,
} from '../../types/mcpServer';

const { TextArea } = Input;
const { Text } = Typography;

/**
 * Server-name format mirrors BE INV-3 exactly: `[a-z0-9_]+`, length ≤ 32.
 * Re-validated FE-side so the user gets inline feedback before round-
 * tripping; the BE remains the source of truth for the rule (uniqueness
 * + reserve list checks).
 */
const NAME_RE = /^[a-z0-9_]+$/;

/**
 * BE r2 W3 masks every literal env value to this sentinel in
 * `McpServerResponse.from(...)` before returning it to the dashboard.
 *
 * **P11 r3**: FE forwards rows with this value verbatim. BE r2.5 has
 * preserve-on-*** logic that recognises the sentinel in the PUT body and
 * substitutes back the stored secret before persisting. Filtering on the
 * FE would defeat that — BE would never see the sentinel and would PUT-
 * replace the env JSONB with a map missing the masked key, deleting the
 * real secret.
 *
 * Keep in sync with the BE constant (`McpServerResponse.MASKED_ENV_VALUE`
 * or equivalent). This is brittle on purpose — if BE ever changes the
 * mask string, type-checker won't catch it; rely on Phase-Final manual
 * e2e (edit a server with a real env, save without changes, re-fetch
 * server → real secret still in BE storage) to flag drift.
 */
const MASKED_VALUE = '***';

const PLUS_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden>
    <path d="M8 3v10M3 8h10" />
  </svg>
);

const MINUS_ICON = (
  <svg width={12} height={12} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden>
    <path d="M3 8h10" />
  </svg>
);

/**
 * One key-value row (env var or HTTP header). Form.List stores objects
 * under `envEntries` / `headerEntries`; the submit handler converts them to
 * `Record<string, string>` while filtering empty keys (so a half-typed row
 * doesn't poison the wire payload).
 */
interface KvEntry {
  key: string;
  value: string;
}

/**
 * Internal form shape. The drawer stores `args`, `envEntries` and
 * `headerEntries` as arrays under `Form.List` for direct rendering; on
 * submit the values are normalised to the wire shape (`string[]` and
 * `Record<string, string>`). Only the fields relevant to the selected
 * `transport` are sent (command/args/env for stdio; url/headers for http).
 */
interface FormValues {
  name: string;
  transport: McpTransport;
  command?: string;
  args: string[];
  envEntries: KvEntry[];
  url?: string;
  headerEntries: KvEntry[];
  description?: string;
  enabled: boolean;
}

export interface McpServerEditDrawerProps {
  open: boolean;
  /** `null` = create mode; non-null = edit mode. */
  server: McpServer | null;
  onClose: () => void;
}

/**
 * Convert a `Record<string, string>` map (env or headers) into the
 * `KvEntry[]` shape Form.List wants. Stable iteration order matters —
 * without it the form shifts around between renders if the user partially
 * edits a row.
 */
function recordToEntries(record: Record<string, string> | undefined): KvEntry[] {
  if (!record) return [];
  return Object.entries(record).map(([key, value]) => ({ key, value }));
}

/**
 * Inverse of {@link recordToEntries}. Empty keys are dropped (a partially-
 * filled new row should not produce a `"" → ""` entry) and duplicate keys
 * keep the last value (matches `Object.fromEntries` semantics).
 *
 * P11 r3: do NOT filter out *** entries here. The BE preserve-on-***
 * logic (for both env and headers) requires seeing the {@link MASKED_VALUE}
 * sentinel in the request body to trigger the secret-preservation path. If
 * FE filters them, BE PUT-replaces semantics will drop the key from DB →
 * real secret loss. The two defences must compound (FE sends ***, BE
 * substitutes back to the stored secret), not run sequentially.
 *
 * User-facing semantics from the form hint stay the same: "leave a row at
 * *** to keep the existing secret; re-type to overwrite". The wire
 * mechanism that makes that promise true changed (was: FE drops; now: BE
 * preserves), but the user contract is identical.
 */
function entriesToRecord(entries: KvEntry[] | undefined): Record<string, string> {
  if (!entries) return {};
  const out: Record<string, string> = {};
  for (const e of entries) {
    if (!e || typeof e.key !== 'string') continue;
    const k = e.key.trim();
    if (!k) continue;
    out[k] = typeof e.value === 'string' ? e.value : '';
  }
  return out;
}

/**
 * Shared key-value Form.List editor used for both env vars (stdio) and HTTP
 * headers (http). Keeping a single implementation means the masking
 * round-trip contract (rows left at `***` survive verbatim) behaves
 * identically for both — see {@link entriesToRecord}.
 */
interface KvListEditorProps {
  /** Form.List name — `'envEntries'` or `'headerEntries'`. */
  name: 'envEntries' | 'headerEntries';
  keyPlaceholder: string;
  valuePlaceholder: string;
  addLabel: string;
  removeAriaLabel: string;
  /** Validator for the key field; rejected message surfaces inline. */
  validateKey: (value: string) => Promise<void>;
}

const KvListEditor: React.FC<KvListEditorProps> = ({
  name,
  keyPlaceholder,
  valuePlaceholder,
  addLabel,
  removeAriaLabel,
  validateKey,
}) => (
  <Form.List name={name}>
    {(fields, { add, remove }) => (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {fields.map((field) => (
          <Space key={field.key} style={{ display: 'flex' }} align="baseline">
            <Form.Item
              name={[field.name, 'key']}
              style={{ marginBottom: 0, width: 200 }}
              rules={[{ validator: (_, value: string) => validateKey(value) }]}
            >
              <Input placeholder={keyPlaceholder} />
            </Form.Item>
            <Form.Item
              name={[field.name, 'value']}
              style={{ marginBottom: 0, width: 200 }}
            >
              <Input placeholder={valuePlaceholder} />
            </Form.Item>
            <Button
              type="text"
              size="small"
              icon={MINUS_ICON}
              aria-label={removeAriaLabel}
              onClick={() => remove(field.name)}
            />
          </Space>
        ))}
        <Button
          type="dashed"
          onClick={() => add({ key: '', value: '' })}
          icon={PLUS_ICON}
          style={{ width: 200 }}
        >
          {addLabel}
        </Button>
      </div>
    )}
  </Form.List>
);

/**
 * Env var names: BE accepts any name but we forbid `=` and whitespace
 * because `Map.Entry<String,String>` can't carry them through
 * ProcessBuilder cleanly.
 */
function validateEnvKey(value: string): Promise<void> {
  if (!value) return Promise.resolve();
  return /[\s=]/.test(value)
    ? Promise.reject(new Error('Env name cannot contain whitespace or `=`'))
    : Promise.resolve();
}

/**
 * HTTP header names are RFC 7230 tokens — no whitespace and no `:` (the
 * name/value delimiter). We keep the check lenient (any other token char is
 * fine) and let the BE / server reject anything truly malformed.
 */
function validateHeaderKey(value: string): Promise<void> {
  if (!value) return Promise.resolve();
  return /[\s:]/.test(value)
    ? Promise.reject(new Error('Header name cannot contain whitespace or `:`'))
    : Promise.resolve();
}

const McpServerEditDrawer: React.FC<McpServerEditDrawerProps> = ({
  open,
  server,
  onClose,
}) => {
  const [form] = Form.useForm<FormValues>();
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const isEdit = server !== null;

  // The stdio↔http conditional is *derived*, never stored in state — mirroring
  // it into state + syncing via useEffect trips `react-hooks/set-state-in-effect`.
  //   - Edit mode: transport is immutable post-create (BE chk_mcp_transport; the
  //     Select is disabled), so we read it straight off the `server` prop. This
  //     is also why we don't depend on Form.useWatch here: its store update lands
  //     a tick after the init `setFieldsValue`, which previously rendered the
  //     wrong branch on edit-open and mis-fired required-field validation.
  //   - Create mode: the user can change the Select, so we track the live form
  //     value via Form.useWatch. The watch lag is harmless here because the
  //     create default is 'stdio' (matched by the `?? 'stdio'` fallback) and
  //     subsequent changes are user-driven, not programmatic.
  const watchedTransport = Form.useWatch('transport', form);
  const transport: McpTransport = server
    ? server.transport ?? 'stdio'
    : watchedTransport ?? 'stdio';
  const isHttp = transport === 'http';

  // ─── Initialize / reset form on open ─────────────────────────────────────
  useEffect(() => {
    if (!open) return;
    if (server) {
      form.setFieldsValue({
        name: server.name,
        transport: server.transport ?? 'stdio',
        command: server.command ?? '',
        // antd's Form.List wants a defensive copy; sharing the reference
        // can let an in-place mutation in the form leak back to React Query
        // cache.
        args: [...(server.args ?? [])],
        envEntries: recordToEntries(server.env),
        url: server.url ?? '',
        headerEntries: recordToEntries(server.headers),
        description: server.description ?? '',
        enabled: server.enabled,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({
        name: '',
        transport: 'stdio',
        command: '',
        args: [],
        envEntries: [],
        url: '',
        headerEntries: [],
        description: '',
        enabled: true,
      });
    }
  }, [open, server, form]);

  // ─── Save mutation ───────────────────────────────────────────────────────
  const { mutate: save, isPending } = useMutation({
    mutationFn: async (values: FormValues) => {
      const transportValue: McpTransport = values.transport ?? 'stdio';
      const http = transportValue === 'http';
      // Filter empty args so a stale "Add" row doesn't ship as `""`
      // and break ProcessBuilder's argv handling on the BE.
      const args = (values.args ?? [])
        .map((a) => (typeof a === 'string' ? a : ''))
        .filter((a) => a.length > 0);
      const env = entriesToRecord(values.envEntries);
      const headers = entriesToRecord(values.headerEntries);
      const url = values.url?.trim() ?? '';
      const description = values.description?.trim() || null;

      if (isEdit && server) {
        // r2 W1 fix — never send `name` on PUT. The field is also `disabled`
        // in the UI but defence-in-depth: a wire-level `name` could
        // theoretically silently rewrite the column if a future BE relaxes
        // the rename rule, and renaming would break every agent's
        // comma-list `mcp_server_ids` reference (server names are the FK
        // surrogate, not the numeric id). Omitting the field here is also
        // closer to PATCH semantics — the PUT body is "fields you want to
        // change", and name is explicitly not changeable.
        //
        // transport is immutable post-create (BE), so it's never sent. We
        // only send the columns that belong to the row's transport so a
        // stale value in the other branch's form fields can't leak through.
        const body: McpServerUpdate = http
          ? { url, headers, description, enabled: values.enabled }
          : { command: values.command, args, env, description, enabled: values.enabled };
        return updateMcpServer(server.id, body, userId);
      }
      const base = {
        name: values.name,
        transport: transportValue,
        description,
        enabled: values.enabled,
      };
      const body: McpServerCreate = http
        ? { ...base, url, headers }
        : { ...base, command: values.command, args, env };
      return createMcpServer(body, userId);
    },
    onSuccess: () => {
      message.success(isEdit ? 'MCP server updated' : 'MCP server created');
      queryClient.invalidateQueries({ queryKey: ['mcp-servers'] });
      onClose();
    },
    onError: (e: unknown) => {
      const detail = e instanceof Error ? e.message : 'unknown error';
      message.error(`Save failed: ${detail}`);
    },
  });

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      save(values);
    } catch {
      // antd surfaces validation errors inline — nothing to do here.
    }
  };

  return (
    <Drawer
      title={isEdit ? `Edit ${server?.name ?? 'MCP server'}` : 'New MCP server'}
      open={open}
      onClose={onClose}
      width={560}
      destroyOnClose
      footer={
        <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="primary"
            loading={isPending}
            onClick={handleSubmit}
            data-testid="mcp-edit-submit"
          >
            {isEdit ? 'Save' : 'Create'}
          </Button>
        </Space>
      }
    >
      <Form form={form} layout="vertical" requiredMark="optional">
        <Form.Item
          label="Name"
          name="name"
          rules={[
            { required: true, message: 'Name is required' },
            { max: 32, message: 'Name must be 32 characters or fewer' },
            {
              validator: (_, value: string) => {
                if (!value) return Promise.resolve();
                return NAME_RE.test(value)
                  ? Promise.resolve()
                  : Promise.reject(
                      new Error('Use only lowercase letters, digits, and underscores'),
                    );
              },
            },
          ]}
          extra={
            <Text type="secondary" style={{ fontSize: 11 }}>
              Used as prefix in <code>mcp_&lt;name&gt;_&lt;tool&gt;</code>. Allowed:{' '}
              <code>[a-z0-9_]+</code>, ≤ 32 chars.
            </Text>
          }
        >
          <Input
            placeholder="e.g. time"
            disabled={isEdit /* renaming would break per-agent mcp_server_ids references */}
          />
        </Form.Item>

        <Form.Item
          label="Transport"
          name="transport"
          rules={[{ required: true, message: 'Transport is required' }]}
          extra={
            <Text type="secondary" style={{ fontSize: 11 }}>
              <code>stdio</code> spawns a local child process;{' '}
              <code>http</code> calls a remote JSON-RPC endpoint. Immutable
              after creation — delete &amp; recreate to switch.
            </Text>
          }
        >
          <Select
            disabled={isEdit /* transport is immutable post-create (BE chk_mcp_transport) */}
            options={[
              { value: 'stdio', label: 'stdio (local child process)' },
              { value: 'http', label: 'http (remote JSON-RPC)' },
            ]}
          />
        </Form.Item>

        {isHttp ? (
          <Form.Item
            label="URL"
            name="url"
            rules={[
              { required: true, message: 'URL is required for http transport' },
              {
                validator: (_, value: string) => {
                  if (!value) return Promise.resolve();
                  return /^https?:\/\//i.test(value.trim())
                    ? Promise.resolve()
                    : Promise.reject(new Error('URL must start with http:// or https://'));
                },
              },
            ]}
            extra={
              <Text type="secondary" style={{ fontSize: 11 }}>
                Remote MCP endpoint, e.g.{' '}
                <code>https://api.example.com/mcp</code>. JSON-RPC is POSTed
                here.
              </Text>
            }
          >
            <Input placeholder="https://api.example.com/mcp" />
          </Form.Item>
        ) : (
          <Form.Item
            label="Command"
            name="command"
            rules={[
              { required: true, message: 'Command is required' },
              { max: 256, message: 'Command must be 256 characters or fewer' },
            ]}
            extra={
              <Text type="secondary" style={{ fontSize: 11 }}>
                First arg of <code>ProcessBuilder</code>, e.g. <code>npx</code> or{' '}
                <code>java</code>. Run directly — no shell expansion.
              </Text>
            }
          >
            <Input placeholder="npx" />
          </Form.Item>
        )}

        <Divider style={{ margin: '8px 0 16px' }} />

        {!isHttp && (
          <Form.Item
            label="Arguments"
            extra={
              <Text type="secondary" style={{ fontSize: 11 }}>
                Each row is one argv entry. Empty rows are dropped on save.
              </Text>
            }
          >
            <Form.List name="args">
              {(fields, { add, remove }) => (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {fields.map((field) => (
                    <Space key={field.key} style={{ display: 'flex' }} align="baseline">
                      <Form.Item
                        name={field.name}
                        style={{ marginBottom: 0, width: 380 }}
                      >
                        <Input placeholder="-y" />
                      </Form.Item>
                      <Button
                        type="text"
                        size="small"
                        icon={MINUS_ICON}
                        aria-label="Remove argument"
                        onClick={() => remove(field.name)}
                      />
                    </Space>
                  ))}
                  <Button
                    type="dashed"
                    onClick={() => add('')}
                    icon={PLUS_ICON}
                    style={{ width: 200 }}
                  >
                    Add argument
                  </Button>
                </div>
              )}
            </Form.List>
          </Form.Item>
        )}

        {!isHttp && (
          <Form.Item
            label="Environment variables"
            extra={
              <Text type="secondary" style={{ fontSize: 11 }}>
                Values may use <code>${'${VAR}'}</code> placeholders resolved from the host
                env at spawn time. Empty keys are dropped. <strong>Existing secret values
                are shown as <code>{MASKED_VALUE}</code></strong> — re-type to overwrite.
                Rows left at <code>{MASKED_VALUE}</code> preserve the existing stored secret
                (the backend recognises the mask sentinel and substitutes back the real
                value before persisting).
              </Text>
            }
          >
            <KvListEditor
              name="envEntries"
              keyPlaceholder="GITHUB_TOKEN"
              valuePlaceholder="${GITHUB_PAT}"
              addLabel="Add env var"
              removeAriaLabel="Remove env var"
              validateKey={validateEnvKey}
            />
          </Form.Item>
        )}

        {isHttp && (
          <Form.Item
            label="Headers"
            extra={
              <Text type="secondary" style={{ fontSize: 11 }}>
                HTTP request headers (e.g. <code>Authorization</code>). Values may use{' '}
                <code>${'${VAR}'}</code> placeholders resolved from the host env at request
                time. Empty keys are dropped. <strong>Existing secret values are shown as{' '}
                <code>{MASKED_VALUE}</code></strong> — re-type to overwrite. Rows left at{' '}
                <code>{MASKED_VALUE}</code> preserve the existing stored secret (the backend
                recognises the mask sentinel and substitutes back the real value before
                persisting).
              </Text>
            }
          >
            <KvListEditor
              name="headerEntries"
              keyPlaceholder="Authorization"
              valuePlaceholder="Bearer ${API_KEY}"
              addLabel="Add header"
              removeAriaLabel="Remove header"
              validateKey={validateHeaderKey}
            />
          </Form.Item>
        )}

        <Divider style={{ margin: '8px 0 16px' }} />

        <Form.Item
          label="Description"
          name="description"
          rules={[{ max: 512, message: 'Description too long (≤ 512 chars)' }]}
        >
          <TextArea
            rows={3}
            placeholder="Optional — what this server is for, auth model, etc."
          />
        </Form.Item>

        <Form.Item label="Enabled" name="enabled" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Drawer>
  );
};

export default McpServerEditDrawer;
