import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // API 请求代理到后端（开发时前后端分离，但都跑本机）
    proxy: {
      '/api': {
        target: process.env.KNOWLY_API_PORT
          ? `http://localhost:${process.env.KNOWLY_API_PORT}`
          : 'http://localhost:8095',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
})
