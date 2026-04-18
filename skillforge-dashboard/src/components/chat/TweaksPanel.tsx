import React, { useEffect } from 'react';
import { useTheme, type Accent, type Density } from '../../contexts/ThemeContext';
import { IconX } from './ChatIcons';

interface TweaksPanelProps {
  open: boolean;
  onClose: () => void;
}

const ACCENT_SWATCH: Record<Accent, string> = {
  terracotta: '#d9633a',
  ink: '#2a2e3a',
  moss: '#4f7a55',
  plum: '#7a4f6b',
};

const densityOptions: { value: Density; label: string }[] = [
  { value: 'compact', label: 'Compact' },
  { value: 'cozy', label: 'Cozy' },
  { value: 'spacious', label: 'Spacious' },
];

const TweaksPanel: React.FC<TweaksPanelProps> = ({ open, onClose }) => {
  const {
    theme,
    setTheme,
    density,
    setDensity,
    accent,
    setAccent,
    serifHeadings,
    setSerifHeadings,
  } = useTheme();

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="tweaks-panel" role="dialog" aria-label="Appearance tweaks">
      <div className="tweaks-head">
        <span className="tweaks-title">Appearance</span>
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Close">
          <IconX />
        </button>
      </div>

      <div className="tweak-row">
        <span className="tweak-label">Theme</span>
        <div className="tweak-seg">
          <button type="button" className={theme === 'light' ? 'on' : ''} onClick={() => setTheme('light')}>
            Light
          </button>
          <button type="button" className={theme === 'dark' ? 'on' : ''} onClick={() => setTheme('dark')}>
            Dark
          </button>
        </div>
      </div>

      <div className="tweak-row">
        <span className="tweak-label">Density</span>
        <div className="tweak-seg">
          {densityOptions.map((o) => (
            <button
              key={o.value}
              type="button"
              className={density === o.value ? 'on' : ''}
              onClick={() => setDensity(o.value)}
            >
              {o.label}
            </button>
          ))}
        </div>
      </div>

      <div className="tweak-row">
        <span className="tweak-label">Accent</span>
        <div className="swatches">
          {(Object.keys(ACCENT_SWATCH) as Accent[]).map((a) => (
            <button
              key={a}
              type="button"
              aria-label={a}
              className={`swatch${accent === a ? ' on' : ''}`}
              style={{ background: ACCENT_SWATCH[a] }}
              onClick={() => setAccent(a)}
            />
          ))}
        </div>
      </div>

      <div className="tweak-row">
        <span className="tweak-label">Serif headings</span>
        <label className="tweak-toggle">
          <input
            type="checkbox"
            checked={serifHeadings}
            onChange={(e) => setSerifHeadings(e.target.checked)}
          />
          <span>{serifHeadings ? 'On' : 'Off'}</span>
        </label>
      </div>

      <div className="tweak-foot">
        <span>Press <kbd>t</kbd> to toggle</span>
      </div>
    </div>
  );
};

export default TweaksPanel;
