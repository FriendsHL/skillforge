import React, { createContext, useContext, useState } from 'react';

interface AuthContextValue {
  token: string | null;
  userId: number;
  login: (token: string) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue>({
  token: null,
  userId: 1,
  login: () => {},
  logout: () => {},
});

export const useAuth = () => useContext(AuthContext);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('sf_token'));
  const [userId] = useState<number>(1);

  const login = (t: string) => {
    localStorage.setItem('sf_token', t);
    setToken(t);
  };

  const logout = () => {
    localStorage.removeItem('sf_token');
    setToken(null);
  };

  return (
    <AuthContext.Provider value={{ token, userId, login, logout }}>{children}</AuthContext.Provider>
  );
};
