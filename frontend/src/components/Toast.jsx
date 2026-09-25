import { useEffect, useState } from 'react';

/** 全局轻提示：window.dispatchEvent(new CustomEvent('toast', { detail: { message, type } })) */
export function toast(message, type = 'info') {
  window.dispatchEvent(new CustomEvent('toast', { detail: { message, type } }));
}

export function ToastHost() {
  const [toasts, setToasts] = useState([]);

  useEffect(() => {
    const onToast = (e) => {
      const { message, type } = e.detail || {};
      const id = Date.now() + Math.random();
      setToasts((prev) => [...prev, { id, message, type }]);
      setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
      }, 2600);
    };
    window.addEventListener('toast', onToast);
    return () => window.removeEventListener('toast', onToast);
  }, []);

  return (
    <div className="toast-host">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.type === 'error' ? 'toast-error' : t.type === 'success' ? 'toast-success' : ''}`}>
          {t.message}
        </div>
      ))}
    </div>
  );
}
