<template>
  <div class="page-container">
    <h2>找医生</h2>
    <div class="filters">
      <el-input v-model="keyword" placeholder="搜索医生、科室、医院..." clearable @keyup.enter="fetchData" @clear="fetchData" style="width:320px" :prefix-icon="Search" />
    </div>
    <div class="doctor-grid">
      <el-card v-for="doc in list" :key="doc.id" shadow="hover" class="doctor-card" @click="$router.push(`/doctor/${doc.id}`)">
        <div class="doc-header">
          <el-avatar :size="64" :src="doc.avatarUrl">{{ doc.name?.charAt(0) }}</el-avatar>
          <div class="doc-info">
            <h3>{{ doc.name }}</h3>
            <p class="title">{{ doc.title }}</p>
            <p class="dept">{{ doc.department }} · {{ doc.hospital }}</p>
          </div>
        </div>
        <p class="specialty">{{ doc.specialty?.substring(0, 100) }}...</p>
      </el-card>
    </div>
    <el-empty v-if="!list.length" description="暂无医生信息" />
    <el-pagination v-if="total > size" :total="total" :page-size="size" :current-page="page" @current-change="handlePageChange" layout="prev, pager, next" style="margin-top:24px;justify-content:center" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import api from '@/api'

const list = ref<any[]>([])
const keyword = ref('')
const page = ref(1)
const size = ref(9)
const total = ref(0)

onMounted(() => fetchData())

async function fetchData() {
  try {
    const res = await api.get('/doctors', { params: { keyword: keyword.value, page: page.value, size: size.value } })
    list.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {}
}

function handlePageChange(p: number) {
  page.value = p
  fetchData()
}
</script>

<style scoped>
h2 { margin-bottom: 24px; }
.filters { margin-bottom: 24px; }
.doctor-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; }
.doctor-card { cursor: pointer; }
.doc-header { display: flex; gap: 16px; margin-bottom: 12px; }
.doc-info h3 { margin-bottom: 4px; }
.title { color: var(--primary); font-size: 13px; }
.dept { color: var(--text-secondary); font-size: 12px; margin-top: 4px; }
.specialty { color: var(--text-secondary); font-size: 13px; }
</style>
