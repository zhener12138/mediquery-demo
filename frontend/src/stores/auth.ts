import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('accessToken') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'))

  function setAuth(t: string, info: any) {
    token.value = t
    userInfo.value = info
    localStorage.setItem('accessToken', t)
    localStorage.setItem('userInfo', JSON.stringify(info))
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('userInfo')
  }

  async function login(username: string, password: string) {
    const res = await api.post('/auth/login', { username, password })
    setAuth(res.data.accessToken, res.data.userInfo)
    return res.data
  }

  async function register(data: any) {
    return api.post('/auth/register', data)
  }

  async function sendCode(email: string) {
    return api.post('/auth/send-code', { email })
  }

  return { token, userInfo, isLoggedIn: () => !!token.value, login, register, sendCode, setAuth, logout }
})
