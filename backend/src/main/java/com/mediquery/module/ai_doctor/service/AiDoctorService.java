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

import java.util.List;
import java.util.UUID;

/**
 * AI智能医生服务：RAG 检索增强对话、SSE流式推送、对话历史查询。
 * 流程：用户提问 → 检索疾病/药品 → 注入上下文 → 调用千问 → 缓存上下文 → 返回回答。
 */
@Service
public class AiDoctorService {
    private static final Logger log = LoggerFactory.getLogger(AiDoctorService.class);

    private final DashScopeClient dashScopeClient;
    private final KnowledgeRetrievalService knowledgeService;
    private final AiChatRecordMapper chatRecordMapper;

    public AiDoctorService(DashScopeClient dashScopeClient,
                           KnowledgeRetrievalService knowledgeService,
                           AiChatRecordMapper chatRecordMapper) {
        this.dashScopeClient = dashScopeClient;
        this.knowledgeService = knowledgeService;
        this.chatRecordMapper = chatRecordMapper;
    }

    /**
     * RAG 增强对话：检索知识库 → 注入上下文 → 调用千问 → 缓存上下文。
     * 相同 sessionId 的后续消息会复用 Redis 缓存的上下文，减少重复检索。
     */
    public String chat(Integer userId, String sessionId, String message) {
        saveRecord(userId, sessionId, "user", message);

        var knowledge = knowledgeService.retrieve(message);
        var reply = dashScopeClient.chatWithContext(sessionId, message, knowledge);

        saveRecord(userId, sessionId, "assistant", reply);
        return reply;
    }

    /**
     * SSE 流式对话（非 RAG，保持简单）。
     * 流式模式下每 30ms 推送 5 字符，模拟打字效果。
     */
    public SseEmitter chatStream(Integer userId, String message) {
        var emitter = new SseEmitter(60000L);
        var sessionId = UUID.randomUUID().toString();
        saveRecord(userId, sessionId, "user", message);
        new Thread(() -> {
            try {
                var reply = dashScopeClient.chat(message);
                saveRecord(userId, sessionId, "assistant", reply);
                for (int i = 0; i < reply.length(); i++) {
                    emitter.send(SseEmitter.event().data(reply.substring(i, Math.min(i + 5, reply.length()))));
                    Thread.sleep(30);
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE 流式输出异常", e);
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    /** 查询当前用户最近 50 条对话历史 */
    public List<AiChatRecord> getHistory(Integer userId) {
        return chatRecordMapper.selectList(new LambdaQueryWrapper<AiChatRecord>()
                .eq(AiChatRecord::getUserId, userId)
                .orderByDesc(AiChatRecord::getCreateTime)
                .last("LIMIT 50"));
    }

    /** 持久化对话记录 */
    private void saveRecord(Integer userId, String sessionId, String role, String content) {
        var record = new AiChatRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setRole(role);
        record.setContent(content);
        chatRecordMapper.insert(record);
    }
}
