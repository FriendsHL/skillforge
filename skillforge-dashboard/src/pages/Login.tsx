import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Input, Button, message } from 'antd';
import { useAuth } from '../contexts/AuthContext';
import api from '../api';

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
    <div
      style={{
        minHeight: '100vh',
        background: 'var(--bg-primary)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <div
        style={{
          background: 'var(--bg-surface)',
          border: '1px solid var(--border-subtle)',
          borderRadius: 12,
          padding: '40px 48px',
          width: 400,
          boxShadow: 'var(--shadow-elevated)',
        }}
      >
        <h1
          style={{
            color: 'var(--text-primary)',
            marginBottom: 4,
            fontSize: 24,
            fontWeight: 600,
            letterSpacing: '-0.5px',
          }}
        >
          SkillForge
        </h1>
        <p
          style={{
            color: 'var(--text-secondary)',
            marginBottom: 24,
            fontSize: 14,
            marginTop: 0,
          }}
        >
          {fetching ? '正在获取 Access Token...' : '粘贴你的 Access Token 进入'}
        </p>
        <Input
          size="large"
          placeholder={fetching ? '正在获取...' : '粘贴 Access Token'}
          value={tokenInput}
          onChange={(e) => setTokenInput(e.target.value)}
          onPressEnter={handleLogin}
          disabled={fetching}
          style={{
            marginBottom: 16,
            background: 'var(--bg-primary)',
            borderColor: 'var(--border-subtle)',
            color: 'var(--text-primary)',
          }}
        />
        <Button
          type="primary"
          size="large"
          block
          loading={loading}
          disabled={fetching || !tokenInput.trim()}
          onClick={handleLogin}
          style={{
            background: 'var(--accent-primary)',
            borderColor: 'var(--accent-primary)',
            fontWeight: 500,
            letterSpacing: '0.02em',
          }}
        >
          进入 →
        </Button>
      </div>
    </div>
  );
};

export default Login;
