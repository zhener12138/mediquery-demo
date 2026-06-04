<template>
  <div class="page-container">
    <div class="search-header">
      <h2>搜索结果：{{ route.query.keyword }}</h2>
    </div>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="疾病" name="illness">
        <div v-if="illnesses.length" class="result-grid">
          <el-card v-for="item in illnesses" :key="item.id" shadow="hover" class="result-card" @click="$router.push(`/illness/${item.id}`)">
            <h3>{{ item.name }}</h3>
            <p>{{ item.symptom?.substring(0, 120) }}</p>
            <span class="tag" v-if="item.categoryName">{{ item.categoryName }}</span>
          </el-card>
        </div>
        <el-empty v-else description="暂无结果" />
      </el-tab-pane>
      <el-tab-pane label="药品" name="medicine">
        <div v-if="medicines.length" class="result-grid">
          <el-card v-for="item in medicines" :key="item.id" shadow="hover" class="result-card" @click="$router.push(`/medicine/${item.id}`)">
            <h3>{{ item.name }}</h3>
            <p>{{ item.effect?.substring(0, 120) }}</p>
            <span class="price" v-if="item.price">¥{{ item.price }}</span>
          </el-card>
        </div>
        <el-empty v-else description="暂无结果" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/api'

const route = useRoute()
const activeTab = ref('illness')
const illnesses = ref<any[]>([])
const medicines = ref<any[]>([])

onMounted(async () => {
  const keyword = route.query.keyword as string
  if (!keyword) return
  try {
    const [iRes, mRes] = await Promise.all([
      api.get('/illnesses', { params: { keyword, size: 20 } }),
      api.get('/medicines', { params: { keyword, size: 20 } })
    ])
    illnesses.value = iRes.data?.records || []
    medicines.value = mRes.data?.records || []
  } catch (e) {}
})
</script>

<style scoped>
.search-header { margin-bottom: 24px; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; }
.result-card { cursor: pointer; }
.result-card h3 { margin-bottom: 8px; }
.result-card p { color: var(--text-secondary); font-size: 13px; max-height: 40px; overflow: hidden; }
.tag { background: #ecf5ff; color: var(--primary); padding: 2px 8px; border-radius: 4px; font-size: 12px; margin-top: 8px; display: inline-block; }
.price { color: #e6a23c; font-weight: 600; }
</style>
