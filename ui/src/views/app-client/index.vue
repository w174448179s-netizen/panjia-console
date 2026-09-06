<template>
  <div class="app-client-page">
    <el-card>
      <el-alert
        title="应用端 = 客户端使用授权码激活（注册）后生成的指纹绑定。换机后旧绑定标记为 INVALIDATED，新绑定为 ACTIVE。"
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      />
      <div class="page-header">
        <div class="header-left">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索授权码/客户编号"
            style="width: 280px"
            clearable
            @keyup.enter="loadData"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select v-model="statusFilter" placeholder="绑定状态" style="width: 140px; margin-left: 12px" clearable>
            <el-option label="有效" value="ACTIVE" />
            <el-option label="已失效" value="INVALIDATED" />
          </el-select>
          <el-button type="primary" @click="loadData">
            <el-icon><Search /></el-icon>
            查询
          </el-button>
        </div>
      </div>

      <el-table :data="filteredData" style="width: 100%" v-loading="loading">
        <el-table-column prop="authCode" label="授权码" width="160">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ row.authCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customerNo" label="客户编号" width="120" />
        <el-table-column prop="fpHash" label="指纹哈希" width="180">
          <template #default="{ row }">
            <el-tooltip :content="row.fpHash" placement="top">
              <span class="mono">{{ shortHash(row.fpHash) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="绑定状态" width="100">
          <template #default="{ row }">
            <el-tag :type="bindingStatusColor(row.status)" size="small">
              {{ bindingStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="onlineStatus" label="在线状态" width="100">
          <template #default="{ row }">
            <el-tag :type="onlineStatusColor(row.onlineStatus)" size="small">
              {{ onlineStatusLabel(row.onlineStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="instanceId" label="实例 ID" width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.instanceId || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="clientMode" label="运行模式" width="90">
          <template #default="{ row }">
            <template v-if="row.clientMode">
              <el-tag :type="row.clientMode === 'NORMAL' ? 'success' : 'danger'" size="small">
                {{ row.clientMode === 'NORMAL' ? '正常' : '受限' }}
              </el-tag>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="boundAt" label="注册时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.boundAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="lastHeartbeatAt" label="最后心跳" width="170">
          <template #default="{ row }">
            {{ formatTime(row.lastHeartbeatAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="invalidateReason" label="失效原因" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.invalidateReason || '-' }}
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50, 100]"
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { get } from '@/utils/request'
import dayjs from 'dayjs'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const searchKeyword = ref('')
const statusFilter = ref('')

/** 前端筛选（后端暂未支持按授权码/状态过滤，先全量拉取后本地过滤） */
const filteredData = computed(() => {
  let list = tableData.value
  if (searchKeyword.value) {
    const kw = searchKeyword.value.trim().toLowerCase()
    list = list.filter(r =>
      (r.authCode || '').toLowerCase().includes(kw) ||
      (r.customerNo || '').toLowerCase().includes(kw)
    )
  }
  if (statusFilter.value) {
    list = list.filter(r => r.status === statusFilter.value)
  }
  return list
})

async function loadData() {
  loading.value = true
  try {
    // 拉取足够多的数据用于前端筛选（实际生产应由后端支持过滤参数）
    const res: any = await get('/v1/dashboard/app-clients', {
      pageNum: 1,
      pageSize: 1000
    })
    tableData.value = res.records ?? []
    total.value = res.total ?? tableData.value.length
  } finally {
    loading.value = false
  }
}

function shortHash(hash: string) {
  if (!hash) return '-'
  return hash.length > 16 ? hash.slice(0, 8) + '…' + hash.slice(-8) : hash
}

function bindingStatusColor(status: string) {
  const map: Record<string, string> = {
    ACTIVE: 'success',
    INVALIDATED: 'info'
  }
  return map[status] || ''
}

function bindingStatusLabel(status: string) {
  const map: Record<string, string> = {
    ACTIVE: '有效',
    INVALIDATED: '已失效'
  }
  return map[status] || status
}

function onlineStatusColor(status: string) {
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
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(loadData)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.header-left {
  display: flex;
  align-items: center;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.mono {
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  font-size: 12px;
}
</style>
