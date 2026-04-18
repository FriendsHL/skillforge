import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Drawer, Button } from 'antd';
import { MenuOutlined, SunOutlined, MoonOutlined } from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useTheme } from '../contexts/ThemeContext';
import { useAuth } from '../contexts/AuthContext';
import CmdKPalette, { type PaletteItem } from './CmdKPalette';

type NavItem = {
  key: string;
  path: string;
  label: string;
  icon: React.ReactNode;
  badge?: string;
};

const IconChat = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <path d="M2 4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6l-3 2v-2.3A2 2 0 0 1 2 10V4Z" />
  </svg>
);
const IconAgents = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <rect x="3" y="4" width="10" height="8" rx="2" />
    <circle cx="6.5" cy="8" r="0.8" fill="currentColor" stroke="none" />
    <circle cx="9.5" cy="8" r="0.8" fill="currentColor" stroke="none" />
    <path d="M8 2v2" />
  </svg>
);
const IconHooks = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <path d="M3 3v6a3 3 0 0 0 3 3h4" />
    <path d="M10 12l2-2M10 12l2 2" />
  </svg>
);
const IconEvals = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <path d="M2 13l4-4 3 3 5-6" />
  </svg>
);
const IconAgentCfg = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <circle cx="8" cy="8" r="2.5" />
    <path d="M8 2v2M8 12v2M2 8h2M12 8h2M4 4l1.4 1.4M10.6 10.6L12 12M4 12l1.4-1.4M10.6 5.4 12 4" />
  </svg>
);
const IconSkills = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <path d="M3 6l5-3 5 3v4l-5 3-5-3V6z" />
    <path d="M8 3v10M3 6l10 4M13 6L3 10" />
  </svg>
);
const IconRules = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <rect x="3" y="2" width="10" height="12" rx="1.5" />
    <path d="M5.5 5.5h5M5.5 8h5M5.5 10.5h3" />
  </svg>
);
const IconSessions = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <circle cx="8" cy="8" r="6" />
    <path d="M8 4.5v3.7l2.3 1.5" />
  </svg>
);
const IconTeams = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <circle cx="6" cy="6.5" r="2" />
    <circle cx="11.5" cy="7" r="1.6" />
    <path d="M2.5 13c.4-2 2-3 3.5-3s3.1 1 3.5 3M10 13c.3-1.4 1.3-2.2 2.4-2.2" />
  </svg>
);
const IconUsage = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <path d="M3 13V7M7 13V3M11 13V9" />
  </svg>
);
const IconTraces = (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" width="14" height="14" aria-hidden="true">
    <circle cx="4" cy="4" r="1.5" />
    <circle cx="12" cy="8" r="1.5" />
    <circle cx="6" cy="12" r="1.5" />
    <path d="M5 5l6 2M11 9l-4 2" />
  </svg>
);

const runGroup: NavItem[] = [
  { key: 'chat', path: '/chat', label: 'Chat', icon: IconChat },
  { key: 'agents', path: '/agents', label: 'Sub-agents', icon: IconAgents },
  { key: 'hooks', path: '/hooks', label: 'Hooks', icon: IconHooks },
  { key: 'evals', path: '/eval', label: 'Evals', icon: IconEvals },
];

const workspaceGroup: NavItem[] = [
  { key: 'agent-cfg', path: '/agents', label: 'Agent config', icon: IconAgentCfg },
  { key: 'skills', path: '/skills', label: 'Skills', icon: IconSkills },
  { key: 'rules', path: '/memories', label: 'Rules', icon: IconRules },
  { key: 'sessions', path: '/sessions', label: 'Sessions', icon: IconSessions },
  { key: 'teams', path: '/teams', label: 'Teams', icon: IconTeams },
  { key: 'usage', path: '/usage', label: 'Usage', icon: IconUsage },
  { key: 'traces', path: '/traces', label: 'Traces', icon: IconTraces },
];

const pageTitles: Record<string, string> = {
  '/': 'Dashboard',
  '/chat': 'Chat',
  '/agents': 'Agents',
  '/skills': 'Skills',
  '/memories': 'Rules',
  '/sessions': 'Sessions',
  '/teams': 'Teams',
  '/usage': 'Usage',
  '/traces': 'Traces',
  '/eval': 'Evals',
  '/hooks': 'Hooks',
};

const getPageKey = (pathname: string): string => {
  if (pathname === '/' || pathname === '') return '/';
  const segments = pathname.split('/').filter(Boolean);
  if (segments.length === 0) return '/';
  return `/${segments[0]}`;
};

const navItemActive = (item: NavItem, pathname: string): boolean => {
  if (item.path === '/') return pathname === '/';
  const rootPath = '/' + item.path.split('/').filter(Boolean)[0];
  return pathname === rootPath || pathname.startsWith(rootPath + '/');
};

interface NavLinkProps {
  item: NavItem;
  pathname: string;
  onNavigate?: () => void;
}

const NavLink: React.FC<NavLinkProps> = ({ item, pathname, onNavigate }) => {
  const active = navItemActive(item, pathname);
  return (
    <Link
      to={item.path}
      className={`sf-nav-item${active ? ' sf-nav-item--active' : ''}`}
      onClick={onNavigate}
      aria-current={active ? 'page' : undefined}
    >
      <span className="sf-nav-item-icon">{item.icon}</span>
      <span className="sf-nav-item-label">{item.label}</span>
      {item.badge && <span className="sf-nav-item-badge">{item.badge}</span>}
    </Link>
  );
};

