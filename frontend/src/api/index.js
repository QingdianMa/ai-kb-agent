const ADMIN_TOKEN_KEY = 'lab_admin_token'

export function getAdminToken() {
  return localStorage.getItem(ADMIN_TOKEN_KEY) || ''
}

export function setAdminToken(token) {
  localStorage.setItem(ADMIN_TOKEN_KEY, token)
  // 同时写一份 cookie：SSE（EventSource）不能自定义请求头，
  // 只能靠 cookie 把口令带过去
  document.cookie = `kb_admin_token=${encodeURIComponent(token)};path=/;max-age=31536000`
}

export function clearAdminToken() {
  localStorage.removeItem(ADMIN_TOKEN_KEY)
  document.cookie = 'kb_admin_token=;path=/;max-age=0'
}

async function request(url, options = {}) {
  const headers = { ...(options.headers || {}) }
  const token = getAdminToken()
  if (token) {
    headers['X-Admin-Token'] = token
  }
  // FormData 的 Content-Type 必须由浏览器自动生成（要带 boundary），不能手写
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }

  const res = await fetch(url, { ...options, headers })
  if (res.status === 401) {
    throw new Error('口令不正确或缺少权限')
  }
  if (res.status === 429) {
    throw new Error('提问太频繁了，请稍等一会儿再试')
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    throw new Error(body ? body.slice(0, 200) : `HTTP ${res.status}`)
  }
  const text = await res.text()
  return text ? JSON.parse(text) : null
}

const post = (url, body) =>
  request(url, { method: 'POST', body: body instanceof FormData ? body : JSON.stringify(body ?? {}) })

export const api = {
  // 站点内容（公开）
  labContent: () => request('/api/lab/content'),
  labMembers: () => request('/api/lab/members'),
  labPublications: () => request('/api/lab/publications'),

  // 站点内容（管理员）
  saveMember: (member) => post('/api/admin/lab/members', member),
  deleteMember: (id) => request(`/api/admin/lab/members/${id}`, { method: 'DELETE' }),
  saveContent: (values) => request('/api/admin/lab/content', { method: 'PUT', body: JSON.stringify(values) }),
  savePublication: (publication) => post('/api/admin/lab/publications', publication),
  deletePublication: (id) => request(`/api/admin/lab/publications/${id}`, { method: 'DELETE' }),

  // 知识库文档（管理员）
  documents: () => request('/api/documents'),
  uploadDocument: (file) => {
    const form = new FormData()
    form.append('file', file)
    return post('/api/documents', form)
  },
  deleteDocument: (id) => request(`/api/documents/${id}`, { method: 'DELETE' }),

  // 评测
  ingestDocs: () => post('/api/eval/ingest-docs'),
  runEval: () => post('/api/eval/run')
}
