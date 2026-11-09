<template>
  <div class="home">
    <!-- 首屏：一句话说清这个产品是什么 -->
    <section class="hero">
      <div class="hero-glow"></div>
      <div class="hero-inner">
        <div class="hero-badge">
          <span class="dot"></span>
          AI 在线问诊 · 全程对话式
        </div>
        <h1 class="hero-title">
          把症状说清楚<br />
          <span class="grad">剩下的交给它</span>
        </h1>
        <p class="hero-sub">
          不是关键词搜索，是一次真正的问诊。它会追问、会收敛，最后给你一份带建议科室和
          危险信号提示的小结。
        </p>
        <div class="hero-actions">
          <el-button type="primary" size="large" class="cta" @click="startNow">
            <el-icon class="mr"><ChatDotRound /></el-icon>
            {{ auth.isLoggedIn() ? '开始问诊' : '免费开始问诊' }}
          </el-button>
          <span class="hero-note">无需挂号 · 不替代面诊</span>
        </div>

        <!-- 真实计数：接口拿不到就整条不显示，不编数字 -->
        <div v-if="stats" class="hero-stats">
          <span>已完成 <b>{{ stats.consultationCount }}</b> 次问诊</span>
          <span class="sep"></span>
          <span>知识库收录 <b>{{ stats.illnessCount }}</b> 种疾病 · <b>{{ stats.medicineCount }}</b> 种药品</span>
        </div>
      </div>
    </section>

    <!-- 问诊流程：三步，讲清「和搜索有什么不一样」 -->
    <section class="flow">
      <h2 class="section-title">一次问诊，是这样发生的</h2>
      <div class="flow-grid">
        <div v-for="(s, i) in steps" :key="i" class="flow-card">
          <div class="flow-num">{{ String(i + 1).padStart(2, '0') }}</div>
          <div class="flow-icon" :style="{ color: s.color, background: s.bg }">
            <el-icon :size="22"><component :is="s.icon" /></el-icon>
          </div>
          <h3>{{ s.title }}</h3>
          <p>{{ s.desc }}</p>
        </div>
      </div>
    </section>

    <!-- 能力清单：把「智能」翻译成用户能感知的事 -->
    <section class="features">
      <div class="feat-text">
        <h2 class="section-title left">它不是查表，是问诊</h2>
        <ul class="feat-list">
          <li v-for="(f, i) in features" :key="i">
            <span class="tick">
              <el-icon :size="12"><Check /></el-icon>
            </span>
            <div>
              <strong>{{ f.title }}</strong>
              <p>{{ f.desc }}</p>
            </div>
          </li>
        </ul>
      </div>

      <!-- 小结卡片预览：让用户提前看到交付物长什么样 -->
      <div class="feat-preview">
        <div class="preview-card">
          <div class="preview-head">
            <el-icon :size="16" color="#409eff"><Document /></el-icon>
            <span>问诊小结</span>
            <el-tag size="small" type="success" effect="plain">已生成</el-tag>
          </div>
          <div class="preview-row">
            <span class="k">主诉</span>
            <span class="v">咳嗽三天，夜间加重，有黄痰</span>
          </div>
          <div class="preview-row">
            <span class="k">症状</span>
            <span class="v">
              <el-tag v-for="t in previewSymptoms" :key="t" size="small" class="chip">{{ t }}</el-tag>
            </span>
          </div>
          <div class="preview-row">
            <span class="k">病程</span>
            <span class="v">约 3 天</span>
          </div>
          <div class="preview-row">
            <span class="k">方向</span>
            <span class="v">
              <el-tag v-for="t in previewDirections" :key="t" size="small" class="chip">{{ t }}</el-tag>
            </span>
          </div>
          <div class="preview-row">
            <span class="k">建议科室</span>
            <span class="v">
              <el-tag size="small" type="info" class="chip">呼吸内科</el-tag>
            </span>
          </div>
          <div class="preview-sep"></div>
          <p class="preview-disclaimer">
            以上为示例内容。实际小结由 AI 根据你的对话整理，不构成医疗诊断。
          </p>
        </div>
      </div>
    </section>

    <!-- 底部 CTA -->
    <section class="tail">
      <h2>现在就说说，哪里不舒服</h2>
      <p>大部分人第一句话都说不完整。没关系，它会问你。</p>
      <el-button type="primary" size="large" class="cta" @click="startNow">开始问诊</el-button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import {
  ChatDotRound,
  ChatLineSquare,
  Document,
  Check,
  Aim,
} from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import api from '@/api'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

