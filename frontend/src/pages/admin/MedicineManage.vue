<template>
  <div class="manage-page">
    <div class="page-bar"><h3>药品管理</h3><el-button type="primary" @click="openDialog()">新增药品</el-button></div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="药品名称" />
      <el-table-column prop="brand" label="品牌" width="120" />
      <el-table-column prop="manufacturer" label="厂商" width="140" />
      <el-table-column label="类型" width="80">
        <template #default="{row}">
          <el-tag size="small" :type="row.type===2?'success':row.type===1?'warning':''">{{ row.type===0?'西药':row.type===1?'中药':'中成药' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="price" label="价格" width="80" />
      <el-table-column label="操作" width="200">
        <template #default="{row}">
          <el-button size="small" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑药品' : '新增药品'" width="600px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="品牌"><el-input v-model="form.brand" /></el-form-item>
        <el-form-item label="厂商"><el-input v-model="form.manufacturer" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type"><el-option label="西药" :value="0" /><el-option label="中药" :value="1" /><el-option label="中成药" :value="2" /></el-select>
        </el-form-item>
        <el-form-item label="价格"><el-input v-model="form.price" /></el-form-item>
        <el-form-item label="功效"><el-input v-model="form.effect" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="用法用量"><el-input v-model="form.usageDosage" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="禁忌"><el-input v-model="form.contraindication" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'
import type { AxiosResponse } from 'axios'

const list = ref<any[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref<any>({})

onMounted(async () => {
  loading.value = true
  try {
    const res = await api.get('/medicines', { params: { size: 100 } })
    list.value = res.data?.records || []
  } catch (e) {} finally { loading.value = false }
})

function openDialog(row?: any) {
  form.value = row ? { ...row } : { name: '', brand: '', manufacturer: '', type: 0, price: '', effect: '', usageDosage: '', contraindication: '' }
  dialogVisible.value = true
}

async function save() {
  if (form.value.id) {
    await api.put(`/medicines/${form.value.id}`, form.value)
  } else {
    await api.post('/medicines', form.value)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  const res = await api.get('/medicines', { params: { size: 100 } })
  list.value = res.data?.records || []
}

async function handleDelete(row: any) {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' })
  await api.delete(`/medicines/${row.id}`)
  list.value = list.value.filter(i => i.id !== row.id)
  ElMessage.success('已删除')
}
</script>

<style scoped>
.page-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
</style>
