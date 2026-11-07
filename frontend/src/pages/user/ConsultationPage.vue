<template>
  <div class="consult-page">
    <div class="consult-wrap">
      <!-- ============ 左侧：会话列表 ============ -->
      <aside class="side">
        <div class="side-head">
          <span class="side-title">我的问诊</span>
          <el-button link type="primary" size="small" @click="newConsultation">
            <el-icon :size="13"><Plus /></el-icon>
            新建
          </el-button>
        </div>

        <div class="side-list" v-loading="listLoading">
          <div
            v-for="c in consultations"
            :key="c.id"
            :class="['side-item', { active: c.id === current?.id }]"
            @click="openConsultation(c.id)"
          >
            <div class="item-top">
              <span class="item-title">{{ c.chiefComplaint || '（未填写主诉）' }}</span>
              <span v-if="c.severity === 2" class="item-alert">
                <el-icon :size="11"><WarningFilled /></el-icon>
              </span>
            </div>
            <div class="item-meta">
              <span :class="['dot', statusDotClass(c.status)]"></span>
              <span class="item-status">{{ c.statusText }}</span>
              <span class="item-time">{{ formatTime(c.createTime) }}</span>
            </div>
          </div>

          <div v-if="!listLoading && consultations.length === 0" class="side-empty">
            <p>还没有问诊记录</p>
            <p class="hint">点上方「新建」开始第一次</p>
          </div>
        </div>
      </aside>

      <!-- ============ 右侧主区 ============ -->
      <main class="main">
        <!-- ---------- 欢迎态：描述症状，开启一次问诊 ---------- -->
        <div v-if="!current" class="welcome">
          <div class="welcome-inner">
            <div class="welcome-mark">
              <el-icon :size="26"><ChatDotRound /></el-icon>
            </div>
            <h1 class="welcome-title">哪里不舒服？</h1>
            <p class="welcome-sub">
              用大白话说就行，不用组织语言。说清楚什么时候开始的、什么感觉、有没有别的症状，
              它会接着往下问。
            </p>

            <div class="composer">
              <el-input
                ref="starterRef"
                v-model="starter"
                type="textarea"
                :autosize="{ minRows: 3, maxRows: 7 }"
                maxlength="500"
                show-word-limit
                resize="none"
                placeholder="例如：咳嗽三天了，晚上躺下更厉害，有黄痰，不发烧"
                @keydown.enter.exact.prevent="startConsultation"
              />
              <div class="composer-foot">
                <span class="foot-hint">Enter 发送 · Shift + Enter 换行</span>
                <el-button
                  type="primary"
                  :loading="creating"
                  :disabled="!starter.trim() || creating"
                  @click="startConsultation"
                >开始问诊</el-button>
              </div>
            </div>

            <div class="samples">
              <span class="samples-label">不知道怎么开口？试试：</span>
              <button
                v-for="s in samples"
                :key="s"
                class="sample-chip"
                @click="starter = s; focusStarter()"
              >{{ s }}</button>
            </div>
          </div>
        </div>

        <!-- ---------- 对话态 ---------- -->
        <template v-else>
          <header class="chat-header">
            <div class="head-left">
              <div class="head-mark">
                <el-icon :size="18"><FirstAidKit /></el-icon>
              </div>
              <div class="head-text">
                <h2>在线问诊</h2>
                <span class="subtitle">{{ current.chiefComplaint || '（未填写主诉）' }}</span>
              </div>
            </div>
            <div class="head-right">
              <el-tag :type="statusTagType(current.status)" size="small" effect="light" round>
                {{ current.statusText }}
              </el-tag>
              <template v-if="isActive">
                <el-button size="small" :loading="cancelling" @click="cancel">取消</el-button>
                <el-button type="primary" size="small" :loading="completing" @click="complete">
                  完结并生成小结
                </el-button>
              </template>
              <el-button
                v-if="isTerminal"
                size="small"
                type="danger"
                plain
                :loading="deleting"
                @click="remove"
              >删除</el-button>
            </div>
          </header>

          <!-- 实时分诊横幅：本轮消息里检测到危险信号，先于 AI 回复出现 -->
          <transition name="slide">
            <div v-if="liveTriage && liveTriage.severity === 2" class="triage-banner">
              <el-icon :size="18" class="triage-icon"><WarningFilled /></el-icon>
              <div class="triage-body">
                <strong>这可能不是能等的事</strong>
                <p>{{ liveTriage.advice }}</p>
                <p v-if="liveTriage.flags.length" class="triage-flags">
                  检测到：{{ liveTriage.flags.join('、') }}
                </p>
              </div>
            </div>
          </transition>

          <!-- 消息区 -->
          <div class="chat-messages" ref="msgContainer">
            <div v-for="(m, i) in messages" :key="i" :class="['message', m.role]">
              <div class="msg-avatar">
                <el-avatar v-if="m.role === 'user'" :size="30" class="user-avatar">
                  {{ userInitial }}
                </el-avatar>
                <div v-else class="bot-avatar">
                  <el-icon :size="15"><FirstAidKit /></el-icon>
                </div>
              </div>
              <div class="msg-col">
                <div :class="['msg-content', { 'msg-error': m.isError }]">{{ m.content }}</div>
                <el-button
                  v-if="m.isError && m.retryable"
                  link
                  type="primary"
                  size="small"
                  class="msg-retry"
                  @click="retry"
                >重试</el-button>
              </div>
            </div>

            <div v-if="typing" class="message assistant">
              <div class="msg-avatar">
                <div class="bot-avatar"><el-icon :size="15"><FirstAidKit /></el-icon></div>
              </div>
              <div class="msg-content typing"><span></span><span></span><span></span></div>
            </div>
          </div>

          <!-- 小结区（终态） -->
          <section v-if="isTerminal" class="summary">
            <div class="summary-head">
              <div class="summary-title">
                <el-icon :size="16"><Document /></el-icon>
                <h3>问诊小结</h3>
                <el-tag size="small" :type="summaryTagType" effect="plain" round>
                  {{ current.summaryStatusText }}
                </el-tag>
              </div>
              <el-button
                v-if="canRetrySummary"
                link
                type="primary"
                size="small"
                :loading="retrying"
                @click="retrySummary"
              >重新生成</el-button>
            </div>

            <!-- 生成中 -->
            <div v-if="current.summaryStatus === 1" class="summary-loading">
              <el-icon class="is-loading" :size="15"><Loading /></el-icon>
              <span>正在整理这次问诊，通常十几秒</span>
            </div>

            <!-- 失败 -->
            <el-alert
              v-else-if="current.summaryStatus === 3"
              type="warning"
              :closable="false"
              show-icon
              title="小结生成失败"
              description="对话记录已经保存，不影响回看。可以点「重新生成」再试一次。"
            />

            <!-- 成功 -->
            <template v-else-if="current.summary">
              <!-- 高危结论 -->
              <div
                v-if="current.summary.severity === 2"
                class="summary-danger"
              >
                <el-icon :size="16"><WarningFilled /></el-icon>
                <div>
                  <strong>建议尽快就医</strong>
                  <p v-if="current.summary.severityReason">{{ current.summary.severityReason }}</p>
                </div>
              </div>

              <!-- 主诉 + 病程 -->
              <div class="sum-block">
                <div class="sum-label">主诉</div>
                <div class="sum-value big">{{ current.summary.chiefComplaint }}</div>
                <div v-if="current.summary.duration" class="sum-duration">
                  <el-icon :size="12"><Clock /></el-icon>
                  病程约 {{ current.summary.duration }}
                </div>
              </div>

              <!-- 症状标签 -->
              <div v-if="current.summary.symptoms?.length" class="sum-block">
                <div class="sum-label">你提到的症状</div>
                <div class="chips">
                  <el-tag
                    v-for="s in current.summary.symptoms"
                    :key="s"
                    size="small"
                    class="chip"
                    effect="plain"
                  >{{ s }}</el-tag>
                </div>
              </div>

              <!-- 可能方向 -->
              <div class="sum-block">
                <div class="sum-label">可能的方向</div>
                <div v-if="current.summary.possibleDirections?.length" class="chips">
                  <el-tag
                    v-for="d in current.summary.possibleDirections"
                    :key="d"
                    size="small"
                    class="chip"
                    type="primary"
                    effect="light"
                  >{{ d }}</el-tag>
                </div>
                <span v-else class="muted">这次对话里的信息还不足以给出方向判断</span>
              </div>

              <!-- 建议科室 -->
              <div class="sum-block">
                <div class="sum-label">建议科室</div>
                <div v-if="current.summary.suggestedDepartments?.length">
                  <div class="chips">
                    <el-tag
                      v-for="d in current.summary.suggestedDepartments"
                      :key="d"
                      size="small"
                      class="chip"
                      type="info"
                      effect="plain"
                    >{{ d }}</el-tag>
                  </div>
                  <p v-if="current.summary.departmentReason" class="sum-reason">
                    {{ current.summary.departmentReason }}
                  </p>
                </div>
                <span v-else class="muted">—</span>
              </div>

              <!-- 居家护理 -->
              <div v-if="current.summary.careAdvice?.length" class="sum-block">
                <div class="sum-label">这几天可以这样做</div>
                <ul class="care-list">
                  <li v-for="(c, i) in current.summary.careAdvice" :key="i">{{ c }}</li>
                </ul>
              </div>

              <!-- 用药提示 -->
              <div v-if="current.summary.medicationHints?.length" class="sum-block">
                <div class="sum-label">用药提示</div>
                <ul class="care-list warn">
                  <li v-for="(h, i) in current.summary.medicationHints" :key="i">{{ h }}</li>
                </ul>
              </div>

              <!-- 危险信号 -->
              <div v-if="current.summary.redFlags?.length" class="sum-block">
                <div class="sum-label">出现这些情况请立即就医</div>
                <div class="chips">
                  <el-tag
                    v-for="r in current.summary.redFlags"
                    :key="r"
                    size="small"
                    class="chip"
                    type="danger"
                    effect="light"
                  >{{ r }}</el-tag>
                </div>
              </div>

              <!-- 严重程度 -->
              <div class="sum-block">
                <div class="sum-label">严重程度</div>
                <el-tag size="small" :type="severityTagType(current.summary.severity)" effect="light">
                  {{ severityText(current.summary.severity) }}
                </el-tag>
                <span v-if="current.summary.severityReason" class="sum-reason inline">
                  {{ current.summary.severityReason }}
                </span>
              </div>

              <p class="disclaimer">{{ current.summary.disclaimer }}</p>
            </template>
          </section>

          <!-- 输入区 -->
          <div class="chat-input">
            <el-input
              v-model="input"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 5 }"
              resize="none"
              :placeholder="isActive ? '继续说，或者补充刚才没提到的…' : '这次问诊已经结束'"
              :disabled="!isActive || typing"
              maxlength="500"
              @keydown.enter.exact.prevent="send"
            />
            <div class="input-foot">
              <span class="foot-hint">
                {{ isActive ? 'Enter 发送 · Shift + Enter 换行' : '已结束的问诊无法继续发消息' }}
              </span>
              <el-button
                type="primary"
                :disabled="!isActive || typing || !input.trim()"
                :loading="typing"
                @click="send"
              >发送</el-button>
            </div>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  FirstAidKit,
  ChatDotRound,
  Document,
  Loading,
  Plus,
  WarningFilled,
  Clock,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import api from '@/api'

