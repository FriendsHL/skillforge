import React from 'react';
import { Input, Alert, Typography } from 'antd';
import { CheckCircleOutlined, ExclamationCircleOutlined } from '@ant-design/icons';

const { TextArea } = Input;

interface JsonModeProps {
  rawJson: string;
  errors: string[];
  onChange: (next: string) => void;
}

/**
 * Raw JSON mode — Input.TextArea with monospace font + live Zod errors below.
 * We intentionally do NOT pull in monaco-editor (see doc §5.5 rationale).
 */
const JsonMode: React.FC<JsonModeProps> = ({ rawJson, errors, onChange }) => {
  const isValid = errors.length === 0;

  return (
    <div className="sf-hooks-json-mode">
      <TextArea
        value={rawJson}
        onChange={(e) => onChange(e.target.value)}
        rows={12}
        spellCheck={false}
        className="sf-hooks-json-textarea"
      />
      {isValid ? (
        <Alert
          className="sf-hooks-json-status"
          type="success"
          showIcon
          icon={<CheckCircleOutlined />}
          message="Valid lifecycle hooks config"
        />
      ) : (
        <Alert
          className="sf-hooks-json-status"
          type="error"
          showIcon
          icon={<ExclamationCircleOutlined />}
          message={`${errors.length} validation error${errors.length > 1 ? 's' : ''}`}
          description={
            <ul className="sf-hooks-json-errors">
              {errors.slice(0, 8).map((err, i) => (
                <li key={i}>
                  <Typography.Text code>{err}</Typography.Text>
                </li>
              ))}
              {errors.length > 8 && (
                <li>
                  <Typography.Text type="secondary">
                    … and {errors.length - 8} more
                  </Typography.Text>
                </li>
              )}
            </ul>
          }
        />
      )}
    </div>
  );
};

export default JsonMode;
