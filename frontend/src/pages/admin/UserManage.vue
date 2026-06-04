<template>
  <div class="manage-page">
    <h3>用户管理</h3>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="username" label="用户名" width="120" />
      <el-table-column prop="realName" label="姓名" width="100" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column prop="phone" label="手机" width="130" />
      <el-table-column prop="roleStatus" label="角色" width="80">
        <template #default="{row}">{{ row.roleStatus === 1 ? '管理员' : '用户' }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{row}"><el-tag :type="row.status ? 'success' : 'danger'">{{ row.status ? '正常' : '禁用' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{row}">
          <el-button v-if="row.roleStatus !== 1" size="small" :type="row.status ? 'danger' : 'success'"
            @click="toggleStatus(row)">{{ row.status ? '禁用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api'

const list = ref<any[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try { list.value = (await api.get('/admin/users')).data || [] } catch (e) {} finally { loading.value = false }
})

async function toggleStatus(row: any) {
  await api.put(`/admin/users/${row.id}/status`, { status: row.status ? 0 : 1 })
  row.status = row.status ? 0 : 1
  ElMessage.success('操作成功')
}
</script>

<style scoped>
h3 { margin-bottom: 16px; }
</style>
