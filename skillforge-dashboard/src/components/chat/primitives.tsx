import React from 'react';

interface ChipProps {
  children: React.ReactNode;
  onClick?: () => void;
  title?: string;
  active?: boolean;
}

export const Chip: React.FC<ChipProps> = ({ children, onClick, title, active }) => (
  <button
    type="button"
    className="chip"
    onClick={onClick}
    title={title}
    style={active ? { borderColor: 'var(--accent)', color: 'var(--accent)' } : undefined}
  >
    {children}
  </button>
);

interface SegOption<T extends string> {
  value: T;
  label: string;
  icon?: React.ReactNode;
}

interface SegProps<T extends string> {
  value: T;
  options: SegOption<T>[];
  onChange: (v: T) => void;
}

export function Seg<T extends string>({ value, options, onChange }: SegProps<T>) {
  return (
    <div className="seg">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          className={value === o.value ? 'on' : ''}
          onClick={() => onChange(o.value)}
        >
          {o.icon && <span>{o.icon}</span>}
          {o.label}
        </button>
      ))}
    </div>
  );
}

type AvatarRole = 'leader' | 'reviewer' | 'writer' | 'assistant' | 'user' | 'judge' | 'evaluator';

const AVATAR_LETTERS: Record<AvatarRole, string> = {
  leader: 'L',
  reviewer: 'R',
  writer: 'W',
  assistant: 'SF',
  user: 'U',
  judge: 'J',
  evaluator: 'E',
};

interface RoleAvatarProps {
  role?: AvatarRole;
  className?: string;
}

export const RoleAvatar: React.FC<RoleAvatarProps> = ({ role = 'assistant', className = '' }) => (
  <div className={`msg-avatar ${role} ${className}`}>{AVATAR_LETTERS[role]}</div>
);
