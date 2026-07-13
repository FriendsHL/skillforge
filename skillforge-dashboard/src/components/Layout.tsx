import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Badge, notification } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTheme } from '../contexts/ThemeContext';
import { useAuth } from '../contexts/AuthContext';
import {
  TaskTrackerProvider,
  useTaskTracker,
} from '../contexts/TaskTrackerContext';
import TaskPanel from './TaskPanel';
import { getSkillDrafts } from '../api';
import CmdKPalette, { type PaletteItem } from './CmdKPalette';
import TweaksPanel from './chat/TweaksPanel';
import { IconChat, IconSun, IconMoon, IconSettings, IconSparkle } from './chat/ChatIcons';

type NavItem = {
  key: string;
  path: string;
  label: string;
};

const primaryNav: NavItem[] = [
  { key: 'chat', path: '/chat', label: 'Chat' },
  { key: 'agents', path: '/agents', label: 'Agents' },
  { key: 'skills', path: '/skills', label: 'Skills' },
  { key: 'tools', path: '/tools', label: 'Tools & MCP' },
  { key: 'sessions', path: '/sessions', label: 'Sessions' },
  { key: 'hooks', path: '/hooks', label: 'Hooks' },
  { key: 'evals', path: '/eval', label: 'Evals' },
  { key: 'memories', path: '/memories', label: 'Memory' },
  { key: 'traces', path: '/traces', label: 'Traces' },
  { key: 'channels', path: '/channels', label: 'Channels' },
  { key: 'mobile', path: '/mobile-devices', label: 'Mobile' },
  { key: 'tasks', path: '/tasks', label: 'Tasks' },
  { key: 'insights', path: '/insights/patterns', label: 'Optimization' },
  { key: 'autoevolving', path: '/autoevolving', label: 'Auto-Evolving' },
];

const paletteItems: PaletteItem[] = primaryNav.map((i) => ({
  path: i.path,
  label: i.label,
  group: 'Navigate',
}));

const navItemActive = (item: NavItem, pathname: string): boolean => {
  if (item.path === '/') return pathname === '/';
  // Exact match on item.path so siblings sharing a route prefix (e.g. both
  // /insights/patterns and /insights/optimization-events live under /insights)
  // don't both light up. Sub-routes under item.path still count as active.
  return pathname === item.path || pathname.startsWith(item.path + '/');
};

