import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { myBookingApi, authApi, getUser, clearAuth } from '../api.js';
import BookingCard from '../components/BookingCard.jsx';
import Modal from '../components/Modal.jsx';
import { toast } from '../components/Toast.jsx';

export default function MyBookingsPage() {
  const [bookings, setBookings] = useState(null);
  const [cancelling, setCancelling] = useState(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const user = getUser();

  const load = async () => {
    try {
      const list = await myBookingApi.list();
      setBookings(list);
    } catch (e) {
      toast(e.message, 'error');
      setBookings([]);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const doCancel = async () => {
    if (!cancelling || busy) return;
    setBusy(true);
    try {
      await myBookingApi.cancel(cancelling.bookingNo, reason || '顾客主动取消');
      toast('已取消预约', 'success');
      setCancelling(null);
      setReason('');
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const logout = async () => {
    try {
      await authApi.logout();
    } catch {
      /* 照常清理本地会话 */
    }
    clearAuth();
    navigate('/login');
  };

  const activeCount = (bookings || []).filter(
    (b) => ['HELD', 'CONFIRMED', 'CHECKED_IN'].includes(b.status)
  ).length;

  return (
    <>
      <header className="topnav">
        <div className="topnav-inner">
          <Link to="/" className="brand">
            <div className="brand-logo">约</div>
            店小约
          </Link>
          <nav className="topnav-links">
            <Link to="/">智能对话</Link>
            <Link to="/my" className="active">我的预约</Link>
            {user ? (
              <>
                {(user.role === 'owner' || user.role === 'staff') && (
                  <Link to="/admin">进入后台</Link>
                )}
                <span className="badge badge-primary">{user.username}</span>
                <button className="btn btn-outline btn-sm" onClick={logout}>
                  退出
                </button>
              </>
            ) : (
              <Link to="/login">登录 / 注册</Link>
            )}
          </nav>
        </div>
      </header>

      <div className="page" style={{ maxWidth: 760 }}>
        <h1 className="page-title">我的预约</h1>
        <p className="page-desc">
          {user
            ? '按登录账号归属，换设备登录也能看到全部历史预约'
            : '以当前浏览器会话为准，登录后可跨设备查看全部预约'}
          {activeCount > 0 ? `，有 ${activeCount} 个生效中的预约` : ''}。
          预约的改期、加人等操作可直接在对话里告诉 AI 小助手。
        </p>

        {bookings === null ? (
          <div className="spinner" />
        ) : bookings.length === 0 ? (
          <div className="card empty">
            <div className="empty-icon">🪑</div>
            还没有预约记录
            <div className="mt-16">
              <Link to="/" className="btn">
                去和小助手聊聊，订个位子
              </Link>
            </div>
          </div>
        ) : (
          <div className="grid grid-2">
            {bookings.map((b) => (
              <div key={b.bookingNo}>
                <BookingCard booking={b} />
                {['HELD', 'CONFIRMED'].includes(b.status) && (
                  <div className="row mt-8" style={{ gap: 8 }}>
                    <button
                      className="btn btn-danger btn-sm"
                      onClick={() => setCancelling(b)}
                    >
                      取消预约
                    </button>
                    <span className="text-muted" style={{ fontSize: 12 }}>
                      改期请到
                      <Link to="/"> 对话窗口 </Link>
                      告诉小助手
                    </span>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {cancelling && (
        <Modal
          title="取消预约"
          onClose={() => setCancelling(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setCancelling(null)}>
                再想想
              </button>
              <button className="btn btn-danger" onClick={doCancel} disabled={busy}>
                {busy ? '取消中…' : '确认取消'}
              </button>
            </>
          }
        >
          <p>
            即将取消 <strong>{cancelling.serviceName}</strong>（{cancelling.date}{' '}
            {cancelling.startTime?.slice(0, 5)}，{cancelling.partySize} 人）的预约，
            座位将立即释放给其他顾客。
          </p>
          <div className="field mt-16">
            <label>取消原因（可选，会记录给店家）</label>
            <input
              className="input"
              placeholder="例如：临时有事"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
        </Modal>
      )}
    </>
  );
}
