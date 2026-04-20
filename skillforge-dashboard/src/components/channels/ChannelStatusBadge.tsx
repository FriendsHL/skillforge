import React from 'react';
import './channels.css';

interface ChannelStatusBadgeProps {
  active: boolean;
}

const ChannelStatusBadge: React.FC<ChannelStatusBadgeProps> = React.memo(({ active }) => (
  <span className={`ch-status-badge ch-status-badge--${active ? 'active' : 'inactive'}`}>
    {active ? 'Active' : 'Inactive'}
  </span>
));

ChannelStatusBadge.displayName = 'ChannelStatusBadge';

export default ChannelStatusBadge;