/**
 * 在线问诊页。
 *
 * <p>这一页围绕「一张问诊单」而不是「一段对话」：单子有状态（进行中 / 已完成 /
 * 已取消 / 超时），有小结，终态之后不能再说话。
 *
 * <h3>两个横幅，两个时机</h3>
 *
 * <p>高危提示有两个来源，故意分开：
 * <ul>
 *   <li>{@code liveTriage} —— 来自 SSE 的 start 事件，是后端规则引擎对**本轮这句话**
 *       的判断。它先于 AI 的第一个字到达，所以用户不用等模型想完才知道要叫救护车。</li>
 *   <li>{@code summary.severity} —— 问诊完结后由模型回顾整段对话给出的总结论。
 *       它是回顾性的，比即时判断更全面，但来得晚。</li>
 * </ul>
 * 两者都可能为 2。实时那条更重要，因为它更快。
 */

interface SummaryVO {
  chiefComplaint: string
  symptoms?: string[]
  duration?: string
  possibleDirections: string[]
  suggestedDepartments: string[]
  departmentReason?: string
  medicationHints: string[]
  careAdvice?: string[]
  severity: number
  severityReason: string
  redFlags: string[]
  disclaimer: string
}

interface ConsultationVO {
  id: number
  sessionId: string
  chiefComplaint: string
  status: number
  statusText: string
  severity: number
  severityText: string
  summaryStatus: number
  summaryStatusText: string
  summary?: SummaryVO | null
  conclusion?: string | null
  lastActiveAt: string
  closedAt?: string | null
  createTime: string
}

