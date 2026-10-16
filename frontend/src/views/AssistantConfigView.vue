<script setup>
import { onMounted, ref } from 'vue'
import { api, ApiError } from '../api.js'
import { pushToast } from '../toast.js'

// ---- 智能体 ----
const agents = ref([])
const selectedAgentId = ref(null)
const agentSaving = ref(false)

// 新建/编辑智能体
const showAgentForm = ref(false)
const editingAgent = ref(null) // null=新建
const agentForm = ref({ name: '', rolePrompt: '' })

// ---- 绑定 ----
const skills = ref([])
const materials = ref([])
const bindings = ref({ skills: [], materials: [] })
const bindingSaving = ref(false)

// ---- 技能文件 ----
const showSkillEditor = ref(false)
const editingSkill = ref(null) // null=新建
const skillRawText = ref('')
const skillSaving = ref(false)
const skillError = ref('')

const selectedAgent = () => agents.value.find((a) => a.id === selectedAgentId.value)

onMounted(loadInitial)

async function loadInitial() {
  try {
    const [agentData, skillData, materialData] = await Promise.all([
      api.listAgents(), api.listSkills(), api.listMaterials(),
    ])
    agents.value = agentData
    skills.value = skillData
    materials.value = materialData
    if (agents.value.length) selectAgent(agents.value[0].id)
  } catch {
    pushToast('配置加载失败', 'error')
  }
}

async function selectAgent(id) {
  selectedAgentId.value = id
  try {
    bindings.value = await api.getAgentBindings(id)
  } catch (error) {
    pushToast(error instanceof ApiError ? error.message : '绑定加载失败', 'error')
  }
}

// ---- 智能体 CRUD ----

function openCreateAgent() {
  editingAgent.value = null
  agentForm.value = { name: '', rolePrompt: '' }
  showAgentForm.value = true
}

function openEditAgent(agent) {
  editingAgent.value = agent
  agentForm.value = { name: agent.name, rolePrompt: agent.rolePrompt || '' }
  showAgentForm.value = true
}

async function saveAgent() {
  const form = agentForm.value
  if (!form.name.trim() || agentSaving.value) return
  agentSaving.value = true
  try {
    if (editingAgent.value) {
      const updated = await api.updateAgent(editingAgent.value.id, {
        name: form.name.trim(),
        rolePrompt: form.rolePrompt,
      })
      Object.assign(editingAgent.value, updated)
      pushToast('智能体已更新，下一轮对话生效')
    } else {
      const created = await api.createAgent({ name: form.name.trim(), rolePrompt: form.rolePrompt })
      agents.value.push(created)
      selectAgent(created.id)
      pushToast(`智能体「${created.name}」已创建`)
    }
    showAgentForm.value = false
  } catch (error) {
    pushToast(error instanceof ApiError ? error.message : '保存失败', 'error')
  } finally {
    agentSaving.value = false
  }
}

async function toggleAgent(agent) {
  try {
    const updated = await api.updateAgent(agent.id, { enabled: !agent.enabled })
    Object.assign(agent, updated)
    pushToast(updated.enabled ? '智能体已启用' : '智能体已停用（已有会话仍可继续使用）')
  } catch (error) {
    pushToast(error instanceof ApiError ? error.message : '操作失败', 'error')
  }
}

// ---- 绑定 ----

function isSkillBound(id) {
  return bindings.value.skills.some((s) => s.id === id)
}

function isMaterialBound(id) {
  return bindings.value.materials.some((m) => m.id === id)
}

async function toggleSkillBinding(skill) {
  if (!selectedAgentId.value || bindingSaving.value) return
  bindingSaving.value = true
  const current = bindings.value.skills.map((s) => s.id)
  const next = isSkillBound(skill.id)
    ? current.filter((id) => id !== skill.id)
    : [...current, skill.id]
  try {
    await api.bindAgentSkills(selectedAgentId.value, next)
    bindings.value = await api.getAgentBindings(selectedAgentId.value)
  } catch (error) {
    pushToast(error instanceof ApiError ? error.message : '绑定失败', 'error')
  } finally {
    bindingSaving.value = false
  }
}

async function toggleMaterialBinding(material) {
  if (!selectedAgentId.value || bindingSaving.value) return
  bindingSaving.value = true
  const current = bindings.value.materials.map((m) => m.id)
  const next = isMaterialBound(material.id)
    ? current.filter((id) => id !== material.id)
    : [...current, material.id]
  try {
    await api.bindAgentMaterials(selectedAgentId.value, next)
    bindings.value = await api.getAgentBindings(selectedAgentId.value)
  } catch (error) {
    pushToast(error instanceof ApiError ? error.message : '绑定失败', 'error')
  } finally {
    bindingSaving.value = false
  }
}

// ---- 技能文件 ----

const SKILL_TEMPLATE = `名称：
适用条件：
资料：
工具：knowledge_search
启用：是

在这里写技能说明正文。`

function openCreateSkill() {
  editingSkill.value = null
  skillRawText.value = SKILL_TEMPLATE
  skillError.value = ''
  showSkillEditor.value = true
}

