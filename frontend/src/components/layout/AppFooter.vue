<template>
  <footer class="app-footer">
    <div class="footer-inner">
      <div class="footer-brand">
        <div class="brand-line">
          <span class="brand-mark">
            <el-icon :size="15"><FirstAidKit /></el-icon>
          </span>
          <span class="brand-name">医问通</span>
        </div>
        <p class="brand-desc">一个只做在线问诊的小产品。</p>
      </div>

      <div class="footer-note">
        <p class="strong">本项目不提供医疗诊断服务</p>
        <p>
          所有内容由 AI 根据对话自动生成，仅供参考，不能替代医生面诊。
          如有不适请及时就医，紧急情况请拨打 120。
        </p>
      </div>
    </div>
    <div class="footer-bottom">
      <span>&copy; {{ year }} 医问通</span>
      <span class="dot">·</span>
      <span>MIT License</span>
    </div>
  </footer>
</template>

<script setup lang="ts">
import { FirstAidKit } from '@element-plus/icons-vue'

const year = new Date().getFullYear()
</script>

<style scoped>
.app-footer {
  background: #fff;
  border-top: 1px solid #f0f2f5;
  margin-top: 0;
}
.footer-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 34px 24px 26px;
  display: flex;
  justify-content: space-between;
  gap: 48px;
  flex-wrap: wrap;
}
.footer-brand { min-width: 180px; }
.brand-line { display: flex; align-items: center; gap: 8px; }
.brand-mark {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: linear-gradient(135deg, #409eff, #2f7fd8);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-name { font-weight: 700; font-size: 15px; color: #303133; }
.brand-desc { font-size: 13px; color: #909399; margin-top: 10px; }

.footer-note { max-width: 520px; }
.footer-note .strong {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}
.footer-note p {
  font-size: 12px;
  line-height: 1.9;
  color: #a8abb2;
}
.footer-bottom {
  border-top: 1px solid #f7f8fa;
  padding: 14px 24px;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}
.footer-bottom .dot { margin: 0 6px; }

@media (max-width: 640px) {
  .footer-inner { flex-direction: column; gap: 24px; padding: 26px 16px 20px; }
}
</style>
