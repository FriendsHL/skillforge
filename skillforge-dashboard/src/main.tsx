import React, { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ConfigProvider, theme as antTheme } from 'antd';
import enUS from 'antd/locale/en_US';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import App from './App';
import { ThemeProvider, useTheme } from './contexts/ThemeContext';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

const ThemeConfigProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { theme, tokens } = useTheme();
  return (
    <ConfigProvider
      locale={enUS}
      theme={{
        algorithm: theme === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
        token: {
          colorPrimary: tokens.accentPrimary,
          colorBgLayout: tokens.bgBase,
          colorBgContainer: tokens.bgSurface,
          colorBgElevated: tokens.bgSurface,
          colorBorder: tokens.borderSubtle,
          colorText: tokens.textPrimary,
          colorTextSecondary: tokens.textSecondary,
          borderRadius: 8,
          borderRadiusLG: 12,
          fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, sans-serif",
          fontSize: 15,
        },
        components: {
          Layout: {
            siderBg: tokens.bgSidebar,
            bodyBg: tokens.bgBase,
            headerBg: tokens.bgSurface,
            headerHeight: 48,
            headerPadding: '0 16px',
          },
          Menu: {
            itemBg: 'transparent',
            itemSelectedBg: tokens.bgHover,
            itemHoverBg: tokens.bgHover,
            itemColor: tokens.textSecondary,
            itemSelectedColor: tokens.textPrimary,
            activeBarBorderWidth: 0,
          },
          Card: {
            borderRadiusLG: 10,
          },
        },
      }}
    >
      {children}
    </ConfigProvider>
  );
};

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <ThemeConfigProvider>
          <App />
        </ThemeConfigProvider>
      </ThemeProvider>
    </QueryClientProvider>
  </StrictMode>,
);
