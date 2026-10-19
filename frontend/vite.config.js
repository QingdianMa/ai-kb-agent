import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    // 开发时把 /api 转发给本地 Spring Boot，避免跨域问题
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 构建产物直接输出到 Spring Boot 的静态资源目录，
    // 这样部署时还是一个 jar、一个容器，不需要额外的前端服务
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
})