interface Msg {
  role: 'user' | 'assistant'
  content: string
  isError?: boolean
  retryable?: boolean
}

/** 后端 TriageResult 的即时投影 */
interface LiveTriage {
  severity: number
  flags: string[]
  departments: string[]
  advice: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const consultations = ref<ConsultationVO[]>([])
const current = ref<ConsultationVO | null>(null)
const messages = ref<Msg[]>([])
const input = ref('')
const starter = ref('')
const typing = ref(false)
const msgContainer = ref<HTMLElement>()
const starterRef = ref<any>()

/** 本轮消息的即时分诊结果；切换问诊单时清空 */
const liveTriage = ref<LiveTriage | null>(null)

const listLoading = ref(false)
const creating = ref(false)
const completing = ref(false)
const cancelling = ref(false)
const deleting = ref(false)
const retrying = ref(false)

const lastUserMsg = ref('')
let pollTimer: number | undefined

const samples = [
  '咳嗽三天，晚上躺下更厉害，有黄痰',
  '从昨天开始拉肚子，一天五六次',
  '右边太阳穴一跳一跳地疼，睡不好',
]

const isActive = computed(() => current.value?.status === 0)
const isTerminal = computed(() => !!current.value && current.value.status !== 0)
/** 只有生成失败才能重试。已生成是终态——医疗记录不该被反复覆盖。 */
const canRetrySummary = computed(() => current.value?.summaryStatus === 3)
const userInitial = computed(
  () => (auth.userInfo?.realName || auth.userInfo?.username || '我').charAt(0)
)

const statusTagType = (s: number) =>
  s === 0 ? 'warning' : s === 1 ? 'success' : s === 3 ? 'info' : 'danger'
const statusDotClass = (s: number) =>
  s === 0 ? 'warn' : s === 1 ? 'ok' : s === 3 ? 'idle' : 'off'

const summaryTagType = computed(() => {
  const s = current.value?.summaryStatus
  return s === 2 ? 'success' : s === 3 ? 'danger' : 'info'
})

const severityTagType = (s: number) => (s === 2 ? 'danger' : s === 1 ? 'warning' : 'info')
const severityText = (s: number) =>
  s === 2 ? '高 · 建议尽快就医' : s === 1 ? '中 · 建议近期就诊' : '低 · 可先观察'

function formatTime(t?: string | null) {
  if (!t) return ''
  // 后端返回的是 LocalDateTime 的 ISO 串，直接截到分钟就够用，
  // 不做 dayjs 那种相对时间——列表里「3分钟前」反而不好扫
  return t.replace('T', ' ').slice(0, 16)
}

function scrollBottom() {
  nextTick(() => {
    if (msgContainer.value) msgContainer.value.scrollTop = msgContainer.value.scrollHeight
  })
}

function focusStarter() {
  nextTick(() => starterRef.value?.focus?.())
}

function stopPolling() {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

/** 小结生成中时轮询详情。只在小结没落定时跑，落定立刻停。 */
function startPolling() {
  if (pollTimer !== undefined) return
  pollTimer = window.setInterval(async () => {
    if (!current.value || current.value.summaryStatus !== 1) {
      stopPolling()
      return
    }
    await loadDetail(current.value.id, false)
  }, 2000)
}

async function loadList() {
  listLoading.value = true
  try {
    const res: any = await api.get('/consultations', { params: { page: 1, size: 30 } })
    consultations.value = res.data?.records || []
  } finally {
    listLoading.value = false
  }
}

async function loadDetail(id: number, withMessages = true) {
  const res: any = await api.get(`/consultations/${id}`)
  current.value = res.data
  if (current.value?.summaryStatus === 1) startPolling()
  else stopPolling()

  if (withMessages) {
    const h: any = await api.get(`/consultations/${id}/messages`)
    messages.value = (h.data || []).map((m: any) => ({
      role: m.role === 'user' ? 'user' : 'assistant',
      content: m.content,
    }))
    // 对话为空时给一句开场白，否则页面上什么都没有，用户不知道该干什么
    if (messages.value.length === 0 && current.value?.status === 0) {
      messages.value.push({
        role: 'assistant',
        content:
          '你好，我是 AI 问诊助手。说说哪里不舒服——什么时候开始的、什么感觉、有没有别的症状，都可以讲。',
      })
    }
    scrollBottom()
  }
}

async function openConsultation(id: number) {
  if (current.value?.id === id) return
  stopPolling()
  typing.value = false
  liveTriage.value = null
  await loadDetail(id)
}

/** 切到欢迎态，不立刻建单——用户得先写下主诉 */
function newConsultation() {
  stopPolling()
  typing.value = false
  current.value = null
  messages.value = []
  liveTriage.value = null
  starter.value = ''
  router.replace({ query: {} })
  focusStarter()
}

/** 从欢迎态真正建单，并把主诉当作第一条消息发出去 */
async function startConsultation() {
  const complaint = starter.value.trim()
  if (!complaint || creating.value) return
  creating.value = true
  try {
    const res: any = await api.post('/consultations', { chiefComplaint: complaint })
    starter.value = ''
    await loadList()
    await loadDetail(res.data.id)
    router.replace({ query: { id: res.data.id } })
    // 主诉已经写好了，别让用户再说一遍
    input.value = complaint
    await send()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '发起问诊失败，请稍后再试')
  } finally {
    creating.value = false
  }
}

async function send() {
  if (!isActive.value || !input.value.trim() || typing.value || !current.value) return
  const userMsg = input.value.trim()
  lastUserMsg.value = userMsg
  messages.value.push({ role: 'user', content: userMsg })
  input.value = ''
  scrollBottom()
  typing.value = true

  let aiIdx = -1
  let streamDone = false

  try {
    // 用 fetch 而不是 axios：axios 会把整个响应当成字符串缓冲，
    // 拿不到「数据边到边读」的流式效果
    const resp = await fetch(`/api/v1/consultations/${current.value.id}/messages/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${auth.token}`,
      },
      body: JSON.stringify({ content: userMsg }),
    })