interface BreadcrumbsProps {
  pathname: string;
  sessionId?: string;
}

const Breadcrumbs: React.FC<BreadcrumbsProps> = ({ pathname, sessionId }) => {
  const key = getPageKey(pathname);
  const title = pageTitles[key] || 'Workspace';
  return (
    <div className="sf-breadcrumbs" aria-label="Breadcrumbs">
      <span>Workspace</span>
      <span className="sf-breadcrumbs-sep">/</span>
      <span className={sessionId ? '' : 'sf-breadcrumbs-current'}>{title}</span>
      {sessionId && (
        <>
          <span className="sf-breadcrumbs-sep">/</span>
          <span className="sf-breadcrumbs-current sf-breadcrumbs-mono">
            cr_{sessionId.slice(0, 6)}
          </span>
        </>
      )}
    </div>
  );
};

interface SidebarContentProps {
  pathname: string;
  theme: 'light' | 'dark';
  userId: number;
  onToggleTheme: () => void;
  onNavigate?: () => void;
}

const SidebarContent: React.FC<SidebarContentProps> = ({
  pathname,
  theme,
  userId,
  onToggleTheme,
  onNavigate,
}) => {
  const userInitial = String(userId).slice(0, 2).toUpperCase();
  return (
    <>
      <div className="sf-brand">
        <div className="sf-brand-mark">SF</div>
        <div className="sf-brand-text">
          <h1 className="sf-brand-name">SkillForge</h1>
          <span className="sf-brand-sub">dashboard</span>
        </div>
      </div>
      <nav className="sf-nav" aria-label="Primary navigation">
        <div className="sf-nav-group">
          <span className="sf-nav-group-label">Run</span>
          {runGroup.map((item) => (
            <NavLink key={item.key} item={item} pathname={pathname} onNavigate={onNavigate} />
          ))}
        </div>
        <div className="sf-nav-group">
          <span className="sf-nav-group-label">Workspace</span>
          {workspaceGroup.map((item) => (
            <NavLink key={item.key} item={item} pathname={pathname} onNavigate={onNavigate} />
          ))}
        </div>
      </nav>
      <div className="sf-sidebar-footer">
        <div className="sf-sidebar-user">
          <div className="sf-sidebar-avatar" aria-hidden="true">{userInitial}</div>
          <span className="sf-sidebar-user-name">user {userId}</span>
        </div>
        <Button
          type="text"
          size="small"
          aria-label="Toggle theme"
          icon={theme === 'dark' ? <SunOutlined /> : <MoonOutlined />}
          onClick={onToggleTheme}
        />
      </div>
    </>
  );
};

const paletteItems: PaletteItem[] = [
  ...runGroup.map((i) => ({ path: i.path, label: i.label, group: 'Run' })),
  ...workspaceGroup.map((i) => ({ path: i.path, label: i.label, group: 'Workspace' })),
];

const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { theme, toggleTheme } = useTheme();
  const { userId } = useAuth();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mac = e.metaKey;
      if ((mac || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const handlePaletteNavigate = useCallback(
    (path: string) => {
      navigate(path);
    },
    [navigate],
  );

  const closeMobile = useCallback(() => setMobileOpen(false), []);

  const isChatRoute = location.pathname.startsWith('/chat');

  const topbarSessionId = useMemo(() => {
    const match = location.pathname.match(/^\/chat\/([^/]+)/);
    return match ? match[1] : undefined;
  }, [location.pathname]);

  return (
    <div className="sf-shell" data-theme={theme}>
      <aside className="sf-sidebar">
        <SidebarContent
          pathname={location.pathname}
          theme={theme}
          userId={userId}
          onToggleTheme={toggleTheme}
        />
      </aside>

      <Drawer
        placement="left"
        open={mobileOpen}
        onClose={closeMobile}
        width={260}
        closable={false}
        styles={{
          body: { padding: 0, background: 'var(--bg-sidebar)', display: 'flex', flexDirection: 'column', height: '100%' },
          header: { display: 'none' },
          content: { background: 'var(--bg-sidebar)' },
        }}
      >
        <SidebarContent
          pathname={location.pathname}
          theme={theme}
          userId={userId}
          onToggleTheme={toggleTheme}
          onNavigate={closeMobile}
        />
      </Drawer>

      <div className="sf-main">
        <header className="sf-topbar">
          <button
            type="button"
            className="sf-menu-btn"
            aria-label="Open navigation"
            onClick={() => setMobileOpen(true)}
          >
            <MenuOutlined />
          </button>
          <Breadcrumbs pathname={location.pathname} sessionId={topbarSessionId} />
          <div className="sf-topbar-actions">
            <button
              type="button"
              className="sf-cmdk-btn"
              onClick={() => setPaletteOpen(true)}
              aria-label="Open command palette"
            >
              <span>Jump to…</span>
              <kbd>⌘K</kbd>
            </button>
          </div>
        </header>
        <main className={`sf-content${isChatRoute ? ' sf-content--chat' : ''}`}>
          <Outlet />
        </main>
      </div>

      {paletteOpen && (
        <CmdKPalette
          items={paletteItems}
          onClose={() => setPaletteOpen(false)}
          onNavigate={handlePaletteNavigate}
        />
      )}
    </div>
  );
};

export default AppLayout;
