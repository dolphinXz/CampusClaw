import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时通过代理把 /api 与 /health 转发到本地后端，登录 Cookie 天然同源。
export default defineConfig({
  plugins: [vue()],
  cacheDir: '../.tmp/vite-cache',
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/health': 'http://localhost:8080',
    },
  },
})
