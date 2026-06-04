<template>
  <div class="page-container" v-loading="loading">
    <div v-if="data">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item :to="{ name: 'medicines' }">药品查询</el-breadcrumb-item>
        <el-breadcrumb-item>{{ data.name }}</el-breadcrumb-item>
      </el-breadcrumb>
      <div class="detail-header">
        <h1>{{ data.name }}</h1>
        <el-tag :type="data.type === 2 ? 'success' : data.type === 1 ? 'warning' : ''">{{ data.type === 0 ? '西药' : data.type === 1 ? '中药' : '中成药' }}</el-tag>
        <span v-if="data.price" class="price">¥{{ data.price }}</span>
      </div>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="品牌">{{ data.brand || '-' }}</el-descriptions-item>
        <el-descriptions-item label="生产厂商">{{ data.manufacturer || '-' }}</el-descriptions-item>
        <el-descriptions-item label="功效" :span="2">{{ data.effect }}</el-descriptions-item>
        <el-descriptions-item label="用法用量" :span="2">{{ data.usageDosage }}</el-descriptions-item>
        <el-descriptions-item label="禁忌" :span="2">{{ data.contraindication || '无' }}</el-descriptions-item>
        <el-descriptions-item label="药物相互作用" :span="2">{{ data.interaction || '无' }}</el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/api'

const route = useRoute()
const data = ref<any>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    const res = await api.get(`/medicines/${route.params.id}`)
    data.value = res.data
  } catch (e) {} finally { loading.value = false }
})
</script>

<style scoped>
.detail-header { margin: 24px 0; display: flex; align-items: center; gap: 16px; }
.detail-header h1 { font-size: 28px; }
.price { color: #e6a23c; font-weight: 600; font-size: 20px; margin-left: auto; }
</style>
