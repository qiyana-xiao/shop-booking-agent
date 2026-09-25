import { useEffect, useState } from 'react';
import StatusBadge from './StatusBadge.jsx';

function fmtCountdown(seconds) {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/**
 * 预约卡片：对话气泡内与"我的预约"共用。
 * HELD 状态显示 15 分钟锁座倒计时，超时自动提示已释放。
 */
export default function BookingCard({ booking, compact }) {
  const [remaining, setRemaining] = useState(
    booking?.holdRemainingSeconds ?? 0
  );

  useEffect(() => {
    if (booking?.status !== 'HELD' || !booking.holdRemainingSeconds) return;
    setRemaining(booking.holdRemainingSeconds);
    const timer = setInterval(() => {
      setRemaining((r) => (r > 0 ? r - 1 : 0));
    }, 1000);
    return () => clearInterval(timer);
  }, [booking?.bookingNo, booking?.status, booking?.holdRemainingSeconds]);

  if (!booking) return null;

  return (
    <div className="booking-card">
      <div className="booking-card-head">
        <span className="booking-card-title">🧾 {booking.serviceName}</span>
        <StatusBadge status={booking.status} />
      </div>
      <div className="booking-card-row">
        <span>时间</span>
        <strong>
          {booking.date} {booking.startTime?.slice(0, 5)}-{booking.endTime?.slice(0, 5)}
        </strong>
      </div>
      <div className="booking-card-row">
        <span>人数</span>
        <strong>{booking.partySize} 人</strong>
      </div>
      {booking.customerName && (
        <div className="booking-card-row">
          <span>联系人</span>
          <strong>
            {booking.customerName} {booking.customerPhone}
          </strong>
        </div>
      )}
      {booking.remark && (
        <div className="booking-card-row">
          <span>备注</span>
          <strong>{booking.remark}</strong>
        </div>
      )}
      {booking.status === 'HELD' && (
        <div className="booking-card-row">
          <span>锁座保留</span>
          <strong className="countdown">
            {remaining > 0 ? `${fmtCountdown(remaining)} 后释放` : '已超时，请重新预约'}
          </strong>
        </div>
      )}
      {booking.cancelReason && (
        <div className="booking-card-row">
          <span>取消原因</span>
          <strong>{booking.cancelReason}</strong>
        </div>
      )}
      {booking.bookingNo && !compact && (
        <div className="booking-card-row">
          <span>单号</span>
          <strong style={{ fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12 }}>
            {booking.bookingNo}
          </strong>
        </div>
      )}
    </div>
  );
}