    if (!resp.ok || !resp.body) {
      // 409/403/404 这类在 SSE 开始前就返回了，能拿到真实状态码和消息
      let msg = '发送失败，请稍后重试'
      try {
        const err = await resp.json()
        msg = err?.message || msg
      } catch {
        /* 响应不是 JSON 就用默认文案 */
      }
      typing.value = false
      messages.value.push({
        role: 'assistant',
        content: msg,
        isError: true,
        retryable: resp.status !== 409,
      })
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        streamDone = true
        break
      }
      buffer += decoder.decode(value, { stream: true })
      // SSE 用 \n\n 分隔事件块；这里按行切更宽容，data: 行逐条处理
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const raw = line.slice(5).trim()
        if (!raw) continue
        let data: any
        try {
          data = JSON.parse(raw)
        } catch {
          continue // 半截 JSON 或非 JSON 内容，跳过而不是整条流崩掉
        }

        // 第一帧：规则引擎的即时分诊，先于 AI 的第一个字
        if (data.type === 'start') {
          applyTriage(data)
          continue
        }

        if (data.type === 'error') {
          typing.value = false
          messages.value.push({
            role: 'assistant',
            content: data.message || 'AI 助手暂时无法回答，请稍后重试。',
            isError: true,
            retryable: data.retryable === true,
          })
          aiIdx = messages.value.length - 1
          continue
        }

