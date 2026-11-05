package com.mediquery.module.ai_doctor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mediquery.integration.ai.DashScopeClient;
import com.mediquery.integration.ai.KnowledgeRetrievalService;
import com.mediquery.module.ai_doctor.entity.AiChatRecord;
import com.mediquery.module.ai_doctor.mapper.AiChatRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI智能医生服务：RAG 检索增强 + 多轮对话历史 + SSE流式。
 */
@Service
public class AiDoctorService {
    private static final Logger log = LoggerFactory.getLogger(AiDoctorService.class);

    private final DashScopeClient dashScopeClient;
    private final KnowledgeRetrievalService knowledgeService;
    private final AiChatRecordMapper chatRecordMapper;
    private final ObjectMapper objectMapper;

    public AiDoctorService(DashScopeClient dashScopeClient,
                           KnowledgeRetrievalService knowledgeService,
                           AiChatRecordMapper chatRecordMapper,
                           ObjectMapper objectMapper) {
        this.dashScopeClient = dashScopeClient;
        this.knowledgeService = knowledgeService;
        this.chatRecordMapper = chatRecordMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取同一 session 的最近 20 条对话历史，按时间正序排列（供 LLM 消费）。
     *
     * <p>必须 desc + limit 才能取到「最近」的 20 条；直接 asc + limit 取的是最早的 20 条，
     * 长会话会永远看不到新内容。取回后在内存中反转为正序。
     */
    private List<AiChatRecord> getSessionHistory(Integer userId, String sessionId) {
        var recent = chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, userId)
                .eq(AiChatRecord::getSessionId, sessionId)
                .orderByDesc(AiChatRecord::getCreateTime)
                .orderByDesc(AiChatRecord::getId)
                .last("LIMIT 20"));
        // 反转为时间正序，保持 LLM 对话上下文的自然阅读顺序
        var ordered = new ArrayList<>(recent);
        Collections.reverse(ordered);
        return ordered;
    }

    /** SSE 流式对话（RAG + 多轮） */
    public SseEmitter chatStream(Integer userId, String sessionId, String message) {
        var emitter = new SseEmitter(120000L);
        var sid = sessionId != null ? sessionId : UUID.randomUUID().toString();
        saveRecord(userId, sid, "user", message);
        var secCtx = SecurityContextHolder.getContext();  // 捕获当前安全上下文
        new Thread(() -> {
            SecurityContextHolder.setContext(secCtx);  // 传递到子线程
            try {
                // 立即发送 start 事件，提交 HTTP 响应头，防止异步分发时安全过滤器二次拦截
                emitter.send(SseEmitter.event().name("delta").data(
                        objectMapper.writeValueAsString(Map.of("type", "start", "sessionId", sid))));

                var history = getSessionHistory(userId, sid);
                var knowledge = knowledgeService.retrieve(message);
                var reply = dashScopeClient.chatWithHistory(sid, message, history, knowledge);
                saveRecord(userId, sid, "assistant", reply);
                for (int i = 0; i < reply.length(); i += 3) {
                    int end = Math.min(i + 3, reply.length());
                    var data = objectMapper.writeValueAsString(
                            Map.of("chunk", reply.substring(i, end), "sessionId", sid));
                    emitter.send(SseEmitter.event().name("delta").data(data));
                    Thread.sleep(20);
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 流式输出异常", e);
                emitter.completeWithError(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }).start();
        return emitter;
    }

    private void saveRecord(Integer userId, String sessionId, String role, String content) {
        var record = new AiChatRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setContent(content);
        chatRecordMapper.insert(record);
    }
}
