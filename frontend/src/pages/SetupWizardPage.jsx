import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { setupApi, serviceItemApi, shopApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import HoursEditor from '../components/HoursEditor.jsx';
import { toast } from '../components/Toast.jsx';

const DRAFT_KEY = 'sb_wizard_draft';
const SLOT_DAYS = 30;

const INDUSTRIES = [
  { value: 'restaurant', label: '餐饮' },
  { value: 'beauty', label: '美业' },
  { value: 'housekeeping', label: '家政' },
];

const DURATIONS = [30, 45, 60, 90, 120, 150, 240];

const DEFAULT_HOURS = (() => {
  const h = {};
  ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].forEach((d) => {
    h[d] = { open: '10:00', close: '22:00', closed: d === 'MONDAY' ? false : false };
  });
  return h;
})();

const emptyDraft = {
  name: '',
  address: '',
  phone: '',
  hours: DEFAULT_HOURS,
  slotGranularityMinutes: 60,
  mode: 'template',
  industry: 'restaurant',
  items: [{ name: '', capacityPerUnit: 4, unitCount: 6, durationMinutes: 90, price: '' }],
};

/** 与后端 SlotGeneratorService.previewCount 同步的估算：粒度推进 × 时长不越界 */
function countSlotsForDay(open, close, granularity, duration) {
  const toMin = (t) => {
    const [h, m] = t.split(':').map(Number);
    return h * 60 + m;
  };
  let start = toMin(open);
  const end = toMin(close);
  let count = 0;
  while (start + duration <= end) {
    count++;
    start += granularity;
    if (start >= end) break;
  }
  return count;
}

function estimateSlots(draft, items) {
  const granularity = Number(draft.slotGranularityMinutes) || 60;
  const active = items.filter((i) => i.name);
  if (!active.length) return 0;
  let total = 0;
  const today = new Date();
  for (let d = 0; d < SLOT_DAYS; d++) {
    const date = new Date(today);
    date.setDate(today.getDate() + d);
    const dayName = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'][date.getDay()];
    const h = draft.hours[dayName];
    if (!h || h.closed) continue;
    for (const item of active) {
      total += countSlotsForDay(h.open, h.close, granularity, Number(item.durationMinutes) || 60);
    }
  }
  return total;
}

const STEPS = ['店铺信息', '营业时间', '服务项目', '确认生成'];