function openEditSkill(skill) {
  editingSkill.value = skill
  // rawText 缺失时按解析字段回拼（老数据兜底）
  skillRawText.value = skill.rawText || [
    `名称：${skill.name}`,
    `适用条件：${skill.conditions || ''}`,
    `资料：${skill.subjectScope || ''}`,
    `工具：${skill.tool || 'knowledge_search'}`,
    `启用：${skill.enabled ? '是' : '否'}`,
    '',
    skill.content || '',
  ].join('\n')
  skillError.value = ''
  showSkillEditor.value = true
}

async function saveSkill() {
  if (!skillRawText.value.trim() || skillSaving.value) return
  skillSaving.value = true
  skillError.value = ''
  try {
    if (editingSkill.value) {
      const updated = await api.updateSkill(editingSkill.value.id, skillRawText.value)
      Object.assign(editingSkill.value, updated)
      pushToast(`技能「${updated.name}」已更新`)
    } else {
      const created = await api.createSkill(skillRawText.value)
      skills.value.push(created)
      pushToast(`技能「${created.name}」已创建`)
    }
    showSkillEditor.value = false
  } catch (error) {
    // 解析失败：400 的 message 是面向教师的可读原因，直接展示在编辑器内
    skillError.value = error instanceof ApiError ? error.message : '保存失败'
  } finally {
    skillSaving.value = false
  }
}

async function deleteSkill() {
  if (!editingSkill.value || skillSaving.value) return
  if (!window.confirm(`确认删除技能「${editingSkill.value.name}」？绑定关系会一并删除。`)) return
  skillSaving.value = true
  try {
    await api.deleteSkill(editingSkill.value.id)
    skills.value = skills.value.filter((s) => s.id !== editingSkill.value.id)
    showSkillEditor.value = false
    pushToast('技能已删除')
    // 绑定回显刷新
    if (selectedAgentId.value) bindings.value = await api.getAgentBindings(selectedAgentId.value)
  } catch (error) {
    pushToast(error instanceof ApiError ? error.message : '删除失败', 'error')
  } finally {
    skillSaving.value = false
  }
}
</script>

