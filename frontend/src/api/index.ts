import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
})

api.interceptors.request.use(config => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 续期请求的「单飞」控制。
 *
 * <p>为什么要这个：一个页面打开时往往并发发好几个请求，accessToken 一过期
 * 它们会同时收到 401。如果每个都去调 /auth/refresh，就会发出 N 个续期请求，
 * 而后端每次续期都会签发新 token —— 后到的会覆盖先到的，还可能因为
 * refreshToken 轮换把自己搞失效。所以只让第一个去续，其余的挂在这个 Promise 上等结果。
 */
let refreshing: Promise<string | null> | null = null

function doRefresh(): Promise<string | null> {
  const rt = localStorage.getItem('refreshToken')
  if (!rt) return Promise.resolve(null)

  // 这里必须用裸 axios，不能用 api 实例：否则续期失败本身也会走 401 拦截器，
  // 触发下一次续期，形成无限递归。
  return axios
    .post('/api/v1/auth/refresh', { refreshToken: rt }, { timeout: 15000 })
    .then(res => {
      const body = res.data
      if (body?.code !== 200 || !body?.data?.accessToken) return null
      localStorage.setItem('accessToken', body.data.accessToken)
      if (body.data.refreshToken) {
        localStorage.setItem('refreshToken', body.data.refreshToken)
      }
      if (body.data.userInfo) {
        localStorage.setItem('userInfo', JSON.stringify(body.data.userInfo))
      }
      return body.data.accessToken as string
    })
    .catch(() => null)
    .finally(() => {
      refreshing = null
    })
}

api.interceptors.response.use(
  response => response.data,
  async error => {
    const original = error.config || {}
    const url: string = original.url || ''
    // 登录/注册/验证码/续期自身的 401 是业务结果，不能当成「登录过期」，
    // 否则用户输错密码会被莫名其妙地重定向到登录页
    const isAuthEntry = url.startsWith('/auth/')
    const status = error.response?.status

    if (status === 401 && !isAuthEntry && !original.__retried) {
      original.__retried = true
      // 单飞：并发 401 只触发一次续期
      refreshing = refreshing || doRefresh()
      const newToken = await refreshing
      if (newToken) {
        original.headers = original.headers || {}
        original.headers.Authorization = `Bearer ${newToken}`
        return api(original)
      }
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('userInfo')
      router.push('/login')
      ElMessage.error('登录已过期，请重新登录')
      return Promise.reject(error)
    }

    if (status === 401 && isAuthEntry) {
      // 业务层 401（用户名或密码错误）：把后端的话原样告诉用户
      ElMessage.error(error.response?.data?.message || '用户名或密码错误')
    } else if (error.response?.data?.message) {
      ElMessage.error(error.response.data.message)
    } else {
      ElMessage.error('网络错误，请稍后再试')
    }
    return Promise.reject(error)
  }
)

export default api
