import { NavLink, useNavigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { getUser, clearAuth, authApi, setupApi, getSessionKey } from '../api.js';
import { toast } from './Toast.jsx';

const NAV_GROUPS = (role) => {
  const groups = [
    { label: '经营', items: [
      { to: '/admin', label: '数据看板', icon: '📊', end: true },
      { to: '/admin/bookings', label: '今日预约', icon: '📅' },
      { to: '/admin/escalations', label: '转人工工单', icon: '🎧' },
    ] },
    { label: '档期', items: [
      { to: '/admin/slots', label: '档期日历', icon: '🗓️' },
    ] },
  ];
  if (role === 'owner') {
    groups.push({ label: '配置（仅老板）', items: [
      { to: '/admin/items', label: '服务项目', icon: '🍽️' },
      { to: '/admin/knowledge', label: '知识库', icon: '📚' },
      { to: '/admin/settings', label: '店铺设置', icon: '⚙️' },
      { to: '/admin/export', label: '数据导出', icon: '📤' },
    ] });
  }
  return groups;
};

export default function AdminLayout({ title, children }) {
  const [user, setUser] = useState(getUser());
  const [setupHint, setSetupHint] = useState(null);
  const navigate = useNavigate();

  useEffect(() => {
    if (!user) {
      navigate('/login');
      return;
    }
    setupApi
      .status()
      .then((s) => {
        if (!s.setupCompleted && user.role === 'owner') {
          setSetupHint(s.shopName || '您的店');
        }
      })
      .catch(() => {});
  }, [user?.id]);

  const logout = async () => {
    try {
      await authApi.logout();
    } catch {
      /* 黑名单写失败也照样清理本地会话 */
    }
    clearAuth();
    toast('已退出登录');
    navigate('/login');
  };

  if (!user) return null;
  const groups = NAV_GROUPS(user.role);
  const roleLabel = { owner: '老板', staff: '店员', customer: '顾客' }[user.role] || user.role;

  return (
    <div className="admin-layout">
      <aside className="admin-sidebar">
        <div className="brand">
          <div className="brand-logo">约</div>
          <span>店小约</span>
        </div>
        <nav className="admin-nav">
          {groups.map((g) => (
            <div key={g.label}>
              <div className="admin-nav-group">{g.label}</div>
              {g.items.map((item) => (
                <NavLink key={item.to} to={item.to} end={item.end ?? false}>
                  <span style={{ width: 18, textAlign: 'center' }}>{item.icon}</span>
                  <span>{item.label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="admin-sidebar-footer">
          会话 {getSessionKey().slice(0, 10)}…
        </div>
      </aside>
      <main className="admin-main">
        <div className="admin-header">
          <h1>{title}</h1>
          <div className="row">
            <span className="badge badge-primary">{user.username} · {roleLabel}</span>
            {user.role !== 'customer' && (
              <button
                className="btn btn-outline btn-sm"
                onClick={() => navigate('/')}
                title="以顾客视角体验 AI 对话（您自己的独立测试会话，不会看到顾客的聊天记录）"
              >
                顾客视图（预览）
              </button>
            )}
            <button className="btn btn-outline btn-sm" onClick={logout}>
              退出
            </button>
          </div>
        </div>
        {setupHint && (
          <div className="notice notice-warning mb-16">
            <span>⚠️</span>
            <span>
              您还没完成开店配置（{setupHint}），AI 客服暂无法接待顾客。
              <a href="/admin/wizard">点这里继续开店向导 →</a>
            </span>
          </div>
        )}
        {children}
      </main>
    </div>
  );
}
