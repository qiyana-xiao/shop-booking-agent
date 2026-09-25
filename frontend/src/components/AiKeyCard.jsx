import { useEffect, useState } from 'react';
import { aiKeyApi } from '../api.js';
import { toast } from './Toast.jsx';

const SOURCE_LABELS = { database: '界面配置', env: '配置文件', none: '未配置' };

/** AI 客服密钥设置卡片（店铺设置页，仅老板可见）：密钥加密落库，保存后立即生效 */
export default function AiKeyCard() {
  const [status, setStatus] = useState(null);
  const [keyInput, setKeyInput] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    aiKeyApi.status().then(setStatus).catch(() => {});
  }, []);

  const save = async () => {
    const value = keyInput.trim();
    if (!value) {
      toast('请先填写密钥', 'error');
      return;
    }
    if (busy) return;
    setBusy(true);
    try {
      setStatus(await aiKeyApi.save(value));
      setKeyInput('');
      toast('密钥已加密保存，立即生效', 'success');
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const clear = async () => {
    if (!window.confirm('确定清除界面配置的密钥吗？清除后 AI 客服将回退到配置文件中的密钥（如有）。')) return;
    if (busy) return;
    setBusy(true);
    try {
      setStatus(await aiKeyApi.clear());
      setKeyInput('');
      toast('已清除', 'success');
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card" style={{ maxWidth: 860, marginTop: 24 }}>
      <h3 style={{ marginTop: 0, display: 'flex', alignItems: 'center', gap: 10 }}>
        AI 客服设置
        <span className={`badge ${status?.configured ? 'badge-success' : 'badge-warning'}`}>
          {status == null ? '检测中…' : status.configured ? '已配置' : '未配置'}
        </span>
      </h3>
      <p className="text-muted" style={{ fontSize: 13, lineHeight: 1.7 }}>
        填写 DeepSeek API Key，用于店小约 AI 客服自动应答顾客。密钥经 AES-GCM 加密后保存到数据库，
        不会以明文出现在任何文件或接口中，保存后立即生效、无需重启。
        {status?.configured && status.masked
          ? ` 当前密钥 ${status.masked}（${SOURCE_LABELS[status.source] || status.source}）。`
          : ''}
      </p>
      <div className="row">
        <input
          className="input"
          style={{ maxWidth: 420 }}
          type="password"
          value={keyInput}
          onChange={(e) => setKeyInput(e.target.value)}
          placeholder="sk-…"
          autoComplete="new-password"
          onKeyDown={(e) => e.key === 'Enter' && save()}
        />
        <button className="btn" onClick={save} disabled={busy}>
          {busy ? '处理中…' : '保存密钥'}
        </button>
        {status?.configured && (
          <button className="btn btn-outline" onClick={clear} disabled={busy}>
            清除
          </button>
        )}
      </div>
    </div>
  );
}
