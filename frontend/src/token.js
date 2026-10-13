// 认证 token 存取：唯一读写 localStorage 认证键的地方。
// 零依赖叶子模块，避免 api ↔ auth 循环引用。
const TOKEN_KEY = 'campusclaw-token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  const had = localStorage.getItem(TOKEN_KEY) !== null
  localStorage.removeItem(TOKEN_KEY)
  if (had) {
    // 广播给 App.vue：重置登录态并引导回登录页
    window.dispatchEvent(new Event('campusclaw:unauthorized'))
  }
}