interface HomeStats {
  consultationCount: number
  illnessCount: number
  medicineCount: number
}

/**
 * 落地页底部的真实计数。
 *
 * <p>拿不到就保持 null，整条不渲染——首页宁可少一行，也不摆一个编出来的数字。
 * 落成空串的兜底（0 次问诊）看着更像真的，反而更容易误导。
 */
const stats = ref<HomeStats | null>(null)

onMounted(async () => {
  try {
    stats.value = await api.get('/stats')
  } catch {
    stats.value = null
  }
})

const steps = [
  {
    icon: ChatLineSquare,
    title: '说清楚哪里不舒服',
    desc: '用大白话描述就行。什么时候开始的、什么感觉、有没有别的伴随症状。',
    color: '#409eff',
    bg: '#ecf5ff',
  },
  {
    icon: Aim,
    title: '它会追问关键细节',
    desc: '每轮只问最要紧的两三个问题，把模糊的描述一点点收敛成清晰的线索。',
    color: '#67c23a',
    bg: '#f0f9eb',
  },
  {
    icon: Document,
    title: '拿到一份结构化小结',
    desc: '主诉、症状标签、可能方向、建议科室，还有需要立刻就医的危险信号提示。',
    color: '#e6a23c',
    bg: '#fdf6ec',
  },
]

const features = [
  {
    title: '危险信号优先识别',
    desc: '描述里出现危及生命的信号时，它会先让你去医院，而不是继续聊天。',
  },
  {
    title: '不做确诊，不给处方',
    desc: '它只给方向和建议，说「更倾向于」而不是「你就是」。剂量一律不给。',
  },
  {
    title: '记录留存，随时回看',
    desc: '每次问诊都是一张有状态的单子。完结后小结会一直留着，随时能翻。',
  },
]

const previewSymptoms = ['干咳', '黄痰', '夜间加重']
const previewDirections = ['急性支气管炎', '病毒性感冒']

function startNow() {
  router.push({ name: 'consultation' })
}
</script>

