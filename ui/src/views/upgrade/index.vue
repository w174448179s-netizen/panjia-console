<template>
  <div class="upgrade-page">
    <el-card>
      <el-alert
        title="升级前必须先备份数据库和 JKS 密钥。升级失败支持回滚到上一已知好版本。"
        type="warning"
        :closable="false"
        style="margin-bottom: 16px"
      />

      <div class="page-header">
        <h3>产品版本库</h3>
        <el-button type="primary" :disabled="true">
          <el-icon><Upload /></el-icon>
          上传升级包
        </el-button>
      </div>

      <el-table :data="versionList" style="width: 100%" v-loading="loadingVersions">
        <el-table-column prop="version" label="版本号" width="140" />
        <el-table-column prop="releaseNote" label="发布说明" min-width="300" />
        <el-table-column prop="fileSize" label="文件大小" width="120">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="releasedAt" label="发布时间" width="170">
          <template #default="{ row }">{{ formatTime(row.releasedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button type="primary" link :disabled="true">升级到此版本</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card style="margin-top: 20px">
      <h3 style="margin-bottom: 16px">升级记录</h3>
      <el-table :data="upgradeRecords" style="width: 100%" v-loading="loadingRecords">
        <el-table-column prop="fromVersion" label="源版本" width="120" />
        <el-table-column prop="toVersion" label="目标版本" width="120" />
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column prop="startedAt" label="开始时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column prop="finishedAt" label="结束时间" width="170">
          <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get } from '@/utils/request'
import dayjs from 'dayjs'

const loadingVersions = ref(false)
const loadingRecords = ref(false)
const versionList = ref<any[]>([])
const upgradeRecords = ref<any[]>([])

async function loadVersions() {
  loadingVersions.value = true
  try {
    const res: any = await get('/v1/upgrades/versions')
    versionList.value = res.records ?? res ?? []
  } finally {
    loadingVersions.value = false
  }
}

async function loadUpgradeRecords() {
  loadingRecords.value = true
  try {
    const res: any = await get('/v1/upgrades', { pageNum: 1, pageSize: 20 })
    upgradeRecords.value = res.records ?? []
  } finally {
    loadingRecords.value = false
  }
}

function formatSize(bytes: number) {
  if (!bytes) return '-'
  if (bytes >= 1024 * 1024) {
    return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  }
  return (bytes / 1024).toFixed(1) + ' KB'
}

function statusType(status: string) {
  const map: Record<string, string> = {
    PENDING: 'info',
    IN_PROGRESS: 'warning',
    SUCCESS: 'success',
    FAILED: 'danger',
    ROLLBACK_SUCCESS: 'warning'
  }
  return map[status] || 'info'
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    PENDING: '待执行',
    IN_PROGRESS: '进行中',
    SUCCESS: '成功',
    FAILED: '失败',
    ROLLBACK_SUCCESS: '已回滚'
  }
  return map[status] || status
}

function formatTime(time: string) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(() => {
  loadVersions()
  loadUpgradeRecords()
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
