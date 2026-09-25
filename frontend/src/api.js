/**
 * 统一 HTTP 层：
 * - 始终携带 X-Session-Key（游客会话标识，"我的预约"归属凭证）
 * - 登录后自动注入 Authorization: Bearer <token>
 * - 401 时清理会话跳转登录（仅对需要登录的调用）
 * - 后端错误统一为 { "error": "..." }，这里统一抛 Error(message)
 */

const TOKEN_KEY = 'sb_token';
const USER_KEY = 'sb_user';
const SESSION_KEY = 'sb_session_key';

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function getUser() {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function saveAuth(token, user) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

/**
 * 会话标识：
 * - 游客：首次访问生成随机串，之后复用（幂等键与预约归属凭证）；
 * - 登录顾客：一律使用账号专属键 user-{id}——每个账号聊天与预约完全隔离，换设备也不变；
 * - 店家/店员：独立的"预览会话"（每个账号每台设备一把钥匙），与顾客会话完全隔离。
 * 后端 SessionKeys 会做同样的强制（登录顾客不信任请求头），这里是让前端行为与之一致。
 */
export function getSessionKey() {
  const user = getUser();
  if (user && (user.role === 'owner' || user.role === 'staff')) {
    const scoped = `${SESSION_KEY}:preview:${user.id}`;
    let key = localStorage.getItem(scoped);
    if (!key) {
      const rand = Array.from(crypto.getRandomValues(new Uint8Array(12)))
        .map((b) => b.toString(36).padStart(2, '0'))
        .join('');
      key = `web-preview-${user.id}-${Date.now().toString(36)}-${rand}`.slice(0, 64);
      localStorage.setItem(scoped, key);
    }
    return key;
  }
  if (user && user.role === 'customer') {
    return `user-${user.id}`;
  }
  let key = localStorage.getItem(SESSION_KEY);
  if (!key) {
    const rand = Array.from(crypto.getRandomValues(new Uint8Array(12)))
      .map((b) => b.toString(36).padStart(2, '0'))
      .join('');
    key = `web-${Date.now().toString(36)}-${rand}`.slice(0, 64);
    localStorage.setItem(SESSION_KEY, key);
  }
  return key;
}

async function request(method, path, body, options = {}) {
  const headers = { 'X-Session-Key': getSessionKey() };
  const token = getToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 401) {
    if (token && !options.anonymous) {
      clearAuth();
      window.location.href = '/login';
      throw new Error('登录已过期，请重新登录');
    }
  }

  let data = null;
  const text = await res.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    const message =
      (data && typeof data === 'object' && data.error) ||
      `请求失败（${res.status}）`;
    throw new Error(message);
  }
  return data;
}

