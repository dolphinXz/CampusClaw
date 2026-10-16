<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, ApiError } from '../api.js'
import { renderMarkdown } from '../markdown.js'
import { pushToast } from '../toast.js'

// ---- 智能体选择 ----
const agents = ref([])
const selectedAgentId = ref(null)
const agentMenuOpen = ref(false)
const agentDropdownRef = ref(null)

const enabledAgents = computed(() => agents.value.filter((a) => a.enabled))
const selectedAgent = computed(() =>
  enabledAgents.value.find((a) => a.id === selectedAgentId.value))

// ---- 会话列表 ----
const sessions = ref([])
const activeSessionId = ref(null)
const messages = ref([]) // {role, content, citations, basis, skill, streaming}
const listLoading = ref(false)

// ---- 发送 ----
const question = ref('')
const sending = ref(false)
const streamError = ref('')
let abortController = null

const activeSession = computed(() =>
  sessions.value.find((s) => s.id === activeSessionId.value))

// 会话一旦产生消息就锁定了智能体（服务端语义）：下拉只在「发送中」或「当前会话已提问」时禁用
const agentPickerLocked = computed(() => {
  if (sending.value) return true
  const active = activeSession.value
  return !!active && (active.messageCount > 0 || messages.value.length > 0)
})

onMounted(() => {
  loadInitial()
  document.addEventListener('mousedown', closeAgentMenuOnOutside)
  document.addEventListener('keydown', closeAgentMenuOnEscape)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', closeAgentMenuOnOutside)
  document.removeEventListener('keydown', closeAgentMenuOnEscape)
})

async function loadInitial() {
  await Promise.all([loadAgents(), loadSessions()])
}

async function loadAgents() {
  try {
    agents.value = await api.listAgents()
    // 只保留已启用（服务端对学生已过滤，这里兜底）
    if (!selectedAgentId.value && enabledAgents.value.length) {
      selectedAgentId.value = enabledAgents.value[0].id
    }
  } catch (error) {
    pushToast(error instanceof ApiError && error.status === 401 ? '请先登录' : '智能体加载失败', 'error')
  }
}

// ---- 自定义下拉（原生 select 的展开菜单由系统渲染，无法与设计体系统一） ----
function toggleAgentMenu() {
  if (agentPickerLocked.value) return
  agentMenuOpen.value = !agentMenuOpen.value
}

function pickAgent(id) {
  agentMenuOpen.value = false
  if (selectedAgentId.value === id) return
  selectedAgentId.value = id
  onAgentChange()
}

function closeAgentMenuOnOutside(event) {
  if (agentMenuOpen.value && agentDropdownRef.value && !agentDropdownRef.value.contains(event.target)) {
    agentMenuOpen.value = false
  }
}

function closeAgentMenuOnEscape(event) {
  if (event.key === 'Escape') agentMenuOpen.value = false
}

async function loadSessions() {
  listLoading.value = true
  try {
    sessions.value = await api.listSessions()
  } catch (error) {
    pushToast(error instanceof ApiError && error.status === 401 ? '请先登录' : '会话列表加载失败', 'error')
  } finally {
    listLoading.value = false
  }
}

async function newSession() {
  if (!selectedAgentId.value) {
    pushToast('本班暂无可用智能体，请联系老师', 'error')
    return
  }
  // 已激活一个尚未提问的空会话：不重复创建（避免连点产生多个空会话）
  const active = activeSession.value
  if (active && messages.value.length === 0 && active.messageCount === 0) {
    return
  }
  try {
    const session = await api.createSession(selectedAgentId.value)
    sessions.value.unshift({ ...session })
    activeSessionId.value = session.id
    messages.value = []
    streamError.value = ''
  } catch (error) {
    // 404：智能体被停用或不存在——刷新列表后提示
    pushToast(error instanceof ApiError ? error.message : '新建会话失败', 'error')
    if (error instanceof ApiError && error.status === 404) loadAgents()
  }
}

// 切换智能体：会话锁定智能体，未提问的空会话直接弃用（下次发送按新智能体建会话）
function onAgentChange() {
  if (messages.value.length > 0) {
    return // 下拉此时本应禁用，兜底不处理
  }
  activeSessionId.value = null
  streamError.value = ''
}

async function openSession(id) {
  activeSessionId.value = id
  streamError.value = ''
  try {
    const detail = await api.getSession(id)
    messages.value = detail.messages.map((m) => ({
      role: m.role,
      content: m.content,
      citations: m.citations || [],
      basis: m.basis,
      skill: m.skillName ? { name: m.skillName } : null,
      streaming: false,
    }))
    scrollToBottom()
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      pushToast('会话不存在或无权访问', 'error')
      sessions.value = sessions.value.filter((s) => s.id !== id)
      activeSessionId.value = null
      messages.value = []
    } else {
      pushToast('会话加载失败', 'error')
    }
  }
}

