<template>
  <div class="dashboard">
    <!-- 统计卡片 -->
    <el-row :gutter="16" class="stat-cards">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon customer">
              <el-icon size="28"><User /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stats.totalCustomers }}</div>
              <div class="stat-label">客户总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon active">
              <el-icon size="28"><Key /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stats.activeLicenses }}</div>
              <div class="stat-label">有效授权</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon online">
              <el-icon size="28"><Connection /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stats.onlineInstances }}</div>
              <div class="stat-label">在线实例</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon alert">
              <el-icon size="28"><Bell /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stats.openAlerts }}</div>
              <div class="stat-label">待处理告警</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近告警 + 客户列表 -->
    <el-row :gutter="16" style="margin-top: 20px">
      <el-col :span="14">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>最近告警</span>
              <el-button type="primary" link @click="$router.push('/alert')">查看全部</el-button>
            </div>
          </template>
          <el-table :data="recentAlerts" style="width: 100%" size="small" v-loading="loading">
            <el-table-column prop="createdAt" label="时间" width="170">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="severity" label="级别" width="80">
              <template #default="{ row }">
                <el-tag :type="severityType(row.severity)" size="small">{{ row.severity }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" />
            <el-table-column prop="customerNo" label="客户" width="120" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>在线状态分布</span>
            </div>
          </template>
          <div class="status-list">
            <div class="status-item">
              <span class="status-dot online"></span>
              <span class="status-label">在线</span>
              <span class="status-count">{{ stats.onlineInstances }}</span>
            </div>
            <div class="status-item">
              <span class="status-dot offline"></span>
              <span class="status-label">离线</span>
              <span class="status-count">{{ stats.offlineInstances }}</span>
            </div>
            <div class="status-item">
              <span class="status-dot lost"></span>
              <span class="status-label">失联</span>
              <span class="status-count">{{ stats.lostInstances }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get } from '@/utils/request'
import dayjs from 'dayjs'

const loading = ref(false)

const stats = ref({
  totalCustomers: 0,
  activeLicenses: 0,
  onlineInstances: 0,
  offlineInstances: 0,
  lostInstances: 0,
  openAlerts: 0
})

const recentAlerts = ref<any[]>([])

async function loadStats() {
  try {
    const res: any = await get('/v1/dashboard/stats')
    stats.value = {
      totalCustomers: res.totalCustomers ?? 0,
      activeLicenses: res.activeLicenses ?? 0,
      onlineInstances: res.onlineInstances ?? 0,
      offlineInstances: res.offlineInstances ?? 0,
      lostInstances: res.lostInstances ?? 0,
      openAlerts: res.openAlerts ?? 0
    }
  } catch {
    // 接口失败保持默认 0
  }
}

async function loadRecentAlerts() {
  loading.value = true
  try {
    const res: any = await get('/v1/alerts', { pageNum: 1, pageSize: 5 })
    recentAlerts.value = res.records ?? []
  } finally {
    loading.value = false
  }
}

function formatTime(time: string) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

function severityType(severity: string) {
  const map: Record<string, string> = {
    'INFO': 'info',
    'WARN': 'warning',
    'ERROR': 'danger',
    'CRITICAL': 'danger'
  }
  return map[severity] || 'info'
}

onMounted(() => {
  loadStats()
  loadRecentAlerts()
})
</script>

<style scoped>
.stat-cards {
  margin-bottom: 0;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 16px;
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.stat-icon.customer { background: linear-gradient(135deg, #667eea, #764ba2); }
.stat-icon.active { background: linear-gradient(135deg, #11998e, #38ef7d); }
.stat-icon.online { background: linear-gradient(135deg, #2193b0, #6dd5ed); }
.stat-icon.alert { background: linear-gradient(135deg, #f093fb, #f5576c); }

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: #111827;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #6b7280;
  margin-top: 4px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}

.status-list {
  padding: 10px 0;
}

.status-item {
  display: flex;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid #f3f4f6;
}

.status-item:last-child {
  border-bottom: none;
}

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 12px;
}

.status-dot.online { background-color: #10b981; }
.status-dot.offline { background-color: #f59e0b; }
.status-dot.lost { background-color: #ef4444; }

.status-label {
  flex: 1;
  font-size: 14px;
  color: #374151;
}

.status-count {
  font-size: 18px;
  font-weight: 600;
  color: #111827;
}
</style>
