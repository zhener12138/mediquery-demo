<template>
  <div class="page-container">
    <h2>疾病百科</h2>
    <div class="filters">
      <el-radio-group v-model="categoryId" @change="fetchData">
        <el-radio-button :value="0">全部</el-radio-button>
        <el-radio-button v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</el-radio-button>
      </el-radio-group>
      <el-input v-model="keyword" placeholder="搜索疾病..." clearable @keyup.enter="fetchData" @clear="fetchData" style="width:260px;margin-left:16px" :prefix-icon="Search" />
    </div>
    <div class="illness-grid">
      <el-card v-for="item in list" :key="item.id" shadow="hover" class="illness-card" @click="$router.push(`/illness/${item.id}`)">
        <h3>{{ item.name }}</h3>
        <p>{{ item.symptom?.substring(0, 100) }}...</p>
        <div class="card-footer">
          <el-tag size="small">{{ item.categoryName }}</el-tag>
          <span>{{ item.viewCount }} 浏览</span>
        </div>
      </el-card>
    </div>
    <el-empty v-if="!list.length && !loading" description="暂无数据" />
    <el-pagination v-if="total > size" :total="total" :page-size="size" :current-page="page" @current-change="handlePageChange" layout="prev, pager, next" style="margin-top:24px;justify-content:center" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import api from '@/api'

const categories = ref<any[]>([])
const list = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const categoryId = ref(0)
const page = ref(1)
const size = ref(9)
const total = ref(0)

onMounted(async () => {
  const [catRes] = await Promise.all([
    api.get('/illnesses/categories'),
  ])
  categories.value = catRes.data || []
  fetchData()
})

async function fetchData() {
  loading.value = true
  try {
    const res = await api.get('/illnesses', { params: { keyword: keyword.value, categoryId: categoryId.value || undefined, page: page.value, size: size.value } })
    list.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {} finally { loading.value = false }
}

function handlePageChange(p: number) {
  page.value = p
  fetchData()
}
</script>

<style scoped>
h2 { margin-bottom: 16px; }
.filters { display: flex; align-items: center; margin-bottom: 24px; }
.illness-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; }
.illness-card { cursor: pointer; }
.illness-card h3 { margin-bottom: 8px; }
.illness-card p { color: var(--text-secondary); font-size: 13px; max-height: 40px; overflow: hidden; }
.card-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; font-size: 12px; color: var(--text-secondary); }
</style>
