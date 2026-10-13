<script setup>
import { onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { authState, logout } from './auth.js'
import { pushToast } from './toast.js'
import { themeState, toggleTheme } from './theme.js'
import AppSidebar from './components/AppSidebar.vue'
import ToastHost from './components/ToastHost.vue'

const router = useRouter()

const isAuthed = () => authState.status === 'authenticated'

// token 失效（401）统一处理：token.js 清除 token 后广播，这里重置登录态并引导回登录页。
// 用 window 事件解耦 api ↔ router 的循环依赖；在 setup 中注册，早于任何网络请求返回。
function onUnauthorized() {
  authState.user = null
  authState.status = 'anonymous'
  const route = router.currentRoute.value
  if (route.meta.requiresAuth && route.name !== 'login') {
    router.push({ name: 'login', query: { redirect: route.fullPath } })
  }
}

window.addEventListener('campusclaw:unauthorized', onUnauthorized)
onBeforeUnmount(() => window.removeEventListener('campusclaw:unauthorized', onUnauthorized))

async function onLogout() {
  await logout()
  pushToast('已退出登录')
  await router.push({ name: 'login' })
}
</script>

<template>
  <!-- 单一 router-view：布局由外壳元素控制，避免双 router-view 与过渡动画互相干扰 -->
  <div class="app-root" :class="{ 'with-sidebar': isAuthed() }">
    <AppSidebar v-if="isAuthed()" @logout="onLogout" />
    <div class="main">
      <header v-if="isAuthed()" class="topbar">
        <nav class="breadcrumb">
          <span>工作空间</span>
          <span class="sep">/</span>
          <span class="current">资料库</span>
        </nav>
        <div class="topbar-right">
          <span class="isolation-tag">
            <span class="dot"></span>
            <span>班级数据已隔离</span>
          </span>
          <button
            class="theme-toggle"
            type="button"
            :title="themeState.mode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
            @click="toggleTheme"
          >
            <!-- 浅色模式展示月亮（点击切换到深色） -->
            <svg v-if="themeState.mode !== 'dark'" width="17" height="17" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
            <!-- 深色模式展示太阳（点击切换到浅色） -->
            <svg v-else width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="5" />
              <line x1="12" y1="1" x2="12" y2="3" />
              <line x1="12" y1="21" x2="12" y2="23" />
              <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
              <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
              <line x1="1" y1="12" x2="3" y2="12" />
              <line x1="21" y1="12" x2="23" y2="12" />
              <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
              <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
            </svg>
          </button>
        </div>
      </header>

      <div class="main-inner">
        <router-view v-slot="{ Component }">
          <!-- 不用 out-in：登录/退出时外壳布局切换会吞掉 transitionend，导致进入动画卡死 -->
          <transition name="page-fade">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>

      <footer v-if="isAuthed()" class="page-foot">
        <span>CampusClaw · 班级教研空间</span>
        <span>数据范围：{{ authState.user?.className }}</span>
      </footer>
    </div>
  </div>
  <ToastHost />
</template>
