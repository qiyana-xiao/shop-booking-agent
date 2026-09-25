import { Routes, Route, Navigate } from 'react-router-dom';
import { getToken, getUser } from './api.js';

import ChatPage from './pages/ChatPage.jsx';
import MyBookingsPage from './pages/MyBookingsPage.jsx';
import LoginPage from './pages/LoginPage.jsx';

import DashboardPage from './pages/DashboardPage.jsx';
import BookingPage from './pages/BookingPage.jsx';
import EscalationPage from './pages/EscalationPage.jsx';
import SlotCalendarPage from './pages/SlotCalendarPage.jsx';
import SetupWizardPage from './pages/SetupWizardPage.jsx';
import ShopSettingPage from './pages/ShopSettingPage.jsx';
import ServiceItemPage from './pages/ServiceItemPage.jsx';
import KnowledgePage from './pages/KnowledgePage.jsx';
import ExportPage from './pages/ExportPage.jsx';

/**
 * 前端路由守卫（与后端 SecurityConfig 同步）：
 * 顾客无后台入口，店员无配置页，老板全通。
 * 拦截以后端为准——前端只做体验层的引导。
 */
function RequireRole({ roles, children }) {
  const token = getToken();
  const user = getUser();
  if (!token || !user) {
    return <Navigate to="/login" replace />;
  }
  if (roles && !roles.includes(user.role)) {
    return <Navigate to="/" replace />;
  }
  return children;
}

export default function App() {
  return (
    <Routes>
      {/* 顾客侧：免登录可对话 */}
      <Route path="/" element={<ChatPage />} />
      <Route path="/my" element={<MyBookingsPage />} />
      <Route path="/login" element={<LoginPage />} />

      {/* 老板 + 店员 */}
      <Route
        path="/admin"
        element={
          <RequireRole roles={['owner', 'staff']}>
            <DashboardPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/bookings"
        element={
          <RequireRole roles={['owner', 'staff']}>
            <BookingPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/escalations"
        element={
          <RequireRole roles={['owner', 'staff']}>
            <EscalationPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/slots"
        element={
          <RequireRole roles={['owner', 'staff']}>
            <SlotCalendarPage />
          </RequireRole>
        }
      />

      {/* 仅老板 */}
      <Route
        path="/admin/wizard"
        element={
          <RequireRole roles={['owner']}>
            <SetupWizardPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/settings"
        element={
          <RequireRole roles={['owner']}>
            <ShopSettingPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/items"
        element={
          <RequireRole roles={['owner']}>
            <ServiceItemPage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/knowledge"
        element={
          <RequireRole roles={['owner']}>
            <KnowledgePage />
          </RequireRole>
        }
      />
      <Route
        path="/admin/export"
        element={
          <RequireRole roles={['owner']}>
            <ExportPage />
          </RequireRole>
        }
      />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
