<template>
  <div class="page-container">
    <div class="auth-card">
      <h2>注册账户</h2>
      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="handleRegister">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item prop="email">
          <el-input v-model="form.email" placeholder="邮箱" size="large" />
        </el-form-item>
        <el-form-item prop="code">
          <el-input v-model="form.code" placeholder="验证码" size="large">
            <template #append><el-button :disabled="counting" @click="sendCode" :loading="sending">{{ btnText }}</el-button></template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码(6位以上)" size="large" show-password />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" size="large" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" native-type="submit" :loading="loading" style="width:100%">注 册</el-button>
        </el-form-item>
      </el-form>
      <p class="auth-footer">已有账户？<router-link to="/login">去登录</router-link></p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const router = useRouter()
const loading = ref(false)
const sending = ref(false)
const counting = ref(false)
const btnText = ref('发送验证码')
const formRef = ref()
let timer: any = null

const form = reactive({
  username: '', password: '', confirmPassword: '', email: '', code: ''
})

const validateConfirmPassword = (_rule: any, value: string, cb: any) => {
  if (value !== form.password) cb(new Error('两次密码不一致'))
  else cb()
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }, { min: 3, max: 50, message: '3-50个字符', trigger: 'blur' }],
  password: [{ required: true, min: 6, message: '密码至少6位', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请确认密码', trigger: 'blur' }, { validator: validateConfirmPassword, trigger: 'blur' }],
  email: [{ required: true, type: 'email', message: '请输入有效邮箱', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}

let countdown = 60
async function sendCode() {
  if (!form.email) { ElMessage.warning('请先输入邮箱'); return }
  sending.value = true
  try {
    await auth.sendCode(form.email)
    ElMessage.success('验证码已发送')
    counting.value = true
    timer = setInterval(() => {
      countdown--
      btnText.value = `${countdown}秒后重发`
      if (countdown <= 0) { clearInterval(timer); counting.value = false; btnText.value = '发送验证码'; countdown = 60 }
    }, 1000)
  } catch (e) {} finally { sending.value = false }
}

async function handleRegister() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.register(form)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (e) {} finally { loading.value = false }
}
</script>

<style scoped>
.auth-card { max-width: 420px; margin: 60px auto; background: #fff; padding: 40px; border-radius: 8px; box-shadow: 0 2px 12px rgba(0,0,0,.06); }
.auth-card h2 { text-align: center; margin-bottom: 32px; }
.auth-footer { text-align: center; font-size: 14px; color: var(--text-secondary); }
</style>