        if (data.chunk) {
          if (aiIdx === -1) {
            typing.value = false
            messages.value.push({ role: 'assistant', content: data.chunk })
            aiIdx = messages.value.length - 1
          } else {
            messages.value[aiIdx].content += data.chunk
          }
          scrollBottom()
        }
      }
    }
  } catch {
    if (aiIdx === -1) {
      messages.value.push({
        role: 'assistant',
        content: '网络中断，这条消息可能没发出去。',
        isError: true,
        retryable: true,
      })
    }
  } finally {
    typing.value = false
    if (aiIdx === -1 && streamDone) {
      messages.value.push({
        role: 'assistant',
        content: 'AI 助手没有返回内容，请重试。',
        isError: true,
        retryable: true,
      })
    }
    scrollBottom()
  }
}

/**
 * 把 start 帧里的分诊信息落到横幅上。
 *
 * <p>只保留「本轮」这一条：它描述的是刚发出去的这句话，不是整次问诊。
 * 用户补充了新信息说「其实我还好」，下一次 start 会把横幅换掉或清掉。
 */
function applyTriage(data: any) {
  const sev = typeof data.triageSeverity === 'number' ? data.triageSeverity : 0
  if (sev <= 0) {
    liveTriage.value = null
    return
  }
  liveTriage.value = {
    severity: sev,
    flags: Array.isArray(data.redFlags) ? data.redFlags : [],
    departments: Array.isArray(data.suggestDept) ? data.suggestDept : [],
    advice: typeof data.advice === 'string' ? data.advice : '',
  }
}

