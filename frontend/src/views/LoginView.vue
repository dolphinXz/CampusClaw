<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../auth.js'
import { themeState, toggleTheme } from '../theme.js'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const errorMessage = ref('')
const submitting = ref(false)

const features = ['教师上传教学材料', '班级数据严格隔离', '本班材料列表即传即用']

async function onSubmit() {
  errorMessage.value = ''
  submitting.value = true
  try {
    await login(username.value.trim(), password.value)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.push(redirect)
  } catch (error) {
    errorMessage.value = error.status === 401 ? '账号或密码错误' : error.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-stage">
    <button
      class="theme-toggle login-theme"
      type="button"
      :title="themeState.mode === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
      @click="toggleTheme"
    >
      <svg v-if="themeState.mode !== 'dark'" width="17" height="17" viewBox="0 0 24 24" fill="none"
           stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
      </svg>
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
    <div class="login-card">
      <aside class="login-aside">
        <div class="logo-line">
          <span class="brand-mark">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
          </span>
          CampusClaw
        </div>
        <h1>安全的班级化<br />知识库协作</h1>
        <p class="tagline">为校园教学打造，材料只在本班流转。</p>
        <ul class="feature-list">
          <li v-for="feature in features" :key="feature">
            <span class="check">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="3"
                   stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </span>
            {{ feature }}
          </li>
        </ul>
      </aside>

      <section class="login-main">
        <h2>登录</h2>
        <p class="hint">使用教师或学生账号进入本班知识库</p>
        <form @submit.prevent="onSubmit">
          <label class="field">
            <span>账号</span>
            <input v-model="username" name="username" autocomplete="username" required placeholder="例如 teacher-a" />
          </label>
          <label class="field">
            <span>密码</span>
            <input
              v-model="password"
              name="password"
              type="password"
              autocomplete="current-password"
              required
              placeholder="请输入密码"
            />
          </label>
          <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
          <button class="btn btn-primary btn-block" type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登录' }}
          </button>
        </form>
      </section>
    </div>
  </div>
</template>
