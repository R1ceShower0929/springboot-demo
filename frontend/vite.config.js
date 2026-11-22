import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时前端跑 5173，/api 请求转发到后端 8080，避免跨域问题
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})