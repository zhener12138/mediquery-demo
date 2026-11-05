package com.mediquery.module.consultation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediquery.common.exception.AiServiceException;
import com.mediquery.common.exception.BusinessException;
import com.mediquery.config.AsyncConfig;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.integration.ai.KnowledgeRetrievalService;
import com.mediquery.module.ai_doctor.entity.AiChatRecord;
import com.mediquery.module.ai_doctor.mapper.AiChatRecordMapper;
import com.mediquery.module.consultation.entity.Consultation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 问诊对话的消息流。
 *
 * <h3>与 {@code AiDoctorService.chatStream} 的关系</h3>
 *
 * <p>这个类是它的演进版，不是复制品。三处实质差别：
 *
 * <ol>
 *   <li><b>消息归属一张有状态的单子</b>。发消息前必须过 {@code requireActive}：
 *       已完结的单子不能再聊。老的 `AiDoctorService` 没有任何状态概念，
 *       用户可以在一个「已经结束」的界面上继续说话，而小结早就生成完了。</li>
 *   <li><b>刷新 {@code last_active_at}</b>。超时判定靠这个字段，不刷新的话
 *       每张单子都会在创建 30 分钟后被误关。</li>
 *   <li><b>用专门的线程池</b>。老实现是 `new Thread(...).start()` —— 每次对话
 *       新建一个裸线程，没有上限、没有排队、线程名全是 `Thread-N`。
 *       并发一高就是 OOM 或者线程数打满。</li>
 * </ol>
 *
 * <h3>为什么仍然不是真流式</h3>
 *
 * <p>`DashScopeClient.chatWithHistory` 用的是同步 `gen.call(param)`，一次性拿到
 * 完整回复，然后按 3 个字符切片 + `Thread.sleep(20)` 假装在打字。
 * 这是**已知的设计债**（设计文档 14.4），不是疏忽：换成 SDK 的流式接口需要
 * 改动 `DashScopeClient` 的返回类型、错误处理时机、以及「出错时已经吐了一半」
 * 的语义，属于独立的一次改造。阶段二先保证状态机和数据结构对，流式留给阶段三。
 */
@Service
public class ConsultationMessageService {

    private static final Logger log = LoggerFactory.getLogger(ConsultationMessageService.class);

    /** SSE 连接超时。要大于 AI 调用的最坏耗时。 */
    private static final long SSE_TIMEOUT_MS = 120_000L;

    /** 一次对话带多少条历史给模型。 */
    private static final int HISTORY_LIMIT = 20;

    /** 假流式每片多少字符 / 间隔多少毫秒。 */
    private static final int CHUNK_SIZE = 3;
    private static final long CHUNK_SLEEP_MS = 20L;

    private final ConsultationService consultationService;
    private final DashScopeClient dashScopeClient;
    private final KnowledgeRetrievalService knowledgeService;
    private final AiChatRecordMapper chatRecordMapper;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor sseExecutor;

    public ConsultationMessageService(ConsultationService consultationService,
                                      DashScopeClient dashScopeClient,
                                      KnowledgeRetrievalService knowledgeService,
                                      AiChatRecordMapper chatRecordMapper,
                                      ObjectMapper objectMapper,
                                      @Qualifier(AsyncConfig.SSE_EXECUTOR) ThreadPoolExecutor sseExecutor) {
        this.consultationService = consultationService;
        this.dashScopeClient = dashScopeClient;
        this.knowledgeService = knowledgeService;
        this.chatRecordMapper = chatRecordMapper;
        this.objectMapper = objectMapper;
        this.sseExecutor = sseExecutor;
    }

    /**
     * 发一条消息并流式返回回答。
     *
     * <p>方法本身**同步**完成三件事（状态校验、存用户消息、刷新活跃时间）之后
     * 才把 AI 调用丢进线程池 —— 这样前端如果收到了 409，说明消息压根没入库，
     * 不存在「报错了但话已经记进去了」这种状态。
     *
     * <p>SSE 一旦开始推送，HTTP 状态码已经发出去了。此后的任何错误都只能通过
     * `type=error` 事件传递，不能再改状态码。所以状态校验必须在返回 emitter 之前做。
     */
    public SseEmitter stream(Integer consultationId, Integer patientId, String content) {
        if (content == null || content.isBlank()) {
            throw BusinessException.badRequest("消息内容不能为空");
        }

        // 这一步会抛 404/403/409 —— 都在 SSE 开始之前，前端能拿到正确状态码
        Consultation c = consultationService.requireActive(consultationId, patientId);
        String sid = c.getSessionId();

        saveRecord(patientId, sid, "user", content.trim());
        consultationService.touch(consultationId);

        var emitter = new SseEmitter(SSE_TIMEOUT_MS);
        var secCtx = SecurityContextHolder.getContext();

        try {
            sseExecutor.execute(() -> streamInWorker(emitter, secCtx, patientId, sid, content));
        } catch (RejectedExecutionException e) {
            // 线程池满：此时消息已经存了，但回答不会来。
            // 存了用户消息却回不了，比什么都不存更糟 —— 用户刷新会看到自己孤零零一句话。
            log.warn("SSE 线程池已满 consultationId={}，拒绝请求", consultationId);
            throw BusinessException.badRequest("当前问诊人数较多，请稍后再试");
        }
        return emitter;
    }

