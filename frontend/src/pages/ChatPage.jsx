import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { chatApi, setupApi, authApi, getUser, clearAuth } from '../api.js';
import BookingCard from '../components/BookingCard.jsx';
import { toast } from '../components/Toast.jsx';

const QUICK_CHIPS = [
  '明天晚上 4 个人有位置吗？',
  '你们几点营业？',
  '门口可以停车吗？',
  '帮我取消预约',
];

const BOOKING_TOOLS = ['hold_slot', 'confirm_booking', 'reschedule_booking'];

/** 历史消息 → 时间线（工具轨迹属于内部执行细节，不进对话界面） */
function buildTimeline(history) {
  return (history.messages || [])
    .filter((m) => m.role !== 'tool')
    .map((m) => ({ role: m.role, content: m.content }));
}

function TopBar() {
  const [user, setUser] = useState(getUser());
  const navigate = useNavigate();

  const logout = async () => {
    try {
      await authApi.logout();
    } catch {
      /* 本地会话照常清理 */
    }
    clearAuth();
    setUser(null);
    toast('已退出登录');
  };

  return (
    <header className="topnav">
      <div className="topnav-inner">
        <Link to="/" className="brand">
          <div className="brand-logo">约</div>
          店小约
        </Link>
        <nav className="topnav-links">
          <Link to="/" className="active">智能对话</Link>
          <Link to="/my">我的预约</Link>
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
  );
}

function PreparingView({ status }) {
  const user = getUser();
  const isOwner = user?.role === 'owner';

  return (
    <div className="page" style={{ maxWidth: 640, textAlign: 'center', paddingTop: 80 }}>
      <div className="card">
        <div style={{ fontSize: 48 }}>🏪</div>
        <h2 style={{ margin: '12px 0 6px' }}>
          {status.shopExists ? `${status.shopName} 正在准备中` : '欢迎使用店小约'}
        </h2>
        <p className="text-secondary">
          {status.shopExists
            ? '店家还没有完成开店配置，暂时无法接待预约，请稍后再来看看～'
            : '店小约是给小店的 AI 预约客服：顾客像聊天一样订位，老板一句话配置好全部档期。'}
        </p>
        {!status.hasOwner && !user && (
          <div className="mt-16">
            <Link to="/login" className="btn">
              我是店主，注册并开店
            </Link>
            <p className="text-muted mt-8">
              首次部署时第一位注册者可创建老板账号，之后的注册仅支持顾客/店员角色
            </p>
          </div>
        )}
        {isOwner && (
          <div className="mt-16">
            <Link to="/admin/wizard" className="btn">
              继续完成开店向导（4 步，约 3 分钟）
            </Link>
          </div>
        )}
      </div>
    </div>
  );
}

