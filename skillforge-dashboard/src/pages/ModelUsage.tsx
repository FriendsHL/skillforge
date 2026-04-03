import React from 'react';
import { Card, Empty } from 'antd';

const ModelUsage: React.FC = () => {
  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>Model Usage</h2>
      <Card>
        <Empty description="Model usage statistics coming soon" />
      </Card>
    </div>
  );
};

export default ModelUsage;
