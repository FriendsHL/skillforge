export const lightTokens = {
  bgVoid: '#f3f0e9',
  bgBase: '#fbfaf7',
  bgSurface: '#ffffff',
  bgHover: '#eceae3',
  bgOverlay: '#f3f0e9',
  bgUserMsg: '#e8e4d9',
  bgCode: '#1c1c1e',
  bgSidebar: '#f3f0e9',

  textPrimary: '#1a1815',
  textSecondary: '#5d5952',
  textTertiary: '#7a7770',
  textMuted: '#8a8780',
  textOnAccent: '#ffffff',

  accentPrimary: '#d9633a',
  accentPrimaryHover: '#c2522d',
  accentMuted: 'rgba(217, 99, 58, 0.12)',

  borderSubtle: '#e2ded3',
  borderMedium: '#cfcbc0',

  colorSuccess: '#5c8a4a',
  colorWarning: '#d49a3a',
  colorError: '#b8412f',
  colorInfo: '#4a7aa8',
  colorOk: '#5c8a4a',
  colorWarn: '#d49a3a',
  colorErr: '#b8412f',
  colorErrorBg: 'rgba(184, 65, 47, 0.08)',
  colorErrorBorder: 'rgba(184, 65, 47, 0.25)',

  aLeader: '#d9633a',
  aReviewer: '#4a7aa8',
  aWriter: '#8a6fb3',
  aEvaluator: '#5c8a4a',
  aGrep: '#c49a3a',
  aJudge: '#b8412f',

  shadow1: '0 1px 2px rgba(26,24,21,0.06)',
  shadow2: '0 4px 14px rgba(26,24,21,0.08)',
  shadow3: '0 16px 40px rgba(26,24,21,0.12)',

  shadowInput: '0 1px 4px rgba(0,0,0,0.08), 0 0 0 1px #e2ded3',
  shadowSurface: '0 1px 3px rgba(0,0,0,0.06)',
  shadowCard: '0 1px 3px rgba(0,0,0,0.06), 0 1px 2px rgba(0,0,0,0.04)',
  shadowCardHover: '0 4px 12px rgba(0,0,0,0.08), 0 2px 4px rgba(0,0,0,0.04)',
  shadowElevated: '0 8px 24px rgba(0,0,0,0.10), 0 2px 8px rgba(0,0,0,0.06)',

  opThinking: '#c2774d',
  opSearch: '#5a8a5e',
  opRead: '#5a85b0',
  opWrite: '#8a6aad',
  opExecute: '#d4960a',
} as const;

export type ThemeTokens = { readonly [K in keyof typeof lightTokens]: string };
export type ThemeMode = 'dark' | 'light';

export const darkTokens = {
  bgVoid: '#0b0a08',
  bgBase: '#14120f',
  bgSurface: '#221f1a',
  bgHover: '#2c2923',
  bgOverlay: '#1c1a16',
  bgUserMsg: '#2c2923',
  bgCode: '#0b0a08',
  bgSidebar: '#1c1a16',

  textPrimary: '#f7f5f0',
  textSecondary: '#d0cabf',
  textTertiary: '#8a8578',
  textMuted: '#62605a',
  textOnAccent: '#ffffff',

  accentPrimary: '#d9633a',
  accentPrimaryHover: '#c2522d',
  accentMuted: 'rgba(217, 99, 58, 0.18)',

  borderSubtle: '#2b2823',
  borderMedium: '#3a3731',

  colorSuccess: '#5c8a4a',
  colorWarning: '#d49a3a',
  colorError: '#b8412f',
  colorInfo: '#4a7aa8',
  colorOk: '#5c8a4a',
  colorWarn: '#d49a3a',
  colorErr: '#b8412f',
  colorErrorBg: 'rgba(184, 65, 47, 0.12)',
  colorErrorBorder: 'rgba(184, 65, 47, 0.35)',

  aLeader: '#d9633a',
  aReviewer: '#4a7aa8',
  aWriter: '#8a6fb3',
  aEvaluator: '#5c8a4a',
  aGrep: '#c49a3a',
  aJudge: '#b8412f',

  shadow1: '0 1px 2px rgba(0,0,0,0.35)',
  shadow2: '0 4px 14px rgba(0,0,0,0.4)',
  shadow3: '0 16px 40px rgba(0,0,0,0.5)',

  shadowInput: '0 1px 4px rgba(0,0,0,0.25), 0 0 0 1px #2b2823',
  shadowSurface: '0 1px 3px rgba(0,0,0,0.3)',
  shadowCard: '0 1px 3px rgba(0,0,0,0.3), 0 1px 2px rgba(0,0,0,0.2)',
  shadowCardHover: '0 4px 12px rgba(0,0,0,0.35), 0 2px 4px rgba(0,0,0,0.2)',
  shadowElevated: '0 8px 24px rgba(0,0,0,0.4), 0 2px 8px rgba(0,0,0,0.25)',

  opThinking: '#dfa88f',
  opSearch: '#9fc9a2',
  opRead: '#9fbbe0',
  opWrite: '#c0a8dd',
  opExecute: '#ffbc33',
} as const satisfies ThemeTokens;
