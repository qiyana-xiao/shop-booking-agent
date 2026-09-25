import { useEffect, useState } from 'react';
import { shopApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import AiKeyCard from '../components/AiKeyCard.jsx';
import HoursEditor from '../components/HoursEditor.jsx';
import Modal from '../components/Modal.jsx';
import { toast } from '../components/Toast.jsx';

export default function ShopSettingPage() {
  const [shop, setShop] = useState(null);
  const [form, setForm] = useState(null);
  const [hoursChanged, setHoursChanged] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [rebuildResult, setRebuildResult] = useState(null);

  useEffect(() => {
    shopApi
      .get()
      .then((s) => {
        setShop(s);
        setForm({
          name: s.name || '',
          address: s.address || '',
          phone: s.phone || '',
          slotGranularityMinutes: s.slotGranularityMinutes || 60,
          hours: s.hours || {},
        });
      })
      .catch((e) => toast(e.message, 'error'));
  }, []);

  if (!form) {
    return (
      <AdminLayout title="店铺设置">
        <div className="spinner" />
      </AdminLayout>
    );
  }

  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  const setHours = (hours) => {
    set({ hours });
    setHoursChanged(JSON.stringify(hours) !== JSON.stringify(shop.hours));
  };

  const save = () => {
    if (hoursChanged) {
      setConfirming(true);
    } else {
      doSave();
    }
  };

  const doSave = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const payload = {
        name: form.name,
        address: form.address,
        phone: form.phone,
        slotGranularityMinutes: form.slotGranularityMinutes,
      };
      if (hoursChanged) payload.hours = form.hours;
      const res = await shopApi.update(payload);
      setShop(res);
      setForm({
        name: res.name || '',
        address: res.address || '',
        phone: res.phone || '',
        slotGranularityMinutes: res.slotGranularityMinutes || 60,
        hours: res.hours || {},
      });
      setHoursChanged(false);
      setConfirming(false);
      toast('店铺设置已保存', 'success');
      if (res.slotsRebuilt) {
        setRebuildResult(res.slotsRebuilt);
      }
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AdminLayout title="店铺设置">
      <p className="page-desc">
        {shop.hoursText ? `当前营业时间：${shop.hoursText}。` : ''}
        修改营业时间只影响未来的空闲档期，已产生的预约一律保留。
      </p>

      <div className="card" style={{ maxWidth: 860 }}>
        <h3 style={{ marginTop: 0 }}>基本信息</h3>
        <div className="form-grid">
          <div className="field">
            <label>店铺名称</label>
            <input className="input" value={form.name} maxLength={100} onChange={(e) => set({ name: e.target.value })} />
          </div>
          <div className="field">
            <label>联系电话</label>
            <input className="input" value={form.phone} onChange={(e) => set({ phone: e.target.value })} />
          </div>
          <div className="field" style={{ gridColumn: 'span 2' }}>
            <label>地址</label>
            <input className="input" value={form.address} onChange={(e) => set({ address: e.target.value })} />
          </div>
        </div>

        <h3 className="mt-16">时段粒度</h3>
        <select
          className="select"
          value={form.slotGranularityMinutes}
          style={{ maxWidth: 240 }}
          onChange={(e) => set({ slotGranularityMinutes: Number(e.target.value) })}
        >
          {[15, 30, 60, 90, 120].map((g) => (
            <option key={g} value={g}>
              每 {g} 分钟一个起始点
            </option>
          ))}
        </select>
        <p className="text-muted mt-8" style={{ fontSize: 12 }}>
          该设置与新营业时间一起保存后生效。
        </p>

        <h3 className="mt-16">营业时间</h3>
        <HoursEditor value={form.hours} onChange={setHours} />
        {hoursChanged && (
          <div className="notice notice-warning mt-8">
            <span>⚠️</span>
            <span>营业时间有改动：保存后系统会删除未来<b>空闲</b>档期并按新时间重建，已有预约原样保留。</span>
          </div>
        )}

        <div className="row mt-24" style={{ justifyContent: 'flex-end' }}>
          <button className="btn" onClick={save} disabled={busy || !form.name.trim()}>
            {busy ? '保存中…' : '保存设置'}
          </button>
        </div>
      </div>

      <AiKeyCard />

      {confirming && (
        <Modal
          title="确认修改营业时间"
          onClose={() => setConfirming(false)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setConfirming(false)}>
                取消
              </button>
              <button className="btn" onClick={doSave} disabled={busy}>
                {busy ? '执行中…' : '确认修改并重建档期'}
              </button>
            </>
          }
        >
          <p>
            系统将删除未来所有<b>空闲</b>（尚无预约）的档期，并按新的营业时间重新生成。
            <b>已产生预约的时段不会被删除</b>，受影响的预约将继续有效。
          </p>
          <p className="text-muted" style={{ fontSize: 13 }}>
            执行后我们会告诉您具体删除与重建了多少条档期。
          </p>
        </Modal>
      )}

      {rebuildResult && (
        <Modal
          title="档期已按新时间重建"
          onClose={() => setRebuildResult(null)}
          footer={
            <button className="btn" onClick={() => setRebuildResult(null)}>
              知道了
            </button>
          }
        >
          <p>
            删除空闲档期 <strong>{rebuildResult.deleted}</strong> 条，
            新建 <strong>{rebuildResult.created}</strong> 条。
            已有预约的时段全部保留。
          </p>
        </Modal>
      )}
    </AdminLayout>
  );
}
