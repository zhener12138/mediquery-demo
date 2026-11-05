<template>
  <div class="page-container">
    <div class="chat-container">
      <div class="chat-header">
        <el-icon :size="24"><FirstAidKit /></el-icon>
        <h2>智能医生</h2>
        <span class="subtitle">AI-powered by Tongyi Qianwen</span>
      </div>
      <div class="chat-messages" ref="msgContainer">
        <div v-for="(msg, idx) in messages" :key="idx" :class="['message', msg.role]">
          <div class="msg-avatar">
            <el-avatar v-if="msg.role === 'user'" :size="36">{{ auth.userInfo?.realName?.charAt(0) || '我' }}</el-avatar>
            <el-icon v-else :size="36" color="#409eff"><FirstAidKit /></el-icon>
          </div>
          <div :class="['msg-content', { 'msg-error': msg.isError }]">{{ msg.content }}<el-button v-if="msg.isError && msg.retryable" link type="primary" size="small" @click="retry">重试</el-button></div>
        </div>
        <div v-if="typing" class="message assistant">
          <div class="msg-avatar"><el-icon :size="36" color="#409eff"><FirstAidKit /></el-icon></div>
          <div class="msg-content typing"><span></span><span></span><span></span></div>
        </div>
      </div>
      <div class="chat-input">
        <el-input v-model="input" placeholder="输入您的健康疑问..." @keyup.enter="send" :disabled="typing" size="large" maxlength="500" show-word-limit>
          <template #append><el-button type="primary" @click="send" :disabled="typing || !input.trim()" :loading="typing">发送</el-button></template>
        </el-input>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import api from '@/api'

const auth = useAuthStore()
const input = ref('')
const typing = ref(false)
const messages = ref<{ role: string; content: string; isError?: boolean; retryable?: boolean }[]>([])
const msgContainer = ref<HTMLElement>()
const sessionId = ref('')  // 会话ID，用于多轮RAG上下文复用
const lastUserMsg = ref('')  // 最后一条用户消息，供失败重试

function scrollBottom() {
  nextTick(() => {
    if (msgContainer.value) msgContainer.value.scrollTop = msgContainer.value.scrollHeight
  })
}

async function send() {
  if (!input.value.trim() || typing.value) return
  const userMsg = input.value.trim()
  lastUserMsg.value = userMsg
  messages.value.push({ role: 'user', content: userMsg })
  input.value = ''
  scrollBottom()
  typing.value = true

  // 打字中：只显示动画，不推空气泡
  let aiIdx = -1  // 等收到第一个chunk再push
  let streamDone = false

  try {
    const token = auth.token
    const body = JSON.stringify({ content: userMsg, sessionId: sessionId.value || undefined })
    const resp = await fetch('/api/v1/ai-doctor/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
      body
    })
    const reader = resp.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) { streamDone = true; break }
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('data:')) {
          try {
            const raw = line.slice(5).trim()
            if (!raw) continue
            const data = JSON.parse(raw)
            if (data.type === 'error') {
              // 后端明确告知 AI 服务故障：不要伪装成正常回答
              typing.value = false
              messages.value.push({
                role: 'assistant',
                content: data.message || '智能医生暂时无法连接，请稍后重试。',
                isError: true,
                retryable: data.retryable === true
              })
              aiIdx = messages.value.length - 1
              continue
            }
            if (data.chunk) {
              if (aiIdx === -1) {
                // 第一个chunk到达：关闭思考动画，推气泡
                typing.value = false
                messages.value.push({ role: 'assistant', content: data.chunk })
                aiIdx = messages.value.length - 1
              } else {
                messages.value[aiIdx].content += data.chunk
              }
            }
            if (data.sessionId && !sessionId.value) {
              sessionId.value = data.sessionId
            }
          } catch {}
        }
      }
    }
  } catch (e) {
    if (aiIdx === -1) {
      messages.value.push({ role: 'assistant', content: '智能医生暂时无法连接，请稍后重试。', isError: true, retryable: true })
    }
  } finally {
    typing.value = false
    // SSE 流正常结束但没收到任何数据
    if (aiIdx === -1 && streamDone) {
      messages.value.push({ role: 'assistant', content: '智能医生暂时无法连接，请稍后重试。', isError: true, retryable: true })
    }
    scrollBottom()
  }
}

// 重试：移除失败气泡，用上次的输入重新发送
function retry() {
  if (!lastUserMsg.value || typing.value) return
  const idx = messages.value.findIndex(m => m.isError)
  if (idx !== -1) messages.value.splice(idx, 1)
  // 移除上一次的用户气泡，避免重复显示
  const lastUserIdx = messages.value.map(m => m.role).lastIndexOf('user')
  if (lastUserIdx !== -1) messages.value.splice(lastUserIdx, 1)
  input.value = lastUserMsg.value
  send()
}

onMounted(() => { messages.value.push({ role: 'assistant', content: '您好！我是医问通智能医生，请问有什么可以帮您的？' }) })
</script>

<style scoped>
.chat-container { max-width: 800px; margin: 0 auto; background: #fff; border-radius: 8px; box-shadow: 0 2px 12px rgba(0,0,0,.06); display: flex; flex-direction: column; height: calc(100vh - 180px); }
.chat-header { display: flex; align-items: center; gap: 12px; padding: 16px 24px; border-bottom: 1px solid #ebeef5; }
.chat-header h2 { font-size: 18px; }
.subtitle { font-size: 12px; color: var(--text-secondary); }
.chat-messages { flex: 1; overflow-y: auto; padding: 24px; }
.message { display: flex; gap: 12px; margin-bottom: 20px; }
.message.user { flex-direction: row-reverse; }
.msg-content { background: var(--primary); color: #fff; }
.message.assistant .msg-content { background: #f4f4f5; }
.msg-content { padding: 12px 16px; border-radius: 12px; max-width: 70%; line-height: 1.6; font-size: 14px; white-space: pre-wrap; word-break: break-word; }
.chat-input { padding: 16px 24px; border-top: 1px solid #ebeef5; }
.typing span { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #909399; margin: 0 2px; animation: typing 1.4s infinite both; }
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes typing { 0%,60%,100% { transform: translateY(0); } 30% { transform: translateY(-6px); } }
.msg-error {
  background: #fef0f0 !important;
  color: #f56c6c !important;
  border: 1px solid #fde2e2;
}
.msg-error .el-button { margin-left: 8px; }
</style>
