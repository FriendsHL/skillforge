import React from 'react';
import { Layout as AntLayout, Menu } from 'antd';
import {
  DashboardOutlined,
  RobotOutlined,
  ToolOutlined,
  MessageOutlined,
  CommentOutlined,
  BulbOutlined,
  BarChartOutlined,
  ApartmentOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';

const { Sider, Content, Header } = AntLayout;

const menuItems = [
  { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/agents', icon: <RobotOutlined />, label: 'Agents' },
  { key: '/skills', icon: <ToolOutlined />, label: 'Skills' },
  { key: '/memories', icon: <BulbOutlined />, label: 'Memories' },
  { key: '/sessions', icon: <MessageOutlined />, label: 'Sessions' },
  { key: '/usage', icon: <BarChartOutlined />, label: 'Usage' },
  { key: '/traces', icon: <ApartmentOutlined />, label: 'Traces' },
  { key: '/chat', icon: <CommentOutlined />, label: 'Chat' },
];

const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();

  const selectedKey = menuItems
    .filter((item) => location.pathname.startsWith(item.key) && item.key !== '/')
    .sort((a, b) => b.key.length - a.key.length)[0]?.key || '/';

  return (
    <AntLayout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" breakpoint="lg" collapsedWidth={80}>
        <div
          style={{
            height: 64,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontSize: 20,
            fontWeight: 700,
            letterSpacing: 1,
          }}
        >
          SkillForge
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <AntLayout>
        <Header style={{ background: '#fff', padding: '0 24px', borderBottom: '1px solid #f0f0f0' }}>
          <span style={{ fontSize: 16, fontWeight: 500 }}>SkillForge Dashboard</span>
        </Header>
        <Content style={{ margin: 16 }}>
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  );
};

export default AppLayout;
