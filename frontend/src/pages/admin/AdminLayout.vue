<template>
  <div class="admin-layout">
    <div class="admin-sidebar">
      <h3>管理后台</h3>
      <el-menu :default-active="$route.path" router>
        <el-menu-item index="/admin/dashboard"><el-icon><DataAnalysis /></el-icon> 数据大屏</el-menu-item>
        <el-menu-item index="/admin/illnesses"><el-icon><FirstAidKit /></el-icon> 疾病管理</el-menu-item>
        <el-menu-item index="/admin/medicines"><el-icon><Box /></el-icon> 药品管理</el-menu-item>
        <el-menu-item index="/admin/doctors"><el-icon><Avatar /></el-icon> 医生管理</el-menu-item>
        <el-menu-item index="/admin/feedbacks"><el-icon><ChatLineSquare /></el-icon> 反馈管理</el-menu-item>
        <el-menu-item index="/admin/users"><el-icon><User /></el-icon> 用户管理</el-menu-item>
      </el-menu>
    </div>
    <div class="admin-content">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
</script>

<style scoped>
.admin-layout { display: flex; min-height: calc(100vh - 124px); }
.admin-sidebar { width: 220px; background: #fff; border-right: 1px solid #ebeef5; padding: 16px 0; }
.admin-sidebar h3 { padding: 0 20px 16px; border-bottom: 1px solid #ebeef5; font-size: 16px; }
.admin-content { flex: 1; padding: 24px; overflow-x: auto; }
</style>
