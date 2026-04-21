import './channels.css';

const PLATFORM_LABEL: Record<string, string> = {
  web: 'web',
  feishu: '飞书',
  telegram: 'Telegram',
  mock: 'mock',
};

const KNOWN_DOT_VARIANTS = new Set(['feishu', 'telegram', 'mock']);

function normalize(platform: string | null | undefined): string {
  if (!platform) return 'web';
  const p = platform.toLowerCase();
  return p || 'web';
}

interface ChannelBadgeProps {
  platform: string | null | undefined;
  /** `chip` = compact pill with dot + label; `dot` = colored dot only */
  variant?: 'chip' | 'dot';
  className?: string;
}

export function ChannelBadge({ platform, variant = 'chip', className }: ChannelBadgeProps) {
  const p = normalize(platform);
  const label = PLATFORM_LABEL[p] ?? p;
  const dotVariant = KNOWN_DOT_VARIANTS.has(p) ? p : 'unknown';
  const dotClass = p === 'web'
    ? 'channel-badge-dot channel-badge-dot--web'
    : `platform-dot platform-dot--${dotVariant}`;

  if (variant === 'dot') {
    return <span className={dotClass} title={label} aria-label={label} />;
  }

  return (
    <span className={`channel-badge ${className ?? ''}`} title={`Channel: ${label}`}>
      <span className={dotClass} />
      <span className="channel-badge-label">{label}</span>
    </span>
  );
}
