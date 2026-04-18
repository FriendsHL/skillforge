import React, { createContext, useContext, useEffect, useState } from 'react';
import { darkTokens, lightTokens, type ThemeMode, type ThemeTokens } from '../styles/tokens';

interface ThemeContextValue {
  theme: ThemeMode;
  tokens: ThemeTokens;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'light',
  tokens: lightTokens,
  toggleTheme: () => {},
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

export const ThemeProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [theme, setTheme] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem('sf-theme');
    if (stored === 'dark' || stored === 'light') return stored;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });

  const tokens = theme === 'dark' ? darkTokens : lightTokens;

  useEffect(() => {
    applyCssVars(tokens);
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme, tokens]);

  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem('sf-theme', next);
    setTheme(next);
  };

  return (
    <ThemeContext.Provider value={{ theme, tokens, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
};
