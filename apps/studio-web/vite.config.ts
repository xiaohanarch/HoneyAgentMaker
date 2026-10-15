import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const PLATFORM_URL = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'
const DEMO_ERP_URL = process.env.EAB_DEMO_ERP_URL ?? 'http://127.0.0.1:4100'

// 前端只和同源的 /api/、/erp/ 打交道，由开发服务器转发到后端，
// 这样演示时不需要处理跨域，docker 部署时也只需改这两个环境变量。
// 代理键用正则并带结尾斜杠，避免 /api-docs 之类的前端路由被误转发。
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: process.env.EAB_HOST ?? '127.0.0.1',
    proxy: {
      '^/api/': { target: PLATFORM_URL, rewrite: (path) => path.replace(/^\/api/, '') },
      '^/erp/': { target: DEMO_ERP_URL, rewrite: (path) => path.replace(/^\/erp/, '') },
    },
  },
})
