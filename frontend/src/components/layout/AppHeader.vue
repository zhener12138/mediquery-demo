<template>
  <header class="app-header" :class="{ scrolled: scrolled }">
    <div class="header-inner">
      <router-link to="/" class="logo">
        <span class="logo-mark">
          <el-icon :size="18"><FirstAidKit /></el-icon>
        </span>
        <span class="logo-text">医问通</span>
      </router-link>

      <nav class="header-nav">
        <router-link to="/consultation" class="nav-link">在线问诊</router-link>

        <template v-if="auth.isLoggedIn()">
          <el-dropdown @command="handleCommand" trigger="click">
            <span class="nav-user">
              <el-avatar :size="30" :src="auth.userInfo?.avatarUrl">
                {{ (auth.userInfo?.realName || auth.userInfo?.username || '我').charAt(0) }}
              </el-avatar>
              <span class="nav-name">{{ auth.userInfo?.realName || auth.userInfo?.username }}</span>
              <el-icon :size="12" class="caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="consultation">我的问诊</el-dropdown-item>
                <el-dropdown-item command="profile">个人资料</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <router-link to="/login" class="nav-link">登录</router-link>
          <el-button type="primary" size="small" round @click="router.push('/register')">注册</el-button>
        </template>
      </nav>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { FirstAidKit, ArrowDown } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

/**
 * 滚动时给 header 加一层更明显的阴影。
 *
 * <p>不是纯装饰：首页首屏是浅色渐变，header 也是白的，不滚时两者几乎融在一起。
 * 用户滚动后需要一个视觉上的「这层在上面」的提示，否则内容像粘在 header 底下。
 */
const scrolled = ref(false)

function onScroll() {
  scrolled.value = window.scrollY > 8
}

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => window.removeEventListener('scroll', onScroll))

function handleCommand(cmd: string) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/')
  } else {
    router.push({ name: cmd })
  }
}
</script>

<style scoped>
.app-header {
  background: rgba(255, 255, 255, .92);
  backdrop-filter: saturate(180%) blur(12px);
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 1px solid transparent;
  transition: box-shadow .2s, border-color .2s;
}
.app-header.scrolled {
  box-shadow: 0 2px 14px rgba(0, 0, 0, .06);
  border-bottom-color: #f0f2f5;
}
.header-inner {
  max-width: 1120px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 62px;
  gap: 24px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--text);
  font-weight: 700;
  font-size: 17px;
  white-space: nowrap;
}
.logo-mark {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #409eff, #2f7fd8);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 3px 10px rgba(64, 158, 255, .35);
}
.header-nav {
  display: flex;
  align-items: center;
  gap: 22px;
  white-space: nowrap;
}
.nav-link {
  color: #606266;
  font-size: 14px;
  transition: color .15s;
}
.nav-link:hover,
.nav-link.router-link-active,
.nav-link.router-link-exact-active {
  color: var(--primary);
}
.nav-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 14px;
  color: #303133;
  outline: none;
}
.nav-name { max-width: 110px; overflow: hidden; text-overflow: ellipsis; }
.caret { color: #909399; }

@media (max-width: 640px) {
  .header-inner { padding: 0 14px; gap: 12px; }
  .header-nav { gap: 14px; }
  .nav-name { display: none; }
}
</style>
