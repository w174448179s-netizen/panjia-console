<template>
  <div class="license-page">
    <el-card>
      <div class="page-header">
        <div class="header-left">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索授权码/客户编号"
            style="width: 280px"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select v-model="statusFilter" placeholder="状态" style="width: 140px; margin-left: 12px" clearable>
            <el-option label="生效中" value="ACTIVE" />
            <el-option label="换机中" value="REBINDING" />
            <el-option label="已到期" value="EXPIRED" />
            <el-option label="已吊销" value="REVOKED" />
          </el-select>
          <el-button type="primary" @click="loadData">
            <el-icon><Search /></el-icon>
            查询
          </el-button>
        </div>
        <el-button type="primary" @click="handleIssue">
          <el-icon><Plus /></el-icon>
          签发授权
        </el-button>
      </div>

      <el-table :data="tableData" style="width: 100%" v-loading="loading">
        <el-table-column prop="authCode" label="授权码" width="160">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ row.authCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="customerNo" label="客户编号" width="120" />
        <el-table-column prop="licenseType" label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="licenseTypeColor(row.licenseType)" size="small">{{ row.licenseType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusColor(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="maxStores" label="门店数" width="80" />
        <el-table-column prop="maxUsers" label="用户数" width="80" />
        <el-table-column prop="endDate" label="到期日期" width="120" />
        <el-table-column prop="issueAt" label="签发时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.issueAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'ACTIVE' || row.status === 'REBINDING'">
              <el-button type="primary" link @click="handleRebind(row)">换机</el-button>
              <el-button type="success" link @click="handleRenew(row)">续期</el-button>
            </template>
            <template v-if="row.status === 'REBINDING'">
              <el-button type="success" link @click="handleCancelRebind(row)">取消换机</el-button>
            </template>
            <template v-if="row.status === 'ACTIVE'">
              <el-button type="warning" link @click="handleRevoke(row)">吊销</el-button>
            </template>
            <template v-if="row.status === 'REVOKED'">
              <el-button type="success" link @click="handleRestore(row)">恢复</el-button>
            </template>
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

    <!-- 签发授权对话框 -->
    <el-dialog v-model="issueDialogVisible" title="签发授权" width="640px">
      <el-form :model="issueForm" :rules="issueRules" ref="issueFormRef" label-width="120px">
        <el-form-item label="客户编号" prop="customerNo">
          <el-input v-model="issueForm.customerNo" placeholder="如：C001" />
        </el-form-item>
        <el-form-item label="授权类型" prop="licenseType">
          <el-select v-model="issueForm.licenseType" style="width: 100%">
            <el-option label="正式授权" value="PRODUCTION" />
            <el-option label="试用授权" value="TRIAL" />
            <el-option label="测试授权" value="TEST" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本套餐">
          <el-input v-model="issueForm.version" placeholder="如：STANDARD/PROFESSIONAL" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="最大门店数">
              <el-input-number v-model="issueForm.maxStores" :min="1" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大用户数">
              <el-input-number v-model="issueForm.maxUsers" :min="1" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="开始日期" prop="startDate">
              <el-date-picker v-model="issueForm.startDate" type="date" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="结束日期" prop="endDate">
              <el-date-picker v-model="issueForm.endDate" type="date" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="能力位">
          <el-select v-model="issueForm.capabilities" multiple style="width: 100%" placeholder="选择能力位">
            <el-option label="IM 即时通讯" value="IM" />
            <el-option label="AI 智能助手" value="AI" />
            <el-option label="BI 报表分析" value="BI" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleIssueSubmit" :loading="issuing">签发</el-button>
      </template>
    </el-dialog>

    <!-- 续期对话框 -->
    <el-dialog v-model="renewDialogVisible" title="续期授权" width="640px">
      <el-alert
        title="续期不新增授权版本，客户端持有 JWT 继续有效，下次 check 时自动获取最新参数。"
        type="info"
        :closable="false"
        style="margin-bottom: 16px"
      />
      <el-form :model="renewForm" :rules="renewRules" ref="renewFormRef" label-width="120px">
        <el-form-item label="授权码">
          <el-tag>{{ renewForm.authCode }}</el-tag>
        </el-form-item>
        <el-form-item label="客户编号">
          <span>{{ renewForm.customerNo }}</span>
        </el-form-item>
        <el-form-item label="到期日期" prop="endDate">
          <el-date-picker v-model="renewForm.endDate" type="date" style="width: 100%" />
        </el-form-item>
        <el-form-item label="版本套餐">
          <el-input v-model="renewForm.version" placeholder="留空则保持不变" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="最大门店数">
              <el-input-number v-model="renewForm.maxStores" :min="1" style="width: 100%" placeholder="留空不变" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大用户数">
              <el-input-number v-model="renewForm.maxUsers" :min="1" style="width: 100%" placeholder="留空不变" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="能力位">
          <el-select v-model="renewForm.capabilities" multiple style="width: 100%" placeholder="留空则保持不变">
            <el-option label="IM 即时通讯" value="IM" />
            <el-option label="AI 智能助手" value="AI" />
            <el-option label="BI 报表分析" value="BI" />
          </el-select>
        </el-form-item>
        <el-form-item label="续期原因">
          <el-input v-model="renewForm.reason" placeholder="请输入续期原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renewDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleRenewSubmit" :loading="renewing">确认续期</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { get, post } from '@/utils/request'
import dayjs from 'dayjs'

const route = useRoute()

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const searchKeyword = ref('')
const statusFilter = ref('')

const issueDialogVisible = ref(false)
const issuing = ref(false)
const issueFormRef = ref<FormInstance>()

const renewDialogVisible = ref(false)
const renewing = ref(false)
const renewFormRef = ref<FormInstance>()

/** 前端生成 requestId 作为幂等键（F1 冻结规则）
 *  crypto.randomUUID 仅 secure context（HTTPS/localhost）可用，
 *  ip-only HTTP 部署必须用 getRandomValues 兜底，自己拼 UUIDv4 */
function generateRequestId() {
  if (crypto.randomUUID) return crypto.randomUUID()
  const b = new Uint8Array(16); crypto.getRandomValues(b)
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map(x => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20,32)}`
}

const issueForm = reactive({
  requestId: '',
  customerNo: '',
  licenseType: 'PRODUCTION',
  version: 'STANDARD',
  maxStores: 10,
  maxUsers: 50,
  capabilities: [] as string[],
  startDate: dayjs().toDate(),
  endDate: dayjs().add(1, 'year').toDate(),
  maintenanceEndDate: null as Date | null,
  minSupportedVersion: '',
  maxSupportedVersion: ''
})

const issueRules: FormRules = {
  customerNo: [{ required: true, message: '请输入客户编号', trigger: 'blur' }],
  licenseType: [{ required: true, message: '请选择授权类型', trigger: 'change' }]
}

/** 续期表单 */
const renewForm = reactive({
  authCode: '',
  customerNo: '',
  endDate: null as Date | null,
  version: '',
  maxStores: null as number | null,
  maxUsers: null as number | null,
  capabilities: [] as string[],
  reason: ''
})

const renewRules: FormRules = {
  endDate: [{ required: true, message: '请选择到期日期', trigger: 'change' }]
}

/** 加载签发流水列表 */
async function loadData() {
  loading.value = true
  try {
    const res: any = await get('/v1/license-mgmt/issue-records', {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      customerNo: searchKeyword.value
    })
    tableData.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

/** 签发授权 */
function handleIssue() {
  issueForm.requestId = generateRequestId()
  if (route.query.customerNo) {
    issueForm.customerNo = route.query.customerNo as string
  }
  issueDialogVisible.value = true
}

/** 提交签发 */
async function handleIssueSubmit() {
  if (!issueFormRef.value) return
  await issueFormRef.value.validate(async (valid) => {
    if (!valid) return
    issuing.value = true
    try {
      const res: any = await post('/v1/license-mgmt/issue', issueForm)
      ElMessage.success(`签发成功！授权码：${res.authCode}`)
      issueDialogVisible.value = false
      loadData()
    } finally {
      issuing.value = false
    }
  })
}

/** 吊销 */
async function handleRevoke(row: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      `确定吊销授权「${row.authCode}」吗？`,
      '吊销授权',
      {
        confirmButtonText: '确认吊销',
        cancelButtonText: '取消',
        inputPlaceholder: '请输入吊销原因',
        type: 'warning',
        inputValidator: (val: string) => !!val || '请输入吊销原因'
      }
    )
    await post(`/v1/license-mgmt/revoke/${row.authCode}`, null, { params: { reason } })
    ElMessage.success('已吊销')
    loadData()
  } catch {
    // 用户取消
  }
}

/** 恢复 */
async function handleRestore(row: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      `确定恢复授权「${row.authCode}」吗？`,
      '恢复授权',
      {
        confirmButtonText: '确认恢复',
        cancelButtonText: '取消',
        inputPlaceholder: '请输入恢复原因',
        type: 'success',
        inputValidator: (val: string) => !!val || '请输入恢复原因'
      }
    )
    await post(`/v1/license-mgmt/restore/${row.authCode}`, null, { params: { reason } })
    ElMessage.success('已恢复，旧 JWT 将在下一鉴权周期失效')
    loadData()
  } catch {
    // 用户取消
  }
}

/** 打开续期对话框 */
function handleRenew(row: any) {
  Object.assign(renewForm, {
    authCode: row.authCode,
    customerNo: row.customerNo,
    endDate: row.endDate ? dayjs(row.endDate).toDate() : null,
    version: '',
    maxStores: null,
    maxUsers: null,
    capabilities: [],
    reason: ''
  })
  renewDialogVisible.value = true
}

/** 提交续期 */
async function handleRenewSubmit() {
  if (!renewFormRef.value) return
  await renewFormRef.value.validate(async (valid) => {
    if (!valid) return
    renewing.value = true
    try {
      const payload: any = {
        authCode: renewForm.authCode,
        endDate: renewForm.endDate
      }
      if (renewForm.version) payload.version = renewForm.version
      if (renewForm.maxStores != null) payload.maxStores = renewForm.maxStores
      if (renewForm.maxUsers != null) payload.maxUsers = renewForm.maxUsers
      if (renewForm.capabilities.length > 0) payload.capabilities = renewForm.capabilities
      if (renewForm.reason) payload.reason = renewForm.reason

      await post('/v1/license-mgmt/renew', payload)
      ElMessage.success('续期成功')
      renewDialogVisible.value = false
      loadData()
    } finally {
      renewing.value = false
    }
  })
}

/** 换机 */
async function handleRebind(row: any) {
  try {
    await ElMessageBox.confirm(
      `确定让授权「${row.authCode}」进入换机状态吗？当前绑定的指纹将失效。`,
      '换机确认',
      {
        confirmButtonText: '确认换机',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
    await post(`/v1/license-mgmt/rebind/${row.authCode}`)
    ElMessage.success('已进入换机状态，客户需重新激活')
    loadData()
  } catch {
    // 用户取消
  }
}

/** 取消换机 */
async function handleCancelRebind(row: any) {
  try {
    const { value: reason } = await ElMessageBox.prompt(
      `确定取消授权「${row.authCode}」的换机吗？`,
      '取消换机',
      {
        confirmButtonText: '确认取消',
        cancelButtonText: '取消',
        inputPlaceholder: '请输入取消原因',
        type: 'info',
        inputValidator: (val: string) => !!val || '请输入取消原因'
      }
    )
    await post(`/v1/license-mgmt/cancel-rebind/${row.authCode}`, null, { params: { reason } })
    ElMessage.success('已取消换机')
    loadData()
  } catch {
    // 用户取消
  }
}

function licenseTypeColor(type: string) {
  const map: Record<string, string> = {
    PRODUCTION: 'success',
    TRIAL: 'warning',
    TEST: 'info'
  }
  return map[type] || ''
}

function statusColor(status: string) {
  const map: Record<string, string> = {
    ACTIVE: 'success',
    REBINDING: 'warning',
    EXPIRED: 'info',
    REVOKED: 'danger'
  }
  return map[status] || ''
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    ACTIVE: '生效中',
    REBINDING: '换机中',
    EXPIRED: '已到期',
    REVOKED: '已吊销'
  }
  return map[status] || status
}

function formatTime(time: string) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(() => {
  if (route.query.customerNo) {
    searchKeyword.value = route.query.customerNo as string
  }
  loadData()
})
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