const AppLayoutInner: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { theme, toggleTheme } = useTheme();
  const { userId } = useAuth();
  const queryClient = useQueryClient();
  const { addTask, resolveByMatch } = useTaskTracker();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [tweaksOpen, setTweaksOpen] = useState(false);

  // SKILL-DASHBOARD-POLISH §E — pending draft count for the sidebar Badge.
  // Shares the cache key with the SkillDrafts page so navigating between
  // them is free; WS pushes invalidate the same key (self-check #3).
  const { data: draftsData } = useQuery({
    queryKey: ['skill-drafts', userId],
    queryFn: () => getSkillDrafts(userId).then(r => r.data),
    enabled: !!userId,
    // Don't hammer the BE — drafts only change via cron / explicit extract +
    // the WS subscription below already invalidates on push.
    staleTime: 30_000,
  });
  const pendingDraftCount = useMemo(
    () => (draftsData ?? []).filter(d => d.status === 'draft').length,
    [draftsData],
  );

  // Layout-level WS subscription for `skill_draft_extracted` so the badge
  // refreshes even when the user is not on the Skills / Drafts page.
  // Cleanup must close the socket (frontend.md footgun #2).
  useEffect(() => {
    if (!userId) return;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const token = localStorage.getItem('sf_token') ?? '';
    const ws = new WebSocket(
      `${proto}://${window.location.host}/ws/users/${userId}?token=${encodeURIComponent(token)}`,
    );
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as {
          type?: string;
          count?: number;
          error?: string;
          failureReason?: string;
          agentId?: number | string;
          agentName?: string;
          abRunId?: string;
          status?: string;
          promoted?: boolean;
          deltaPassRate?: number;
          // FLYWHEEL-CHAIN-VISIBILITY gap A — `flywheel_chain_completed` payload
          // fields (annotator → dispatcher chain finished).
          optEventCount?: number;
          hasResults?: boolean;
          // OPT-REPORT-V1 Sub-batch 2 — `opt_report_completed` payload fields
          // (report-generator finished a 7-day report). See
          // OptReportService#onReportCompleted for the canonical shape.
          reportId?: string;
          summaryHighlight?: string | null;
          completedAt?: string | null;
        };
        if (msg.type === 'skill_draft_extracted') {
          queryClient.invalidateQueries({ queryKey: ['skill-drafts'] });
          const count = msg.count ?? 0;
          const agentKey =
            msg.agentId !== undefined && msg.agentId !== null
              ? String(msg.agentId)
              : null;
          const resolved = resolveByMatch(
            'skill-extract',
            (t) => agentKey === null || t.relatedId === agentKey,
            {
              state: count === 0 ? 'info' : 'success',
              detail:
                count === 0
                  ? '未发现可抽取的新技能'
                  : `抽出 ${count} 条 draft`,
            },
          );
          if (!resolved) {
            // No local task to resolve (cron-triggered / different tab) —
            // still surface so the user sees the activity.
            addTask({
              id: `extract-${Date.now()}`,
              type: 'skill-extract',
              label: agentKey
                ? `Skill 抽取 (agent ${agentKey})`
                : 'Skill 抽取',
              state: count === 0 ? 'info' : 'success',
              detail:
                count === 0
                  ? '未发现可抽取的新技能'
                  : `抽出 ${count} 条 draft`,
              relatedId: agentKey ?? undefined,
            });
          }
        } else if (msg.type === 'skill_draft_failed') {
          const agentKey =
            msg.agentId !== undefined && msg.agentId !== null
              ? String(msg.agentId)
              : null;
          const errText = msg.error ?? '未知错误';
          const resolved = resolveByMatch(
            'skill-extract',
            (t) => agentKey === null || t.relatedId === agentKey,
            { state: 'failed', detail: errText },
          );
          if (!resolved) {
            addTask({
              id: `extract-${Date.now()}`,
              type: 'skill-extract',
              label: agentKey
                ? `Skill 抽取 (agent ${agentKey})`
                : 'Skill 抽取',
              state: 'failed',
              detail: errText,
              relatedId: agentKey ?? undefined,
            });
          }
        } else if (msg.type === 'flywheel_chain_completed') {
          // FLYWHEEL-CHAIN-VISIBILITY gap A — annotator → dispatcher chain
          // finished (triggered by AgentDrawer "Run Opt Loop"). Surface a
          // persistent notification so the operator sees the outcome even if
          // they navigated away. Uses `notification.open` with duration=0 so
          // the user must click × to dismiss; matches the spec from the BE
          // payload + design.md "designed interaction states" requirement.
          const agentName = msg.agentName ?? `agent ${msg.agentId ?? '?'}`;
          const optEventCount = msg.optEventCount ?? 0;
          const hasResults = msg.hasResults === true;
          const resultText = hasResults
            ? `${optEventCount} optimization event${optEventCount > 1 ? 's' : ''} generated`
            : 'no eligible patterns found';
          const linkAgentId =
            msg.agentId !== undefined && msg.agentId !== null
              ? String(msg.agentId)
              : null;
          notification.open({
            message: `Opt Loop Complete — ${agentName}`,
            description: (
              <span>
                {resultText}.{' '}
                {hasResults && linkAgentId && (
                  <a
                    href={`/insights/patterns?tab=optimization&agentId=${linkAgentId}`}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    View events →
                  </a>
                )}
              </span>
            ),
            duration: 0, // persistent — operator dismisses with × click
            placement: 'topRight',
            key: `flywheel-chain-${msg.agentId ?? 'unknown'}-${Date.now()}`,
          });
          // Surface as a task in the TaskPanel too (mirrors ab_skill_run_completed).
          // BE already includes counts in the payload; we don't need to refetch.
        } else if (msg.type === 'opt_report_completed') {
          // OPT-REPORT-V1 Sub-batch 2 — report-generator finished. Surface
          // a persistent toast (duration=0) so the operator can click
          // "View report →" even after they switched tabs. The deep link
          // lands on /insights/patterns?tab=reports&agentId=…&reportId=…
          // so OptReportsPage auto-selects the right row.
          const reportId = msg.reportId;
          const agentName = msg.agentName ?? `agent ${msg.agentId ?? '?'}`;
          const summaryHighlight = msg.summaryHighlight ?? null;
          const linkAgentId =
            msg.agentId !== undefined && msg.agentId !== null
              ? String(msg.agentId)
              : null;
          const href =
            reportId && linkAgentId
              ? `/insights/patterns?tab=reports&agentId=${linkAgentId}&reportId=${reportId}`
              : null;
          notification.open({
            message: `Report Ready — ${agentName}`,
            description: (
              <span>
                {summaryHighlight ?? 'Report is ready to view.'}
                {href && (
                  <>
                    {' '}
                    <a href={href} target="_blank" rel="noopener noreferrer">
                      View report →
                    </a>
                  </>
                )}
              </span>
            ),
            duration: 0,
            placement: 'topRight',
            key: `opt-report-${reportId ?? msg.agentId ?? 'unknown'}-${Date.now()}`,
          });
        } else if (msg.type === 'ab_skill_run_completed') {
          const abId = msg.abRunId ?? null;
          const isFailed = msg.status === 'FAILED';
          const promoted = msg.promoted === true;
          const delta = msg.deltaPassRate;
          const detail = isFailed
            ? msg.failureReason ?? msg.error ?? 'A/B 评估失败'
            : promoted
              ? `已晋升 (Δ${delta?.toFixed?.(1) ?? '?'}pp)`
              : `未晋升 (Δ${delta?.toFixed?.(1) ?? '?'}pp)`;
          const resolved = resolveByMatch(
            'skill-ab-eval',
            (t) => abId === null || t.relatedId === abId,
            { state: isFailed ? 'failed' : 'success', detail },
          );
          if (!resolved) {
            addTask({
              id: `ab-${abId ?? Date.now()}`,
              type: 'skill-ab-eval',
              label: 'Skill A/B 评估',
              state: isFailed ? 'failed' : 'success',
              detail,
              relatedId: abId ?? undefined,
            });
          }
        }
      } catch {
        /* ignore */
      }
    };
    return () => { try { ws.close(); } catch { /* ignore */ } };
  }, [userId, queryClient]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mac = e.metaKey;
      if ((mac || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((v) => !v);
        return;
      }
      const target = e.target as HTMLElement | null;
      const typing =
        !!target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.isContentEditable);
      if (!typing && !e.metaKey && !e.ctrlKey && !e.altKey && e.key.toLowerCase() === 't') {
        setTweaksOpen((v) => !v);
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

  const isChatRoute = location.pathname.startsWith('/chat');
  const userInitial = useMemo(() => String(userId).slice(0, 2).toUpperCase(), [userId]);

  return (
    <div className="sf-shell sf-shell--topbar" data-theme={theme}>
      <header className="topbar" role="banner">
        <Link to="/" className="brand" aria-label="SkillForge home">
          <span className="brand-mark">SF</span>
          <span>
            Skill<em>Forge</em>
          </span>
        </Link>

        <nav className="topbar-nav" aria-label="Primary">
          {primaryNav.map((item) => {
            const active = navItemActive(item, location.pathname);
            const showBadge = item.key === 'skills' && pendingDraftCount > 0;
            return (
              <Link
                key={item.key}
                to={item.path}
                className={active ? 'active' : ''}
                aria-current={active ? 'page' : undefined}
              >
                {item.key === 'chat' && <IconChat />}
                {showBadge ? (
                  <Badge
                    count={pendingDraftCount}
                    size="small"
                    offset={[8, 2]}
                    overflowCount={99}
                    data-testid="drafts-badge"
                  >
                    <span style={{ paddingRight: 2 }}>{item.label}</span>
                  </Badge>
                ) : (
                  item.label
                )}
              </Link>
            );
          })}
        </nav>

        <div className="topbar-tools">
          <button
            type="button"
            className="cmdk"
            onClick={() => setPaletteOpen(true)}
            aria-label="Open command palette"
          >
            <IconSparkle />
            <span>Jump to…</span>
            <span className="kbd">⌘K</span>
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            title="Toggle theme"
          >
            {theme === 'dark' ? <IconSun /> : <IconMoon />}
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={() => setTweaksOpen((v) => !v)}
            aria-label="Appearance tweaks"
            title="Appearance (t)"
          >
            <IconSettings />
          </button>
          <div className="avatar" aria-label={`User ${userId}`}>
            {userInitial}
          </div>
        </div>
      </header>

      <div className="sf-main">
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

      <TweaksPanel open={tweaksOpen} onClose={() => setTweaksOpen(false)} />
      <TaskPanel />
    </div>
  );
};

const AppLayout: React.FC = () => (
  <TaskTrackerProvider>
    <AppLayoutInner />
  </TaskTrackerProvider>
);

export default AppLayout;
