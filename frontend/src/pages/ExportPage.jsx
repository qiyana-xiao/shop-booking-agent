import { useState } from 'react';
import { exportApi } from '../api.js';
import AdminLayout from '../components/AdminLayout.jsx';
import { toast } from '../components/Toast.jsx';

function dateStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export default function ExportPage() {
  const [from, setFrom] = useState(dateStr(new Date(Date.now() - 89 * 86400000)));
  const [to, setTo] = useState(dateStr(new Date()));
  const [busy, setBusy] = useState('');

  const downloadBookings = async () => {
    if (busy) return;
    if (from > to) {
      toast('开始日期不能晚于结束日期', 'error');
      return;
    }
    setBusy('csv');
    try {
      await exportApi.bookingsCsv(from, to);
      toast('预约记录已导出', 'success');
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy('');
    }
  };

  const downloadConfig = async () => {
    if (busy) return;
    setBusy('json');
    try {
      await exportApi.configJson();
      toast('配置快照已导出', 'success');
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      setBusy('');
    }
  };

  return (
    <AdminLayout title="数据导出">
      <p className="page-desc">
        数据是您的，随时可以带走。CSV 带 BOM 头，Excel 双击直接打开不乱码。
      </p>

      <div className="grid grid-2">
        <div className="card">
          <h3 style={{ marginTop: 0, fontSize: 15 }}>📄 预约记录（CSV）</h3>
          <p className="text-muted" style={{ fontSize: 13 }}>
            包含单号、状态、时间、服务、人数、顾客姓名、手机号、备注与取消原因。
          </p>
          <div className="row mb-16">
            <div className="field" style={{ marginBottom: 0 }}>
              <label>开始日期</label>
              <input className="input" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            </div>
            <div className="field" style={{ marginBottom: 0 }}>
              <label>结束日期</label>
              <input className="input" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
            </div>
          </div>
          <button className="btn" onClick={downloadBookings} disabled={!!busy}>
            {busy === 'csv' ? '导出中…' : '导出预约 CSV'}
          </button>
        </div>

        <div className="card">
          <h3 style={{ marginTop: 0, fontSize: 15 }}>🗂️ 配置快照（JSON）</h3>
          <p className="text-muted" style={{ fontSize: 13 }}>
            店铺信息、营业时间、全部服务项与知识库。备份或换机时用它。
          </p>
          <button className="btn" onClick={downloadConfig} disabled={!!busy}>
            {busy === 'json' ? '导出中…' : '导出配置 JSON'}
          </button>
          <div className="notice notice-info mt-16">
            <span>💡</span>
            <span>
              快照仅供备份与查看；当前版本暂不支持一键导入恢复，重配时可作为参照逐项填写。
            </span>
          </div>
        </div>
      </div>
    </AdminLayout>
  );
}