<template>
  <section class="hero">
    <div>
      <div class="hero-overline">ASSISTANT CONFIG</div>
      <h1>智能体配置<span class="star">✳</span></h1>
      <p class="hero-sub">智能体 = 角色提示词 + 可用技能 + 可检索材料。修改后已有会话下一轮立即生效。</p>
    </div>
  </section>

  <!-- 智能体列表 -->
  <section class="card config-card">
    <div class="panel-head">
      <h2 class="panel-title"><span class="icon-chip">◆</span> 智能体</h2>
      <button class="btn btn-primary" type="button" @click="openCreateAgent">＋ 新建智能体</button>
    </div>
    <p v-if="!agents.length" class="muted">还没有智能体，点击右上角新建。</p>
    <div v-for="agent in agents" :key="agent.id" class="agent-row" :class="{ active: agent.id === selectedAgentId }">
      <button class="agent-main" type="button" @click="selectAgent(agent.id)">
        <span class="hit-title">{{ agent.name }}</span>
        <span class="status-pill" :class="{ on: agent.enabled }">{{ agent.enabled ? '已启用' : '已停用' }}</span>
        <span class="muted agent-role">{{ agent.rolePrompt ? agent.rolePrompt.slice(0, 30) : '使用默认角色' }}</span>
      </button>
      <div class="agent-actions">
        <button class="btn btn-ghost" type="button" @click="openEditAgent(agent)">编辑</button>
        <button class="btn btn-ghost" type="button" @click="toggleAgent(agent)">
          {{ agent.enabled ? '停用' : '启用' }}
        </button>
      </div>
    </div>

    <!-- 新建/编辑智能体表单 -->
    <div v-if="showAgentForm" class="config-form">
      <label>
        名称（班内唯一，最多 64 字）
        <input v-model="agentForm.name" class="config-input" type="text" maxlength="64" placeholder="例如：数学解题助手" />
      </label>
      <label>
        角色提示词（留空使用服务端默认角色）
        <textarea v-model="agentForm.rolePrompt" class="config-textarea" rows="4" maxlength="2000"
          placeholder="例如：你是本班的数学解题助手，耐心引导学生思考……"></textarea>
      </label>
      <div class="form-actions">
        <button class="btn btn-ghost" type="button" @click="showAgentForm = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="!agentForm.name.trim() || agentSaving" @click="saveAgent">
          {{ agentSaving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>
  </section>

  <!-- 绑定配置（针对选中智能体） -->
  <section v-if="selectedAgent" class="card config-card">
    <h2 class="panel-title">
      <span class="icon-chip">⚙</span>
      「{{ selectedAgent.name }}」的绑定
    </h2>
    <p class="muted" style="margin: -6px 0 14px; font-size: 13px">
      每轮问答最多路由一个已绑定且启用的技能；检索只在已绑定材料内进行。未绑定材料时回答没有知识库依据。
    </p>

    <h3 class="group-title">技能</h3>
    <p v-if="!skills.length" class="muted">还没有技能文件，在下方「技能文件」区新建。</p>
    <label v-for="skill in skills" :key="skill.id" class="check-row">
      <input
        type="checkbox"
        :checked="isSkillBound(skill.id)"
        :disabled="bindingSaving"
        @change="toggleSkillBinding(skill)"
      />
      <span class="hit-title">{{ skill.name }}</span>
      <span class="status-pill" :class="{ on: skill.enabled }">{{ skill.enabled ? '启用' : '停用' }}</span>
      <span class="muted check-desc">{{ skill.description }}</span>
    </label>

    <h3 class="group-title">材料</h3>
    <p v-if="!materials.length" class="muted">本班还没有材料。</p>
    <label v-for="material in materials" :key="material.id" class="check-row">
      <input
        type="checkbox"
        :checked="isMaterialBound(material.id)"
        :disabled="bindingSaving"
        @change="toggleMaterialBinding(material)"
      />
      <span class="hit-title">{{ material.title }}</span>
    </label>
  </section>

  <!-- 技能文件 -->
  <section class="card config-card">
    <div class="panel-head">
      <h2 class="panel-title"><span class="icon-chip">⚡</span> 技能文件</h2>
      <button class="btn btn-primary" type="button" @click="openCreateSkill">＋ 新建技能文件</button>
    </div>
    <p class="muted" style="margin: -6px 0 14px; font-size: 13px">
      单一文本文件：元数据区（名称/适用条件/资料/工具/启用）+ 空行 + 说明正文。「适用条件」是路由关键词，按逗号、顿号等切分；名称含「解题引导」的回答禁止给出最终答案。
    </p>
    <div v-if="!skills.length" class="muted">还没有技能文件。</div>
    <div v-for="skill in skills" :key="skill.id" class="skill-card">
      <div class="skill-head">
        <div>
          <span class="hit-title">{{ skill.name }}</span>
          <span class="status-pill" :class="{ on: skill.enabled }">{{ skill.enabled ? '已启用' : '已停用' }}</span>
          <span v-if="skill.conditions" class="muted skill-kw">关键词：{{ skill.conditions }}</span>
        </div>
        <button class="btn btn-ghost" type="button" @click="openEditSkill(skill)">编辑文件</button>
      </div>
      <pre class="skill-raw">{{ skill.rawText }}</pre>
    </div>

    <!-- 技能文件编辑器 -->
    <div v-if="showSkillEditor" class="config-form">
      <label>
        技能文件（元数据 + 空行 + 正文）
        <textarea v-model="skillRawText" class="config-textarea skill-editor" rows="14" spellcheck="false"></textarea>
      </label>
      <p v-if="skillError" class="error skill-error">{{ skillError }}</p>
      <div class="form-actions">
        <button v-if="editingSkill" class="btn btn-ghost skill-delete" type="button" :disabled="skillSaving" @click="deleteSkill">
          删除
        </button>
        <button class="btn btn-ghost" type="button" @click="showSkillEditor = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="!skillRawText.trim() || skillSaving" @click="saveSkill">
          {{ skillSaving ? '保存中…' : '保存技能文件' }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.config-card { display: flex; flex-direction: column; gap: 10px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.group-title { font-size: 13px; font-weight: 600; opacity: 0.75; margin: 10px 0 2px; }

.agent-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--border, rgba(0, 0, 0, 0.1));
  border-radius: 12px;
  padding: 10px 14px;
}
.agent-row.active { border-color: rgba(139, 30, 45, 0.35); background: rgba(139, 30, 45, 0.04); }
.agent-main {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  cursor: pointer;
  font-family: inherit;
  font-size: 14px;
  color: inherit;
  text-align: left;
  padding: 0;
}
.agent-role {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.agent-actions { display: flex; gap: 6px; flex-shrink: 0; }

.check-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  padding: 6px 2px;
  cursor: pointer;
}
.check-row input { accent-color: rgba(139, 30, 45, 0.8); }
.check-desc {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-form { display: flex; flex-direction: column; gap: 10px; margin-top: 6px; }
.config-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; opacity: 0.9; }
.config-input,
.config-textarea {
  border: 1px solid var(--border, rgba(0, 0, 0, 0.12));
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  background: transparent;
  color: inherit;
}
.config-textarea { resize: vertical; line-height: 1.6; }
.skill-editor {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  white-space: pre;
}
.skill-error {
  font-size: 13px;
  margin: 0;
  word-break: break-all;
}
.form-actions { display: flex; justify-content: flex-end; gap: 8px; }
.skill-delete { margin-right: auto; color: rgba(180, 40, 40, 0.9); }

.skill-card {
  border: 1px solid var(--border, rgba(0, 0, 0, 0.1));
  border-radius: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
}
.skill-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.skill-kw { font-size: 12px; }
.skill-raw {
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  opacity: 0.85;
  background: rgba(0, 0, 0, 0.03);
  border-radius: 8px;
  padding: 10px 12px;
  margin: 10px 0 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  max-height: 180px;
  overflow-y: auto;
}
.status-pill.on { background: rgba(34, 130, 80, 0.15); color: inherit; }
</style>
