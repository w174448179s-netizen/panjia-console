<template>
  <div class="heartbeat-page">
    <el-card>
      <el-alert
        title="心跳数据由客户端直连授权引擎上报，每 24 小时一次。在线状态以服务端接收时间（received_at）为权威。"
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      />
      <el-table :data="tableData" style="width: 100%" v-loading="loading">
        <el-table-column prop="customerNo" label="客户编号" width="120" />
        <el-table-column prop="instanceId" label="实例 ID" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.instanceId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="onlineStatus" label="在线状态" width="100">
          <template #default="{ row }">
            <el-tag :type="onlineStatusType(row.onlineStatus)" size="small">
              {{ onlineStatusLabel(row.onlineStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clientMode" label="运行模式" width="100">
          <template #default="{ row }">
            <el-tag :type="row.clientMode === 'NORMAL' ? 'success' : 'danger'" size="small">
              {{ row.clientMode === 'NORMAL' ? '正常' : '受限' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentStores" label="门店数" width="80" />
        <el-table-column prop="currentUsers" label="用户数" width="80" />
        <el-table-column prop="lastHeartbeatAt" label="最后心跳" width="170">
          <template #default="{ row }">
            {{ formatTime(row.lastHeartbeatAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get } from '@/utils/request'
import dayjs from 'dayjs'

const loading = ref(false)
const tableData = ref<any[]>([])

async function loadData() {
  loading.value = true
  try {
    const res: any = await get('/v1/dashboard/runtimes')
    tableData.value = res.records ?? res ?? []
  } finally {
    loading.value = false
  }
}

function onlineStatusType(status: string) {
  const map: Record<string, string> = {
    ONLINE: 'success',
    OFFLINE: 'warning',
    LOST: 'danger',
    UNKNOWN: 'info'
  }
  return map[status] || 'info'
}

function onlineStatusLabel(status: string) {
  const map: Record<string, string> = {
    ONLINE: '在线',
    OFFLINE: '离线',
    LOST: '失联',
    UNKNOWN: '未知'
  }
  return map[status] || status
}

function formatTime(time: string) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

onMounted(loadData)
</script>

<style scoped>
.mono {
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  font-size: 12px;
}
</style>
