import { useEffect, useState } from 'react';
import { dashboardApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import Heatmap from '../components/Heatmap.jsx';
import StatusBadge from '../components/StatusBadge.jsx';

function StatCard({ label, value, unit, sub }) {
  return (
    <div className="stat-card">
      <div className="stat-label">{label}</div>
      <div className="stat-value">
        {value ?? '—'}
        {unit && <small> {unit}</small>}
      </div>
      {sub && <div className="stat-trend">{sub}</div>}
    </div>
  );
}

export default function DashboardPage() {
  const [data, setData] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    dashboardApi
      .get()
      .then(setData)
      .catch((e) => setError(e.message));
  }, []);

  return (
    <AdminLayout title="数据看板">
      {error && <div className="notice notice-danger">{error}</div>}
      {!data && !error && <div className="spinner" />}

      {data && (
        <>
          <div className="grid grid-4">
            <StatCard
              label="今日咨询"
              value={data.today.conversations}
              unit="次"
              sub={`AI 接住 ${data.today.aiHandled} 次`}
            />
            <StatCard
              label="今日接住率"
              value={data.today.catchRate}
              unit="%"
              sub={`转人工 ${data.today.escalated} 次`}
            />
            <StatCard
              label="今日预约"
              value={data.today.bookings}
              unit="单"
              sub="含已确认 / 到店"
            />
            <StatCard
              label="本月转化率"
              value={data.month.conversionRate}
              unit="%"
              sub={`本月预约 ${data.month.bookings} 单 / 咨询 ${data.month.conversations} 次`}
            />
          </div>

          <div className="grid grid-2 mt-16">
            <div className="card">
              <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>近 30 天 · 转人工 TOP 原因</h3>
              {(data.topEscalationReasons || []).length === 0 ? (
                <div className="empty" style={{ padding: 24 }}>
                  没有转人工记录，AI 全接住了 🎉
                </div>
              ) : (
                (data.topEscalationReasons || []).map((r, i) => {
                  const max = data.topEscalationReasons[0].cnt || 1;
                  return (
                    <div key={i} className="mb-8">
                      <div className="row-between" style={{ fontSize: 13 }}>
                        <span>{r.reason}</span>
                        <span className="text-muted">{r.cnt} 次</span>
                      </div>
                      <div className="cal-bar">
                        <div style={{ width: `${Math.max(6, (r.cnt / max) * 100)}%` }} />
                      </div>
                    </div>
                  );
                })
              )}
            </div>

            <div className="card">
              <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>今日预约（{data.today.date}）</h3>
              {(data.todayBookings || []).length === 0 ? (
                <div className="empty" style={{ padding: 24 }}>
                  今天还没有预约
                </div>
              ) : (
                <div className="table-wrap" style={{ maxHeight: 260, overflowY: 'auto' }}>
                  <table className="table">
                    <thead>
                      <tr>
                        <th>时间</th>
                        <th>服务</th>
                        <th>顾客</th>
                        <th>状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.todayBookings.map((b) => (
                        <tr key={b.bookingNo}>
                          <td>{String(b.startTime).slice(0, 5)}</td>
                          <td>{b.serviceName}</td>
                          <td>
                            {b.customerName || '—'}
                            <span className="text-muted" style={{ marginLeft: 4 }}>
                              {b.partySize}人
                            </span>
                          </td>
                          <td>
                            <StatusBadge status={b.status} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>

          <div className="card mt-16">
            <h3 style={{ margin: '0 0 4px', fontSize: 15 }}>
              预约时段热力（{data.heat?.from} ~ {data.heat?.to}）
            </h3>
            <p className="text-muted" style={{ margin: '0 0 12px', fontSize: 12 }}>
              颜色越深代表该星期 × 小时的预约越多，可用于安排人手与备货
            </p>
            <Heatmap heat={data.heat} />
          </div>
        </>
      )}
    </AdminLayout>
  );
}
