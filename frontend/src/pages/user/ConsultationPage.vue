<template>
  <div class="page-container">
    <el-breadcrumb separator="/" class="crumb">
      <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item>在线问诊</el-breadcrumb-item>
    </el-breadcrumb>

    <div class="consult-wrap">
      <!-- 左侧：会话列表 -->
      <aside class="side">
        <div class="side-head">
          <span>我的问诊</span>
          <el-button link type="primary" size="small" @click="startNew" :disabled="creating">
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
            <div class="side-title">{{ c.chiefComplaint || '（未填写主诉）' }}</div>
            <div class="side-meta">
              <el-tag size="small" :type="statusTagType(c.status)" effect="plain">
                {{ c.statusText }}
              </el-tag>
              <span class="side-time">{{ formatTime(c.createTime) }}</span>
            </div>
          </div>
          <el-empty v-if="!listLoading && consultations.length === 0" description="还没有问诊记录" :image-size="60" />
        </div>
      </aside>

      <!-- 右侧：对话主体 -->
      <main class="main">
        <!-- 未选单时的引导 -->
        <div v-if="!current" class="empty-guide">
          <el-empty description="选择一条问诊记录，或新建一次问诊" />
          <el-button type="primary" @click="startNew">开始问诊</el-button>
        </div>

        <template v-else>
          <header class="chat-header">
            <div class="head-left">
              <el-icon :size="22" color="#409eff"><FirstAidKit /></el-icon>
              <div>
                <h2>在线问诊</h2>
                <span class="subtitle">主诉：{{ current.chiefComplaint || '（未填写）' }}</span>
              </div>
            </div>
            <div class="head-right">
              <el-tag :type="statusTagType(current.status)" effect="dark">{{ current.statusText }}</el-tag>
              <el-button
                v-if="isActive"
                type="primary"
                size="small"
                :loading="completing"
                @click="complete"
              >完结问诊</el-button>
              <el-button
                v-if="isActive"
                size="small"
                :loading="cancelling"
                @click="cancel"
              >取消</el-button>
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

          <!-- 高危横幅：severity=2 才出现 -->
          <el-alert
            v-if="current.summary && current.summary.severity === 2"
            class="danger-banner"
            type="error"
            :closable="false"
            show-icon
            title="⚠️ 你描述的情况可能需要紧急处理"
          >
            <template #default>
              <p>请立即就医或拨打 120。以下内容不能替代医生面诊。</p>
              <p v-if="current.summary.severityReason" class="reason">
                判断依据：{{ current.summary.severityReason }}
              </p>
            </template>
          </el-alert>

          <!-- 消息区 -->
          <div class="chat-messages" ref="msgContainer">
            <div v-for="(m, i) in messages" :key="i" :class="['message', m.role]">
              <div class="msg-avatar">
                <el-avatar v-if="m.role === 'user'" :size="36">
                  {{ auth.userInfo?.realName?.charAt(0) || '我' }}
                </el-avatar>
                <el-icon v-else :size="36" color="#409eff"><FirstAidKit /></el-icon>
              </div>
              <div :class="['msg-content', { 'msg-error': m.isError }]">
                {{ m.content }}
                <el-button
                  v-if="m.isError && m.retryable"
                  link
                  type="primary"
                  size="small"
                  @click="retry"
                >重试</el-button>
              </div>
            </div>

            <div v-if="typing" class="message assistant">
              <div class="msg-avatar"><el-icon :size="36" color="#409eff"><FirstAidKit /></el-icon></div>
              <div class="msg-content typing"><span></span><span></span><span></span></div>
            </div>
          </div>

          <!-- 小结区（终态时显示） -->
          <section v-if="isTerminal" class="summary-box">
            <div class="summary-head">
              <h3>问诊小结</h3>
              <el-tag size="small" :type="summaryTagType">{{ current.summaryStatusText }}</el-tag>
              <el-button
                v-if="canRetrySummary"
                link
                type="primary"
                size="small"
                :loading="retrying"
                @click="retrySummary"
              >重新生成</el-button>
            </div>

            <div v-if="current.summaryStatus === 1" class="summary-loading">
              <el-icon class="is-loading" :size="16"><Loading /></el-icon>
              <span>AI 正在整理这次问诊，通常十几秒，无需刷新页面</span>
            </div>

            <el-alert
              v-else-if="current.summaryStatus === 3"
              type="warning"
              :closable="false"
              show-icon
              title="小结生成失败"
              description="问诊记录已经保存，不影响你查看上面的对话。可以点「重新生成」再试一次。"
            />

            <template v-else-if="current.summary">
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="主诉">{{ current.summary.chiefComplaint }}</el-descriptions-item>
                <el-descriptions-item label="可能方向">
                  <el-tag
                    v-for="d in current.summary.possibleDirections"
                    :key="d"
                    size="small"
                    class="chip"
                  >{{ d }}</el-tag>
                  <span v-if="!current.summary.possibleDirections?.length" class="muted">—</span>
                </el-descriptions-item>
                <el-descriptions-item label="建议科室">
                  <el-tag
                    v-for="d in current.summary.suggestedDepartments"
                    :key="d"
                    size="small"
                    type="info"
                    class="chip"
                  >{{ d }}</el-tag>
                  <span v-if="!current.summary.suggestedDepartments?.length" class="muted">—</span>
                </el-descriptions-item>
                <el-descriptions-item label="用药提示">
                  <ul class="hint-list">
                    <li v-for="(h, i) in current.summary.medicationHints" :key="i">{{ h }}</li>
                  </ul>
                  <span v-if="!current.summary.medicationHints?.length" class="muted">—</span>
                </el-descriptions-item>
                <el-descriptions-item label="严重程度">
                  <el-tag size="small" :type="severityTagType(current.summary.severity)">
                    {{ severityText(current.summary.severity) }}
                  </el-tag>
                  <span v-if="current.summary.severityReason" class="reason-inline">
                    {{ current.summary.severityReason }}
                  </span>
                </el-descriptions-item>
                <el-descriptions-item v-if="current.summary.redFlags?.length" label="危险信号">
                  <el-tag v-for="r in current.summary.redFlags" :key="r" type="danger" size="small" class="chip">
                    {{ r }}
                  </el-tag>
                </el-descriptions-item>
              </el-descriptions>
              <p class="disclaimer">{{ current.summary.disclaimer }}</p>
            </template>
          </section>

          <!-- 输入区 -->
          <div class="chat-input">
            <el-input
              v-model="input"
              :placeholder="isActive ? '继续描述你的情况…' : '这次问诊已结束，不能再发消息'"
              :disabled="!isActive || typing"
              @keyup.enter="send"
              size="large"
              maxlength="500"
              show-word-limit
            >
              <template #append>
                <el-button
                  type="primary"
                  :disabled="!isActive || typing || !input.trim()"
                  :loading="typing"
                  @click="send"
                >发送</el-button>
              </template>
            </el-input>
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FirstAidKit, Loading } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import api from '@/api'

