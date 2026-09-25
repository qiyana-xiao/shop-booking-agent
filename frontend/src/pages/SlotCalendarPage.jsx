import { useCallback, useEffect, useState } from 'react';
import { slotApi } from '../api.js';
import { getUser } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import Modal from '../components/Modal.jsx';
import { toast } from '../components/Toast.jsx';

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日'];

function ymStr(y, m) {
  return `${y} 年 ${m} 月`;
}

export default function SlotCalendarPage() {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth() + 1);
  const [days, setDays] = useState(null);
  const [selected, setSelected] = useState(null); // {date, ...day aggregate}
  const [dayDetail, setDayDetail] = useState(null);
  const [closePreview, setClosePreview] = useState(null);
  const [busy, setBusy] = useState(false);
  const isOwner = getUser()?.role === 'owner';

  const loadCalendar = useCallback(async () => {
    try {
      const list = await slotApi.calendar(year, month);
      setDays(list);
    } catch (e) {
      toast(e.message, 'error');
    }
  }, [year, month]);

  const loadDay = useCallback(async (date) => {
    try {
      setDayDetail(await slotApi.day(date));
    } catch (e) {
      toast(e.message, 'error');
    }
  }, []);

  useEffect(() => {
    loadCalendar();
  }, [loadCalendar]);

  useEffect(() => {
    if (selected) loadDay(selected.date);
  }, [selected?.date, loadDay]);

  const pickDay = (day) => {
    setSelected(day);
  };

  const shiftMonth = (delta) => {
    let y = year;
    let m = month + delta;
    if (m > 12) {
      m = 1;
      y += 1;
    }
    if (m < 1) {
      m = 12;
      y -= 1;
    }
    setYear(y);
    setMonth(m);
    setSelected(null);
    setDayDetail(null);
  };

  const askDayClose = async (day, closed) => {
    try {
      const preview = await slotApi.dayClosePreview(day.date);
      setClosePreview({ ...preview, target: day, closed });
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const doDayClose = async () => {
    if (!closePreview || busy) return;
    setBusy(true);
    try {
      const res = await slotApi.dayClose(closePreview.date, closePreview.closed);
      toast(
        closePreview.closed
          ? `已设为全天休息：关闭 ${res.changedSlots} 个空闲时段`
          : `已恢复营业：开启 ${res.changedSlots} 个时段`,
        'success'
      );
      if (res.affectedBookings > 0) {
        toast(`注意：该日仍有 ${res.affectedBookings} 个已存在的预约，请逐个联系顾客改期`, 'error');
      }
      setClosePreview(null);
      loadCalendar();
      loadDay(closePreview.date);
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const saveCapacity = async (slot, capacity) => {
    const v = Number(capacity);
    if (Number.isNaN(v)) return;
    try {
      await slotApi.updateCapacity(slot.slotId, v);
      toast('容量已更新', 'success');
      loadDay(selected.date);
      loadCalendar();
    } catch (e) {
      toast(e.message, 'error');
    }
  };

  const renew = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const res = await slotApi.generate();
      toast(`已滚动补齐未来 ${res.days} 天档期`, 'success');
      loadCalendar();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  // 月历网格：前置空白对齐周一起始
  const firstDay = days && days.length ? new Date(days[0].date + 'T00:00:00') : null;
  const leading = firstDay ? (firstDay.getDay() + 6) % 7 : 0;
  const todayStr = new Date().toISOString().slice(0, 10);
  const restDay = (d) => d.restDay;
  const pastDay = (d) => d.past || d.date < todayStr;
  const fullDay = (d) => d.open > 0 && d.remaining === 0;

  return (
    <AdminLayout title="档期日历">
      <div className="row-between mb-16">
        <div className="row">
          <button className="btn btn-outline btn-sm" onClick={() => shiftMonth(-1)}>
            ←
          </button>
          <strong style={{ minWidth: 96, textAlign: 'center' }}>{ymStr(year, month)}</strong>
          <button className="btn btn-outline btn-sm" onClick={() => shiftMonth(1)}>
            →
          </button>
        </div>
        {isOwner && (
          <div className="row">
            <span className="text-muted" style={{ fontSize: 12 }}>
              档期由营业时间自动生成并每日滚动续期，无需手动添加
            </span>
            <button className="btn btn-outline btn-sm" onClick={renew} disabled={busy}>
              立即续期 30 天
            </button>
          </div>
        )}
      </div>

      {!days ? (
        <div className="spinner" />
      ) : (
        <div className="card">
          <div className="slot-calendar">
            {WEEKDAYS.map((w) => (
              <div key={w} className="cal-weekday">
                周{w}
              </div>
            ))}
            {Array.from({ length: leading }).map((_, i) => (
              <div key={`empty-${i}`} className="cal-cell empty" />
            ))}
            {days.map((d) => (
              <div
                key={d.date}
                className={`cal-cell ${restDay(d) ? 'rest' : ''} ${pastDay(d) ? 'past' : ''} ${selected?.date === d.date ? 'selected' : ''}`}
                onClick={() => pickDay(d)}
                title={pastDay(d) ? '已结束' : restDay(d) ? '休息日' : `剩余 ${d.remaining}/${d.open}`}
              >
                <div className="row-between">
                  <span className="cal-date" style={d.date === todayStr ? { color: 'var(--primary-dark)' } : undefined}>
                    {Number(d.date.slice(8))}
                    {d.date === todayStr && <span style={{ fontSize: 10 }}> 今天</span>}
                  </span>
                  {pastDay(d) ? (
                    <span className="badge badge-muted">已结束</span>
                  ) : restDay(d) ? (
                    <span className="badge badge-warning">休</span>
                  ) : d.total === 0 ? (
                    <span className="badge badge-muted">无档期</span>
                  ) : fullDay(d) ? (
                    <span className="badge badge-danger">满</span>
                  ) : (
                    <span className="badge badge-success">余 {d.remaining}</span>
                  )}
                </div>
                {d.total > 0 && !restDay(d) && !pastDay(d) && (
                  <>
                    <div className="cal-sub">
                      已约 {d.booked} / {d.open} 个时段
                    </div>
                    <div className="cal-bar">
                      <div style={{ width: `${Math.min(100, (d.booked / Math.max(1, d.booked + d.remaining)) * 100)}%` }} />
                    </div>
                  </>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {selected && (
        <div className="card mt-16">
          <div className="row-between mb-16">
            <h3 style={{ margin: 0, fontSize: 15 }}>
              {selected.date} 档期明细{restDay(selected) ? '（休息日）' : ''}
            </h3>
            {isOwner && (
              <div className="row">
                {restDay(selected) ? (
                  <button className="btn btn-outline btn-sm" onClick={() => askDayClose(selected, false)}>
                    恢复营业
                  </button>
                ) : (
                  <button className="btn btn-danger btn-sm" onClick={() => askDayClose(selected, true)}>
                    设为全天休息
                  </button>
                )}
              </div>
            )}
          </div>

          {!dayDetail ? (
            <div className="spinner" />
          ) : (dayDetail.groups || []).length === 0 ? (
            <div className="empty">该日没有档期（可能是超出自动生成范围或整天关闭）</div>
          ) : (
            <>
              {dayDetail.past && (
                <p className="text-muted" style={{ fontSize: 12, margin: '0 0 12px' }}>
                  该日已结束，档期仅供回顾，不可再预约或调整。
                </p>
              )}
              {(dayDetail.groups || []).map((g) => (
              <div key={g.serviceItemId} className="mb-16">
                <div className="row-between mb-8">
                  <strong style={{ fontSize: 14 }}>{g.serviceName}</strong>
                </div>
                <div className="table-wrap">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>时段</th>
                        <th>已约</th>
                        <th>容量</th>
                        <th>剩余</th>
                        <th>状态</th>
                        {isOwner && <th>调整容量</th>}
                      </tr>
                    </thead>
                    <tbody>
                      {g.slots.map((s) => (
                        <tr key={s.slotId}>
                          <td style={{ whiteSpace: 'nowrap' }}>
                            {s.startTime.slice(0, 5)}-{s.endTime.slice(0, 5)}
                          </td>
                          <td>{s.bookedCount}</td>
                          <td>{s.capacity}</td>
                          <td>
                            {s.remaining > 0 ? (
                              <span className="badge badge-success">{s.remaining}</span>
                            ) : (
                              <span className="badge badge-muted">满</span>
                            )}
                          </td>
                          <td>
                            {s.status === 'OPEN' ? (
                              <span className="badge badge-info">开放</span>
                            ) : s.status === 'EXPIRED' ? (
                              <span className="badge badge-muted">已结束</span>
                            ) : (
                              <span className="badge badge-warning">已关闭</span>
                            )}
                          </td>
                          {isOwner && (
                            <td>
                              <input
                                className="input"
                                type="number"
                                min={s.bookedCount}
                                max={999}
                                defaultValue={s.capacity}
                                style={{ width: 90 }}
                                disabled={s.status !== 'OPEN'}
                                onBlur={(e) => {
                                  const v = Number(e.target.value);
                                  if (v !== s.capacity) saveCapacity(s, v);
                                }}
                                onKeyDown={(e) => e.key === 'Enter' && e.target.blur()}
                                title={s.status !== 'OPEN' ? '已关闭的时段不可调整' : `不能低于已约数 ${s.bookedCount}`}
                              />
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
              ))}
            </>
          )}
          {!isOwner && (
            <p className="text-muted" style={{ fontSize: 12, margin: 0 }}>
              店员账号为只读视图，调整档期请联系老板。
            </p>
          )}
        </div>
      )}

      {closePreview && (
        <Modal
          title={closePreview.closed ? '设为全天休息' : '恢复营业'}
          onClose={() => setClosePreview(null)}
          footer={
            <>
              <button className="btn btn-outline" onClick={() => setClosePreview(null)}>
                取消
              </button>
              <button className={`btn ${closePreview.closed ? 'btn-danger' : ''}`} onClick={doDayClose} disabled={busy}>
                {busy ? '执行中…' : closePreview.closed ? '确认休息' : '确认恢复'}
              </button>
            </>
          }
        >
          {closePreview.closed ? (
            <p>
              即将把 <strong>{closePreview.date}</strong> 设为全天休息：
              该日所有<b>空闲</b>时段将被关闭；
              {closePreview.affectedBookings > 0 ? (
                <span className="text-danger">
                  目前该日已有 <b>{closePreview.affectedBookings}</b> 个预约，这些预约会被保留，
                  请您逐个联系顾客改期（可在工单页跟进）。
                </span>
              ) : (
                <span>该日暂无已存在的预约，可放心关闭。</span>
              )}
            </p>
          ) : (
            <p>
              即将恢复 <strong>{closePreview.date}</strong> 的营业：
              之前因"全天休息"被关闭且无人预约的时段会重新开放。
            </p>
          )}
        </Modal>
      )}
    </AdminLayout>
  );
}