<style scoped>
.home { background: #fff; }

/* ---------- 首屏 ---------- */
.hero {
  position: relative;
  overflow: hidden;
  padding: 88px 20px 96px;
  background: linear-gradient(180deg, #f0f7ff 0%, #ffffff 78%);
}
.hero-glow {
  position: absolute;
  top: -220px;
  left: 50%;
  transform: translateX(-50%);
  width: 900px;
  height: 560px;
  background: radial-gradient(circle, rgba(64, 158, 255, .22) 0%, rgba(64, 158, 255, 0) 68%);
  pointer-events: none;
}
.hero-inner {
  position: relative;
  max-width: 820px;
  margin: 0 auto;
  text-align: center;
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 16px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid #d9ecff;
  font-size: 13px;
  color: #337ecc;
  margin-bottom: 28px;
  box-shadow: 0 2px 12px rgba(64, 158, 255, .1);
}
.hero-badge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #67c23a;
  box-shadow: 0 0 0 3px rgba(103, 194, 58, .18);
}
.hero-title {
  font-size: 52px;
  line-height: 1.25;
  font-weight: 800;
  letter-spacing: -1px;
  color: #1a1a1a;
  margin-bottom: 22px;
}
.hero-title .grad {
  background: linear-gradient(100deg, #409eff 10%, #2f7fd8 60%, #67c23a 130%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.hero-sub {
  font-size: 16px;
  line-height: 1.85;
  color: #606266;
  max-width: 600px;
  margin: 0 auto 36px;
}
.hero-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  flex-wrap: wrap;
}
.cta {
  height: 50px;
  padding: 0 32px;
  font-size: 16px;
  border-radius: 10px;
  box-shadow: 0 8px 22px rgba(64, 158, 255, .32);
}
.cta .mr { margin-right: 6px; }
.hero-note { font-size: 13px; color: #909399; }

.hero-stats {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 22px;
  font-size: 13px;
  color: #7a8291;
}
.hero-stats b {
  font-weight: 600;
  color: #303133;
  font-variant-numeric: tabular-nums;
}
.hero-stats .sep {
  width: 1px;
  height: 12px;
  background: #dcdfe6;
}

/* ---------- 流程 ---------- */
.flow {
  max-width: 1080px;
  margin: 0 auto;
  padding: 16px 20px 72px;
}
.section-title {
  text-align: center;
  font-size: 30px;
  font-weight: 700;
  color: #1a1a1a;
  margin-bottom: 44px;
  letter-spacing: -.5px;
}
.section-title.left { text-align: left; margin-bottom: 28px; }
.flow-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}
.flow-card {
  position: relative;
  padding: 28px 24px;
  border: 1px solid #ebeef5;
  border-radius: 14px;
  background: #fff;
  transition: transform .25s, box-shadow .25s, border-color .25s;
}
.flow-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, .08);
  border-color: #d9ecff;
}
.flow-num {
  position: absolute;
  top: 20px;
  right: 22px;
  font-size: 28px;
  font-weight: 800;
  color: #f0f2f5;
  letter-spacing: -1px;
}
.flow-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
}
.flow-card h3 { font-size: 17px; margin-bottom: 10px; color: #1a1a1a; }
.flow-card p { font-size: 14px; line-height: 1.75; color: #606266; }

/* ---------- 能力 ---------- */
.features {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 56px;
  align-items: center;
  max-width: 1080px;
  margin: 0 auto;
  padding: 56px 20px 80px;
  border-top: 1px solid #f2f4f7;
}
.feat-list { list-style: none; display: flex; flex-direction: column; gap: 24px; }
.feat-list li { display: flex; gap: 14px; }
.tick {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #ecf5ff;
  color: #409eff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}
.feat-list strong { font-size: 15px; color: #1a1a1a; }
.feat-list p { font-size: 14px; line-height: 1.7; color: #606266; margin-top: 5px; }

.preview-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 14px;
  padding: 22px;
  box-shadow: 0 16px 40px rgba(0, 0, 0, .09);
}
.preview-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
  padding-bottom: 14px;
  border-bottom: 1px solid #f2f4f7;
  margin-bottom: 14px;
}
.preview-head .el-tag { margin-left: auto; }
.preview-row {
  display: flex;
  gap: 12px;
  padding: 7px 0;
  font-size: 13px;
}
.preview-row .k { flex-shrink: 0; width: 62px; color: #909399; }
.preview-row .v { flex: 1; color: #303133; line-height: 1.7; }
.chip { margin: 0 6px 6px 0; }
.preview-sep { height: 1px; background: #f2f4f7; margin: 14px 0 12px; }
.preview-disclaimer { font-size: 12px; line-height: 1.7; color: #a8abb2; }

/* ---------- 尾部 ---------- */
.tail {
  text-align: center;
  padding: 72px 20px 88px;
  background: linear-gradient(180deg, #ffffff 0%, #f5faff 100%);
}
.tail h2 { font-size: 28px; font-weight: 700; margin-bottom: 12px; color: #1a1a1a; }
.tail p { font-size: 15px; color: #606266; margin-bottom: 30px; }

@media (max-width: 860px) {
  .hero-title { font-size: 36px; }
  .flow-grid { grid-template-columns: 1fr; }
  .features { grid-template-columns: 1fr; gap: 36px; padding-top: 40px; }
  .section-title { font-size: 24px; }
}
</style>
