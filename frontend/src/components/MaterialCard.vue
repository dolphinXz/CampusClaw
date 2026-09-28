<script setup>
import { computed } from 'vue'

const props = defineProps({
  material: { type: Object, required: true },
  view: { type: String, default: 'list' }, // 'list' | 'grid'
})

// 按扩展名给出图标字母与配色，让列表有文件类型区分
const fileStyle = computed(() => {
  const name = (props.material.fileName || props.material.title || '').toLowerCase()
  if (/\.(pdf)$/.test(name)) return { tone: 'red', letter: 'P' }
  if (/\.(md|markdown)$/.test(name)) return { tone: 'amber', letter: 'M' }
  if (/\.(docx?|rtf|txt)$/.test(name)) return { tone: 'blue', letter: 'T' }
  if (/\.(xlsx?|csv)$/.test(name)) return { tone: 'green', letter: 'X' }
  if (/\.(pptx?)$/.test(name)) return { tone: 'amber', letter: 'P' }
  if (/\.(png|jpe?g|gif|webp|svg)$/.test(name)) return { tone: 'purple', letter: 'I' }
  if (/\.(zip|rar|7z|tar|gz)$/.test(name)) return { tone: 'amber', letter: 'Z' }
  return { tone: 'blue', letter: 'F' }
})

const typeLabel = computed(() => {
  const name = (props.material.fileName || props.material.title || '').toLowerCase()
  const ext = name.includes('.') ? name.split('.').pop() : ''
  const map = {
    pdf: 'PDF 文档',
    md: 'MD 文档',
    markdown: 'MD 文档',
    doc: 'DOC 文档',
    docx: 'DOC 文档',
    txt: 'TXT 文档',
    xlsx: 'XLS 表格',
    xls: 'XLS 表格',
    csv: 'CSV 表格',
    ppt: 'PPT 课件',
    pptx: 'PPT 课件',
  }
  return map[ext] || '教学文件'
})

function formatDate(value) {
  if (!value) return ''
  return new Date(value).toLocaleDateString('zh-CN')
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatTime(value) {
  if (!value) return ''
  const diff = Date.now() - new Date(value).getTime()
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 7 * day) return `${Math.floor(diff / day)} 天前`
  return new Date(value).toLocaleDateString('zh-CN')
}

const contentUrl = `/api/materials/${props.material.id}/content`
</script>

<template>
  <!-- 行视图：截图风格的资料清单 -->
  <article v-if="view === 'list'" class="material-row">
    <span class="file-icon" :class="fileStyle.tone">{{ fileStyle.letter }}</span>
    <div class="row-body">
      <div class="row-title">{{ material.title }}</div>
      <div class="row-meta">
        <span>{{ typeLabel }}</span>
        <span class="sep">·</span>
        <span>{{ formatDate(material.createdAt) }}</span>
        <template v-if="material.uploadedByName">
          <span class="sep">·</span>
          <span>{{ material.uploadedByName }}</span>
        </template>
        <template v-if="material.sizeBytes">
          <span class="sep">·</span>
          <span>{{ formatSize(material.sizeBytes) }}</span>
        </template>
      </div>
    </div>
    <div class="row-side">
      <span class="status-pill">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"
             stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
        已入知识库
      </span>
      <a class="row-open" :href="contentUrl" title="打开 / 下载">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <line x1="7" y1="17" x2="17" y2="7" />
          <polyline points="7 7 17 7 17 17" />
        </svg>
      </a>
    </div>
  </article>

  <!-- 网格视图 -->
  <article v-else class="material-card">
    <div class="material-head">
      <span class="file-icon" :class="fileStyle.tone">{{ fileStyle.letter }}</span>
      <div>
        <div class="material-title">{{ material.title }}</div>
        <div class="muted" style="font-size: 12px">{{ typeLabel }}</div>
      </div>
    </div>

    <p class="material-desc">{{ material.description || '暂无说明' }}</p>

    <div class="material-meta">
      <span>{{ material.uploadedByName }}</span>
      <span>{{ formatTime(material.createdAt) }}</span>
      <span>{{ formatSize(material.sizeBytes) }}</span>
    </div>

    <div class="material-actions">
      <a class="btn btn-ghost" :href="contentUrl">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="7 10 12 15 17 10" />
          <line x1="12" y1="15" x2="12" y2="3" />
        </svg>
        下载
      </a>
    </div>
  </article>
</template>
