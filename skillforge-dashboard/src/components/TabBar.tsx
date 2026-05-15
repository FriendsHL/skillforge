import React from 'react';

export interface TabItem {
  key: string;
  label: string;
  badge?: number;
  /** Badge color override. Default uses accent primary. */
  badgeColor?: string;
}

interface TabBarProps {
  tabs: TabItem[];
  activeTab: string;
  onSwitch: (key: string) => void;
}

const tabBtnStyle = (active: boolean): React.CSSProperties => ({
  padding: '10px 16px',
  background: 'none',
  border: 'none',
  borderBottom: active ? '2px solid var(--accent-primary, #d9633a)' : '2px solid transparent',
  color: active ? 'var(--fg-1, #1a1815)' : 'var(--fg-3, #7a7670)',
  cursor: active ? 'default' : 'pointer',
  fontSize: 13,
  fontWeight: active ? 600 : 500,
  position: 'relative',
});

const badgeStyle: React.CSSProperties = {
  marginLeft: 6,
  background: 'var(--accent-primary, #d9633a)',
  color: '#fff',
  borderRadius: 10,
  padding: '1px 7px',
  fontSize: 11,
  fontWeight: 600,
  lineHeight: '16px',
};

const TabBar: React.FC<TabBarProps> = ({ tabs, activeTab, onSwitch }) => (
  <div style={{ display: 'flex', alignItems: 'center', gap: 0, borderBottom: '1px solid var(--border-1, #e0dbcf)', flexShrink: 0 }}>
    {tabs.map((tab) => {
      const active = tab.key === activeTab;
      return (
        <button
          key={tab.key}
          style={tabBtnStyle(active)}
          onClick={() => !active && onSwitch(tab.key)}
        >
          {tab.label}
          {tab.badge != null && tab.badge > 0 && (
            <span style={{ ...badgeStyle, ...(tab.badgeColor ? { background: tab.badgeColor } : {}) }}>
              {tab.badge}
            </span>
          )}
        </button>
      );
    })}
  </div>
);

export default TabBar;