function retry() {
  if (!lastUserMsg.value || typing.value) return
  const idx = messages.value.findIndex(m => m.isError)
  if (idx !== -1) messages.value.splice(idx, 1)
  const lastUserIdx = messages.value.map(m => m.role).lastIndexOf('user')
  if (lastUserIdx !== -1) messages.value.splice(lastUserIdx, 1)
  input.value = lastUserMsg.value
  send()
}

async function complete() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(
      '完结后不能再发消息，AI 会根据整段对话生成一份小结。确定完结？',
      '完结问诊',
      { confirmButtonText: '完结', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  completing.value = true
  try {
    await api.put(`/consultations/${current.value.id}/complete`)
    liveTriage.value = null
    await loadDetail(current.value.id, false)
    await loadList()
    ElMessage.success('已完结，正在生成小结')
  } finally {
    completing.value = false
  }
}

async function cancel() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(
      '取消后不会再生成小结，对话记录仍会保留。确定取消？',
      '取消问诊',
      { confirmButtonText: '取消问诊', cancelButtonText: '返回', type: 'warning' }
    )
  } catch {
    return
  }
  cancelling.value = true
  try {
    await api.put(`/consultations/${current.value.id}/cancel`)
    liveTriage.value = null
    await loadDetail(current.value.id, false)
    await loadList()
    ElMessage.success('已取消')
  } finally {
    cancelling.value = false
  }
}

async function remove() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('删除后这条问诊记录不可恢复，确定删除？', '删除问诊', {
      confirmButtonText: '删除',
      cancelButtonText: '返回',
      type: 'warning',
    })
  } catch {
    return
  }
  deleting.value = true
  try {
    await api.delete(`/consultations/${current.value.id}`)
    stopPolling()
    current.value = null
    messages.value = []
    liveTriage.value = null
    router.replace({ query: {} })
    await loadList()
    ElMessage.success('已删除')
  } finally {
    deleting.value = false
  }
}

async function retrySummary() {
  if (!current.value) return
  retrying.value = true
  try {
    await api.put(`/consultations/${current.value.id}/summary/retry`)
    await loadDetail(current.value.id, false)
    ElMessage.success('已重新提交，稍等片刻')
  } finally {
    retrying.value = false
  }
}

onMounted(async () => {
  await loadList()

  const qid = Number(route.query.id)
  if (qid && consultations.value.some(c => c.id === qid)) {
    await openConsultation(qid)
    return
  }
  // 从首页「开始问诊」进来时带着 new=1，直接给输入框而不是翻出上一次的旧单子
  if (route.query.new) {
    focusStarter()
    return
  }
  if (consultations.value.length > 0) {
    await openConsultation(consultations.value[0].id)
  } else {
    focusStarter()
  }
})

onUnmounted(stopPolling)
</script>

<style scoped>
.consult-page {
  padding: 18px;
  max-width: 1280px;
  margin: 0 auto;
}

.consult-wrap {
  display: flex;
  gap: 14px;
  height: calc(100vh - 178px);
  min-height: 540px;
}

