import React, { createContext, useContext, useEffect, useState } from 'react';
import { darkTokens, lightTokens, type ThemeMode, type ThemeTokens } from '../styles/tokens';

export type Density = 'compact' | 'cozy' | 'spacious';
export type Accent = 'terracotta' | 'ink' | 'moss' | 'plum';

interface ThemeContextValue {
  theme: ThemeMode;
  tokens: ThemeTokens;
  toggleTheme: () => void;
  setTheme: (m: ThemeMode) => void;
  density: Density;
  setDensity: (d: Density) => void;
  accent: Accent;
  setAccent: (a: Accent) => void;
  serifHeadings: boolean;
  setSerifHeadings: (v: boolean) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'light',
  tokens: lightTokens,
  toggleTheme: () => {},
  setTheme: () => {},
  density: 'cozy',
  setDensity: () => {},
  accent: 'terracotta',
  setAccent: () => {},
  serifHeadings: true,
  setSerifHeadings: () => {},
});

export const useTheme = () => useContext(ThemeContext);

const CSS_VAR_MAP: Record<string, keyof ThemeTokens> = {
  '--bg-base': 'bgBase',
  '--bg-primary': 'bgBase',
  '--bg-sidebar': 'bgSidebar',
  '--bg-surface': 'bgSurface',
  '--bg-user-msg': 'bgUserMsg',
  '--bg-assistant-structured': 'bgSidebar',
  '--bg-code': 'bgCode',
  '--bg-hover': 'bgHover',
  '--text-primary': 'textPrimary',
  '--text-secondary': 'textSecondary',
  '--text-tertiary': 'textTertiary',
  '--text-muted': 'textMuted',
  '--text-on-accent': 'textOnAccent',
  '--accent-primary': 'accentPrimary',
  '--accent-primary-hover': 'accentPrimaryHover',
  '--accent-muted': 'accentMuted',
  '--border-subtle': 'borderSubtle',
  '--border-medium': 'borderMedium',
  '--color-ok': 'colorOk',
  '--color-warn': 'colorWarn',
  '--color-err': 'colorErr',
  '--color-error': 'colorError',
  '--color-error-bg': 'colorErrorBg',
  '--color-error-border': 'colorErrorBorder',
  '--color-success': 'colorSuccess',
  '--color-warning': 'colorWarning',
  '--color-info': 'colorInfo',
  '--a-leader': 'aLeader',
  '--a-reviewer': 'aReviewer',
  '--a-writer': 'aWriter',
  '--a-evaluator': 'aEvaluator',
  '--a-grep': 'aGrep',
  '--a-judge': 'aJudge',
  '--shadow-1': 'shadow1',
  '--shadow-2': 'shadow2',
  '--shadow-3': 'shadow3',
  '--shadow-input': 'shadowInput',
  '--shadow-surface': 'shadowSurface',
  '--shadow-card': 'shadowCard',
  '--shadow-card-hover': 'shadowCardHover',
  '--shadow-elevated': 'shadowElevated',
  '--op-thinking': 'opThinking',
  '--op-search': 'opSearch',
  '--op-read': 'opRead',
  '--op-write': 'opWrite',
  '--op-execute': 'opExecute',
};

function applyCssVars(tokens: ThemeTokens) {
  const root = document.documentElement;
  for (const [cssVar, tokenKey] of Object.entries(CSS_VAR_MAP)) {
    root.style.setProperty(cssVar, tokens[tokenKey]);
  }
}

const ACCENT_COLORS: Record<Accent, { accent: string; hover: string; ink: string }> = {
  terracotta: { accent: '#d9633a', hover: '#c2522d', ink: '#ffffff' },
  ink: { accent: '#2a2e3a', hover: '#1a1d26', ink: '#f6f3ec' },
  moss: { accent: '#4f7a55', hover: '#3e6344', ink: '#ffffff' },
  plum: { accent: '#7a4f6b', hover: '#633d57', ink: '#ffffff' },
};

function readStored<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  const v = localStorage.getItem(key);
  return v && (allowed as readonly string[]).includes(v) ? (v as T) : fallback;
}

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [theme, setThemeState] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem('sf-theme');
    if (stored === 'dark' || stored === 'light') return stored;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });

  const [density, setDensityState] = useState<Density>(() =>
    readStored<Density>('sf-density', ['compact', 'cozy', 'spacious'], 'cozy'),
  );
  const [accent, setAccentState] = useState<Accent>(() =>
    readStored<Accent>('sf-accent', ['terracotta', 'ink', 'moss', 'plum'], 'terracotta'),
  );
  const [serifHeadings, setSerifHeadingsState] = useState<boolean>(() => {
    const v = localStorage.getItem('sf-serif');
    return v === null ? true : v === '1';
  });

  const tokens = theme === 'dark' ? darkTokens : lightTokens;

  useEffect(() => {
    applyCssVars(tokens);
    const root = document.documentElement;
    root.setAttribute('data-theme', theme);
    root.setAttribute('data-density', density);
    root.setAttribute('data-accent', accent);
    root.setAttribute('data-serif', serifHeadings ? '1' : '0');
    const a = ACCENT_COLORS[accent];
    root.style.setProperty('--accent', a.accent);
    root.style.setProperty('--accent-hover', a.hover);
    root.style.setProperty('--accent-ink', a.ink);
  }, [theme, tokens, density, accent, serifHeadings]);

  const setTheme = (m: ThemeMode) => {
    localStorage.setItem('sf-theme', m);
    setThemeState(m);
  };
  const toggleTheme = () => setTheme(theme === 'dark' ? 'light' : 'dark');
  const setDensity = (d: Density) => {
    localStorage.setItem('sf-density', d);
    setDensityState(d);
  };
  const setAccent = (a: Accent) => {
    localStorage.setItem('sf-accent', a);
    setAccentState(a);
  };
  const setSerifHeadings = (v: boolean) => {
    localStorage.setItem('sf-serif', v ? '1' : '0');
    setSerifHeadingsState(v);
  };

  return (
    <ThemeContext.Provider
      value={{
        theme,
        tokens,
        toggleTheme,
        setTheme,
        density,
        setDensity,
        accent,
        setAccent,
        serifHeadings,
        setSerifHeadings,
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
};