export default function ChatPage() {
  const [status, setStatus] = useState(null);
  const [statusError, setStatusError] = useState(false);
  const [timeline, setTimeline] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [humanMode, setHumanMode] = useState(false);
  const messagesRef = useRef(null);
  const humanSeenRef = useRef(0);

  const user = getUser();
  const isStaffView = user?.role === 'owner' || user?.role === 'staff';

  const ready = status?.setupCompleted === true;

  useEffect(() => {
    setupApi
      .status()
      .then(setStatus)
      .catch(() => setStatusError(true));
  }, []);

  useEffect(() => {
    if (!ready) return;
    chatApi
      .history()
      .then((h) => {
        setTimeline(buildTimeline(h));
        setHumanMode(h.humanMode === true);
        humanSeenRef.current = (h.messages || []).filter((m) => m.role === 'human').length;
        if (h.conversationId && (h.messages || []).length === 0) {
          setTimeline([
            {
              role: 'assistant',
              content:
                '您好，欢迎光临！我是店里的 AI 预约小助手 🍽️\n可以直接告诉我用餐时间、人数，我帮您查位订位；\n也可以问我营业时间、停车、包间这些小问题～',
              trace: [],
            },
          ]);
        }
      })
      .catch(() => {
        toast('历史会话加载失败', 'error');
      });
  }, [ready]);

  // 人工接管期间轮询：店家的回复直达对话窗，工单解决后 AI 恢复
  useEffect(() => {
    if (!humanMode) return;
    const timer = setInterval(async () => {
      try {
        const h = await chatApi.history();
        const humans = (h.messages || []).filter((m) => m.role === 'human');
        if (humans.length > humanSeenRef.current) {
          const fresh = humans.slice(humanSeenRef.current);
          humanSeenRef.current = humans.length;
          setTimeline((prev) => [
            ...prev,
            ...fresh.map((m) => ({ role: 'human', content: m.content })),
          ]);
        }
        if (h.humanMode === false) {
          setHumanMode(false);
          setTimeline((prev) => [
            ...prev,
            { role: 'system', content: '店家已处理完毕，AI 小助手恢复服务，您可以直接对话啦～' },
          ]);
        }
      } catch {
        /* 轮询失败不打扰顾客，下个周期再试 */
      }
    }, 4000);
    return () => clearInterval(timer);
  }, [humanMode]);

  useEffect(() => {
    messagesRef.current?.scrollTo({
      top: messagesRef.current.scrollHeight,
      behavior: 'smooth',
    });
  }, [timeline, sending]);

  const send = async (text) => {
    const message = (text ?? input).trim();
    if (!message || sending) return;
    setInput('');
    setTimeline((prev) => [...prev, { role: 'user', content: message }]);
    setSending(true);
    try {
      const resp = await chatApi.send(message);
      setHumanMode(!!resp.humanMode);
      if (resp.humanMode) {
        // 人工接管中：消息已转给店家，这里只给一条轻量回执（连续发送不重复刷屏）
        setTimeline((prev) => {
          const last = prev[prev.length - 1];
          if (last?.role === 'system' && last.content === resp.reply) return prev;
          return [...prev, { role: 'system', content: resp.reply }];
        });
        return;
      }
      const trace = resp.trace || [];
      const showCard =
        resp.booking && trace.some((s) => BOOKING_TOOLS.includes(s.tool));
      setTimeline((prev) => [
        ...prev,
        {
          role: 'assistant',
          content: resp.reply,
          trace,
          booking: showCard ? resp.booking : null,
          degraded: resp.degraded,
          escalated: resp.escalated,
        },
      ]);
    } catch (e) {
      setTimeline((prev) => [
        ...prev,
        { role: 'assistant', content: `出错了：${e.message}`, trace: [], error: true },
      ]);
    } finally {
      setSending(false);
    }
  };

  if (statusError) {
    return (
      <>
        <TopBar />
        <div className="page" style={{ paddingTop: 80 }}>
          <div className="empty">
            <div className="empty-icon">🔌</div>
            连不上服务器，请确认后端已启动（localhost:8080）
          </div>
        </div>
      </>
    );
  }

  if (!status) {
    return (
      <>
        <TopBar />
        <div className="spinner" />
      </>
    );
  }

  if (!ready) {
    return (
      <>
        <TopBar />
        <PreparingView status={status} />
      </>
    );
  }

  return (
    <>
      <TopBar />
      <div className="chat-shell">
        {isStaffView && (
          <div className="notice notice-info" style={{ margin: '12px 12px 0' }}>
            <span>👓</span>
            <span>
              店家预览模式：这是您自己的测试会话，与顾客会话相互隔离、互不可见。
              顾客发来的转人工消息请到{' '}
              <Link to="/admin/escalations" style={{ textDecoration: 'underline' }}>
                后台 · 转人工工单
              </Link>{' '}
              中回复。
            </span>
          </div>
        )}
        <div className="chat-shop-bar">
          <span className="dot" style={humanMode ? { background: 'var(--accent)', boxShadow: '0 0 0 3px var(--accent-bg)' } : undefined} />
          <div className="flex-1">
            <strong>{status.shopName}</strong>
            <span className="text-muted" style={{ marginLeft: 8, fontSize: 12 }}>
              {humanMode
                ? '店家人工接待中 · 您的消息会直接转给店家'
                : isStaffView
                  ? 'AI 预约小助手 · 店家预览会话'
                  : 'AI 预约小助手 · 已就绪'}
            </span>
          </div>
          {humanMode && (
            <span className="badge badge-warning">人工客服接待中</span>
          )}
          {humanMode && status.shopPhone && (
            <span className="text-muted" style={{ fontSize: 12 }}>
              急事可电{' '}
              <a href={`tel:${status.shopPhone}`} style={{ textDecoration: 'underline' }}>
                {status.shopPhone}
              </a>
            </span>
          )}
          {status.deepSeekConfigured === false && (
            <span className="badge badge-warning" title="AI 服务暂不可用，将走 FAQ 兜底并转人工">
              AI 降级中
            </span>
          )}
          <Link to="/my" className="btn btn-outline btn-sm">
            我的预约
          </Link>
        </div>

        <div className="chat-messages" ref={messagesRef}>
          {timeline.length === 0 && (
            <div className="chat-msg assistant">
              <div className="chat-avatar">约</div>
              <div className="chat-bubble">
                您好，欢迎光临！我是店里的 AI 预约小助手 🍽️
                <br />
                直接告诉我用餐时间、人数，我帮您查位订位～
              </div>
            </div>
          )}
          {timeline.map((m, i) =>
            m.role === 'user' ? (
              <div key={i} className="chat-msg user">
                <div className="chat-avatar">我</div>
                <div className="chat-bubble">{m.content}</div>
              </div>
            ) : m.role === 'human' ? (
              <div key={i} className="chat-msg human">
                <div className="chat-avatar">店</div>
                <div className="chat-bubble">{m.content}</div>
              </div>
            ) : m.role === 'system' ? (
              <div key={i} className="chat-system-note">
                {m.content}
              </div>
            ) : (
              <div key={i} className="chat-msg assistant">
                <div className="chat-avatar">约</div>
                <div style={{ minWidth: 0 }}>
                  <div className="chat-bubble" style={m.error ? { background: 'var(--danger-bg)', color: '#991b1b' } : undefined}>
                    {m.degraded && (
                      <span className="badge badge-warning" style={{ marginBottom: 6 }}>
                        AI 服务降级 · 已转人工
                      </span>
                    )}
                    {!m.degraded && m.escalated && (
                      <span className="badge badge-info" style={{ marginBottom: 6 }}>
                        已转人工，店家会尽快联系您
                      </span>
                    )}
                    {m.content}
                  </div>
                  {m.booking && <BookingCard booking={m.booking} />}
                </div>
              </div>
            )
          )}
          {sending && (
            <div className="chat-msg assistant">
              <div className="chat-avatar">约</div>
              <div className="chat-bubble">
                <span className="chat-typing">
                  <span />
                  <span />
                  <span />
                </span>
              </div>
            </div>
          )}
        </div>

        <div className="quick-chips">
          {QUICK_CHIPS.map((c) => (
            <button key={c} className="chip" onClick={() => send(c)} disabled={sending}>
              {c}
            </button>
          ))}
        </div>

        <div className="chat-input-bar">
          <textarea
            className="textarea"
            placeholder="例如：明天晚上 6 点，4 个人，有小包间吗？"
            value={input}
            rows={1}
            maxLength={500}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
          />
          <button className="btn" onClick={() => send()} disabled={sending || !input.trim()}>
            发送
          </button>
        </div>
      </div>
    </>
  );
}
