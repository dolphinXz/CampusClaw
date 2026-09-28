import { reactive } from 'vue'

// 极简全局 Toast：push 后自动消失。
let seed = 0

export const toastState = reactive({
  items: [],
})

export function pushToast(message, type = 'success', duration = 2800) {
  const id = ++seed
  toastState.items.push({ id, message, type })
  setTimeout(() => {
    const index = toastState.items.findIndex((item) => item.id === id)
    if (index !== -1) {
      toastState.items.splice(index, 1)
    }
  }, duration)
}