export const api = {
  get: (path, options) => request('GET', path, undefined, options),
  post: (path, body, options) => request('POST', path, body, options),
  put: (path, body, options) => request('PUT', path, body, options),
  delete: (path, options) => request('DELETE', path, undefined, options),

  /** 登录态下载（CSV/JSON 导出），触发浏览器保存文件 */
  async download(path, filename) {
    const headers = { 'X-Session-Key': getSessionKey() };
    const token = getToken();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    const res = await fetch(path, { headers });
    if (!res.ok) {
      let message = `下载失败（${res.status}）`;
      try {
        const data = await res.json();
        if (data.error) message = data.error;
      } catch {
        /* 非 JSON 响应体 */
      }
      throw new Error(message);
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  },
};

/* ==================== 认证 ==================== */

export const authApi = {
  register: (payload) => api.post('/api/auth/register', payload, { anonymous: true }),
  login: (payload) => api.post('/api/auth/login', payload, { anonymous: true }),
  logout: () => api.post('/api/auth/logout'),
  me: () => api.get('/api/auth/me'),
};

/* ==================== 对话（顾客侧，游客可用） ==================== */

export const chatApi = {
  send: (message) => api.post('/api/chat', { message }, { anonymous: true }),
  history: () =>
    api.get(`/api/chat/history?sessionKey=${encodeURIComponent(getSessionKey())}`, {
      anonymous: true,
    }),
};

/* ==================== 我的预约（游客以 sessionKey 归属，登录顾客以账号归属） ==================== */

export const myBookingApi = {
  list: () => api.get('/api/bookings/my', { anonymous: true }),
  cancel: (bookingNo, reason) =>
    api.post(`/api/bookings/${bookingNo}/cancel`, { reason }, { anonymous: true }),
  reschedule: (bookingNo, newSlotId) =>
    api.post(`/api/bookings/${bookingNo}/reschedule`, { newSlotId }, { anonymous: true }),
};

/* ==================== 开店向导与店铺配置（老板） ==================== */

export const setupApi = {
  status: () => api.get('/api/setup/status', { anonymous: true }),
  wizard: (payload) => api.post('/api/setup/wizard', payload),
};

export const shopApi = {
  get: () => api.get('/api/shop'),
  update: (payload) => api.put('/api/shop', payload),
};

/* ==================== AI 客服密钥（老板） ==================== */

export const aiKeyApi = {
  status: () => api.get('/api/settings/ai-key'),
  save: (key) => api.put('/api/settings/ai-key', { key }),
  clear: () => api.delete('/api/settings/ai-key'),
};

/* ==================== 服务项（老板） ==================== */

export const serviceItemApi = {
  list: () => api.get('/api/service-items'),
  templates: () => api.get('/api/service-items/templates'),
  create: (payload) => api.post('/api/service-items', payload),
  update: (id, payload) => api.put(`/api/service-items/${id}`, payload),
  changeStatus: (id, status) => api.post(`/api/service-items/${id}/status`, { status }),
  remove: (id) => api.delete(`/api/service-items/${id}`),
};

/* ==================== 档期（老板/店员） ==================== */

export const slotApi = {
  calendar: (year, month) => api.get(`/api/slots/calendar?year=${year}&month=${month}`),
  day: (date) => api.get(`/api/slots/day?date=${date}`),
  dayClosePreview: (date) => api.get(`/api/slots/day-close/preview?date=${date}`),
  dayClose: (date, closed) => api.post('/api/slots/day-close', { date, closed }),
  updateCapacity: (slotId, capacity) => api.put(`/api/slots/${slotId}/capacity`, { capacity }),
  generate: () => api.post('/api/slots/generate'),
};

/* ==================== 预约管理（老板/店员） ==================== */

export const bookingApi = {
  today: (date) => api.get(`/api/bookings/today${date ? `?date=${date}` : ''}`),
  upcomingSummary: () => api.get('/api/bookings/upcoming-summary'),
  checkin: (bookingNo) => api.post(`/api/bookings/${bookingNo}/checkin`),
  noShow: (bookingNo) => api.post(`/api/bookings/${bookingNo}/no-show`),
};

/* ==================== 转人工工单（老板/店员） ==================== */

export const escalationApi = {
  list: (status, page = 1, size = 20) => {
    const q = status ? `&status=${status}` : '';
    return api.get(`/api/escalations?page=${page}&size=${size}${q}`);
  },
  detail: (id) => api.get(`/api/escalations/${id}`),
  claim: (id) => api.post(`/api/escalations/${id}/claim`),
  reply: (id, content) => api.post(`/api/escalations/${id}/reply`, { content }),
  resolve: (id, note) => api.post(`/api/escalations/${id}/resolve`, { note }),
  adoptAsKnowledge: (id, question, answer) =>
    api.post(`/api/escalations/${id}/adopt-as-knowledge`, { question, answer }),
};

/* ==================== 知识库（老板） ==================== */

export const knowledgeApi = {
  list: (keyword, status) => {
    const params = new URLSearchParams();
    if (keyword) params.set('keyword', keyword);
    if (status) params.set('status', status);
    const q = params.toString();
    return api.get(`/api/knowledge${q ? `?${q}` : ''}`);
  },
  templates: () => api.get('/api/knowledge/templates'),
  create: (payload) => api.post('/api/knowledge', payload),
  update: (id, payload) => api.put(`/api/knowledge/${id}`, payload),
  toggleStatus: (id, status) => api.post(`/api/knowledge/${id}/status`, { status }),
  remove: (id) => api.delete(`/api/knowledge/${id}`),
  importTemplates: (industry, selected) =>
    api.post('/api/knowledge/import', { industry, selected }),
};

/* ==================== 看板（老板/店员） ==================== */

export const dashboardApi = {
  get: (date) => api.get(`/api/dashboard${date ? `?date=${date}` : ''}`),
};

/* ==================== 导出（老板） ==================== */

export const exportApi = {
  bookingsCsv: (from, to) =>
    api.download(`/api/export/bookings.csv?from=${from}&to=${to}`,
      `bookings-${from}-${to}.csv`),
  configJson: () => api.download('/api/export/config.json', 'shop-config.json'),
};
