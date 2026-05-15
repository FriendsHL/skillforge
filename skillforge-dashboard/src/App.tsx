import { BrowserRouter, Routes, Route, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import AppLayout from './components/Layout';
import Dashboard from './pages/Dashboard';
import AgentList from './pages/AgentList';
import SkillList from './pages/SkillList';
import SkillDrafts from './pages/SkillDrafts';
import ToolsMcp from './pages/ToolsMcp';
import SessionList from './pages/SessionList';
import SessionDetail from './pages/SessionDetail';
import Chat from './pages/Chat';
import MemoryList from './pages/MemoryList';
import ModelUsage from './pages/ModelUsage';
import Traces from './pages/Traces';
import Teams from './pages/Teams';
import Eval from './pages/Eval';
import HookMethods from './pages/HookMethods';
import Channels from './pages/Channels';
import Schedules from './pages/Schedules';
import Insights from './pages/Insights';
import OptimizationEvents from './pages/OptimizationEvents';
import Login from './pages/Login';
import { ErrorBoundary } from './components/ErrorBoundary';
import { AuthProvider } from './contexts/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import { setApiNavigate } from './api';

/** Connects React Router's navigate to the API layer for client-side 401 redirects */
function NavigateInitializer() {
  const navigate = useNavigate();
  useEffect(() => {
    setApiNavigate((path) => navigate(path, { replace: true }));
  }, [navigate]);
  return null;
}

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <NavigateInitializer />
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
            <Route path="skill-drafts" element={<ErrorBoundary context="Skill Drafts"><SkillDrafts /></ErrorBoundary>} />
            <Route path="tools" element={<ErrorBoundary context="Tools"><ToolsMcp /></ErrorBoundary>} />
            <Route path="sessions" element={<ErrorBoundary context="Sessions"><SessionList /></ErrorBoundary>} />
            <Route path="sessions/:id" element={<ErrorBoundary context="SessionDetail"><SessionDetail /></ErrorBoundary>} />
            <Route path="memories" element={<ErrorBoundary context="Memories"><MemoryList /></ErrorBoundary>} />
            <Route path="usage" element={<ErrorBoundary context="Model Usage"><ModelUsage /></ErrorBoundary>} />
            <Route path="traces" element={<ErrorBoundary context="Traces"><Traces /></ErrorBoundary>} />
            <Route path="teams" element={<ErrorBoundary context="Teams"><Teams /></ErrorBoundary>} />
            <Route path="eval" element={<ErrorBoundary context="Eval"><Eval /></ErrorBoundary>} />
            <Route path="hooks" element={<ErrorBoundary context="Hook Methods"><HookMethods /></ErrorBoundary>} />
            <Route path="channels" element={<ErrorBoundary context="Channels"><Channels /></ErrorBoundary>} />
            <Route path="schedules" element={<ErrorBoundary context="Schedules"><Schedules /></ErrorBoundary>} />
            <Route path="insights/patterns" element={<ErrorBoundary context="Insights"><Insights /></ErrorBoundary>} />
            <Route path="insights/optimization-events" element={<ErrorBoundary context="OptimizationEvents"><OptimizationEvents /></ErrorBoundary>} />
            <Route path="chat/:sessionId?" element={<ErrorBoundary context="Chat"><Chat /></ErrorBoundary>} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;
