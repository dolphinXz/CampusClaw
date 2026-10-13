import { createRouter, createWebHistory } from 'vue-router'
import { authState, fetchMe } from './auth.js'
import LoginView from './views/LoginView.vue'
import MaterialsView from './views/MaterialsView.vue'
import RetrievalView from './views/RetrievalView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/', name: 'materials', component: MaterialsView, meta: { requiresAuth: true } },
    { path: '/retrieval', name: 'retrieval', component: RetrievalView, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

// 未登录访问受保护页面时统一引导到登录页。
router.beforeEach(async (to) => {
  if (authState.status === 'unknown') {
    await fetchMe()
  }
  if (to.meta.requiresAuth && authState.status !== 'authenticated') {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && authState.status === 'authenticated') {
    return { name: 'materials' }
  }
  return true
})

export default router
