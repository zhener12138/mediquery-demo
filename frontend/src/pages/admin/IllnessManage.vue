<template>
  <div class="manage-page">
    <div class="page-bar"><h3>疾病管理</h3><el-button type="primary" @click="openDialog()">新增疾病</el-button></div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="疾病名称" />
      <el-table-column prop="categoryName" label="分类" width="100" />
      <el-table-column prop="viewCount" label="浏览量" width="80" />
      <el-table-column label="操作" width="200">
        <template #default="{row}">
          <el-button size="small" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑疾病' : '新增疾病'" width="600px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="病因"><el-input v-model="form.cause" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="症状"><el-input v-model="form.symptom" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="特殊症状"><el-input v-model="form.specialSymptom" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'

const list = ref<any[]>([])
const categories = ref<any[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref<any>({})

onMounted(async () => {
  loading.value = true
  try {
    const [iRes, cRes] = await Promise.all([
      api.get('/illnesses', { params: { size: 100 } }),
      api.get('/illnesses/categories')
    ])
    list.value = iRes.data?.records || []
    categories.value = cRes.data || []
  } catch (e) {} finally { loading.value = false }
})

function openDialog(row?: any) {
  form.value = row ? { ...row } : { name: '', categoryId: 0, cause: '', symptom: '', specialSymptom: '' }
  dialogVisible.value = true
}

async function save() {
  if (form.value.id) {
    await api.put(`/illnesses/${form.value.id}`, form.value)
  } else {
    await api.post('/illnesses', form.value)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  // Reload
  const res = await api.get('/illnesses', { params: { size: 100 } })
  list.value = res.data?.records || []
}

async function handleDelete(row: any) {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' })
  await api.delete(`/illnesses/${row.id}`)
  list.value = list.value.filter(i => i.id !== row.id)
  ElMessage.success('已删除')
}
</script>

<style scoped>
.page-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
</style>