async function send() {
  const q = question.value.trim()
  if (!q || sending.value) return
  streamError.value = ''

  // 没有会话时先建一个（锁定当前选择的智能体）
  if (!activeSessionId.value) {
    await newSession()
    if (!activeSessionId.value) return
  }

  sending.value = true
  question.value = ''
  messages.value.push({ role: 'user', content: q })
  // reactive 包装：push 后仍能通过引用驱动流式渲染（普通对象不触发视图更新）
  const assistantMsg = reactive({
    role: 'assistant', content: '', citations: [], basis: null, skill: null, streaming: true,
  })
  messages.value.push(assistantMsg)
  scrollToBottom()

  abortController = new AbortController()
  try {
    await api.streamMessage(activeSessionId.value, q, {
      signal: abortController.signal,
      // meta 最先到达：技能提示与引用先渲染，正文随后增量追加
      onMeta(data) {
        assistantMsg.skill = data.skill || null
        assistantMsg.citations = data.citations || []
        assistantMsg.basis = data.status === 'no_evidence' ? 'none' : 'found'
        scrollToBottom()
      },
      onDelta(text) {
        assistantMsg.content += text
        scrollToBottom()
      },
      onDone() {
        assistantMsg.streaming = false
        // 与服务端落库口径一致：只展示回答中实际标注 [n] 的引用
        assistantMsg.citations = citationsActuallyCited(assistantMsg.citations, assistantMsg.content)
        // 会话标题/条数可能刚更新：后台静默刷新列表
        refreshSessionsQuiet()
      },
      onError(message) {
        assistantMsg.streaming = false
        // 部分文本已落库时服务端会先发 done 再发 error：保留已产生内容
        if (!assistantMsg.content) {
          const index = messages.value.lastIndexOf(assistantMsg)
          if (index !== -1) messages.value.splice(index, 1)
        }
        streamError.value = message || '问答服务暂不可用'
      },
    })
    // 流结束但没收到 done：按中断处理
    if (assistantMsg.streaming) {
      assistantMsg.streaming = false
    }
  } catch (error) {
    assistantMsg.streaming = false
    if (error?.name === 'AbortError') {
      // 用户主动停止：学生消息服务端已落库、已产生内容也会保存——
      // 本地保留现状不回滚，静默刷新列表对齐条数（完整内容刷新会话后可见）
      streamError.value = ''
      refreshSessionsQuiet()
    } else {
      // 网络/接口失败：移除未完成的助手占位与本轮提问（服务端可能未收到）
      const last = messages.value[messages.value.length - 1]
      if (last === assistantMsg) messages.value.pop()
      const userMsg = messages.value[messages.value.length - 1]
      if (userMsg && userMsg.role === 'user' && userMsg.content === q) messages.value.pop()
      streamError.value = error instanceof ApiError ? error.message : '问答失败'
    }
  } finally {
    sending.value = false
    abortController = null
    scrollToBottom()
  }
}

async function refreshSessionsQuiet() {
  try {
    sessions.value = await api.listSessions()
  } catch { /* 静默刷新失败不影响当前对话 */ }
}

function stop() {
  abortController?.abort()
}

// 与后端 citationsActuallyCited 口径一致：标注了 [n] 时只保留被引用的条目
function citationsActuallyCited(citations, content) {
  if (!citations?.length || !content) return citations || []
  const used = new Set()
  for (const match of content.matchAll(/\[(\d{1,2})\]/g)) used.add(Number(match[1]))
  if (!used.size) return citations
  const filtered = citations.filter((c) => used.has(c.refNumber))
  return filtered.length ? filtered : citations
}

// 带认证打开原材料（浏览器直跳 /content 带不上 Authorization 头）
async function openMaterial(materialId) {
  try {
    await api.openMaterial(materialId)
  } catch {
    pushToast('打开原材料失败', 'error')
  }
}

function scrollToBottom() {
  nextTick(() => {
    const box = document.querySelector('.chat-scroll')
    if (box) box.scrollTop = box.scrollHeight
  })
}
</script>

