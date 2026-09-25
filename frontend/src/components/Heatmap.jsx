/**
 * 近 28 天时段热力图：星期 × 小时网格，纯 CSS 渲染，不引图表库。
 * 颜色深浅 = 该时段累计预约量 / 最大值。
 */
export default function Heatmap({ heat }) {
  if (!heat?.cells?.length) {
    return <div className="empty">暂无预约热度数据</div>;
  }
  const max = heat.max || 1;
  const hours = Array.from({ length: 24 }, (_, i) => i);
  const cellMap = new Map();
  for (const c of heat.cells) {
    cellMap.set(`${c.day}-${c.hour}`, c.count);
  }
  const days = Array.from({ length: 7 }, (_, i) => i + 1);
  const dayNames = ['', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];

  return (
    <div>
      <div className="heatmap">
        <div />
        {hours.map((h) => (
          <div key={h} className="heatmap-hour">
            {h % 3 === 0 ? h : ''}
          </div>
        ))}
        {days.map((d) => (
          <div key={d} style={{ display: 'contents' }}>
            <div className="heatmap-label">{dayNames[d]}</div>
            {hours.map((h) => {
              const count = cellMap.get(`${d}-${h}`) || 0;
              const alpha = count === 0 ? 0 : 0.15 + (count / max) * 0.85;
              return (
                <div
                  key={h}
                  className="heatmap-cell"
                  title={`${dayNames[d]} ${String(h).padStart(2, '0')}:00 · ${count} 单`}
                  style={
                    count > 0
                      ? { background: `rgba(13, 148, 136, ${alpha.toFixed(2)})` }
                      : undefined
                  }
                />
              );
            })}
          </div>
        ))}
      </div>
      <div className="row mt-8" style={{ justifyContent: 'flex-end', gap: 6 }}>
        <span className="text-muted" style={{ fontSize: 11 }}>少</span>
        {[0.2, 0.4, 0.6, 0.8, 1].map((a) => (
          <span
            key={a}
            style={{
              width: 14,
              height: 10,
              borderRadius: 2,
              background: `rgba(13, 148, 136, ${a})`,
            }}
          />
        ))}
        <span className="text-muted" style={{ fontSize: 11 }}>多（峰值 {max} 单）</span>
      </div>
    </div>
  );
}
