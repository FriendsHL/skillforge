import React, { useCallback, useEffect, useRef, useState } from 'react';
import './dropdown.css';

export interface DropdownOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface DropdownProps {
  options: DropdownOption[];
  value?: string;
  placeholder?: string;
  allowClear?: boolean;
  onChange?: (value: string | undefined) => void;
  /** Extra class on the trigger button */
  className?: string;
  /** Inline style on the trigger */
  style?: React.CSSProperties;
  /** Max height of the dropdown panel */
  maxHeight?: number;
}

/**
 * Custom dropdown component — div-based, fully styled, no native <select>.
 * Closes on outside click, supports keyboard nav (↑↓ Enter Esc),
 * and has a clear button when allowClear is set.
 */
const Dropdown: React.FC<DropdownProps> = ({
  options,
  value,
  placeholder = 'Select…',
  allowClear = false,
  onChange,
  className = '',
  style,
  maxHeight = 280,
}) => {
  const [open, setOpen] = useState(false);
  const [highlightIdx, setHighlightIdx] = useState(-1);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const selected = options.find((o) => o.value === value);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (
        triggerRef.current && !triggerRef.current.contains(e.target as Node) &&
        panelRef.current && !panelRef.current.contains(e.target as Node)
      ) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  // Close on Escape
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open]);

  // Scroll highlighted item into view
  useEffect(() => {
    if (highlightIdx < 0 || !panelRef.current) return;
    const items = panelRef.current.querySelectorAll('.dd-item');
    items[highlightIdx]?.scrollIntoView({ block: 'nearest' });
  }, [highlightIdx]);

  const handleToggle = useCallback(() => {
    setOpen((prev) => {
      if (!prev) setHighlightIdx(-1);
      return !prev;
    });
  }, []);

  const handleSelect = useCallback(
    (val: string) => {
      onChange?.(val);
      setOpen(false);
      triggerRef.current?.focus();
    },
    [onChange],
  );

  const handleClear = useCallback(
    (e: React.MouseEvent) => {
      e.stopPropagation();
      onChange?.(undefined);
      setOpen(false);
    },
    [onChange],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!open) {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          setOpen(true);
          setHighlightIdx(0);
        }
        return;
      }

      const enabledOptions = options.filter((o) => !o.disabled);

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setHighlightIdx((prev) => {
            const next = prev + 1;
            return next >= enabledOptions.length ? 0 : next;
          });
          break;
        case 'ArrowUp':
          e.preventDefault();
          setHighlightIdx((prev) => {
            const next = prev - 1;
            return next < 0 ? enabledOptions.length - 1 : next;
          });
          break;
        case 'Enter':
        case ' ':
          e.preventDefault();
          if (highlightIdx >= 0 && highlightIdx < enabledOptions.length) {
            handleSelect(enabledOptions[highlightIdx].value);
          }
          break;
        case 'Escape':
          e.preventDefault();
          setOpen(false);
          triggerRef.current?.focus();
          break;
      }
    },
    [open, options, highlightIdx, handleSelect],
  );

  const triggerClasses = [
    'dd-trigger',
    open ? 'dd-open' : '',
    !selected ? 'dd-placeholder' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className="dd-root">
      <button
        ref={triggerRef}
        className={triggerClasses}
        style={style}
        type="button"
        onClick={handleToggle}
        onKeyDown={handleKeyDown}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span className="dd-trigger-text">
          {selected ? selected.label : placeholder}
        </span>
        {allowClear && selected && (
          <span className="dd-clear" onClick={handleClear} title="Clear">
            ×
          </span>
        )}
        <svg className="dd-chevron" width="10" height="6" viewBox="0 0 10 6" fill="none">
          <path d="M1 1L5 5L9 1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </button>

      {open && (
        <div
          ref={panelRef}
          className="dd-panel"
          style={{ maxHeight }}
          role="listbox"
        >
          {options.length === 0 ? (
            <div className="dd-empty">No options</div>
          ) : (
            options.map((opt, i) => {
              const isSelected = opt.value === value;
              // Find index among enabled options for highlight matching
              const enabledIdx = options.slice(0, i + 1).filter((o) => !o.disabled).length - 1;
              const isHighlighted = enabledIdx === highlightIdx;
              return (
                <div
                  key={opt.value}
                  className={[
                    'dd-item',
                    isSelected ? 'dd-selected' : '',
                    isHighlighted ? 'dd-highlighted' : '',
                    opt.disabled ? 'dd-disabled' : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  role="option"
                  aria-selected={isSelected}
                  onClick={() => !opt.disabled && handleSelect(opt.value)}
                  onMouseEnter={() => !opt.disabled && setHighlightIdx(enabledIdx)}
                >
                  <span className="dd-item-label">{opt.label}</span>
                  {isSelected && (
                    <svg className="dd-check" width="14" height="14" viewBox="0 0 14 14" fill="none">
                      <path d="M3 7L6 10L11 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  )}
                </div>
              );
            })
          )}
        </div>
      )}
    </div>
  );
};

export default Dropdown;