export default function SetupWizardPage() {
  const [step, setStep] = useState(1);
  const [draft, setDraft] = useState(null);
  const [templates, setTemplates] = useState(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(null);
  const navigate = useNavigate();

  useEffect(() => {
    const raw = localStorage.getItem(DRAFT_KEY);
    if (raw) {
      try {
        setDraft({ ...emptyDraft, ...JSON.parse(raw) });
      } catch {
        setDraft(emptyDraft);
      }
    } else {
      // 没有草稿：尝试带出当前店铺配置（重复配置场景）
      shopApi
        .get()
        .then((shop) => {
          setDraft({
            ...emptyDraft,
            name: shop.name || '',
            address: shop.address || '',
            phone: shop.phone || '',
            hours: shop.hours || DEFAULT_HOURS,
            slotGranularityMinutes: shop.slotGranularityMinutes || 60,
          });
        })
        .catch(() => setDraft(emptyDraft));
    }
    serviceItemApi.templates().then(setTemplates).catch(() => {});
  }, []);

  useEffect(() => {
    if (draft) localStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
  }, [draft]);

  const set = (patch) => setDraft((d) => ({ ...d, ...patch }));

  const currentItems = useMemo(() => {
    if (!draft) return [];
    if (draft.mode === 'template') {
      return (templates?.[draft.industry] || []).map((t) => ({
        name: t.name,
        capacityPerUnit: t.capacityPerUnit,
        unitCount: t.unitCount,
        durationMinutes: t.durationMinutes,
        price: t.price || '',
      }));
    }
    return draft.items;
  }, [draft, templates]);

  const estimate = useMemo(
    () => (draft ? estimateSlots(draft, currentItems) : 0),
    [draft, currentItems]
  );

  if (!draft) {
    return (
      <AdminLayout title="开店向导">
        <div className="spinner" />
      </AdminLayout>
    );
  }

  const validStep = (n) => {
    if (n === 1) return draft.name.trim().length >= 1 && draft.name.length <= 100;
    if (n === 2) {
      return Object.values(draft.hours).some((h) => !h.closed);
    }
    if (n === 3) return currentItems.filter((i) => i.name && i.name.trim()).length >= 1;
    return true;
  };

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const payload = {
        name: draft.name.trim(),
        address: draft.address.trim(),
        phone: draft.phone.trim(),
        hours: draft.hours,
        slotGranularityMinutes: Number(draft.slotGranularityMinutes) || 60,
      };
      if (draft.mode === 'template') {
        payload.useTemplates = true;
        payload.industry = draft.industry;
      } else {
        payload.useTemplates = false;
        payload.serviceItems = draft.items
          .filter((i) => i.name && i.name.trim())
          .map((i) => ({
            name: i.name.trim(),
            capacityPerUnit: Number(i.capacityPerUnit) || 1,
            unitCount: Number(i.unitCount) || 1,
            durationMinutes: Number(i.durationMinutes) || 60,
            ...(String(i.price).trim() ? { price: String(i.price).trim() } : {}),
          }));
      }
      const res = await setupApi.wizard(payload);
      setDone(res);
      localStorage.removeItem(DRAFT_KEY);
      toast('开店配置完成，档期已生成', 'success');
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  if (done) {
    return (
      <AdminLayout title="开店向导">
        <div className="card text-center" style={{ padding: 48 }}>
          <div style={{ fontSize: 52 }}>🎉</div>
          <h2 style={{ margin: '12px 0 8px' }}>开店成功！</h2>
          <p className="text-secondary">
            {done.firstTime ? '首次开店' : '配置更新'}完成：服务项 {done.serviceItemsCreated} 个，
            已自动生成未来 {done.slotDays} 天共 <strong>{done.slotsGenerated}</strong> 条可预约档期。
          </p>
          <p className="text-muted" style={{ fontSize: 13 }}>
            之后每天凌晨系统会自动滚动补期，您无需操心"档期用完了"。
          </p>
          <div className="row mt-16" style={{ justifyContent: 'center' }}>
            <button className="btn" onClick={() => navigate('/admin')}>
              进入数据看板
            </button>
            <button className="btn btn-outline" onClick={() => navigate('/')}>
              看看顾客眼中的对话窗
            </button>
          </div>
        </div>
      </AdminLayout>
    );
  }

  return (
    <AdminLayout title="开店向导">
      <div className="steps">
        {STEPS.map((label, i) => {
          const n = i + 1;
          return (
            <div key={label} className={`step ${n === step ? 'active' : n < step ? 'done' : ''}`}>
              <span className="step-no">{n < step ? '✓' : n}</span>
              <span>{label}</span>
              {n < STEPS.length && <span className="step-line" />}
            </div>
          );
        })}
      </div>

      <div className="card" style={{ maxWidth: 860 }}>
        {step === 1 && (
          <>
            <h3 style={{ marginTop: 0 }}>第 1 步 · 店铺基本信息</h3>
            <p className="text-muted" style={{ fontSize: 13 }}>
              这些信息会展示给顾客，随时可以在"店铺设置"里修改。
            </p>
            <div className="field">
              <label>店铺名称 *</label>
              <input
                className="input"
                placeholder="例如：老王饭店"
                value={draft.name}
                maxLength={100}
                onChange={(e) => set({ name: e.target.value })}
              />
            </div>
            <div className="field">
              <label>地址</label>
              <input
                className="input"
                placeholder="例如：幸福路 88 号（支持直接粘贴）"
                value={draft.address}
                onChange={(e) => set({ address: e.target.value })}
              />
            </div>
            <div className="field">
              <label>联系电话</label>
              <input
                className="input"
                placeholder="顾客咨询转人工时可以打这个电话"
                value={draft.phone}
                onChange={(e) => set({ phone: e.target.value })}
              />
            </div>
          </>
        )}

        {step === 2 && (
          <>
            <h3 style={{ marginTop: 0 }}>第 2 步 · 营业时间</h3>
            <p className="text-muted" style={{ fontSize: 13 }}>
              系统按营业时间自动展开档期，勾"休息"的日子不会生成任何可预约时段。
            </p>
            <div className="field">
              <label>时段粒度（两个时段的间隔）</label>
              <select
                className="select"
                value={draft.slotGranularityMinutes}
                onChange={(e) => set({ slotGranularityMinutes: Number(e.target.value) })}
                style={{ maxWidth: 240 }}
              >
                {[15, 30, 60, 90, 120].map((g) => (
                  <option key={g} value={g}>
                    每 {g} 分钟一个起始点
                  </option>
                ))}
              </select>
            </div>
            <HoursEditor value={draft.hours} onChange={(hours) => set({ hours })} />
          </>
        )}

        {step === 3 && (
          <>
            <h3 style={{ marginTop: 0 }}>第 3 步 · 服务项目</h3>
            <p className="text-muted" style={{ fontSize: 13 }}>
              推荐直接用行业模板，之后可在"服务项目"页随时增删改。
            </p>
            <div className="tabs">
              <button
                className={`tab ${draft.mode === 'template' ? 'active' : ''}`}
                onClick={() => set({ mode: 'template' })}
              >
                行业模板（推荐）
              </button>
              <button
                className={`tab ${draft.mode === 'custom' ? 'active' : ''}`}
                onClick={() => set({ mode: 'custom' })}
              >
                自定义
              </button>
            </div>

            {draft.mode === 'template' ? (
              <>
                <div className="row wrap mb-16">
                  {INDUSTRIES.map((ind) => (
                    <button
                      key={ind.value}
                      className={`chip ${draft.industry === ind.value ? 'active' : ''}`}
                      style={
                        draft.industry === ind.value
                          ? { borderColor: 'var(--primary)', color: 'var(--primary-dark)', background: 'var(--primary-bg)' }
                          : undefined
                      }
                      onClick={() => set({ industry: ind.value })}
                    >
                      {ind.label}
                    </button>
                  ))}
                </div>
                {!templates ? (
                  <div className="spinner" />
                ) : (
                  <div className="table-wrap">
                    <table className="table">
                      <thead>
                        <tr>
                          <th>服务 / 桌型</th>
                          <th>单次容纳</th>
                          <th>数量（并发）</th>
                          <th>单次时长</th>
                          <th>价格</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(templates[draft.industry] || []).map((t) => (
                          <tr key={t.name}>
                            <td>{t.name}</td>
                            <td>{t.capacityPerUnit} 人</td>
                            <td>{t.unitCount}</td>
                            <td>{t.durationMinutes} 分钟</td>
                            <td>{t.price ? `¥${t.price}` : '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            ) : (
              <>
                {draft.items.map((item, idx) => (
                  <div key={idx} className="card mb-8" style={{ padding: 14 }}>
                    <div className="form-grid">
                      <div className="field">
                        <label>名称 *</label>
                        <input
                          className="input"
                          placeholder="例如：露台位"
                          value={item.name}
                          onChange={(e) => {
                            const items = [...draft.items];
                            items[idx] = { ...item, name: e.target.value };
                            set({ items });
                          }}
                        />
                      </div>
                      <div className="field">
                        <label>单次容纳（人）</label>
                        <input
                          className="input"
                          type="number"
                          min="1"
                          value={item.capacityPerUnit}
                          onChange={(e) => {
                            const items = [...draft.items];
                            items[idx] = { ...item, capacityPerUnit: e.target.value };
                            set({ items });
                          }}
                        />
                      </div>
                      <div className="field">
                        <label>数量（几张/几个）</label>
                        <input
                          className="input"
                          type="number"
                          min="1"
                          value={item.unitCount}
                          onChange={(e) => {
                            const items = [...draft.items];
                            items[idx] = { ...item, unitCount: e.target.value };
                            set({ items });
                          }}
                        />
                      </div>
                      <div className="field">
                        <label>单次时长</label>
                        <select
                          className="select"
                          value={item.durationMinutes}
                          onChange={(e) => {
                            const items = [...draft.items];
                            items[idx] = { ...item, durationMinutes: Number(e.target.value) };
                            set({ items });
                          }}
                        >
                          {DURATIONS.map((d) => (
                            <option key={d} value={d}>
                              {d} 分钟
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="field">
                        <label>价格（可空）</label>
                        <input
                          className="input"
                          placeholder="如 88"
                          value={item.price}
                          onChange={(e) => {
                            const items = [...draft.items];
                            items[idx] = { ...item, price: e.target.value };
                            set({ items });
                          }}
                        />
                      </div>
                    </div>
                    {draft.items.length > 1 && (
                      <button
                        className="btn-link"
                        onClick={() => set({ items: draft.items.filter((_, i) => i !== idx) })}
                      >
                        删除此项
                      </button>
                    )}
                  </div>
                ))}
                <button
                  className="btn btn-outline btn-sm"
                  onClick={() =>
                    set({
                      items: [
                        ...draft.items,
                        { name: '', capacityPerUnit: 4, unitCount: 6, durationMinutes: 90, price: '' },
                      ],
                    })
                  }
                >
                  + 添加服务项
                </button>
              </>
            )}
          </>
        )}

        {step === 4 && (
          <>
            <h3 style={{ marginTop: 0 }}>第 4 步 · 确认并生成</h3>
            <div className="card" style={{ background: 'var(--primary-bg)', border: '1px solid var(--primary-light)' }}>
              <p style={{ margin: 0, fontSize: 15 }}>
                「<strong>{draft.name}</strong>」
                {(() => {
                  const openDays = Object.entries(draft.hours).filter(([, h]) => !h.closed).length;
                  const sample = Object.values(draft.hours).find((h) => !h.closed);
                  return sample
                    ? `每周营业 ${openDays} 天（${sample.open}-${sample.close} 起），`
                    : ' ';
                })()}
                共 {currentItems.filter((i) => i.name && i.name.trim()).length} 类服务，
                {draft.slotGranularityMinutes} 分钟一个起始点。
              </p>
              <p style={{ margin: '8px 0 0', fontSize: 15 }}>
                将自动生成未来 <strong>{SLOT_DAYS}</strong> 天约{' '}
                <strong style={{ color: 'var(--primary-dark)', fontSize: 20 }}>{estimate}</strong> 条可预约档期。
              </p>
            </div>
            <div className="notice notice-info mt-16">
              <span>✅</span>
              <span>
                已有预约不受影响；提交后仍可随时回这里或到"店铺设置"修改。
                向导可中断，草稿已自动保存在本机浏览器。
              </span>
            </div>
            <div className="table-wrap mt-16">
              <table className="table">
                <thead>
                  <tr>
                    <th>服务 / 桌型</th>
                    <th>容纳</th>
                    <th>数量</th>
                    <th>时长</th>
                    <th>价格</th>
                  </tr>
                </thead>
                <tbody>
                  {currentItems
                    .filter((i) => i.name && i.name.trim())
                    .map((i) => (
                      <tr key={i.name}>
                        <td>{i.name}</td>
                        <td>{i.capacityPerUnit} 人</td>
                        <td>{i.unitCount}</td>
                        <td>{i.durationMinutes} 分钟</td>
                        <td>{i.price ? `¥${i.price}` : '—'}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </>
        )}

        <div className="row-between mt-24">
          <button
            className="btn btn-outline"
            onClick={() => setStep(Math.max(1, step - 1))}
            disabled={step === 1}
          >
            上一步
          </button>
          <span className="text-muted" style={{ fontSize: 12 }}>
            {step}/4 · 草稿已自动保存
          </span>
          {step < 4 ? (
            <button
              className="btn"
              onClick={() => setStep(step + 1)}
              disabled={!validStep(step)}
            >
              下一步
            </button>
          ) : (
            <button className="btn" onClick={submit} disabled={busy || !validStep(3)}>
              {busy ? '正在生成档期…' : '✓ 确认开店'}
            </button>
          )}
        </div>
      </div>
    </AdminLayout>
  );
}
