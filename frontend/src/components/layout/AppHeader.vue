<template>
  <header class="app-header">
    <div class="header-inner">
      <router-link to="/" class="logo">
        <el-icon :size="28"><FirstAidKit /></el-icon>
        <span class="logo-text">医问通</span>
        <span class="logo-sub">MediQuery</span>
      </router-link>

      <div class="header-search" v-if="showSearch">
        <el-input v-model="searchKeyword" placeholder="搜索疾病、药品..." @keyup.enter="doSearch" :prefix-icon="Search" clearable size="large" />
      </div>

      <nav class="header-nav">
        <router-link to="/illnesses" class="nav-link">疾病百科</router-link>
        <router-link to="/medicines" class="nav-link">药品查询</router-link>
        <router-link to="/doctors" class="nav-link">找医生</router-link>
        <router-link to="/ai-doctor" class="nav-link ai-link">
          <el-icon><ChatDotRound /></el-icon> 智能医生
        </router-link>

        <template v-if="auth.isLoggedIn()">
          <el-dropdown @command="handleCommand">
            <span class="nav-user">
              <el-avatar :size="32" :src="auth.userInfo?.avatarUrl" />
              <span>{{ auth.userInfo?.realName || auth.userInfo?.username }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人资料</el-dropdown-item>
                <el-dropdown-item command="feedback">意见反馈</el-dropdown-item>
                <template v-if="auth.userInfo?.roleStatus === 1">
                  <el-dropdown-item divided command="dashboard">管理后台</el-dropdown-item>
                </template>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <router-link to="/login" class="nav-link">登录</router-link>
          <router-link to="/register"><el-button type="primary" size="small">注册</el-button></router-link>
        </template>
      </nav>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const searchKeyword = ref('')
const showSearch = ref(true)

function doSearch() {
  if (searchKeyword.value.trim()) {
    router.push({ name: 'search', query: { keyword: searchKeyword.value } })
  }
}

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
  background: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,.06);
  position: sticky;
  top: 0;
  z-index: 100;
}
.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  padding: 0 20px;
  height: 64px;
  gap: 24px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--primary);
  font-weight: 700;
  font-size: 20px;
  white-space: nowrap;
}
.logo-sub {
  font-size: 12px;
  color: var(--text-secondary);
  font-weight: 400;
}
.header-search {
  flex: 1;
  max-width: 400px;
}
.header-nav {
  display: flex;
  align-items: center;
  gap: 16px;
  white-space: nowrap;
}
.nav-link {
  color: var(--text);
  font-size: 14px;
}
.nav-link:hover, .nav-link.router-link-active {
  color: var(--primary);
}
.ai-link {
  color: var(--primary);
  font-weight: 500;
}
.nav-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 14px;
}
</style>
