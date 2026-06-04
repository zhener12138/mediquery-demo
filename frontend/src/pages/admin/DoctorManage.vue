<template>
  <div class="manage-page">
    <div class="page-bar"><h3>医生管理</h3><el-button type="primary" @click="openDialog()">新增医生</el-button></div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="姓名" width="100" />
      <el-table-column prop="title" label="职称" width="120" />
      <el-table-column prop="department" label="科室" width="100" />
      <el-table-column prop="hospital" label="医院" />
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{row}"><el-tag :type="row.status ? 'success' : 'danger'">{{ row.status ? '在岗' : '停诊' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{row}">
          <el-button size="small" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑医生' : '新增医生'" width="600px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="职称"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="科室"><el-input v-model="form.department" /></el-form-item>
        <el-form-item label="医院"><el-input v-model="form.hospital" /></el-form-item>
        <el-form-item label="擅长"><el-input v-model="form.specialty" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
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
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref<any>({})

onMounted(async () => {
  loading.value = true
  try {
    const res = await api.get('/doctors', { params: { size: 100 } })
    list.value = res.data?.records || []
  } catch (e) {} finally { loading.value = false }
})

function openDialog(row?: any) {
  form.value = row ? { ...row } : { name: '', title: '', department: '', hospital: '', specialty: '', description: '' }
  dialogVisible.value = true
}

async function save() {
  if (form.value.id) {
    await api.put(`/doctors/${form.value.id}`, form.value)
  } else {
    await api.post('/doctors', form.value)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  const res = await api.get('/doctors', { params: { size: 100 } })
  list.value = res.data?.records || []
}

async function handleDelete(row: any) {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' })
  await api.delete(`/doctors/${row.id}`)
  list.value = list.value.filter(i => i.id !== row.id)
  ElMessage.success('已删除')
}
</script>

<style scoped>
.page-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
</style>
