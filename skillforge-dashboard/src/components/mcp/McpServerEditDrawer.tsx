import React, { useEffect } from 'react';
import {
  Drawer,
  Form,
  Input,
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
 * One env-var KV row. Form.List stores objects under `envEntries`; the
 * submit handler converts them to `Record<string, string>` while filtering
 * empty keys (so a half-typed row doesn't poison the wire payload).
 */
interface EnvEntry {
  key: string;
  value: string;
}

/**
 * Internal form shape. The drawer stores `args` and `envEntries` as arrays
 * under `Form.List` for direct rendering; on submit the values are
 * normalised to the wire shape (`string[]` and `Record<string, string>`).
 */
interface FormValues {
  name: string;
  command: string;
  args: string[];
  envEntries: EnvEntry[];
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
 * Convert a `Record<string, string>` env map into the `EnvEntry[]` shape
 * Form.List wants. Stable iteration order matters — without it the form
 * shifts around between renders if the user partially edits a row.
 */
function envMapToEntries(env: Record<string, string> | undefined): EnvEntry[] {
  if (!env) return [];
  return Object.entries(env).map(([key, value]) => ({ key, value }));
}

/**
 * Inverse of {@link envMapToEntries}. Empty keys are dropped (a partially-
 * filled new row should not produce a `"" → ""` env var) and duplicate
 * keys keep the last value (matches `Object.fromEntries` semantics).
 *
 * P11 r3: do NOT filter out *** entries here. BE r2.5 preserve-on-***
 * logic requires seeing the {@link MASKED_VALUE} sentinel in the request
 * body to trigger the secret-preservation path. If FE filters them, BE
 * PUT-replaces semantics will drop the key from DB → real secret loss.
 * The two defences must compound (FE sends ***, BE substitutes back to
 * the stored secret), not run sequentially.
 *
 * User-facing semantics from the form hint stay the same: "leave a row
 * at *** to keep the existing secret; re-type to overwrite". The wire
 * mechanism that makes that promise true changed (was: FE drops; now:
 * BE preserves), but the user contract is identical.
 */
function entriesToEnvMap(entries: EnvEntry[] | undefined): Record<string, string> {
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

const McpServerEditDrawer: React.FC<McpServerEditDrawerProps> = ({
  open,
  server,
  onClose,
}) => {
  const [form] = Form.useForm<FormValues>();
  const queryClient = useQueryClient();
  const { userId } = useAuth();
  const isEdit = server !== null;

  // ─── Initialize / reset form on open ─────────────────────────────────────
  useEffect(() => {
    if (!open) return;
    if (server) {
      form.setFieldsValue({
        name: server.name,
        command: server.command,
        // antd's Form.List wants a defensive copy; sharing the reference
        // can let an in-place mutation in the form leak back to React Query
        // cache.
        args: [...(server.args ?? [])],
        envEntries: envMapToEntries(server.env),
        description: server.description ?? '',
        enabled: server.enabled,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({
        name: '',
        command: '',
        args: [],
        envEntries: [],
        description: '',
        enabled: true,
      });
    }
  }, [open, server, form]);

  // ─── Save mutation ───────────────────────────────────────────────────────
  const { mutate: save, isPending } = useMutation({
    mutationFn: async (values: FormValues) => {
      // Filter empty args so a stale "Add" row doesn't ship as `""`
      // and break ProcessBuilder's argv handling on the BE.
      const args = (values.args ?? [])
        .map((a) => (typeof a === 'string' ? a : ''))
        .filter((a) => a.length > 0);
      const env = entriesToEnvMap(values.envEntries);
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
        const body: McpServerUpdate = {
          command: values.command,
          args,
          env,
          description,
          enabled: values.enabled,
        };
        return updateMcpServer(server.id, body, userId);
      }
      const body: McpServerCreate = {
        name: values.name,
        command: values.command,
        args,
        env,
        description,
        enabled: values.enabled,
      };
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

        <Divider style={{ margin: '8px 0 16px' }} />

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
          <Form.List name="envEntries">
            {(fields, { add, remove }) => (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                {fields.map((field) => (
                  <Space key={field.key} style={{ display: 'flex' }} align="baseline">
                    <Form.Item
                      name={[field.name, 'key']}
                      style={{ marginBottom: 0, width: 200 }}
                      rules={[
                        {
                          validator: (_, value: string) => {
                            if (!value) return Promise.resolve();
                            // BE accepts any env var name; we forbid `=` and
                            // whitespace because `Map.Entry<String,String>`
                            // can't carry them through ProcessBuilder cleanly.
                            return /[\s=]/.test(value)
                              ? Promise.reject(
                                  new Error('Env name cannot contain whitespace or `=`'),
                                )
                              : Promise.resolve();
                          },
                        },
                      ]}
                    >
                      <Input placeholder="GITHUB_TOKEN" />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, 'value']}
                      style={{ marginBottom: 0, width: 200 }}
                    >
                      <Input placeholder="${GITHUB_PAT}" />
                    </Form.Item>
                    <Button
                      type="text"
                      size="small"
                      icon={MINUS_ICON}
                      aria-label="Remove env var"
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
                  Add env var
                </Button>
              </div>
            )}
          </Form.List>
        </Form.Item>

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
