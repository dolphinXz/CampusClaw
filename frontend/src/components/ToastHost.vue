<script setup>
import { toastState } from '../toast.js'

const icons = {
  success: 'M20 6 9 17l-5-5',
  error: 'M18 6 6 18M6 6l12 12',
}
</script>

<template>
  <div class="toast-host">
    <transition-group name="toast">
      <div v-for="item in toastState.items" :key="item.id" class="toast" :class="item.type">
        <svg v-if="item.type === 'success'" width="18" height="18" viewBox="0 0 24 24" fill="none"
             stroke="#16a34a" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path :d="icons.success" />
        </svg>
        <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none"
             stroke="#dc2626" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <path :d="icons.error" />
        </svg>
        <span>{{ item.message }}</span>
      </div>
    </transition-group>
  </div>
</template>
