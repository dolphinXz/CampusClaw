import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时通过代理把 /api 与 /health 转发到本地后端；认证经 Authorization 头携带，同源代理路径无需 Cookie。
export default defineConfig({
  plugins: [vue()],
  cacheDir: '../.tmp/vite-cache',
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/health': 'http://localhost:8080',
      // Swagger UI 与 OpenAPI 描述（SSE 由 vite 默认流式透传）
      '/swagger-ui': 'http://localhost:8080',
      '/v3/api-docs': 'http://localhost:8080',
    },
  },
})
