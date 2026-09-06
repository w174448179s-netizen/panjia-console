import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由配置
 *
 * ★ V1.3：无登录页、无路由守卫——前端直接调用管理 API，
 * 前提是用户已处于 VPN/白名单网络。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '看板概览', icon: 'DataBoard' }
      },
      {
        path: 'customer',
        name: 'Customer',
        component: () => import('@/views/customer/index.vue'),
        meta: { title: '客户管理', icon: 'User' }
      },
      {
        path: 'license',
        name: 'License',
        component: () => import('@/views/license/index.vue'),
        meta: { title: '授权管理', icon: 'Key' }
      },
      {
        path: 'app-client',
        name: 'AppClient',
        component: () => import('@/views/app-client/index.vue'),
        meta: { title: '应用端', icon: 'Monitor' }
      },
      {
        path: 'heartbeat',
        name: 'Heartbeat',
        component: () => import('@/views/heartbeat/index.vue'),
        meta: { title: '心跳监控', icon: 'Connection' }
      },
      {
        path: 'alert',
        name: 'Alert',
        component: () => import('@/views/alert/index.vue'),
        meta: { title: '告警中心', icon: 'Bell' }
      },
      {
        path: 'upgrade',
        name: 'Upgrade',
        component: () => import('@/views/upgrade/index.vue'),
        meta: { title: '升级管理', icon: 'Upload' }
      },
      {
        path: 'backup',
        name: 'Backup',
        component: () => import('@/views/backup/index.vue'),
        meta: { title: '备份管理', icon: 'FolderOpened' }
      }
    ]
  }
]

const router = createRouter({
  // BASE_URL 跟随 vite base（/console/），保证部署在子路径时路由正常
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
