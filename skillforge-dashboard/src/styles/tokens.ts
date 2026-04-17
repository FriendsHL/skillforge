export const darkTokens = {
  bgVoid: '#07080a',
  bgBase: '#0f1011',
  bgSurface: '#191a1b',
  bgHover: '#28282c',
  bgOverlay: '#1b1c1e',
  bgUserMsg: '#28282c',
  bgCode: '#0f1011',
  bgSidebar: '#0f1011',

  textPrimary: '#f7f8f8',
  textSecondary: '#d0d6e0',
  textTertiary: '#8a8f98',
  textMuted: '#62666d',
  textOnAccent: '#ffffff',

  accentPrimary: '#6366f1',
  accentPrimaryHover: '#818cf8',
  accentMuted: 'rgba(99, 102, 241, 0.15)',

  borderSubtle: '#23252a',
  borderMedium: '#34343a',

  colorSuccess: '#52c41a',
  colorWarning: '#faad14',
  colorError: '#ff4d4f',
  colorInfo: '#1677ff',
  colorWarn: '#d97706',
  colorErrorBg: 'rgba(255, 77, 79, 0.1)',
  colorErrorBorder: 'rgba(255, 77, 79, 0.3)',

  shadowInput: '0 1px 4px rgba(0,0,0,0.2), 0 0 0 1px #23252a',
  shadowSurface: '0 1px 3px rgba(0,0,0,0.2)',
  shadowCard: '0 1px 3px rgba(0,0,0,0.2), 0 1px 2px rgba(0,0,0,0.15)',
  shadowCardHover: '0 4px 12px rgba(0,0,0,0.3), 0 2px 4px rgba(0,0,0,0.15)',
  shadowElevated: '0 8px 24px rgba(0,0,0,0.3), 0 2px 8px rgba(0,0,0,0.2)',

  opThinking: '#dfa88f',
  opSearch: '#9fc9a2',
  opRead: '#9fbbe0',
  opWrite: '#c0a8dd',
  opExecute: '#ffbc33',
} as const;

export const lightTokens = {
  bgVoid: '#f5f4f2',
  bgBase: '#fafafa',
  bgSurface: '#ffffff',
  bgHover: '#eceae6',
  bgOverlay: '#f5f4f2',
  bgUserMsg: '#e8e6e2',
  bgCode: '#1c1c1e',
  bgSidebar: '#f5f4f2',

  textPrimary: '#1a1915',
  textSecondary: '#6b6760',
  textTertiary: '#7a7870',
  textMuted: '#7a7870',
  textOnAccent: '#ffffff',

  accentPrimary: '#6366f1',
  accentPrimaryHover: '#4f46e5',
  accentMuted: '#eef2ff',

  borderSubtle: '#e2e0dc',
  borderMedium: '#d1cfc9',

  colorSuccess: '#52c41a',
  colorWarning: '#faad14',
  colorError: '#991b1b',
  colorInfo: '#1677ff',
  colorWarn: '#b45309',
  colorErrorBg: '#fef2f2',
  colorErrorBorder: '#fecaca',

  shadowInput: '0 1px 4px rgba(0,0,0,0.08), 0 0 0 1px #e2e0dc',
  shadowSurface: '0 1px 3px rgba(0,0,0,0.06)',
  shadowCard: '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
  shadowCardHover: '0 4px 12px rgba(0,0,0,0.08), 0 2px 4px rgba(0,0,0,0.04)',
  shadowElevated: '0 8px 24px rgba(0,0,0,0.10), 0 2px 8px rgba(0,0,0,0.06)',

  opThinking: '#c2774d',
  opSearch: '#5a8a5e',
  opRead: '#5a85b0',
  opWrite: '#8a6aad',
  opExecute: '#d4960a',
} as const satisfies ThemeTokens;

export type ThemeTokens = { readonly [K in keyof typeof darkTokens]: string };
export type ThemeMode = 'dark' | 'light';
