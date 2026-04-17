import React, { useCallback, useMemo } from 'react';
import {
  Select,
  Spin,
  Typography,
} from 'antd';
import {
  FunctionOutlined,
} from '@ant-design/icons';
import type { BuiltInMethodDto } from '../../api';
import type { HookHandler } from '../../constants/lifecycleHooks';
import { BufferedInput } from './BufferedInputs';

interface MethodHandlerFieldsProps {
  handler: Extract<HookHandler, { type: 'method' }>;
  methods: BuiltInMethodDto[];
  isLoading?: boolean;
  onChange: (patch: Partial<Extract<HookHandler, { type: 'method' }>>) => void;
}

const MethodHandlerFields: React.FC<MethodHandlerFieldsProps> = ({ handler, methods, isLoading, onChange }) => {
  const options = useMemo(
    () =>
      methods.map((m) => ({
        label: m.displayName,
        value: m.ref,
      })),
    [methods],
  );

  const selectedMethod = useMemo(
    () => methods.find((m) => m.ref === handler.methodRef),
    [methods, handler.methodRef],
  );

  const isEmpty = !handler.methodRef;

  const handleArgChange = useCallback(
    (key: string, value: string) => {
      const nextArgs = { ...(handler.args ?? {}), [key]: value };
      onChange({ args: nextArgs });
    },
    [handler.args, onChange],
  );

  return (
    <div className="sf-hooks-method-fields">
      <div className="sf-hooks-field">
        <label className="sf-hooks-label">Built-in Method</label>
        {isLoading ? (
          <Spin size="small" />
        ) : methods.length === 0 ? (
          <div className="sf-hooks-disabled-placeholder">
            <FunctionOutlined />
            <span>No built-in methods available. The server may not expose any yet.</span>
          </div>
        ) : (
          <>
            <Select
              value={handler.methodRef || undefined}
              onChange={(next) => onChange({ methodRef: next })}
              options={options}
              placeholder="Select a built-in method"
              showSearch
              optionFilterProp="label"
              status={isEmpty ? 'error' : undefined}
              style={{ width: '100%' }}
            />
            {isEmpty && (
              <Typography.Text type="danger" className="sf-hooks-field-error">
                Method is required -- pick one from the list above.
              </Typography.Text>
            )}
          </>
        )}
      </div>

      {selectedMethod && (
        <>
          <div className="sf-hooks-method-desc">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {selectedMethod.description}
            </Typography.Text>
          </div>

          {Object.keys(selectedMethod.argsSchema).length > 0 && (
            <div className="sf-hooks-field">
              <label className="sf-hooks-label">Arguments</label>
              <div className="sf-hooks-method-args">
                {Object.entries(selectedMethod.argsSchema).map(([argName, typeHint]) => (
                  <div key={argName} className="sf-hooks-method-arg-row">
                    <label className="sf-hooks-method-arg-label">
                      <code>{argName}</code>
                      <span className="sf-hooks-method-arg-type">{typeHint}</span>
                    </label>
                    <BufferedInput
                      value={String((handler.args as Record<string, unknown>)?.[argName] ?? '')}
                      onCommit={(next) => handleArgChange(argName, next)}
                      placeholder={typeHint}
                    />
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default MethodHandlerFields;
