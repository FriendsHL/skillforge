import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef } from 'react';
import { Radio, message, Tooltip } from 'antd';
import {
  AppstoreOutlined,
  FormOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import PresetMode from './lifecycle-hooks/PresetMode';
import FormMode from './lifecycle-hooks/FormMode';
import JsonMode from './lifecycle-hooks/JsonMode';
import {
  stringifyHooks,
  type LifecycleHooksConfig,
} from '../constants/lifecycleHooks';
import { useLifecycleHooks } from '../hooks/useLifecycleHooks';

interface SkillOption {
  name: string;
  description?: string;
}

interface LifecycleHooksEditorProps {
  /** Initial JSON string loaded from AgentEntity.lifecycleHooks. */
  initialJson: string | null;
  /** Skills list from getSkills() — used by FormMode skill selector. */
  skills: SkillOption[];
  /** Agent id — needed for dry-run test button. */
  agentId: string | null;
  /**
   * Emitted whenever the internal rawJson changes, including the first render
   * (so the parent can latch a baseline for dirty comparison without reading
   * the imperative handle every render).
   */
  onRawJsonChange?: (raw: string) => void;
  /** Emitted whenever validation errors change. Parent typically stores in a ref. */
  onErrorsChange?: (errors: string[]) => void;
}

/**
 * Imperative handle exposed to the parent (AgentList). The parent reads the
 * live `rawJson` + `errors` at save-time instead of mirroring them into its
 * own state, which avoids an extra render round-trip per keystroke.
 *
 * `setRawJson` lets the parent drive the editor (e.g. Revert restores the
 * baseline JSON without remounting the editor).
 */
export interface LifecycleHooksEditorHandle {
  rawJson: string;
  errors: string[];
  setRawJson: (next: string) => void;
}

/**
 * N3 Lifecycle Hooks editor — container component.
 *
 * Three editing modes share a single `rawJson` source of truth:
 *   - Preset: click card → overwrite rawJson with preset.config
 *   - Form:   Ant Design form → on change, stringify back into rawJson
 *   - JSON:   raw TextArea with Zod live validation
 *
 * See docs/design-n3-lifecycle-hooks.md §5.2.
 */
const LifecycleHooksEditor = forwardRef<LifecycleHooksEditorHandle, LifecycleHooksEditorProps>(
  ({ initialJson, skills, agentId, onRawJsonChange, onErrorsChange }, ref) => {
    const {
      rawJson,
      mode,
      parsed,
      errors,
      events,
      presets,
      methods,
      isPresetsLoading,
      isMethodsLoading,
      setRawJson,
      setMode,
      setConfig,
    } = useLifecycleHooks(initialJson);

    useImperativeHandle(
      ref,
      () => ({ rawJson, errors, setRawJson }),
      [rawJson, errors, setRawJson],
    );

    // Ref-mirror the callbacks so we don't need to re-fire the effect when the
    // parent passes an unstable callback identity (common React pitfall).
    // The effect below depends only on rawJson / errors.
    const onRawJsonChangeRef = useRef(onRawJsonChange);
    const onErrorsChangeRef = useRef(onErrorsChange);
    useEffect(() => {
      onRawJsonChangeRef.current = onRawJsonChange;
    }, [onRawJsonChange]);
    useEffect(() => {
      onErrorsChangeRef.current = onErrorsChange;
    }, [onErrorsChange]);

    useEffect(() => {
      onRawJsonChangeRef.current?.(rawJson);
    }, [rawJson]);

    useEffect(() => {
      onErrorsChangeRef.current?.(errors);
    }, [errors]);

    const handleApplyPreset = useCallback(
      (config: LifecycleHooksConfig, presetName: string) => {
        setConfig(config);
        message.success(`Applied preset: ${presetName}`);
        // Auto-switch to form so the user can tweak the applied values.
        setMode('form');
      },
      [setConfig, setMode],
    );

    const handleFormChange = useCallback(
      (next: LifecycleHooksConfig) => {
        setRawJson(stringifyHooks(next));
      },
      [setRawJson],
    );

    return (
      <div className="sf-hooks-editor">
        <div className="sf-hooks-mode-bar">
          <Radio.Group
            value={mode}
            onChange={(e) => setMode(e.target.value)}
            optionType="button"
            buttonStyle="solid"
            size="small"
          >
            <Tooltip title="Pick a preset template to start from">
              <Radio.Button value="preset">
                <AppstoreOutlined /> 预设
              </Radio.Button>
            </Tooltip>
            <Tooltip title="Fill a form per event (P0 main flow)">
              <Radio.Button value="form">
                <FormOutlined /> 表单
              </Radio.Button>
            </Tooltip>
            <Tooltip title="Edit raw JSON with live validation">
              <Radio.Button value="json">
                <CodeOutlined /> JSON
              </Radio.Button>
            </Tooltip>
          </Radio.Group>
          {errors.length > 0 && (
            <span className="sf-hooks-mode-bar-warn">
              {errors.length} JSON validation {errors.length === 1 ? 'error' : 'errors'} —
              fix before saving.
            </span>
          )}
        </div>

        <div className="sf-hooks-mode-body">
          {mode === 'preset' && (
            <PresetMode
              presets={presets}
              isLoading={isPresetsLoading}
              onApply={handleApplyPreset}
            />
          )}
          {mode === 'form' && (
            <FormMode
              parsed={parsed}
              errors={errors}
              events={events}
              skills={skills}
              methods={methods}
              isMethodsLoading={isMethodsLoading}
              agentId={agentId}
              onConfigChange={handleFormChange}
            />
          )}
          {mode === 'json' && (
            <JsonMode rawJson={rawJson} errors={errors} onChange={setRawJson} />
          )}
        </div>
      </div>
    );
  },
);

LifecycleHooksEditor.displayName = 'LifecycleHooksEditor';

export default LifecycleHooksEditor;
