<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api.js'
import { authState } from '../auth.js'
import { uiState } from '../ui.js'
import { pushToast } from '../toast.js'
import MaterialCard from '../components/MaterialCard.vue'
import UploadDialog from '../components/UploadDialog.vue'

const materials = ref([])
const loading = ref(true)
const loadError = ref('')
const keyword = ref('')
const searchInput = ref(null)
const viewMode = ref(localStorage.getItem('cc-view-mode') === 'grid' ? 'grid' : 'list')

const isTeacher = computed(() => authState.user?.role === 'TEACHER')

const filteredMaterials = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return materials.value
  return materials.value.filter((material) =>
    [material.title, material.description, material.fileName]
      .filter(Boolean)
      .some((text) => text.toLowerCase().includes(key))
  )
})

function setViewMode(mode) {
  viewMode.value = mode
  localStorage.setItem('cc-view-mode', mode)
}

function focusSearch() {
  searchInput.value?.focus()
  searchInput.value?.select()
}

// ⌘K / Ctrl+K 唤起快速搜索（侧边栏按钮经 uiState.searchFocusTick 触发）
function onKeydown(event) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    focusSearch()
  }
}

watch(
  () => uiState.searchFocusTick,
  () => focusSearch()
)

async function loadMaterials() {
  loading.value = true
  loadError.value = ''
  try {
    materials.value = await api.listMaterials()
  } catch (error) {
    loadError.value = error.message
    pushToast('材料列表加载失败', 'error')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadMaterials()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <!-- 单根容器：<Transition> 要求单一根节点才能挂载过渡动画 -->
  <div class="page">
  <section class="hero">
    <div>
      <div class="hero-overline">CLASSROOM LIBRARY</div>
      <h1>班级资料库<span class="star">✳</span></h1>
      <p class="hero-sub">归档共享教学资料，让每一份教研经验都能被看见。</p>
    </div>
    <div class="hero-action">
      <button v-if="isTeacher" class="btn btn-primary" type="button" @click="uiState.uploadOpen = true">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        上传材料
      </button>
    </div>
  </section>

  <section class="stat-grid">
    <div class="stat-card">
      <div class="stat-icon red">
        <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
          <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
        </svg>
      </div>
      <div class="stat-body">
        <div class="label">资料数量</div>
        <div class="num">{{ materials.length }} 份</div>
      </div>
    </div>
    <div class="stat-card">
      <div class="stat-icon green">
        <svg width="21" height="21" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </div>
      <div class="stat-body">
        <div class="label">知识同步</div>
        <div class="num">已开启</div>
      </div>
    </div>
    <div class="stat-card stat-note">
      <span class="note-dot"></span>
      所有材料均限定在当前班级空间
    </div>
  </section>

  <section class="section-head">
    <div>
      <h2 class="section-title">
        全部材料
        <span class="count-chip">{{ filteredMaterials.length }}</span>
      </h2>
      <p class="section-sub">从标题与知识内容中搜索班级资料</p>
    </div>
    <div class="section-tools">
      <div class="search-box">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
        <input
          ref="searchInput"
          v-model="keyword"
          type="search"
          placeholder="搜索材料标题或内容"
        />
        <span class="kbd">⌘ K</span>
      </div>
      <div class="view-toggle">
        <button
          class="view-btn"
          :class="{ active: viewMode === 'list' }"
          type="button"
          title="列表视图"
          @click="setViewMode('list')"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <line x1="8" y1="6" x2="21" y2="6" />
            <line x1="8" y1="12" x2="21" y2="12" />
            <line x1="8" y1="18" x2="21" y2="18" />
            <line x1="3" y1="6" x2="3.01" y2="6" />
            <line x1="3" y1="12" x2="3.01" y2="12" />
            <line x1="3" y1="18" x2="3.01" y2="18" />
          </svg>
        </button>
        <button
          class="view-btn"
          :class="{ active: viewMode === 'grid' }"
          type="button"
          title="网格视图"
          @click="setViewMode('grid')"
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="7" height="7" />
            <rect x="14" y="3" width="7" height="7" />
            <rect x="14" y="14" width="7" height="7" />
            <rect x="3" y="14" width="7" height="7" />
          </svg>
        </button>
      </div>
    </div>
  </section>

  <!-- 加载骨架屏 -->
  <div v-if="loading" class="skeleton-list">
    <div v-for="n in 5" :key="n" class="skeleton-row">
      <div class="skeleton-block skeleton-line"></div>
      <div class="skeleton-lines">
        <div class="skeleton-line w40"></div>
        <div class="skeleton-line w60"></div>
      </div>
    </div>
  </div>

  <p v-else-if="loadError" class="error">{{ loadError }}</p>

  <!-- 空态 -->
  <div v-else-if="filteredMaterials.length === 0" class="empty-state">
    <div class="empty-icon">
      <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round">
        <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
      </svg>
    </div>
    <p class="empty-title">{{ keyword ? '没有匹配的材料' : '本班还没有材料' }}</p>
    <p v-if="!keyword && isTeacher">点击右上角“上传材料”，发布第一份教学资料</p>
    <p v-else-if="keyword">换个关键词试试</p>
    <p v-else>教师上传后会显示在这里</p>
  </div>

  <!-- 材料清单 -->
  <div v-else-if="viewMode === 'list'" class="material-list">
    <MaterialCard
      v-for="material in filteredMaterials"
      :key="material.id"
      :material="material"
      view="list"
    />
  </div>

  <!-- 材料卡片网格 -->
  <div v-else class="material-grid">
    <MaterialCard
      v-for="material in filteredMaterials"
      :key="material.id"
      :material="material"
      view="grid"
    />
  </div>

  <UploadDialog v-model:open="uiState.uploadOpen" @uploaded="loadMaterials" />
  </div>
</template>
