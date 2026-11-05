<template>
  <div class="page-container" v-loading="loading">
    <el-breadcrumb separator="/">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ name: 'doctors' }">医生团队</el-breadcrumb-item>
      <el-breadcrumb-item>{{ doctor?.name || '医生详情' }}</el-breadcrumb-item>
    </el-breadcrumb>

    <el-empty v-if="notFound" description="这位医生不存在或已下架">
      <el-button type="primary" @click="$router.push({ name: 'doctors' })">返回医生列表</el-button>
    </el-empty>

    <template v-else-if="doctor">
      <el-row :gutter="24" style="margin-top:20px">
        <el-col :span="8">
          <el-card shadow="never" class="profile-card">
            <el-avatar :size="110" :src="doctor.avatarUrl || undefined">
              {{ doctor.name.charAt(0) }}
            </el-avatar>
            <h2>{{ doctor.name }}</h2>
            <p class="title">{{ doctor.title }}</p>
            <el-tag type="success" effect="plain">{{ doctor.department }}</el-tag>
          </el-card>
        </el-col>

        <el-col :span="16">
          <el-card shadow="never">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="所属医院">{{ doctor.hospital }}</el-descriptions-item>
              <el-descriptions-item label="科室">{{ doctor.department }}</el-descriptions-item>
              <el-descriptions-item label="职称">{{ doctor.title }}</el-descriptions-item>
              <el-descriptions-item label="擅长">
                <span v-if="doctor.specialty">{{ doctor.specialty }}</span>
                <span v-else class="muted">暂未填写</span>
              </el-descriptions-item>
              <el-descriptions-item label="联系电话">
                <span v-if="doctor.phone">{{ doctor.phone }}</span>
                <span v-else class="muted">暂未公开</span>
              </el-descriptions-item>
            </el-descriptions>

            <h3 class="section-title">医生简介</h3>
            <p class="description">{{ doctor.description || '这位医生还没有填写简介。' }}</p>

            <el-alert
              type="warning"
              :closable="false"
              show-icon
              class="disclaimer"
              title="这里只是医生资料展示"
              description="本平台的 AI 问诊由大模型直接作答，没有真人医生参与，也不能代替面诊。需要看诊请通过医院官方渠道挂号。"
            />

            <div class="actions">
              <el-button type="primary" @click="$router.push({ name: 'ai-doctor' })">
                去问 AI 助手
              </el-button>
              <el-button @click="$router.push({ name: 'doctors' })">返回列表</el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/api'

interface Doctor {
  id: number
  name: string
  title: string
  department: string
  hospital: string
  avatarUrl: string
  specialty: string
  description: string
  phone: string
}

const route = useRoute()
const doctor = ref<Doctor | null>(null)
const loading = ref(false)
const notFound = ref(false)

async function load(id: string) {
  loading.value = true
  notFound.value = false
  doctor.value = null
  try {
    const res: any = await api.get(`/doctors/${id}`)
    doctor.value = res.data
  } catch (e: any) {
    // 后端对不存在的医生返回 404，`api` 拦截器会把它抛出来。
    // 这里区分「404 就是没有这个人」和「其它错误已经在拦截器里提示过了」。
    if (e?.response?.status === 404) notFound.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => load(route.params.id as string))
// 从 A 医生的详情页点进 B 医生时，组件会被复用，必须手动重新取数
watch(() => route.params.id, (id) => { if (id) load(id as string) })
</script>

<style scoped>
.page-container {
  max-width: 1100px;
  margin: 0 auto;
  padding: 20px;
}
.profile-card {
  text-align: center;
}
.profile-card h2 {
  margin: 16px 0 6px;
  font-size: 20px;
}
.profile-card .title {
  margin: 0 0 12px;
  color: #606266;
  font-size: 14px;
}
.section-title {
  margin: 24px 0 10px;
  font-size: 15px;
}
.description {
  margin: 0;
  color: #606266;
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
}
.muted {
  color: #c0c4cc;
}
.disclaimer {
  margin-top: 20px;
}
.actions {
  margin-top: 20px;
}
</style>
