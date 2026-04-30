import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Input, Button, message } from 'antd';
import { useAuth } from '../contexts/AuthContext';
import api from '../api';

// SkillForge Logo - SF Letters
const SkillForgeLogo = () => (
  <svg
    width="36"
    height="36"
    viewBox="0 0 32 32"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    style={{ filter: 'drop-shadow(0 4px 12px rgba(63, 185, 80, 0.3))' }}
  >
    {/* Background */}
    <rect width="32" height="32" rx="6" fill="#0d1117" />
    
    {/* S letter - block style */}
    <g fill="#3fb950">
      <rect x="5" y="5" width="12" height="4" rx="1" />
      <rect x="5" y="9" width="4" height="6" rx="1" />
      <rect x="5" y="13" width="12" height="4" rx="1" />
      <rect x="13" y="17" width="4" height="6" rx="1" />
      <rect x="5" y="21" width="12" height="4" rx="1" />
    </g>
    
    {/* F letter - block style */}
    <g fill="#79c0ff">
      <rect x="20" y="5" width="4" height="20" rx="1" />
      <rect x="20" y="5" width="9" height="4" rx="1" />
      <rect x="20" y="13" width="7" height="4" rx="1" />
    </g>
  </svg>
);

const Login: React.FC = () => {
  const [tokenInput, setTokenInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [fetching, setFetching] = useState(true);
  const { login } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    api
      .get('/auth/local-token')
      .then((res: { data: { token?: string } }) => setTokenInput(res.data.token ?? ''))
      .catch(() => {})
      .finally(() => setFetching(false));
  }, []);

  const handleLogin = async () => {
    if (!tokenInput.trim()) return;
    setLoading(true);
    try {
      const res = await api.post('/auth/verify', { token: tokenInput.trim() });
      if ((res.data as { valid?: boolean }).valid) {
        login(tokenInput.trim());
        navigate('/');
      } else {
        message.error('Token 无效');
      }
    } catch {
      message.error('Token 无效');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      {/* Left: Brand Panel - Terminal Style */}
      <div className="login-brand-panel">
        {/* Terminal window frame */}
        <div className="terminal-window">
          <div className="terminal-header">
            <div className="terminal-dots">
              <span className="terminal-dot terminal-dot-red" />
              <span className="terminal-dot terminal-dot-yellow" />
              <span className="terminal-dot terminal-dot-green" />
            </div>
            <span className="terminal-title">skillforge — bash</span>
          </div>
          
          <div className="terminal-body">
            {/* Brand header as comment */}
            <div className="terminal-line terminal-comment">
              <span className="terminal-line-number">1</span>
              <span className="terminal-content">{'// SkillForge - AI Agent Skill Platform'}</span>
            </div>
            <div className="terminal-line terminal-comment">
              <span className="terminal-line-number">2</span>
              <span className="terminal-content">{'// ────────────────────────────────'}</span>
            </div>
            
            <div className="terminal-spacer" />
            
            {/* Logo as ASCII-style display */}
            <div className="terminal-logo-section">
              <div className="terminal-logo-line">
                <span className="terminal-line-number">4</span>
                <span className="terminal-prompt">$</span>
                <span className="terminal-command">skillforge</span>
                <span className="terminal-flag">--version</span>
              </div>
              <div className="terminal-output">
                <div className="terminal-brand-row">
                  <SkillForgeLogo />
                  <div className="terminal-brand-text">
                    <span className="terminal-brand-name">SkillForge</span>
                    <span className="terminal-brand-version">v2.0.0</span>
                  </div>
                </div>
              </div>
            </div>
            
            <div className="terminal-spacer" />
            
            {/* Features as code */}
            <div className="terminal-line">
              <span className="terminal-line-number">9</span>
              <span className="terminal-prompt">$</span>
              <span className="terminal-command">cat</span>
              <span className="terminal-arg">features.json</span>
            </div>
            <div className="terminal-output terminal-code-block">
              <div className="terminal-code-line">
                <span className="terminal-bracket">{'{'}</span>
              </div>
              <div className="terminal-code-line terminal-code-indent">
                <span className="terminal-key">"orchestration"</span>
                <span className="terminal-colon">:</span>
                <span className="terminal-string">"智能技能编排"</span>
                <span className="terminal-comma">,</span>
              </div>
              <div className="terminal-code-line terminal-code-indent">
                <span className="terminal-key">"collaboration"</span>
                <span className="terminal-colon">:</span>
                <span className="terminal-string">"多 Agent 协作"</span>
                <span className="terminal-comma">,</span>
              </div>
              <div className="terminal-code-line terminal-code-indent">
                <span className="terminal-key">"tracing"</span>
                <span className="terminal-colon">:</span>
                <span className="terminal-string">"全链路追踪"</span>
              </div>
              <div className="terminal-code-line">
                <span className="terminal-bracket">{'}'}</span>
              </div>
            </div>
            
            <div className="terminal-spacer" />
            
            {/* Cursor line */}
            <div className="terminal-line terminal-cursor-line">
              <span className="terminal-line-number">18</span>
              <span className="terminal-prompt">$</span>
              <span className="terminal-cursor" />
            </div>
          </div>
        </div>
        
        {/* Subtle scan line effect */}
        <div className="terminal-scanline" />
      </div>

      {/* Right: Login Form */}
      <div className="login-form-panel">
        <div className="login-form-card">
          {/* Header - no logo to avoid conflict with left side */}
          <div className="login-form-header">
            <h2 className="login-form-title">登录</h2>
            <p className="login-form-subtitle">验证 Access Token 以访问平台</p>
          </div>

          {/* Server connection status */}
          <div className="login-server-info">
            <div className="login-server-status">
              <span className={`login-status-dot ${fetching ? 'loading' : 'connected'}`} />
              <span className="login-status-text">
                {fetching ? '连接中...' : '已连接'}
              </span>
            </div>
            <div className="login-server-address">
              <span className="login-address-label">API</span>
              <span className="login-address-value">http://localhost:8080</span>
            </div>
          </div>

          {/* Main: Token input */}
          <div className="login-form-section">
            <div className="login-input-header">
              <label className="login-input-label">Access Token</label>
              {!fetching && tokenInput && (
                <span className="login-input-status">✓ 已获取</span>
              )}
            </div>
            <Input
              size="large"
              placeholder={fetching ? '正在获取...' : '粘贴或输入 Access Token'}
              value={tokenInput}
              onChange={(e) => setTokenInput(e.target.value)}
              onPressEnter={handleLogin}
              disabled={fetching}
              className="login-token-input"
            />
            <Button
              type="primary"
              size="large"
              block
              loading={loading}
              disabled={fetching || !tokenInput.trim()}
              onClick={handleLogin}
              className="login-submit-btn"
            >
              {loading ? '验证中...' : '进入平台'}
            </Button>
          </div>

          {/* Divider */}
          <div className="login-divider">
            <span className="login-divider-line" />
            <span className="login-divider-text">或者</span>
            <span className="login-divider-line" />
          </div>

          {/* Alternative: Local dev mode */}
          <div className="login-form-section login-alt-section">
            <div className="login-alt-header">
              <span className="login-alt-icon">⚡</span>
              <span className="login-alt-title">本地开发模式</span>
            </div>
            <p className="login-alt-desc">
              已自动从服务器获取 Token，可直接使用快速登录
            </p>
            <Button
              size="large"
              block
              disabled={fetching || !tokenInput.trim()}
              onClick={handleLogin}
              className="login-alt-btn"
            >
              快速登录
            </Button>
          </div>

          {/* Footer */}
          <div className="login-form-footer">
            <p>Token 存储于浏览器本地，仅用于 API 请求鉴权</p>
          </div>
        </div>
      </div>

      {/* Embedded styles */}
      <style>{`
        .login-page {
          min-height: 100vh;
          display: grid;
          grid-template-columns: 1fr 1fr;
          background: var(--bg-primary);
        }

        /* ========== BRAND PANEL - TERMINAL STYLE ========== */
        .login-brand-panel {
          position: relative;
          background: #0d1117;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          padding: 32px;
          overflow: hidden;
        }

        /* Terminal window */
        .terminal-window {
          width: 100%;
          max-width: 480px;
          background: #161b22;
          border-radius: 12px;
          border: 1px solid #30363d;
          box-shadow: 
            0 16px 48px rgba(0, 0, 0, 0.4),
            0 0 0 1px rgba(255, 255, 255, 0.03) inset;
          overflow: hidden;
        }

        .terminal-header {
          display: flex;
          align-items: center;
          gap: 12px;
          padding: 12px 16px;
          background: #21262d;
          border-bottom: 1px solid #30363d;
        }

        .terminal-dots {
          display: flex;
          gap: 8px;
        }

        .terminal-dot {
          width: 12px;
          height: 12px;
          border-radius: 50%;
        }

        .terminal-dot-red { background: #f85149; }
        .terminal-dot-yellow { background: #d29922; }
        .terminal-dot-green { background: #3fb950; }

        .terminal-title {
          font-size: 12px;
          color: #8b949e;
          font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
        }

        .terminal-body {
          padding: 20px 16px;
          font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
          font-size: 13px;
          line-height: 1.7;
        }

        .terminal-line {
          display: flex;
          align-items: flex-start;
          gap: 16px;
        }

        .terminal-line-number {
          width: 20px;
          color: #484f58;
          text-align: right;
          flex-shrink: 0;
          user-select: none;
          font-size: 11px;
        }

        .terminal-content {
          color: #8b949e;
        }

        .terminal-comment .terminal-content {
          color: #6e7681;
          font-style: italic;
        }

        .terminal-spacer {
          height: 12px;
        }

        .terminal-prompt {
          color: #3fb950;
          font-weight: 600;
        }

        .terminal-command {
          color: #79c0ff;
          margin-left: 8px;
        }

        .terminal-flag {
          color: #d2a8ff;
          margin-left: 8px;
        }

        .terminal-arg {
          color: #a5d6ff;
          margin-left: 8px;
        }

        /* Logo section */
        .terminal-logo-section {
          margin: 4px 0;
        }

        .terminal-output {
          margin-left: 36px;
          margin-top: 8px;
        }

        .terminal-brand-row {
          display: flex;
          align-items: center;
          gap: 12px;
        }

        .terminal-brand-row svg {
          width: 36px !important;
          height: 36px !important;
          filter: drop-shadow(0 4px 12px rgba(134, 59, 255, 0.5)) !important;
        }

        .terminal-brand-text {
          display: flex;
          flex-direction: column;
        }

        .terminal-brand-name {
          font-size: 20px;
          font-weight: 700;
          color: #f0f6fc;
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
          letter-spacing: -0.5px;
        }

        .terminal-brand-version {
          font-size: 11px;
          color: #7ee787;
          font-family: 'SF Mono', monospace;
        }

        /* Code block */
        .terminal-code-block {
          background: #0d1117;
          border-radius: 6px;
          padding: 12px 16px;
          margin-top: 8px;
          border: 1px solid #21262d;
        }

        .terminal-code-line {
          display: flex;
          line-height: 1.8;
        }

        .terminal-code-indent {
          padding-left: 16px;
        }

        .terminal-key { color: #79c0ff; }
        .terminal-colon { color: #f0f6fc; margin: 0 4px; }
        .terminal-string { color: #a5d6ff; }
        .terminal-comma { color: #8b949e; }
        .terminal-bracket { color: #ffa657; }

        /* Cursor */
        .terminal-cursor-line {
          align-items: center;
        }

        .terminal-cursor {
          display: inline-block;
          width: 8px;
          height: 16px;
          background: #3fb950;
          margin-left: 4px;
          animation: terminal-blink 1s step-end infinite;
        }

        @keyframes terminal-blink {
          0%, 100% { opacity: 1; }
          50% { opacity: 0; }
        }

        /* Scan line effect */
        .terminal-scanline {
          position: absolute;
          top: 0;
          left: 0;
          right: 0;
          height: 2px;
          background: linear-gradient(
            90deg,
            transparent,
            rgba(63, 185, 80, 0.1),
            transparent
          );
          animation: terminal-scan 4s linear infinite;
          pointer-events: none;
        }

        @keyframes terminal-scan {
          0% { top: 0; opacity: 0; }
          10% { opacity: 1; }
          90% { opacity: 1; }
          100% { top: 100%; opacity: 0; }
        }

        /* ========== FORM PANEL ========== */
        .login-form-panel {
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 40px;
          background: var(--bg-primary);
        }

        .login-form-card {
          width: 100%;
          max-width: 400px;
          background: var(--bg-surface);
          border: 1px solid var(--border-subtle);
          border-radius: 16px;
          padding: 36px 32px;
          box-shadow: var(--shadow-elevated);
        }

        .login-form-header {
          text-align: center;
          margin-bottom: 24px;
        }

        .login-form-title {
          font-size: 24px;
          font-weight: 600;
          color: var(--text-primary);
          margin: 0 0 4px 0;
          letter-spacing: -0.5px;
        }

        .login-form-subtitle {
          font-size: 14px;
          color: var(--text-secondary);
          margin: 0;
        }

        /* Server connection info */
        .login-server-info {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 12px 16px;
          background: var(--bg-hover);
          border-radius: 10px;
          margin-bottom: 20px;
        }

        .login-server-status {
          display: flex;
          align-items: center;
          gap: 8px;
        }

        .login-status-dot {
          width: 8px;
          height: 8px;
          border-radius: 50%;
          background: #3fb950;
        }

        .login-status-dot.loading {
          background: #d29922;
          animation: status-pulse 1s ease-in-out infinite;
        }

        .login-status-dot.connected {
          background: #3fb950;
        }

        @keyframes status-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.4; }
        }

        .login-status-text {
          font-size: 13px;
          font-weight: 500;
          color: var(--text-secondary);
        }

        .login-server-address {
          display: flex;
          align-items: center;
          gap: 6px;
        }

        .login-address-label {
          font-size: 11px;
          font-weight: 600;
          color: var(--text-muted);
          background: var(--bg-primary);
          padding: 2px 6px;
          border-radius: 4px;
        }

        .login-address-value {
          font-size: 12px;
          color: var(--text-muted);
          font-family: 'SF Mono', 'Fira Code', monospace;
        }

        /* Input section */
        .login-form-section {
          display: flex;
          flex-direction: column;
          gap: 12px;
        }

        .login-input-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
        }

        .login-input-label {
          font-size: 13px;
          font-weight: 500;
          color: var(--text-secondary);
        }

        .login-input-status {
          font-size: 12px;
          color: #3fb950;
          font-weight: 500;
        }

        .login-token-input {
          background: var(--bg-primary) !important;
          border: 1px solid var(--border-subtle) !important;
          border-radius: 10px !important;
          font-size: 14px !important;
          transition: all 0.2s ease !important;
        }

        .login-token-input:hover {
          border-color: var(--border-medium) !important;
        }

        .login-token-input:focus,
        .login-token-input.ant-input-focused {
          border-color: #3fb950 !important;
          box-shadow: 0 0 0 3px rgba(63, 185, 80, 0.12) !important;
        }

        .login-token-input::placeholder {
          color: var(--text-muted);
        }

        .login-submit-btn {
          height: 44px !important;
          margin-top: 4px;
          background: #3fb950 !important;
          border: none !important;
          border-radius: 10px !important;
          font-weight: 600 !important;
          font-size: 14px !important;
          transition: all 0.2s ease !important;
          box-shadow: 0 2px 8px rgba(63, 185, 80, 0.15) !important;
        }

        .login-submit-btn:hover:not(:disabled) {
          background: #2ea043 !important;
          transform: translateY(-1px);
          box-shadow: 0 4px 12px rgba(63, 185, 80, 0.25) !important;
        }

        .login-submit-btn:active:not(:disabled) {
          transform: translateY(0);
          background: #238636 !important;
        }

        .login-submit-btn:disabled {
          background: var(--bg-hover) !important;
          color: var(--text-muted) !important;
          box-shadow: none !important;
        }

        /* Divider */
        .login-divider {
          display: flex;
          align-items: center;
          gap: 12px;
          margin: 20px 0;
        }

        .login-divider-line {
          flex: 1;
          height: 1px;
          background: var(--border-subtle);
        }

        .login-divider-text {
          font-size: 13px;
          color: var(--text-muted);
          font-weight: 500;
        }

        /* Alternative section */
        .login-alt-section {
          text-align: center;
        }

        .login-alt-header {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: 8px;
          margin-bottom: 8px;
        }

        .login-alt-icon {
          font-size: 16px;
        }

        .login-alt-title {
          font-size: 14px;
          font-weight: 600;
          color: var(--text-primary);
        }

        .login-alt-desc {
          font-size: 13px;
          color: var(--text-secondary);
          margin: 0 0 12px 0;
          line-height: 1.5;
        }

        .login-alt-btn {
          height: 40px !important;
          background: transparent !important;
          border: 1px solid var(--border-subtle) !important;
          border-radius: 10px !important;
          font-weight: 500 !important;
          font-size: 14px !important;
          color: var(--text-secondary) !important;
          transition: all 0.2s ease !important;
        }

        .login-alt-btn:hover:not(:disabled) {
          border-color: #3fb950 !important;
          color: #3fb950 !important;
          background: rgba(63, 185, 80, 0.08) !important;
        }

        .login-alt-btn:disabled {
          opacity: 0.5 !important;
        }

        /* Footer */
        .login-form-footer {
          margin-top: 20px;
          text-align: center;
        }

        .login-form-footer p {
          font-size: 12px;
          color: var(--text-muted);
          margin: 0;
          line-height: 1.5;
        }

        /* ========== RESPONSIVE ========== */
        @media (max-width: 900px) {
          .login-page {
            grid-template-columns: 1fr;
          }

          .login-brand-panel {
            display: none;
          }

          .login-form-panel {
            padding: 24px;
          }

          .login-form-card {
            padding: 32px 24px;
          }
        }

        /* ========== DARK MODE ========== */
        [data-theme="dark"] .login-form-panel {
          background: var(--bg-base);
        }

        [data-theme="dark"] .login-form-card {
          background: var(--bg-surface);
          border-color: var(--border-subtle);
        }
      `}</style>
    </div>
  );
};

export default Login;