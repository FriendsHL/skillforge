import React, { useEffect, useState } from 'react';
import {
  Drawer,
  Form,
  Input,
  Select,
  Switch,
  Button,
  Space,
  Divider,
  message,
  Tooltip,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getAgents } from '../../api';
import {
  createChannelConfig,
  updateChannelConfig,
  testChannelConfig,
} from '../../api/channels';
import type { ChannelConfig } from '../../types/channel';
import type {
  CreateChannelConfigRequest,
  UpdateChannelConfigRequest,
} from '../../api/channels';
import './channels.css';

const COPY_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <rect x="5" y="5" width="9" height="9" rx="1.5" />
    <path d="M11 5V3.5A1.5 1.5 0 0 0 9.5 2H3.5A1.5 1.5 0 0 0 2 3.5V9.5A1.5 1.5 0 0 0 3.5 11H5" />
  </svg>
);

const TEST_ICON = (
  <svg width={13} height={13} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 3l10 5-10 5V3z" fill="currentColor" stroke="none" opacity={0.8} />
  </svg>
);

interface ChannelConfigDrawerProps {
  open: boolean;
  config: ChannelConfig | null;
  onClose: () => void;
}

interface AgentOption {
  id: number;
  name: string;
}

function normalizeAgents(raw: unknown[]): AgentOption[] {
  return raw.map((r) => {
    const a = r as Record<string, unknown>;
    return { id: Number(a.id), name: String(a.name || '') };
  });
}

function buildWebhookUrl(platform: string): string {
  return `${window.location.origin}/api/channels/${platform}/webhook`;
}

const platformOptions = [
  { value: 'feishu', label: '飞书 (Feishu)' },
  { value: 'telegram', label: 'Telegram' },
];

interface FormValues {
  platform: string;
  displayName: string;
  defaultAgentId: number;
  active: boolean;
  updateCredentials: boolean;
  feishu_appId?: string;
  feishu_appSecret?: string;
  feishu_verificationToken?: string;
  feishu_encryptKey?: string;
  telegram_botToken?: string;
  telegram_webhookSecret?: string;
}

