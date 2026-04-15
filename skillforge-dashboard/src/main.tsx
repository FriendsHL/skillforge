import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import App from './App';
import './index.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#2563EB',
          colorBgLayout: '#FAFAFA',
          colorBgContainer: '#FFFFFF',
          colorBgElevated: '#FFFFFF',
          colorBorder: '#E2E0DC',
          colorText: '#1A1915',
          colorTextSecondary: '#6B6760',
          borderRadius: 8,
          borderRadiusLG: 12,
          fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
          fontSize: 15,
        },
        components: {
          Layout: {
            siderBg: '#F5F4F2',
            bodyBg: '#FAFAFA',
            headerBg: '#FFFFFF',
            headerHeight: 48,
            headerPadding: '0 16px',
          },
          Menu: {
            itemBg: 'transparent',
            itemSelectedBg: '#ECEAE6',
            itemHoverBg: '#ECEAE6',
            itemColor: '#6B6760',
            itemSelectedColor: '#1A1915',
            activeBarBorderWidth: 0,
          },
          Card: {
            borderRadiusLG: 10,
          },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </StrictMode>,
);
