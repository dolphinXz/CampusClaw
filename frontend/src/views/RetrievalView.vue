<script setup>
import { ref } from 'vue'
import { api, ApiError } from '../api.js'
import { pushToast } from '../toast.js'

// ---- 检索区 ----
const query = ref('')
const mode = ref('hybrid')
const searching = ref(false)
const searchError = ref('')
const searchResult = ref(null) // { mode, basis, message, hits }

// ---- 问答区 ----
const question = ref('')
const asking = ref(false)
const askError = ref('')
const askResult = ref(null) // { answer, basis, citations }

// 带认证打开原材料（浏览器直跳 /content 带不上 Authorization 头会 401）
async function openMaterial(materialId) {
  try {
    await api.openMaterial(materialId)
  } catch {
    pushToast('打开原材料失败', 'error')
  }
}

const modeOptions = [
  { value: 'keyword', label: '关键字', hint: '只查全文索引，不依赖向量库' },
  { value: 'vector', label: '向量', hint: '语义改写也能命中' },
  { value: 'hybrid', label: '混合', hint: '两路融合排序（默认）' },
]

async function onSearch() {
  const q = query.value.trim()
  if (!q) {
    searchError.value = '请输入查询内容'
    return
  }
  searching.value = true
  searchError.value = ''
  try {
    searchResult.value = await api.retrieval({ query: q, mode: mode.value })
  } catch (error) {
    searchResult.value = null
    if (error instanceof ApiError && error.status === 503) {
      searchError.value = '检索服务暂不可用（向量库或嵌入网关未就绪），可稍后重试'
    } else if (error instanceof ApiError && error.status === 400) {
      searchError.value = error.message
    } else {
      searchError.value = error.message || '检索失败'
    }
  } finally {
    searching.value = false
  }
}

async function onAsk() {
  const q = question.value.trim()
  if (!q) {
    askError.value = '请输入问题'
    return
  }
  asking.value = true
  askError.value = ''
  try {
    askResult.value = await api.ask({ question: q })
  } catch (error) {
    askResult.value = null
    if (error instanceof ApiError && error.status === 503) {
      askError.value = '问答服务暂不可用，可稍后重试'
    } else if (error instanceof ApiError && error.status === 400) {
      askError.value = error.message
    } else {
      askError.value = error.message || '问答失败'
    }
    pushToast(askError.value, 'error')
  } finally {
    asking.value = false
  }
}

function scoreText(score) {
  if (!score) return ''
  const parts = [`第 ${score.rank} 名`]
  const d = score.detail || {}
  if (d.keyword != null) parts.push(`全文 ${d.keyword.toFixed(2)}`)
  if (d.cosine != null) parts.push(`余弦 ${d.cosine.toFixed(3)}`)
  if (d.rrf != null) parts.push(`RRF ${d.rrf.toFixed(4)}`)
  return parts.join(' · ')
}
</script>

<template>
  <section class="hero">
    <div>
      <div class="hero-overline">KNOWLEDGE RETRIEVAL</div>
      <h1>知识检索<span class="star">✳</span></h1>
      <p class="hero-sub">从本班材料切片中检索可靠依据；只有检索到依据时才生成回答。</p>
    </div>
  </section>

  <!-- 检索区 -->
  <section class="card retrieval-card">
    <h2 class="panel-title">
      <span class="icon-chip">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
      </span>
      检索材料
    </h2>

    <div class="mode-picker" role="radiogroup" aria-label="检索模式">
      <button
        v-for="option in modeOptions"
        :key="option.value"
        class="mode-option"
        :class="{ active: mode === option.value }"
        type="button"
        :title="option.hint"
        @click="mode = option.value"
      >
        {{ option.label }}
      </button>
    </div>

    <form class="ask-row" @submit.prevent="onSearch">
      <input
        v-model="query"
        class="ask-input"
        type="search"
        placeholder="输入要检索的内容，例如：分数加减法"
      />
      <button class="btn btn-primary" type="submit" :disabled="searching">
        {{ searching ? '检索中…' : '检索' }}
      </button>
    </form>

    <p v-if="searchError" class="error">{{ searchError }}</p>

    <!-- 空结果：固定无依据提示 -->
    <div v-if="searchResult && searchResult.basis === 'none'" class="empty-state">
      <p class="empty-title">资料中未找到相关内容</p>
      <p>换个说法试试，或确认相关材料已上传并重建索引</p>
    </div>

    <!-- 命中列表 -->
    <ul v-else-if="searchResult" class="hit-list">
      <li v-for="hit in searchResult.hits" :key="hit.chunkId" class="hit-card">
        <div class="hit-head">
          <span class="hit-title">{{ hit.materialTitle }}</span>
          <span class="status-pill">切片 #{{ hit.chunkIndex }}</span>
        </div>
        <p class="hit-excerpt">{{ hit.excerpt }}</p>
        <div class="hit-meta">
          <span>字符 {{ hit.charRange.start }}–{{ hit.charRange.end }}</span>
          <span class="sep">·</span>
          <span>{{ scoreText(hit.score) }}</span>
          <a class="hit-open" href="#" title="打开原材料" @click.prevent="openMaterial(hit.materialId)">
            打开原材料
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <line x1="7" y1="17" x2="17" y2="7" />
              <polyline points="7 7 17 7 17 17" />
            </svg>
          </a>
        </div>
      </li>
    </ul>
  </section>

  <!-- 问答区 -->
  <section class="card retrieval-card">
    <h2 class="panel-title">
      <span class="icon-chip">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      </span>
      有依据提问
    </h2>
    <p class="muted" style="margin: -6px 0 14px; font-size: 13px">
      先在本班材料中检索依据；没有依据时直接告知，不编造答案。
    </p>

    <form class="ask-row" @submit.prevent="onAsk">
      <input
        v-model="question"
        class="ask-input"
        type="text"
        placeholder="向本班知识库提问，例如：分数加减法为什么要先通分？"
      />
      <button class="btn btn-primary" type="submit" :disabled="asking">
        {{ asking ? '思考中…' : '提问' }}
      </button>
    </form>

    <p v-if="askError" class="error">{{ askError }}</p>

    <div v-if="askResult" class="answer-box">
      <p class="answer-text">{{ askResult.answer }}</p>
      <div v-if="askResult.citations && askResult.citations.length" class="citation-list">
        <p class="citation-label">引用来源</p>
        <div v-for="(citation, i) in askResult.citations" :key="citation.chunkId" class="citation-item">
          <span class="citation-num">[{{ i + 1 }}]</span>
          <div class="citation-body">
            <div class="citation-head">
              <span class="hit-title">{{ citation.materialTitle }}</span>
              <span class="muted" style="font-size: 12px">切片 #{{ citation.chunkIndex }}</span>
              <a class="hit-open" href="#" title="打开原材料" @click.prevent="openMaterial(citation.materialId)">
                打开原材料
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                     stroke-linecap="round" stroke-linejoin="round">
                  <line x1="7" y1="17" x2="17" y2="7" />
                  <polyline points="7 7 17 7 17 17" />
                </svg>
              </a>
            </div>
            <p class="hit-excerpt">{{ citation.excerpt }}</p>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