/* ================= 左侧 ================= */
.side {
  width: 248px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #f0f2f5;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid #f5f7fa;
}
.side-title { font-size: 13px; font-weight: 600; color: #303133; }
.side-list { flex: 1; overflow-y: auto; }

.side-item {
  padding: 12px 16px;
  border-bottom: 1px solid #fafbfc;
  cursor: pointer;
  transition: background .15s;
  border-left: 2px solid transparent;
}
.side-item:hover { background: #fafbfc; }
.side-item.active {
  background: #f0f7ff;
  border-left-color: var(--primary);
}
.item-top { display: flex; align-items: center; gap: 6px; margin-bottom: 7px; }
.item-title {
  flex: 1;
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-alert { color: #f56c6c; display: flex; }
.item-meta { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #a8abb2; }
.item-status { color: #909399; }
.item-time { margin-left: auto; }
.dot { width: 5px; height: 5px; border-radius: 50%; flex-shrink: 0; }
.dot.warn { background: #e6a23c; }
.dot.ok { background: #67c23a; }
.dot.idle { background: #909399; }
.dot.off { background: #f56c6c; }

.side-empty { padding: 40px 16px; text-align: center; font-size: 13px; color: #a8abb2; }
.side-empty .hint { font-size: 12px; color: #c0c4cc; margin-top: 6px; }

/* ================= 右侧 ================= */
.main {
  flex: 1;
  min-width: 0;
  background: #fff;
  border: 1px solid #f0f2f5;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ---------- 欢迎态 ---------- */
.welcome {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
  overflow-y: auto;
  background: linear-gradient(180deg, #fbfdff 0%, #fff 42%);
}
.welcome-inner { width: 100%; max-width: 620px; }
.welcome-mark {
  width: 52px;
  height: 52px;
  border-radius: 15px;
  background: linear-gradient(135deg, #409eff, #2f7fd8);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 22px rgba(64, 158, 255, .3);
  margin-bottom: 22px;
}
.welcome-title { font-size: 27px; font-weight: 700; color: #1a1a1a; margin-bottom: 12px; }
.welcome-sub { font-size: 14px; line-height: 1.85; color: #606266; margin-bottom: 26px; }

.composer {
  border: 1px solid #e4e7ed;
  border-radius: 12px;
  padding: 4px 4px 0;
  background: #fff;
  transition: border-color .2s, box-shadow .2s;
}
.composer:focus-within {
  border-color: #a0cfff;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, .1);
}
.composer :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 12px 14px;
  font-size: 14px;
  line-height: 1.75;
}
.composer-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px 8px 14px;
  border-top: 1px solid #f5f7fa;
}
.foot-hint { font-size: 11px; color: #c0c4cc; }

.samples { margin-top: 22px; }
.samples-label { font-size: 12px; color: #a8abb2; display: block; margin-bottom: 10px; }
.sample-chip {
  display: inline-block;
  margin: 0 8px 8px 0;
  padding: 7px 13px;
  font-size: 12.5px;
  color: #606266;
  background: #f7f9fc;
  border: 1px solid #ebeef5;
  border-radius: 999px;
  cursor: pointer;
  transition: all .18s;
  font-family: inherit;
}
.sample-chip:hover {
  color: var(--primary);
  border-color: #c6e2ff;
  background: #f0f7ff;
}

/* ---------- 对话头部 ---------- */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 18px;
  border-bottom: 1px solid #f5f7fa;
  flex-shrink: 0;
}
.head-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.head-mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: #ecf5ff;
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.head-text { min-width: 0; }
.head-text h2 { font-size: 14px; font-weight: 600; color: #1a1a1a; }
.subtitle {
  font-size: 12px;
  color: #a8abb2;
  display: block;
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

/* ---------- 实时分诊横幅 ---------- */
.triage-banner {
  display: flex;
  gap: 11px;
  margin: 12px 18px 0;
  padding: 13px 15px;
  background: #fef0f0;
  border: 1px solid #fde2e2;
  border-left: 3px solid #f56c6c;
  border-radius: 9px;
  flex-shrink: 0;
}
.triage-icon { color: #f56c6c; flex-shrink: 0; margin-top: 1px; }
.triage-body strong { font-size: 13.5px; color: #c0392b; display: block; margin-bottom: 5px; }
.triage-body p { font-size: 12.5px; line-height: 1.7; color: #a83232; }
.triage-flags { margin-top: 5px; opacity: .8; }

.slide-enter-active, .slide-leave-active { transition: all .25s ease; }
.slide-enter-from, .slide-leave-to { opacity: 0; transform: translateY(-6px); }

/* ---------- 消息 ---------- */
.chat-messages { flex: 1; overflow-y: auto; padding: 20px 18px; }

.message { display: flex; gap: 10px; margin-bottom: 16px; }
.message.user { flex-direction: row-reverse; }
.msg-avatar { flex-shrink: 0; }
.user-avatar { background: #409eff; color: #fff; font-size: 13px; }
.bot-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #ecf5ff;
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
}
.msg-col { display: flex; flex-direction: column; align-items: flex-start; max-width: 74%; }
.message.user .msg-col { align-items: flex-end; }

.msg-content {
  padding: 11px 15px;
  border-radius: 12px;
  line-height: 1.75;
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.message.user .msg-content {
  background: var(--primary);
  color: #fff;
  border-top-right-radius: 4px;
}
.message.assistant .msg-content {
  background: #f7f8fa;
  color: #303133;
  border-top-left-radius: 4px;
}
.msg-error {
  background: #fef0f0 !important;
  color: #a83232 !important;
  border: 1px solid #fde2e2 !important;
}
.msg-retry { margin-top: 6px; padding: 0; height: auto; }

.typing { display: flex; align-items: center; gap: 4px; padding: 14px 15px; }
.typing span {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c0c4cc;
  animation: typing 1.4s infinite both;
}
.typing span:nth-child(2) { animation-delay: .2s; }
.typing span:nth-child(3) { animation-delay: .4s; }
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: .5; }
  30% { transform: translateY(-5px); opacity: 1; }
}

/* ---------- 小结 ---------- */
.summary {
  border-top: 1px solid #f5f7fa;
  padding: 16px 18px;
  max-height: 50%;
  overflow-y: auto;
  background: #fcfdff;
  flex-shrink: 0;
}
.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.summary-title { display: flex; align-items: center; gap: 7px; color: var(--primary); }
.summary-title h3 { font-size: 14px; font-weight: 600; color: #1a1a1a; }

.summary-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
  font-size: 13px;
  padding: 10px 0;
}

.summary-danger {
  display: flex;
  gap: 9px;
  padding: 11px 13px;
  background: #fef0f0;
  border-radius: 8px;
  border-left: 3px solid #f56c6c;
  color: #c0392b;
  margin-bottom: 16px;
}
.summary-danger strong { font-size: 13px; display: block; margin-bottom: 4px; }
.summary-danger p { font-size: 12px; line-height: 1.7; opacity: .9; }

.sum-block { margin-bottom: 15px; }
.sum-label { font-size: 11.5px; color: #a8abb2; margin-bottom: 7px; letter-spacing: .3px; }
.sum-value { font-size: 14px; color: #303133; line-height: 1.75; }
.sum-value.big { font-size: 15px; font-weight: 500; }
.sum-duration {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #909399;
  margin-top: 7px;
}
.sum-reason { font-size: 12px; line-height: 1.7; color: #909399; margin-top: 7px; }
.sum-reason.inline { margin-left: 8px; display: inline; }

.chips { display: flex; flex-wrap: wrap; }
.chip { margin: 0 6px 6px 0; }
.care-list { margin: 0; padding-left: 17px; font-size: 13px; line-height: 1.9; color: #303133; }
.care-list.warn { color: #a06a20; }
.muted { font-size: 13px; color: #c0c4cc; }

.disclaimer {
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 1.75;
  color: #a8abb2;
  padding: 10px 12px;
  background: #f7f8fa;
  border-radius: 7px;
}

/* ---------- 输入区 ---------- */
.chat-input {
  border-top: 1px solid #f5f7fa;
  padding: 12px 18px 14px;
  flex-shrink: 0;
}
.chat-input :deep(.el-textarea__inner) {
  border: 1px solid #e4e7ed;
  border-radius: 9px;
  padding: 9px 12px;
  font-size: 14px;
  line-height: 1.7;
  box-shadow: none;
  transition: border-color .2s, box-shadow .2s;
}
.chat-input :deep(.el-textarea__inner:focus) {
  border-color: #a0cfff;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, .1);
}
.input-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 9px;
}

@media (max-width: 900px) {
  .consult-page { padding: 10px; }
  .consult-wrap { height: calc(100vh - 150px); }
  .side { display: none; }
  .msg-col { max-width: 84%; }
}
</style>
