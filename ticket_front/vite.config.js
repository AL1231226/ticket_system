import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
import Components from 'unplugin-vue-components/vite'
import { VantResolver } from '@vant/auto-import-resolver'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Vant 4 按需引入：模板里直接写 <van-field> 即可，组件和它自己的样式会自动带进来
    Components({ resolvers: [VantResolver()] }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 前端统一用 /api 前缀，代理到后端 8081。
      // 后端已配 server.servlet.context-path=/api，两边的 /api 是同一个前缀，
      // 所以这里【不能】再 rewrite 掉它 —— 去掉就变成后端 /user/login，直接 404。
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
