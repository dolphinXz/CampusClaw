const JSON_HEADERS = { 'Content-Type': 'application/json' }

class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    credentials: 'include',
    ...options,
    headers: { ...options.headers },
  })

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
}

export { ApiError }
