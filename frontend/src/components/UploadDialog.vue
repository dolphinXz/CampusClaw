<script setup>
import { ref } from 'vue'
import { api } from '../api.js'
import { pushToast } from '../toast.js'

const props = defineProps({
  open: { type: Boolean, required: true },
})
const emit = defineEmits(['update:open', 'uploaded'])

const title = ref('')
const description = ref('')
const file = ref(null)
const dragover = ref(false)
const uploading = ref(false)
const errorMessage = ref('')
const fileInput = ref(null)

function openPicker() {
  fileInput.value?.click()
}

function close() {
  if (uploading.value) return
  emit('update:open', false)
}

function reset() {
  title.value = ''
  description.value = ''
  file.value = null
  errorMessage.value = ''
}

function pickFile(event) {
  file.value = event.target.files[0] ?? null
}

function onDrop(event) {
  dragover.value = false
  file.value = event.dataTransfer.files[0] ?? null
}

async function submit() {
  errorMessage.value = ''
  if (!file.value) {
    errorMessage.value = '请选择要上传的文件'
    return
  }
  uploading.value = true
  try {
    await api.uploadMaterial({
      file: file.value,
      title: title.value.trim(),
      description: description.value.trim(),
    })
    pushToast(`《${title.value.trim()}》已入库`)
    reset()
    emit('update:open', false)
    emit('uploaded')
  } catch (error) {
    errorMessage.value = error.status === 403 ? '学生不能上传材料' : error.message
    pushToast(errorMessage.value, 'error')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <transition name="modal">
    <div v-if="props.open" class="modal-overlay" @click.self="close">
      <div class="modal" role="dialog" aria-label="上传材料">
        <div class="modal-head">
          <h3>上传材料</h3>
          <button class="icon-btn" type="button" title="关闭" @click="close">
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M18 6 6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div class="modal-body">
          <div class="dropzone" :class="{ dragover }"
               @click="openPicker"
               @dragover.prevent="dragover = true"
               @dragleave.prevent="dragover = false"
               @drop.prevent="onDrop">
            <div class="dz-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                   stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="17 8 12 3 7 8" />
                <line x1="12" y1="3" x2="12" y2="15" />
              </svg>
            </div>
            <div class="dz-main">点击选择文件，或拖拽到此处</div>
            <div class="dz-hint">单个文件不超过 20MB</div>
            <div v-if="file" class="dz-file">已选择：{{ file.name }}</div>
            <input ref="fileInput" type="file" hidden @click.stop @change="pickFile" />
          </div>

          <label class="field">
            <span>标题</span>
            <input v-model="title" placeholder="材料标题" />
          </label>
          <label class="field">
            <span>说明（可选）</span>
            <textarea v-model="description" placeholder="一句话说明材料内容"></textarea>
          </label>
          <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
        </div>

        <div class="modal-foot">
          <button class="btn" type="button" :disabled="uploading" @click="close">取消</button>
          <button class="btn btn-primary" type="button" :disabled="uploading" @click="submit">
            {{ uploading ? '上传中…' : '上传入库' }}
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>
