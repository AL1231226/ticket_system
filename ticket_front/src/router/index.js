import { createRouter, createWebHistory } from 'vue-router'

import { TOKEN_KEY } from '@/utils/request'

const routes = [
  { path: '/', redirect: '/home' },
  {
    path: '/login',
    name: 'login',
    // () => import(...) 是懒加载：打包时登录页会被拆成单独一个 js 文件，
    // 首屏只下载首页那份，用到登录页再去取。写死的 import 会让所有页面打成一个包。
    component: () => import('@/views/login.vue'),
    // 已登录的人不该再看到登录页
    meta: { guestOnly: true },
  },
  {
    path: '/home',
    name: 'home',
    component: () => import('@/views/home.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局前置守卫：每次跳转前都会跑一遍，拦截掉不该进的页面。
// 返回值决定放行还是改道——return true 放行，return { name: 'xx' } 就是跳去别处。
router.beforeEach((to) => {
  const hasToken = Boolean(localStorage.getItem(TOKEN_KEY))

  if (to.meta.guestOnly && hasToken) {
    return { name: 'home' }
  }
  // 默认所有页面都要登录；无需登录的页自己在 meta 里标 guestOnly
  if (!to.meta.guestOnly && !hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
