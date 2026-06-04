<template>
  <div class="page-container">
    <section class="hero">
      <h1>智慧医药，健康相伴</h1>
      <p>快速查询疾病百科、了解药品信息、与AI智能医生对话</p>
      <div class="hero-search">
        <el-input v-model="keyword" placeholder="搜索疾病名称、症状、药品..." size="large" @keyup.enter="search" :prefix-icon="Search" />
        <el-button type="primary" size="large" @click="search">搜索一下</el-button>
      </div>
      <div class="hero-stats">
        <div class="stat-item"><strong>10+</strong><span>疾病分类</span></div>
        <div class="stat-item"><strong>1000+</strong><span>药品信息</span></div>
        <div class="stat-item"><strong>7x24</strong><span>AI智能医生</span></div>
      </div>
    </section>

    <section class="features">
      <h2>核心功能</h2>
      <el-row :gutter="24">
        <el-col :span="8" v-for="card in cards" :key="card.title">
          <el-card shadow="hover" class="feature-card" @click="$router.push(card.link)">
            <el-icon :size="40" :color="card.color"><component :is="card.icon" /></el-icon>
            <h3>{{ card.title }}</h3>
            <p>{{ card.desc }}</p>
          </el-card>
        </el-col>
      </el-row>
    </section>

    <section class="popular">
      <h2>热门疾病</h2>
      <el-row :gutter="16">
        <el-col :span="8" v-for="item in popularIllnesses" :key="item.id">
          <el-card shadow="hover" class="illness-card" @click="$router.push(`/illness/${item.id}`)">
            <h3>{{ item.name }}</h3>
            <p class="symptom">{{ item.symptom?.substring(0, 80) }}...</p>
            <span class="tag" v-if="item.categoryName">{{ item.categoryName }}</span>
            <span class="views">{{ item.viewCount }} 次浏览</span>
          </el-card>
        </el-col>
      </el-row>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, FirstAidKit, ChatDotRound, UserFilled } from '@element-plus/icons-vue'
import api from '@/api'

const router = useRouter()
const keyword = ref('')
const popularIllnesses = ref<any[]>([])

const cards = [
  { title: '疾病百科', desc: '全面的疾病信息库，按科室分类浏览，了解病因症状', icon: FirstAidKit, color: '#409eff', link: '/illnesses' },
  { title: '药品查询', desc: '快速查找药品信息，了解功效用法和注意事项', icon: Search, color: '#67c23a', link: '/medicines' },
  { title: '智能医生', desc: 'AI驱动的大语言模型，7x24小时回答您的健康疑问', icon: ChatDotRound, color: '#e6a23c', link: '/ai-doctor' },
]

function search() {
  if (keyword.value.trim()) {
    router.push({ name: 'search', query: { keyword: keyword.value } })
  }
}

onMounted(async () => {
  try {
    const res = await api.get('/illnesses', { params: { page: 1, size: 6 } })
    popularIllnesses.value = res.data?.records || []
  } catch (e) {}
})
</script>

<style scoped>
.hero {
  text-align: center;
  padding: 60px 20px 40px;
}
.hero h1 {
  font-size: 36px;
  color: var(--text);
  margin-bottom: 12px;
}
.hero p {
  font-size: 16px;
  color: var(--text-secondary);
  margin-bottom: 32px;
}
.hero-search {
  display: flex;
  max-width: 600px;
  margin: 0 auto 40px;
  gap: 12px;
}
.hero-stats {
  display: flex;
  justify-content: center;
  gap: 60px;
}
.stat-item strong {
  display: block;
  font-size: 28px;
  color: var(--primary);
}
.stat-item span {
  font-size: 14px;
  color: var(--text-secondary);
}
.features {
  padding: 40px 0;
}
.features h2, .popular h2 {
  text-align: center;
  margin-bottom: 24px;
  font-size: 24px;
}
.feature-card {
  text-align: center;
  cursor: pointer;
  padding: 12px;
}
.feature-card h3 {
  margin: 12px 0 8px;
}
.feature-card p {
  color: var(--text-secondary);
  font-size: 14px;
}
.illness-card {
  cursor: pointer;
  margin-bottom: 16px;
}
.illness-card h3 {
  margin-bottom: 8px;
}
.symptom {
  color: var(--text-secondary);
  font-size: 13px;
  margin-bottom: 8px;
}
.tag {
  display: inline-block;
  background: #ecf5ff;
  color: var(--primary);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  margin-right: 12px;
}
.views {
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