    private void streamInWorker(SseEmitter emitter, SecurityContext secCtx,
                                Integer patientId, String sid, String message) {
        SecurityContextHolder.setContext(secCtx);
        try {
            // start 事件先发：把 HTTP 响应头冲出去，避免异步分派被安全过滤器二次拦截
            send(emitter, Map.of("type", "start", "sessionId", sid));

            var history = recentHistory(patientId, sid);
            var knowledge = knowledgeService.retrieve(message);
            var reply = dashScopeClient.chatWithHistory(sid, message, history, knowledge);
            saveRecord(patientId, sid, "assistant", reply);

            for (int i = 0; i < reply.length(); i += CHUNK_SIZE) {
                int end = Math.min(i + CHUNK_SIZE, reply.length());
                send(emitter, Map.of("sessionId", sid, "chunk", reply.substring(i, end)));
                sleepQuietly();
            }

            // meta 事件：severity 由小结阶段最终确定，这里先给一个「未知」占位，
            // 让前端拿到统一的事件序列，不必为「有没有 meta」写两套解析。
            var meta = new HashMap<String, Object>();
            meta.put("type", "meta");
            meta.put("severity", null);
            meta.put("suggestDept", null);
            meta.put("note", "严重度在问诊完结、生成小结后确定");
            send(emitter, meta);

            send(emitter, Map.of("type", "end", "summarizing", false));
            emitter.complete();
        } catch (AiServiceException e) {
            log.warn("AI 服务不可用 consultationSession={} code={} msg={}",
                    sid, e.getUpstreamCode(), e.getMessage());
            sendError(emitter, sid, e);
        } catch (Exception e) {
            log.error("SSE 流式输出异常 session={}", sid, e);
            sendError(emitter, sid, null);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 取最近 N 条历史并按时间正序返回。
     *
     * <p>必须 desc + limit 再反转：直接 asc + limit 拿到的是**最早**的 N 条，
     * 长会话里模型永远看不到最新内容（这是老实现的真实 bug，已在本类修正）。
     * 加 id 作二级排序是因为 createTime 是秒级精度，同秒插入顺序不稳定。
     */
    private List<AiChatRecord> recentHistory(Integer patientId, String sid) {
        var recent = chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, patientId)
                .eq(AiChatRecord::getSessionId, sid)
                .orderByDesc(AiChatRecord::getCreateTime)
                .orderByDesc(AiChatRecord::getId)
                .last("LIMIT " + HISTORY_LIMIT));
        var ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);
        return ordered;
    }

    private void send(SseEmitter emitter, Map<String, ?> payload) throws Exception {
        emitter.send(SseEmitter.event().name("delta")
                .data(objectMapper.writeValueAsString(payload)));
    }

    /**
     * 下发错误事件后正常关闭。
     *
     * <p>不用 {@code completeWithError}：那会让 Spring 走异常解析流程，
     * 客户端只看到连接断开，拿不到可读原因。发一条 `type=error` 再 complete，
     * 前端能区分「AI 故障」和「AI 回答了」。
     */
    private void sendError(SseEmitter emitter, String sid, AiServiceException e) {
        try {
            var payload = new HashMap<String, Object>();
            payload.put("type", "error");
            payload.put("sessionId", sid);
            payload.put("message", e != null ? e.getMessage() : "AI 服务暂时不可用，请稍后再试");
            payload.put("code", e != null ? e.getUpstreamCode() : "UNKNOWN");
            payload.put("retryable", e != null && e.getUpstreamStatus() == 429);
            send(emitter, payload);
            emitter.complete();
        } catch (Exception sendFail) {
            log.warn("下发错误事件失败", sendFail);
            emitter.completeWithError(sendFail);
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(CHUNK_SLEEP_MS);
        } catch (InterruptedException e) {
            // 客户端断开会导致 send 抛异常从而提前退出；这里的 sleep 被打断说明线程池在关停，
            // 恢复中断标记让上层决定，不吞掉
            Thread.currentThread().interrupt();
        }
    }

    private void saveRecord(Integer patientId, String sid, String role, String content) {
        var record = new AiChatRecord();
        record.setUserId(patientId);
        record.setSessionId(sid);
        record.setRole(role);
        record.setContent(content);
        chatRecordMapper.insert(record);
    }

    // ───────────── 2.5 附带：会话历史查询（供前端刷新后恢复对话） ─────────────

    /**
     * 拉取一张单子的全部消息。
     *
     * <p>上限 200 条：一次问诊不太可能更长了，超过的部分前端也用不上，
     * 而数据库里可能有几万条历史会话，不设限就是一次全表扫描。
     */
    public List<Map<String, Object>> history(Integer consultationId, Integer patientId) {
        Consultation c = consultationService.requireOwnedForRead(consultationId, patientId);
        var records = chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, patientId)
                .eq(AiChatRecord::getSessionId, c.getSessionId())
                .orderByAsc(AiChatRecord::getCreateTime)
                .orderByAsc(AiChatRecord::getId)
                .last("LIMIT 200"));
        var out = new ArrayList<Map<String, Object>>(records.size());
        for (var r : records) {
            var m = new HashMap<String, Object>();
            m.put("role", r.getRole());
            m.put("content", r.getContent());
            m.put("createTime", r.getCreateTime());
            out.add(m);
        }
        return out;
    }
}
