<template>
  <footer class="app-footer">
    <div class="footer-inner">
      <div class="footer-brand">
        <h3>医问通 MediQuery</h3>
        <p>智慧医药查询系统 — 您的健康助手</p>
      </div>
      <div class="footer-links">
        <div class="footer-col">
          <h4>快速导航</h4>
          <router-link to="/illnesses">疾病百科</router-link>
          <router-link to="/medicines">药品查询</router-link>
          <router-link to="/doctors">找医生</router-link>
        </div>
        <div class="footer-col">
          <h4>特色功能</h4>
          <router-link to="/ai-doctor">智能医生</router-link>
          <router-link to="/feedback">意见反馈</router-link>
        </div>
        <div class="footer-col">
          <h4>关于我们</h4>
          <a href="https://github.com" target="_blank">GitHub</a>
          <a href="mailto:support@mediquery.com">联系我们</a>
        </div>
      </div>
    </div>
    <div class="footer-bottom">
      <p>&copy; {{ year }} 医问通 MediQuery. All rights reserved. MIT License.</p>
    </div>
  </footer>
</template>

<script setup lang="ts">
const year = new Date().getFullYear()
</script>

<style scoped>
.app-footer {
  background: #303133;
  color: #c0c4cc;
  margin-top: 40px;
}
.footer-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 20px;
  display: flex;
  justify-content: space-between;
  gap: 40px;
}
.footer-brand h3 {
  color: #fff;
  margin-bottom: 8px;
}
.footer-links {
  display: flex;
  gap: 60px;
}
.footer-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.footer-col h4 {
  color: #fff;
  margin-bottom: 4px;
  font-size: 15px;
}
.footer-col a {
  color: #c0c4cc;
  font-size: 13px;
}
.footer-col a:hover {
  color: #fff;
}
.footer-bottom {
  text-align: center;
  padding: 16px;
  border-top: 1px solid #4a4b4d;
  font-size: 13px;
}
</style>
