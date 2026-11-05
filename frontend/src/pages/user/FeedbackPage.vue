<template>
  <div class="page-container">
    <el-breadcrumb separator="/">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>意见反馈</el-breadcrumb-item>
    </el-breadcrumb>

    <h2>意见反馈</h2>

    <el-row :gutter="24">
      <el-col :span="14">
        <el-card shadow="never">
          <el-form :model="form" :rules="rules" ref="formRef" label-width="80px">
            <el-form-item label="标题" prop="title">
              <el-input v-model="form.title" maxlength="50" show-word-limit placeholder="一句话说明问题" />
            </el-form-item>
            <el-form-item label="联系方式" prop="email">
              <el-input v-model="form.email" maxlength="60" placeholder="邮箱，方便我们回复你" />
            </el-form-item>
            <el-form-item label="内容" prop="content">
              <el-input
                v-model="form.content"
                type="textarea"
                :rows="7"
                maxlength="500"
                show-word-limit
                placeholder="遇到了什么问题、在哪个页面、期望是什么"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submit">提交反馈</el-button>
              <el-button @click="reset">清空</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card shadow="never" class="side-card">
          <h3>提交之后呢</h3>
          <p>反馈会进入后台，管理员可以查看并回复。</p>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="暂时看不到自己的历史反馈"
            description="后端目前只提供「提交」和「管理员查看全部」两个接口，没有「我的反馈列表」。所以提交完想再看一眼是不行的——这条是真缺，不是藏起来了。"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import api from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive({
  name: '',
  email: '',
  title: '',
  content: '',
})

const rules: FormRules = {
  title: [
    { required: true, message: '请填写标题', trigger: 'blur' },
    { min: 2, max: 50, message: '标题 2-50 字', trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  content: [
    { required: true, message: '请填写反馈内容', trigger: 'blur' },
    { min: 5, max: 500, message: '内容 5-500 字', trigger: 'blur' },
  ],
}

function reset() {
  form.title = ''
  form.content = ''
  form.email = auth.userInfo?.email || ''
  formRef.value?.clearValidate()
}

async function submit() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  submitting.value = true
  try {
    await api.post('/feedbacks', {
      name: auth.userInfo?.realName || auth.userInfo?.username || '匿名用户',
      email: form.email,
      title: form.title,
      content: form.content,
    })
    ElMessage.success('反馈已提交，谢谢')
    reset()
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 邮箱从个人资料带过来，省得用户再敲一遍
  form.email = auth.userInfo?.email || ''
})
</script>

<style scoped>
.page-container {
  max-width: 1100px;
  margin: 0 auto;
  padding: 20px;
}
.side-card h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.side-card p {
  margin: 0 0 16px;
  color: #606266;
  font-size: 14px;
  line-height: 1.7;
}
</style>
