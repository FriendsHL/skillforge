import React from 'react';

interface SpanDetailTabsProps {
  tabs: { key: string; label: string; badge?: string; disabled?: boolean }[];
  activeKey: string;
  onSelect: (key: string) => void;
}

const SpanDetailTabs: React.FC<SpanDetailTabsProps> = ({ tabs, activeKey, onSelect }) => {
  return (
    <div className="obs-span-tabs">
      {tabs.map((tab) => (
        <button
          key={tab.key}
          type="button"
          className={`obs-span-tab ${activeKey === tab.key ? 'is-active' : ''} ${tab.disabled ? 'is-disabled' : ''}`}
          onClick={() => !tab.disabled && onSelect(tab.key)}
          disabled={tab.disabled}
          aria-selected={activeKey === tab.key}
        >
          {tab.label}
          {tab.badge && <span className="obs-span-tab-badge">{tab.badge}</span>}
        </button>
      ))}
    </div>
  );
};

export default SpanDetailTabs;