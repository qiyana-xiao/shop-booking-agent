import { useCallback, useEffect, useState } from 'react';
import { bookingApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { toast } from '../components/Toast.jsx';

function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const WEEK_CN = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

/** 未来日期的人话标签：明天/后天，再往后用 "9/20 周六" */
function upcomingLabel(dateStr) {
  const today = new Date();
  const d = new Date(`${dateStr}T00:00:00`);
  const diff = Math.round((d - new Date(today.getFullYear(), today.getMonth(), today.getDate())) / 86400000);
  if (diff === 1) return '明天';
  if (diff === 2) return '后天';
  return `${d.getMonth() + 1}/${d.getDate()} ${WEEK_CN[d.getDay()]}`;
}

export default function BookingPage() {
  const [date, setDate] = useState(todayStr());
  const [list, setList] = useState(null);
  const [upcoming, setUpcoming] = useState(null);
  const [busyNo, setBusyNo] = useState('');

  const load = useCallback(async () => {
    try {
      const [dayList, upcomingList] = await Promise.all([
        bookingApi.today(date),
        bookingApi.upcomingSummary(),
      ]);
      setList(dayList);
      setUpcoming(upcomingList);
    } catch (e) {
      toast(e.message, 'error');
      setList([]);
      setUpcoming([]);
    }
  }, [date]);

  useEffect(() => {
    load();
  }, [load]);

  const act = async (bookingNo, action, label) => {
    if (busyNo) return;
    setBusyNo(bookingNo);
    try {
      if (action === 'checkin') await bookingApi.checkin(bookingNo);
      else await bookingApi.noShow(bookingNo);
      toast(label + '成功', 'success');
      load();
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusyNo('');
    }
  };

  const summary = (list || []).reduce(
    (acc, b) => {
      if (['CONFIRMED', 'CHECKED_IN'].includes(b.status)) acc.confirmed += 1;
      if (b.status === 'HELD') acc.held += 1;
      if (b.status === 'NO_SHOW') acc.noShow += 1;
      acc.people += ['HELD', 'CONFIRMED', 'CHECKED_IN'].includes(b.status) ? b.partySize : 0;
      return acc;
    },
    { confirmed: 0, held: 0, noShow: 0, people: 0 }
  );

  const upcomingTotal = (upcoming || []).reduce(
    (acc, u) => {
      acc.total += Number(u.total) || 0;
      acc.people += Number(u.people) || 0;
      return acc;
    },
    { total: 0, people: 0 }
  );

  return (
    <AdminLayout title="今日预约">
      <div className="row-between mb-16">
        <div className="row">
          <input
            className="input"
            type="date"
            value={date}
            style={{ width: 170 }}
            onChange={(e) => setDate(e.target.value)}
          />
          <button className="btn btn-outline btn-sm" onClick={() => setDate(todayStr())}>
            回到今天
          </button>
        </div>
        <span className="text-muted">
          已确认/到店 {summary.confirmed} 单 · 锁定中 {summary.held} 单 · 爽约 {summary.noShow} 单 ·
          预计接待 {summary.people} 人
        </span>
      </div>

      {/* 未来预约总览：打开页面即可看到后面几天还有哪些预约，点击任意一天切换查看明细 */}
      <div className="card mb-16">
        {upcoming === null ? (
          <div className="spinner" />
        ) : upcoming.length === 0 ? (
          <div className="row" style={{ gap: 8, alignItems: 'center' }}>
            <strong style={{ flexShrink: 0 }}>未来 14 天</strong>
            <span className="text-muted">暂无任何预约，临时休息或关门不会影响任何顾客</span>
          </div>
        ) : (
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <strong style={{ flexShrink: 0 }}>未来 14 天</strong>
            {upcoming.map((u) => (
              <button
                key={u.date}
                className="chip"
                style={date === u.date ? { borderColor: 'var(--primary)', color: 'var(--primary-dark)', background: 'var(--primary-bg)' } : undefined}
                onClick={() => setDate(u.date)}
                title={`点击查看 ${u.date} 的预约明细`}
              >
                {upcomingLabel(u.date)} · {u.total} 单 {u.people} 人
              </button>
            ))}
            <span className="text-muted" style={{ marginLeft: 'auto' }}>
              共 {upcomingTotal.total} 单 · {upcomingTotal.people} 人
            </span>
          </div>
        )}
      </div>

      <div className="card">
        {!list ? (
          <div className="spinner" />
        ) : list.length === 0 ? (
          <div className="empty">
            <div className="empty-icon">📭</div>
            {date === todayStr() ? '今天还没有预约' : `${date} 没有预约`}
          </div>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>服务</th>
                  <th>顾客</th>
                  <th>手机号</th>
                  <th>人数</th>
                  <th>状态</th>
                  <th>备注</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {list.map((b) => (
                  <tr key={b.bookingNo}>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      {String(b.startTime).slice(0, 5)}-{String(b.endTime).slice(0, 5)}
                    </td>
                    <td>{b.serviceName}</td>
                    <td>{b.customerName || <span className="text-muted">未留名</span>}</td>
                    <td style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}>
                      {b.customerPhone || '—'}
                    </td>
                    <td>{b.partySize}</td>
                    <td>
                      <StatusBadge status={b.status} />
                    </td>
                    <td style={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {b.remark || '—'}
                    </td>
                    <td>
                      {b.status === 'CONFIRMED' && (
                        <div className="row" style={{ gap: 6 }}>
                          <button
                            className="btn btn-sm"
                            disabled={busyNo === b.bookingNo}
                            onClick={() => act(b.bookingNo, 'checkin', '到店核销')}
                          >
                            到店核销
                          </button>
                          <button
                            className="btn btn-danger btn-sm"
                            disabled={busyNo === b.bookingNo}
                            onClick={() => act(b.bookingNo, 'no-show', '标记爽约')}
                          >
                            爽约
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </AdminLayout>
  );
}
