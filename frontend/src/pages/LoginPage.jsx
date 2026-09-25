import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi, setupApi, saveAuth } from '../api.js';
import { toast } from '../components/Toast.jsx';

/** 登录后按角色分流：老板未完成开店 → 向导；顾客 → 对话窗 */
function homeOf(user, setupCompleted) {
  if (user.role === 'customer') return '/';
  if (user.role === 'owner' && !setupCompleted) return '/admin/wizard';
  return '/admin';
}

export default function LoginPage() {
  const [mode, setMode] = useState('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState('customer');
  const [setup, setSetup] = useState(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    setupApi
      .status()
      .then(setSetup)
      .catch(() => setSetup({}));
  }, []);

  const submit = async (e) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    try {
      const res =
        mode === 'login'
          ? await authApi.login({ username, password })
          : await authApi.register({ username, password, role });
      saveAuth(res.token, res.user);
      toast(mode === 'login' ? `欢迎回来，${res.user.username}` : '注册成功');
      navigate(homeOf(res.user, setup?.setupCompleted));
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const ownerAvailable = setup ? setup.hasOwner === false : false;

  return (
    <div className="page" style={{ maxWidth: 420, paddingTop: 60 }}>
      <div className="card">
        <div className="text-center mb-16">
          <div className="brand" style={{ justifyContent: 'center', fontSize: 22 }}>
            <div className="brand-logo" style={{ width: 38, height: 38, fontSize: 18 }}>约</div>
            店小约
          </div>
          <p className="text-muted" style={{ margin: '6px 0 0' }}>
            小店的 AI 预约客服 · 顾客聊着天就把位子订了
          </p>
        </div>

        <div className="tabs">
          <button className={`tab ${mode === 'login' ? 'active' : ''}`} onClick={() => setMode('login')}>
            登录
          </button>
          <button className={`tab ${mode === 'register' ? 'active' : ''}`} onClick={() => setMode('register')}>
            注册
          </button>
        </div>

        <form onSubmit={submit}>
          <div className="field">
            <label>用户名</label>
            <input
              className="input"
              placeholder="2-20 位中文、字母、数字或下划线"
              value={username}
              autoComplete="username"
              onChange={(e) => setUsername(e.target.value)}
            />
          </div>
          <div className="field">
            <label>密码</label>
            <input
              className="input"
              type="password"
              placeholder="至少 6 位"
              value={password}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          {mode === 'register' && (
            <div className="field">
              <label>注册角色</label>
              <div className="row wrap" style={{ gap: 8 }}>
                {[
                  { value: 'customer', label: '顾客 · 订位查位' },
                  { value: 'staff', label: '店员 · 看板核销' },
                ].map((r) => (
                  <label key={r.value} className="chip" style={{ cursor: 'pointer', borderColor: role === r.value ? 'var(--primary)' : undefined }}>
                    <input
                      type="radio"
                      name="role"
                      style={{ marginRight: 6 }}
                      checked={role === r.value}
                      onChange={() => setRole(r.value)}
                    />
                    {r.label}
                  </label>
                ))}
                {ownerAvailable && (
                  <label className="chip" style={{ cursor: 'pointer', borderColor: role === 'owner' ? 'var(--primary)' : undefined }}>
                    <input
                      type="radio"
                      name="role"
                      style={{ marginRight: 6 }}
                      checked={role === 'owner'}
                      onChange={() => setRole('owner')}
                    />
                    老板 · 首次开店
                  </label>
                )}
              </div>
              {ownerAvailable && (
                <p className="text-muted" style={{ fontSize: 12, margin: '4px 0 0' }}>
                  当前系统还没有老板账号，首位注册者将成为老板（之后老板只能由数据库授权升级）。
                </p>
              )}
              {!ownerAvailable && role === 'owner' && setRole('customer')}
            </div>
          )}

          <button className="btn btn-block mt-8" type="submit" disabled={busy || !username || !password}>
            {busy ? '请稍候…' : mode === 'login' ? '登录' : '注册并进入'}
          </button>
        </form>

        <p className="text-muted text-center mt-16" style={{ fontSize: 12 }}>
          顾客也可以不注册，
          <Link to="/">直接和小助手对话订位 →</Link>
        </p>
      </div>
    </div>
  );
}
