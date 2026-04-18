import React from 'react';

interface IconProps {
  s?: number;
}

export const IconChat: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M2 4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H6l-3 2v-2.3A2 2 0 0 1 2 10V4Z" />
  </svg>
);

export const IconSearch: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="7" cy="7" r="4.5" />
    <path d="M10.5 10.5L13.5 13.5" />
  </svg>
);

export const IconPlus: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M8 3v10M3 8h10" />
  </svg>
);

export const IconSun: React.FC<IconProps> = ({ s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <circle cx="8" cy="8" r="3" />
    <path d="M8 1v2M8 13v2M1 8h2M13 8h2M3 3l1.4 1.4M11.6 11.6L13 13M3 13l1.4-1.4M11.6 4.4 13 3" />
  </svg>
);

export const IconMoon: React.FC<IconProps> = ({ s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M13 9A5 5 0 1 1 7 3a4 4 0 0 0 6 6Z" />
  </svg>
);

export const IconSend: React.FC<IconProps> = ({ s = 16 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
    <path d="M2.5 8L13.5 3l-3 10-2.5-4-5.5-1Z" />
  </svg>
);

export const IconCheck: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 8.5l3 3 7-7" />
  </svg>
);

export const IconX: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <path d="M3 3l10 10M13 3l-10 10" />
  </svg>
);

export const IconPlay: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="currentColor">
    <path d="M4 3l9 5-9 5z" />
  </svg>
);

export const IconPause: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="currentColor">
    <rect x="4" y="3" width="3" height="10" />
    <rect x="9" y="3" width="3" height="10" />
  </svg>
);

export const IconTool: React.FC<IconProps> = ({ s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M10.5 3.5a3 3 0 0 0-3 3c0 .5.1 1 .3 1.4L2 13.7 3.3 15l5.8-5.8c.4.2.9.3 1.4.3a3 3 0 1 0 0-6Z" />
  </svg>
);

export const IconTeam: React.FC<IconProps> = ({ s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="6" cy="6.5" r="2" />
    <circle cx="11.5" cy="7" r="1.6" />
    <path d="M2.5 13c.4-2 2-3 3.5-3s3.1 1 3.5 3M10 13c.3-1.4 1.3-2.2 2.4-2.2" />
  </svg>
);

export const IconCompact: React.FC<IconProps> = ({ s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M3 6l3-3 3 3M3 10l3 3 3-3M11 3v10" />
  </svg>
);

export const IconReplay: React.FC<IconProps> = ({ s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M2 8a6 6 0 1 0 2-4.5L2 6M2 2v4h4" />
  </svg>
);

export const IconSparkle: React.FC<IconProps> = ({ s = 12 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
    <path d="M8 2v3M8 11v3M2 8h3M11 8h3M4 4l2 2M10 10l2 2M12 4l-2 2M4 12l2-2" />
  </svg>
);

export const IconMic: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <rect x="6" y="2" width="4" height="8" rx="2" />
    <path d="M3.5 8a4.5 4.5 0 0 0 9 0M8 12.5V14" />
  </svg>
);

export const IconAttach: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M10 5L5 10a2.5 2.5 0 0 0 3.5 3.5L13 9a4 4 0 0 0-5.5-5.5L3 8" />
  </svg>
);

export const IconSettings: React.FC<IconProps> = ({ s = 14 }) => (
  <svg width={s} height={s} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
    <circle cx="8" cy="8" r="2" />
    <path d="M8 2v1.5M8 12.5V14M2 8h1.5M12.5 8H14M3.8 3.8l1 1M11.2 11.2l1 1M3.8 12.2l1-1M11.2 4.8l1-1" />
  </svg>
);