/**
 * 在线问诊页。
 *
 * <p>与旧的 {@code AiDoctorPage} 的本质区别：这一页围绕「一张问诊单」而不是
 * 「一段对话」。单子有状态（进行中/已完成/已取消/超时），有小结，
 * 终态之后不能再说话。旧页面只有消息，关掉浏览器就什么都找不回来了。
 */

interface SummaryVO {
  chiefComplaint: string
  possibleDirections: string[]
  suggestedDepartments: string[]
  medicationHints: string[]
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

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const consultations = ref<ConsultationVO[]>([])
const current = ref<ConsultationVO | null>(null)
const messages = ref<Msg[]>([])
const input = ref('')
const typing = ref(false)
const msgContainer = ref<HTMLElement>()

const listLoading = ref(false)
const creating = ref(false)
const completing = ref(false)
const cancelling = ref(false)
const deleting = ref(false)
const retrying = ref(false)

const lastUserMsg = ref('')
let pollTimer: number | undefined

const isActive = computed(() => current.value?.status === 0)
const isTerminal = computed(() => !!current.value && current.value.status !== 0)
/** 只有生成失败才能重试。已生成是终态——医疗记录不该被反复覆盖。 */
const canRetrySummary = computed(() => current.value?.summaryStatus === 3)

const statusTagType = (s: number) =>
  s === 0 ? 'warning' : s === 1 ? 'success' : s === 3 ? 'info' : 'danger'

const summaryTagType = computed(() => {
  const s = current.value?.summaryStatus
  return s === 2 ? 'success' : s === 3 ? 'danger' : 'info'
})

const severityTagType = (s: number) => (s === 2 ? 'danger' : s === 1 ? 'warning' : 'info')
const severityText = (s: number) => (s === 2 ? '高 · 建议尽快就医' : s === 1 ? '中 · 建议近期就诊' : '低 · 可先观察')

function formatTime(t?: string | null) {
  if (!t) return ''
  // 后端返回的是 LocalDateTime 的 ISO 串，直接截到分钟就够用，
  // 不做 dayjs 那种相对时间——列表里"3分钟前"反而不好扫
  return t.replace('T', ' ').slice(0, 16)
}

function scrollBottom() {
  nextTick(() => {
    if (msgContainer.value) msgContainer.value.scrollTop = msgContainer.value.scrollHeight
  })
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
    const res: any = await api.get('/consultations', { params: { page: 1, size: 20 } })
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
        content: '你好，我是 AI 问诊助手。请描述一下你的症状、持续多久了，有没有其他不舒服。',
      })
    }
    scrollBottom()
  }
}

async function openConsultation(id: number) {
  if (current.value?.id === id) return
  stopPolling()
  typing.value = false
  await loadDetail(id)
}

