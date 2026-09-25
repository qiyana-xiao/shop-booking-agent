/**
 * 营业时间编辑器（向导第 2 步与店铺设置页共用）：
 * 按星期七行，每行 开始/结束 时间 + 休息开关，支持工作日批量填充。
 * value: { MONDAY: {open,close,closed}, ... } 与后端契约一致。
 */
const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DAY_LABELS = { MONDAY: '周一', TUESDAY: '周二', WEDNESDAY: '周三', THURSDAY: '周四', FRIDAY: '周五', SATURDAY: '周六', SUNDAY: '周日' };

export default function HoursEditor({ value, onChange }) {
  const set = (day, patch) => {
    onChange({ ...value, [day]: { ...value[day], ...patch } });
  };

  const applyWeekdays = () => {
    const ref = value.MONDAY;
    const next = { ...value };
    for (const d of DAYS.slice(0, 5)) {
      next[d] = { ...ref };
    }
    onChange(next);
  };

  const activeCount = DAYS.filter((d) => !value[d]?.closed).length;

  return (
    <div>
      <div className="row-between mb-8">
        <span className="text-muted">每周营业 {activeCount} 天；勾选"休息"该日不生成任何档期</span>
        <button type="button" className="btn btn-outline btn-sm" onClick={applyWeekdays}>
          工作日统一按周一设置
        </button>
      </div>
      <div className="hours-row" style={{ fontWeight: 600, color: 'var(--text-secondary)', fontSize: 12 }}>
        <span>星期</span>
        <span>开门时间</span>
        <span>关门时间</span>
        <span>休息</span>
      </div>
      {DAYS.map((day) => {
        const h = value[day] || { open: '09:00', close: '21:00', closed: false };
        return (
          <div key={day} className={`hours-row ${h.closed ? 'closed' : ''}`}>
            <span className="hours-day">{DAY_LABELS[day]}</span>
            <input
              className="input"
              type="time"
              value={h.open}
              disabled={h.closed}
              onChange={(e) => set(day, { open: e.target.value })}
            />
            <input
              className="input"
              type="time"
              value={h.close}
              disabled={h.closed}
              onChange={(e) => set(day, { close: e.target.value })}
            />
            <input
              className="switch"
              type="checkbox"
              checked={!!h.closed}
              onChange={(e) => set(day, { closed: e.target.checked })}
            />
          </div>
        );
      })}
    </div>
  );
}
