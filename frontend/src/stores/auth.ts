import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'))

  function setAuth(t: string, info: any, refresh?: string) {
    token.value = t
    userInfo.value = info
    localStorage.setItem('accessToken', t)
    localStorage.setItem('userInfo', JSON.stringify(info))
    // refresh 只在登录/续期时才有；不传就保留原来的，
    // 避免「只更新用户资料」这类调用把 refreshToken 抹掉
    if (refresh) {
      refreshToken.value = refresh
      localStorage.setItem('refreshToken', refresh)
    }
  }

  function logout() {
    token.value = ''
    refreshToken.value = ''
    userInfo.value = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('userInfo')
  }

  async function login(username: string, password: string) {
    const res = await api.post('/auth/login', { username, password })
    setAuth(res.data.accessToken, res.data.userInfo, res.data.refreshToken)
    return res.data
  }

  async function register(data: any) {
    return api.post('/auth/register', data)
  }

  async function sendCode(email: string) {
    return api.post('/auth/send-code', { email })
  }

  return {
    token,
    refreshToken,
    userInfo,
    isLoggedIn: () => !!token.value,
    login,
    register,
    sendCode,
    setAuth,
    logout,
  }
})
