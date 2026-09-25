import { useEffect, useState } from 'react';
import { knowledgeApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import Modal from '../components/Modal.jsx';
import { toast } from '../components/Toast.jsx';

const INDUSTRY_LABELS = { restaurant: '餐饮', beauty: '美业', housekeeping: '家政' };

export default function KnowledgePage() {
  const [data, setData] = useState(null);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [importing, setImporting] = useState(null); // {industry, templates, selected:Set}
  const [busy, setBusy] = useState(false);

  const load = () =>
    knowledgeApi
      .list(keyword || undefined, statusFilter || undefined)
      .then(setData)
      .catch((e) => toast(e.message, 'error'));

  useEffect(() => {
    load();
  }, [keyword, statusFilter]);

  const save = async () => {
    if (!editing || busy) return;
    setBusy(true);
    try {
      const payload = {
        question: editing.question.trim(),
        answer: editing.answer.trim(),
        keywords: editing.keywords?.trim() || '',
        category: editing.category?.trim() || '',
      };
      if (editing.id) {
        await knowledgeApi.update(editing.id, payload);
        toast('已更新', 'success');
      } else {
        await knowledgeApi.create(payload);
        toast('已添加', 'success');
      }
      setEditing(null);
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (item) => {
    const next = item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    try {
      await knowledgeApi.toggleStatus(item.id, next);
      toast(next === 'ACTIVE' ? '已上架，AI 会引用' : '已下架，AI 不再引用', 'success');
      load();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const doDelete = async () => {
    if (!deleting || busy) return;
    setBusy(true);
    try {
      await knowledgeApi.remove(deleting.id);
      toast('已删除', 'success');
      setDeleting(null);
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const openImport = async () => {
    try {
      const templates = await knowledgeApi.templates();
      setImporting({ industries: templates, selected: new Set(), active: 'restaurant' });
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const toggleSelect = (q) => {
    setImporting((imp) => {
      const selected = new Set(imp.selected);
      if (selected.has(q)) selected.delete(q);
      else selected.add(q);
      return { ...imp, selected };
    });
  };

  const doImport = async () => {
    if (!importing || busy) return;
    setBusy(true);
    try {
      const tpl = importing.industries[importing.active] || [];
      const selected = tpl
        .filter((t) => importing.selected.has(t.question))
        .map((t) => ({ question: t.question, answer: t.answer }));
      if (!selected.length) {
        toast('请至少勾选一条', 'error');
        return;
      }
      const res = await knowledgeApi.importTemplates(importing.active, selected);
      toast(`成功导入 ${res.imported} 条，答案可再修改`, 'success');
      setImporting(null);
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const list = data?.list || [];

  return (
    <AdminLayout title="知识库">
      <p className="page-desc">
        AI 回答"停车、宠物、发票、最低消费"这类问题全靠这里。三种录入方式：模板勾选导入、手动添加、
        转人工工单一键回流（在工单页操作）。
      </p>

      <div className="row-between mb-16">
        <div className="row">
          <input
            className="input"
            placeholder="搜索问题或关键词"
            value={keyword}
            style={{ width: 220 }}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="select"
            value={statusFilter}
            style={{ width: 130 }}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            <option value="">全部状态</option>
            <option value="ACTIVE">上架中</option>
            <option value="INACTIVE">已下架</option>
          </select>
        </div>
        <div className="row">
          <button className="btn btn-outline" onClick={openImport}>
            模板导入（推荐）
          </button>
          <button
            className="btn"
            onClick={() => setEditing({ question: '', answer: '', keywords: '', category: '' })}
          >
            + 手动添加
          </button>
        </div>
      </div>

      <div className="card">
        {!data ? (
          <div className="spinner" />
        ) : list.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">📚</div>
            知识库还是空的，点击"模板导入"勾选几条常见问题即可开始
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '32%' }}>问题</th>
                  <th>答案</th>
                  <th>命中</th>
                  <th>来源</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {list.map((k) => (
                  <tr key={k.id} style={{ opacity: k.status === 'INACTIVE' ? 0.55 : 1 }}>
                    <td>{k.question}</td>
                    <td style={{ maxWidth: 320 }}>
                      <div style={{ maxHeight: 60, overflow: 'hidden', fontSize: 13 }}>{k.answer}</div>
                    </td>
                    <td>{k.hitCount}</td>
                    <td>
                      <span className={`badge ${k.source === 'FEEDBACK' ? 'badge-purple' : k.source === 'TEMPLATE' ? 'badge-info' : 'badge-muted'}`}>
                        {{ MANUAL: '手动', TEMPLATE: '模板', FEEDBACK: '工单回流' }[k.source] || k.source}
                      </span>
                    </td>
                    <td>
                      <span className={`badge ${k.status === 'ACTIVE' ? 'badge-success' : 'badge-muted'}`}>
                        {k.status === 'ACTIVE' ? '上架' : '下架'}
                      </span>
                    </td>
                    <td>
                      <div className="row" style={{ gap: 6 }}>
                        <button className="btn-link" onClick={() => setEditing({ ...k })}>
                          编辑
                        </button>
                        <button className="btn-link" onClick={() => toggle(k)}>
                          {k.status === 'ACTIVE' ? '下架' : '上架'}
                        </button>
                        <button
                          className="btn-link"
                          style={{ color: 'var(--danger)' }}
                          onClick={() => setDeleting(k)}
                        >
                          删除
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {editing && (
        <Modal
          title={editing.id ? '编辑知识条目' : '添加知识条目'}
          onClose={() => setEditing(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setEditing(null)}>
                取消
              </button>
              <button
                className="btn"
                onClick={save}
                disabled={busy || !editing.question.trim() || !editing.answer.trim()}
              >
                {busy ? '保存中…' : '保存'}
              </button>
            </>
          }
        >
          <div className="field">
            <label>顾客可能会怎么问 *</label>
            <input
              className="input"
              placeholder="例如：门口可以停车吗？"
              value={editing.question}
              maxLength={200}
              onChange={(e) => setEditing({ ...editing, question: e.target.value })}
            />
          </div>
          <div className="field">
            <label>标准答案 *</label>
            <textarea
              className="textarea"
              placeholder="例如：可以，店门口有免费停车位，高峰期可能需要等位。"
              value={editing.answer}
              onChange={(e) => setEditing({ ...editing, answer: e.target.value })}
            />
          </div>
          <div className="form-grid">
            <div className="field">
              <label>触发关键词（空格分隔）</label>
              <input
                className="input"
                placeholder="停车 车位 泊车"
                value={editing.keywords || ''}
                onChange={(e) => setEditing({ ...editing, keywords: e.target.value })}
              />
            </div>
            <div className="field">
              <label>分类（可选）</label>
              <input
                className="input"
                placeholder="例如：到店须知"
                value={editing.category || ''}
                onChange={(e) => setEditing({ ...editing, category: e.target.value })}
              />
            </div>
          </div>
        </Modal>
      )}

      {importing && (
        <Modal
          wide
          title="按行业模板批量导入"
          onClose={() => setImporting(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setImporting(null)}>
                取消
              </button>
              <button className="btn" onClick={doImport} disabled={busy || importing.selected.size === 0}>
                {busy ? '导入中…' : `导入勾选的 ${importing.selected.size} 条`}
              </button>
            </>
          }
        >
          <div className="tabs">
            {Object.entries(importing.industries).map(([key, items]) => (
              <button
                key={key}
                className={`tab ${importing.active === key ? 'active' : ''}`}
                onClick={() => setImporting({ ...importing, active: key, selected: new Set() })}
              >
                {INDUSTRY_LABELS[key] || key}（{items.length} 条）
              </button>
            ))}
          </div>
          {(importing.industries[importing.active] || []).map((t) => {
            const checked = importing.selected.has(t.question);
            return (
              <label
                key={t.question}
                className="card mb-8"
                style={{
                  display: 'flex',
                  gap: 10,
                  cursor: 'pointer',
                  padding: 12,
                  borderColor: checked ? 'var(--primary)' : undefined,
                  background: checked ? 'var(--primary-bg)' : undefined,
                }}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleSelect(t.question)}
                  style={{ marginTop: 3 }}
                />
                <div>
                  <div style={{ fontWeight: 500 }}>{t.question}</div>
                  <div className="text-muted" style={{ fontSize: 13 }}>
                    {t.answer}
                  </div>
                </div>
              </label>
            );
          })}
          <p className="text-muted" style={{ fontSize: 12 }}>
            已存在的问题会自动跳过；导入后答案随时可以再改。
          </p>
        </Modal>
      )}

      {deleting && (
        <Modal
          title="删除知识条目"
          onClose={() => setDeleting(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setDeleting(null)}>
                取消
              </button>
              <button className="btn btn-danger" onClick={doDelete} disabled={busy}>
                {busy ? '删除中…' : '确认删除'}
              </button>
            </>
          }
        >
          <p>
            确认删除「<strong>{deleting.question}</strong>」？删除后 AI 不会再引用这条答案。
          </p>
        </Modal>
      )}
    </AdminLayout>
  );
}
