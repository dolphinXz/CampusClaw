import { reactive } from 'vue'

// 跨组件共享的界面状态（侧边栏按钮打开上传模态框、⌘K 聚焦搜索框）。
export const uiState = reactive({
  uploadOpen: false,
  searchFocusTick: 0,
})
