import React, { useMemo, useState } from 'react';
import { Layout as AntLayout, Menu, Button, Drawer, Grid } from 'antd';
import {
  DashboardOutlined,
  RobotOutlined,
  ToolOutlined,
  MessageOutlined,
  CommentOutlined,
  BulbOutlined,
  BarChartOutlined,
  ApartmentOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  TeamOutlined,
  ExperimentOutlined,
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
  { key: '/teams', icon: <TeamOutlined />, label: 'Teams' },
  { key: '/eval', icon: <ExperimentOutlined />, label: 'Eval' },
  { key: '/chat', icon: <CommentOutlined />, label: 'Chat' },
];

const pageTitles: Record<string, string> = {
  '/': 'Dashboard',
  '/agents': 'Agents',
  '/skills': 'Skills & Tools',
  '/memories': 'Memories',
  '/sessions': 'Sessions',
  '/usage': 'Usage',
  '/traces': 'Traces',
  '/teams': 'Teams',
  '/eval': 'Eval Pipeline',
  '/chat': 'Chat',
};

const AppLayout: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const screens = Grid.useBreakpoint();
  const isMobile = screens.md === false;
  const [collapsed, setCollapsed] = useState(() => window.innerWidth < 768);

  const selectedKey = menuItems
    .filter((item) => location.pathname.startsWith(item.key) && item.key !== '/')
    .sort((a, b) => b.key.length - a.key.length)[0]?.key || '/';

  const pageTitle = pageTitles[selectedKey] || 'SkillForge';

  const brandHeader = useMemo(() => (
    <div
      style={{
        height: 48,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: 'var(--text-primary)',
        fontSize: 20,
        fontWeight: 700,
        letterSpacing: 1,
      }}
    >
      SkillForge
    </div>
  ), []);

  const navMenu = useMemo(() => (
    <Menu
      mode="inline"
      selectedKeys={[selectedKey]}
      items={menuItems}
      onClick={({ key }) => {
        navigate(key);
        if (isMobile) setCollapsed(true);
      }}
      style={{ borderRight: 'none', background: 'var(--bg-sidebar)' }}
    />
  ), [selectedKey, isMobile, navigate]);

  return (
    <AntLayout style={{ height: '100vh', overflow: 'hidden' }}>
      {isMobile ? (
        <Drawer
          placement="left"
          open={!collapsed}
          onClose={() => setCollapsed(true)}
          width={260}
          closable={false}
          styles={{
            body: { padding: 0, background: 'var(--bg-sidebar)' },
            header: { display: 'none' },
            content: { background: 'var(--bg-sidebar)' },
          }}
        >
          {brandHeader}
          {navMenu}
        </Drawer>
      ) : (
        <Sider
          width={260}
          collapsed={collapsed}
          collapsedWidth={0}
          trigger={null}
          className="sf-sider"
          style={{
            background: 'var(--bg-sidebar)',
            borderRight: '1px solid var(--border-subtle)',
          }}
        >
          {brandHeader}
          {navMenu}
        </Sider>
      )}
      <AntLayout>
        <Header
          style={{
            height: 48,
            lineHeight: '48px',
            background: 'var(--bg-surface)',
            padding: '0 16px',
            borderBottom: '1px solid var(--border-subtle)',
            display: 'flex',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <Button
            type="text"
            aria-label="Toggle navigation"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((c) => !c)}
            style={{ fontSize: 16 }}
          />
          <span style={{ fontSize: 16, fontWeight: 500, color: 'var(--text-primary)' }}>{pageTitle}</span>
        </Header>
        <Content style={{
          flex: 1,
          overflow: location.pathname.startsWith('/chat') ? 'hidden' : 'auto',
          padding: location.pathname.startsWith('/chat') ? 0 : '24px 32px',
        }}>
          <Outlet />
        </Content>
      </AntLayout>
    </AntLayout>
  );
};

export default AppLayout;
