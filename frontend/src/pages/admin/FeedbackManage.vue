<template>
  <div class="manage-page">
    <h3>反馈管理</h3>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="name" label="提交人" width="100" />
      <el-table-column prop="email" label="邮箱" width="180" />
      <el-table-column prop="title" label="标题" width="200" />
      <el-table-column prop="content" label="内容" :show-overflow-tooltip="true" />
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{row}"><el-tag :type="row.status ? 'success' : 'warning'">{{ row.status ? '已处理' : '待处理' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{row}">
          <el-button size="small" v-if="!row.status" type="primary" @click="replyVisible=true;replyForm.id=row.id">回复</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="replyVisible" title="回复反馈" width="500px">
      <el-input v-model="replyForm.reply" type="textarea" :rows="4" placeholder="输入回复内容..." />
      <template #footer><el-button @click="replyVisible=false">取消</el-button><el-button type="primary" @click="doReply">发送回复</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api'
import type { AxiosResponse } from 'axios'

const list = ref<any[]>([])
const loading = ref(false)
const replyVisible = ref(false)
const replyForm = ref({ id: 0, reply: '' })

onMounted(async () => {
  loading.value = true
  try { list.value = (await api.get('/feedbacks')).data || [] } catch (e) {} finally { loading.value = false }
})

async function doReply() {
  await api.put(`/feedbacks/${replyForm.value.id}/reply`, { reply: replyForm.value.reply })
  ElMessage.success('回复成功')
  replyVisible.value = false
  replyForm.value = { id: 0, reply: '' }
  list.value = (await api.get('/feedbacks')).data || []
}
</script>

<style scoped>
h3 { margin-bottom: 16px; }
</style>
