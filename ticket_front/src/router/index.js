import { createRouter, createWebHistory } from 'vue-router'

import { TOKEN_KEY } from '@/utils/request'

const routes = [
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
    path: '/',
    component: () => import('@/views/TabLayout.vue'),
    children: [
      { path: '', redirect: '/home' },
      {
        path: 'home',
        name: 'home',
        component: () => import('@/views/home.vue'),
        // 浏览演出不需要登录，和拦截器白名单保持一致
        meta: { public: true },
      },
      {
        path: 'wallet',
        name: 'wallet',
        component: () => import('@/views/wallet.vue'),
      },
      {
        path: 'mine',
        name: 'mine',
        component: () => import('@/views/mine.vue'),
      },
    ],
  },
  {
    path: '/show/:id',
    name: 'show-detail',
    component: () => import('@/views/show-detail.vue'),
    meta: { public: true },
  },
  {
    path: '/seckill/:tierId',
    name: 'seckill',
    component: () => import('@/views/seckill.vue'),
  },
  {
    path: '/waitlist/:tierId',
    name: 'waitlist-join',
    component: () => import('@/views/waitlist-join.vue'),
  },
  {
    path: '/waitlists',
    name: 'waitlists',
    component: () => import('@/views/waitlists.vue'),
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: () => import('@/views/notifications.vue'),
  },
  {
    path: '/queue/:orderId',
    name: 'queue',
    component: () => import('@/views/queue.vue'),
  },
  {
    path: '/order/:orderId',
    name: 'order-detail',
    component: () => import('@/views/order-detail.vue'),
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('@/views/orders.vue'),
  },
  {
    path: '/attendees',
    name: 'attendees',
    component: () => import('@/views/attendees.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

// 全局前置守卫：每次跳转前都会跑一遍，拦截掉不该进的页面。
// 返回值决定放行还是改道——return true 放行，return { name: 'xx' } 就是跳去别处。
router.beforeEach((to) => {
  const hasToken = Boolean(localStorage.getItem(TOKEN_KEY))

  if (to.meta.guestOnly && hasToken) {
    return { name: 'home' }
  }
  // 公开页（首页、演出详情）不登录也能看；其余页面没有 token 就回登录页
  if (!to.meta.public && !to.meta.guestOnly && !hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
