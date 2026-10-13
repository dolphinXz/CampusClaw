import { clearToken, getToken } from './token.js'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

async function request(path, options = {}) {
  const token = getToken()
  const response = await fetch(path, {
    ...options,
    headers: {
      ...options.headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })

  if (response.status === 401 && token && !path.startsWith('/api/auth/login')) {
    // token 过期/失效：清除并广播（登录接口的 401 属凭据错误，由登录页展示）
    clearToken()
  }

  if (response.status === 204) {
    return null
  }

  const isJson = (response.headers.get('content-type') || '').includes('application/json')
  const body = isJson ? await response.json() : await response.text()

  if (!response.ok) {
    const message = body && body.message ? body.message : `请求失败（${response.status}）`
    throw new ApiError(response.status, message)
  }

  return body
}

export const api = {
  me() {
    return request('/api/auth/me')
  },

  login(username, password) {
    return request('/api/auth/login', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ username, password }),
    })
  },

  logout() {
    return request('/api/auth/logout', { method: 'POST' })
  },

  listMaterials() {
    return request('/api/materials')
  },

  uploadMaterial({ file, title, description }) {
    const form = new FormData()
    form.append('file', file)
    form.append('title', title)
    if (description) {
      form.append('description', description)
    }
    return request('/api/materials', { method: 'POST', body: form })
  },

  retrieval({ query, mode, topK }) {
    return request('/api/retrieval', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ query, mode, topK }),
    })
  },

  ask({ question, history }) {
    return request('/api/ask', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ question, history }),
    })
  },
}

export { ApiError }
