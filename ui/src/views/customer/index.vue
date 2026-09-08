<template>
  <div class="customer-page">
    <el-card>
      <div class="page-header">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索客户编号/名称"
          style="width: 280px"
          clearable
          @keyup.enter="loadData"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" @click="handleCreate">
          <el-icon><Plus /></el-icon>
          新增客户
        </el-button>
      </div>

      <el-table :data="tableData" style="width: 100%" v-loading="loading">
        <el-table-column prop="customerNo" label="客户编号" width="140" />
        <el-table-column prop="customerName" label="客户名称" width="200" />
        <el-table-column prop="contactPerson" label="联系人" width="120" />
        <el-table-column prop="contactPhone" label="联系电话" width="140" />
        <el-table-column prop="currentVersion" label="当前版本" width="120" />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="handleEdit(row)">编辑</el-button>
            <el-button type="primary" link @click="handleViewLicenses(row)">授权</el-button>
            <el-button type="danger" link @click="handleDelete(row)">删除</el-button>
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

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-form :model="formData" :rules="formRules" ref="formRef" label-width="100px">
        <el-form-item label="客户编号" prop="customerNo">
          <el-input v-model="formData.customerNo" :disabled="isEdit" placeholder="如：C001" />
        </el-form-item>
        <el-form-item label="客户名称" prop="customerName">
          <el-input v-model="formData.customerName" placeholder="请输入客户名称" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="formData.contactPerson" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="formData.contactPhone" />
        </el-form-item>
        <el-form-item label="当前版本">
          <el-input v-model="formData.currentVersion" placeholder="如：6.0.0（留空表示未分配）" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="formData.remark" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { get, post, put, del } from '@/utils/request'
import dayjs from 'dayjs'

const router = useRouter()

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const searchKeyword = ref('')

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const formData = reactive({
  id: null as number | null,
  customerNo: '',
  customerName: '',
  contactPerson: '',
  contactPhone: '',
  currentVersion: '',
  remark: ''
})

const formRules: FormRules = {
  customerNo: [{ required: true, message: '请输入客户编号', trigger: 'blur' }],
  customerName: [{ required: true, message: '请输入客户名称', trigger: 'blur' }]
}

/** 加载数据 */
async function loadData() {
  loading.value = true
  try {
    const res: any = await get('/v1/customers', {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: searchKeyword.value
    })
    tableData.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

/** 新增客户 */
function handleCreate() {
  isEdit.value = false
  dialogTitle.value = '新增客户'
  Object.assign(formData, {
    id: null,
    customerNo: '',
    customerName: '',
    contactPerson: '',
    contactPhone: '',
    currentVersion: '',
    remark: ''
  })
  dialogVisible.value = true
}

/** 编辑客户 */
function handleEdit(row: any) {
  isEdit.value = true
  dialogTitle.value = '编辑客户'
  Object.assign(formData, row)
  dialogVisible.value = true
}

/** 查看授权 */
function handleViewLicenses(row: any) {
  router.push({ path: '/license', query: { customerNo: row.customerNo } })
}

/** 删除客户 */
async function handleDelete(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除客户「${row.customerName}」吗？`, '提示', {
      type: 'warning',
      confirmButtonText: '确定删除',
      cancelButtonText: '取消'
    })
    await del(`/v1/customers/${row.id}`)
    ElMessage.success('删除成功')
    loadData()
  } catch {
    // 用户取消
  }
}

/** 提交表单 */
async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (isEdit.value) {
        await put('/v1/customers', formData)
        ElMessage.success('更新成功')
      } else {
        await post('/v1/customers', formData)
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      loadData()
    } finally {
      submitting.value = false
    }
  })
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

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
