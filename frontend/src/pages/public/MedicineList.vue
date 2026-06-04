<template>
  <div class="page-container">
    <h2>药品查询</h2>
    <div class="filters">
      <el-radio-group v-model="type" @change="fetchData">
        <el-radio-button :value="-1">全部</el-radio-button>
        <el-radio-button :value="0">西药</el-radio-button>
        <el-radio-button :value="1">中药</el-radio-button>
        <el-radio-button :value="2">中成药</el-radio-button>
      </el-radio-group>
      <el-input v-model="keyword" placeholder="搜索药品名称、功效..." clearable @keyup.enter="fetchData" @clear="fetchData" style="width:280px;margin-left:16px" :prefix-icon="Search" />
    </div>
    <div class="med-grid">
      <el-card v-for="item in list" :key="item.id" shadow="hover" class="med-card" @click="$router.push(`/medicine/${item.id}`)">
        <h3>{{ item.name }}</h3>
        <p>{{ item.effect?.substring(0, 100) }}...</p>
        <div class="card-footer">
          <el-tag size="small" :type="item.type === 2 ? 'success' : item.type === 1 ? 'warning' : ''">{{ typeLabel(item.type) }}</el-tag>
          <span v-if="item.price" class="price">¥{{ item.price }}</span>
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

const list = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const type = ref(-1)
const page = ref(1)
const size = ref(9)
const total = ref(0)

const typeLabel = (t: number) => t === 0 ? '西药' : t === 1 ? '中药' : '中成药'

onMounted(() => fetchData())

async function fetchData() {
  loading.value = true
  try {
    const res = await api.get('/medicines', { params: { keyword: keyword.value, type: type.value >= 0 ? type.value : undefined, page: page.value, size: size.value } })
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
.med-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 16px; }
.med-card { cursor: pointer; }
.med-card h3 { margin-bottom: 8px; }
.med-card p { color: var(--text-secondary); font-size: 13px; max-height: 40px; overflow: hidden; }
.card-footer { display: flex; justify-content: space-between; align-items: center; margin-top: 12px; }
.price { color: #e6a23c; font-weight: 600; }
</style>
