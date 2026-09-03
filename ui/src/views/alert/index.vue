<template>
  <div class="alert-page">
    <el-card>
      <div class="page-header">
        <div class="header-left">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索客户编号"
            style="width: 220px"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select v-model="statusFilter" placeholder="状态" style="width: 140px; margin-left: 12px" clearable>
            <el-option label="待处理" value="OPEN" />
            <el-option label="已确认" value="ACKNOWLEDGED" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
          <el-button type="primary" @click="loadData">
            <el-icon><Search /></el-icon>
            查询
          </el-button>
        </div>
      </div>

      <el-table :data="tableData" style="width: 100%" v-loading="loading">
        <el-table-column prop="severity" label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="severityType(row.severity)" size="small">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="trigger" label="触发类型" width="160" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="customerNo" label="客户编号" width="120" />
        <el-table-column prop="occurredAt" label="发生时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.occurredAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="handleAck(row)" v-if="row.status === 'OPEN'">
              确认
            </el-button>
            <el-button type="success" link @click="handleClose(row)" v-if="row.status !== 'CLOSED'">
              关闭
            </el-button>
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
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '@/utils/request'
import dayjs from 'dayjs'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const searchKeyword = ref('')
const statusFilter = ref('OPEN')

async function loadData() {
  loading.value = true
  try {
    const res: any = await get('/v1/alerts', {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      customerNo: searchKeyword.value,
      status: statusFilter.value
    })
    tableData.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

async function handleAck(row: any) {
  await post(`/v1/alerts/${row.id}/acknowledge`)
  ElMessage.success('已确认')
  loadData()
}

async function handleClose(row: any) {
  await post(`/v1/alerts/${row.id}/close`)
  ElMessage.success('已关闭')
  loadData()
}

function severityType(severity: string) {
  const map: Record<string, string> = {
    INFO: 'info',
    WARN: 'warning',
    ERROR: 'danger',
    CRITICAL: 'danger'
  }
  return map[severity] || 'info'
}

function statusType(status: string) {
  const map: Record<string, string> = {
    OPEN: 'danger',
    ACKNOWLEDGED: 'warning',
    CLOSED: 'success'
  }
  return map[status] || 'info'
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    OPEN: '待处理',
    ACKNOWLEDGED: '已确认',
    CLOSED: '已关闭'
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
</style>
