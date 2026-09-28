import { reactive } from 'vue'
import { api, ApiError } from './api.js'

// 保存当前登录用户；unknown 表示还在向后端确认登录态。
export const authState = reactive({
  status: 'unknown',
  user: null,
})

export async function fetchMe() {
  try {
    authState.user = await api.me()
    authState.status = 'authenticated'
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      authState.user = null
      authState.status = 'anonymous'
      return
    }
    throw error
  }
}

export async function login(username, password) {
  authState.user = await api.login(username, password)
  authState.status = 'authenticated'
}

export async function logout() {
  try {
    await api.logout()
  } finally {
    // 即使注销请求失败，也在本地登出，避免卡在已认证状态
    authState.user = null
    authState.status = 'anonymous'
  }
}