<template>
  <section class="hero">
    <div>
      <div class="hero-overline">SOLVING ASSISTANT</div>
      <h1>解题助手<span class="star">✳</span></h1>
      <p class="hero-sub">选定智能体后连续提问；回答在绑定材料内检索，引用可追溯到原文。</p>
    </div>
    <div class="hero-action agent-picker">
      <span class="muted">智能体</span>
      <div ref="agentDropdownRef" class="agent-dropdown">
        <button
          type="button"
          class="agent-trigger"
          :disabled="agentPickerLocked"
          :aria-expanded="agentMenuOpen"
          aria-haspopup="listbox"
          @click="toggleAgentMenu"
        >
          <span class="agent-trigger-name">
            {{ selectedAgent ? selectedAgent.name : '本班暂无可用智能体' }}
          </span>
          <svg class="agent-trigger-caret" width="12" height="12" viewBox="0 0 12 12" fill="none"
               stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"
               :class="{ open: agentMenuOpen }">
            <path d="M2.5 4.5 6 8l3.5-3.5" />
          </svg>
        </button>
        <ul v-if="agentMenuOpen" class="agent-menu" role="listbox">
          <li v-for="agent in enabledAgents" :key="agent.id">
            <button
              type="button"
              class="agent-option"
              :class="{ active: agent.id === selectedAgentId }"
              role="option"
              :aria-selected="agent.id === selectedAgentId"
              @click="pickAgent(agent.id)"
            >
              {{ agent.name }}
            </button>
          </li>
        </ul>
      </div>
      <button class="btn btn-primary" type="button" :disabled="!selectedAgentId" @click="newSession">
        ＋ 新会话
      </button>
    </div>
  </section>

  <section class="card assistant-layout">
    <!-- 会话列表 -->
    <aside class="session-list">
      <p v-if="listLoading" class="muted">加载中…</p>
      <p v-else-if="!sessions.length" class="muted">还没有会话，点击「新会话」开始提问</p>
      <button
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ active: session.id === activeSessionId }"
        type="button"
        @click="openSession(session.id)"
      >
        <span class="session-title">{{ session.title }}</span>
        <span class="session-count">{{ session.messageCount }} 条</span>
      </button>
    </aside>

    <!-- 聊天区 -->
    <div class="chat-pane">
      <div class="chat-scroll">
        <p v-if="!messages.length" class="chat-empty">
          在左侧新建会话，向本班知识库提问。<br />
          例如：分数加减法为什么要先通分？
        </p>
        <div v-for="(message, i) in messages" :key="i" class="chat-bubble" :class="message.role">
          <div class="chat-role">{{ message.role === 'user' ? '我' : '助手' }}</div>
          <!-- 命中技能提示：随 meta 先于正文显示 -->
          <div v-if="message.role === 'assistant' && message.skill" class="skill-hint">
            <span class="skill-hint-label">技能</span>
            {{ message.skill.name }}
          </div>
          <!-- 用户输入按纯文本；助手回答渲染 Markdown + 公式（DOMPurify 已净化） -->
          <p v-if="message.role === 'user'" class="chat-text">{{ message.content }}</p>
          <div v-else class="chat-text chat-markdown" v-html="renderMarkdown(message.content)"></div>
          <span v-if="message.streaming" class="typing-cursor">▍</span>
          <!-- meta 先于正文到达：引用与提示等正文开始输出后再显示，避免「先出引用后出回答」 -->
          <div v-if="message.content && message.basis === 'none'" class="chat-none">本班资料中未找到相关内容，回答仅供参考</div>
          <div v-if="message.content && message.citations && message.citations.length" class="citation-list">
            <p class="citation-label">引用来源</p>
            <div v-for="(citation, ci) in message.citations" :key="citation.chunkId" class="citation-item">
              <span class="citation-num">[{{ citation.refNumber || ci + 1 }}]</span>
              <div class="citation-body">
                <div class="citation-head">
                  <span class="hit-title">{{ citation.materialTitle }}</span>
                  <span class="muted" style="font-size: 12px">切片 #{{ citation.chunkIndex }}</span>
                  <a class="hit-open" href="#" title="打开原材料" @click.prevent="openMaterial(citation.materialId)">
                    打开原材料
                  </a>
                </div>
                <div class="hit-excerpt chat-markdown" v-html="renderMarkdown(citation.excerpt)"></div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <p v-if="streamError" class="error">{{ streamError }}</p>

      <form class="ask-row" @submit.prevent="send">
        <input
          v-model="question"
          class="ask-input"
          type="text"
          placeholder="向本班知识库提问，例如：异分母分数怎么相加？"
          :disabled="sending"
        />
        <button v-if="!sending" class="btn btn-primary" type="submit" :disabled="!question.trim()">发送</button>
        <button v-else class="btn btn-ghost" type="button" @click="stop">停止</button>
      </form>
    </div>
  </section>
</template>

<style scoped>
.assistant-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 20px;
  /* 限高：两栏内部滚动，页面本身不被会话列表撑长 */
  height: calc(100vh - 220px);
  min-height: 420px;
  max-height: 760px;
}
.agent-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
/* 自定义下拉：按钮与输入框同边框/圆角/焦点风格，菜单用设计令牌渲染 */
.agent-dropdown { position: relative; }
.agent-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 150px;
  max-width: 220px;
  padding: 9px 12px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.agent-trigger:hover:not(:disabled) { border-color: var(--brand); }
