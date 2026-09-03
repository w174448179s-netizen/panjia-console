<template>
  <el-container class="main-layout">
    <!-- 侧边栏 -->
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <el-icon size="28"><Key /></el-icon>
        <span class="logo-text">盘家智管</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#1f2937"
        text-color="#9ca3af"
        active-text-color="#ffffff"
      >
        <el-menu-item
          v-for="item in menuItems"
          :key="item.path"
          :index="item.path"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 主内容区 -->
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="page-title">{{ currentPageTitle }}</span>
        </div>
        <div class="header-right">
          <el-tag type="info" size="small">运营控制台</el-tag>
        </div>
      </el-header>

      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 菜单项 */
const menuItems = [
  { path: '/dashboard', title: '看板概览', icon: 'DataBoard' },
  { path: '/customer', title: '客户管理', icon: 'User' },
  { path: '/license', title: '授权管理', icon: 'Key' },
  { path: '/heartbeat', title: '心跳监控', icon: 'Connection' },
  { path: '/alert', title: '告警中心', icon: 'Bell' },
  { path: '/upgrade', title: '升级管理', icon: 'Upload' },
  { path: '/backup', title: '备份管理', icon: 'FolderOpened' }
]

/** 当前激活的菜单 */
const activeMenu = computed(() => route.path)

/** 当前页面标题 */
const currentPageTitle = computed(() => {
  const item = menuItems.find(m => m.path === route.path)
  return item?.title || ''
})
</script>

<style scoped>
.main-layout {
  height: 100vh;
}

.sidebar {
  background-color: #1f2937;
  color: #fff;
  overflow-y: auto;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px;
  border-bottom: 1px solid #374151;
  margin-bottom: 10px;
}

.logo-text {
  font-size: 18px;
  font-weight: 600;
  color: #fff;
}

:deep(.el-menu) {
  border-right: none;
}

:deep(.el-menu-item) {
  height: 48px;
  line-height: 48px;
}

:deep(.el-menu-item.is-active) {
  background-color: #111827;
}

.header {
  background-color: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #111827;
}

.main-content {
  background-color: #f3f4f6;
  padding: 20px;
  overflow-y: auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
