<template>
  <el-card shadow="never">
    <div class="toolbar">
      <div class="left">
        <el-button type="primary" @click="openCreate">新增用户</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
      <div class="right">
        <el-tag type="success" effect="plain">读库：{{ readDs || '-' }}</el-tag>
        <el-tag type="warning" effect="plain">写库：master</el-tag>
        <el-tag effect="plain">耗时 {{ elapsedMs }} ms</el-tag>
        <el-tag v-if="fromCache" type="info" effect="plain">缓存命中</el-tag>
      </div>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="email" label="邮箱" min-width="200" />
      <el-table-column prop="age" label="年龄" width="80" />
      <el-table-column prop="createTime" label="创建时间" width="180" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        :page-sizes="[10, 20, 50]"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="440px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" placeholder="请输入用户名" maxlength="32" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="form.email" placeholder="请输入邮箱" maxlength="64" />
      </el-form-item>
      <el-form-item label="年龄" prop="age">
        <el-input-number v-model="form.age" :min="0" :max="150" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listUsers, createUser, updateUser, deleteUser, getDatasource } from '../api/user'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)
const saving = ref(false)
const elapsedMs = ref(0)
const readDs = ref('')
const fromCache = ref(false)

const dialogVisible = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, username: '', email: '', age: null })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    const data = await listUsers(page.value, size.value)
    rows.value = data.records || []
    total.value = data.total || 0
    elapsedMs.value = data.elapsedMs ?? 0
    readDs.value = data.datasource || ''
    // 查库通常 5ms 以上，走 Redis 缓存基本在 1-2ms
    fromCache.value = (data.elapsedMs ?? 99) <= 3
  } catch (e) {
    // 错误已在拦截器里提示
  } finally {
    loading.value = false
  }
}

async function refreshDs() {
  try {
    const d = await getDatasource()
    readDs.value = d.readDatasource || ''
  } catch (e) {
    /* ignore */
  }
}

function openCreate() {
  form.id = null
  form.username = ''
  form.email = ''
  form.age = null
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function openEdit(row) {
  form.id = row.id
  form.username = row.username
  form.email = row.email
  form.age = row.age
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function submit() {
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      const payload = { username: form.username, email: form.email, age: form.age }
      if (form.id) {
        await updateUser(form.id, payload)
        ElMessage.success('修改成功（写主库，已清缓存）')
      } else {
        await createUser(payload)
        ElMessage.success('新增成功（写主库，已清缓存）')
      }
      dialogVisible.value = false
      await load()
    } catch (e) {
      /* 拦截器已提示 */
    } finally {
      saving.value = false
    }
  })
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除用户「${row.username}」吗？`, '提示', { type: 'warning' })
  await deleteUser(row.id)
  ElMessage.success('删除成功')
  await load()
}

function onPageChange(p) {
  page.value = p
  load()
}

function onSizeChange(s) {
  size.value = s
  page.value = 1
  load()
}

onMounted(async () => {
  await refreshDs()
  await load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 8px;
}
.right {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>