.agent-trigger:focus-visible {
  outline: none;
  border-color: var(--brand);
  box-shadow: var(--ring);
}
.agent-trigger:disabled { opacity: 0.55; cursor: not-allowed; }
.agent-trigger-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.agent-trigger-caret { color: var(--muted); flex-shrink: 0; transition: transform 0.15s ease; }
.agent-trigger-caret.open { transform: rotate(180deg); }

.agent-menu {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 30;
  min-width: 100%;
  margin: 0;
  padding: 6px;
  list-style: none;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-md);
}
.agent-option {
  width: 100%;
  text-align: left;
  padding: 9px 12px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--ink);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.agent-option:hover { background: var(--surface-2); }
.agent-option.active {
  background: var(--brand-soft);
  color: var(--brand-strong);
  font-weight: 600;
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-right: 1px solid var(--border, rgba(0, 0, 0, 0.08));
  padding-right: 14px;
  overflow-y: auto;
  min-height: 0;
}
.session-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-size: 13px;
}
.session-item:hover { background: rgba(0, 0, 0, 0.04); }
.session-item.active {
  background: rgba(139, 30, 45, 0.08);
  border-color: rgba(139, 30, 45, 0.25);
}
.session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-count { font-size: 11px; opacity: 0.6; flex-shrink: 0; }

.chat-pane { display: flex; flex-direction: column; gap: 12px; min-height: 0; }
.chat-scroll {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 4px;
  min-height: 0;
}
.chat-empty { opacity: 0.6; font-size: 13px; line-height: 1.8; padding: 24px 0; }

.chat-bubble {
  max-width: 86%;
  border-radius: 12px;
  padding: 10px 14px;
}
/* 引用摘录只作出处提示：最多两行（旧数据里存的长摘录也被截断），不喧宾夺主 */
.chat-markdown.hit-excerpt {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.chat-bubble.user {
  align-self: flex-end;
  background: rgba(139, 30, 45, 0.1);
}
.chat-bubble.assistant {
  align-self: flex-start;
  background: rgba(0, 0, 0, 0.04);
}
.chat-role { font-size: 11px; opacity: 0.55; margin-bottom: 4px; }
.skill-hint {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  opacity: 0.8;
  margin-bottom: 6px;
}
.skill-hint-label {
  font-size: 10px;
  letter-spacing: 0.06em;
  border: 1px solid rgba(139, 30, 45, 0.35);
  border-radius: 999px;
  padding: 1px 7px;
}
.chat-text { font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; margin: 0; }
/* Markdown 渲染内容：覆盖默认标签间距，公式允许横向滚动 */
.chat-markdown {
  white-space: normal;
  font-size: 14px;
  line-height: 1.75;
  word-break: break-word;
}
.chat-markdown :deep(p) { margin: 0 0 8px; }
.chat-markdown :deep(p:last-child) { margin-bottom: 0; }
.chat-markdown :deep(ul),
.chat-markdown :deep(ol) { margin: 6px 0; padding-left: 22px; }
.chat-markdown :deep(h1),
.chat-markdown :deep(h2),
.chat-markdown :deep(h3),
.chat-markdown :deep(h4) { font-size: 15px; margin: 10px 0 6px; }
.chat-markdown :deep(code) {
  background: rgba(0, 0, 0, 0.06);
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 13px;
}
.chat-markdown :deep(pre) {
  background: rgba(0, 0, 0, 0.05);
  border-radius: 8px;
  padding: 10px 12px;
  overflow-x: auto;
}
.chat-markdown :deep(pre code) { background: none; padding: 0; }
.chat-markdown :deep(table) { border-collapse: collapse; margin: 8px 0; }
.chat-markdown :deep(th),
.chat-markdown :deep(td) { border: 1px solid rgba(0, 0, 0, 0.12); padding: 4px 8px; font-size: 13px; }
.chat-markdown :deep(.katex-display) {
  overflow-x: auto;
  overflow-y: hidden;
  padding: 4px 0;
}
.chat-markdown :deep(blockquote) {
  border-left: 3px solid rgba(139, 30, 45, 0.35);
  margin: 8px 0;
  padding: 2px 12px;
  opacity: 0.85;
}
.chat-none { margin-top: 6px; font-size: 12px; opacity: 0.65; }
.typing-cursor { animation: blink 1s step-end infinite; }
@keyframes blink { 50% { opacity: 0; } }

.ask-row { display: flex; gap: 10px; }
.ask-input { flex: 1; }
</style>
