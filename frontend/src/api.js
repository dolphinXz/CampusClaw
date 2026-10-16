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

  // ---- 提示词 / 技能（教师可写，学生只读） ----
  getPrompt() {
    return request('/api/prompt')
  },

  savePrompt(content) {
    return request('/api/prompt', {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify({ content }),
    })
  },

  listSkills() {
    return request('/api/skills')
  },

  // 技能文件：只收 rawText，服务端解析；解析失败抛 400（message 为可读原因）
  createSkill(rawText) {
    return request('/api/skills', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ rawText }),
    })
  },

  updateSkill(id, rawText) {
    return request(`/api/skills/${id}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify({ rawText }),
    })
  },

  deleteSkill(id) {
    return request(`/api/skills/${id}`, { method: 'DELETE' })
  },

  /**
   * 打开原材料内容。浏览器直接跳 /content 带不上 Authorization 头会 401，
   * 这里带 token fetch 后用 blob URL 打开。
   * window.open 必须在用户手势内同步调用（否则被弹窗拦截），故放在 fetch 之前。
   */
  async openMaterial(materialId) {
    const win = window.open('', '_blank')
    const token = getToken()
    try {
      const response = await fetch(`/api/materials/${materialId}/content`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      if (!response.ok) {
        throw new ApiError(response.status, `打开原材料失败（${response.status}）`)
      }
      const blob = await response.blob()
      const url = URL.createObjectURL(blob)
      if (win && !win.closed) {
        win.location = url
      } else {
        window.location.href = url
      }
    } catch (error) {
      if (win && !win.closed) win.close()
      throw error
    }
  },

  // ---- 智能体（教师写、学生只读；他班/未启用统一 404） ----
  listAgents() {
    return request('/api/agents')
  },

  createAgent({ name, rolePrompt }) {
    return request('/api/agents', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ name, rolePrompt }),
    })
  },

  updateAgent(id, patch) {
    return request(`/api/agents/${id}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(patch),
    })
  },

  getAgentBindings(id) {
    return request(`/api/agents/${id}/bindings`)
  },

  bindAgentSkills(id, ids) {
    return request(`/api/agents/${id}/skills`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify({ ids }),
    })
  },

  bindAgentMaterials(id, ids) {
    return request(`/api/agents/${id}/materials`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify({ ids }),
    })
  },

  // ---- 解题会话（必须选定本班已启用智能体） ----
  createSession(agentId, title) {
    return request('/api/sessions', {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(title ? { agentId, title } : { agentId }),
    })
  },

  listSessions() {
    return request('/api/sessions')
  },

  getSession(id) {
    return request(`/api/sessions/${id}`)
  },

  /**
   * 发送消息并以 SSE 流式接收回答。
   * EventSource 不能带 Authorization 头，这里用 fetch + ReadableStream 手动解析 SSE 帧。
   * 事件顺序 meta → delta × n → done（或 error）。
   * 回调：onMeta({status,skill,citations})、onDelta(text)、onDone({messageId})、onError(message)。
   */
  async streamMessage(sessionId, question, { onMeta, onDelta, onDone, onError, signal } = {}) {
    const token = getToken()
    const response = await fetch(`/api/sessions/${sessionId}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ question }),
      signal,
    })

    if (response.status === 401 && token) {
      clearToken()
    }
    if (!response.ok) {
      let message = `请求失败（${response.status}）`
      try {
        const body = await response.json()
        if (body && body.message) message = body.message
      } catch { /* 错误体非 JSON 时保留通用文案 */ }
      throw new ApiError(response.status, message)
    }

    // 逐帧解析：event: xxx\ndata: {...}\n\n
    // 收到 done/error 即结束读取（不依赖底层连接 EOF——dev 代理可能延迟关闭连接）
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let finished = false
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sep
      // SSE 帧以空行分隔（兼容 \n\n 与 \r\n\r\n）
      while ((sep = buffer.search(/\r?\n\r?\n/)) !== -1) {
        const rawFrame = buffer.slice(0, sep)
        buffer = buffer.slice(sep).replace(/^\r?\n\r?\n/, '')
        let event = 'message'
        const dataLines = []
        for (const line of rawFrame.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
        }
        if (!dataLines.length) continue
        let data = {}
        try { data = JSON.parse(dataLines.join('\n')) } catch { /* 忽略坏帧 */ }
        if (event === 'meta') onMeta?.(data)
        else if (event === 'delta' && data.text) onDelta?.(data.text)
        else if (event === 'done') { onDone?.(data); finished = true }
        else if (event === 'error') { onError?.(data.message || '问答服务暂不可用'); finished = true }
      }
      if (finished) break
    }
    if (finished) {
      // 主动取消读取，释放连接（服务端已完成本轮，无需等 EOF）
      reader.cancel().catch(() => {})
    }
  },
}

export { ApiError }