async function startNew() {
  creating.value = true
  try {
    const { value } = await ElMessageBox.prompt(
      '请简要描述你的症状（例如：咳嗽三天，有痰）',
      '发起问诊',
      {
        confirmButtonText: '开始',
        cancelButtonText: '取消',
        inputPattern: /\S/,
        inputErrorMessage: '请描述一下症状',
        inputValidator: (v: string) => (v && v.trim().length <= 500) || '不能超过 500 字',
      }
    )
    const res: any = await api.post('/consultations', { chiefComplaint: value.trim() })
    await loadList()
    await openConsultation(res.data.id)
    router.replace({ query: { id: res.data.id } })
    // 新建后自动把主诉作为第一条用户消息发出去，用户不用再说一遍
    input.value = value.trim()
    await send()
  } catch (e: any) {
    // ElMessageBox 的「取消」也走 reject，不该弹错误
    if (e !== 'cancel' && e !== 'close' && e?.response) {
      ElMessage.error(e.response?.data?.message || '发起问诊失败')
    }
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
    // 拿不到"数据边到边读"的流式效果
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
      messages.value.push({ role: 'assistant', content: msg, isError: true, retryable: resp.status !== 409 })
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
  } catch (e) {
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
      '完结后不能再发消息，AI 会根据对话生成一份小结。确定完结？',
      '完结问诊',
      { confirmButtonText: '完结', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  completing.value = true
  try {
    await api.put(`/consultations/${current.value.id}/complete`)
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
    await ElMessageBox.confirm('取消后不会再生成小结，对话记录仍会保留。确定取消？', '取消问诊', {
      confirmButtonText: '取消问诊',
      cancelButtonText: '返回',
      type: 'warning',
    })
  } catch {
    return
  }
  cancelling.value = true
  try {
    await api.put(`/consultations/${current.value.id}/cancel`)
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
  } else if (consultations.value.length > 0) {
    await openConsultation(consultations.value[0].id)
  }
})
</script>

<style scoped>
.crumb { margin-bottom: 16px; }

.consult-wrap {
  display: flex;
  gap: 16px;
  height: calc(100vh - 220px);
  min-height: 520px;
}

/* ---------- 左侧列表 ---------- */
.side {
  width: 240px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .06);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #ebeef5;
  font-size: 14px;
  font-weight: 600;
}
.side-list { flex: 1; overflow-y: auto; }
.side-item {
  padding: 12px 16px;
  border-bottom: 1px solid #f5f7fa;
  cursor: pointer;
  transition: background .15s;
}
.side-item:hover { background: #f5f7fa; }
.side-item.active { background: #ecf5ff; border-left: 3px solid var(--primary); padding-left: 13px; }
.side-title {
  font-size: 13px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-bottom: 6px;
}
.side-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.side-time { font-size: 11px; color: var(--text-secondary); }

/* ---------- 右侧主区 ---------- */
.main {
  flex: 1;
  min-width: 0;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .06);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.empty-guide {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  border-bottom: 1px solid #ebeef5;
}
.head-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.head-left h2 { font-size: 16px; margin-bottom: 2px; }
.subtitle {
  font-size: 12px;
  color: var(--text-secondary);
  display: block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

.danger-banner { margin: 12px 20px 0; }
.danger-banner .reason { margin-top: 6px; font-size: 12px; opacity: .85; }

.chat-messages { flex: 1; overflow-y: auto; padding: 20px; }

.message { display: flex; gap: 12px; margin-bottom: 18px; }
.message.user { flex-direction: row-reverse; }
.message.user .msg-content { background: var(--primary); color: #fff; }
.message.assistant .msg-content {
  background: #f4f4f5;
  color: var(--text);
  border: 1px solid #e4e7ed;
}
.msg-content {
  padding: 12px 16px;
  border-radius: 12px;
  max-width: 72%;
  line-height: 1.6;
  font-size: 14px;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-error {
  background: #fef0f0 !important;
  color: #a83232 !important;
  border: 1px solid #fde2e2 !important;
}
.msg-error .el-button { margin-left: 8px; }

.typing span {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #909399;
  margin: 0 2px;
  animation: typing 1.4s infinite both;
}
.typing span:nth-child(2) { animation-delay: .2s; }
.typing span:nth-child(3) { animation-delay: .4s; }
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-6px); }
}

/* ---------- 小结 ---------- */
.summary-box {
  border-top: 1px solid #ebeef5;
  padding: 16px 20px;
  max-height: 46%;
  overflow-y: auto;
  background: #fafcff;
}
.summary-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.summary-head h3 { font-size: 15px; }
.summary-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-secondary);
  font-size: 13px;
  padding: 12px 0;
}
.chip { margin: 0 6px 6px 0; }
.hint-list { margin: 0; padding-left: 18px; line-height: 1.8; }
.muted { color: var(--text-secondary); }
.reason-inline { margin-left: 8px; font-size: 12px; color: var(--text-secondary); }
.disclaimer {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-secondary);
  padding: 10px 12px;
  background: #f4f4f5;
  border-radius: 6px;
  border-left: 3px solid #c0c4cc;
}

.chat-input { padding: 14px 20px; border-top: 1px solid #ebeef5; }

@media (max-width: 900px) {
  .side { display: none; }
}
</style>
