import { reactive } from 'vue'
import { api, ApiError } from './api.js'
import { clearToken, getToken, setToken } from './token.js'

// 保存当前登录用户；unknown 表示还在向后端确认登录态。
export const authState = reactive({
  status: 'unknown',
  user: null,
})

export async function fetchMe() {
  // 本地无 token：直接判定未登录，省一次请求
  if (!getToken()) {
    authState.user = null
    authState.status = 'anonymous'
    return
  }
  try {
    authState.user = await api.me()
    authState.status = 'authenticated'
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      clearToken()
      authState.user = null
      authState.status = 'anonymous'
      return
    }
    throw error
  }
}

export async function login(username, password) {
  const body = await api.login(username, password)
  setToken(body.token)
  authState.user = body.user
  authState.status = 'authenticated'
}

export async function logout() {
  try {
    await api.logout()
  } finally {
    // 即使注销请求失败，也在本地登出，避免卡在已认证状态
    clearToken()
    authState.user = null
    authState.status = 'anonymous'
  }
}
