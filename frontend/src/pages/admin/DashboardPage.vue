<template>
  <div class="dashboard">
    <h2>数据大屏</h2>
    <el-row :gutter="16" class="stats-row">
      <el-col :span="4" v-for="s in stats" :key="s.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-value" :style="{color: s.color}">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:24px">
      <el-col :span="12">
        <el-card>
          <template #header>热门疾病 TOP 10</template>
          <div ref="popularChart" style="height:300px"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>系统概况</template>
          <el-empty description="更多统计开发中..." :image-size="80" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, nextTick } from 'vue'
// 按需引入，不再是 import * as echarts from 'echarts'。
//
// 原来那行让 echarts 整包进构建产物——实测 DashboardPage 的 chunk 高达 1014KB，
// 其中绝大部分是项目里从来没调用过的图表类型（地图、3D、关系图、雷达……）。
// 这里只引真正用到的：柱状图 + 三个组件 + Canvas 渲染器。
//
// 代价：以后要用新图表必须手动往 echarts.use([...]) 里加一行，否则图表静默不显示。
// 这是有意的取舍——默认全量引进来的体积只会涨不会降。
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, GridComponent, TooltipComponent, TitleComponent, CanvasRenderer])
import api from '@/api'

const stats = reactive([
  { label: '用户总数', value: 0, color: '#409eff' },
  { label: '疾病条目', value: 0, color: '#67c23a' },
  { label: '药品条目', value: 0, color: '#e6a23c' },
  { label: '医生数量', value: 0, color: '#f56c6c' },
  { label: '待处理反馈', value: 0, color: '#909399' },
  { label: '反馈总数', value: 0, color: '#b37feb' },
])

const popularChart = ref<HTMLElement>()

onMounted(async () => {
  try {
    const [overview, popular] = await Promise.all([
      api.get('/dashboard/overview'),
      api.get('/dashboard/popular/illnesses'),
    ])
    const d = overview.data
    stats[0].value = d.userCount
    stats[1].value = d.illnessCount
    stats[2].value = d.medicineCount
    stats[3].value = d.doctorCount
    stats[4].value = d.pendingFeedbackCount
    stats[5].value = d.feedbackCount

    if (popular.data && popularChart.value) {
      const chart = echarts.init(popularChart.value)
      chart.setOption({
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'category', data: popular.data.map((i: any) => i.name).reverse(), axisLabel: { rotate: 30 } },
        yAxis: { type: 'value', name: '浏览量' },
        series: [{ type: 'bar', data: popular.data.map((i: any) => i.viewCount).reverse(), itemStyle: { color: '#409eff' } }],
        grid: { left: 50, right: 20, bottom: 60, top: 20 }
      })
    }
  } catch (e) {}
})
</script>

<style scoped>
.dashboard h2 { margin-bottom: 24px; }
.stat-card { text-align: center; }
.stat-value { font-size: 32px; font-weight: 700; }
.stat-label { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
</style>
