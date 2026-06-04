<template>
  <div class="page-container" v-loading="loading">
    <div v-if="data" class="detail-page">
      <el-breadcrumb separator="/">
        <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item :to="{ name: 'illnesses' }">疾病百科</el-breadcrumb-item>
        <el-breadcrumb-item>{{ data.name }}</el-breadcrumb-item>
      </el-breadcrumb>

      <div class="detail-header">
        <h1>{{ data.name }}</h1>
        <p class="meta">
          <el-tag>{{ data.categoryName }}</el-tag>
          <span>浏览量：{{ data.viewCount }}</span>
        </p>
      </div>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="病因">{{ data.cause }}</el-descriptions-item>
        <el-descriptions-item label="症状">{{ data.symptom }}</el-descriptions-item>
        <el-descriptions-item v-if="data.specialSymptom" label="特殊症状">{{ data.specialSymptom }}</el-descriptions-item>
      </el-descriptions>

      <div v-if="data.medicines?.length" class="related-medicines">
        <h3>推荐药品</h3>
        <el-row :gutter="16">
          <el-col :span="8" v-for="m in data.medicines" :key="m.id">
            <el-card shadow="hover" class="med-card" @click="$router.push(`/medicine/${m.id}`)">
              <h4>{{ m.name }}</h4>
              <p>{{ m.effect?.substring(0, 60) }}...</p>
              <span v-if="m.price" class="price">¥{{ m.price }}</span>
            </el-card>
          </el-col>
        </el-row>
      </div>
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
    const res = await api.get(`/illnesses/${route.params.id}`)
    data.value = res.data
  } catch (e) {} finally { loading.value = false }
})
</script>

<style scoped>
.detail-header { margin: 24px 0; }
.detail-header h1 { font-size: 28px; margin-bottom: 12px; }
.meta { display: flex; align-items: center; gap: 16px; color: var(--text-secondary); font-size: 14px; }
.related-medicines { margin-top: 32px; }
.related-medicines h3 { margin-bottom: 16px; font-size: 20px; }
.med-card { cursor: pointer; margin-bottom: 12px; }
.med-card h4 { margin-bottom: 8px; }
.med-card p { color: var(--text-secondary); font-size: 13px; }
.price { color: #e6a23c; font-weight: 600; font-size: 14px; }
</style>
