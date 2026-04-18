import React, { useEffect, useMemo, useRef, useState } from 'react';

export interface PaletteItem {
  path: string;
  label: string;
  group: string;
}

interface CmdKPaletteProps {
  items: PaletteItem[];
  onClose: () => void;
  onNavigate: (path: string) => void;
}

const CmdKPalette: React.FC<CmdKPaletteProps> = ({ items, onClose, onNavigate }) => {
  const [query, setQuery] = useState('');
  const [focusIdx, setFocusIdx] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return items;
    return items.filter((it) => it.label.toLowerCase().includes(q) || it.path.toLowerCase().includes(q));
  }, [items, query]);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    setFocusIdx(0);
  }, [query]);

  useEffect(() => {
    const el = listRef.current?.children[focusIdx] as HTMLElement | undefined;
    el?.scrollIntoView({ block: 'nearest' });
  }, [focusIdx]);

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setFocusIdx((i) => Math.min(i + 1, Math.max(filtered.length - 1, 0)));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setFocusIdx((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const target = filtered[focusIdx];
      if (target) {
        onNavigate(target.path);
        onClose();
      }
    } else if (e.key === 'Tab') {
      e.preventDefault();
    }
  };

  const handleScrimClick = (e: React.MouseEvent) => {
    if (e.target === e.currentTarget) onClose();
  };

  return (
    <div className="sf-palette-scrim" onClick={handleScrimClick}>
      <div
        className="sf-palette"
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        onKeyDown={handleKey}
      >
        <input
          ref={inputRef}
          className="sf-palette-input"
          placeholder="Jump to page or run command…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          role="combobox"
          aria-expanded="true"
          aria-controls="sf-palette-listbox"
          aria-activedescendant={filtered.length > 0 ? `sf-palette-opt-${focusIdx}` : undefined}
        />
        <div className="sf-palette-list" ref={listRef} id="sf-palette-listbox" role="listbox">
          {filtered.length === 0 ? (
            <div className="sf-palette-empty">No matches</div>
          ) : (
            filtered.map((item, idx) => (
              <div
                key={`${item.path}-${item.label}`}
                id={`sf-palette-opt-${idx}`}
                role="option"
                aria-selected={idx === focusIdx}
                className={`sf-palette-item${idx === focusIdx ? ' sf-palette-item--focus' : ''}`}
                onMouseEnter={() => setFocusIdx(idx)}
                onClick={() => {
                  onNavigate(item.path);
                  onClose();
                }}
              >
                <span className="sf-palette-item-label">{item.label}</span>
                <span className="sf-palette-item-path">{item.path}</span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

export default CmdKPalette;
