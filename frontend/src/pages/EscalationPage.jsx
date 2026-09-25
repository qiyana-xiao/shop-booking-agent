import { useCallback, useEffect, useState } from 'react';
import { escalationApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import StatusBadge, { ESCALATION_STATUS } from '../components/StatusBadge.jsx';
import Modal from '../components/Modal.jsx';
import { toast } from '../components/Toast.jsx';

const FILTERS = [
  { value: '', label: '全部' },
  { value: 'OPEN', label: '待处理' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'RESOLVED', label: '已解决' },
];

export default function EscalationPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [result, setResult] = useState(null);
  const [detail, setDetail] = useState(null);
  const [resolving, setResolving] = useState(null);
  const [note, setNote] = useState('');
  const [adopting, setAdopting] = useState(null);
  const [question, setQuestion] = useState('');
  const [answer, setAnswer] = useState('');
  const [replyText, setReplyText] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setResult(await escalationApi.list(status || undefined, page));
    } catch (e) {
      toast(e.message, 'error');
    }
  }, [status, page]);

  useEffect(() => {
    load();
  }, [load]);

  // 新工单自动浮现（10s 轻轮询，挂页面期间不需要手动刷新）
  useEffect(() => {
    const timer = setInterval(load, 10000);
    return () => clearInterval(timer);
  }, [load]);

  // 工单详情打开期间轮询顾客新消息（对话窗实时性）
  useEffect(() => {
    if (!detail || detail.status === 'RESOLVED') return;
    const timer = setInterval(async () => {
      try {
        setDetail(await escalationApi.detail(detail.id));
      } catch {
        /* 轮询失败静默重试 */
      }
    }, 4000);
    return () => clearInterval(timer);
  }, [detail?.id, detail?.status]);

  const openDetail = async (id) => {
    try {
      setDetail(await escalationApi.detail(id));
      setReplyText('');
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const claim = async (id) => {
    try {
      await escalationApi.claim(id);
      toast('已接手工单', 'success');
      load();
      openDetail(id);
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  /** 店员/老板回复顾客：消息直接出现在顾客对话窗（店家气泡） */
  const doReply = async () => {
    if (!detail || busy || !replyText.trim()) return;
    setBusy(true);
    try {
      await escalationApi.reply(detail.id, replyText.trim());
      setReplyText('');
      toast('已发送给顾客，消息会直接出现在 TA 的对话窗', 'success');
      setDetail(await escalationApi.detail(detail.id));
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const doResolve = async () => {
    if (!resolving || busy) return;
    setBusy(true);
    try {
      await escalationApi.resolve(resolving.id, note);
      toast('工单已解决', 'success');
      setResolving(null);
      setNote('');
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const openAdopt = (e) => {
    setAdopting(e);
    const firstUser = (e.messages || []).find((m) => m.role === 'user');
    setQuestion(firstUser ? firstUser.content.slice(0, 120) : '');
    setAnswer('');
  };

  const doAdopt = async () => {
    if (!adopting || busy) return;
    setBusy(true);
    try {
      const res = await escalationApi.adoptAsKnowledge(adopting.id, question, answer);
      toast(`已入库为标准答案（知识库 #${res.knowledgeId}），下次 AI 就会答了`, 'success');
      setAdopting(null);
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const list = result?.list || [];
  const total = result?.total || 0;
  const size = result?.size || 20;
  const totalPages = Math.max(1, Math.ceil(total / size));

  return (
    <AdminLayout title="转人工工单">
      <p className="page-desc">
        AI 接不住的对话会带着完整会话摘要转到这里；接手后顾客不用重讲。回答过一次的问题可一键回流为标准答案，AI 越用越聪明。
      </p>

      <div className="tabs">
        {FILTERS.map((f) => (
          <button
            key={f.value}
            className={`tab ${status === f.value ? 'active' : ''}`}
            onClick={() => {
              setStatus(f.value);
              setPage(1);
            }}
          >
            {f.label}
          </button>
        ))}
      </div>

      {!result ? (
        <div className="spinner" />
      ) : list.length === 0 ? (
        <div className="card empty">
          <div className="empty-icon">🎉</div>
          没有符合条件的工单
        </div>
      ) : (
        <div className="grid">
          {list.map((e) => (
            <div key={e.id} className="card">
              <div className="row-between">
                <div className="row">
                  <StatusBadge status={e.status} map={ESCALATION_STATUS} />
                  <strong style={{ fontSize: 14 }}>{e.reason}</strong>
                </div>
                <span className="text-muted" style={{ fontSize: 12 }}>
                  {e.createdAt?.replace('T', ' ').slice(0, 16)}
                </span>
              </div>
              {e.summary && (
                <p
                  className="text-secondary mt-8"
                  style={{
                    fontSize: 13,
                    whiteSpace: 'pre-wrap',
                    maxHeight: 84,
                    overflow: 'hidden',
                  }}
                >
                  {e.summary}
                </p>
              )}
              <div className="row mt-8" style={{ gap: 8 }}>
                <button className="btn btn-outline btn-sm" onClick={() => openDetail(e.id)}>
                  查看对话
                </button>
                {e.status === 'OPEN' && (
                  <button className="btn btn-sm" onClick={() => claim(e.id)}>
                    接手处理
                  </button>
                )}
                {e.status !== 'RESOLVED' && (
                  <>
                    <button
                      className="btn btn-outline btn-sm"
                      onClick={() => {
                        setResolving(e);
                        setNote('');
                      }}
                    >
                      标记解决
                    </button>
                    <button className="btn btn-outline btn-sm" onClick={() => openAdopt(e)}>
                      采纳为标准答案
                    </button>
                  </>
                )}
                {e.status === 'RESOLVED' && e.resolutionNote && (
                  <span className="text-muted" style={{ fontSize: 12 }}>
                    处理结果：{e.resolutionNote}
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="row mt-16" style={{ justifyContent: 'center' }}>
          <button className="btn btn-outline btn-sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            上一页
          </button>
          <span className="text-muted">
            {page} / {totalPages}
          </span>
          <button
            className="btn btn-outline btn-sm"
            disabled={page >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            下一页
          </button>
        </div>
      )}

      {detail && (
        <Modal
          wide
          title={`工单 #${detail.id} · ${detail.reason}`}
          onClose={() => setDetail(null)}
          footer={
            <>
              {detail.status === 'OPEN' && (
                <button
                  className="btn"
                  onClick={() => {
                    claim(detail.id);
                    setDetail(null);
                  }}
                >
                  接手处理
                </button>
              )}
              <button className="btn btn-outline" onClick={() => setDetail(null)}>
                关闭
              </button>
            </>
          }
        >
          {detail.collectedFields && (
            <div className="notice notice-info mb-16">
              <span>📋</span>
              <span style={{ whiteSpace: 'pre-wrap' }}>{detail.collectedFields}</span>
            </div>
          )}
          {detail.summary && (
            <p className="text-secondary" style={{ whiteSpace: 'pre-wrap', fontSize: 13 }}>
              {detail.summary}
            </p>
          )}
          <div className="mt-16" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {(detail.messages || []).map((m, i) => (
              <div
                key={i}
                className={`chat-msg ${
                  m.role === 'user' ? 'user' : m.role === 'human' ? 'human' : 'assistant'
                }`}
                style={{ maxWidth: '88%' }}
              >
                <div className="chat-avatar">
                  {m.role === 'user' ? '客' : m.role === 'human' ? '店' : 'AI'}
                </div>
                <div className="chat-bubble" style={{ fontSize: 13 }}>{m.content}</div>
              </div>
            ))}
            {(detail.messages || []).length === 0 && (
              <div className="empty">工单没有关联对话消息</div>
            )}
          </div>

          {detail.status !== 'RESOLVED' && (
            <div className="mt-16" style={{ borderTop: '1px solid var(--border-light)', paddingTop: 14 }}>
              <div className="field">
                <label>回复顾客（直接出现在 TA 的对话窗，AI 不会抢答）</label>
                <textarea
                  className="textarea"
                  rows={2}
                  maxLength={500}
                  placeholder="例如：实在抱歉！周六 18:00 的包间我给您留好了～"
                  value={replyText}
                  onChange={(e) => setReplyText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      doReply();
                    }
                  }}
                />
              </div>
              <div className="row" style={{ justifyContent: 'flex-end', gap: 8 }}>
                <span className="text-muted" style={{ fontSize: 12 }}>
                  处理完记得点「标记解决」，AI 小助手会自动恢复接待
                </span>
                <button
                  className="btn"
                  onClick={doReply}
                  disabled={busy || !replyText.trim()}
                >
                  {busy ? '发送中…' : '发送回复'}
                </button>
              </div>
            </div>
          )}
        </Modal>
      )}

      {resolving && (
        <Modal
          title="标记解决"
          onClose={() => setResolving(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setResolving(null)}>
                取消
              </button>
              <button className="btn" onClick={doResolve} disabled={busy}>
                {busy ? '提交中…' : '确认解决'}
              </button>
            </>
          }
        >
          <div className="field">
            <label>处理说明（可选）</label>
            <textarea
              className="textarea"
              placeholder="例如：已电话联系顾客确认周六 18:00"
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </div>
        </Modal>
      )}

      {adopting && (
        <Modal
          title="采纳为标准答案"
          onClose={() => setAdopting(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setAdopting(null)}>
                取消
              </button>
              <button
                className="btn"
                onClick={doAdopt}
                disabled={busy || !question.trim() || !answer.trim()}
              >
                {busy ? '提交中…' : '入库，让 AI 学会'}
              </button>
            </>
          }
        >
          <div className="notice notice-info mb-16">
            <span>💡</span>
            <span>这条记录会写入知识库，AI 之后遇到同样问题直接按标准答案回复。</span>
          </div>
          <div className="field">
            <label>顾客问题</label>
            <textarea className="textarea" value={question} onChange={(e) => setQuestion(e.target.value)} />
          </div>
          <div className="field">
            <label>标准答案（您这次是怎么回答的）</label>
            <textarea
              className="textarea"
              placeholder="例如：周六晚上 18:00-20:00 是高峰，建议 17:00 前到店"
              value={answer}
              onChange={(e) => setAnswer(e.target.value)}
            />
          </div>
        </Modal>
      )}
    </AdminLayout>
  );
}
