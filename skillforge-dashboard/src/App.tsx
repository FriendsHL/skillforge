import { BrowserRouter, Routes, Route } from 'react-router-dom';
import AppLayout from './components/Layout';
import Dashboard from './pages/Dashboard';
import AgentList from './pages/AgentList';
import SkillList from './pages/SkillList';
import SessionList from './pages/SessionList';
import Chat from './pages/Chat';
import MemoryList from './pages/MemoryList';
import ModelUsage from './pages/ModelUsage';
import Traces from './pages/Traces';
import Teams from './pages/Teams';
import Eval from './pages/Eval';
import Login from './pages/Login';
import { ErrorBoundary } from './components/ErrorBoundary';
import { AuthProvider } from './contexts/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <AppLayout />
              </ProtectedRoute>
            }
          >
            <Route index element={<ErrorBoundary context="Dashboard"><Dashboard /></ErrorBoundary>} />
            <Route path="agents" element={<ErrorBoundary context="Agents"><AgentList /></ErrorBoundary>} />
            <Route path="skills" element={<ErrorBoundary context="Skills"><SkillList /></ErrorBoundary>} />
            <Route path="sessions" element={<ErrorBoundary context="Sessions"><SessionList /></ErrorBoundary>} />
            <Route path="memories" element={<ErrorBoundary context="Memories"><MemoryList /></ErrorBoundary>} />
            <Route path="usage" element={<ErrorBoundary context="Model Usage"><ModelUsage /></ErrorBoundary>} />
            <Route path="traces" element={<ErrorBoundary context="Traces"><Traces /></ErrorBoundary>} />
            <Route path="teams" element={<ErrorBoundary context="Teams"><Teams /></ErrorBoundary>} />
            <Route path="eval" element={<ErrorBoundary context="Eval"><Eval /></ErrorBoundary>} />
            <Route path="chat" element={<ErrorBoundary context="Chat"><Chat /></ErrorBoundary>} />
            <Route path="chat/:sessionId" element={<ErrorBoundary context="Chat"><Chat /></ErrorBoundary>} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;
