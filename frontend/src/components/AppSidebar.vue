<script setup>
import { computed } from 'vue'
import { authState } from '../auth.js'
import { uiState } from '../ui.js'

const emit = defineEmits(['logout'])

const initials = computed(() => (authState.user?.displayName || '').slice(0, 1).toUpperCase())
const isTeacher = computed(() => authState.user?.role === 'TEACHER')
const roleLabel = computed(() => (isTeacher.value ? '教师' : '学生'))

function openUpload() {
  uiState.uploadOpen = true
}

function focusSearch() {
  uiState.searchFocusTick += 1
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-brand">
      <span class="brand-mark">C</span>
      <span class="brand-text">
        <span class="name">CampusClaw</span>
        <span class="sub">教研资料空间</span>
      </span>
    </div>

    <div class="class-card">
      <div class="class-line">
        <span class="dot"></span>
        <span class="name">{{ authState.user?.className }}</span>
      </div>
      <div class="desc">当前班级</div>
    </div>

    <nav class="nav-section">
      <div class="nav-label">工作空间</div>
      <ul class="nav-list">
        <li>
          <router-link class="nav-item" :class="{ active: $route.name === 'materials' }" to="/">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
            资料库
          </router-link>
        </li>
        <li>
          <router-link class="nav-item" :class="{ active: $route.name === 'retrieval' }" to="/retrieval">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <circle cx="11" cy="11" r="8" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
            知识检索
          </router-link>
        </li>
        <li v-if="isTeacher">
          <button class="nav-item" type="button" @click="openUpload">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            上传材料
          </button>
        </li>
      </ul>
    </nav>

    <nav class="nav-section">
      <div class="nav-label">个人</div>
      <ul class="nav-list">
        <li>
          <button class="nav-item" type="button" @click="focusSearch">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <circle cx="11" cy="11" r="8" />
              <line x1="21" y1="21" x2="16.65" y2="16.65" />
            </svg>
            快速搜索
            <span class="kbd">⌘ K</span>
          </button>
        </li>
      </ul>
    </nav>

    <div class="sidebar-foot">
      <span class="avatar">{{ initials }}</span>
      <span class="user-meta">
        <span class="user-name">{{ authState.user?.displayName }}</span>
        <span class="badge" :class="isTeacher ? 'badge-teacher' : 'badge-student'">{{ roleLabel }}</span>
      </span>
      <button class="icon-btn" type="button" title="退出登录" @click="emit('logout')">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
          <polyline points="16 17 21 12 16 7" />
          <line x1="21" y1="12" x2="9" y2="12" />
        </svg>
      </button>
    </div>
  </aside>
</template>
