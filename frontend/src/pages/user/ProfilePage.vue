<template>
  <div class="page-container" v-loading="loading">
    <el-breadcrumb separator="/">
      <el-breadcrumb-item :to="{ name: 'home' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>个人资料</el-breadcrumb-item>
    </el-breadcrumb>

    <h2>个人资料</h2>

    <el-row :gutter="24" style="margin-top:20px">
      <el-col :span="8">
        <el-card shadow="never" class="avatar-card">
          <el-avatar :size="96" :src="form.avatarUrl || undefined">
            {{ (form.realName || form.username || '?').charAt(0) }}
          </el-avatar>
          <p class="username">{{ form.username }}</p>
          <el-tag :type="userInfo?.roleStatus === 1 ? 'danger' : 'info'" size="small">
            {{ userInfo?.roleStatus === 1 ? '管理员' : '普通用户' }}
          </el-tag>

          <div class="avatar-upload">
            <el-upload
              :show-file-list="false"
              :before-upload="beforeAvatarUpload"
              :http-request="uploadAvatar"
              accept="image/*"
            >
              <el-button size="small" :loading="uploading">更换头像</el-button>
            </el-upload>
            <p class="hint">支持 jpg / png / gif / webp，5MB 以内</p>
          </div>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card shadow="never">
          <el-tabs v-model="activeTab">
            <el-tab-pane label="基本资料" name="basic">
              <el-form :model="form" label-width="90px" style="max-width:420px">
                <el-form-item label="用户名">
                  <!-- 用户名不可改：它是登录凭据 -->
                  <el-input :model-value="form.username" disabled />
                </el-form-item>
                <el-form-item label="真实姓名">
                  <el-input v-model="form.realName" maxlength="20" placeholder="选填" />
                </el-form-item>
                <el-form-item label="性别">
                  <el-radio-group v-model="form.sex">
                    <el-radio value="男">男</el-radio>
                    <el-radio value="女">女</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="年龄">
                  <el-input-number v-model="form.age" :min="0" :max="150" controls-position="right" />
                </el-form-item>
                <el-form-item label="手机号">
                  <el-input v-model="form.phone" maxlength="20" placeholder="选填" />
                </el-form-item>
                <el-form-item label="邮箱">
                  <el-input v-model="form.email" maxlength="60" placeholder="选填" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="saving" @click="saveProfile">保存修改</el-button>
                  <el-button @click="loadProfile">重置</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="修改密码" name="password">
              <el-form :model="pwd" :rules="pwdRules" ref="pwdFormRef" label-width="90px" style="max-width:420px">
                <el-form-item label="当前密码" prop="oldPassword">
                  <el-input v-model="pwd.oldPassword" type="password" show-password />
                </el-form-item>
                <el-form-item label="新密码" prop="newPassword">
                  <el-input v-model="pwd.newPassword" type="password" show-password />
                </el-form-item>
                <el-form-item label="确认密码" prop="confirm">
                  <el-input v-model="pwd.confirm" type="password" show-password />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="changing" @click="changePassword">确认修改</el-button>
                </el-form-item>
              </el-form>
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                title="修改密码后当前登录不会立即失效"
                description="项目目前没有 token 主动吊销机制。改完密码会帮你重新登录一次，但旧的 accessToken 在有效期内仍然可用。"
              />
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules, UploadRequestOptions } from 'element-plus'
import api from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const userInfo = auth.userInfo

const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const changing = ref(false)
const activeTab = ref('basic')

const form = reactive({
  username: '',
  realName: '',
  sex: '',
  age: undefined as number | undefined,
  phone: '',
  email: '',
  avatarUrl: '',
})

const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const pwdFormRef = ref<FormInstance>()

const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_r: any, v: string, cb: (e?: Error) => void) => {
        if (v !== pwd.newPassword) cb(new Error('两次输入的密码不一致'))
        else cb()
      },
      trigger: 'blur',
    },
  ],
}

async function loadProfile() {
  loading.value = true
  try {
    const res: any = await api.get('/user/me')
    const d = res.data || {}
    form.username = d.username || ''
    form.realName = d.realName || ''
    form.sex = d.sex || ''
    form.age = d.age ?? undefined
    form.phone = d.phone || ''
    form.email = d.email || ''
    form.avatarUrl = d.avatarUrl || ''
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  saving.value = true
  try {
    // 只提交可改字段。后端 updateProfile 是逐字段判 null 再赋值，
    // 多发几个字段也不会出错，但显式列出来才能一眼看出哪些允许改。
    const res: any = await api.put('/user/profile', {
      realName: form.realName,
      sex: form.sex,
      age: form.age,
      phone: form.phone,
      email: form.email,
    })
    // 同步回 store，否则顶栏头像和昵称要刷新页面才变
    auth.setAuth(localStorage.getItem('accessToken') || '', {
      ...auth.userInfo,
      ...res.data,
    })
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

async function changePassword() {
  const ok = await pwdFormRef.value?.validate().catch(() => false)
  if (!ok) return
  changing.value = true
  try {
    await api.put('/user/password', {
      oldPassword: pwd.oldPassword,
      newPassword: pwd.newPassword,
    })
    ElMessage.success('密码已修改，请重新登录')
    pwd.oldPassword = pwd.newPassword = pwd.confirm = ''
    setTimeout(() => {
      auth.logout()
      window.location.href = '/login'
    }, 800)
  } finally {
    changing.value = false
  }
}

/**
 * 上传前先挡一道明显不合规的。
 *
 * <p>真正的校验在后端（OssClient 会验扩展名、大小、文件魔数），
 * 这里只是让用户不用等一次网络往返才知道自己选错了文件。
 * <b>不能把这层当防线</b>——浏览器里改个 JS 就绕过去了。
 */
function beforeAvatarUpload(file: File) {
  const ok = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type)
  if (!ok) {
    ElMessage.error('只支持 jpg / png / gif / webp 格式的图片')
    return false
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB')
    return false
  }
  return true
}

async function uploadAvatar(options: UploadRequestOptions) {
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', options.file)
    const res: any = await api.post('/files/upload', fd)
    form.avatarUrl = res.data
    await saveProfile()
    ElMessage.success('头像已更新')
  } finally {
    uploading.value = false
  }
}

onMounted(loadProfile)
</script>

<style scoped>
.page-container {
  max-width: 1100px;
  margin: 0 auto;
  padding: 20px;
}
.avatar-card {
  text-align: center;
}
.avatar-card .username {
  margin: 12px 0 8px;
  font-size: 16px;
  font-weight: 600;
}
.avatar-upload {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}
.avatar-upload .hint {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
