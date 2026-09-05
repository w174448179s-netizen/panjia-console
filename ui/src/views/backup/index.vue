<template>
  <div class="backup-page">
    <el-card>
      <el-alert
        title="备份包含数据库（pg_dump）和 JKS 密钥文件。备份文件需离线加密存储，与数据库备份物理隔离。"
        type="warning"
        :closable="false"
        style="margin-bottom: 16px"
      />

      <div class="page-header">
        <h3>备份记录</h3>
        <el-button type="primary" @click="handleBackup">
          <el-icon><VideoPlay /></el-icon>
          立即备份
        </el-button>
      </div>

      <el-table :data="backupList" style="width: 100%" v-loading="loading">
        <el-table-column prop="fileName" label="文件名" min-width="240" />
        <el-table-column prop="backupType" label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ row.backupType === 'FULL' ? '全量' : '增量' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fileSize" label="大小" width="120">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="sha256" label="SHA256" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.sha256?.substring(0, 16) }}...</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              :disabled="row.status !== 'SUCCESS'"
              @click="handleDownload(row)"
            >下载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 备份对话框 -->
    <el-dialog v-model="backupDialogVisible" title="执行备份" width="480px">
      <el-form label-width="100px">
        <el-form-item label="备注">
          <el-input v-model="backupRemark" type="textarea" :rows="2" placeholder="可选，如：升级前备份" />
        </el-form-item>
      </el-form>
      <el-alert
        title="备份可能需要几分钟时间，请耐心等待。备份完成后请验证文件完整性。"
        type="info"
        :closable="false"
      />
      <template #footer>
        <el-button @click="backupDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="startBackup" :loading="backingUp">开始备份</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '@/utils/request'
import dayjs from 'dayjs'

const loading = ref(false)
const backupList = ref<any[]>([])
const backupDialogVisible = ref(false)
const backupRemark = ref('')
const backingUp = ref(false)

function handleBackup() {
  backupRemark.value = ''
  backupDialogVisible.value = true
}

async function startBackup() {
  backingUp.value = true
  try {
    await post('/v1/backups', null, { params: { remark: backupRemark.value || undefined } })
    ElMessage.success('备份任务已启动')
    backupDialogVisible.value = false
    loadBackups()
  } finally {
    backingUp.value = false
  }
}

async function loadBackups() {
  loading.value = true
  try {
    const res: any = await get('/v1/backups', { pageNum: 1, pageSize: 20 })
    backupList.value = res.records ?? []
  } finally {
    loading.value = false
  }
}

function handleDownload(row: any) {
  // 用 <a> 标签触发浏览器原生下载：不弹新标签页、不会被弹窗拦截器拦掉
  const a = document.createElement('a')
  a.href = `/api/v1/backups/${row.id}/download`
  a.download = row.fileName || ''
  document.body.appendChild(a)
  a.click()
  a.remove()
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
    FAILED: 'danger'
  }
  return map[status] || 'info'
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    PENDING: '待执行',
    IN_PROGRESS: '进行中',
    SUCCESS: '成功',
    FAILED: '失败'
  }
  return map[status] || status
}

function formatTime(time: string) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(loadBackups)
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.mono {
  font-family: 'SF Mono', Monaco, Consolas, monospace;
  font-size: 12px;
}
</style>