const ChannelConfigDrawer: React.FC<ChannelConfigDrawerProps> = ({
  open,
  config,
  onClose,
}) => {
  const [form] = Form.useForm<FormValues>();
  const queryClient = useQueryClient();
  const isEdit = config !== null;
  const [platform, setPlatform] = useState<string>(config?.platform ?? 'feishu');
  const [updateCreds, setUpdateCreds] = useState(false);
  const [testing, setTesting] = useState(false);

  const { data: agentsRaw = [] } = useQuery({
    queryKey: ['agents'],
    queryFn: () => getAgents().then((r) => {
      const d = (r as { data: unknown }).data;
      return Array.isArray(d) ? d : [];
    }),
  });
  const agents = normalizeAgents(agentsRaw);

  useEffect(() => {
    if (!open) return;
    if (config) {
      form.setFieldsValue({
        platform: config.platform,
        displayName: config.displayName,
        defaultAgentId: config.defaultAgentId,
        active: config.active,
        updateCredentials: false,
      });
      setPlatform(config.platform);
      setUpdateCreds(false);
    } else {
      form.resetFields();
      form.setFieldsValue({ platform: 'feishu', active: true, updateCredentials: true });
      setPlatform('feishu');
      setUpdateCreds(true);
    }
  }, [open, config, form]);

  const { mutate: save, isPending } = useMutation({
    mutationFn: async (values: FormValues) => {
      if (isEdit && config) {
        const body: UpdateChannelConfigRequest = {
          displayName: values.displayName,
          defaultAgentId: values.defaultAgentId,
          active: values.active,
        };
        if (values.updateCredentials) {
          body.credentials = buildCredentials(values);
        }
        return updateChannelConfig(config.id, body);
      } else {
        const body: CreateChannelConfigRequest = {
          platform: values.platform,
          displayName: values.displayName,
          defaultAgentId: values.defaultAgentId,
          active: values.active,
          credentials: buildCredentials(values),
        };
        return createChannelConfig(body);
      }
    },
    onSuccess: () => {
      message.success(isEdit ? 'Channel updated' : 'Channel created');
      queryClient.invalidateQueries({ queryKey: ['channel-configs'] });
      onClose();
    },
    onError: () => {
      message.error('Failed to save channel config');
    },
  });

  function buildCredentials(values: FormValues): Record<string, string> {
    if (values.platform === 'feishu' || platform === 'feishu') {
      return {
        app_id: values.feishu_appId ?? '',
        app_secret: values.feishu_appSecret ?? '',
        verification_token: values.feishu_verificationToken ?? '',
        encrypt_key: values.feishu_encryptKey ?? '',
      };
    }
    return {
      bot_token: values.telegram_botToken ?? '',
      webhook_secret: values.telegram_webhookSecret ?? '',
    };
  }

  async function handleTest() {
    if (!config) return;
    setTesting(true);
    try {
      const res = await testChannelConfig(config.id);
      if (res.data.ok) {
        message.success('Connection test passed');
      } else {
        message.warning(res.data.message ?? 'Test failed');
      }
    } catch {
      message.error('Connection test failed');
    } finally {
      setTesting(false);
    }
  }

  function handleCopyWebhook() {
    const url = buildWebhookUrl(platform);
    navigator.clipboard.writeText(url).then(() => {
      message.success('Webhook URL copied');
    });
  }

  return (
    <Drawer
      title={isEdit ? `Edit ${config?.displayName ?? 'Channel'}` : 'New Channel'}
      open={open}
      onClose={onClose}
      width={480}
      footer={
        <Space style={{ justifyContent: 'flex-end', width: '100%' }}>
          {isEdit && (
            <Button
              icon={<span style={{ marginRight: 4 }}>{TEST_ICON}</span>}
              loading={testing}
              onClick={handleTest}
            >
              Test Connection
            </Button>
          )}
          <Button onClick={onClose}>Cancel</Button>
          <Button type="primary" loading={isPending} onClick={() => form.submit()}>
            {isEdit ? 'Save' : 'Create'}
          </Button>
        </Space>
      }
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={(v) => save(v)}
        requiredMark={false}
      >
        {/* Platform — only on create */}
        {!isEdit && (
          <Form.Item label="Platform" name="platform" rules={[{ required: true }]}>
            <Select
              options={platformOptions}
              onChange={(v) => {
                setPlatform(v as string);
                form.setFieldsValue({ platform: v as string });
              }}
            />
          </Form.Item>
        )}

        <Form.Item
          label="Display Name"
          name="displayName"
          rules={[{ required: true, message: 'Name is required' }]}
        >
          <Input placeholder={platform === 'feishu' ? '飞书机器人' : 'Telegram Bot'} />
        </Form.Item>

        <Form.Item
          label="Default Agent"
          name="defaultAgentId"
          rules={[{ required: true, message: 'Select an agent' }]}
        >
          <Select
            placeholder="Select agent to handle messages"
            options={agents.map((a) => ({ value: a.id, label: a.name }))}
            showSearch
            filterOption={(input, opt) =>
              String(opt?.label ?? '').toLowerCase().includes(input.toLowerCase())
            }
          />
        </Form.Item>

        <Form.Item label="Active" name="active" valuePropName="checked">
          <Switch />
        </Form.Item>

        <Divider style={{ margin: '8px 0 16px' }} />

        {/* Webhook URL */}
        <div style={{ marginBottom: 16 }}>
          <p className="credentials-section-label" style={{ marginBottom: 6 }}>
            Webhook URL
          </p>
          <div className="webhook-url-box">
            <span className="webhook-url-text">{buildWebhookUrl(platform)}</span>
            <Tooltip title="Copy">
              <button
                type="button"
                onClick={handleCopyWebhook}
                style={{
                  background: 'none',
                  border: 'none',
                  cursor: 'pointer',
                  color: 'inherit',
                  opacity: 0.5,
                  padding: '2px 4px',
                  display: 'flex',
                  alignItems: 'center',
                }}
              >
                {COPY_ICON}
              </button>
            </Tooltip>
          </div>
        </div>

        {/* Credential update toggle (edit mode only) */}
        {isEdit && (
          <Form.Item label="Update credentials" name="updateCredentials" valuePropName="checked">
            <Switch
              size="small"
              onChange={(v) => setUpdateCreds(v)}
            />
          </Form.Item>
        )}

        {/* Feishu credentials */}
        {platform === 'feishu' && ((!isEdit) || updateCreds) && (
          <div className="credentials-section">
            <p className="credentials-section-label">Feishu Credentials</p>
            <Form.Item
              label="App ID"
              name="feishu_appId"
              rules={[{ required: true, message: 'App ID required' }]}
            >
              <Input placeholder="cli_xxxxxxxxxxxxxxxxx" />
            </Form.Item>
            <Form.Item
              label="App Secret"
              name="feishu_appSecret"
              rules={[{ required: true, message: 'App Secret required' }]}
            >
              <Input.Password placeholder="App Secret" />
            </Form.Item>
            <Form.Item
              label="Verification Token"
              name="feishu_verificationToken"
              rules={[{ required: true, message: 'Verification Token required' }]}
            >
              <Input.Password placeholder="URL verification token" />
            </Form.Item>
            <Form.Item
              label="Encrypt Key"
              name="feishu_encryptKey"
              extra="Optional — leave empty to skip signature verification"
            >
              <Input.Password placeholder="Webhook signature key (optional)" />
            </Form.Item>
          </div>
        )}

        {/* Telegram credentials */}
        {platform === 'telegram' && ((!isEdit) || updateCreds) && (
          <div className="credentials-section">
            <p className="credentials-section-label">Telegram Credentials</p>
            <Form.Item
              label="Bot Token"
              name="telegram_botToken"
              rules={[{ required: true, message: 'Bot Token required' }]}
            >
              <Input.Password placeholder="1234567890:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" />
            </Form.Item>
            <Form.Item
              label="Webhook Secret"
              name="telegram_webhookSecret"
              rules={[{ required: true, message: 'Webhook Secret required' }]}
            >
              <Input.Password placeholder="Random UUID for webhook verification" />
            </Form.Item>
          </div>
        )}
      </Form>
    </Drawer>
  );
};

export default ChannelConfigDrawer;
