import React, { useEffect, useRef, useState } from 'react';
import { Input, InputNumber } from 'antd';
import { useDebouncedCallback } from '../../hooks/useDebouncedCallback';

/**
 * Buffered inputs used by FormMode — character-level debounce so that each
 * keystroke does not round-trip through the rawJson serialize + Zod reparse
 * that the parent LifecycleHooksEditor performs on every commit.
 *
 * Select / Switch / Radio stay synchronous in FormMode so the Save button
 * always sees the latest choice. Only free-text inputs benefit from debouncing.
 */

const FIELD_COMMIT_DEBOUNCE_MS = 200;

interface BufferedInputProps {
  value: string;
  onCommit: (next: string) => void;
  placeholder?: string;
  maxLength?: number;
}

export const BufferedInput: React.FC<BufferedInputProps> = ({
  value,
  onCommit,
  placeholder,
  maxLength,
}) => {
  const [local, setLocal] = useState(value);
  const lastExternalRef = useRef(value);
  const [debouncedCommit, flush] = useDebouncedCallback(onCommit, FIELD_COMMIT_DEBOUNCE_MS);

  useEffect(() => {
    if (value !== lastExternalRef.current) {
      lastExternalRef.current = value;
      setLocal(value);
    }
  }, [value]);

  return (
    <Input
      value={local}
      onChange={(e) => {
        const next = e.target.value;
        lastExternalRef.current = next;
        setLocal(next);
        debouncedCommit(next);
      }}
      onBlur={flush}
      placeholder={placeholder}
      maxLength={maxLength}
    />
  );
};

interface BufferedInputNumberProps {
  value: number;
  min?: number;
  max?: number;
  onCommit: (next: number | null) => void;
}

export const BufferedInputNumber: React.FC<BufferedInputNumberProps> = ({
  value,
  min,
  max,
  onCommit,
}) => {
  const [local, setLocal] = useState<number | null>(value);
  const lastExternalRef = useRef<number | null>(value);
  const [debouncedCommit, flush] = useDebouncedCallback(onCommit, FIELD_COMMIT_DEBOUNCE_MS);

  useEffect(() => {
    if (value !== lastExternalRef.current) {
      lastExternalRef.current = value;
      setLocal(value);
    }
  }, [value]);

  return (
    <InputNumber
      min={min}
      max={max}
      value={local ?? undefined}
      onChange={(next) => {
        const nextValue = (next ?? null) as number | null;
        lastExternalRef.current = nextValue;
        setLocal(nextValue);
        debouncedCommit(nextValue);
      }}
      onBlur={flush}
      style={{ width: '100%' }}
    />
  );
};

interface BufferedTextAreaProps {
  value: string;
  rows?: number;
  maxLength?: number;
  placeholder?: string;
  className?: string;
  status?: 'error' | 'warning';
  onCommit: (next: string) => void;
}

export const BufferedTextArea: React.FC<BufferedTextAreaProps> = ({
  value,
  rows,
  maxLength,
  placeholder,
  className,
  status,
  onCommit,
}) => {
  const [local, setLocal] = useState(value);
  const lastExternalRef = useRef(value);
  const [debouncedCommit, flush] = useDebouncedCallback(onCommit, FIELD_COMMIT_DEBOUNCE_MS);

  useEffect(() => {
    if (value !== lastExternalRef.current) {
      lastExternalRef.current = value;
      setLocal(value);
    }
  }, [value]);

  return (
    <Input.TextArea
      value={local}
      rows={rows}
      maxLength={maxLength}
      placeholder={placeholder}
      className={className}
      status={status}
      spellCheck={false}
      onChange={(e) => {
        const next = e.target.value;
        lastExternalRef.current = next;
        setLocal(next);
        debouncedCommit(next);
      }}
      onBlur={flush}
    />
  );
};
