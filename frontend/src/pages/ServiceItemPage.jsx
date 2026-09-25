import { useEffect, useState } from 'react';
import { serviceItemApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import Modal from '../components/Modal.jsx';
import { toast } from '../components/Toast.jsx';

const DURATIONS = [30, 45, 60, 90, 120, 150, 240];
const INDUSTRY_LABELS = { restaurant: '餐饮', beauty: '美业', housekeeping: '家政' };

const blank = { name: '', capacityPerUnit: 4, unitCount: 6, durationMinutes: 90, price: '', advanceDays: 30, cancelPolicy: '' };

export default function ServiceItemPage() {
  const [list, setList] = useState(null);
  const [editing, setEditing] = useState(null); // {id? , ...fields}
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = () =>
    serviceItemApi
      .list()
      .then(setList)
      .catch((e) => toast(e.message, 'error'));

  useEffect(() => {
    load();
  }, []);

  const save = async () => {
    if (!editing || busy) return;
    setBusy(true);
    try {
      const payload = {
        name: editing.name.trim(),
        capacityPerUnit: Number(editing.capacityPerUnit) || 1,
        unitCount: Number(editing.unitCount) || 1,
        durationMinutes: Number(editing.durationMinutes) || 60,
        ...(String(editing.price).trim() ? { price: String(editing.price).trim() } : {}),
        ...(editing.advanceDays ? { advanceDays: Number(editing.advanceDays) || 30 } : {}),
        ...(editing.cancelPolicy?.trim() ? { cancelPolicy: editing.cancelPolicy.trim() } : {}),
      };
      if (editing.id) {
        await serviceItemApi.update(editing.id, payload);
        toast('服务项已更新', 'success');
      } else {
        await serviceItemApi.create(payload);
        toast('服务项已新增，未来档期已自动生成', 'success');
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
      await serviceItemApi.changeStatus(item.id, next);
      toast(next === 'ACTIVE' ? '已上架' : '已下架', 'success');
      load();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const doDelete = async () => {
    if (!deleting || busy) return;
    setBusy(true);
    try {
      await serviceItemApi.remove(deleting.id);
      toast('已删除', 'success');
      setDeleting(null);
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const applyTemplates = async (industry) => {
    try {
      const templates = await serviceItemApi.templates();
      const existing = new Set((list || []).map((i) => i.name));
      const toAdd = (templates[industry] || []).filter((t) => !existing.has(t.name));
      if (!toAdd.length) {
        toast('该行业模板的项目已全部存在', 'info');
        return;
      }
      for (const t of toAdd) {
        await serviceItemApi.create(t);
      }
      toast(`已按${INDUSTRY_LABELS[industry]}模板新增 ${toAdd.length} 个服务项`, 'success');
      load();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  return (
    <AdminLayout title="服务项目">
      <p className="page-desc">
        顾客在对话里听到的"有什么服务、能坐几人、多少钱"都来自这里。新增服务会自动生成未来 30 天档期；
        下架会关闭未来空闲档期；有未来预约的服务不能删除。
      </p>

      <div className="row-between mb-16">
        <div className="row wrap">
          <span className="text-muted" style={{ fontSize: 13 }}>一键套用行业模板：</span>
          {Object.entries(INDUSTRY_LABELS).map(([value, label]) => (
            <button key={value} className="btn btn-outline btn-sm" onClick={() => applyTemplates(value)}>
              + {label}
            </button>
          ))}
        </div>
        <button
          className="btn"
          onClick={() => setEditing({ ...blank })}
        >
          + 新增服务项
        </button>
      </div>

      <div className="card">
        {!list ? (
          <div className="spinner" />
        ) : list.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">🍽️</div>
            还没有服务项目，试试上面的行业模板一键套用
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>单次容纳</th>
                  <th>数量</th>
                  <th>时长</th>
                  <th>价格</th>
                  <th>可提前预约</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {list.map((item) => (
                  <tr key={item.id} style={{ opacity: item.status === 'INACTIVE' ? 0.55 : 1 }}>
                    <td>{item.name}</td>
                    <td>{item.capacityPerUnit} 人</td>
                    <td>{item.unitCount}</td>
                    <td>{item.durationMinutes} 分钟</td>
                    <td>{item.price ? `¥${item.price}` : '—'}</td>
                    <td>{item.advanceDays} 天</td>
                    <td>
                      <span className={`badge ${item.status === 'ACTIVE' ? 'badge-success' : 'badge-muted'}`}>
                        {item.status === 'ACTIVE' ? '上架中' : '已下架'}
                      </span>
                    </td>
                    <td>
                      <div className="row" style={{ gap: 6 }}>
                        <button className="btn-link" onClick={() => setEditing({ ...item, price: item.price || '' })}>
                          编辑
                        </button>
                        <button className="btn-link" onClick={() => toggle(item)}>
                          {item.status === 'ACTIVE' ? '下架' : '上架'}
                        </button>
                        <button
                          className="btn-link"
                          style={{ color: 'var(--danger)' }}
                          onClick={() => setDeleting(item)}
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
          title={editing.id ? `编辑 · ${editing.name}` : '新增服务项'}
          onClose={() => setEditing(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setEditing(null)}>
                取消
              </button>
              <button className="btn" onClick={save} disabled={busy || !editing.name.trim()}>
                {busy ? '保存中…' : '保存'}
              </button>
            </>
          }
        >
          <div className="form-grid">
            <div className="field" style={{ gridColumn: 'span 2' }}>
              <label>名称 *</label>
              <input
                className="input"
                placeholder="例如：露台位 / 深度保洁 4 小时"
                value={editing.name}
                onChange={(e) => setEditing({ ...editing, name: e.target.value })}
              />
            </div>
            <div className="field">
              <label>单次容纳（人）</label>
              <input
                className="input"
                type="number"
                min="1"
                value={editing.capacityPerUnit}
                onChange={(e) => setEditing({ ...editing, capacityPerUnit: e.target.value })}
              />
            </div>
            <div className="field">
              <label>数量（几张 / 并发几单）</label>
              <input
                className="input"
                type="number"
                min="1"
                value={editing.unitCount}
                onChange={(e) => setEditing({ ...editing, unitCount: e.target.value })}
              />
            </div>
            <div className="field">
              <label>单次时长</label>
              <select
                className="select"
                value={editing.durationMinutes}
                onChange={(e) => setEditing({ ...editing, durationMinutes: Number(e.target.value) })}
              >
                {DURATIONS.map((d) => (
                  <option key={d} value={d}>
                    {d} 分钟
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>价格（元，可空）</label>
              <input
                className="input"
                value={editing.price}
                onChange={(e) => setEditing({ ...editing, price: e.target.value })}
              />
            </div>
            <div className="field">
              <label>可提前预约（天）</label>
              <input
                className="input"
                type="number"
                min="1"
                max="90"
                value={editing.advanceDays}
                onChange={(e) => setEditing({ ...editing, advanceDays: e.target.value })}
              />
            </div>
            <div className="field" style={{ gridColumn: 'span 2' }}>
              <label>取消政策（顾客问起时 AI 会引用）</label>
              <input
                className="input"
                placeholder="例如：提前 2 小时可免费取消"
                value={editing.cancelPolicy || ''}
                onChange={(e) => setEditing({ ...editing, cancelPolicy: e.target.value })}
              />
            </div>
          </div>
          {!editing.id && (
            <p className="text-muted" style={{ fontSize: 12, margin: 0 }}>
              保存后将自动生成该服务未来 30 天的档期。
            </p>
          )}
        </Modal>
      )}

      {deleting && (
        <Modal
          title="删除服务项"
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
            确认删除「<strong>{deleting.name}</strong>」？
            若该服务仍有未来预约，删除会被拒绝（可先改为"下架"）。
          </p>
        </Modal>
      )}
    </AdminLayout>
  );
}
