import React, { useState } from 'react';
import { Collapse, Switch, Radio, Tooltip, Input, Button, Space, Empty } from 'antd';
import {
  SafetyCertificateOutlined,
  CodeOutlined,
  ThunderboltOutlined,
  MessageOutlined,
  PlusOutlined,
  DeleteOutlined,
  EditOutlined,
  CheckOutlined,
  CloseOutlined,
} from '@ant-design/icons';
import { RULE_TEMPLATES, CATEGORY_META, type RuleTemplateId } from '../constants/behaviorRules';
import type { GroupedCategory } from '../hooks/useBehaviorRules';

const { TextArea } = Input;

const MAX_CUSTOM_RULES = 10;
const MAX_CUSTOM_RULE_LENGTH = 500;

const CATEGORY_ICONS: Record<string, React.ReactNode> = {
  safety: <SafetyCertificateOutlined />,
  quality: <CodeOutlined />,
  workflow: <ThunderboltOutlined />,
  communication: <MessageOutlined />,
};

interface BehaviorRulesEditorProps {
  groupedRules: GroupedCategory[];
  templateId: RuleTemplateId;
  customRules: string[];
  isLoading: boolean;
  onApplyTemplate: (id: Exclude<RuleTemplateId, 'custom'>) => void;
  onToggleRule: (ruleId: string, enabled: boolean) => void;
  onAddCustomRule: (text: string) => void;
  onRemoveCustomRule: (index: number) => void;
  onUpdateCustomRule: (index: number, text: string) => void;
}

/** Inline editable custom rule row */
function CustomRuleItem({
  text,
  index,
  onUpdate,
  onRemove,
}: {
  text: string;
  index: number;
  onUpdate: (index: number, text: string) => void;
  onRemove: (index: number) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(text);

  const save = () => {
    const trimmed = draft.trim();
    if (trimmed) {
      onUpdate(index, trimmed);
    }
    setEditing(false);
  };

  const cancel = () => {
    setDraft(text);
    setEditing(false);
  };

  if (editing) {
    return (
      <div className="sf-rules-custom-item sf-rules-custom-item--editing">
        <TextArea
          autoSize={{ minRows: 1, maxRows: 4 }}
          value={draft}
          onChange={(e) => setDraft(e.target.value.slice(0, MAX_CUSTOM_RULE_LENGTH))}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); save(); }
            if (e.key === 'Escape') cancel();
          }}
          autoFocus
          className="sf-rules-custom-input"
          maxLength={MAX_CUSTOM_RULE_LENGTH}
        />
        <Space size={4} className="sf-rules-custom-actions">
          <Button type="text" size="small" icon={<CheckOutlined />} onClick={save} />
          <Button type="text" size="small" icon={<CloseOutlined />} onClick={cancel} />
        </Space>
      </div>
    );
  }

  return (
    <div className="sf-rules-custom-item">
      <span className="sf-rules-custom-text">{text}</span>
      <Space size={4} className="sf-rules-custom-actions">
        <Button
          type="text"
          size="small"
          icon={<EditOutlined />}
          onClick={() => { setDraft(text); setEditing(true); }}
        />
        <Button
          type="text"
          size="small"
          icon={<DeleteOutlined />}
          danger
          onClick={() => onRemove(index)}
        />
      </Space>
    </div>
  );
}

const BehaviorRulesEditor: React.FC<BehaviorRulesEditorProps> = ({
  groupedRules,
  templateId,
  customRules,
  isLoading,
  onApplyTemplate,
  onToggleRule,
  onAddCustomRule,
  onRemoveCustomRule,
  onUpdateCustomRule,
}) => {
  const [newRuleText, setNewRuleText] = useState('');

  const handleAddRule = () => {
    const trimmed = newRuleText.trim();
    if (!trimmed) return;
    onAddCustomRule(trimmed);
    setNewRuleText('');
  };

  if (isLoading) {
    return <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>Loading rules...</div>;
  }

  const hasRules = groupedRules.some((g) => g.rules.length > 0);
  if (!hasRules) {
    return <Empty description="No behavior rules available" />;
  }

  return (
    <div className="sf-rules-container">
      {/* Template selector */}
      <div className="sf-rules-template-bar">
        <Radio.Group
          value={templateId}
          onChange={(e) => {
            const val = e.target.value as RuleTemplateId;
            if (val !== 'custom') onApplyTemplate(val);
          }}
          optionType="button"
          buttonStyle="solid"
          size="small"
        >
          {(Object.entries(RULE_TEMPLATES) as [Exclude<RuleTemplateId, 'custom'>, { label: string; description: string }][]).map(
            ([id, tmpl]) => (
              <Tooltip key={id} title={tmpl.description}>
                <Radio.Button value={id}>{tmpl.label}</Radio.Button>
              </Tooltip>
            ),
          )}
          {templateId === 'custom' && (
            <Radio.Button value="custom" disabled>
              Custom
            </Radio.Button>
          )}
        </Radio.Group>
      </div>

      {/* Category collapse */}
      <div className="sf-rules-categories">
        <Collapse
          defaultActiveKey={[CATEGORY_META[0]?.key]}
          ghost
          items={groupedRules
            .filter((g) => g.rules.length > 0)
            .map((group) => ({
              key: group.key,
              label: (
                <span className="sf-rules-category-header">
                  {CATEGORY_ICONS[group.key]}
                  <span>{group.label}</span>
                  <span className="sf-rules-category-counter">
                    {group.enabledCount}/{group.rules.length}
                  </span>
                </span>
              ),
              children: group.rules.map((rule) => (
                <div key={rule.id} className="sf-rules-item">
                  <Switch
                    size="small"
                    checked={rule.enabled}
                    onChange={(checked) => onToggleRule(rule.id, checked)}
                  />
                  <Tooltip title={rule.labelZh}>
                    <span className="sf-rules-item-title">{rule.label}</span>
                  </Tooltip>
                  <span className={`sf-rules-severity sf-rules-severity--${rule.severity}`}>
                    {rule.severity}
                  </span>
                </div>
              )),
            }))}
        />
      </div>

      {/* Custom rules */}
      <div className="sf-rules-custom-section">
        <div className="sf-rules-custom-header">
          Custom Rules
          <span className="sf-rules-category-counter">
            {customRules.length}/{MAX_CUSTOM_RULES}
          </span>
        </div>
        {customRules.map((text, i) => (
          <CustomRuleItem
            key={i}
            text={text}
            index={i}
            onUpdate={onUpdateCustomRule}
            onRemove={onRemoveCustomRule}
          />
        ))}
        {customRules.length < MAX_CUSTOM_RULES && (
          <div className="sf-rules-custom-add">
            <Input
              value={newRuleText}
              onChange={(e) => setNewRuleText(e.target.value.slice(0, MAX_CUSTOM_RULE_LENGTH))}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleAddRule(); }
              }}
              placeholder="Add a custom rule..."
              className="sf-rules-custom-input"
              maxLength={MAX_CUSTOM_RULE_LENGTH}
              suffix={
                <Button
                  type="text"
                  size="small"
                  icon={<PlusOutlined />}
                  onClick={handleAddRule}
                  disabled={!newRuleText.trim()}
                />
              }
            />
          </div>
        )}
      </div>
    </div>
  );
};

export default BehaviorRulesEditor;
