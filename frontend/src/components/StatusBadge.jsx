export const BOOKING_STATUS = {
  HELD: { label: '锁定中', cls: 'badge-warning' },
  CONFIRMED: { label: '已确认', cls: 'badge-success' },
  CHECKED_IN: { label: '已到店', cls: 'badge-info' },
  CANCELLED: { label: '已取消', cls: 'badge-muted' },
  EXPIRED: { label: '已过期', cls: 'badge-muted' },
  NO_SHOW: { label: '爽约', cls: 'badge-danger' },
};

export const ESCALATION_STATUS = {
  OPEN: { label: '待处理', cls: 'badge-danger' },
  PROCESSING: { label: '处理中', cls: 'badge-warning' },
  RESOLVED: { label: '已解决', cls: 'badge-success' },
};

export default function StatusBadge({ status, map = BOOKING_STATUS }) {
  const meta = map[status] || { label: status, cls: 'badge-muted' };
  return <span className={`badge ${meta.cls}`}>{meta.label}</span>;
}
